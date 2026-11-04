package com.eezydraw.ui.canvas.editing

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import com.eezydraw.R
import com.eezydraw.core.editing.EditDelta
import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.Viewport2D
import com.eezydraw.core.model.Bounds3
import com.eezydraw.core.model.Point2
import com.eezydraw.core.model.Point3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

enum class GizmoHandle { MOVE_X, MOVE_Y, MOVE_Z, ROTATE_Z, SCALE }
data class GizmoControl(val handle: GizmoHandle, val center: Point2)

/**
 * Project-Z rotation follows the horizontal construction plane when that plane is visible.
 * In front/side views its projection collapses to a line: the circular icon then acts as a
 * screen-space dial. This keeps rotation usable without an unstable ray/plane intersection.
 */
fun gizmoRotationZ(
    pivot: Point3,
    start: Offset,
    current: Offset,
    viewport: Viewport2D,
    orbit: OrbitCamera,
    is3D: Boolean,
    width: Double,
    height: Double,
): Double {
    if (!width.isFinite() || !height.isFinite() || width <= 0.0 || height <= 0.0) return 0.0
    fun plane(position: Offset): Point3? = if (is3D)
        orbit.rayToPlaneZ(position.x.toDouble(), position.y.toDouble(), width, height, pivot.z)
    else viewport.screenToWorld(position.x.toDouble(), position.y.toDouble(), width, height).let { Point3(it.x, it.y, pivot.z) }
    // Near the horizon small pixel movements produce enormous and discontinuous XY picks.
    if (!is3D || abs(sin(orbit.pitch)) >= 0.15) {
        val first = plane(start); val last = plane(current)
        if (first != null && last != null) {
            val angle = atan2(last.y - pivot.y, last.x - pivot.x) - atan2(first.y - pivot.y, first.x - pivot.x)
            return atan2(sin(angle), cos(angle))
        }
    }
    val center = projectGizmo(pivot, viewport, orbit, is3D, width, height) ?: return 0.0
    val first = atan2(center.y - start.y, start.x - center.x)
    val last = atan2(center.y - current.y, current.x - center.x)
    val angle = (last - first) * if (orbit.pitch < 0.0) -1.0 else 1.0
    return atan2(sin(angle), cos(angle))
}

/** Fixed-size screen controls; axes which point directly toward the eye have no handle. */
fun gizmoHandles(
    bounds: Bounds3,
    viewport: Viewport2D,
    orbit: OrbitCamera,
    is3D: Boolean,
    width: Double,
    height: Double,
    density: Double,
): List<GizmoControl> {
    if (width <= 0.0 || height <= 0.0 || density <= 0.0) return emptyList()
    val pivot = gizmoCenter(bounds)
    val center = projectGizmo(pivot, viewport, orbit, is3D, width, height) ?: return emptyList()
    val arm = 72.0 * density
    val axes = listOf(GizmoHandle.MOVE_X to Point3(1.0), GizmoHandle.MOVE_Y to Point3(y = 1.0)) +
        if (is3D) listOf(GizmoHandle.MOVE_Z to Point3(z = 1.0)) else emptyList()
    val result = ArrayList<GizmoControl>()
    for ((handle, axis) in axes) {
        // A camera-relative step avoids subtracting almost equal projections at very distant zoom.
        val step = if (is3D) max(orbit.distance, orbit.orthographicHeight) * 0.001 else 1.0
        val endpoint = projectGizmo(Point3(pivot.x + axis.x * step, pivot.y + axis.y * step, pivot.z + axis.z * step),
            viewport, orbit, is3D, width, height) ?: continue
        val dx = endpoint.x - center.x; val dy = endpoint.y - center.y
        val length = hypot(dx, dy)
        if (length < 1e-8 * density) continue
        result.add(GizmoControl(handle, Point2(center.x + dx / length * arm, center.y + dy / length * arm)))
    }
    result.add(GizmoControl(GizmoHandle.ROTATE_Z, Point2(center.x - 34.0 * density, center.y - 34.0 * density)))
    result.add(GizmoControl(GizmoHandle.SCALE, Point2(center.x + 48.0 * density, center.y + 48.0 * density)))
    return result
}

/**
 * Only a down event on a handle is consumed. Tapping/dragging an object's body remains
 * available to the canvas for selection and navigation, never for implicit object movement.
 */
