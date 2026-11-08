package com.eezydraw.ui.canvas

import android.graphics.Paint
import androidx.core.graphics.withRotation
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
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

/**
 * Faces and corners navigate; dragging the cube orbits. Gesture input is deliberately not
 * keyed by camera values, because its own orbit callback changes those values every frame.
 */
@Composable
internal fun ViewCube(
    camera: OrbitCamera,
    onFace: (Point3) -> Unit,
    modifier: Modifier = Modifier,
    onOrbit: (Double, Double) -> Unit = { _, _ -> },
    onCorner: (Point3) -> Unit = {},
    onGestureActive: (Boolean) -> Unit = {},
    onPlan: () -> Unit = {},
    onProjection: (Projection) -> Unit = {},
    onCompass:(Double)->Unit={},
) {
    val cubeLabel = stringResource(R.string.canvas_view_cube)
    val top = stringResource(R.string.canvas_top)
    val front = stringResource(R.string.canvas_front)
    val side = stringResource(R.string.canvas_side)
    val back = stringResource(R.string.canvas_cube_back)
    val left = stringResource(R.string.canvas_cube_left)
    val bottom = stringResource(R.string.canvas_cube_bottom)
    val iso = stringResource(R.string.canvas_iso)
    val plan = stringResource(R.string.cube_plan_2d)
    val perspective = stringResource(R.string.cube_perspective)
    val orthographic = stringResource(R.string.cube_orthographic)
    var menuOpen by remember { mutableStateOf(false) }
    val foreground = MaterialTheme.colorScheme.onPrimaryContainer
    val fill = MaterialTheme.colorScheme.primaryContainer
    val stroke = MaterialTheme.colorScheme.primary
    val labels = listOf(top, bottom, front, back, side, left)
    val currentCamera by rememberUpdatedState(camera)
    val currentFace by rememberUpdatedState(onFace)
    val currentCorner by rememberUpdatedState(onCorner)
    val currentOrbit by rememberUpdatedState(onOrbit)
    val currentGesture by rememberUpdatedState(onGestureActive)
    val currentPlan by rememberUpdatedState(onPlan)
    val currentProjection by rememberUpdatedState(onProjection)
    val currentCompass by rememberUpdatedState(onCompass)
    val touchDensity = LocalDensity.current.density.toDouble()
    val currentDensity by rememberUpdatedState(touchDensity)
    Box(modifier.testTag("canvas_view_cube").semantics {
        contentDescription = cubeLabel
        customActions = cubeNormals.mapIndexed { index, normal ->
            CustomAccessibilityAction(labels[index]) { currentFace(normal); true }
        } + cubeCorners.map { corner ->
            val direction = Point3(corner.x * 2, corner.y * 2, corner.z * 2)
            val label = "$iso ${if (corner.x > 0) "+X" else "−X"}, ${if (corner.y > 0) "+Y" else "−Y"}, ${if (corner.z > 0) "+Z" else "−Z"}"
            CustomAccessibilityAction(label) { currentCorner(direction); true }
        } + listOf(
            CustomAccessibilityAction(plan) { currentPlan(); true },
            CustomAccessibilityAction(perspective) { currentProjection(Projection.PERSPECTIVE); true },
            CustomAccessibilityAction(orthographic) { currentProjection(Projection.ORTHOGRAPHIC); true },
        )
    }) {
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                if (currentEvent.changes.count { it.pressed } != 1) return@awaitEachGesture
                val first = down.position
                val startCamera = currentCamera
                var previous = first
                var orbiting = false
                var longPressed = false
                var elapsed = 0L
                val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                down.consume()
                try {
                    while (true) {
                        val event = if (!orbiting && !longPressed) {
                            withTimeoutOrNull((longPressTimeout - elapsed).coerceAtLeast(1L)) { awaitPointerEvent() }
                        } else awaitPointerEvent()
                        if (event == null) { longPressed = true; menuOpen = true; continue }
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || change.isConsumed || event.changes.count { it.pressed } > 1) {
                            event.changes.forEach { it.consume() }
                            break
                        }
                        val total = change.position - first
                        val delta = change.position - previous
                        elapsed = change.uptimeMillis - down.uptimeMillis
                        if (!orbiting && !longPressed && elapsed >= longPressTimeout && total.getDistance() <= viewConfiguration.touchSlop) {
                            longPressed = true
                            menuOpen = true
                        }
                        if (!longPressed && !orbiting && total.getDistance() > viewConfiguration.touchSlop) {
                            orbiting = true
                            currentGesture(true)
                            currentOrbit(total.x.toDouble(), total.y.toDouble())
                        } else if (!longPressed && orbiting && delta != Offset.Zero) {
                            currentOrbit(delta.x.toDouble(), delta.y.toDouble())
                        }
                        previous = change.position
                        change.consume()
                        if (!change.pressed) {
                            if(!orbiting && !longPressed){
                                val compass=cubeCompassHit(change.position,startCamera,size.width.toDouble(),size.height.toDouble(),currentDensity)
                                if(compass!=null)currentCompass(compass)else cubeNavigationHit(change.position,startCamera,size.width.toDouble(),size.height.toDouble(),currentDensity)?.let {direction->if(direction.x!=0.0 && direction.y!=0.0 && direction.z!=0.0)currentCorner(direction)else currentFace(direction)}
                            }
                            break
                        }
                    }
                } finally {
                    if (orbiting) currentGesture(false)
                }
            }
        }) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = foreground.toArgb()
                textSize = 10f * density
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
                val center = face.center
                drawContext.canvas.nativeCanvas.drawText(labels[face.index], center.x,
                    center.y + paint.textSize / 3, paint)
            }
            val centre=Offset(size.width/2,size.height/2);val radius=min(size.width,size.height)*.44f
            drawCircle(stroke,radius,centre,style=Stroke(density))
            listOf("N","E","S","W").forEachIndexed {i,label->val angle=camera.yaw+i*Math.PI/2;val at=centre+Offset((kotlin.math.cos(angle)*radius*.91).toFloat(),(kotlin.math.sin(angle)*radius*.91).toFloat());drawContext.canvas.nativeCanvas.drawText(label,at.x,at.y+paint.textSize/3,paint)}

        }
        DropdownMenu(menuOpen, { menuOpen = false }) {
            DropdownMenuItem(text = { Text(plan) }, modifier = Modifier.testTag("cube_plan_2d"),
                onClick = { menuOpen = false; currentPlan() })
            DropdownMenuItem(text = { Text(perspective) }, modifier = Modifier.testTag("cube_perspective"),
                onClick = { menuOpen = false; currentProjection(Projection.PERSPECTIVE) })
            DropdownMenuItem(text = { Text(orthographic) }, modifier = Modifier.testTag("cube_orthographic"),
                onClick = { menuOpen = false; currentProjection(Projection.ORTHOGRAPHIC) })
        }
    }
}

