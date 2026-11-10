package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

private const val SURFACE_STEPS = 64
private const val SURFACE_BAND_STEPS = 24
private const val SURFACE_TILE = 128.0
private const val MAX_SURFACE_TRIANGLES = 500_000
private const val MAX_SURFACE_CHUNKS = 16_384
private const val CHUNK_TRIANGLES = 256
private const val MAX_POLYGON_WORK = 8_000_000L
private const val SURFACE_TAU = PI * 2

/**
 * Tessellates actual faces for filled/depth/lighting modes, independently of annotation edges.
 * Open shapes remain line geometry. Polygon caps use even/odd scanline trapezoids, preserving
 * concavities and holes without requiring ring winding. Call off the main thread.
 */
fun buildSurfaceScene(document: ProjectDocument, elementIds: Set<ElementId>? = null, planView:Boolean=false, architecturalCuts:Boolean=true, checkCancelled: () -> Unit = {}): SurfaceScene {
    val layers = document.layers.associateBy { it.id }
    val systems = document.systems.associateBy { it.id }
    val materials = document.materials.associateBy { it.id }
    val library = document.libraryItems.associateBy { it.id }
    val collector = SurfaceCollector()
    val wallFrames=wallFrames(document).associateBy {it.host.id to it.roomSide}
    val allWallCuts=wallServiceCuts(document).groupBy {it.wallId to it.roomSide}
    val architecture=document.elements.mapNotNull {e->(e.body as? ElementBody.Component)?.let {b->if(library[b.libraryItemId]?.openingKind()!=null)e.id else null}}.toSet()
    val wallCuts=allWallCuts.mapValues {(key,cuts)->cuts.filter {document.project.settings.wallServices.showCuts || architecturalCuts && it.sourceId in architecture}.map {cut->
        if(planView && cut.sourceId in architecture) {
            val l=cut.outline.minOf {it.x};val r=cut.outline.maxOf {it.x};val h=wallFrames[key]?.height ?: 1.0
            cut.copy(outline=listOf(Point2(l,0.0),Point2(r,0.0),Point2(r,h),Point2(l,h)))
        }else cut
    }}
    val sectionHeight=document.project.settings.wallServices.sectionHeight.takeIf {document.project.settings.viewMode==ViewMode.SECTION}
    val presentation = ScenePresentation(document,planView)
    for (element in document.elements) {
        checkCancelled()
        if (elementIds != null && element.id !in elementIds) continue
        val layer = layers[element.layerId]
        val system = element.systemId?.let(systems::get)
        val display=presentation.display(element)
        if(display==ObjectDisplay.HIDDEN)continue
        val component = element.body as? ElementBody.Component
        val definition = component?.let { library[it.libraryItemId] }
        val material = (element.materialId ?: definition?.materialId)?.let(materials::get)
        val colour = renderColour(element,layer,system,material,definition)
        val transform = SurfaceTransform(element.transform)
        val partSide=if(element.body is ElementBody.Wall && !(ELEMENT_DISPLAY_KEY in element.properties && element.objectDisplay==ObjectDisplay.NORMAL))presentation.wallSide(element) else null
        val roomCenter=presentation.roomCenter(element)
        fun emitter(mode:ObjectDisplay,side:RoomSideRef?,surfaceTransform:SurfaceTransform=transform):SurfaceEmitter? {
            if(mode==ObjectDisplay.HIDDEN || mode==ObjectDisplay.WIREFRAME)return null
            var emitted=0
            return SurfaceEmitter(surfaceTransform) {a,b,c,na,nb,nc->
                if(emitted++ % 256 == 0)checkCancelled()
                val plane=sectionHeight?.plus(document.levels.find {it.id==element.levelId}?.elevation ?: 0.0)
                clipSurfaceAtHeight(a,b,c,na,nb,nc,plane) {pa,pb,pc,pna,pnb,pnc->collector.triangle(pa,pb,pc,pna,pnb,pnc,element.id.value,element.levelId?.value,colour,mode,side,roomCenter)}
            }
        }
        val emit=emitter(display,partSide)
        if(emit==null && element.body !is ElementBody.Room)continue
        when (val body = element.body) {
            is ElementBody.DrawingShape -> surfaceShape(body.shape, requireNotNull(emit))
            is ElementBody.Room -> {
                emit?.let {surfaceCap(body.boundary,0.0,true,it)}
                (listOf(body.boundary.outer)+body.boundary.holes).forEachIndexed {ringIndex,vertices->
                    val ccw=surfaceArea(vertices)>0.0
                    val forward=if(ringIndex==0)ccw else !ccw
                    for(i in vertices.indices) {
                        val side=RoomSideRef(element.id,i,if(ringIndex==0)null else ringIndex-1)
                        if(presentation.linkedWall(element,side)!=null)continue
                        val output=emitter(presentation.sideDisplay(element,side),side) ?: continue
                        val frame=wallFrames[element.id to side]
                        if(frame!=null && (allWallCuts[element.id to side].orEmpty().isNotEmpty() || sectionHeight!=null)) {
                            wallCutMesh(frame,wallCuts[element.id to side].orEmpty(),sectionHeight)?.let {surfaceShape(it,output)}
                            continue
                        }
                        val a=vertices[i];val b=vertices[(i+1)%vertices.size]
                        val p=Point3(a.x,a.y,0.0);val q=Point3(b.x,b.y,0.0)
                        val r=Point3(b.x,b.y,body.ceilingHeight);val t=Point3(a.x,a.y,body.ceilingHeight)
                        if(forward)output.quad(p,q,r,t)else output.quad(t,r,q,p)
                    }
                }
            }
            is ElementBody.Slab -> if(document.project.settings.floorServices.showChannels && (document.project.settings.viewMode in setOf(ViewMode.XRAY,ViewMode.SECTION) || display==ObjectDisplay.XRAY) && floorServiceCuts(document,element).isNotEmpty())floorCutMesh(document,element)?.let {surfaceShape(it,requireNotNull(emit))}else surfaceExtrusion(body.boundary, -body.thickness, 0.0, requireNotNull(emit))
            is ElementBody.Wall -> {
                val frame=wallFrames[element.id to null]
                if(frame!=null && (allWallCuts[element.id to null].orEmpty().isNotEmpty() || sectionHeight!=null))wallCutMesh(frame,wallCuts[element.id to null].orEmpty(),sectionHeight)?.let {surfaceShape(it,requireNotNull(emit))}
                else surfaceWall(body,requireNotNull(emit))
            }
            is ElementBody.Route -> {
                val mount=body.wallService
                val frame=mount?.let {wallFrames[it.wallId to it.roomSide]}
                val normal=frame?.normal?.let {n->measurementToLocal(element.transform,Point3(element.transform.translation.x+n.x,element.transform.translation.y+n.y,element.transform.translation.z))}
                surfaceRoute(body,requireNotNull(emit),normal)
            }
            is ElementBody.Component -> if(definition?.openingKind()!=null) {
                openingGeometry(definition.openingKind()!!,body.size,body.wallService?.options?.swing ?: DoorSwing(),planView).forEach {surfaceShape(it,requireNotNull(emit))}
            } else if (definition == null || definition.geometry.isEmpty()) {
                surfaceBox(body.size, requireNotNull(emit))
            } else {
                val scale = Scale3(element.transform.scale.x * body.size.width / (definition.geometrySize ?: definition.defaultSize).width,
                    element.transform.scale.y * body.size.depth / (definition.geometrySize ?: definition.defaultSize).depth,
                    element.transform.scale.z * body.size.height / (definition.geometrySize ?: definition.defaultSize).height)
                val instance=requireNotNull(emitter(display,partSide,SurfaceTransform(element.transform.copy(scale=scale))))
                definition.geometry.forEach { surfaceShape(it, instance) }
            }
        }
    }
    val byId=document.elements.associateBy {it.id.value}
    return collector.finish().let {scene->scene.copy(chunks=scene.chunks.map {chunk->
        val e=byId.getValue(chunk.elementId)
        chunk.copy(depthBias=if(e.body is ElementBody.Room)2f else 1f,material=renderMaterial(e),lodAllowed=e.body is ElementBody.Component && (e.body as ElementBody.Component).wallService==null && !e.isCeiling)
    })}
}

