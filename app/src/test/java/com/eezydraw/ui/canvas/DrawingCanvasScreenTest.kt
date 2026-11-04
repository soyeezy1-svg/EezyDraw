package com.eezydraw.ui.canvas

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import com.eezydraw.R
import com.eezydraw.core.model.AppSettings
import com.eezydraw.core.model.Element
import com.eezydraw.core.model.ElementBody
import com.eezydraw.core.model.ElementId
import com.eezydraw.core.model.Layer
import com.eezydraw.core.model.LayerId
import com.eezydraw.core.model.LengthUnit
import com.eezydraw.core.model.Level
import com.eezydraw.core.model.LevelId
import com.eezydraw.core.model.Point2
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.Polygon2
import com.eezydraw.core.model.Project
import com.eezydraw.core.model.ProjectDocument
import com.eezydraw.core.model.ProjectId
import com.eezydraw.core.model.Projection
import com.eezydraw.core.model.Shape
import com.eezydraw.core.model.SnapMode
import com.eezydraw.core.model.SnapSettings
import com.eezydraw.ui.theme.EezyDrawTheme
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class DrawingCanvasScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val renderedFrame = AtomicReference<CanvasFrame>()

    @Test
    fun touchDragPansInWorldUnitsAndPinchKeepsItsWorldAnchor() {
        showCanvas()
        val beforePan = camera()
        compose.onNodeWithTag("canvas_surface").performTouchInput {
            down(center)
            moveBy(Offset(96f, 48f), delayMillis = 32)
            up()
        }
        val afterPan = camera()
        assertEquals(beforePan.scale, afterPan.scale, 1e-8)
        assertEquals(beforePan.x - 96.0 / beforePan.scale, afterPan.x, 1e-6)
        assertEquals(beforePan.y + 48.0 / beforePan.scale, afterPan.y, 1e-6)

        val bounds = compose.onNodeWithTag("canvas_surface").fetchSemanticsNode().boundsInRoot
        val anchor = Offset(bounds.width * .60f, bounds.height * .55f)
        val span = min(bounds.width, bounds.height) * .08f
        val anchorBefore = afterPan.worldAt(anchor, bounds.width, bounds.height)
        compose.onNodeWithTag("canvas_surface").performTouchInput {
            pinch(
                start0 = anchor - Offset(span, 0f), end0 = anchor - Offset(span * 2, 0f),
                start1 = anchor + Offset(span, 0f), end1 = anchor + Offset(span * 2, 0f),
                durationMillis = 240,
            )
        }
        val afterPinch = camera()
        val anchorAfter = afterPinch.worldAt(anchor, bounds.width, bounds.height)
        assertTrue("Pinch must increase the rendered plan scale", afterPinch.scale > afterPan.scale * 1.5)
        assertEquals(anchorBefore.first, anchorAfter.first, 1e-5)
        assertEquals(anchorBefore.second, anchorAfter.second, 1e-5)
    }

    @Test
    fun extentsRestoresTheDrawingAndWindowDragFitsTheSelectedRectangle() {
        showCanvas()
        toolbarClick("canvas_extents")
        val extents = camera()
        assertEquals(6.0, extents.x, 1e-8)
        assertEquals(4.0, extents.y, 1e-8)
        compose.onNodeWithTag("canvas_surface").performTouchInput {
            swipe(center, center + Offset(120f, 60f), durationMillis = 160)
        }
        assertTrue(abs(camera().x - extents.x) > .1)
        toolbarClick("canvas_extents")
        assertEquals(extents.x, camera().x, 1e-8)
        assertEquals(extents.y, camera().y, 1e-8)
        assertEquals(extents.scale, camera().scale, 1e-8)

        toolbarClick("canvas_zoom_window")
        compose.onNodeWithTag("canvas_surface").performTouchInput {
            swipe(Offset(width * .25f, height * .25f), Offset(width * .75f, height * .75f), 240)
        }
        val window = camera()
        assertEquals(extents.x, window.x, 1e-5)
        assertEquals(extents.y, window.y, 1e-5)
        assertEquals(extents.scale * 2, window.scale, extents.scale * .02)
    }

    @Test
    fun cubeNavigationOrbitAndHomeReachTheRendererWithoutPresetButtons() {
        showCanvas()
        compose.onNodeWithTag("canvas_view_cube").assertIsDisplayed()
        compose.cubeHome()
        val first3D = currentFrame()
        assertTrue(first3D.is3D)
        assertEquals(Projection.ORTHOGRAPHIC, first3D.orbit.projection)
        assertEquals(1.5, first3D.orbit.target.z, 1e-8)
        val surfaceBounds = compose.onNodeWithTag("canvas_surface").fetchSemanticsNode().boundsInRoot
        for (x in listOf(0.0, 12.0)) for (y in listOf(0.0, 8.0)) for (z in listOf(0.0, 3.0)) {
            val corner = checkNotNull(first3D.orbit.project(
                Point3(x, y, z), surfaceBounds.width.toDouble(), surfaceBounds.height.toDouble(),
            ))
            assertTrue("Initial 3D fit clips room corner ($x, $y, $z) horizontally",
                corner.x in 0.0..surfaceBounds.width.toDouble())
            assertTrue("Initial 3D fit clips room corner ($x, $y, $z) vertically",
                corner.y in 0.0..surfaceBounds.height.toDouble())
        }
        compose.onNodeWithTag("canvas_view_cube").assertIsDisplayed()
        val initialYaw = compose.onNodeWithTag("canvas_surface").fetchSemanticsNode().config[OrbitYaw]
        compose.onNodeWithTag("canvas_surface").performTouchInput {
            swipe(Offset(width * .35f, height * .6f), Offset(width * .55f, height * .65f), 240)
        }
        val changedYaw = compose.onNodeWithTag("canvas_surface").fetchSemanticsNode().config[OrbitYaw]
        assertTrue("A one-finger 3D drag must orbit", abs(changedYaw - initialYaw) > .1)
        compose.cubeFace(context,R.string.canvas_cube_back)
        assertEquals(PI / 2, currentFrame().orbit.yaw, 1e-8)
        assertEquals(0.0, currentFrame().orbit.pitch, 1e-8)

        compose.cubeFace(context,R.string.canvas_top)
        assertTrue("A cube face is a real 3D view",currentFrame().is3D)
        assertEquals(PI / 2, currentFrame().orbit.pitch, 1e-8)
        compose.cubeFace(context,R.string.canvas_front)
        assertEquals(-PI / 2, currentFrame().orbit.yaw, 1e-8)
        assertEquals(0.0, currentFrame().orbit.pitch, 1e-8)
        compose.cubeFace(context,R.string.canvas_side)
        assertEquals(0.0, currentFrame().orbit.yaw, 1e-8)
        compose.cubeIsometric()
        assertEquals(-3*PI / 4, currentFrame().orbit.yaw, 1e-8)
        assertTrue(currentFrame().orbit.pitch < -.5)
        compose.cubeHome()
        assertEquals(PI / 4, currentFrame().orbit.yaw, 1e-8)
        assertTrue(currentFrame().orbit.pitch > .5)
        assertEquals(Projection.ORTHOGRAPHIC, currentFrame().orbit.projection)
        assertEquals(1.5,currentFrame().orbit.target.z,1e-8)
        listOf("canvas_top","canvas_front","canvas_side","canvas_iso","canvas_3d",
            "canvas_perspective","canvas_orthographic").forEach { tag ->
            assertTrue("Cube-only navigation must not leave a preset button $tag",
                compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun probeSnapsToDrawingGeometryWithoutPanningAndReferenceCanBeCleared() {
        showCanvas()
        toolbarClick("canvas_probe")
        val before = camera()
        val bounds = compose.onNodeWithTag("canvas_surface").fetchSemanticsNode().boundsInRoot
        val endpoint = Offset(
            (bounds.width / 2 - before.x * before.scale).toFloat(),
            (bounds.height / 2 + before.y * before.scale).toFloat(),
        )
        compose.onNodeWithTag("canvas_surface").performTouchInput {
            swipe(endpoint + Offset(30f, -30f), endpoint + Offset(.5f, -.5f), 240)
        }
        assertEquals(before, camera())
        compose.onNodeWithText(context.getString(R.string.canvas_snap_endpoint)).assertIsDisplayed()
        compose.onNodeWithTag("canvas_surface").performTouchInput { click(endpoint) }
        compose.onNodeWithTag("canvas_clear_reference").performScrollTo().assertIsEnabled().performClick()
            .assertIsNotEnabled()
    }

    @Test
    fun snapOptionsPersistAndStatusControlsChangeUnitsAndActiveLevel() {
        val snapChanges = mutableListOf<SnapSettings>()
        val unitChanges = mutableListOf<LengthUnit>()
        showCanvas(onSnap = snapChanges::add, onUnits = unitChanges::add)
        compose.onNodeWithTag("canvas_status").assertIsDisplayed()
        toolbarClick("canvas_snaps")
        snapOption(R.string.canvas_snap_grid)
        assertFalse(snapChanges.last().modes.contains(SnapMode.GRID))
        assertTrue(snapChanges.last().modes.contains(SnapMode.ENDPOINT))
        snapOption(R.string.canvas_snap_intersection)
        assertTrue(snapChanges.last().modes.contains(SnapMode.INTERSECTION))
        for (mode in listOf(R.string.canvas_snap_midpoint, R.string.canvas_snap_perpendicular, R.string.canvas_snap_ortho)) {
            snapOption(mode)
        }
        assertTrue(snapChanges.last().modes.containsAll(setOf(
            SnapMode.ENDPOINT, SnapMode.MIDPOINT, SnapMode.INTERSECTION, SnapMode.PERPENDICULAR, SnapMode.ORTHO,
        )))
        compose.onNodeWithText(context.getString(R.string.close)).performClick()
        compose.onNodeWithTag("canvas_units").performClick()
        compose.onNodeWithText(context.getString(R.string.project_unit_metres)).performClick()
        assertEquals(listOf(LengthUnit.METRES), unitChanges)
        assertEquals("ground", currentFrame().levelId)
        compose.onNodeWithTag("canvas_level").performClick()
        compose.onNodeWithText(context.getString(R.string.canvas_all_levels)).performClick()
        assertEquals(null, currentFrame().levelId)
        compose.onNodeWithTag("canvas_level").performClick()
        compose.onNodeWithText("Ground").performClick()
        assertEquals("ground", currentFrame().levelId)
    }

    private fun showCanvas(
        onSnap: (SnapSettings) -> Unit = {},
        onUnits: (LengthUnit) -> Unit = {},
    ) {
        var document by mutableStateOf(document())
        compose.setContent {
            EezyDrawTheme {
                Surface {
                    DrawingCanvasScreen(
                        document = document,
                        settings = AppSettings(),
                        dirty = false,
                        busy = false,
                        onSnapSettingsChange = { snap ->
                            onSnap(snap)
                            document = document.copy(project = document.project.copy(
                                settings = document.project.settings.copy(snap = snap),
                            ))
                        },
                        onUnitsChange = { unit ->
                            onUnits(unit)
                            document = document.copy(project = document.project.copy(
                                settings = document.project.settings.copy(lengthUnit = unit),
                            ))
                        },
                        onSave = {}, onExport = {}, onDetails = {}, onBack = {},
                        // Exercise the production gesture/control layer without creating a GL context.
                        // Renderer shaders and geometry have their own integration/core tests.
                        surface = { _, frame, modifier ->
                            SideEffect { renderedFrame.set(frame) }
                            Box(modifier)
                        },
                    )
                }
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("canvas_ready").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun toolbarClick(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun snapOption(label: Int) {
        compose.onNode(
            hasText(context.getString(label)) and hasClickAction() and hasAnyAncestor(isDialog()),
        ).performClick()
    }

    private fun currentFrame(): CanvasFrame = compose.runOnIdle { checkNotNull(renderedFrame.get()) }

    private fun camera(): PlanSnapshot {
        val node = compose.onNodeWithTag("canvas_surface").fetchSemanticsNode()
        return PlanSnapshot(node.config[PlanCenterX], node.config[PlanCenterY], node.config[PlanScale])
    }

    private data class PlanSnapshot(val x: Double, val y: Double, val scale: Double) {
        fun worldAt(point: Offset, width: Float, height: Float) =
            Pair(x + (point.x - width / 2) / scale, y - (point.y - height / 2) / scale)
    }

    private fun document(): ProjectDocument {
        val project = Project(ProjectId("canvas-test"), "Canvas test", 1, 2)
        val layer = Layer(LayerId("geometry"), project.id, "Geometry")
        val level = Level(LevelId("ground"), project.id, "Ground", 0.0, .2, 3.0)
        return ProjectDocument(
            project,
            levels = listOf(level),
            layers = listOf(layer),
            elements = listOf(
                Element(
                    ElementId("test-line"), project.id, layer.id,
                    ElementBody.DrawingShape(Shape.Line(Point3(), Point3(12.0, 8.0))),
                    levelId = level.id,
                ),
                Element(
                    ElementId("test-room"), project.id, layer.id,
                    ElementBody.Room(Polygon2(listOf(
                        Point2(0.0, 0.0), Point2(12.0, 0.0), Point2(12.0, 8.0), Point2(0.0, 8.0),
                    )), ceilingHeight = 3.0),
                    levelId = level.id,
                ),
            ),
        )
    }
}
