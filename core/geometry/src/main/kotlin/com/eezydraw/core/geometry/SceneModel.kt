package com.eezydraw.core.geometry

import com.eezydraw.core.model.Bounds3
import com.eezydraw.core.model.Point3

/** Project-space doubles are retained for selection, measurements and snapping. */
data class SceneSegment(
    val a: Point3,
    val b: Point3,
    val elementId: String,
    val levelId: String?,
    val colorArgb: Long = 0xFF334455,
)

/**
 * Immutable after publication. GL_LINES vertices contain x, y, z, r, g, b, a.
 * Positions are relative to [origin]; [bounds] remains in double project coordinates.
 */
data class SceneChunk(
    val origin: Point3,
    val bounds: Bounds3,
    val levelId: String?,
    val vertices: FloatArray,
)

data class DrawingScene(
    val segments: List<SceneSegment>,
    val chunks: List<SceneChunk>,
    val bounds: Bounds3,
    val levelBounds: Map<String?, Bounds3> = emptyMap(),
) {
    /** Null selects every level. Unassigned geometry is visible with every selected level. */
    fun boundsForLevel(levelId: String?): Bounds3 {
        if (levelId == null) return bounds
        // Also support small hand-built snapshots that omit the precomputed level map.
        if (levelBounds.isEmpty()) {
            var selected: Bounds3? = null
            for (segment in segments) {
                if (segment.levelId != null && segment.levelId != levelId) continue
                val edgeBounds = Bounds3(
                    Point3(minOf(segment.a.x, segment.b.x), minOf(segment.a.y, segment.b.y), minOf(segment.a.z, segment.b.z)),
                    Point3(maxOf(segment.a.x, segment.b.x), maxOf(segment.a.y, segment.b.y), maxOf(segment.a.z, segment.b.z)),
                )
                selected = selected?.let { unionBounds(it, edgeBounds) } ?: edgeBounds
            }
            return selected ?: Bounds3(Point3(), Point3())
        }
        val assigned = levelBounds[levelId]
        val global = levelBounds[null]
        return when {
            assigned != null && global != null -> unionBounds(assigned, global)
            assigned != null -> assigned
            global != null -> global
            else -> Bounds3(Point3(), Point3())
        }
    }
}

internal fun unionBounds(a: Bounds3, b: Bounds3) = Bounds3(
    Point3(minOf(a.min.x, b.min.x), minOf(a.min.y, b.min.y), minOf(a.min.z, b.min.z)),
    Point3(maxOf(a.max.x, b.max.x), maxOf(a.max.y, b.max.y), maxOf(a.max.z, b.max.z)),
)
