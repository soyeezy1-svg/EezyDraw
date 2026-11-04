package com.eezydraw.core.editing

import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.Viewport2D
import com.eezydraw.core.model.Bounds3
import com.eezydraw.core.model.Element
import com.eezydraw.core.model.Point2
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.ProjectDocument
import com.eezydraw.core.model.Transform3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

enum class ResizeHandle {
    WIDTH_NEGATIVE, WIDTH_POSITIVE, DEPTH_NEGATIVE, DEPTH_POSITIVE,
    CORNER_MIN_MIN, CORNER_MAX_MIN, CORNER_MAX_MAX, CORNER_MIN_MAX,
    HEIGHT,
}

/**
 * The raw local bounds are shared with Properties. Resize handles use sorted, scaled
 * coordinates before rotation, so reflected objects retain intuitive physical sides.
 */
data class ResizeFrame(val localBounds: Bounds3, val transform: Transform3) {
    private val orientation = editRotationMatrix(transform.rotation)
    private val physicalBounds = run {
        fun ordered(a: Double, b: Double) = minOf(a, b) to maxOf(a, b)
        val x = ordered(localBounds.min.x * transform.scale.x, localBounds.max.x * transform.scale.x)
        val y = ordered(localBounds.min.y * transform.scale.y, localBounds.max.y * transform.scale.y)
        val z = ordered(localBounds.min.z * transform.scale.z, localBounds.max.z * transform.scale.z)
        Bounds3(Point3(x.first, y.first, z.first), Point3(x.second, y.second, z.second))
    }

    val size = Point3(physicalBounds.max.x - physicalBounds.min.x,
        physicalBounds.max.y - physicalBounds.min.y, physicalBounds.max.z - physicalBounds.min.z)

    /** Project a point in the shape's original unscaled local coordinates into metres. */
    fun worldPoint(local: Point3): Point3 = orientedPoint(Point3(
        local.x * transform.scale.x, local.y * transform.scale.y, local.z * transform.scale.z,
    ))

    /** Positive orthonormal local dimension direction in project coordinates. */
    fun axis(index: Int): Point3 {
        require(index in 0..2)
        return Point3(orientation[index], orientation[3 + index], orientation[6 + index])
    }

    fun supports(handle: ResizeHandle): Boolean = when (handle) {
        ResizeHandle.WIDTH_NEGATIVE, ResizeHandle.WIDTH_POSITIVE -> size.x > 0.0
        ResizeHandle.DEPTH_NEGATIVE, ResizeHandle.DEPTH_POSITIVE -> size.y > 0.0
        ResizeHandle.HEIGHT -> size.z > 0.0
        else -> size.x > 0.0 && size.y > 0.0
    }

    fun handlePoint(handle: ResizeHandle): Point3 = orientedPoint(pointFor(handle, opposite = false))

    /** The opposite edge/corner, or physical base for the top handle, stays fixed. */
    fun pivotPoint(handle: ResizeHandle): Point3 = orientedPoint(pointFor(handle, opposite = true))

    private fun pointFor(handle: ResizeHandle, opposite: Boolean): Point3 {
        val lo = physicalBounds.min; val hi = physicalBounds.max
        val midX = lo.x + (hi.x - lo.x) / 2
        val midY = lo.y + (hi.y - lo.y) / 2
        fun end(minimum: Boolean, low: Double, high: Double) = if (minimum xor opposite) low else high
        return when (handle) {
            ResizeHandle.WIDTH_NEGATIVE -> Point3(end(true, lo.x, hi.x), midY, lo.z)
            ResizeHandle.WIDTH_POSITIVE -> Point3(end(false, lo.x, hi.x), midY, lo.z)
            ResizeHandle.DEPTH_NEGATIVE -> Point3(midX, end(true, lo.y, hi.y), lo.z)
            ResizeHandle.DEPTH_POSITIVE -> Point3(midX, end(false, lo.y, hi.y), lo.z)
            ResizeHandle.CORNER_MIN_MIN -> Point3(end(true, lo.x, hi.x), end(true, lo.y, hi.y), lo.z)
            ResizeHandle.CORNER_MAX_MIN -> Point3(end(false, lo.x, hi.x), end(true, lo.y, hi.y), lo.z)
            ResizeHandle.CORNER_MAX_MAX -> Point3(end(false, lo.x, hi.x), end(false, lo.y, hi.y), lo.z)
            ResizeHandle.CORNER_MIN_MAX -> Point3(end(true, lo.x, hi.x), end(false, lo.y, hi.y), lo.z)
            ResizeHandle.HEIGHT -> Point3(midX, midY, if (opposite) lo.z else hi.z)
        }
    }

    private fun orientedPoint(p: Point3) = Point3(
        transform.translation.x + orientation[0] * p.x + orientation[1] * p.y + orientation[2] * p.z,
        transform.translation.y + orientation[3] * p.x + orientation[4] * p.y + orientation[5] * p.z,
        transform.translation.z + orientation[6] * p.x + orientation[7] * p.y + orientation[8] * p.z,
    )
}

fun resizeFrame(document: ProjectDocument, element: Element): ResizeFrame =
    ResizeFrame(elementLocalBounds(document, element), element.transform)

