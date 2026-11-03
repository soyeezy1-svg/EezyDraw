package com.eezydraw.core.geometry

import com.eezydraw.core.model.Bounds3
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.Projection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class OrbitCameraTest {
    @Test fun presetsHaveCorrectScreenDirectionsAndTopHasNoSingularity() {
        val camera = OrbitCamera().preset(ViewPreset.TOP)
        val origin = camera.project(Point3(), 1000.0, 800.0)!!
        val east = camera.project(Point3(1.0, 0.0), 1000.0, 800.0)!!
        val north = camera.project(Point3(0.0, 1.0), 1000.0, 800.0)!!
        assertTrue(east.x > origin.x)
        assertEquals(origin.y, east.y, 1e-9)
        assertTrue(north.y < origin.y)
        for (preset in ViewPreset.entries) {
            val next = camera.preset(preset)
            assertTrue(next.viewProjectionMatrix(1.25).all { it.isFinite() })
            assertNotNull(next.project(Point3(), 1000.0, 800.0))
        }
        val frontUp = camera.preset(ViewPreset.FRONT).project(Point3(0.0, 0.0, 1.0), 1000.0, 800.0)!!
        assertTrue(frontUp.y < origin.y)
        val sideRight = camera.preset(ViewPreset.SIDE).project(Point3(0.0, 1.0), 1000.0, 800.0)!!
        assertTrue(sideRight.x > origin.x)
    }

    @Test fun projectionTogglePreservesTargetPlaneFramingAndPerspectiveHasForeshortening() {
        val ortho = OrbitCamera().preset(ViewPreset.TOP)
        val perspective = ortho.withProjection(Projection.PERSPECTIVE)
        val point = Point3(2.0, 3.0)
        val before = ortho.project(point, 1200.0, 800.0)!!
        val after = perspective.project(point, 1200.0, 800.0)!!
        assertEquals(before.x, after.x, 1e-9)
        assertEquals(before.y, after.y, 1e-9)
        val closer = perspective.project(Point3(2.0, 3.0, 3.0), 1200.0, 800.0)!!
        assertTrue(closer.x > after.x)
        assertTrue(closer.y < after.y)
        assertEquals(ortho.orthographicHeight,
            perspective.withProjection(Projection.ORTHOGRAPHIC).orthographicHeight, 1e-9)
    }

    @Test fun screenRaysRoundTripBothLensesAtLargeCoordinates() {
        for (projection in Projection.entries) {
            val camera = OrbitCamera(target = Point3(1e9, -1e9, 17.0)).withProjection(projection)
            val point = Point3(1e9 + 2.0, -1e9 - 3.0, 17.0)
            val screen = camera.project(point, 1600.0, 1000.0)!!
            val hit = camera.rayToPlaneZ(screen.x, screen.y, 1600.0, 1000.0, 17.0)!!
            assertEquals(point.x, hit.x, 5e-7)
            assertEquals(point.y, hit.y, 5e-7)
            assertEquals(17.0, hit.z, 0.0)
        }
        assertNull(OrbitCamera().preset(ViewPreset.FRONT).rayToPlaneZ(500.0, 400.0, 1000.0, 800.0, 0.0))
    }

    @Test fun gpuMatrixMatchesDoubleProjectionWithFloatingOrigin() {
        for (projection in Projection.entries) {
            val camera = OrbitCamera(target = Point3(1e9, -1e9, 30.0)).withProjection(projection)
            val point = Point3(1e9 + 1.234, -1e9 - 2.345, 31.2)
            val matrix = camera.viewProjectionMatrix(1.6)
            val relative = doubleArrayOf(point.x - camera.target.x, point.y - camera.target.y, point.z - camera.target.z, 1.0)
            val clip = DoubleArray(4) { row -> (0..3).sumOf { column -> matrix[column * 4 + row] * relative[column] } }
            val projected = camera.project(point, 1600.0, 1000.0)!!
            assertEquals(projected.x, (clip[0] / clip[3] + 1) * 800, 0.0002)
            assertEquals(projected.y, (1 - clip[1] / clip[3]) * 500, 0.0002)
            assertEquals(projected.z, clip[2] / clip[3], 1e-6)
        }
    }

    @Test fun extentsFitAllCornersInBothLensesAndCullsDistantBounds() {
        val bounds = Bounds3(Point3(-30.0, -3.0, -4.0), Point3(14.0, 17.0, 12.0))
        for (projection in Projection.entries) for (preset in ViewPreset.entries) {
            val camera = OrbitCamera(projection = projection).preset(preset).fit(bounds, 1.6)
            for (x in listOf(-30.0, 14.0)) for (y in listOf(-3.0, 17.0)) for (z in listOf(-4.0, 12.0)) {
                val screen = camera.project(Point3(x, y, z), 1600.0, 1000.0)!!
                assertTrue("$projection $preset $screen", screen.x in 0.0..1600.0 && screen.y in 0.0..1000.0 && screen.z in -1.0..1.0)
            }
            assertTrue(camera.visible(bounds, 1.6))
        }
        val top = OrbitCamera().preset(ViewPreset.TOP)
        assertFalse(top.visible(Bounds3(Point3(10000.0, 10000.0), Point3(10001.0, 10001.0)), 1.6))
    }

    @Test fun orbitPanAndZoomRemainDefinedAtPolesAndInvalidEvents() {
        val camera = OrbitCamera().preset(ViewPreset.TOP).orbit(PI * 200, PI)
        assertEquals(PI / 2, camera.pitch, 0.0)
        val moved = camera.pan(100.0, 50.0, 1000.0, 800.0)
        val screen = moved.project(camera.target, 1000.0, 800.0)!!
        assertEquals(600.0, screen.x, 1e-9)
        assertEquals(450.0, screen.y, 1e-9)
        assertEquals(camera, camera.zoom(Double.NaN))
        assertEquals(camera, camera.pan(Double.POSITIVE_INFINITY, 0.0, 1000.0, 800.0))
        assertTrue(camera.zoom(1e300).viewProjectionMatrix(1.25).all { it.isFinite() })
    }
}
