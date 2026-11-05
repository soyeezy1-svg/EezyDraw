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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import com.eezydraw.R
import com.eezydraw.core.editing.EditDelta
import com.eezydraw.core.editing.ResizeFrame
import com.eezydraw.core.editing.ResizeHandle
import com.eezydraw.core.editing.resizePlaneVisible
import com.eezydraw.core.editing.moveAxisScreenDirection
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
import kotlin.math.PI
import java.util.Locale

enum class GizmoMode { MOVE, ROTATE, SCALE }

enum class GizmoHandle {
    MOVE_FREE, MOVE_X, MOVE_Y, MOVE_Z, ROTATE_X, ROTATE_Y, ROTATE_Z, SCALE,
    RESIZE_WIDTH_NEGATIVE, RESIZE_WIDTH_POSITIVE,
    RESIZE_DEPTH_NEGATIVE, RESIZE_DEPTH_POSITIVE,
    RESIZE_CORNER_MIN_MIN, RESIZE_CORNER_MAX_MIN, RESIZE_CORNER_MAX_MAX, RESIZE_CORNER_MIN_MAX,
    RESIZE_HEIGHT,
}

fun GizmoHandle.resizeHandle(): ResizeHandle? = when (this) {
    GizmoHandle.RESIZE_WIDTH_NEGATIVE -> ResizeHandle.WIDTH_NEGATIVE
    GizmoHandle.RESIZE_WIDTH_POSITIVE -> ResizeHandle.WIDTH_POSITIVE
    GizmoHandle.RESIZE_DEPTH_NEGATIVE -> ResizeHandle.DEPTH_NEGATIVE
    GizmoHandle.RESIZE_DEPTH_POSITIVE -> ResizeHandle.DEPTH_POSITIVE
    GizmoHandle.RESIZE_CORNER_MIN_MIN -> ResizeHandle.CORNER_MIN_MIN
    GizmoHandle.RESIZE_CORNER_MAX_MIN -> ResizeHandle.CORNER_MAX_MIN
    GizmoHandle.RESIZE_CORNER_MAX_MAX -> ResizeHandle.CORNER_MAX_MAX
    GizmoHandle.RESIZE_CORNER_MIN_MAX -> ResizeHandle.CORNER_MIN_MAX
    GizmoHandle.RESIZE_HEIGHT -> ResizeHandle.HEIGHT
    else -> null
}

/** [anchor] joins expanded small-object controls to their real dimension boundary. */
data class GizmoControl(
    val handle: GizmoHandle,
    val center: Point2,
    val anchor: Point2? = null,
    val accessibleStep: Point2? = null,
    /** Closed, projected rotation band; all points share the handle's rotation axis. */
    val rotationRing: List<Point2> = emptyList(),
)

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