/** Corner resizing needs a visible base plane; an edge-on plane has no unique XY pick. */
fun resizePlaneVisible(frame: ResizeFrame, orbit: OrbitCamera, is3D: Boolean): Boolean {
    val normal = frame.axis(2)
    if (!is3D) return abs(normal.z) >= 0.15
    val outward = Point3(cos(orbit.pitch) * cos(orbit.yaw), cos(orbit.pitch) * sin(orbit.yaw), sin(orbit.pitch))
    return abs(normal dot outward) >= 0.15
}

/**
 * Converts absolute screen positions to local physical dimension ratios. Side controls
 * alter one dimension, base corners alter width/depth, and Height preserves the base.
 * A locked corner ratio affects width/depth only; it never changes a solid's height.
 */
fun resizeDelta(
    frame: ResizeFrame,
    handle: ResizeHandle,
    start: Point2,
    current: Point2,
    viewport: Viewport2D,
    orbit: OrbitCamera,
    is3D: Boolean,
    width: Double,
    height: Double,
    keepProportions: Boolean = false,
): EditDelta {
    val pivot = frame.pivotPoint(handle)
    fun result(x: Double = 1.0, y: Double = 1.0, z: Double = 1.0) = EditDelta(
        pivot = pivot, axisScale = Point3(x, y, z), axes = frame.transform.rotation,
    )
    if (!frame.supports(handle) || start == current || width <= 0.0 || height <= 0.0 ||
        !width.isFinite() || !height.isFinite()) return result()
    fun ratio(size: Double, change: Double) = (1.0 + change / size).let {
        if (it.isNaN()) 1.0 else it.coerceIn(1e-6, 1e6)
    }
    fun axisAmount(index: Int): Double {
        val axis = frame.axis(index)
        if (!is3D) {
            val denominator = axis.x * axis.x + axis.y * axis.y
            if (denominator < 1e-8) return 0.0
            val a = viewport.screenToWorld(start.x, start.y, width, height)
            val b = viewport.screenToWorld(current.x, current.y, width, height)
            return ((b.x - a.x) * axis.x + (b.y - a.y) * axis.y) / denominator
        }
        val displacement = screenAxisDrag(axis, pivot, start, current, viewport, orbit, true, width, height)
        return displacement dot axis
    }
    return when (handle) {
        ResizeHandle.WIDTH_NEGATIVE -> result(x = ratio(frame.size.x, -axisAmount(0)))
        ResizeHandle.WIDTH_POSITIVE -> result(x = ratio(frame.size.x, axisAmount(0)))
        ResizeHandle.DEPTH_NEGATIVE -> result(y = ratio(frame.size.y, -axisAmount(1)))
        ResizeHandle.DEPTH_POSITIVE -> result(y = ratio(frame.size.y, axisAmount(1)))
        ResizeHandle.HEIGHT -> result(z = ratio(frame.size.z, axisAmount(2)))
        else -> {
            if (!resizePlaneVisible(frame, orbit, is3D)) return result()
            val first = planePick(frame, pivot, start, viewport, orbit, is3D, width, height) ?: return result()
            val last = planePick(frame, pivot, current, viewport, orbit, is3D, width, height) ?: return result()
            val drag = Point3(last.x - first.x, last.y - first.y, last.z - first.z)
            val negativeX = handle == ResizeHandle.CORNER_MIN_MIN || handle == ResizeHandle.CORNER_MIN_MAX
            val negativeY = handle == ResizeHandle.CORNER_MIN_MIN || handle == ResizeHandle.CORNER_MAX_MIN
            val x = ratio(frame.size.x, (drag dot frame.axis(0)) * if (negativeX) -1.0 else 1.0)
            val y = ratio(frame.size.y, (drag dot frame.axis(1)) * if (negativeY) -1.0 else 1.0)
            if (keepProportions) {
                val uniform = if (abs(x - 1.0) >= abs(y - 1.0)) x else y
                result(uniform, uniform)
            } else result(x, y)
        }
    }
}

private fun planePick(
    frame: ResizeFrame, pointOnPlane: Point3, screen: Point2,
    viewport: Viewport2D, orbit: OrbitCamera, is3D: Boolean, width: Double, height: Double,
): Point3? {
    val normal = frame.axis(2)
    val ray = if (is3D) cameraPickRay(orbit, screen.x, screen.y, width, height) else {
        val p = viewport.screenToWorld(screen.x, screen.y, width, height)
        PickRay(DragVector(p.x, p.y, 0.0), DragVector(0.0, 0.0, -1.0))
    }
    val direction = Point3(ray.direction.x, ray.direction.y, ray.direction.z)
    val denominator = direction dot normal
    if (abs(denominator) < 1e-10) return null
    val offset = Point3(pointOnPlane.x - ray.origin.x, pointOnPlane.y - ray.origin.y, pointOnPlane.z - ray.origin.z)
    val distance = (offset dot normal) / denominator
    if (!distance.isFinite() || is3D && distance < 0.0) return null
    val hit = ray.origin + ray.direction * distance
    return Point3(hit.x, hit.y, hit.z)
}

private infix fun Point3.dot(other: Point3): Double = x * other.x + y * other.y + z * other.z