private fun surfaceShape(shape: Shape, emit: SurfaceEmitter) {
    when (shape) {
        is Shape.Circle -> surfaceCap(Polygon2(surfaceCircle(shape.radius, shape.radius)), 0.0, false, emit)
        is Shape.Ellipse -> surfaceCap(Polygon2(surfaceCircle(shape.radiusX, shape.radiusY)), 0.0, false, emit)
        is Shape.Polygon -> surfaceCap(shape.boundary, 0.0, false, emit)
        is Shape.Hatch -> surfaceCap(shape.boundary, 0.0, false, emit)
        is Shape.Polyline -> if (shape.closed && shape.points.all { it.z == shape.points[0].z }) {
            surfaceCap(Polygon2(shape.points.map { Point2(it.x, it.y) }), shape.points[0].z, false, emit)
        }
        is Shape.Box -> surfaceBox(shape.size, emit)
        is Shape.Sphere -> surfaceSphere(shape.radius, emit)
        is Shape.Cylinder -> surfaceCone(shape.radius, shape.radius, shape.height, emit)
        is Shape.Cone -> surfaceCone(shape.baseRadius, shape.topRadius, shape.height, emit)
        is Shape.Pyramid -> surfacePyramid(shape, emit)
        is Shape.Mesh -> for (index in shape.triangles.indices step 3) {
            emit.triangle(shape.vertices[shape.triangles[index]], shape.vertices[shape.triangles[index + 1]],
                shape.vertices[shape.triangles[index + 2]])
        }
        is Shape.Extrusion -> surfaceExtrusion(shape.profile, 0.0, shape.distance, emit)
        is Shape.Revolution -> surfaceRevolution(shape, emit)
        is Shape.Line, is Shape.Arc, is Shape.Spline, is Shape.Text, is Shape.Dimension,
        is Shape.Leader, is Shape.RevisionCloud -> Unit
    }
}