/** Fixed-size, mode-specific controls. Unobservable move axes retain a virtual drag arrow. */
fun gizmoHandles(
    bounds: Bounds3,
    viewport: Viewport2D,
    orbit: OrbitCamera,
    is3D: Boolean,
    width: Double,
    height: Double,
    density: Double,
    resizeFrame: ResizeFrame? = null,
    preview: EditDelta? = null,
    allowResize: Boolean = true,
    mode: GizmoMode = GizmoMode.MOVE,
    pivotOverride: Point3? = null,
): List<GizmoControl> {
    if (width <= 0.0 || height <= 0.0 || density <= 0.0) return emptyList()
    val shownBounds = previewGizmoBounds(bounds, preview)
    val pivot = pivotOverride?.let {preview?.apply(it) ?: it} ?: gizmoCenter(shownBounds)
    val center = projectGizmo(pivot, viewport, orbit, is3D, width, height) ?: return emptyList()
    if (mode == GizmoMode.ROTATE) return rotationControls(pivot, center, viewport, orbit, is3D, width, height, density)
    if (mode == GizmoMode.SCALE) {
        if (!allowResize) return emptyList()
        return resizeFrame?.let { resizeControls(it, preview, viewport, orbit, is3D, width, height, density) }
            ?: listOf(GizmoControl(GizmoHandle.SCALE,
                Point2(center.x + 64.0 * density, center.y + 64.0 * density)))
    }
    val result = ArrayList<GizmoControl>()
    // The square stays at the object centre. Axis arrows are separated from its 52dp target.
    result.add(GizmoControl(GizmoHandle.MOVE_FREE, center, accessibleStep = Point2(12.0 * density, 0.0)))
    val axes = listOf(GizmoHandle.MOVE_X to Point3(1.0), GizmoHandle.MOVE_Y to Point3(y = 1.0),
        GizmoHandle.MOVE_Z to Point3(z = 1.0))
    for ((handle, axis) in axes) {
        val direction = moveAxisScreenDirection(axis, viewport, orbit, is3D, width, height, pivot)
        var distance = 84.0 * density
        var end = Point2(center.x + direction.x * distance, center.y + direction.y * distance)
        while (result.any { hypot(it.center.x - end.x, it.center.y - end.y) < 52.0 * density } && distance < 1300.0 * density) {
            distance += 52.0 * density
            end = Point2(center.x + direction.x * distance, center.y + direction.y * distance)
        }
        result.add(GizmoControl(handle, end, accessibleStep = Point2(direction.x * 12.0 * density, direction.y * 12.0 * density)))
    }
    return result
}

/** The three world-axis bands remain distinct; a collapsed plane becomes a screen dial. */
private fun rotationControls(
    pivot: Point3, center: Point2, viewport: Viewport2D, orbit: OrbitCamera,
    is3D: Boolean, width: Double, height: Double, density: Double,
): List<GizmoControl> {
    val result = ArrayList<GizmoControl>()
    val axes = listOf(Point3(1.0), Point3(y = 1.0), Point3(z = 1.0))
    val handles = listOf(GizmoHandle.ROTATE_X, GizmoHandle.ROTATE_Y, GizmoHandle.ROTATE_Z)
    val step = if (is3D) max(orbit.distance, orbit.orthographicHeight) * .001 else 1.0
    for (axis in 0..2) {
        val radius = (72.0 + axis * 24.0) * density
        fun projectedDirection(direction: Point3): Point2? = projectGizmo(Point3(
            pivot.x + direction.x * step, pivot.y + direction.y * step, pivot.z + direction.z * step,
        ), viewport, orbit, is3D, width, height)?.let { Point2(it.x - center.x, it.y - center.y) }
        val pu = projectedDirection(axes[(axis + 1) % 3]) ?: Point2(0.0, 0.0)
        val pv = projectedDirection(axes[(axis + 2) % 3]) ?: Point2(0.0, 0.0)
        val lu = hypot(pu.x, pu.y); val lv = hypot(pv.x, pv.y)
        val collapsed = lu < 1e-7 || lv < 1e-7 || abs(pu.x * pv.y - pu.y * pv.x) / (lu * lv) < .15
        val longest = max(lu, lv).coerceAtLeast(1e-12)
        val u = if (collapsed) Point2(radius, 0.0) else Point2(pu.x / longest * radius, pu.y / longest * radius)
        val v = if (collapsed) Point2(0.0, -radius) else Point2(pv.x / longest * radius, pv.y / longest * radius)
        val points = (0 until 64).map { index ->
            val angle = index * 2 * PI / 64
            Point2(center.x + u.x * cos(angle) + v.x * sin(angle), center.y + u.y * cos(angle) + v.y * sin(angle))
        }.let { it + it.first() }
        val preferred = listOf(24, 40, 8)[axis]
        val index = (0 until 64).map { (preferred + it) % 64 }.firstOrNull { at ->
            result.all { hypot(it.center.x - points[at].x, it.center.y - points[at].y) >= 52.0 * density }
        } ?: preferred
        val handlePoint = points[index]
        val next = points[(index + 16) % 64]
        result.add(GizmoControl(handles[axis], handlePoint,
            accessibleStep = Point2(next.x - handlePoint.x, next.y - handlePoint.y), rotationRing = points))
    }
    return result
}