@Composable
fun SelectionGizmo(
    bounds: Bounds3,
    viewport: Viewport2D,
    orbit: OrbitCamera,
    is3D: Boolean,
    preview: EditDelta?,
    onStart: (GizmoHandle, Offset) -> Unit,
    onDrag: (GizmoHandle, Offset, Offset) -> Unit,
    onEnd: (cancelled: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val density = LocalDensity.current.density.toDouble()
    val accent = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surface
    val latestStart by rememberUpdatedState(onStart)
    val latestDrag by rememberUpdatedState(onDrag)
    val latestEnd by rememberUpdatedState(onEnd)
    val latestPreview by rememberUpdatedState(preview)
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    val labels = mapOf(
        GizmoHandle.MOVE_X to stringResource(R.string.gizmo_move_x),
        GizmoHandle.MOVE_Y to stringResource(R.string.gizmo_move_y),
        GizmoHandle.MOVE_Z to stringResource(R.string.gizmo_move_z),
        GizmoHandle.ROTATE_Z to stringResource(R.string.gizmo_rotate_z),
        GizmoHandle.SCALE to stringResource(R.string.gizmo_scale),
    )
    val description = stringResource(R.string.gizmo_selection)
    fun currentBounds() = previewGizmoBounds(bounds, latestPreview)
    val controls = gizmoHandles(currentBounds(), viewport, orbit, is3D,
        surfaceSize.width.toDouble(), surfaceSize.height.toDouble(), density)
    Canvas(modifier.fillMaxSize().onSizeChanged { surfaceSize = it }.testTag("selection_gizmo")
        .semantics {
            contentDescription = description
            if (enabled) customActions = controls.map { control ->
                CustomAccessibilityAction(labels.getValue(control.handle)) {
                    val center = projectGizmo(gizmoCenter(currentBounds()), viewport, orbit, is3D,
                        surfaceSize.width.toDouble(), surfaceSize.height.toDouble())
                    if (center == null) false else {
                        val start = Offset(control.center.x.toFloat(), control.center.y.toFloat())
                        val dx = control.center.x - center.x; val dy = control.center.y - center.y
                        val end = when (control.handle) {
                            GizmoHandle.ROTATE_Z -> Offset((center.x - dy).toFloat(), (center.y + dx).toFloat())
                            GizmoHandle.SCALE -> Offset((center.x + dx * 1.1).toFloat(), (center.y + dy * 1.1).toFloat())
                            else -> start + Offset((dx / 6).toFloat(), (dy / 6).toFloat())
                        }
                        latestStart(control.handle, start)
                        latestDrag(control.handle, start, end)
                        latestEnd(false)
                        true
                    }
                }
            }
        }
        .pointerInput(bounds, viewport, orbit, is3D, density, enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                val controlsAtDown = gizmoHandles(currentBounds(), viewport, orbit, is3D,
                    size.width.toDouble(), size.height.toDouble(), density)
                val control = controlsAtDown.minByOrNull {
                    hypot(it.center.x - down.position.x, it.center.y - down.position.y)
                }?.takeIf { hypot(it.center.x - down.position.x, it.center.y - down.position.y) <= 20.0 * density }
                    ?: return@awaitEachGesture
                if (currentEvent.changes.count { it.pressed } > 1) {
                    currentEvent.changes.forEach { it.consume() }
                    return@awaitEachGesture
                }
                down.consume()
                var completed = false
                try {
                    latestStart(control.handle, down.position)
                    var cancelled = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || change.isConsumed || event.changes.count { it.pressed } > 1) {
                            cancelled = true
                            event.changes.forEach { it.consume() }
                            break
                        }
                        latestDrag(control.handle, down.position, change.position)
                        change.consume()
                        if (!change.pressed) break
                    }
                    completed = true
                    latestEnd(cancelled)
                } finally {
                    if (!completed) latestEnd(true)
                }
            }
        }) {
        val width = size.width.toDouble(); val height = size.height.toDouble()
        val corners = gizmoCorners(bounds).map { latestPreview?.apply(it) ?: it }
        val projected = corners.map { projectGizmo(it, viewport, orbit, is3D, width, height) }
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f * this.density, 4f * this.density))
        for (i in corners.indices) for (axis in listOf(1, 2, 4)) {
            val j = i xor axis
            if (j <= i || !is3D && axis == 1) continue
            val a = projected[i] ?: continue; val b = projected[j] ?: continue
            drawLine(accent.copy(alpha = 0.8f), a.offset(), b.offset(), 1.5f * this.density, pathEffect = dash)
        }
        val shownBounds = previewGizmoBounds(bounds, latestPreview)
        val center = projectGizmo(gizmoCenter(shownBounds), viewport, orbit, is3D, width, height) ?: return@Canvas
        val origin = center.offset()
        if (!enabled) return@Canvas
        val visibleControls = gizmoHandles(shownBounds, viewport, orbit, is3D, width, height, density)
        drawCircle(background.copy(alpha = 0.8f), 5f * this.density, origin)
        drawCircle(accent, 3f * this.density, origin)
        drawCircle(accent.copy(alpha = 0.55f), (sqrt(2.0) * 34 * density).toFloat(), origin,
            style = Stroke(1.5f * this.density))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12f * this@Canvas.density
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        for (control in visibleControls) {
            val end = control.center.offset()
            val color = when (control.handle) {
                GizmoHandle.MOVE_X -> Color(0xFFD64747)
                GizmoHandle.MOVE_Y -> Color(0xFF23965C)
                GizmoHandle.MOVE_Z -> Color(0xFF3976D2)
                else -> accent
            }
            when (control.handle) {
                GizmoHandle.MOVE_X, GizmoHandle.MOVE_Y, GizmoHandle.MOVE_Z -> {
                    drawLine(color, origin, end, 2.5f * this.density)
                    val direction = end - origin
                    val unit = direction / direction.getDistance()
                    val perpendicular = Offset(-unit.y, unit.x)
                    val back = end - unit * (10f * this.density)
                    drawLine(color, end, back + perpendicular * (5f * this.density), 2.5f * this.density)
                    drawLine(color, end, back - perpendicular * (5f * this.density), 2.5f * this.density)
                    val label = when (control.handle) { GizmoHandle.MOVE_X -> "X"; GizmoHandle.MOVE_Y -> "Y"; else -> "Z" }
                    paint.color = color.toArgb()
                    val textAt = end + unit * (14f * this.density)
                    drawContext.canvas.nativeCanvas.drawText(label, textAt.x, textAt.y + 4f * this.density, paint)
                }
                GizmoHandle.ROTATE_Z -> {
                    drawCircle(background, 10f * this.density, end)
                    drawArc(color, 35f, 290f, false, end - Offset(7f, 7f) * this.density,
                        Size(14f, 14f) * this.density, style = Stroke(2f * this.density))
                }
                GizmoHandle.SCALE -> {
                    drawLine(color, origin, end, 1.5f * this.density, pathEffect = dash)
                    drawRect(background, end - Offset(7f, 7f) * this.density, Size(14f, 14f) * this.density)
                    drawRect(color, end - Offset(7f, 7f) * this.density, Size(14f, 14f) * this.density, style = Stroke(2f * this.density))
                }
            }
        }
    }
}

