package com.eezydraw.core.editing

import com.eezydraw.core.geometry.DrawingScene
import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.SceneSegment
import com.eezydraw.core.geometry.SnapEngine
import com.eezydraw.core.geometry.SpatialIndex
import com.eezydraw.core.geometry.Viewport2D
import com.eezydraw.core.model.ElementId
import com.eezydraw.core.model.Point2
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.ProjectDocument
import com.eezydraw.core.model.SnapMode
import com.eezydraw.core.model.SnapSettings
import com.eezydraw.core.model.measurementShape
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tan

/**
 * Immutable gesture index, prepared once for a selection rather than on every pointer event.
 * Selected objects are excluded so a moving item cannot snap to its original wireframe.
 */
class MoveContext(document: ProjectDocument, selectedIds: Set<ElementId>, scene: DrawingScene) {
    val projectId = document.project.id
    private val selected = selectedIds.mapTo(HashSet()) { it.value }.also { excluded ->
        // Associative annotations follow a selected host and must not attract that host
        // back to its old position while the next document snapshot is being prepared.
        document.elements.forEach { element ->
            if (measurementShape(element)?.measurement?.anchors?.any { it.elementId in selectedIds } == true)
                excluded.add(element.id.value)
        }
    }
    private val index = SpatialIndex(scene.segments.filterNot { it.elementId in selected })
    private val planSnaps = SnapEngine(index)

    /** Null [axis] moves freely in plan XY or the camera-facing plane through [pivot]. */
    fun delta(
        pivot: Point3,
        start: Point2,
        current: Point2,
        axis: Point3?,
        viewport: Viewport2D,
        orbit: OrbitCamera,
        is3D: Boolean,
        width: Double,
        height: Double,
        snapSettings: SnapSettings,
        tolerancePixels: Double,
        levelId: String? = null,
    ): EditDelta {
        if (start == current || width <= 0.0 || height <= 0.0 || !width.isFinite() || !height.isFinite()) return EditDelta(pivot)
        val unitAxis = axis?.let(::normalized)
        val displacement = if (unitAxis != null) {
            screenAxisDrag(unitAxis, pivot, start, current, viewport, orbit, is3D, width, height)
        } else if (!is3D) {
            val first = viewport.screenToWorld(start.x, start.y, width, height)
            val last = viewport.screenToWorld(current.x, current.y, width, height)
            Point3(last.x - first.x, last.y - first.y)
        } else {
            val normal = facingNormal(orbit)
            val first = planePick(orbit, start, width, height, pivot, normal) ?: return EditDelta(pivot)
            val last = planePick(orbit, current, width, height, pivot, normal) ?: return EditDelta(pivot)
            last - first
        }
        if (magnitude(displacement) < 1e-12) return EditDelta(pivot)
        val candidate = pivot + displacement
        val snapped = if (!is3D) {
            // X/Y controls are always constraints even when Ortho is disabled in project snaps.
            val constrainedSettings = if (unitAxis != null && abs(unitAxis.z) < 1e-10 &&
                (abs(unitAxis.x) < 1e-10 || abs(unitAxis.y) < 1e-10))
                snapSettings.copy(modes = snapSettings.modes + SnapMode.ORTHO) else snapSettings
            val tolerance = max(snapSettings.tolerance, max(0.0, tolerancePixels) / viewport.pixelsPerMetre)
            val snap = planSnaps.snap(Point2(candidate.x, candidate.y), constrainedSettings, tolerance,
                Point2(pivot.x, pivot.y), levelId).point
            val p = Point3(snap.x, snap.y, pivot.z)
            if (unitAxis == null) p else pivot + unitAxis * ((p - pivot) dot unitAxis)
        } else snapOrbit(candidate, pivot, unitAxis, orbit, width, height, snapSettings, tolerancePixels, levelId)
        return EditDelta(pivot = pivot, translation = snapped - pivot)
    }

