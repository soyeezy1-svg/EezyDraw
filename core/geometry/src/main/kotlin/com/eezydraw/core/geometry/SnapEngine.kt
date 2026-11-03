package com.eezydraw.core.geometry

import com.eezydraw.core.model.Point2
import com.eezydraw.core.model.SnapMode
import com.eezydraw.core.model.SnapSettings
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.round

data class SnapResult(val point: Point2, val mode: SnapMode?, val elementId: String? = null)

/** Pixel-controlled capture tolerance is converted to metres by the calling viewport. */
class SnapEngine(private val index: SpatialIndex) {
    fun snap(point: Point2, settings: SnapSettings, tolerance: Double, anchor: Point2? = null,
        levelId: String? = null): SnapResult {
        if (!settings.enabled || settings.modes.isEmpty() || !tolerance.isFinite() || tolerance <= 0)
            return SnapResult(point, null)
        val modes = settings.modes
        // Ortho is a continuous construction constraint. A horizontal/vertical tie chooses horizontal.
        val ortho = SnapMode.ORTHO in modes && anchor != null
        val horizontal = ortho && abs(point.y - anchor!!.y) <= abs(point.x - anchor.x)
        val cursor = if (ortho) {
            if (horizontal) Point2(point.x, anchor!!.y) else Point2(anchor!!.x, point.y)
        } else point
        var best: Candidate? = null
        val epsilon = max(1e-10, tolerance * 1e-8)
        fun consider(candidate: Point2, mode: SnapMode, elementId: String? = null) {
            if (ortho && if (horizontal) abs(candidate.y - anchor!!.y) > epsilon else abs(candidate.x - anchor!!.x) > epsilon) return
            val distance = hypot(candidate.x - cursor.x, candidate.y - cursor.y)
            if (!distance.isFinite() || distance > tolerance) return
            val new = Candidate(candidate, mode, elementId, distance)
            val old = best
            if (old == null || distance < old.distance - epsilon ||
                (abs(distance - old.distance) <= epsilon && compareCandidates(new, old) < 0)) best = new
        }
        if (SnapMode.GRID in modes) {
            fun grid(value: Double): Double = round(value / settings.gridSpacing) * settings.gridSpacing
            val x = if (ortho && !horizontal) anchor!!.x else grid(cursor.x)
            val y = if (ortho && horizontal) anchor!!.y else grid(cursor.y)
            if (x.isFinite() && y.isFinite()) consider(Point2(x, y), SnapMode.GRID)
        }
        val geometryModes = modes.any { it != SnapMode.GRID && it != SnapMode.ORTHO }
        val segments = if (geometryModes) index.queryNear2D(cursor, tolerance, levelId = levelId) else emptyList()
        for (segment in segments) {
            if (SnapMode.ENDPOINT in modes) {
                consider(Point2(segment.a.x, segment.a.y), SnapMode.ENDPOINT, segment.elementId)
                consider(Point2(segment.b.x, segment.b.y), SnapMode.ENDPOINT, segment.elementId)
            }
            if (SnapMode.MIDPOINT in modes) consider(Point2(segment.a.x / 2 + segment.b.x / 2,
                segment.a.y / 2 + segment.b.y / 2), SnapMode.MIDPOINT, segment.elementId)
            if (SnapMode.PERPENDICULAR in modes && anchor != null) {
                perpendicular(anchor, segment)?.let { consider(it, SnapMode.PERPENDICULAR, segment.elementId) }
            }
        }
        if (SnapMode.INTERSECTION in modes) {
            // The index returns nearest edges first. A fixed pair budget prevents dense drawings from
            // making a pointer event quadratic in the full document size.
            val nearby = segments.take(64)
            for (i in nearby.indices) for (j in i + 1 until nearby.size) {
                intersection(nearby[i], nearby[j])?.let { consider(it, SnapMode.INTERSECTION, nearby[i].elementId) }
            }
        }
        return best?.let { SnapResult(it.point, it.mode, it.elementId) }
            ?: SnapResult(cursor, if (ortho) SnapMode.ORTHO else null)
    }

    private data class Candidate(val point: Point2, val mode: SnapMode, val elementId: String?, val distance: Double)

    private fun compareCandidates(a: Candidate, b: Candidate): Int {
        val priority = priority(a.mode).compareTo(priority(b.mode))
        if (priority != 0) return priority
        val id = (a.elementId ?: "").compareTo(b.elementId ?: "")
        if (id != 0) return id
        val x = a.point.x.compareTo(b.point.x)
        return if (x != 0) x else a.point.y.compareTo(b.point.y)
    }

    private fun priority(mode: SnapMode): Int = when (mode) {
        SnapMode.ENDPOINT -> 0
        SnapMode.INTERSECTION -> 1
        SnapMode.MIDPOINT -> 2
        SnapMode.PERPENDICULAR -> 3
        SnapMode.ORTHO -> 4
        SnapMode.GRID -> 5
    }

    private fun perpendicular(anchor: Point2, segment: SceneSegment): Point2? {
        val dx = segment.b.x - segment.a.x
        val dy = segment.b.y - segment.a.y
        val length = hypot(dx, dy)
        if (!length.isFinite() || length <= 1e-12) return null
        val ux = dx / length
        val uy = dy / length
        val distance = (anchor.x - segment.a.x) * ux + (anchor.y - segment.a.y) * uy
        if (!distance.isFinite() || distance < 0 || distance > length) return null
        val x = segment.a.x + ux * distance
        val y = segment.a.y + uy * distance
        return if (x.isFinite() && y.isFinite()) Point2(x, y) else null
    }

    private fun intersection(a: SceneSegment, b: SceneSegment): Point2? {
        // Subtract a local origin before products so metre-sized edges at survey coordinates do
        // not lose their intersection through subtraction of enormous world-coordinate products.
        val ax = a.b.x - a.a.x
        val ay = a.b.y - a.a.y
        val bx = b.b.x - b.a.x
        val by = b.b.y - b.a.y
        val denominator = ax * by - ay * bx
        val scale = hypot(ax, ay) * hypot(bx, by)
        if (!scale.isFinite() || scale <= 1e-24 || abs(denominator) <= scale * 1e-12) return null
        val offsetX = b.a.x - a.a.x
        val offsetY = b.a.y - a.a.y
        val t = (offsetX * by - offsetY * bx) / denominator
        val u = (offsetX * ay - offsetY * ax) / denominator
        if (t !in -1e-12..1.000000000001 || u !in -1e-12..1.000000000001) return null
        val x = a.a.x + t.coerceIn(0.0, 1.0) * ax
        val y = a.a.y + t.coerceIn(0.0, 1.0) * ay
        return if (x.isFinite() && y.isFinite()) Point2(x, y) else null
    }
}
