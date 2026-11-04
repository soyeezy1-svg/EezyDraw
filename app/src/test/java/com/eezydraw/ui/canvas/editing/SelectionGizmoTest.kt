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
import com.eezydraw.core.editing.ResizeFrame
import com.eezydraw.core.editing.ResizeHandle
import com.eezydraw.core.editing.resizeDelta
import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.ViewPreset
import com.eezydraw.core.geometry.Viewport2D
import com.eezydraw.core.model.Bounds3
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.Point2
import com.eezydraw.core.model.Projection
import com.eezydraw.core.model.Transform3
import com.eezydraw.ui.theme.EezyDrawTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
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
    fun singleObjectHandlesNameEachDimensionAndKeepSmallTargetsSeparateAtAnyZoom() {
        val box = Bounds3(Point3(), Point3(1.0, 1.0, 1.0))
        val frame = ResizeFrame(box, Transform3())
        for (scale in listOf(.0001, 20.0, 250.0)) for (density in listOf(1.0, 2.0)) {
            val controls = gizmoHandles(box, Viewport2D(Point2(.5, .5), scale), OrbitCamera(), false,
                1200.0, 1000.0, density, frame)
            val resize = controls.filter { it.handle.resizeHandle() != null }
            assertEquals("All four sides and four base corners must be available", 8, resize.size)
            assertFalse(controls.any { it.handle == GizmoHandle.SCALE })
            assertFalse(controls.any { it.handle == GizmoHandle.RESIZE_HEIGHT })
            controls.forEachIndexed { index, a -> controls.drop(index + 1).forEach { b ->
                assertTrue("Overlapping targets ${a.handle} and ${b.handle} at $scale/$density",
                    hypot(a.center.x - b.center.x, a.center.y - b.center.y) >= 44.0 * density - 1e-7)
            } }
        }
        val controls3D = gizmoHandles(box, viewport,
            OrbitCamera(target = Point3(.5, .5, .5)).preset(ViewPreset.ISOMETRIC), true,
            1000.0, 800.0, 1.0, frame)
        assertEquals(9, controls3D.count { it.handle.resizeHandle() != null })
        assertTrue(controls3D.any { it.handle == GizmoHandle.RESIZE_HEIGHT })
    }

    @Test
    fun flatAndEdgeOnSelectionsHideUnobservableResizeDimensions() {
        val solid = Bounds3(Point3(), Point3(1.0, 1.0, 1.0))
        val flat = Bounds3(Point3(), Point3(1.0, 1.0))
        val camera = OrbitCamera(target = Point3(.5, .5, .5), projection = Projection.ORTHOGRAPHIC)
        fun handles(bounds: Bounds3, view: ViewPreset) = gizmoHandles(bounds, viewport,
            camera.preset(view), true, 1000.0, 800.0, 1.0, ResizeFrame(bounds, Transform3())).map { it.handle }
        assertFalse(GizmoHandle.RESIZE_HEIGHT in handles(flat, ViewPreset.ISOMETRIC))
        assertFalse(GizmoHandle.RESIZE_HEIGHT in handles(solid, ViewPreset.TOP))
        assertTrue(GizmoHandle.RESIZE_HEIGHT in handles(solid, ViewPreset.FRONT))
        val front = handles(solid, ViewPreset.FRONT)
        assertTrue(GizmoHandle.RESIZE_WIDTH_POSITIVE in front)
        assertFalse(GizmoHandle.RESIZE_DEPTH_POSITIVE in front)
        assertFalse(front.any { it.name.startsWith("RESIZE_CORNER") })
    }

    @Test
    fun localResizeAnchorsAndAccessibleStepsFollowThePreviewWithoutChangingOtherAxes() {
        val box = Bounds3(Point3(), Point3(1.0, 1.0, 1.0))
        val frame = ResizeFrame(box, Transform3())
        val camera = Viewport2D(Point2(.5, .5), 100.0)
        val preview = EditDelta(axisScale = Point3(2.0, 1.0, 1.0))
        val controls = gizmoHandles(box, camera, OrbitCamera(), false, 1000.0, 800.0, 1.0, frame, preview)
        val width = controls.single { it.handle == GizmoHandle.RESIZE_WIDTH_POSITIVE }
        val expected = camera.worldToScreen(Point2(2.0, .5), 1000.0, 800.0)
        assertEquals(expected, width.anchor)
        assertEquals(20.0, checkNotNull(width.accessibleStep).x, 1e-9)
        assertEquals(0.0, checkNotNull(width.accessibleStep).y, 1e-9)
        val depth = controls.single { it.handle == GizmoHandle.RESIZE_DEPTH_POSITIVE }
        assertEquals(0.0, checkNotNull(depth.accessibleStep).x, 1e-9)
        assertEquals(-10.0, checkNotNull(depth.accessibleStep).y, 1e-9)
    }

    @Test
    fun physicalSideCornerAndTopHandleGesturesChangeOnlyTheirNamedDimensions() {
        val box = Bounds3(Point3(), Point3(1.0, 1.0, 1.0))
        val frame = ResizeFrame(box, Transform3())
        val camera = OrbitCamera(target = Point3(.5, .5, .5), projection = Projection.ORTHOGRAPHIC,
            orthographicHeight = 4.0).preset(ViewPreset.ISOMETRIC)
        var preview by mutableStateOf<EditDelta?>(null)
        var density = 1.0
        var measuredWidth = 0.0
        var measuredHeight = 0.0
        val committed = mutableListOf<Pair<ResizeHandle, EditDelta>>()
        var active: ResizeHandle? = null
        compose.setContent {
            val measuredDensity = LocalDensity.current.density
            SideEffect { density = measuredDensity.toDouble() }
            EezyDrawTheme { Surface { Box(Modifier.fillMaxSize()) {
                SelectionGizmo(box, viewport, camera, true, preview,
                    onStart = { handle, _ -> active = handle.resizeHandle() },
                    onDrag = { handle, start, current ->
                        handle.resizeHandle()?.let { resize ->
                            preview = resizeDelta(frame, resize, Point2(start.x.toDouble(), start.y.toDouble()),
                                Point2(current.x.toDouble(), current.y.toDouble()), viewport, camera, true,
                                measuredWidth, measuredHeight)
                        }
                    },
                    onEnd = { cancelled ->
                        if (!cancelled) committed.add(checkNotNull(active) to checkNotNull(preview))
                        preview = null
                    }, resizeFrame = frame)
            } } }
        }
        val node = compose.onNodeWithTag("selection_gizmo")
        val area = node.fetchSemanticsNode().boundsInRoot
        measuredWidth = area.width.toDouble(); measuredHeight = area.height.toDouble()
        val controls = gizmoHandles(box, viewport, camera, true, measuredWidth, measuredHeight, density, frame)
            .filter { it.handle.resizeHandle() != null }
        assertEquals(9, controls.size)
        controls.forEach { control ->
            val start = Offset(control.center.x.toFloat(), control.center.y.toFloat())
            val shift = checkNotNull(control.accessibleStep).let { Offset(it.x.toFloat(), it.y.toFloat()) * 2f }
            node.performTouchInput { down(start); moveTo(start + shift, delayMillis = 32); up() }
            compose.runOnIdle {
                val (handle, delta) = committed.last()
                assertEquals(control.handle.resizeHandle(), handle)
                val expected = when (handle) {
                    ResizeHandle.WIDTH_NEGATIVE, ResizeHandle.WIDTH_POSITIVE -> setOf(0)
                    ResizeHandle.DEPTH_NEGATIVE, ResizeHandle.DEPTH_POSITIVE -> setOf(1)
                    ResizeHandle.HEIGHT -> setOf(2)
                    else -> setOf(0, 1)
                }
                val scales = listOf(delta.axisScale.x, delta.axisScale.y, delta.axisScale.z)
                scales.forEachIndexed { index, value ->
                    if (index in expected) assertTrue("$handle must resize dimension $index", abs(value - 1.0) > 1e-6)
                    else assertEquals("$handle changed another dimension $index", 1.0, value, 1e-9)
                }
            }
        }
        assertEquals(9, committed.size)
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