private val cubeCorners = listOf(Point3(-.5,-.5,-.5), Point3(.5,-.5,-.5), Point3(.5,.5,-.5), Point3(-.5,.5,-.5),
    Point3(-.5,-.5,.5), Point3(.5,-.5,.5), Point3(.5,.5,.5), Point3(-.5,.5,.5))
private val cubeVertexFaces = listOf(listOf(4,5,6,7), listOf(0,3,2,1), listOf(0,1,5,4),
    listOf(3,7,6,2), listOf(1,2,6,5), listOf(0,4,7,3))
private val cubeNormals = listOf(Point3(0.0,0.0,1.0), Point3(0.0,0.0,-1.0), Point3(0.0,-1.0,0.0),
    Point3(0.0,1.0,0.0), Point3(1.0,0.0,0.0), Point3(-1.0,0.0,0.0))

private data class CubeFace(val index: Int, val points: List<Offset>) {
    val center: Offset get() = Offset(points.sumOf { it.x.toDouble() }.toFloat() / 4,
        points.sumOf { it.y.toDouble() }.toFloat() / 4)
}

private fun cubeCamera(camera: OrbitCamera) = camera.copy(target = Point3(), distance = 4.0,
    orthographicHeight = 1.9, projection = Projection.ORTHOGRAPHIC)