private fun surfaceCircle(rx: Double, ry: Double) = List(SURFACE_STEPS) { i ->
    val angle = SURFACE_TAU * i / SURFACE_STEPS
    Point2(rx * cos(angle), ry * sin(angle))
}

/** Pairing intersections by parity handles concave outlines and every hole independent of winding. */
private fun surfaceCap(boundary: Polygon2, z: Double, reverse: Boolean, emit: SurfaceEmitter) {
    val rings = listOf(boundary.outer) + boundary.holes
    val edges = rings.flatMap { vertices -> vertices.indices.map { i -> vertices[i] to vertices[(i + 1) % vertices.size] } }
    val heights = rings.flatten().map { it.y }.distinct().sorted()
    require(edges.size.toLong() * heights.size <= MAX_POLYGON_WORK) { "Surface polygon exceeds the tessellation work limit." }
    data class Crossing(val middle: Double, val bottom: Double, val top: Double)
    for ((bottom, top) in heights.zipWithNext()) {
        if (bottom == top) continue
        val middle = bottom + (top - bottom) / 2
        if (middle == bottom || middle == top) continue
        val crossing = edges.mapNotNull { (a, b) ->
            if (middle <= minOf(a.y, b.y) || middle >= maxOf(a.y, b.y)) null else {
                fun x(y: Double) = a.x + (b.x - a.x) * ((y - a.y) / (b.y - a.y))
                Crossing(x(middle), x(bottom), x(top))
            }
        }.sortedBy { it.middle }
        require(crossing.size % 2 == 0) { "Surface polygon has an invalid boundary." }
        for (index in crossing.indices step 2) {
            val left = crossing[index]; val right = crossing[index + 1]
            val a = Point3(left.bottom, bottom, z); val b = Point3(right.bottom, bottom, z)
            val c = Point3(right.top, top, z); val d = Point3(left.top, top, z)
            if (reverse) emit.quad(d, c, b, a) else emit.quad(a, b, c, d)
        }
    }
}

