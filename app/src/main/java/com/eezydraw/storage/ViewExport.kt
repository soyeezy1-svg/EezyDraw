package com.eezydraw.storage

import android.graphics.Bitmap
import android.graphics.Color
import com.eezydraw.core.geometry.SceneSegment
import com.eezydraw.core.geometry.SurfaceChunk
import com.eezydraw.core.geometry.adaptiveGridStep
import com.eezydraw.core.geometry.buildDrawingScene
import com.eezydraw.core.geometry.buildSurfaceScene
import com.eezydraw.core.model.ObjectDisplay
import com.eezydraw.core.model.ElementBody
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.ProjectDocument
import com.eezydraw.core.model.Projection
import com.eezydraw.core.model.Shape
import com.eezydraw.core.model.ViewMode
import com.eezydraw.ui.canvas.CanvasFrame
import com.eezydraw.ui.canvas.RoomWallIndex
import com.eezydraw.ui.canvas.RoomWallPart
import com.eezydraw.ui.canvas.objectRenderPlan
import com.eezydraw.ui.canvas.objectViewMode
import com.eezydraw.ui.canvas.objectEdgeOpacity
import com.eezydraw.ui.canvas.hasXrayOverrides
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.DeflaterOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

enum class ViewExportFormat { PNG, PDF }

/** Snapshot dimensions are the canvas pixel dimensions, before the export's memory bound. */
data class ViewExportRequest(
    val document: ProjectDocument,
    val frame: CanvasFrame,
    val width: Int,
    val height: Int,
    val underlayBackground:Bitmap?=null,
) {
    init { require(width > 0 && height > 0) { "The drawing view has no exportable size." } }
}

/**
 * Reprojects the committed document using the current camera. Editor handles, selection tint and
 * panels are presentation controls and are not part of the shared drawing. PNG and PDF use the
 * same bounded, depth-tested snapshot, so they include the same dimensions and visible layers.
 */
object ViewExport {
    const val MAX_SIDE = 2048
    const val MAX_PIXELS = MAX_SIDE * MAX_SIDE