private fun resizeControls(
    frame: ResizeFrame, preview: EditDelta?, viewport: Viewport2D, orbit: OrbitCamera,
    is3D: Boolean, width: Double, height: Double, density: Double,
): List<GizmoControl> {
    fun project(point: Point3): Point2? = projectGizmo(preview?.apply(point) ?: point, viewport, orbit, is3D, width, height)
    val leftWorld = frame.handlePoint(ResizeHandle.WIDTH_NEGATIVE)
    val rightWorld = frame.handlePoint(ResizeHandle.WIDTH_POSITIVE)
    val left = project(leftWorld) ?: return emptyList()
    val right = project(rightWorld) ?: return emptyList()
    val front = project(frame.handlePoint(ResizeHandle.DEPTH_NEGATIVE)) ?: return emptyList()
    val back = project(frame.handlePoint(ResizeHandle.DEPTH_POSITIVE)) ?: return emptyList()
    val baseCenter = project(Point3(leftWorld.x / 2 + rightWorld.x / 2,
        leftWorld.y / 2 + rightWorld.y / 2, leftWorld.z / 2 + rightWorld.z / 2)) ?: return emptyList()
    val u = Point2((right.x - left.x) / 2, (right.y - left.y) / 2)
    val v = Point2((back.x - front.x) / 2, (back.y - front.y) / 2)
    val uLength = hypot(u.x, u.y); val vLength = hypot(v.x, v.y)
    val widthUsable = frame.size.x > 0.0 && uLength > 1e-7 * density
    val depthUsable = frame.size.y > 0.0 && vLength > 1e-7 * density
    val planeUsable = widthUsable && depthUsable && resizePlaneVisible(frame, orbit, is3D) &&
        abs(u.x * v.y - u.y * v.x) / (uLength * vLength) >= .15
    val growX = if (widthUsable) max(1.0, 56.0 * density / uLength) - 1.0 else 0.0
    val growY = if (depthUsable) max(1.0, 56.0 * density / vLength) - 1.0 else 0.0
    val result = ArrayList<GizmoControl>()
    fun add(handle: GizmoHandle, x: Int, y: Int, z: Int = 0) {
        val core = handle.resizeHandle() ?: return
        val world = frame.handlePoint(core)
        val actual = project(world) ?: return
        var displayed = Point2(actual.x + x * u.x * growX + y * v.x * growY,
            actual.y + x * u.y * growX + y * v.y * growY)
        val dx = displayed.x - baseCenter.x; val dy = displayed.y - baseCenter.y
        val length = hypot(dx, dy)
        if (length < 1e-7 * density) return
        var arm = max(length, (if (z == 0) 56.0 else 88.0) * density)
        var attempts = 0
        while (attempts++ < 24) {
            displayed = Point2(baseCenter.x + dx / length * arm, baseCenter.y + dy / length * arm)
            if (result.none { hypot(it.center.x - displayed.x, it.center.y - displayed.y) < 52.0 * density }) break
            arm += 52.0 * density
        }
        // A small action uses ten percent of this physical dimension, independent of zoom/units.
        val axes = listOf(frame.axis(0), frame.axis(1), frame.axis(2))
        val amounts = listOf(x * frame.size.x * .1, y * frame.size.y * .1, z * frame.size.z * .1)
        val next = Point3(world.x + axes.indices.sumOf { axes[it].x * amounts[it] },
            world.y + axes.indices.sumOf { axes[it].y * amounts[it] },
            world.z + axes.indices.sumOf { axes[it].z * amounts[it] })
        val projectedNext = project(next)
        val step = projectedNext?.let { Point2(it.x - actual.x, it.y - actual.y) }
        result.add(GizmoControl(handle, displayed, actual, step))
    }
    if (widthUsable) {
        add(GizmoHandle.RESIZE_WIDTH_NEGATIVE, -1, 0)
        add(GizmoHandle.RESIZE_WIDTH_POSITIVE, 1, 0)
    }
    if (depthUsable) {
        add(GizmoHandle.RESIZE_DEPTH_NEGATIVE, 0, -1)
        add(GizmoHandle.RESIZE_DEPTH_POSITIVE, 0, 1)
    }
    if (planeUsable) {
        add(GizmoHandle.RESIZE_CORNER_MIN_MIN, -1, -1)
        add(GizmoHandle.RESIZE_CORNER_MAX_MIN, 1, -1)
        add(GizmoHandle.RESIZE_CORNER_MAX_MAX, 1, 1)
        add(GizmoHandle.RESIZE_CORNER_MIN_MAX, -1, 1)
    }
    if (is3D && frame.size.z > 0.0) add(GizmoHandle.RESIZE_HEIGHT, 0, 0, 1)
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
    resizeFrame: ResizeFrame? = null,
    allowResize: Boolean = true,
    mode: GizmoMode = GizmoMode.MOVE,
    rotationAngleDegrees: Double? = null,
    pivotOverride: Point3? = null,
) {
    val density = LocalDensity.current.density.toDouble()
    val accent = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surface
    val latestStart by rememberUpdatedState(onStart)
    val latestDrag by rememberUpdatedState(onDrag)
    val latestEnd by rememberUpdatedState(onEnd)
    val latestPreview by rememberUpdatedState(preview)
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    var activeHandle by remember { mutableStateOf<GizmoHandle?>(null) }
    val labels = mapOf(
        GizmoHandle.MOVE_FREE to stringResource(R.string.gizmo_move_free),
        GizmoHandle.MOVE_X to stringResource(R.string.gizmo_move_x),
        GizmoHandle.MOVE_Y to stringResource(R.string.gizmo_move_y),
        GizmoHandle.MOVE_Z to stringResource(R.string.gizmo_move_z),
        GizmoHandle.ROTATE_X to stringResource(R.string.gizmo_rotate_x),
        GizmoHandle.ROTATE_Y to stringResource(R.string.gizmo_rotate_y),
        GizmoHandle.ROTATE_Z to stringResource(R.string.gizmo_rotate_z),
        GizmoHandle.SCALE to stringResource(R.string.gizmo_scale),
        GizmoHandle.RESIZE_WIDTH_NEGATIVE to stringResource(R.string.gizmo_resize_width_negative),
        GizmoHandle.RESIZE_WIDTH_POSITIVE to stringResource(R.string.gizmo_resize_width_positive),
        GizmoHandle.RESIZE_DEPTH_NEGATIVE to stringResource(R.string.gizmo_resize_depth_negative),
        GizmoHandle.RESIZE_DEPTH_POSITIVE to stringResource(R.string.gizmo_resize_depth_positive),
        GizmoHandle.RESIZE_CORNER_MIN_MIN to stringResource(R.string.gizmo_resize_corner_min_min),
        GizmoHandle.RESIZE_CORNER_MAX_MIN to stringResource(R.string.gizmo_resize_corner_max_min),
        GizmoHandle.RESIZE_CORNER_MAX_MAX to stringResource(R.string.gizmo_resize_corner_max_max),
        GizmoHandle.RESIZE_CORNER_MIN_MAX to stringResource(R.string.gizmo_resize_corner_min_max),
        GizmoHandle.RESIZE_HEIGHT to stringResource(R.string.gizmo_resize_height),
    )
    val description = stringResource(R.string.gizmo_selection)
    fun currentBounds() = previewGizmoBounds(bounds, latestPreview)
    val controls = gizmoHandles(bounds, viewport, orbit, is3D,
        surfaceSize.width.toDouble(), surfaceSize.height.toDouble(), density, resizeFrame, latestPreview, allowResize, mode,pivotOverride)
    Canvas(modifier.fillMaxSize().onSizeChanged { surfaceSize = it }.testTag("selection_gizmo")
        .semantics {
            contentDescription = description
            if (mode == GizmoMode.ROTATE && rotationAngleDegrees != null) stateDescription = angleLabel(rotationAngleDegrees)
            if (enabled) customActions = controls.map { control ->
                CustomAccessibilityAction(labels.getValue(control.handle)) {
                    val center = projectGizmo(pivotOverride ?: gizmoCenter(currentBounds()), viewport, orbit, is3D,
                        surfaceSize.width.toDouble(), surfaceSize.height.toDouble())
                    if (center == null) false else {
                        val start = Offset(control.center.x.toFloat(), control.center.y.toFloat())
                        val dx = control.center.x - center.x; val dy = control.center.y - center.y
                        val end = control.accessibleStep?.let { start + it.offset() } ?: when (control.handle) {
                            GizmoHandle.ROTATE_X, GizmoHandle.ROTATE_Y, GizmoHandle.ROTATE_Z -> Offset((center.x - dy).toFloat(), (center.y + dx).toFloat())
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
        .pointerInput(bounds, viewport, orbit, is3D, density, enabled, resizeFrame, allowResize, mode,pivotOverride) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                val controlsAtDown = gizmoHandles(bounds, viewport, orbit, is3D,
                    size.width.toDouble(), size.height.toDouble(), density, resizeFrame, latestPreview, allowResize, mode,pivotOverride)
                val position = Point2(down.position.x.toDouble(), down.position.y.toDouble())
                val knob = controlsAtDown.minByOrNull { hypot(it.center.x - position.x, it.center.y - position.y) }
                    ?.takeIf {
                        if (it.handle == GizmoHandle.MOVE_FREE)
                            max(abs(it.center.x - position.x), abs(it.center.y - position.y)) <= 26.0 * density
                        else hypot(it.center.x - position.x, it.center.y - position.y) <= 24.0 * density
                    }
                val control = knob ?: controlsAtDown.filter { it.rotationRing.isNotEmpty() }.minByOrNull {
                    distanceToRing(position, it.rotationRing)
                }?.takeIf { distanceToRing(position, it.rotationRing) <= 24.0 * density }
                    ?: return@awaitEachGesture
                if (currentEvent.changes.count { it.pressed } > 1) {
                    currentEvent.changes.forEach { it.consume() }
                    return@awaitEachGesture
                }
                down.consume()
                var completed = false
                try {
                    activeHandle = control.handle
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
                    activeHandle = null
                } finally {
                    if (!completed) latestEnd(true)
                    activeHandle = null
                }
            }
        }) {
        val width = size.width.toDouble(); val height = size.height.toDouble()
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f * this.density, 4f * this.density))
        if (mode == GizmoMode.SCALE) {
            val originalCorners = if (resizeFrame == null) gizmoCorners(bounds)
                else gizmoCorners(resizeFrame.localBounds).map(resizeFrame::worldPoint)
            val corners = originalCorners.map { latestPreview?.apply(it) ?: it }
            val projected = corners.map { projectGizmo(it, viewport, orbit, is3D, width, height) }
            for (i in corners.indices) for (axis in listOf(1, 2, 4)) {
                val j = i xor axis
                if (j <= i || !is3D && axis == 1) continue
                val a = projected[i] ?: continue; val b = projected[j] ?: continue
                drawLine(accent.copy(alpha = .7f), a.offset(), b.offset(), 1.5f * this.density, pathEffect = dash)
            }
        }
        val shownBounds = previewGizmoBounds(bounds, latestPreview)
        val center = projectGizmo(pivotOverride?.let {latestPreview?.apply(it) ?: it} ?: gizmoCenter(shownBounds), viewport, orbit, is3D, width, height) ?: return@Canvas
        val origin = center.offset()
        if (!enabled) return@Canvas
        val visibleControls = gizmoHandles(bounds, viewport, orbit, is3D, width, height, density, resizeFrame, latestPreview, allowResize, mode,pivotOverride)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12f * this@Canvas.density
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        for (control in visibleControls) {
            val end = control.center.offset()
            val color = when (control.handle) {
                GizmoHandle.MOVE_X, GizmoHandle.ROTATE_X -> Color(0xFFD64747)
                GizmoHandle.MOVE_Y, GizmoHandle.ROTATE_Y -> Color(0xFF23965C)
                GizmoHandle.MOVE_Z, GizmoHandle.ROTATE_Z -> Color(0xFF3976D2)
                GizmoHandle.RESIZE_WIDTH_NEGATIVE, GizmoHandle.RESIZE_WIDTH_POSITIVE -> Color(0xFFD64747)
                GizmoHandle.RESIZE_DEPTH_NEGATIVE, GizmoHandle.RESIZE_DEPTH_POSITIVE -> Color(0xFF23965C)
                GizmoHandle.RESIZE_HEIGHT -> Color(0xFF3976D2)
                else -> accent
            }
            when (control.handle) {
                GizmoHandle.MOVE_FREE -> {
                    val half = 26f * this.density
                    drawRoundRect(background, end - Offset(half, half), Size(half * 2, half * 2),
                        androidx.compose.ui.geometry.CornerRadius(3f * this.density))
                    drawRoundRect(color, end - Offset(half, half), Size(half * 2, half * 2),
                        androidx.compose.ui.geometry.CornerRadius(3f * this.density), style = Stroke(2f * this.density))
                    val arm = 15f * this.density
                    val head = 5f * this.density
                    for (direction in listOf(Offset(1f, 0f), Offset(-1f, 0f), Offset(0f, 1f), Offset(0f, -1f))) {
                        val tip = end + direction * arm
                        val back = tip - direction * head
                        val side = Offset(-direction.y, direction.x) * head
                        drawLine(color, end, tip, 2.5f * this.density)
                        drawLine(color, tip, back + side, 2.5f * this.density)
                        drawLine(color, tip, back - side, 2.5f * this.density)
                    }
                }
                GizmoHandle.MOVE_X, GizmoHandle.MOVE_Y, GizmoHandle.MOVE_Z -> {
                    drawLine(color, origin, end, 2.5f * this.density)
                    drawCircle(background.copy(alpha = .95f), 24f * this.density, end)
                    drawCircle(color, 24f * this.density, end, style = Stroke(2f * this.density))
                    val direction = end - origin
                    val unit = direction / direction.getDistance()
                    val perpendicular = Offset(-unit.y, unit.x)
                    val tip = end + unit * (12f * this.density)
                    val back = end - unit * (2f * this.density)
                    drawLine(color, end - unit * (12f * this.density), tip, 2.5f * this.density)
                    drawLine(color, tip, back + perpendicular * (7f * this.density), 2.5f * this.density)
                    drawLine(color, tip, back - perpendicular * (7f * this.density), 2.5f * this.density)
                    val label = when (control.handle) { GizmoHandle.MOVE_X -> "X"; GizmoHandle.MOVE_Y -> "Y"; else -> "Z" }
                    paint.color = color.toArgb()
                    val textAt = end + perpendicular * (17f * this.density)
                    drawContext.canvas.nativeCanvas.drawText(label, textAt.x, textAt.y + 4f * this.density, paint)
                }
                GizmoHandle.ROTATE_X, GizmoHandle.ROTATE_Y, GizmoHandle.ROTATE_Z -> {
                    val ringWidth = if (activeHandle == control.handle) 4f else 2.5f
                    control.rotationRing.zipWithNext().forEach { (a, b) ->
                        drawLine(color.copy(alpha = if (activeHandle == null || activeHandle == control.handle) .95f else .45f),
                            a.offset(), b.offset(), ringWidth * this.density)
                    }
                    drawCircle(background, 24f * this.density, end)
                    drawCircle(color, 24f * this.density, end, style = Stroke(2.5f * this.density))
                    val label = when (control.handle) { GizmoHandle.ROTATE_X -> "X"; GizmoHandle.ROTATE_Y -> "Y"; else -> "Z" }
                    paint.color = color.toArgb()
                    drawContext.canvas.nativeCanvas.drawText(label, end.x, end.y + 4f * this.density, paint)
                }
                GizmoHandle.SCALE -> {
                    drawLine(color, origin, end, 1.5f * this.density, pathEffect = dash)
                    drawRect(background, end - Offset(24f, 24f) * this.density, Size(48f, 48f) * this.density)
                    drawRect(color, end - Offset(24f, 24f) * this.density, Size(48f, 48f) * this.density, style = Stroke(2f * this.density))
                }
                else -> {
                    control.anchor?.let { anchor ->
                        if (hypot(anchor.x - control.center.x, anchor.y - control.center.y) > 3 * density)
                            drawLine(color.copy(alpha = .65f), anchor.offset(), end, this.density, pathEffect = dash)
                    }
                    drawRect(background, end - Offset(24f, 24f) * this.density, Size(48f, 48f) * this.density)
                    drawRect(color, end - Offset(24f, 24f) * this.density, Size(48f, 48f) * this.density,
                        style = Stroke(2f * this.density))
                    val text = when (control.handle) {
                        GizmoHandle.RESIZE_WIDTH_NEGATIVE, GizmoHandle.RESIZE_WIDTH_POSITIVE -> "W"
                        GizmoHandle.RESIZE_DEPTH_NEGATIVE, GizmoHandle.RESIZE_DEPTH_POSITIVE -> "D"
                        GizmoHandle.RESIZE_HEIGHT -> "H"
                        else -> null
                    }
                    if (text != null) {
                        paint.color = color.toArgb()
                        drawContext.canvas.nativeCanvas.drawText(text, end.x, end.y + 4f * this.density, paint)
                    }
                }
            }
        }
        if (mode == GizmoMode.ROTATE && rotationAngleDegrees != null) {
            paint.color = accent.toArgb()
            paint.textSize = 16f * this.density
            val text = angleLabel(rotationAngleDegrees)
            val textWidth = paint.measureText(text)
            val margin = textWidth / 2 + 12f * this.density
            val labelX = if (size.width < margin * 2) size.width / 2 else origin.x.coerceIn(margin, size.width - margin)
            val labelY = (origin.y + 156f * this.density).coerceIn(28f * this.density, max(28f * this.density, size.height - 12f * this.density))
            val at = Offset(labelX, labelY)
            drawRoundRect(background.copy(alpha = .96f), at - Offset(textWidth / 2 + 12f * this.density, 22f * this.density),
                Size(textWidth + 24f * this.density, 32f * this.density), androidx.compose.ui.geometry.CornerRadius(6f * this.density))
            drawContext.canvas.nativeCanvas.drawText(text, at.x, at.y, paint)
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

private fun angleLabel(angle: Double): String = String.format(Locale.getDefault(), "%+.0f°", angle)

private fun distanceToRing(point: Point2, ring: List<Point2>): Double = ring.zipWithNext().minOfOrNull { (a, b) ->
    val dx = b.x - a.x; val dy = b.y - a.y
    val length = dx * dx + dy * dy
    val t = if (length < 1e-12) 0.0 else (((point.x - a.x) * dx + (point.y - a.y) * dy) / length).coerceIn(0.0, 1.0)
    hypot(point.x - a.x - t * dx, point.y - a.y - t * dy)
} ?: Double.POSITIVE_INFINITY