private fun surfaceExtrusion(boundary: Polygon2, first: Double, second: Double, emit: SurfaceEmitter) {
    val bottom = minOf(first, second); val top = maxOf(first, second)
    surfaceCap(boundary, bottom, true, emit)
    surfaceCap(boundary, top, false, emit)
    (listOf(boundary.outer) + boundary.holes).forEachIndexed { ringIndex, vertices ->
        val ccw = surfaceArea(vertices) > 0.0
        val forward = if (ringIndex == 0) ccw else !ccw
        for (i in vertices.indices) {
            val a = vertices[i]; val b = vertices[(i + 1) % vertices.size]
            val p = Point3(a.x, a.y, bottom); val q = Point3(b.x, b.y, bottom)
            val r = Point3(b.x, b.y, top); val s = Point3(a.x, a.y, top)
            if (forward) emit.quad(p, q, r, s) else emit.quad(s, r, q, p)
        }
    }
}

private fun surfaceBox(size: Size3, emit: SurfaceEmitter) = surfaceExtrusion(Polygon2(listOf(
    Point2(0.0, 0.0), Point2(size.width, 0.0), Point2(size.width, size.depth), Point2(0.0, size.depth),
)), 0.0, size.height, emit)

private fun surfaceWall(body: ElementBody.Wall, emit: SurfaceEmitter) {
    body.joinProfile?.let {surfaceExtrusion(it,0.0,body.height,emit);return}
    val dx = body.end.x - body.start.x; val dy = body.end.y - body.start.y
    val length = hypot(dx, dy)
    require(length.isFinite() && length > 0.0) { "Wall face exceeds finite coordinate limits." }
    val nx = -dy / length; val ny = dx / length
    val left = when (body.reference) {
        WallReference.CENTERLINE -> body.thickness / 2
        WallReference.LEFT_FINISH_FACE -> 0.0
        WallReference.RIGHT_FINISH_FACE -> body.thickness
    }
    fun offset(point: Point2, distance: Double) = Point2(point.x + nx * distance, point.y + ny * distance)
    surfaceExtrusion(Polygon2(listOf(offset(body.start, left), offset(body.end, left),
        offset(body.end, left - body.thickness), offset(body.start, left - body.thickness))), 0.0, body.height, emit)
}

private fun surfaceSphere(radius: Double, emit: SurfaceEmitter) {
    fun normal(longitude: Double, latitude: Double) = when (latitude) {
        -PI / 2 -> Point3(z = -1.0)
        PI / 2 -> Point3(z = 1.0)
        else -> Point3(cos(latitude) * cos(longitude), cos(latitude) * sin(longitude), sin(latitude))
    }
    fun position(normal: Point3) = Point3(normal.x * radius, normal.y * radius, normal.z * radius)
    for (band in 0 until SURFACE_BAND_STEPS) {
        val lower = -PI / 2 + PI * band / SURFACE_BAND_STEPS
        val upper = -PI / 2 + PI * (band + 1) / SURFACE_BAND_STEPS
        for (step in 0 until SURFACE_STEPS) {
            val a = SURFACE_TAU * step / SURFACE_STEPS; val b = SURFACE_TAU * (step + 1) / SURFACE_STEPS
            val na = normal(a, lower); val nb = normal(b, lower)
            val nc = normal(b, upper); val nd = normal(a, upper)
            emit.triangle(position(na), position(nb), position(nc), na, nb, nc)
            emit.triangle(position(na), position(nc), position(nd), na, nc, nd)
        }
    }
}

