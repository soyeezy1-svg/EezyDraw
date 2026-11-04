package com.eezydraw.ui.canvas.editing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.eezydraw.core.editing.EditDelta
import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.ViewPreset
import com.eezydraw.core.geometry.Viewport2D
import com.eezydraw.core.model.Bounds3
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.Projection
import com.eezydraw.ui.theme.EezyDrawTheme
import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class SelectionGizmoTest {
    @get:Rule val compose = createComposeRule()
    private val bounds = Bounds3(Point3(-2.0, -1.0), Point3(2.0, 1.0, 3.0))
    private val viewport = Viewport2D(pixelsPerMetre = 20.0)

    @Test
    fun frontAndSideRotationRemainUsableInBothProjections() {
        val pivot = Point3(1e9, -1e9, 2.0)
        for (projection in Projection.entries) for (view in listOf(ViewPreset.FRONT, ViewPreset.SIDE)) {
            val camera = OrbitCamera(target = pivot, projection = projection).preset(view)
            val center = camera.project(pivot, 1000.0, 800.0)!!
            val angle = gizmoRotationZ(pivot, Offset(center.x.toFloat() - 40, center.y.toFloat() - 40),
                Offset(center.x.toFloat() + 40, center.y.toFloat() - 40), viewport, camera, true, 1000.0, 800.0)
            assertEquals("$projection $view", -PI / 2, angle, 1e-12)
        }
    }

    @Test
    fun visibleHorizontalPlaneRetainsWorldAnglesAndEdgeOnDialHasBoundedAngles() {
        val pivot = Point3(1e9, -1e9, 2.0)
        for (projection in Projection.entries) for (view in listOf(ViewPreset.TOP, ViewPreset.ISOMETRIC)) {
            val camera = OrbitCamera(target = pivot, projection = projection).preset(view)
            val start = camera.project(Point3(pivot.x + 2, pivot.y, pivot.z), 1000.0, 800.0)!!
            val current = camera.project(Point3(pivot.x, pivot.y + 2, pivot.z), 1000.0, 800.0)!!
            assertEquals(PI / 2, gizmoRotationZ(pivot,
                Offset(start.x.toFloat(), start.y.toFloat()), Offset(current.x.toFloat(), current.y.toFloat()),
                viewport, camera, true, 1000.0, 800.0), 2e-6)
        }
        val above = OrbitCamera(pitch = .01)
        val below = above.copy(pitch = -.01)
        val start = Offset(460f, 360f); val current = Offset(540f, 360f)
        assertEquals(-PI / 2, gizmoRotationZ(Point3(), start, current, viewport, above, true, 1000.0, 800.0), 1e-12)
        assertEquals(PI / 2, gizmoRotationZ(Point3(), start, current, viewport, below, true, 1000.0, 800.0), 1e-12)
    }

    @Test
    fun edgeOnViewsHideOnlyUnusableMovementAxesAndKeepTheRotationDial() {
        fun handles(view: ViewPreset) = gizmoHandles(bounds, viewport,
            OrbitCamera(target = Point3(z = 1.5)).preset(view), true, 1000.0, 800.0, 1.0).map { it.handle }
        assertTrue(GizmoHandle.ROTATE_Z in handles(ViewPreset.FRONT))
        assertTrue(GizmoHandle.MOVE_X in handles(ViewPreset.FRONT))
        assertTrue(GizmoHandle.MOVE_Z in handles(ViewPreset.FRONT))
        assertFalse(GizmoHandle.MOVE_Y in handles(ViewPreset.FRONT))
        assertFalse(GizmoHandle.MOVE_X in handles(ViewPreset.SIDE))
        assertFalse(GizmoHandle.MOVE_Z in handles(ViewPreset.TOP))
    }

    @Test
    fun previewRecompositionKeepsAnActiveDragAndSecondPointerCancelsWithoutCommit() {
        var preview by mutableStateOf<EditDelta?>(null)
        var density = 1.0
        var starts = 0
        var commits = 0
        val endings = mutableListOf<Boolean>()
        compose.setContent {
            val measuredDensity = LocalDensity.current.density
            SideEffect { density = measuredDensity.toDouble() }
            EezyDrawTheme { Surface { Box(Modifier.fillMaxSize()) {
                SelectionGizmo(bounds, viewport, OrbitCamera(), false, preview,
                    onStart = { _, _ -> starts++ },
                    onDrag = { _, start, current ->
                        preview = EditDelta(translation = Point3((current.x - start.x) / viewport.pixelsPerMetre))
                    },
                    onEnd = { cancelled ->
                        endings.add(cancelled)
                        if (!cancelled) commits++
                        preview = null
                    })
            } } }
        }
        val node = compose.onNodeWithTag("selection_gizmo")
        val surface = node.fetchSemanticsNode().boundsInRoot
        val control = gizmoHandles(bounds, viewport, OrbitCamera(), false,
            surface.width.toDouble(), surface.height.toDouble(), density).single { it.handle == GizmoHandle.MOVE_X }
        val start = Offset(control.center.x.toFloat(), control.center.y.toFloat())
        node.performTouchInput {
            down(0, start)
            moveTo(0, start + Offset(40f, 0f), delayMillis = 32)
        }
        compose.runOnIdle {
            assertEquals(1, starts)
            assertTrue(preview != null)
            assertTrue(endings.isEmpty())
        }
        node.performTouchInput {
            down(1, start + Offset(80f, 50f))
            moveTo(0, start + Offset(70f, 0f), delayMillis = 32)
            up(1)
            up(0)
        }
        compose.runOnIdle {
            assertEquals(listOf(true), endings)
            assertEquals(0, commits)
            assertNull(preview)
        }
        // A cancelled gesture must leave the node ready for a fresh, single transaction.
        node.performTouchInput {
            down(0, start)
            moveTo(0, start + Offset(20f, 0f), delayMillis = 32)
            up(0)
        }
        compose.runOnIdle {
            assertEquals(listOf(true, false), endings)
            assertEquals(1, commits)
            assertNull(preview)
        }
    }
}