    fun write(request: ViewExportRequest, format: ViewExportFormat, output: OutputStream) {
        val bitmap = render(request)
        try {
            when (format) {
                ViewExportFormat.PNG -> if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IOException("The drawing image could not be written.")
                }
                ViewExportFormat.PDF -> writePdf(bitmap, output)
            }
        } finally { bitmap.recycle() }
    }

    /** Caller owns the returned bitmap. Rendering belongs on an I/O/background dispatcher. */
    fun render(request: ViewExportRequest): Bitmap {
        val scale = min(1.0, MAX_SIDE.toDouble() / max(request.width, request.height))
        val width = max(1, (request.width * scale).toInt())
        val height = max(1, (request.height * scale).toInt())
        val raster = Raster(request, width, height)
        val scene = com.eezydraw.core.geometry.SectionClip.drawing(buildDrawingScene(request.document,planSymbols=!request.frame.is3D),request.frame.sectionBox)
        val mode = request.frame.viewMode
        val autoWalls = request.frame.autoXrayFrontWalls || request.document.project.settings.autoXrayFrontWalls
        val surfaces = if (mode != ViewMode.WIREFRAME || autoWalls && request.frame.is3D || hasXrayOverrides(request.document))
            com.eezydraw.core.geometry.SectionClip.surfaces(buildSurfaceScene(request.document,planView=!request.frame.is3D),request.frame.sectionBox) else null
        val parts = scene.chunks.map { RoomWallPart(it.roomSide, it.bounds, it.roomCenter) } +
            surfaces?.chunks.orEmpty().map { RoomWallPart(it.roomSide, it.bounds, it.roomCenter) }
        val walls = RoomWallIndex(parts)
        fun front(side:com.eezydraw.core.model.RoomSideRef?) = walls.isFront(side,request.frame.orbit,autoWalls,request.frame.is3D)
        val meshIds = surfaces?.chunks?.filter { objectRenderPlan(mode,it.display,front(it.roomSide)).surfaces }
            ?.mapTo(HashSet()) { it.elementId } ?: emptySet()
        val annotations = request.document.elements.asSequence().filter { element ->
            !request.frame.is3D && element.body is ElementBody.Component || when ((element.body as? ElementBody.DrawingShape)?.shape) {
                is Shape.Dimension, is Shape.Text, is Shape.Leader -> true
                else -> false
            }
        }.mapTo(HashSet()) { it.id.value }
        raster.grid()
        if (surfaces != null) {
            val chunks = surfaces.chunks.filter { request.frame.levelId == null || it.levelId == null || it.levelId == request.frame.levelId }
            val opaque = chunks.filter { val plan=objectRenderPlan(mode,it.display,front(it.roomSide));plan.surfaces && !plan.transparent }
            val transparent = chunks.filter { val plan=objectRenderPlan(mode,it.display,front(it.roomSide));plan.surfaces && plan.transparent }
                .sortedByDescending { raster.depth(it) }
            (opaque + transparent).forEach { raster.surface(it, objectViewMode(mode,it.display,front(it.roomSide))) }
        }
        for (segment in scene.segments) {
            if (segment.elementId in annotations) continue
            val isFront = front(segment.roomSide)
            val plan = objectRenderPlan(mode,segment.display,isFront)
            if (plan.fullEdges || segment.elementId !in meshIds) raster.segment(segment,
                depthTest = mode != ViewMode.WIREFRAME && !plan.transparent &&
                    segment.display != ObjectDisplay.WIREFRAME && segment.display != ObjectDisplay.HIDDEN,
                opacity=objectEdgeOpacity(segment.display,isFront).toDouble())
        }
        // Dimensions and annotation strokes remain readable over filled geometry in both outputs.
        scene.segments.filter { it.elementId in annotations }.forEach {
            raster.segment(it,depthTest=false,annotation=true,
                opacity=objectEdgeOpacity(it.display,front(it.roomSide)).toDouble())
        }
        return Bitmap.createBitmap(raster.pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** Portable one-page PDF with the exact opaque view raster, without platform PDF state. */
    private fun writePdf(bitmap: Bitmap, output: OutputStream) {
        val compressed=ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { deflater ->
            val pixels=IntArray(bitmap.width)
            val row=ByteArray(bitmap.width*3)
            for(y in 0 until bitmap.height) {
                bitmap.getPixels(pixels,0,bitmap.width,0,y,bitmap.width,1)
                for(x in pixels.indices) {
                    row[x*3]=Color.red(pixels[x]).toByte()
                    row[x*3+1]=Color.green(pixels[x]).toByte()
                    row[x*3+2]=Color.blue(pixels[x]).toByte()
                }
                deflater.write(row)
            }
        }
        val image=compressed.toByteArray()
        val pageWidth=if(bitmap.width>=bitmap.height)842 else 595
        val pageHeight=if(bitmap.width>=bitmap.height)595 else 842
        val scale=min((pageWidth-36.0)/bitmap.width,(pageHeight-36.0)/bitmap.height)
        val width=bitmap.width*scale;val height=bitmap.height*scale
        val content=String.format(Locale.ROOT,"q\n%.4f 0 0 %.4f %.4f %.4f cm\n/Im0 Do\nQ\n",
            width,height,(pageWidth-width)/2,(pageHeight-height)/2).toByteArray(Charsets.US_ASCII)
        var count=0L
        val offsets=LongArray(6)
        fun bytes(value:ByteArray) {output.write(value);count+=value.size}
        fun text(value:String)=bytes(value.toByteArray(Charsets.US_ASCII))
        fun obj(number:Int,value:String) {offsets[number]=count;text("$number 0 obj\n$value\nendobj\n")}
        text("%PDF-1.4\n% EEZY DRAW current view\n")
        obj(1,"<< /Type /Catalog /Pages 2 0 R >>")
        obj(2,"<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        obj(3,"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pageWidth $pageHeight] /Resources << /XObject << /Im0 4 0 R >> >> /Contents 5 0 R >>")
        offsets[4]=count
        text("4 0 obj\n<< /Type /XObject /Subtype /Image /Width ${bitmap.width} /Height ${bitmap.height} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length ${image.size} >>\nstream\n")
        bytes(image);text("\nendstream\nendobj\n")
        offsets[5]=count;text("5 0 obj\n<< /Length ${content.size} >>\nstream\n")
        bytes(content);text("endstream\nendobj\n")
        val crossReference=count
        text("xref\n0 6\n0000000000 65535 f \n")
        for(i in 1..5)text(String.format(Locale.ROOT,"%010d 00000 n \n",offsets[i]))
        text("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$crossReference\n%%EOF\n")
    }

    private class Raster(val request: ViewExportRequest, val width: Int, val height: Int) {
        private var hiddenCoverage:BooleanArray?=null
        val pixels = IntArray(width * height) { if (request.frame.dark) Color.rgb(14, 18, 26) else Color.rgb(247, 250, 252) }
        init {request.underlayBackground?.let {require(it.width==width && it.height==height);it.getPixels(pixels,0,width,0,0,width,height)}}
        private val depths = FloatArray(width * height) { Float.POSITIVE_INFINITY }
        private val frame = request.frame
        private val projector = Projector(request, width, height)
        private val keyLight = normalized(.45, -.40, .80)
        private val fillLight = normalized(-.60, .35, .55)
        private val halfLight = normalized(keyLight.x + projector.viewDirection.x,
            keyLight.y + projector.viewDirection.y, keyLight.z + projector.viewDirection.z)

        fun depth(chunk: SurfaceChunk): Double = projector.project(Point3(
            chunk.bounds.min.x / 2 + chunk.bounds.max.x / 2,
            chunk.bounds.min.y / 2 + chunk.bounds.max.y / 2,
            chunk.bounds.min.z / 2 + chunk.bounds.max.z / 2,
        ))?.z ?: Double.POSITIVE_INFINITY

        fun surface(chunk: SurfaceChunk, mode: ViewMode) {
            val values = chunk.vertices
            val depthOnly = mode == ViewMode.HIDDEN_LINE || mode == ViewMode.SECTION
            val transparent = mode == ViewMode.XRAY || mode == ViewMode.TRANSPARENT
            for (offset in values.indices step 30) {
                val triangle = ArrayList<Vertex>(3)
                repeat(3) { index ->
                    val start = offset + index * 10
                    val point = Point3(chunk.origin.x + values[start], chunk.origin.y + values[start + 1], chunk.origin.z + values[start + 2])
                    val normal = Point3(values[start + 3].toDouble(), values[start + 4].toDouble(), values[start + 5].toDouble())
                    val facingNormal = if (dot(normal, projector.viewDirection) < 0) Point3(-normal.x,-normal.y,-normal.z) else normal
                    val shade = when (mode) {
                        ViewMode.REALISTIC -> .22 + .62 * max(0.0, dot(facingNormal,keyLight)) + .16 * max(0.0,dot(facingNormal,fillLight))
                        ViewMode.SHADED, ViewMode.SHADED_WITH_EDGES -> .36 + .64 * max(0.0,dot(facingNormal,keyLight))
                        else -> 1.0
                    }
                    val specular = if (mode == ViewMode.REALISTIC) .22 * max(0.0,dot(facingNormal,halfLight)).pow(32) else 0.0
                    val rgb = contrast(values[start + 6].toDouble(), values[start + 7].toDouble(), values[start + 8].toDouble(), surface = true)
                    triangle.add(projector.clip(point, rgb[0] * shade + specular, rgb[1] * shade + specular, rgb[2] * shade + specular,
                        values[start + 9].toDouble() * if (transparent) .28 else 1.0))
                }
                val clipped = clipPolygon(triangle)
                for (index in 1 until clipped.lastIndex) {
                    triangle(projector.screen(clipped[0]), projector.screen(clipped[index]), projector.screen(clipped[index + 1]),
                        depthOnly, transparent)
                }
            }
        }

        fun segment(segment: SceneSegment, depthTest: Boolean, annotation: Boolean = false, opacity:Double=1.0) {
            if (frame.levelId != null && segment.levelId != null && segment.levelId != frame.levelId) return
            val color = segment.colorArgb.toInt()
            val rgb = contrast(Color.red(color) / 255.0, Color.green(color) / 255.0, Color.blue(color) / 255.0)
            line(segment.a, segment.b, Color.argb((Color.alpha(color)*opacity).toInt().coerceIn(0,255), channel(rgb[0]), channel(rgb[1]), channel(rgb[2])), depthTest, annotation,segment.display==ObjectDisplay.HIDDEN)
        }

        fun grid() {
            if (!frame.showGrid) return
            val visibleHeight = if (!frame.is3D) request.height / frame.viewport.pixelsPerMetre
                else if (frame.orbit.projection == Projection.ORTHOGRAPHIC) frame.orbit.orthographicHeight
                else 2 * frame.orbit.distance * tan(com.eezydraw.core.geometry.OrbitCamera.FOV_Y / 2)
            val centerX = if (frame.is3D) frame.orbit.target.x else frame.viewport.center.x
            val centerY = if (frame.is3D) frame.orbit.target.y else frame.viewport.center.y
            val halfY = visibleHeight * if (frame.is3D) 2.0 else .5
            val halfX = halfY * request.width / request.height
            val effectiveScale = height / visibleHeight
            val minimum = max(max(Math.ulp(centerX), Math.ulp(centerY)) * 8, frame.gridSpacing)
            val step = adaptiveGridStep(minimum, effectiveScale, 24.0)
            if (!step.isFinite() || step <= 0.0 || !halfX.isFinite() || !halfY.isFinite()) return
            val color = if (frame.dark) Color.rgb(40, 47, 60) else Color.rgb(220, 226, 234)
            val xStart = floor((centerX - halfX) / step).toLong()
            val yStart = floor((centerY - halfY) / step).toLong()
            val xCount = min(256L, ceil(halfX * 2 / step).toLong() + 2).coerceAtLeast(0)
            val yCount = min(256L, ceil(halfY * 2 / step).toLong() + 2).coerceAtLeast(0)
            for (i in 0 until xCount) {
                val x = (xStart + i) * step
                if (x.isFinite()) line(Point3(x, centerY - halfY, frame.floorElevation), Point3(x, centerY + halfY, frame.floorElevation), color, false)
            }
            for (i in 0 until yCount) {
                val y = (yStart + i) * step
                if (y.isFinite()) line(Point3(centerX - halfX, y, frame.floorElevation), Point3(centerX + halfX, y, frame.floorElevation), color, false)
            }
        }

        private fun line(first: Point3, last: Point3, color: Int, depthTest: Boolean, annotation: Boolean = false, hidden:Boolean=false) {
            var a = projector.clip(first); var b = projector.clip(last)
            for (plane in 0..5) {
                val da = a.distance(plane); val db = b.distance(plane)
                if (da < 0 && db < 0) return
                if (da < 0 || db < 0) {
                    val intersection = a.mix(b, da / (da - db))
                    if (da < 0) a = intersection else b = intersection
                }
            }
            val start = projector.screen(a); val end = projector.screen(b)
            val count = ceil(max(abs(end.x - start.x), abs(end.y - start.y))).toInt().coerceIn(1, 8192)
            val radius = if (annotation) 1 else 0
            for (step in 0..count) {
                val t = step.toDouble() / count
                val x = (start.x + (end.x - start.x) * t).toInt()
                val y = (start.y + (end.y - start.y) * t).toInt()
                val z = start.z + (end.z - start.z) * t
                for (dy in -radius..radius) for (dx in -radius..radius) {
                    val px = x + dx; val py = y + dy
                    if (px !in 0 until width || py !in 0 until height) continue
                    val index = py * width + px
                    // This bias matches the surface polygon-offset/edge pass in the GLES view.
                    if (!depthTest || z <= depths[index] + .000002) {
                        // Projected top/bottom edges can coincide; faint outlines are a coverage
                        // union, so repeated edges never turn a hidden object opaque.
                        if(hidden) {
                            val coverage=hiddenCoverage ?: BooleanArray(width*height).also {hiddenCoverage=it}
                            if(coverage[index])continue
                            coverage[index]=true
                        }
                        pixels[index] = blend(pixels[index], color)
                    }
                }
            }
        }

        private fun triangle(a: Screen, b: Screen, c: Screen, depthOnly: Boolean, transparent: Boolean) {
            val area = edge(a, b, c.x, c.y)
            if (!area.isFinite() || abs(area) < 1e-9) return
            val left = floor(min(a.x, min(b.x, c.x))).toInt().coerceIn(0, width - 1)
            val right = ceil(max(a.x, max(b.x, c.x))).toInt().coerceIn(0, width - 1)
            val top = floor(min(a.y, min(b.y, c.y))).toInt().coerceIn(0, height - 1)
            val bottom = ceil(max(a.y, max(b.y, c.y))).toInt().coerceIn(0, height - 1)
            for (y in top..bottom) for (x in left..right) {
                val u = edge(b, c, x + .5, y + .5) / area
                val v = edge(c, a, x + .5, y + .5) / area
                val w = 1 - u - v
                if (min(u, min(v, w)) < -1e-9) continue
                val z = u * a.z + v * b.z + w * c.z
                val index = y * width + x
                if (!transparent && z > depths[index] + 1e-7) continue
                if (!transparent) depths[index] = z.toFloat()
                if (depthOnly) continue
                val divisor = u * a.inverseW + v * b.inverseW + w * c.inverseW
                if (divisor <= 0 || !divisor.isFinite()) continue
                fun value(f: (Screen) -> Double) = (u * f(a) * a.inverseW + v * f(b) * b.inverseW + w * f(c) * c.inverseW) / divisor
                val color = Color.argb(channel(value { it.alpha }), channel(value { it.r }), channel(value { it.g }), channel(value { it.b }))
                pixels[index] = if (transparent || Color.alpha(color) < 255) blend(pixels[index], color) else color
            }
        }

        private fun contrast(r: Double, g: Double, b: Double, surface: Boolean = false): DoubleArray {
            val luminance = .2126 * r + .7152 * g + .0722 * b
            val addition = if (frame.dark && luminance < (if (surface) .08 else .12))
                (if (surface) .14 else .74) - luminance else 0.0
            return doubleArrayOf((r + addition).coerceIn(0.0, 1.0), (g + addition).coerceIn(0.0, 1.0), (b + addition).coerceIn(0.0, 1.0))
        }
    }

    private class Projector(val request: ViewExportRequest, val width: Int, val height: Int) {
        private val camera = request.frame.orbit
        private val cy = cos(camera.yaw); private val sy = sin(camera.yaw)
        private val cp = cos(camera.pitch); private val sp = sin(camera.pitch)
        private val near = max(1e-5, camera.distance * .0001)
        private val far = max(camera.distance * 100, camera.orthographicHeight * 100)
        val viewDirection = Point3(cp * cy,cp * sy,sp)

        fun project(point: Point3): Screen? {
            val value = clip(point)
            return if (value.w <= 0) null else screen(value)
        }

        fun clip(point: Point3, r: Double = 0.0, g: Double = 0.0, b: Double = 0.0, alpha: Double = 1.0): Vertex {
            if (!request.frame.is3D) {
                val viewport = request.frame.viewport
                val dx=point.x-viewport.center.x;val dy=point.y-viewport.center.y
                val x=(cos(viewport.rotation)*dx-sin(viewport.rotation)*dy)*viewport.pixelsPerMetre*2/request.width
                val y=(sin(viewport.rotation)*dx+cos(viewport.rotation)*dy)*viewport.pixelsPerMetre*2/request.height
                return Vertex(x, y, 0.0, 1.0, r, g, b, alpha)
            }
            val x = point.x - camera.target.x; val y = point.y - camera.target.y; val z = point.z - camera.target.z
            val right = -sy * x + cy * y
            val up = -sp * cy * x - sp * sy * y + cp * z
            val cameraZ = cp * cy * x + cp * sy * y + sp * z - camera.distance
            val aspect = request.width.toDouble() / request.height
            return if (camera.projection == Projection.PERSPECTIVE) {
                val scale = 1 / tan(com.eezydraw.core.geometry.OrbitCamera.FOV_Y / 2)
                Vertex(right * scale / aspect, up * scale,
                    -(far + near) / (far - near) * cameraZ - 2 * far * near / (far - near), -cameraZ, r, g, b, alpha)
            } else Vertex(right * 2 / camera.orthographicHeight / aspect, up * 2 / camera.orthographicHeight,
                -2 * cameraZ / (far - near) - (far + near) / (far - near), 1.0, r, g, b, alpha)
        }

        fun screen(vertex: Vertex) = Screen((vertex.x / vertex.w + 1) * width / 2,
            (1 - vertex.y / vertex.w) * height / 2, vertex.z / vertex.w, 1 / vertex.w,
            vertex.r, vertex.g, vertex.b, vertex.alpha)
    }

    private data class Vertex(val x: Double, val y: Double, val z: Double, val w: Double,
        val r: Double, val g: Double, val b: Double, val alpha: Double) {
        fun distance(plane: Int): Double = when (plane) { 0 -> w + x; 1 -> w - x; 2 -> w + y; 3 -> w - y; 4 -> w + z; else -> w - z }
        fun mix(other: Vertex, t: Double) = Vertex(x + (other.x - x) * t, y + (other.y - y) * t,
            z + (other.z - z) * t, w + (other.w - w) * t, r + (other.r - r) * t,
            g + (other.g - g) * t, b + (other.b - b) * t, alpha + (other.alpha - alpha) * t)
    }

    private data class Screen(val x: Double, val y: Double, val z: Double, val inverseW: Double,
        val r: Double, val g: Double, val b: Double, val alpha: Double)

    private fun clipPolygon(input: List<Vertex>): List<Vertex> {
        var result = input
        for (plane in 0..5) {
            if (result.isEmpty()) return result
            val next = ArrayList<Vertex>(result.size + 1)
            var previous = result.last(); var previousDistance = previous.distance(plane)
            for (current in result) {
                val distance = current.distance(plane)
                if ((distance >= 0) != (previousDistance >= 0)) next.add(previous.mix(current, previousDistance / (previousDistance - distance)))
                if (distance >= 0) next.add(current)
                previous = current; previousDistance = distance
            }
            result = next
        }
        return result
    }

    private fun edge(a: Screen, b: Screen, x: Double, y: Double) = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
    private fun dot(a:Point3,b:Point3) = a.x*b.x+a.y*b.y+a.z*b.z
    private fun normalized(x:Double,y:Double,z:Double):Point3 {
        val length=sqrt(x*x+y*y+z*z)
        return if (length>1e-12) Point3(x/length,y/length,z/length) else Point3(z=1.0)
    }
    private fun channel(value: Double) = (value.coerceIn(0.0, 1.0) * 255 + .5).toInt()
    private fun blend(background: Int, foreground: Int): Int {
        val alpha = Color.alpha(foreground) / 255.0
        if (alpha >= 1.0) return foreground
        return Color.rgb((Color.red(foreground) * alpha + Color.red(background) * (1 - alpha)).toInt(),
            (Color.green(foreground) * alpha + Color.green(background) * (1 - alpha)).toInt(),
            (Color.blue(foreground) * alpha + Color.blue(background) * (1 - alpha)).toInt())
    }
}