private fun surfaceCone(base: Double, top: Double, height: Double, emit: SurfaceEmitter) {
    surfaceCap(Polygon2(surfaceCircle(base, base)), 0.0, true, emit)
    if (top > 0.0) surfaceCap(Polygon2(surfaceCircle(top, top)), height, false, emit)
    for (i in 0 until SURFACE_STEPS) {
        val a = SURFACE_TAU * i / SURFACE_STEPS; val b = SURFACE_TAU * (i + 1) / SURFACE_STEPS
        val pa = Point3(base * cos(a), base * sin(a)); val pb = Point3(base * cos(b), base * sin(b))
        val pc = Point3(top * cos(b), top * sin(b), height); val pd = Point3(top * cos(a), top * sin(a), height)
        val na = unit(Point3(cos(a), sin(a), (base - top) / height))
        val nb = unit(Point3(cos(b), sin(b), (base - top) / height))
        emit.triangle(pa, pb, pc, na, nb, nb); emit.triangle(pa, pc, pd, na, nb, na)
    }
}

private fun surfacePyramid(shape: Shape.Pyramid, emit: SurfaceEmitter) {
    // A pyramid with holes has no unambiguous apex topology; preserve the void in its base.
    surfaceCap(shape.base, 0.0, shape.apex.z >= 0.0, emit)
    (listOf(shape.base.outer) + shape.base.holes).forEachIndexed { ringIndex, vertices ->
        val forward = (surfaceArea(vertices) > 0.0) xor (ringIndex > 0) xor (shape.apex.z < 0.0)
        for (i in vertices.indices) {
            val a = vertices[i]; val b = vertices[(i + 1) % vertices.size]
            if (forward) emit.triangle(Point3(a.x, a.y), Point3(b.x, b.y), shape.apex)
            else emit.triangle(Point3(b.x, b.y), Point3(a.x, a.y), shape.apex)
        }
    }
}

private fun surfaceRevolution(shape: Shape.Revolution, emit: SurfaceEmitter) {
    val sweep = shape.sweepRadians.coerceIn(-SURFACE_TAU, SURFACE_TAU)
    val count = ceil(abs(sweep) / SURFACE_TAU * SURFACE_STEPS).toInt().coerceIn(2, SURFACE_STEPS)
    val closed = abs(sweep) == SURFACE_TAU
    val rings = listOf(shape.profile.outer) + shape.profile.holes
    for ((ringIndex, original) in rings.withIndex()) {
        val vertices = if ((surfaceArea(original) > 0.0) == (ringIndex == 0)) original else original.reversed()
        for (index in vertices.indices) {
            val p = vertices[index]; val q = vertices[(index + 1) % vertices.size]
            for (i in 0 until count) {
                val a = sweep * i / count; val b = sweep * (i + 1) / count
                fun at(point: Point2, angle: Double) = Point3(point.x * cos(angle), point.x * sin(angle), point.y)
                if (sweep > 0.0) emit.quad(at(p, a), at(p, b), at(q, b), at(q, a))
                else emit.quad(at(q, a), at(q, b), at(p, b), at(p, a))
            }
        }
    }
    if (!closed) {
        // Profile caps are triangulated in radius/height coordinates, then mapped to each end.
        for ((angle, reverse) in listOf(0.0 to true, sweep to false)) {
            val mapping = emit.mapped { p -> Point3(p.x * cos(angle), p.x * sin(angle), p.y) }
            surfaceCap(shape.profile, 0.0, reverse xor (sweep < 0.0), mapping)
        }
    }
}

