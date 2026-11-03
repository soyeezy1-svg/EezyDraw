package com.eezydraw.core.geometry

import com.eezydraw.core.model.Bounds3
import com.eezydraw.core.model.Point2
import com.eezydraw.core.model.Point3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Viewport2DTest {
    @Test fun worldScreenRoundTripAndTouchDirection() {
        val viewport = Viewport2D(Point2(31.5, -20.25), 120.0)
        val point = Point2(32.125, -19.75)
        val screen = viewport.worldToScreen(point, 1600.0, 1000.0)
        assertPoint(point, viewport.screenToWorld(screen.x, screen.y, 1600.0, 1000.0))
        val dragged = viewport.pan(90.0, 60.0).worldToScreen(point, 1600.0, 1000.0)
        assertEquals(screen.x + 90, dragged.x, 1e-9)
        assertEquals(screen.y + 60, dragged.y, 1e-9)
    }

    @Test fun pinchKeepsAnchorFixedAtSurveyCoordinates() {
        val viewport = Viewport2D(Point2(1e9, -1e9), 25_000.0)
        val anchor = viewport.screenToWorld(123.0, 781.0, 1400.0, 1000.0)
        val zoomed = viewport.zoom(3.5, 123.0, 781.0, 1400.0, 1000.0)
        assertPoint(anchor, zoomed.screenToWorld(123.0, 781.0, 1400.0, 1000.0), 2e-7)
        val millimetre = Point2(1e9 + 0.001, -1e9 + 0.001)
        val screen = viewport.worldToScreen(millimetre, 1400.0, 1000.0)
        assertEquals(25.0, screen.x - 700, 0.002)
        assertEquals(-25.0, screen.y - 500, 0.002)
        assertPoint(millimetre, viewport.screenToWorld(screen.x, screen.y, 1400.0, 1000.0), 2e-7)
    }

    @Test fun extentsUseBothAxesWithPadding() {
        val bounds = Bounds3(Point3(-10.0, -5.0), Point3(30.0, 15.0))
        val viewport = Viewport2D().fit(bounds, 1000.0, 600.0, 50.0)
        assertPoint(Point2(10.0, 5.0), viewport.center)
        assertEquals(22.5, viewport.pixelsPerMetre, 1e-9)
        val low = viewport.worldToScreen(Point2(-10.0, -5.0), 1000.0, 600.0)
        val high = viewport.worldToScreen(Point2(30.0, 15.0), 1000.0, 600.0)
        assertTrue(low.x >= 50 && low.y <= 550 && high.x <= 950 && high.y >= 50)
    }

    @Test fun windowZoomWorksInBothDragDirectionsAndRejectsThinDrags() {
        val viewport = Viewport2D(pixelsPerMetre = 100.0)
        val next = viewport.zoomWindow(750.0, 450.0, 250.0, 150.0, 1000.0, 600.0)
        assertPoint(Point2(0.0, 0.0), next.center)
        assertEquals(200.0, next.pixelsPerMetre, 1e-9)
        assertEquals(viewport, viewport.zoomWindow(100.0, 100.0, 101.0, 400.0, 1000.0, 600.0))
        val bounds = next.visibleBounds(1000.0, 600.0)
        assertEquals(-2.5, bounds.min.x, 1e-9)
        assertEquals(1.5, bounds.max.y, 1e-9)
    }

    @Test fun extentsFitMillimetreDrawingsAndLinesAtTheirActualSize() {
        val viewport = Viewport2D()
        val square = viewport.fit(Bounds3(Point3(), Point3(0.001, 0.001)), 1000.0, 600.0, 50.0)
        assertEquals(500_000.0, square.pixelsPerMetre, 1e-6)
        assertPoint(Point2(0.0005, 0.0005), square.center)
        val horizontal = viewport.fit(Bounds3(Point3(), Point3(0.001, 0.0)), 1000.0, 600.0, 50.0)
        assertEquals(900_000.0, horizontal.pixelsPerMetre, 1e-6)
        val vertical = viewport.fit(Bounds3(Point3(), Point3(0.0, 0.001)), 1000.0, 600.0, 50.0)
        assertEquals(500_000.0, vertical.pixelsPerMetre, 1e-6)
        val point = viewport.fit(Bounds3(Point3(2.0, 3.0), Point3(2.0, 3.0)), 1000.0, 600.0, 50.0)
        assertEquals(5000.0, point.pixelsPerMetre, 1e-9)
        assertPoint(Point2(2.0, 3.0), point.center)
    }

    @Test fun smallWindowAtDeepZoomZoomsInRatherThanOut() {
        val viewport = Viewport2D(pixelsPerMetre = 100_000.0)
        val next = viewport.zoomWindow(250.0, 150.0, 750.0, 450.0, 1000.0, 600.0)
        assertEquals(200_000.0, next.pixelsPerMetre, 1e-6)
        assertPoint(viewport.center, next.center)
        val nearLimit = Viewport2D(pixelsPerMetre = 8_000_000.0)
            .zoomWindow(250.0, 150.0, 750.0, 450.0, 1000.0, 600.0)
        assertEquals(Viewport2D.MAX_SCALE, nearLimit.pixelsPerMetre, 0.0)
    }

    @Test fun scaleLimitsAndNonFiniteEventsRemainSafe() {
        val viewport = Viewport2D()
        assertEquals(Viewport2D.MAX_SCALE, viewport.zoom(1e300, 0.0, 0.0, 1000.0, 1000.0).pixelsPerMetre, 0.0)
        assertEquals(Viewport2D.MIN_SCALE, viewport.zoom(1e-300, 0.0, 0.0, 1000.0, 1000.0).pixelsPerMetre, 0.0)
        assertEquals(viewport, viewport.pan(Double.NaN, 1.0))
        assertEquals(viewport, viewport.zoom(Double.POSITIVE_INFINITY, 0.0, 0.0, 1000.0, 1000.0))
        assertEquals(viewport.center, viewport.screenToWorld(0.0, 0.0, 0.0, 0.0))
        assertTrue(viewport.worldToScreen(Point2(Double.MAX_VALUE, Double.MAX_VALUE), 100.0, 100.0).x.isFinite())
    }

    @Test fun adaptiveGridSuppressesSubpixelDensityWithoutChangingCloseZoomSpacing() {
        assertEquals(0.1, adaptiveGridStep(0.1, 1000.0), 1e-12)
        assertEquals(10.0, adaptiveGridStep(0.1, 10.0), 1e-12)
        assertTrue(adaptiveGridStep(0.001, 0.001) * 0.001 >= 20.0)
    }

    private fun assertPoint(expected: Point2, actual: Point2, tolerance: Double = 1e-9) {
        assertEquals(expected.x, actual.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
    }
}
