package com.eezydraw.core.geometry

import com.eezydraw.core.model.Point2
import com.eezydraw.core.model.Point3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialIndexTest {
    @Test fun hundredThousandSpreadEdgesUseSpatialFilteringInsteadOfDocumentScan() {
        val segments = List(100_000) { index ->
            val x = (index % 1000) * 10.0
            val y = (index / 1000) * 10.0
            SceneSegment(Point3(x, y), Point3(x + 0.5, y), "edge-$index", null, 0xFF334455)
        }
        val result = SpatialIndex(segments).queryNear2DWithStats(Point2(5000.2, 500.0), 0.5)
        assertEquals(listOf("edge-50500"), result.segments.map { it.elementId })
        assertTrue("Visited ${result.visitedNodes} BVH nodes", result.visitedNodes < 100)
        assertTrue("Tested ${result.testedSegments} edges", result.testedSegments < 100)
    }

    @Test fun longSegmentIsFoundEvenWhenBothEndpointsAreFarAway() {
        val segment = SceneSegment(Point3(-1e6, 3.0), Point3(1e6, 3.0), "long", null, 0xFF334455)
        assertEquals(listOf(segment), SpatialIndex(listOf(segment)).queryNear2D(Point2(0.0, 3.01), 0.1))
    }

    @Test fun queryUsesActualSegmentDistanceAndReturnsNearestFirst() {
        val diagonal = SceneSegment(Point3(-10.0, -10.0), Point3(10.0, 10.0), "diagonal", null, 0xFF334455)
        val far = SceneSegment(Point3(-5.0, 0.1), Point3(5.0, 0.1), "far", null, 0xFF334455)
        val near = SceneSegment(Point3(-5.0, 0.01), Point3(5.0, 0.01), "near", null, 0xFF334455)
        val index = SpatialIndex(listOf(diagonal, far, near))
        assertTrue(index.queryNear2D(Point2(-5.0, 5.0), 0.1).isEmpty())
        assertEquals(listOf("diagonal", "near"), index.queryNear2D(Point2(0.0, 0.0), 0.2, 2).map { it.elementId })
    }

    @Test fun denseOverlapCapsCandidateAndTraversalWork() {
        val segments = List(100_000) { index ->
            SceneSegment(Point3(-1.0, -1.0), Point3(1.0, 1.0), "edge-$index", null, 0xFF334455)
        }
        val index = SpatialIndex(segments)
        // Both an exact hit and a small offset matter: in the latter case all node bounds
        // have a smaller distance than the actual edges, exercising the fallback budget.
        for (point in listOf(Point2(0.0, 0.0), Point2(0.0, 0.02))) {
            val result = index.queryNear2DWithStats(point, 0.1, 32)
            assertEquals(32, result.segments.size)
            assertTrue("Tested ${result.testedSegments} edges", result.testedSegments < 1000)
            assertTrue("Visited ${result.visitedNodes} nodes", result.visitedNodes < 1000)
        }
    }

    @Test fun selectedLevelIncludesGlobalAndNullLevelIncludesAll() {
        fun edge(id: String, level: String?) = SceneSegment(Point3(), Point3(1.0, 0.0), id, level, 0xFF334455)
        val index = SpatialIndex(listOf(edge("global", null), edge("first", "L1"), edge("second", "L2")))
        assertEquals(setOf("global", "first"), index.queryNear2D(Point2(0.0, 0.0), 0.1, levelId = "L1").map { it.elementId }.toSet())
        assertEquals(3, index.queryNear2D(Point2(0.0, 0.0), 0.1).size)
    }
}
