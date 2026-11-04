package com.eezydraw.ui.canvas

import com.eezydraw.core.editing.EditDelta
import com.eezydraw.core.geometry.Viewport2D
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.Rotation3
import com.eezydraw.core.model.ViewMode
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewRenderPlanTest {
    @Test fun eachVisibleModeHasItsOwnSurfaceEdgeLightingAndTransparencyPasses() {
        assertEquals(ViewRenderPlan(false, true), viewRenderPlan(ViewMode.WIREFRAME))
        assertEquals(ViewRenderPlan(true, true, depthOnly = true), viewRenderPlan(ViewMode.HIDDEN_LINE))
        assertEquals(ViewRenderPlan(true, false, lighting = 1), viewRenderPlan(ViewMode.SHADED))
        assertEquals(ViewRenderPlan(true, true, lighting = 1), viewRenderPlan(ViewMode.SHADED_WITH_EDGES))
        assertEquals(ViewRenderPlan(true, false, lighting = 2), viewRenderPlan(ViewMode.REALISTIC))
        assertEquals(ViewRenderPlan(true, true, transparent = true, lighting = 1), viewRenderPlan(ViewMode.XRAY))
        assertEquals(viewRenderPlan(ViewMode.XRAY), viewRenderPlan(ViewMode.TRANSPARENT))
        assertTrue(viewRenderPlan(ViewMode.SECTION).depthOnly)
    }

    @Test fun inverseTransposeKeepsNormalsPerpendicularToNonuniformlyResizedRotatedSurfaces() {
        val delta = EditDelta(rotationZ = .37, axisScale = Point3(2.0, .3, 1.7), axes = Rotation3(.41, .23, -.62))
        val normal = previewNormalMatrix(delta)
        val linear = delta.linearMatrixDouble()
        fun transformed(v: Point3) = Point3(
            linear[0] * v.x + linear[4] * v.y + linear[8] * v.z,
            linear[1] * v.x + linear[5] * v.y + linear[9] * v.z,
            linear[2] * v.x + linear[6] * v.y + linear[10] * v.z,
        )
        fun transformedNormal(v: Point3) = Point3(
            normal[0] * v.x + normal[3] * v.y + normal[6] * v.z,
            normal[1] * v.x + normal[4] * v.y + normal[7] * v.z,
            normal[2] * v.x + normal[5] * v.y + normal[8] * v.z,
        )
        val n = transformedNormal(Point3(1.0, 1.0, 1.0))
        for (tangent in listOf(Point3(1.0, -1.0), Point3(0.0, 1.0, -1.0))) {
            val t = transformed(tangent)
            assertEquals(0.0, n.x * t.x + n.y * t.y + n.z * t.z, 1e-6)
        }
        assertTrue(normal.all(Float::isFinite))
    }

    @Test fun filledPlanProjectionPreservesMetreScaleAndShowsHigherElevationsInFront() {
        val viewport = Viewport2D(pixelsPerMetre = 100.0)
        val matrix = filledPlanMatrix(viewport, 1000, 500, -1.0, 9.0)
        assertEquals(.2f, matrix[0], 1e-7f)
        assertEquals(.4f, matrix[5], 1e-7f)
        fun depth(z: Double) = matrix[10] * z + matrix[14]
        assertTrue(depth(9.0) < depth(-1.0))
        assertTrue(abs(depth(9.0)) < 1.0 && abs(depth(-1.0)) < 1.0)
        val flat = filledPlanMatrix(viewport, 1000, 500, 1e9, 1e9)
        assertTrue(flat.all(Float::isFinite))
        assertFalse(viewRenderPlan(ViewMode.WIREFRAME).surfaces)
    }
}
