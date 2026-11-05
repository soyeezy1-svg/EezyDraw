package com.eezydraw.ui.canvas

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.eezydraw.R
import com.eezydraw.core.editing.DrawingTool
import com.eezydraw.core.editing.ToolOptions
import com.eezydraw.core.editing.elementWorldBounds
import com.eezydraw.core.editing.readProperties
import com.eezydraw.core.editing.resizeFrame
import com.eezydraw.core.geometry.DrawingScene
import com.eezydraw.core.model.*
import com.eezydraw.ui.AppViewModel
import com.eezydraw.ui.EezyDrawApp
import com.eezydraw.ui.TestAppRepositories
import com.eezydraw.ui.canvas.editing.GizmoHandle
import com.eezydraw.ui.canvas.editing.GizmoMode
import com.eezydraw.ui.canvas.editing.gizmoHandles
import com.eezydraw.ui.canvas.editing.resizeHandle
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin
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

/** Full screen workflows, retaining real gestures/history and replacing only the EGL surface. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class CleanSelectionPlacementFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var repositories: TestAppRepositories
    private lateinit var model: AppViewModel
    private val rendered = AtomicReference<CanvasFrame>()
    private val drawing = AtomicReference<DrawingScene?>()

    @Before fun prepare() {
        // These regressions exercise the optional direct-tap workflow.
        repositories = TestAppRepositories(context, AppSettings(autosaveIntervalSeconds = 0,
            precisionPointer = PrecisionPointerSettings(enabled = false)))
        model = repositories.newViewModel()
    }
    @After fun close() { repositories.close() }

    @Test
    fun choosingAToolOnlyPreviewsThenTapSelectsAndPropertiesNeedsItsOwnClick() {
        startProject("Clear placement")
        chooseTool("box")
        assertElements(0)
        compose.onNodeWithTag("placement_hint").assertIsDisplayed().assertTextContains("box", substring = true, ignoreCase = true)
        compose.onNodeWithTag("placement_cancel").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("placement_done").assertIsDisplayed()
        val before = frame().viewport
        compose.onNodeWithTag("canvas_surface").performTouchInput {
            val first = Offset(width * .51f, height * .72f)
            down(first)
            moveTo(first + Offset(42f, -24f), delayMillis = 48)
        }
        compose.onNodeWithTag("construction_ghost").assertIsDisplayed()
        assertElements(0)
        assertEquals("An active placement drag tracks the ghost rather than panning", before, frame().viewport)
        compose.onNodeWithTag("canvas_surface").performTouchInput { up() }
        assertElements(0)
        tapCanvas(.51f, .72f)
        awaitElements(1)
        val element = singleElement()
        awaitRendered(element)
        compose.onNodeWithTag("placement_banner").assertDoesNotExist()
        compose.onNodeWithTag("construction_ghost").assertDoesNotExist()
        compose.onNodeWithTag("selection_toolbar").assertIsDisplayed()
        listOf("mode", "copy", "delete", "properties", "more").forEach { action ->
            compose.onNodeWithTag("selection_$action").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithTag("properties_side_panel").assertDoesNotExist()
        compose.onNodeWithTag("selection_properties").performClick()
        compose.onNodeWithTag("properties_side_panel").assertIsDisplayed()
        compose.onNodeWithTag("properties_close").performClick()
        compose.onNodeWithTag("properties_side_panel").assertDoesNotExist()
        tapCanvas(.94f, .92f)
        compose.runOnIdle { assertTrue(model.state.value.selectedIds.isEmpty()) }
        compose.onNodeWithTag("selection_toolbar").assertDoesNotExist()
        compose.onNodeWithTag("selection_gizmo").assertDoesNotExist()
    }

    @Test
    fun cancelAndKeepToolActiveGiveExplicitAtomicPlacementHistory() {
        startProject("Repeated placement", keepActive = true)
        chooseTool("box")
        compose.onNodeWithTag("placement_cancel").performClick()
        assertElements(0)
        compose.onNodeWithTag("placement_banner").assertDoesNotExist()
        chooseTool("box")
        tapCanvas(.40f, .72f)
        awaitElements(1)
        compose.onNodeWithTag("placement_banner").assertIsDisplayed()
        tapCanvas(.65f, .72f)
        awaitElements(2)
        compose.onNodeWithTag("placement_done").performClick()
        compose.onNodeWithTag("placement_banner").assertDoesNotExist()
        selectionAction("undo")
        awaitElements(1)
        compose.runOnIdle { model.undo() }
        awaitElements(0)
        compose.runOnIdle { assertFalse(model.state.value.dirty); assertFalse(model.state.value.canUndo) }
        compose.runOnIdle { model.redo() }
        awaitElements(1)
        compose.runOnIdle { model.redo() }
        awaitElements(2)
    }

    @Test
    fun choosingALibraryDefinitionWaitsForCanvasPlacementAndCancelLeavesNoEdit() {
        startProject("Library placement")
        val document = compose.runOnIdle { checkNotNull(model.state.value.document) }
        val socket = LibraryItem(LibraryItemId("socket"), "Socket", Discipline.ELECTRICAL,
            "Electrical", Size3(.12, .08, .12))
        reopen(document.copy(libraryItems = listOf(socket)))
        fun chooseSocket() {
            compose.onNodeWithTag("toolbar_library").performClick()
            compose.onNodeWithTag("library_insert_socket").performClick()
            compose.onNodeWithTag("library_panel").assertDoesNotExist()
            compose.onNodeWithTag("placement_hint").assertIsDisplayed()
            assertElements(0)
        }
        chooseSocket()
        compose.onNodeWithTag("placement_cancel").performClick()
        assertElements(0)
        compose.runOnIdle { assertFalse(model.state.value.canUndo) }
        chooseSocket()
        tapCanvas(.55f, .72f)
        awaitElements(1)
        val component = singleElement()
        assertEquals(socket.id, (component.body as ElementBody.Component).libraryItemId)
        compose.onNodeWithTag("placement_banner").assertDoesNotExist()
        compose.onNodeWithTag("selection_toolbar").assertIsDisplayed()
        selectionAction("undo")
        awaitElements(0)
        compose.runOnIdle { assertFalse(model.state.value.dirty) }
    }

    @Test
    fun cubeFrontAndSideKeepAnActiveShapeGhostOnItsConstructionFloorUntilTapped() {
        startProject("Placement in elevation views")
        val initial = compose.runOnIdle { checkNotNull(model.state.value.document) }
        val floor = 2.4
        val level = Level(LevelId("elevated-floor"), initial.project.id, "Elevated floor", floor, .2, 3.4)
        reopen(initial.copy(levels = listOf(level)))
        compose.onNodeWithTag("canvas_level").performScrollTo().performClick()
        compose.onNodeWithText(level.name).performClick()
        for (face in listOf(R.string.canvas_front, R.string.canvas_side)) {
            chooseTool("box")
            compose.cubeFace(context, face)
            assertTrue(frame().is3D)
            assertEquals(floor, frame().floorElevation, 1e-9)
            compose.onNodeWithTag("placement_banner").assertIsDisplayed()
            compose.onNodeWithTag("construction_ghost").assertIsDisplayed()
            assertElements(0)
            val camera = frame().orbit
            compose.onNodeWithTag("canvas_surface").performTouchInput {
                swipe(Offset(width * .50f, height * .62f), Offset(width * .61f, height * .68f), 240)
            }
            assertElements(0)
            assertEquals("An active elevation-view tool tracks its ghost rather than orbiting", camera, frame().orbit)
            val area = compose.onNodeWithTag("canvas_surface").fetchSemanticsNode().boundsInRoot
            val x = area.width * .61f
            val y = area.height * .68f
            val anchor = (camera.rayToPlaneZ(x.toDouble(), y.toDouble(), area.width.toDouble(), area.height.toDouble(), floor)
                ?: viewPlanePoint(camera, x.toDouble(), y.toDouble(), area.width.toDouble(), area.height.toDouble()))
                .copy(z = floor)
            val document = compose.runOnIdle { checkNotNull(model.state.value.document) }
            val ghost = checkNotNull(buildConstructionPreview(document, DrawingTool.BOX, ToolOptions(), emptyList(), anchor))
            assertEquals("The preview starts at the current construction floor", floor, ghost.bounds.min.z, 1e-9)
            tapCanvas(.61f, .68f)
            awaitElements(1)
            val element = singleElement()
            awaitRendered(element)
            assertEquals("Committed geometry must match the floor-anchored ghost in face $face", ghost.bounds,
                checkNotNull(frame().selectionScene).bounds)
            compose.onNodeWithTag("placement_banner").assertDoesNotExist()
            selectionAction("undo")
            awaitElements(0)
            compose.runOnIdle { assertFalse(model.state.value.dirty) }
        }
    }

    @Test
    fun moveGroundAndEveryAxisInTopFrontSideViewsAreSnappedAndUndoable() {
        startProject("Move axes")
        val initial = compose.runOnIdle { checkNotNull(model.state.value.document) }
        // A capture radius above half the spacing deliberately tests grid capture,
        // independently of the camera's metres-per-pixel and pointer release distance.
        reopen(initial.copy(project=initial.project.copy(settings=initial.project.settings.copy(
            snap=SnapSettings(modes=setOf(SnapMode.GRID),gridSpacing=.1,tolerance=.06)))))
        val original = placeBox()
        compose.cubeHome()
        awaitRendered(original)
        val before = original.transform.translation
        dragHandle(GizmoHandle.MOVE_FREE, GizmoMode.MOVE, Offset(47f, -31f))
        val free = singleElement()
        assertEquals("Free move is always the ground plane", before.z, free.transform.translation.z, 1e-9)
        assertTrue(abs(free.transform.translation.x - before.x) + abs(free.transform.translation.y - before.y) > .01)
        selectionAction("undo")
        awaitRendered(original)
        for (face in listOf(R.string.canvas_top, R.string.canvas_front, R.string.canvas_side)) {
            compose.cubeFace(context, face)
            for ((handle, axis) in listOf(GizmoHandle.MOVE_X to 0, GizmoHandle.MOVE_Y to 1, GizmoHandle.MOVE_Z to 2)) {
                awaitRendered(original)
                val control = control(handle, GizmoMode.MOVE)
                val step = checkNotNull(control.accessibleStep)
                val length = hypot(step.x, step.y)
                dragHandle(handle, GizmoMode.MOVE, Offset((step.x / length * 46).toFloat(), (step.y / length * 46).toFloat()))
                val moved = singleElement()
                val a = listOf(original.transform.translation.x, original.transform.translation.y, original.transform.translation.z)
                val b = listOf(moved.transform.translation.x, moved.transform.translation.y, moved.transform.translation.z)
                b.indices.forEach { index ->
                    if (index == axis) {
                        assertTrue("$handle must move in face $face", abs(b[index] - a[index]) > 1e-6)
                        // Snapping is absolute in project space, even if the original
                        // insertion fell outside the snap tolerance of a grid point.
                        val bounds = compose.runOnIdle { elementWorldBounds(checkNotNull(model.state.value.document), setOf(moved.id)) }
                        val center = listOf((bounds.min.x + bounds.max.x) / 2, (bounds.min.y + bounds.max.y) / 2, (bounds.min.z + bounds.max.z) / 2)[index]
                        assertEquals("Move pivot is snapped to the 100mm project grid", round(center / .1) * .1, center, 1e-8)
                    } else assertEquals("$handle must preserve axis $index", a[index], b[index], 1e-8)
                }
                selectionAction("undo")
                assertEquals("One axis gesture is one edit", original, singleElement())
            }
        }
    }

    @Test
    fun allThreeRotationBandsSnapToFifteenDegreesAndHaveOneStepUndoRedo() {
        startProject("Tilt and turn")
        val original = placeBox()
        compose.onNodeWithTag("selection_mode").performClick()
        for ((handle, axis) in listOf(GizmoHandle.ROTATE_X to 0, GizmoHandle.ROTATE_Y to 1, GizmoHandle.ROTATE_Z to 2)) {
            awaitRendered(original)
            val current = frame()
            val bounds = checkNotNull(current.selectionScene).bounds
            val area = compose.onNodeWithTag("canvas_surface").fetchSemanticsNode().boundsInRoot
            val pivot = bounds.center()
            val center = current.viewport.worldToScreen(Point2(pivot.x, pivot.y), area.width.toDouble(), area.height.toDouble())
            val item = control(handle, GizmoMode.ROTATE)
            val start = Offset(item.center.x.toFloat(), item.center.y.toFloat())
            val angle = -23 * PI / 180
            val x = item.center.x - center.x; val y = item.center.y - center.y
            val end = Offset((center.x + cos(angle) * x - sin(angle) * y).toFloat(),
                (center.y + sin(angle) * x + cos(angle) * y).toFloat())
            compose.onNodeWithTag("canvas_surface").performTouchInput { down(start); moveTo(end, delayMillis = 64) }
            val description = compose.onNodeWithTag("selection_gizmo").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
            assertTrue("Current snapped angle is visible while rotating", description.contains("30"))
            compose.onNodeWithTag("canvas_surface").performTouchInput { up() }
            compose.waitUntil(10_000) { model.state.value.document?.elements?.single()?.transform != original.transform }
            val rotated = singleElement()
            val rotation = rotated.transform.rotation
            listOf(rotation.x, rotation.y, rotation.z).forEachIndexed { index, value ->
                assertEquals("$handle rotates only its own axis", if (index == axis) PI / 6 else 0.0, value, 1e-7)
            }
            val size = compose.runOnIdle { readProperties(checkNotNull(model.state.value.document), rotated).size }
            assertEquals(Point3(1.0, 1.0, 1.0), size)
            selectionAction("undo")
            assertEquals(original, singleElement())
            selectionAction("redo")
            assertEquals(rotated, singleElement())
            selectionAction("undo")
        }
    }

    @Test
    fun moreKeepsScaleAndAppearanceEditsOutOfThePrimaryToolbarAndUndoRestoresThem() {
        startProject("Selection details")
        val initial = compose.runOnIdle { checkNotNull(model.state.value.document) }
        val alternate = Layer(LayerId("alternate"), initial.project.id, "Alternate")
        reopen(initial.copy(layers = initial.layers + alternate))
        val original = placeBox()
        selectionAction("scale")
        assertTrue(controls(GizmoMode.SCALE).any { it.handle.resizeHandle() != null })
        compose.onNodeWithTag("selection_mode").performClick()
        assertEquals(setOf(GizmoHandle.MOVE_FREE, GizmoHandle.MOVE_X, GizmoHandle.MOVE_Y, GizmoHandle.MOVE_Z),
            controls(GizmoMode.MOVE).map { it.handle }.toSet())
        selectionAction("rotate_90")
        assertEquals(PI / 2, singleElement().transform.rotation.z, 1e-8)
        selectionAction("undo")
        assertEquals(original, singleElement())
        openMore()
        compose.onNodeWithTag("selection_layer").performScrollTo().performClick()
        compose.onNodeWithTag("selection_layer_alternate").performClick()
        assertEquals(alternate.id, singleElement().layerId)
        selectionAction("undo")
        assertEquals(original, singleElement())
        openMore()
        compose.onNodeWithTag("selection_colour").performScrollTo().performClick()
        compose.onNodeWithTag("selection_colour_ffd64747").performClick()
        compose.waitUntil(10_000) { rendered.get()?.selectionScene?.segments?.any { it.elementId == original.id.value && it.colorArgb == 0xFFD64747L } == true }
        selectionAction("undo")
        assertEquals(original, singleElement())
        selectionAction("lock")
        compose.onNodeWithTag("selection_delete").assertIsNotEnabled()
        selectionAction("undo")
        compose.onNodeWithTag("selection_delete").assertIsEnabled()
        assertEquals(original, singleElement())
    }

    @Test
    fun measureDefaultsToDistanceOnlyWithoutOpeningAnyPanelAndCanBeUndoneAsOnePlacement() {
        startProject("Distance only")
        compose.onNodeWithTag("toolbar_measure").performClick()
        compose.onNodeWithTag("toolbar_measure_distance").performClick()
        assertElements(0)
        compose.onNodeWithTag("placement_hint").assertIsDisplayed()
        tapCanvas(.40f, .72f)
        assertElements(0)
        compose.onNodeWithTag("measurement_ghost").assertIsDisplayed()
        tapCanvas(.66f, .56f)
        awaitElements(1)
        val annotation = singleElement()
        val labels = compose.runOnIdle {
            val document = checkNotNull(model.state.value.document)
            val summary = checkNotNull(resolveMeasurement(document, annotation))
            measurementText(summary, document.project.settings)
        }
        assertEquals("Default measurement has one distance readout", 1, labels.size)
        assertFalse(labels.single().contains("AZ"))
        assertFalse(labels.single().contains("EL"))
        assertFalse(labels.single().contains("X "))
        compose.onNodeWithTag("placement_banner").assertDoesNotExist()
        compose.onNodeWithTag("measurements_panel").assertDoesNotExist()
        compose.onNodeWithTag("properties_side_panel").assertDoesNotExist()
        compose.onNodeWithTag("selection_toolbar").assertIsDisplayed()
        selectionAction("undo")
        awaitElements(0)
        compose.runOnIdle { assertFalse(model.state.value.dirty) }
    }

    private fun startProject(name: String, keepActive: Boolean = false) {
        if (keepActive) repositories.settings.settings.value = repositories.settings.settings.value.copy(keepToolActiveAfterPlacing = true)
        compose.mainClock.autoAdvance = false
        compose.setContent { EezyDrawApp(model, canvasSurface = { scene, frame, modifier ->
            SideEffect { drawing.set(scene); rendered.set(frame) }
            Box(modifier)
        }) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(3_000)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText(context.getString(R.string.create_new_project)).performScrollTo().performClick()
        compose.onNodeWithTag("project_name").performTextInput(name)
        compose.onNodeWithTag("confirm_create_project").performScrollTo().performClick()
        awaitReady()
    }

    private fun chooseTool(tool: String) {
        if (compose.onAllNodesWithTag("tools_expand").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("tools_expand").performClick()
        else if (compose.onAllNodesWithTag("tool_$tool").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("toolbar_draw").performClick()
        compose.onNodeWithTag("tool_$tool").performScrollTo().performClick()
        if (compose.onAllNodesWithTag("tools_collapse").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("tools_collapse").performClick()
        compose.waitForIdle()
    }

    private fun placeBox(): Element {
        chooseTool("box")
        tapCanvas(.51f, .72f)
        awaitElements(1)
        return singleElement().also(::awaitRendered)
    }

    private fun tapCanvas(x: Float, y: Float) {
        compose.onNodeWithTag("canvas_surface").performTouchInput { click(Offset(width * x, height * y)) }
        compose.waitForIdle()
    }

    private fun dragHandle(handle: GizmoHandle, mode: GizmoMode, change: Offset) {
        val original = singleElement()
        awaitRendered(original)
        val item = control(handle, mode)
        val start = Offset(item.center.x.toFloat(), item.center.y.toFloat())
        compose.onNodeWithTag("canvas_surface").performTouchInput { swipe(start, start + change, 240) }
        compose.waitUntil(10_000) { model.state.value.document?.elements?.single()?.transform != original.transform }
        compose.waitForIdle()
    }

    private fun control(handle: GizmoHandle, mode: GizmoMode) = controls(mode).single { it.handle == handle }

    private fun controls(mode: GizmoMode): List<com.eezydraw.ui.canvas.editing.GizmoControl> {
        val frame = frame()
        val element = singleElement()
        val resize = compose.runOnIdle { resizeFrame(checkNotNull(model.state.value.document), element) }
        val area = compose.onNodeWithTag("canvas_surface").fetchSemanticsNode().boundsInRoot
        return gizmoHandles(checkNotNull(frame.selectionScene).bounds, frame.viewport, frame.orbit, frame.is3D,
            area.width.toDouble(), area.height.toDouble(), context.resources.displayMetrics.density.toDouble(),
            resizeFrame = resize, mode = mode)
    }

    private fun openMore() { compose.onNodeWithTag("selection_more").performClick() }
    private fun selectionAction(action: String) {
        openMore()
        compose.onNodeWithTag("selection_$action").performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
    }

    private fun reopen(document: ProjectDocument) {
        compose.runOnIdle {
            repositories.projects.documents[document.project.id.value] = document
            model.open(document.project.id.value)
        }
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.document == document }
        awaitReady()
    }

    private fun awaitReady() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("canvas_ready").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("canvas_extents") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun awaitRendered(element: Element) {
        val expected = compose.runOnIdle { elementWorldBounds(checkNotNull(model.state.value.document), setOf(element.id)) }
        compose.waitUntil(10_000) { rendered.get()?.let { it.selectionDelta == null && it.selectionScene?.bounds == expected &&
            it.selectionScene?.segments?.any { edge -> edge.elementId == element.id.value } == true } == true }
        compose.waitForIdle()
    }

    private fun awaitElements(count: Int) {
        compose.waitUntil(10_000) { model.state.value.document?.elements?.size == count }
        compose.waitForIdle()
    }
    private fun assertElements(count: Int) { compose.runOnIdle { assertEquals(count, model.state.value.document?.elements?.size) } }
    private fun singleElement(): Element = compose.runOnIdle { checkNotNull(model.state.value.document).elements.single() }
    private fun frame(): CanvasFrame = compose.runOnIdle { checkNotNull(rendered.get()) }
}
