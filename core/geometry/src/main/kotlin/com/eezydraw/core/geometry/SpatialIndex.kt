package com.eezydraw.core.geometry

import com.eezydraw.core.model.Point2
import java.util.Arrays
import java.util.PriorityQueue
import kotlin.math.max
import kotlin.math.min

/** Immutable balanced segment BVH. Long edges are indexed by their complete XY bounds. */
class SpatialIndex(segments: List<SceneSegment>) {
    private val entries = segments.mapIndexed { index, segment -> Entry(segment, index) }.toTypedArray()
    private val root = if (entries.isEmpty()) null else build(0, entries.size)

    fun queryNear2D(point: Point2, radius: Double, limit: Int = 2048, levelId: String? = null): List<SceneSegment> =
        queryNear2DWithStats(point, radius, limit, levelId).segments

    /** Counters support deterministic large-drawing regression checks without timing assertions. */
    fun queryNear2DWithStats(point: Point2, radius: Double, limit: Int = 2048, levelId: String? = null): SpatialQuery {
        if (root == null || !radius.isFinite() || radius < 0 || limit <= 0) return SpatialQuery(emptyList(), 0, 0)
        val cappedLimit = limit.coerceAtMost(2048)
        val radiusSquared = radius * radius
        val queue = PriorityQueue<Visit>(compareBy<Visit> { it.distance }
            .thenBy { if (it.entry == null) 1 else 0 }
            // Equal bounds are common in dense/stacked geometry. Descend to a leaf before
            // expanding sibling subtrees, so the bounded fallback always has local candidates.
            .thenBy { it.node?.let { node -> node.end - node.start } ?: 0 }
            .thenBy { it.order })
        var order = 0
        fun enqueue(node: Node) {
            val distance = node.distanceSquared(point)
            if (distance <= radiusSquared) queue.add(Visit(distance, order++, node = node))
        }
        enqueue(root)
        val result = ArrayList<SceneSegment>(min(cappedLimit, 32))
        var visitedNodes = 0
        var testedSegments = 0
        // Highly overlapping/degenerate input gets a deterministic local fallback budget.
        val nodeBudget = max(128, cappedLimit * 8)
        val segmentBudget = max(128, cappedLimit * 16)
        while (queue.isNotEmpty() && result.size < cappedLimit) {
            val visit = queue.remove()
            val entry = visit.entry
            if (entry != null) {
                result.add(entry.segment)
                continue
            }
            val node = checkNotNull(visit.node)
            if (++visitedNodes > nodeBudget || testedSegments >= segmentBudget) continue
            if (node.left != null) {
                enqueue(node.left)
                enqueue(checkNotNull(node.right))
            } else {
                for (i in node.start until node.end) {
                    if (testedSegments >= segmentBudget) break
                    val candidate = entries[i]
                    val segment = candidate.segment
                    if (levelId != null && segment.levelId != null && segment.levelId != levelId) continue
                    testedSegments++
                    val distance = distanceSquaredToSegment(point, segment)
                    if (distance <= radiusSquared) queue.add(Visit(distance, candidate.order, entry = candidate))
                }
            }
        }
        return SpatialQuery(result, visitedNodes, testedSegments)
    }

    private fun build(start: Int, end: Int): Node {
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (i in start until end) {
            val segment = entries[i].segment
            minX = min(minX, min(segment.a.x, segment.b.x))
            minY = min(minY, min(segment.a.y, segment.b.y))
            maxX = max(maxX, max(segment.a.x, segment.b.x))
            maxY = max(maxY, max(segment.a.y, segment.b.y))
        }
        if (end - start <= 12) return Node(minX, minY, maxX, maxY, start, end)
        val splitX = maxX - minX >= maxY - minY
        Arrays.sort(entries, start, end, Comparator { first, second ->
            val a = if (splitX) first.segment.a.x / 2 + first.segment.b.x / 2 else first.segment.a.y / 2 + first.segment.b.y / 2
            val b = if (splitX) second.segment.a.x / 2 + second.segment.b.x / 2 else second.segment.a.y / 2 + second.segment.b.y / 2
            val comparison = a.compareTo(b)
            if (comparison == 0) first.order.compareTo(second.order) else comparison
        })
        val middle = start + (end - start) / 2
        return Node(minX, minY, maxX, maxY, start, end, build(start, middle), build(middle, end))
    }

    private data class Entry(val segment: SceneSegment, val order: Int)
    private data class Visit(val distance: Double, val order: Int, val node: Node? = null, val entry: Entry? = null)
    private data class Node(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double,
        val start: Int, val end: Int, val left: Node? = null, val right: Node? = null) {
        fun distanceSquared(point: Point2): Double {
            val dx = max(0.0, max(minX - point.x, point.x - maxX))
            val dy = max(0.0, max(minY - point.y, point.y - maxY))
            return dx * dx + dy * dy
        }
    }

    companion object {
        internal fun distanceSquaredToSegment(point: Point2, segment: SceneSegment): Double {
            val dx = segment.b.x - segment.a.x
            val dy = segment.b.y - segment.a.y
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared > 0 && lengthSquared.isFinite())
                (((point.x - segment.a.x) * dx + (point.y - segment.a.y) * dy) / lengthSquared).coerceIn(0.0, 1.0) else 0.0
            val x = point.x - (segment.a.x + dx * t)
            val y = point.y - (segment.a.y + dy * t)
            return x * x + y * y
        }
    }
}

data class SpatialQuery(val segments: List<SceneSegment>, val visitedNodes: Int, val testedSegments: Int)