private fun surfaceRoute(body: ElementBody.Route, emit: SurfaceEmitter,wallNormal:Point3?=null) {
    for ((a, b) in (body.fabricationPath ?: body.path).zipWithNext()) {
        val direction = minus(b, a)
        val length = magnitude(direction)
        if (length == 0.0) continue
        val axis = times(direction, 1.0 / length)
        val helper = wallNormal ?: if (abs(axis.z) < 0.9) Point3(z = 1.0) else Point3(y = 1.0)
        val right = unit(cross(helper, axis)); val up = unit(cross(axis, right))
        val mapped = emit.mapped { p -> plus(a, plus(times(right, p.x), plus(times(up, p.y), times(axis, p.z)))) }
        when (val section = body.section) {
            is RouteSection.Circular -> surfaceCone(section.diameter / 2, section.diameter / 2, length, mapped)
            is RouteSection.Rectangular -> {
                val outline = Polygon2(listOf(Point2(-section.width / 2, -section.height / 2),
                    Point2(section.width / 2, -section.height / 2), Point2(section.width / 2, section.height / 2),
                    Point2(-section.width / 2, section.height / 2)))
                surfaceExtrusion(outline, 0.0, length, mapped)
            }
        }
    }
}

private typealias WorldTriangle = (Point3, Point3, Point3, Point3, Point3, Point3) -> Unit

private class SurfaceEmitter(
    private val transform: SurfaceTransform,
    private val mapping: ((Point3) -> Point3)? = null,
    private val sink: WorldTriangle,
) {
    fun triangle(a: Point3, b: Point3, c: Point3, na: Point3? = null, nb: Point3? = null, nc: Point3? = null) {
        val pa = mapping?.invoke(a) ?: a; val pb = mapping?.invoke(b) ?: b; val pc = mapping?.invoke(c) ?: c
        val cross = cross(minus(pb, pa), minus(pc, pa))
        if (magnitude(cross) == 0.0) return
        // Arbitrary point mapping is used for profile/route orientation; recompute its flat normal.
        val flat = unit(cross)
        val normalA = transform.normal(if (mapping == null) na ?: flat else flat)
        val normalB = transform.normal(if (mapping == null) nb ?: flat else flat)
        val normalC = transform.normal(if (mapping == null) nc ?: flat else flat)
        val wa = transform.apply(pa); val wb = transform.apply(pb); val wc = transform.apply(pc)
        if (transform.reflected) sink(wa, wc, wb, normalA, normalC, normalB) else sink(wa, wb, wc, normalA, normalB, normalC)
    }

    fun quad(a: Point3, b: Point3, c: Point3, d: Point3) {
        triangle(a, b, c); triangle(a, c, d)
    }

    fun mapped(next: (Point3) -> Point3) = SurfaceEmitter(transform,
        mapping = { p -> mapping?.invoke(next(p)) ?: next(p) }, sink = sink)
}

private class SurfaceTransform(private val transform: Transform3) {
    private val cx = cos(transform.rotation.x); private val sx = sin(transform.rotation.x)
    private val cy = cos(transform.rotation.y); private val sy = sin(transform.rotation.y)
    private val cz = cos(transform.rotation.z); private val sz = sin(transform.rotation.z)
    val reflected = (transform.scale.x < 0.0) xor (transform.scale.y < 0.0) xor (transform.scale.z < 0.0)

    fun apply(point: Point3): Point3 = plus(rotate(Point3(point.x * transform.scale.x,
        point.y * transform.scale.y, point.z * transform.scale.z)), transform.translation)

    fun normal(normal: Point3): Point3 = unit(rotate(Point3(normal.x / transform.scale.x,
        normal.y / transform.scale.y, normal.z / transform.scale.z)))

    private fun rotate(p: Point3): Point3 {
        val x = cz * p.x - sz * p.y; val y = sz * p.x + cz * p.y
        val xx = cy * x + sy * p.z; val z = -sy * x + cy * p.z
        return Point3(xx, cx * y - sx * z, sx * y + cx * z)
    }
}