    private fun snapOrbit(
        candidate: Point3, pivot: Point3, axis: Point3?, camera: OrbitCamera,
        width: Double, height: Double, settings: SnapSettings, pixels: Double, levelId: String?,
    ): Point3 {
        if (!settings.enabled || settings.modes.isEmpty()) return candidate
        val normal = facingNormal(camera)
        val right = Point3(-sin(camera.yaw), cos(camera.yaw), 0.0)
        val up = Point3(-sin(camera.pitch) * cos(camera.yaw), -sin(camera.pitch) * sin(camera.yaw), cos(camera.pitch))
        val visibleHeight = if (camera.projection == com.eezydraw.core.model.Projection.PERSPECTIVE)
            2 * camera.distance * tan(OrbitCamera.FOV_Y / 2) else camera.orthographicHeight
        val tolerance = max(settings.tolerance, max(0.0, pixels) * visibleHeight / height)
        val epsilon = max(1e-7, max(Math.ulp(pivot.x), max(Math.ulp(pivot.y), Math.ulp(pivot.z))) * 8)
        var constraint = axis
        var cursor = candidate
        if (axis == null && SnapMode.ORTHO in settings.modes) {
            val offset = candidate - pivot
            constraint = if (abs(offset dot right) >= abs(offset dot up)) right else up
            cursor = pivot + constraint * (offset dot constraint)
        }
        fun onConstraint(point: Point3): Boolean = if (constraint != null) {
            val offset = point - pivot
            magnitude(offset - constraint * (offset dot constraint)) <= epsilon
        } else abs((point - pivot) dot normal) <= epsilon
        var best: Point3? = null
        var bestDistance = Double.POSITIVE_INFINITY
        var bestPriority = Int.MAX_VALUE
        fun consider(point: Point3, priority: Int) {
            if (!onConstraint(point)) return
            val distance = magnitude(point - cursor)
            if (!distance.isFinite() || distance > tolerance) return
            if (distance < bestDistance - epsilon || abs(distance - bestDistance) <= epsilon && priority < bestPriority) {
                best = point; bestDistance = distance; bestPriority = priority
            }
        }
        val near = index.queryNear2D(Point2(cursor.x, cursor.y), tolerance, levelId = levelId)
        for (segment in near) {
            if (SnapMode.ENDPOINT in settings.modes) { consider(segment.a, 0); consider(segment.b, 0) }
            if (SnapMode.MIDPOINT in settings.modes) consider(segment.a + (segment.b - segment.a) * .5, 2)
            if (SnapMode.PERPENDICULAR in settings.modes) {
                val edge = segment.b - segment.a
                val length2 = edge dot edge
                if (length2 > 1e-20) {
                    val fraction = ((pivot - segment.a) dot edge) / length2
                    if (fraction in 0.0..1.0) consider(segment.a + edge * fraction, 3)
                }
            }
        }
        if (SnapMode.INTERSECTION in settings.modes) {
            val edges = near.take(64)
            for (i in edges.indices) for (j in i + 1 until edges.size) intersection(edges[i], edges[j], epsilon)?.let { consider(it, 1) }
        }
        if (SnapMode.GRID in settings.modes) {
            fun grid(value: Double) = round(value / settings.gridSpacing) * settings.gridSpacing
            if (constraint != null) {
                val change = grid(cursor dot constraint) - (pivot dot constraint)
                if (change.isFinite()) consider(pivot + constraint * change, 5)
            } else {
                val r = grid(cursor dot right); val u = grid(cursor dot up)
                if (r.isFinite() && u.isFinite()) consider(cursor + right * (r - (cursor dot right)) + up * (u - (cursor dot up)), 5)
            }
        }
        return best ?: cursor
    }
}

private fun facingNormal(camera: OrbitCamera) = Point3(cos(camera.pitch) * cos(camera.yaw),
    cos(camera.pitch) * sin(camera.yaw), sin(camera.pitch))

private fun planePick(camera: OrbitCamera, screen: Point2, width: Double, height: Double, point: Point3, normal: Point3): Point3? {
    val ray = cameraPickRay(camera, screen.x, screen.y, width, height)
    val origin = Point3(ray.origin.x, ray.origin.y, ray.origin.z)
    val direction = Point3(ray.direction.x, ray.direction.y, ray.direction.z)
    val denominator = direction dot normal
    if (abs(denominator) < 1e-10) return null
    val t = ((point - origin) dot normal) / denominator
    if (!t.isFinite() || t < 0.0) return null
    return origin + direction * t
}

private fun intersection(a: SceneSegment, b: SceneSegment, epsilon: Double): Point3? {
    val u = a.b - a.a; val v = b.b - b.a; val w = a.a - b.a
    val aa = u dot u; val bb = u dot v; val cc = v dot v
    val dd = u dot w; val ee = v dot w
    val denominator = aa * cc - bb * bb
    if (aa <= 1e-20 || cc <= 1e-20 || abs(denominator) <= aa * cc * 1e-12) return null
    val s = (bb * ee - cc * dd) / denominator
    val t = (aa * ee - bb * dd) / denominator
    if (s !in 0.0..1.0 || t !in 0.0..1.0) return null
    val first = a.a + u * s; val second = b.a + v * t
    return if (magnitude(first - second) <= epsilon) first else null
}

private fun normalized(axis: Point3): Point3 {
    val length = magnitude(axis)
    require(length.isFinite() && length > 0) { "Move axis must be nonzero." }
    return axis * (1.0 / length)
}
private fun magnitude(p: Point3) = hypot(hypot(p.x, p.y), p.z)
private operator fun Point3.plus(other: Point3) = Point3(x + other.x, y + other.y, z + other.z)
private operator fun Point3.minus(other: Point3) = Point3(x - other.x, y - other.y, z - other.z)
private operator fun Point3.times(value: Double) = Point3(x * value, y * value, z * value)
private infix fun Point3.dot(other: Point3) = x * other.x + y * other.y + z * other.z
