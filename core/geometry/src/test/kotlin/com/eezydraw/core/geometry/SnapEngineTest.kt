package com.eezydraw.core.geometry

import com.eezydraw.core.model.Point2
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.SnapMode
import com.eezydraw.core.model.SnapSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapEngineTest {
    @Test fun gridSnapsNegativeCoordinatesOnlyWithinCaptureDistance() {
        val engine = engine()
        val settings = settings(SnapMode.GRID)
        assertSnap(engine.snap(Point2(-0.26, -0.19), settings, 0.05), Point2(-0.3, -0.2), SnapMode.GRID)
        assertNull(engine.snap(Point2(-0.26, -0.19), settings, 0.01).mode)
    }

    @Test fun endpointAndMidpointUseSegmentGeometry() {
        val engine = engine(edge(-2.0, 0.0, 2.0, 0.0))
        assertSnap(engine.snap(Point2(1.97, 0.02), settings(SnapMode.ENDPOINT), 0.1), Point2(2.0, 0.0), SnapMode.ENDPOINT)
        assertSnap(engine.snap(Point2(0.03, -0.02), settings(SnapMode.MIDPOINT), 0.1), Point2(0.0, 0.0), SnapMode.MIDPOINT)
        assertNull(engine.snap(Point2(1.0, 0.01), settings(SnapMode.ENDPOINT), 0.1).mode)
    }

    @Test fun intersectionUsesFiniteSegmentsAndRelativeSurveyOrigin() {
        val engine = engine(edge(-2.0, 0.0, 2.0, 0.0), edge(0.0, -2.0, 0.0, 2.0, "vertical"))
        assertSnap(engine.snap(Point2(0.03, 0.04), settings(SnapMode.INTERSECTION), 0.1), Point2(0.0, 0.0), SnapMode.INTERSECTION)
        val extensions = engine(edge(-1.0, 0.0, 1.0, 0.0), edge(2.0, -1.0, 2.0, 1.0, "outside"))
        assertNull(extensions.snap(Point2(2.0, 0.0), settings(SnapMode.INTERSECTION), 3.0).mode)
        val origin = 1e9
        val survey = engine(edge(origin, origin, origin + 0.01, origin),
            edge(origin + 0.004, origin - 0.01, origin + 0.004, origin + 0.01, "cross"))
        val result = survey.snap(Point2(origin + 0.0041, origin + 0.0001), settings(SnapMode.INTERSECTION), 0.001)
        assertSnap(result, Point2(origin + 0.004, origin), SnapMode.INTERSECTION, 2e-7)
    }

    @Test fun parallelCollinearAndZeroLengthEdgesHaveNoInventedIntersection() {
        val engine = engine(edge(-1.0, 0.0, 1.0, 0.0), edge(0.0, 0.0, 3.0, 0.0, "collinear"),
            edge(0.0, 0.01, 3.0, 0.01, "parallel"), edge(0.0, 0.0, 0.0, 0.0, "zero"))
        assertNull(engine.snap(Point2(0.0, 0.0), settings(SnapMode.INTERSECTION), 0.1).mode)
    }

    @Test fun perpendicularRequiresAnchorAndFootInsideNondegenerateSegment() {
        val engine = engine(edge(-2.0, 0.0, 2.0, 0.0))
        val settings = settings(SnapMode.PERPENDICULAR)
        assertSnap(engine.snap(Point2(0.73, 0.02), settings, 0.1, Point2(0.7, 2.0)), Point2(0.7, 0.0), SnapMode.PERPENDICULAR)
        assertNull(engine.snap(Point2(0.73, 0.02), settings, 0.1).mode)
        assertNull(engine.snap(Point2(-3.0, 0.0), settings, 2.0, Point2(-3.0, 1.0)).mode)
        assertNull(engine(edge(0.0, 0.0, 0.0, 0.0)).snap(Point2(0.0, 0.0), settings, 0.1, Point2(0.0, 1.0)).mode)
    }

    @Test fun orthoContinuouslyConstrainsNearestAxisAndHorizontalWinsTie() {
        val engine = engine()
        val settings = settings(SnapMode.ORTHO)
        assertSnap(engine.snap(Point2(10.0, 3.0), settings, 0.1, Point2(0.0, 0.0)), Point2(10.0, 0.0), SnapMode.ORTHO)
        assertSnap(engine.snap(Point2(3.0, 10.0), settings, 0.1, Point2(0.0, 0.0)), Point2(0.0, 10.0), SnapMode.ORTHO)
        assertSnap(engine.snap(Point2(1.0, 1.0), settings, 0.1, Point2(0.0, 0.0)), Point2(1.0, 0.0), SnapMode.ORTHO)
        assertNull(engine.snap(Point2(1.0, 1.0), settings, 0.1).mode)
    }

    @Test fun gridAndGeometricSnapsRespectOrthoFixedCoordinate() {
        val anchor = Point2(0.037, 0.043)
        val grid = engine().snap(Point2(0.276, 0.1), settings(SnapMode.GRID, SnapMode.ORTHO), 0.04, anchor)
        assertSnap(grid, Point2(0.3, 0.043), SnapMode.GRID)
        val offAxis = engine(edge(0.276, 0.055, 0.9, 0.6)).snap(Point2(0.276, 0.1),
            settings(SnapMode.ENDPOINT, SnapMode.ORTHO), 0.04, anchor)
        assertSnap(offAxis, Point2(0.276, 0.043), SnapMode.ORTHO)
        val onAxis = engine(edge(0.3, 0.043, 0.9, 0.6)).snap(Point2(0.29, 0.1),
            settings(SnapMode.ENDPOINT, SnapMode.ORTHO), 0.04, anchor)
        assertSnap(onAxis, Point2(0.3, 0.043), SnapMode.ENDPOINT)
    }

    @Test fun disabledEmptyAndInvalidToleranceLeaveInputUntouched() {
        val point = Point2(0.01, 0.01)
        val engine = engine(edge(0.0, 0.0, 1.0, 0.0))
        for (settings in listOf(SnapSettings(enabled = false), SnapSettings(modes = emptySet()))) {
            assertEquals(SnapResult(point, null), engine.snap(point, settings, 0.1, Point2(0.0, 0.0)))
        }
        assertEquals(SnapResult(point, null), engine.snap(point, SnapSettings(), Double.NaN))
    }

    @Test fun geometricPriorityAndElementIdBreakEqualDistanceTiesDeterministically() {
        val all = SnapSettings(modes = SnapMode.entries.toSet())
        val result = engine(edge(-1.0, 0.0, 0.0, 0.0, "z"), edge(0.0, -1.0, 0.0, 1.0, "vertical"),
            edge(0.0, 0.0, 1.0, 0.0, "a")).snap(Point2(0.01, 0.01), all, 0.1)
        assertSnap(result, Point2(0.0, 0.0), SnapMode.ENDPOINT)
        assertEquals("a", result.elementId)
    }

    @Test fun chosenLevelIncludesGlobalEdgesAndExcludesOtherLevels() {
        val engine = engine(edge(0.0, 0.0, 1.0, 0.0, "other", "L2"),
            edge(0.02, 0.0, 1.0, 0.0, "global"), edge(0.04, 0.0, 1.0, 0.0, "local", "L1"))
        val result = engine.snap(Point2(0.001, 0.0), settings(SnapMode.ENDPOINT), 0.1, levelId = "L1")
        assertEquals("global", result.elementId)
    }

    private fun engine(vararg segments: SceneSegment) = SnapEngine(SpatialIndex(segments.toList()))
    private fun edge(ax: Double, ay: Double, bx: Double, by: Double, id: String = "edge", level: String? = null) =
        SceneSegment(Point3(ax, ay), Point3(bx, by), id, level, 0xFF334455)
    private fun settings(vararg modes: SnapMode) = SnapSettings(modes = modes.toSet(), gridSpacing = 0.1)
    private fun assertSnap(result: SnapResult, expected: Point2, mode: SnapMode, tolerance: Double = 1e-9) {
        assertEquals(mode, result.mode)
        assertEquals(expected.x, result.point.x, tolerance)
        assertEquals(expected.y, result.point.y, tolerance)
    }
}