private data class SurfaceKey(val x:Double,val y:Double,val z:Double,val id:String,val level:String?,
    val display:ObjectDisplay,val roomSide:RoomSideRef?,val roomCenter:Point3?)

private class SurfaceCollector {
    private val active = linkedMapOf<SurfaceKey, SurfaceBatch>()
    private val complete = ArrayList<SurfaceChunk>()
    private var triangleCount = 0
    private var bounds: Bounds3? = null

    fun triangle(a: Point3, b: Point3, c: Point3, na: Point3, nb: Point3, nc: Point3, id:String,level:String?,colour:Long,display:ObjectDisplay=ObjectDisplay.NORMAL,roomSide:RoomSideRef?=null,roomCenter:Point3?=null) {
        val low = Point3(minOf(a.x, b.x, c.x), minOf(a.y, b.y, c.y), minOf(a.z, b.z, c.z))
        val high = Point3(maxOf(a.x, b.x, c.x), maxOf(a.y, b.y, c.y), maxOf(a.z, b.z, c.z))
        val span = maxOf(high.x - low.x, high.y - low.y, high.z - low.z)
        require(span.isFinite() && span <= 64_000_000.0) { "Surface face exceeds the supported coordinate span." }
        if (span > SURFACE_TILE) {
            val ab = magnitude(minus(a, b)); val bc = magnitude(minus(b, c)); val ca = magnitude(minus(c, a))
            when (maxOf(ab, bc, ca)) {
                ab -> { val mid = midpoint(a, b); val normal = unit(plus(na, nb))
                    triangle(a, mid, c, na, normal, nc, id,level,colour,display,roomSide,roomCenter); triangle(mid, b, c, normal, nb, nc, id,level,colour,display,roomSide,roomCenter) }
                bc -> { val mid = midpoint(b, c); val normal = unit(plus(nb, nc))
                    triangle(a, b, mid, na, nb, normal, id,level,colour,display,roomSide,roomCenter); triangle(a, mid, c, na, normal, nc, id,level,colour,display,roomSide,roomCenter) }
                else -> { val mid = midpoint(c, a); val normal = unit(plus(nc, na))
                    triangle(a, b, mid, na, nb, normal, id,level,colour,display,roomSide,roomCenter); triangle(mid, b, c, normal, nb, nc, id,level,colour,display,roomSide,roomCenter) }
            }
            return
        }
        require(triangleCount++ < MAX_SURFACE_TRIANGLES) { "Drawing exceeds the $MAX_SURFACE_TRIANGLES surface triangle limit." }
        val centre = Point3(low.x + (high.x - low.x) / 2, low.y + (high.y - low.y) / 2, low.z + (high.z - low.z) / 2)
        val key = SurfaceKey(floor(centre.x / SURFACE_TILE) * SURFACE_TILE,
            floor(centre.y / SURFACE_TILE) * SURFACE_TILE, floor(centre.z / SURFACE_TILE) * SURFACE_TILE,id,level,display,roomSide,roomCenter)
        var batch = active[key]
        if (batch == null) {
            require(active.size + complete.size < MAX_SURFACE_CHUNKS) { "Drawing exceeds the $MAX_SURFACE_CHUNKS surface batch limit." }
            batch = SurfaceBatch(a,id,level,display,roomSide,roomCenter)
            active[key] = batch
        } else if (batch.size == CHUNK_TRIANGLES) {
            complete.add(batch.finish())
            require(active.size + complete.size < MAX_SURFACE_CHUNKS) { "Drawing exceeds the $MAX_SURFACE_CHUNKS surface batch limit." }
            batch = SurfaceBatch(a,id,level,display,roomSide,roomCenter)
            active[key] = batch
        }
        batch.add(a, na, colour); batch.add(b, nb, colour); batch.add(c, nc, colour)
        val triangleBounds = Bounds3(low, high)
        bounds = bounds?.let { unionBounds(it, triangleBounds) } ?: triangleBounds
    }

