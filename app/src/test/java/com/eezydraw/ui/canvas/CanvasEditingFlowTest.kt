package com.eezydraw.ui.canvas

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import com.eezydraw.R
import com.eezydraw.core.editing.readProperties
import com.eezydraw.core.editing.resizeFrame
import com.eezydraw.core.model.AppSettings
import com.eezydraw.core.model.PrecisionPointerSettings
import com.eezydraw.core.model.Element
import com.eezydraw.core.model.ElementBody
import com.eezydraw.core.model.Point2
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.Shape
import com.eezydraw.core.model.SnapMode
import com.eezydraw.ui.AppScreen
import com.eezydraw.ui.AppViewModel
import com.eezydraw.ui.EezyDrawApp
import com.eezydraw.ui.TestAppRepositories
import com.eezydraw.ui.canvas.editing.GizmoHandle
import com.eezydraw.ui.canvas.editing.gizmoHandles
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Exercises real screen gestures, ViewModel commands and save/reopen, replacing only GL. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class CanvasEditingFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var repositories: TestAppRepositories
    private lateinit var model: AppViewModel
    private val renderedFrame = AtomicReference<CanvasFrame>()

    @Before fun prepareModel() {
        // These regressions exercise the optional direct-tap workflow.
        repositories = TestAppRepositories(context, AppSettings(autosaveIntervalSeconds = 0,
            precisionPointer = PrecisionPointerSettings(enabled = false)))
        model = repositories.newViewModel()
    }

    @After fun closeRepositories() { repositories.close() }

    @Test
    fun lineCreationHandleOnlyMovementUndoRedoAndSaveContinueUseTheRealCanvasFlow() {
        createEmptyProject("Handle editing")
        chooseTool("line")
        compose.onNodeWithTag("canvas_surface").performTouchInput {
            click(Offset(width * .43f, height * .55f))
            click(Offset(width * .58f, height * .55f))
        }
        awaitElements(1)
        val created = element()
        assertTrue((created.body as ElementBody.DrawingShape).shape is Shape.Line)
        compose.runOnIdle {
            assertEquals(setOf(created.id), model.state.value.selectedIds)
            assertTrue(model.state.value.dirty)
            assertTrue(model.state.value.canUndo)
        }

        chooseTool("select")
        awaitSelectedScene(created)
        val beforePan = currentFrame().viewport
        val bounds = compose.onNodeWithTag("canvas_surface").fetchSemanticsNode().boundsInRoot
        val selectedBounds = checkNotNull(currentFrame().selectionScene).bounds
        // Start near the line's end, outside the new centre move square and axis targets.
        val bodyPoint = currentFrame().viewport.worldToScreen(
            com.eezydraw.core.model.Point2(
                selectedBounds.min.x * .9 + selectedBounds.max.x * .1,
                selectedBounds.min.y / 2 + selectedBounds.max.y / 2,
            ), bounds.width.toDouble(), bounds.height.toDouble(),
        )
        compose.onNodeWithTag("canvas_surface").performTouchInput {
            swipe(Offset(bodyPoint.x.toFloat(), bodyPoint.y.toFloat()),
                Offset(bodyPoint.x.toFloat() + 34f, bodyPoint.y.toFloat() + 20f), 160)
        }
        assertEquals("Dragging the body must not transform the object", created, element())
        assertNotEquals("Body drag still navigates the canvas", beforePan.center, currentFrame().viewport.center)

        awaitSelectedScene(created)
        val frame = currentFrame()
        val resize = compose.runOnIdle { resizeFrame(checkNotNull(model.state.value.document),created) }
        val control = gizmoHandles(
            checkNotNull(frame.selectionScene).bounds, frame.viewport, frame.orbit, frame.is3D,
            bounds.width.toDouble(), bounds.height.toDouble(),
            context.resources.displayMetrics.density.toDouble(),resizeFrame = resize,
        ).single { it.handle == GizmoHandle.MOVE_X }
        val handle = Offset(control.center.x.toFloat(), control.center.y.toFloat())
        compose.onNodeWithTag("canvas_surface").performTouchInput {
            swipe(handle, handle + Offset(52f, 0f), 240)
        }
        compose.waitUntil(10_000) { model.state.value.document?.elements?.single()?.transform != created.transform }
        val moved = element()
        assertTrue(moved.transform.translation.x > created.transform.translation.x)
        assertEquals(created.transform.translation.y, moved.transform.translation.y, 1e-10)
        assertEquals(created.transform.translation.z, moved.transform.translation.z, 1e-10)

        toolsAction("undo")
        assertEquals("A completed handle drag is one undo transaction", created, element())
        toolsAction("redo")
        assertEquals(moved, element())
        collapseTools()
        compose.cadGroup("settings");compose.onNodeWithTag("canvas_save").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && !model.state.value.dirty }
        assertEquals(moved, repositories.projects.documents.values.single().elements.single())
        // The bottom-right View panel is persistent; deselect on uncovered canvas.
        compose.cadTap(.94f, .20f)
        compose.runOnIdle { assertTrue(model.state.value.selectedIds.isEmpty()) }
        compose.onNodeWithTag("canvas_close").performClick()
        compose.onNodeWithTag("start_menu").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.continue_project)).performScrollTo().performClick()
        awaitCanvas()
        compose.runOnIdle {
            assertEquals(AppScreen.PROJECT, model.state.value.screen)
            assertEquals(moved, model.state.value.document?.elements?.single())
            assertFalse(model.state.value.dirty)
            assertFalse("A reopened document starts a fresh editing session", model.state.value.canUndo)
            assertTrue(model.state.value.selectedIds.isEmpty())
        }
    }

    @Test
    fun polylineCommitsOnlyOnFinishAndClipboardCommandsRemainUndoable() {
        createEmptyProject("Polyline edits")
        chooseTool("polyline")
        compose.onNodeWithTag("canvas_surface").performTouchInput {
            click(Offset(width * .43f, height * .60f))
            click(Offset(width * .56f, height * .60f))
            click(Offset(width * .56f, height * .42f))
        }
        compose.runOnIdle { assertTrue(checkNotNull(model.state.value.document).elements.isEmpty()) }
        toolsAction("finish")
        awaitElements(1)
        val original = element()
        val polyline = (original.body as ElementBody.DrawingShape).shape as Shape.Polyline
        assertEquals(3, polyline.points.size)

        toolsAction("copy")
        toolsAction("paste")
        awaitElements(2)
        val pasted = compose.runOnIdle {
            val state = model.state.value
            checkNotNull(state.document).elements.single { it.id != original.id }.also {
                assertEquals(setOf(it.id), state.selectedIds)
                assertEquals(original.body, it.body)
                assertNotEquals(original.transform.translation, it.transform.translation)
            }
        }
        toolsAction("delete")
        awaitElements(1)
        assertEquals(original, element())
        toolsAction("undo")
        awaitElements(2)
        compose.runOnIdle { assertTrue(checkNotNull(model.state.value.document).elements.contains(pasted)) }
        toolsAction("undo")
        awaitElements(1)
        assertEquals(original, element())
        toolsAction("undo")
        awaitElements(0)
        compose.runOnIdle {
            assertFalse(model.state.value.dirty)
            assertFalse(model.state.value.canUndo)
            assertTrue(model.state.value.canRedo)
        }
        collapseTools()
        compose.cadGroup("edit")
        compose.onNodeWithTag("view_undo").assertIsNotEnabled()
    }

    @Test
    fun numericPropertiesMirrorAndRectangularArrayApplyAsAtomicUndoableEdits() {
        createEmptyProject("Numeric solid editing")
        chooseTool("box")
        compose.cadTap(.43f, .60f)
        awaitElements(1)
        val original = element()
        assertTrue((original.body as ElementBody.DrawingShape).shape is Shape.Box)

        toolsAction("properties")
        // The default project display unit is millimetres; rotations are entered in degrees.
        val values = linkedMapOf(
            "property_x" to "1250", "property_y" to "-2500", "property_z" to "3500",
            "property_rotation_x" to "15", "property_rotation_y" to "30", "property_rotation_z" to "45",
            "property_width" to "2400", "property_depth" to "1800", "property_height" to "3200",
        )
        values.forEach { (tag, value) ->
            compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
        }
        assertEquals("Typing a Properties draft must not mutate the document", original, element())
        compose.onNodeWithTag("properties_apply").assertIsEnabled().performClick()
        compose.onNodeWithTag("properties_side_panel").assertIsDisplayed()
        compose.cadCloseDetail()
        val edited = element()
        compose.runOnIdle {
            val properties = readProperties(checkNotNull(model.state.value.document), edited)
            assertEquals(1.25, properties.position.x, 1e-10)
            assertEquals(-2.5, properties.position.y, 1e-10)
            assertEquals(3.5, properties.position.z, 1e-10)
            assertEquals(Math.toRadians(15.0), properties.rotation.x, 1e-10)
            assertEquals(Math.toRadians(30.0), properties.rotation.y, 1e-10)
            assertEquals(Math.toRadians(45.0), properties.rotation.z, 1e-10)
            assertEquals(2.4, properties.size.x, 1e-10)
            assertEquals(1.8, properties.size.y, 1e-10)
            assertEquals(3.2, properties.size.z, 1e-10)
        }
        toolsAction("undo")
        assertEquals("All numeric fields apply in one undo step", original, element())
        toolsAction("redo")
        assertEquals(edited, element())

        toolsAction("mirror_x")
        val mirrored = element()
        assertNotEquals(edited.transform, mirrored.transform)
        val scale = mirrored.transform.scale
        assertTrue("Mirror must change handedness", scale.x * scale.y * scale.z < 0.0)

        toolsAction("array")
        linkedMapOf("array_columns" to "2", "array_rows" to "2", "array_dx" to "4000", "array_dy" to "-6000")
            .forEach { (tag, value) ->
                compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
            }
        assertEquals("Array dialog input must not add partial copies", mirrored, element())
        compose.onNodeWithTag("array_apply").assertIsEnabled().performClick()
        awaitElements(4)
        compose.runOnIdle {
            val elements = checkNotNull(model.state.value.document).elements
            assertEquals(4, elements.map { it.id }.toSet().size)
            assertTrue(elements.contains(mirrored))
            val position = mirrored.transform.translation
            val offsets = elements.map {
                Pair(it.transform.translation.x - position.x, it.transform.translation.y - position.y)
            }.toSet()
            assertEquals(4, offsets.size)
            setOf(0.0 to 0.0, 4.0 to 0.0, 0.0 to -6.0, 4.0 to -6.0).forEach { expected ->
                assertTrue("Missing array offset $expected", offsets.any {
                    abs(it.first - expected.first) < 1e-10 && abs(it.second - expected.second) < 1e-10
                })
            }
            elements.forEach {
                assertEquals(mirrored.body, it.body)
                assertEquals(mirrored.transform.rotation, it.transform.rotation)
                assertEquals(mirrored.transform.scale, it.transform.scale)
                assertEquals(position.z, it.transform.translation.z, 1e-10)
            }
        }
        toolsAction("undo")
        awaitElements(1)
        assertEquals("The full rectangular array is one undo step", mirrored, element())
        toolsAction("undo")
        assertEquals("Undo mirror restores the complete numeric transform", edited, element())
        collapseTools()
        compose.cadGroup("settings");compose.onNodeWithTag("canvas_save").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && !model.state.value.dirty }
        assertEquals(edited, repositories.projects.documents.values.single().elements.single())
    }

    @Test
    fun consecutiveLinesSnapToTheCompletedShapeWhileItRemainsSelected() {
        repositories.settings.settings.value = repositories.settings.settings.value.copy(keepToolActiveAfterPlacing = true)
        createEmptyProject("Connected lines")
        compose.runOnIdle {
            val settings = checkNotNull(model.state.value.document).project.settings.snap
            model.changeSnaps(settings.copy(modes = setOf(SnapMode.ENDPOINT), tolerance = .001))
        }
        chooseTool("line")
        compose.onNodeWithTag("canvas_surface").performTouchInput {
            click(Offset(width * .43f, height * .55f))
            click(Offset(width * .58f, height * .55f))
        }
        awaitElements(1)
        val first = element()
        awaitSelectedScene(first)
        compose.runOnIdle { assertEquals(setOf(first.id), model.state.value.selectedIds) }
        expandTools()
        compose.onNodeWithTag("tool_line").performScrollTo().assertIsSelected()
        collapseTools()

        val line = (first.body as ElementBody.DrawingShape).shape as Shape.Line
        val endpoint = Point3(
            first.transform.translation.x + line.end.x,
            first.transform.translation.y + line.end.y,
            first.transform.translation.z + line.end.z,
        )
        val surfaceBounds = compose.onNodeWithTag("canvas_surface").fetchSemanticsNode().boundsInRoot
        val screen = currentFrame().viewport.worldToScreen(
            Point2(endpoint.x, endpoint.y), surfaceBounds.width.toDouble(), surfaceBounds.height.toDouble(),
        )
        val endpointScreen = Offset(screen.x.toFloat(), screen.y.toFloat())
        val density = context.resources.displayMetrics.density
        // Deliberately miss the endpoint by five dp: exact placement requires endpoint snapping.
        compose.onNodeWithTag("canvas_surface").performTouchInput {
            click(endpointScreen + Offset(4f * density, -3f * density))
        }
        compose.onNodeWithText(context.getString(R.string.canvas_snap_endpoint)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, checkNotNull(model.state.value.document).elements.size)
            assertEquals("Starting the next line retains the completed object's selection", setOf(first.id), model.state.value.selectedIds)
        }
        compose.onNodeWithTag("canvas_surface").performTouchInput {
            click(endpointScreen + Offset(64f * density, -72f * density))
        }
        awaitElements(2)
        compose.runOnIdle {
            val second = checkNotNull(model.state.value.document).elements.single { it.id != first.id }
            val secondLine = (second.body as ElementBody.DrawingShape).shape as Shape.Line
            assertEquals(endpoint.x, second.transform.translation.x + secondLine.start.x, 1e-10)
            assertEquals(endpoint.y, second.transform.translation.y + secondLine.start.y, 1e-10)
            assertEquals(endpoint.z, second.transform.translation.z + secondLine.start.z, 1e-10)
        }
    }

    private fun createEmptyProject(name: String) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EezyDrawApp(model, canvasSurface = { _, frame, modifier ->
                SideEffect { renderedFrame.set(frame) }
                Box(modifier)
            })
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithTag("start_menu").assertIsDisplayed()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText(context.getString(R.string.create_new_project)).performScrollTo().performClick()
        compose.onNodeWithTag("project_name").performTextInput(name)
        compose.onNodeWithTag("confirm_create_project").performScrollTo().performClick()
        awaitCanvas()
        compose.runOnIdle { assertTrue(checkNotNull(model.state.value.document).elements.isEmpty()) }
    }

    private fun chooseTool(tool: String) {
        compose.cadTool(tool)
    }

    private fun toolsAction(action: String) {
        compose.cadAction(action)
    }

    private fun expandTools() {
        compose.cadGroup("draw")
    }

    private fun collapseTools() {
        compose.cadCloseStrip("draw")
    }

    private fun awaitCanvas() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("canvas_ready").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("drawing_canvas").assertIsDisplayed()
        compose.waitForIdle()
    }

    private fun awaitElements(count: Int) {
        compose.waitUntil(10_000) { model.state.value.document?.elements?.size == count }
        compose.waitForIdle()
    }

    private fun awaitSelectedScene(selected: Element) {
        compose.waitUntil(10_000) {
            val frame = renderedFrame.get()
            frame?.selectionScene?.segments?.any { it.elementId == selected.id.value } == true &&
                frame.selectionDelta == null
        }
        compose.waitForIdle()
    }

    private fun element(): Element = compose.runOnIdle { checkNotNull(model.state.value.document).elements.single() }
    private fun currentFrame(): CanvasFrame = compose.runOnIdle { checkNotNull(renderedFrame.get()) }
}
