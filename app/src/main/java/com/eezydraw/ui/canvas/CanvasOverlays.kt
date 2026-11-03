package com.eezydraw.ui.canvas

import android.graphics.Paint
import androidx.core.graphics.withRotation
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.MaterialTheme
import com.eezydraw.R
import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.SnapResult
import com.eezydraw.core.geometry.Viewport2D
import com.eezydraw.core.model.LengthUnit
import com.eezydraw.core.model.Point2
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.Projection
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Rulers and probe markers remain vector overlays, independent of GPU scene-buffer preparation. */
@Composable
internal fun PlanOverlays(
    viewport: Viewport2D,
    unit: LengthUnit,
    cursor: State<SnapResult?>,
    anchor: Point2?,
    window: State<Pair<Offset, Offset>?>,
    modifier: Modifier = Modifier,
) {
    val background = MaterialTheme.colorScheme.surface
    val foreground = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxSize()) {
        val gutter = 28f * density
        drawRect(background, size = Size(size.width, gutter))
        drawRect(background, size = Size(gutter, size.height))
        drawLine(outline, Offset(0f, gutter), Offset(size.width, gutter))
        drawLine(outline, Offset(gutter, 0f), Offset(gutter, size.height))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = foreground.toArgb()
            textSize = 10f * density * fontScale
        }
        val factor = unitFactor(unit)
        val desiredPixels = max(120.0 * density * fontScale, paint.measureText("-1234567890123.000").toDouble())
        val raw = desiredPixels / viewport.pixelsPerMetre
        val power = 10.0.pow(floor(log10(raw.coerceAtLeast(1e-12))))
        val normalized = raw / power
        val step = power * when { normalized <= 1 -> 1; normalized <= 2 -> 2; normalized <= 5 -> 5; else -> 10 }
        val decimals = ceil(-log10((step * factor).coerceAtLeast(1e-12))).toInt().coerceIn(0, 7)
        val bounds = viewport.visibleBounds(size.width.toDouble(), size.height.toDouble())
        fun ticks(low: Double, high: Double, emit: (Double) -> Unit) {
            val first = ceil(low / step) * step
            if (!first.isFinite() || !step.isFinite() || step <= 0) return
            val count = ((high - first) / step).toInt().coerceIn(0, 100)
            for (i in 0..count) emit(first + i * step)
        }
        ticks(bounds.min.x, bounds.max.x) { value ->
            val pixel = viewport.worldToScreen(Point2(value, viewport.center.y), size.width.toDouble(), size.height.toDouble()).x.toFloat()
            if (pixel in gutter..size.width) {
                drawLine(foreground, Offset(pixel, gutter - 5f * density), Offset(pixel, gutter))
                drawContext.canvas.nativeCanvas.drawText(coordinate(value, unit, decimals), pixel + 3f * density, 13f * density * fontScale, paint)
            }
        }
        ticks(bounds.min.y, bounds.max.y) { value ->
            val pixel = viewport.worldToScreen(Point2(viewport.center.x, value), size.width.toDouble(), size.height.toDouble()).y.toFloat()
            if (pixel in gutter..size.height) {
                drawLine(foreground, Offset(gutter - 5f * density, pixel), Offset(gutter, pixel))
                val native = drawContext.canvas.nativeCanvas
                native.withRotation(-90f, 13f * density, pixel - 3f * density) {
                    drawText(coordinate(value, unit, decimals), 13f * density, pixel - 3f * density, paint)
                }
            }
        }
        fun screen(point: Point2): Offset {
            val position = viewport.worldToScreen(point, size.width.toDouble(), size.height.toDouble())
            return Offset(position.x.toFloat(), position.y.toFloat())
        }
        anchor?.let {
            val p = screen(it)
            if (p.x in 0f..size.width && p.y in 0f..size.height) {
                drawCircle(accent.copy(alpha = .3f), 9f * density, p, style = Stroke(1f * density))
                drawLine(accent, p - Offset(12f * density, 0f), p + Offset(12f * density, 0f))
                drawLine(accent, p - Offset(0f, 12f * density), p + Offset(0f, 12f * density))
            }
        }
        cursor.value?.let {
            val p = screen(it.point)
            if (p.x in 0f..size.width && p.y in 0f..size.height) {
                val color = if (it.mode != null) accent else foreground
                drawRect(color, p - Offset(4f * density, 4f * density), Size(8f * density, 8f * density), style = Stroke(1.5f * density))
            }
        }
        window.value?.let { (a, b) ->
            val topLeft = Offset(min(a.x, b.x), min(a.y, b.y))
            val extent = Size(abs(a.x - b.x), abs(a.y - b.y))
            drawRect(accent.copy(alpha = .12f), topLeft, extent)
            drawRect(accent, topLeft, extent, style = Stroke(2f * density))
        }
    }
}