    fun finish() = SurfaceScene(complete + active.values.map { it.finish() }, bounds ?: Bounds3(Point3(), Point3()))
}

private class SurfaceBatch(private val origin:Point3,private val id:String,private val level:String?,
    private val display:ObjectDisplay,private val roomSide:RoomSideRef?,private val roomCenter:Point3?) {
    private val data = FloatArray(CHUNK_TRIANGLES * 30)
    private var count = 0
    private var bounds: Bounds3? = null
    val size: Int get() = count / 30

    fun add(point: Point3, normal: Point3, colour: Long) {
        val values = floatArrayOf((point.x - origin.x).toFloat(), (point.y - origin.y).toFloat(), (point.z - origin.z).toFloat(),
            normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat(),
            ((colour shr 16) and 255).toFloat() / 255, ((colour shr 8) and 255).toFloat() / 255,
            (colour and 255).toFloat() / 255, ((colour shr 24) and 255).toFloat() / 255)
        require(values.all { it.isFinite() }) { "Surface geometry exceeds finite GPU limits." }
        values.copyInto(data, count); count += values.size
        val pointBounds = Bounds3(point, point)
        bounds = bounds?.let { unionBounds(it, pointBounds) } ?: pointBounds
    }

    fun finish() = SurfaceChunk(origin,requireNotNull(bounds),level,data.copyOf(count),id,display,roomSide,roomCenter)
}

private fun surfaceArea(points: List<Point2>): Double {
    val origin = points.first()
    return points.indices.sumOf { i ->
        val a = points[i]; val b = points[(i + 1) % points.size]
        (a.x - origin.x) * (b.y - origin.y) - (b.x - origin.x) * (a.y - origin.y)
    } / 2
}

private fun plus(a: Point3, b: Point3) = Point3(a.x + b.x, a.y + b.y, a.z + b.z)
private fun minus(a: Point3, b: Point3) = Point3(a.x - b.x, a.y - b.y, a.z - b.z)
private fun times(a: Point3, factor: Double) = Point3(a.x * factor, a.y * factor, a.z * factor)
private fun midpoint(a: Point3, b: Point3) = Point3(a.x + (b.x - a.x) / 2, a.y + (b.y - a.y) / 2, a.z + (b.z - a.z) / 2)
private fun cross(a: Point3, b: Point3) = Point3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
private fun magnitude(point: Point3) = hypot(hypot(point.x, point.y), point.z)
private fun unit(point: Point3): Point3 {
    val length = magnitude(point)
    require(length.isFinite() && length > 0.0) { "Surface normal is degenerate." }
    return times(point, 1.0 / length)
}

/** Clip other service faces to the same AFFL section plane as the capped wall mesh. */
private fun clipSurfaceAtHeight(a:Point3,b:Point3,c:Point3,na:Point3,nb:Point3,nc:Point3,height:Double?,sink:WorldTriangle) {
    if(height==null){sink(a,b,c,na,nb,nc);return}
    data class Vertex(val p:Point3,val n:Point3)
    val input=listOf(Vertex(a,na),Vertex(b,nb),Vertex(c,nc));val output=mutableListOf<Vertex>()
    for(i in input.indices) {
        val x=input[i];val y=input[(i+1)%input.size];val inside=x.p.z<=height;val next=y.p.z<=height
        if(inside)output+=x
        if(inside!=next) {
            val t=(height-x.p.z)/(y.p.z-x.p.z)
            fun mix(a:Point3,b:Point3)=Point3(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t,a.z+(b.z-a.z)*t)
            output+=Vertex(mix(x.p,y.p),mix(x.n,y.n))
        }
    }
    for(i in 1 until output.lastIndex)sink(output[0].p,output[i].p,output[i+1].p,output[0].n,output[i].n,output[i+1].n)
}