private fun cubeFaces(camera: OrbitCamera, width: Double, height: Double): List<CubeFace> {
    if (width <= 0.0 || height <= 0.0 || !width.isFinite() || !height.isFinite()) return emptyList()
    val direction = cubeDirection(camera)
    val local = cubeCamera(camera)
    return cubeVertexFaces.mapIndexedNotNull { index, vertices ->
        val n = cubeNormals[index]
        if (n.x*direction.x+n.y*direction.y+n.z*direction.z <= 1e-9) null
        else CubeFace(index, vertices.map { point ->
            val p = requireNotNull(local.project(cubeCorners[point], width, height))
            Offset(p.x.toFloat(), p.y.toFloat())
        })
    }
}

private fun cubeDirection(camera: OrbitCamera) = Point3(kotlin.math.cos(camera.pitch)*kotlin.math.cos(camera.yaw),
    kotlin.math.cos(camera.pitch)*kotlin.math.sin(camera.yaw), kotlin.math.sin(camera.pitch))

/**
 * The center of a face remains an unambiguous target. Around its borders, the nearest
 * visible corner gets a 20dp capture radius and navigates to that body-diagonal view.
 */
internal fun cubeNavigationHit(position: Offset, camera: OrbitCamera, width: Double,
    height: Double, density: Double): Point3? {
    if (!position.x.isFinite() || !position.y.isFinite() || !density.isFinite() || density <= 0.0) return null
    val faces = cubeFaces(camera, width, height)
    if (faces.isEmpty()) return null
    val face = faces.firstOrNull { inPolygon(position, it.points) }
    if (face != null) {
        val central = face.points.map { face.center + (it - face.center) * .48f }
        if (inPolygon(position, central)) return cubeNormals[face.index]
    }
    val local = cubeCamera(camera)
    val direction = cubeDirection(camera)
    val visibleCorners = faces.flatMap { cubeVertexFaces[it.index] }.toSet()
    val nearby = visibleCorners.mapNotNull { index ->
        val corner = cubeCorners[index]
        local.project(corner, width, height)?.let { projected ->
            Triple(index, kotlin.math.hypot(position.x - projected.x, position.y - projected.y),
                corner.x * direction.x + corner.y * direction.y + corner.z * direction.z)
        }
    }.filter { it.second <= 20.0 * density }
        .sortedWith(compareBy<Triple<Int, Double, Double>> { it.second }.thenByDescending { it.third })
        .firstOrNull()
    if (nearby != null) return cubeCorners[nearby.first].let { Point3(it.x * 2, it.y * 2, it.z * 2) }
    return face?.let { cubeNormals[it.index] }
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

internal fun cubeCompassHit(position:Offset,camera:OrbitCamera,width:Double,height:Double,density:Double):Double? {
    val x=position.x-width/2;val y=position.y-height/2;val radius=minOf(width,height)*.44
    if(kotlin.math.hypot(x,y)<radius*.78)return null
    val nearest=(0..3).minByOrNull {i->val angle=camera.yaw+i*Math.PI/2;kotlin.math.hypot(x-kotlin.math.cos(angle)*radius*.91,y-kotlin.math.sin(angle)*radius*.91)} ?: return null
    val angle=camera.yaw+nearest*Math.PI/2
    return if(kotlin.math.hypot(x-kotlin.math.cos(angle)*radius*.91,y-kotlin.math.sin(angle)*radius*.91)<=16*density) -Math.PI/2+nearest*Math.PI/2 else null
}