/** The projected, selectable cube follows the orbital camera, including its opposite faces. */
@Composable
internal fun ViewCube(camera: OrbitCamera, onFace: (Point3) -> Unit, modifier: Modifier = Modifier) {
    val cubeLabel = stringResource(R.string.canvas_view_cube)
    val top = stringResource(R.string.canvas_top)
    val front = stringResource(R.string.canvas_front)
    val side = stringResource(R.string.canvas_side)
    val back = stringResource(R.string.canvas_cube_back)
    val left = stringResource(R.string.canvas_cube_left)
    val bottom = stringResource(R.string.canvas_cube_bottom)
    val foreground = MaterialTheme.colorScheme.onPrimaryContainer
    val fill = MaterialTheme.colorScheme.primaryContainer
    val stroke = MaterialTheme.colorScheme.primary
    val labels = listOf(top, bottom, front, back, side, left)
    val normals = listOf(Point3(0.0, 0.0, 1.0), Point3(0.0, 0.0, -1.0), Point3(0.0, -1.0, 0.0),
        Point3(0.0, 1.0, 0.0), Point3(1.0, 0.0, 0.0), Point3(-1.0, 0.0, 0.0))
    Box(modifier.testTag("canvas_view_cube").semantics {
        contentDescription = cubeLabel
        customActions = normals.mapIndexed { index, normal -> CustomAccessibilityAction(labels[index]) { onFace(normal); true } }
    }) {
        Canvas(Modifier.fillMaxSize().pointerInput(camera.yaw, camera.pitch) {
            detectTapGestures { position ->
                cubeFaces(camera, size.width.toDouble(), size.height.toDouble()).firstOrNull {
                    inPolygon(position, it.points)
                }?.let { onFace(normals[it.index]) }
            }
        }) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = foreground.toArgb()
                textSize = 9f * density
                textAlign = Paint.Align.CENTER
            }
            for (face in cubeFaces(camera, size.width.toDouble(), size.height.toDouble())) {
                val path = Path().apply {
                    moveTo(face.points[0].x, face.points[0].y)
                    face.points.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                drawPath(path, fill)
                drawPath(path, stroke, style = Stroke(density))
                val x = face.points.sumOf { it.x.toDouble() }.toFloat() / 4
                val y = face.points.sumOf { it.y.toDouble() }.toFloat() / 4 + paint.textSize / 3
                drawContext.canvas.nativeCanvas.drawText(labels[face.index], x, y, paint)
            }
        }
    }
}

private data class CubeFace(val index: Int, val points: List<Offset>)
private fun cubeFaces(camera: OrbitCamera, width: Double, height: Double): List<CubeFace> {
    if (width <= 0.0 || height <= 0.0 || !width.isFinite() || !height.isFinite()) return emptyList()
    val corners = listOf(Point3(-.5,-.5,-.5), Point3(.5,-.5,-.5), Point3(.5,.5,-.5), Point3(-.5,.5,-.5),
        Point3(-.5,-.5,.5), Point3(.5,-.5,.5), Point3(.5,.5,.5), Point3(-.5,.5,.5))
    val faces = listOf(listOf(4,5,6,7), listOf(0,3,2,1), listOf(0,1,5,4), listOf(3,7,6,2), listOf(1,2,6,5), listOf(0,4,7,3))
    val normals = listOf(Point3(0.0,0.0,1.0), Point3(0.0,0.0,-1.0), Point3(0.0,-1.0,0.0),
        Point3(0.0,1.0,0.0), Point3(1.0,0.0,0.0), Point3(-1.0,0.0,0.0))
    val direction = Point3(kotlin.math.cos(camera.pitch)*kotlin.math.cos(camera.yaw),
        kotlin.math.cos(camera.pitch)*kotlin.math.sin(camera.yaw), kotlin.math.sin(camera.pitch))
    val local = camera.copy(target = Point3(), distance = 4.0, orthographicHeight = 1.9, projection = Projection.ORTHOGRAPHIC)
    return faces.mapIndexedNotNull { index, vertices ->
        val n = normals[index]
        if (n.x*direction.x+n.y*direction.y+n.z*direction.z <= 1e-9) null
        else CubeFace(index, vertices.map { point ->
            val p = requireNotNull(local.project(corners[point], width, height))
            Offset(p.x.toFloat(), p.y.toFloat())
        })
    }
}
private fun inPolygon(point: Offset, vertices: List<Offset>): Boolean {
    var result = false
    var previous = vertices.last()
    for (current in vertices) {
        if ((current.y > point.y) != (previous.y > point.y) &&
            point.x < (previous.x-current.x)*(point.y-current.y)/(previous.y-current.y)+current.x) result = !result
        previous = current
    }
    return result
}