private fun Point2.offset() = Offset(x.toFloat(), y.toFloat())
private fun gizmoCenter(bounds: Bounds3) = Point3(bounds.min.x / 2 + bounds.max.x / 2,
    bounds.min.y / 2 + bounds.max.y / 2, bounds.min.z / 2 + bounds.max.z / 2)
private fun gizmoCorners(bounds: Bounds3) = listOf(bounds.min.x, bounds.max.x).flatMap { x ->
    listOf(bounds.min.y, bounds.max.y).flatMap { y -> listOf(bounds.min.z, bounds.max.z).map { z -> Point3(x, y, z) } }
}
private fun previewGizmoBounds(bounds: Bounds3, preview: EditDelta?): Bounds3 {
    if (preview == null) return bounds
    val corners = gizmoCorners(bounds).map(preview::apply)
    return Bounds3(Point3(corners.minOf { it.x }, corners.minOf { it.y }, corners.minOf { it.z }),
        Point3(corners.maxOf { it.x }, corners.maxOf { it.y }, corners.maxOf { it.z }))
}
private fun projectGizmo(point: Point3, viewport: Viewport2D, orbit: OrbitCamera, is3D: Boolean, width: Double, height: Double): Point2? =
    if (is3D) orbit.project(point, width, height)?.let { Point2(it.x, it.y) }
    else viewport.worldToScreen(Point2(point.x, point.y), width, height)
