package com.eezydraw.ui.canvas

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import com.eezydraw.R
import com.eezydraw.core.editing.elementWorldBounds
import com.eezydraw.core.editing.resizeFrame
import com.eezydraw.core.model.*
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

/** Full application workflows; only the Android EGL surface is substituted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class CanvasWorkflowImprovementsTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var repositories: TestAppRepositories
    private lateinit var model: AppViewModel
    private val rendered = AtomicReference<CanvasFrame>()

    @Before fun prepare() {
        repositories = TestAppRepositories(context)
        model = repositories.newViewModel()
    }
    @After fun close() { repositories.close() }

    @Test fun freeMoveHandleSnapsBothAxesCommitsOnceAndFullscreenRetainsTheStatusBar() {
        startProject("Grab and move")
        val created = placeBox()
        chooseTool("select")
        awaitRendered(created)
        val beforePan = frame().viewport
        dragBody(Offset(35f, 20f))
        assertEquals("Body movement remains navigation by default", created, singleElement())
        assertNotEquals(beforePan.center, frame().viewport.center)

        val current = frame()
        val area = compose.onNodeWithTag("canvas_surface").fetchSemanticsNode().boundsInRoot
        val local = compose.runOnIdle { resizeFrame(checkNotNull(model.state.value.document), created) }
        val grab = gizmoHandles(checkNotNull(current.selectionScene).bounds, current.viewport, current.orbit,
            current.is3D, area.width.toDouble(), area.height.toDouble(), context.resources.displayMetrics.density.toDouble(), local)
            .single { it.handle == GizmoHandle.MOVE_FREE }
        val start = Offset(grab.center.x.toFloat(), grab.center.y.toFloat())
        val change = Offset((.27 * current.viewport.pixelsPerMetre).toFloat(), (-.18 * current.viewport.pixelsPerMetre).toFloat())
        compose.onNodeWithTag("canvas_surface").performTouchInput { swipe(start, start + change, 240) }
        compose.waitUntil(10_000) { model.state.value.document?.elements?.single()?.transform != created.transform }
        val moved = singleElement()
        assertEquals(.3, moved.transform.translation.x - created.transform.translation.x, 1e-8)
        assertEquals(.2, moved.transform.translation.y - created.transform.translation.y, 1e-8)
        assertEquals(created.transform.translation.z, moved.transform.translation.z, 0.0)
        editAction("undo")
        assertEquals("One completed grab is one undo entry", created, singleElement())
        collapseTools()

        compose.onNodeWithTag("canvas_hide_panels").performClick()
        compose.onNodeWithTag("canvas_status").assertIsDisplayed()
        compose.onNodeWithTag("canvas_save").assertDoesNotExist()
        compose.onNodeWithTag("toolbar_draw").assertDoesNotExist()
        compose.onNodeWithTag("tools_expand").assertDoesNotExist()
        compose.onNodeWithTag("canvas_show_panels").assertIsDisplayed().performClick()
        compose.onNodeWithTag("toolbar_draw").assertIsDisplayed()
        compose.onNodeWithTag("canvas_status").assertIsDisplayed()
    }

    @Test fun optionalBodyDraggingMovesTheSelectedObjectAndLeavesTheCameraStill() {
        startProject("Direct movement", bodyDragging = true)
        val created = placeBox()
        chooseTool("select")
        awaitRendered(created)
        val camera = frame().viewport
        dragBody(Offset(45f, 30f))
        compose.waitUntil(10_000) { model.state.value.document?.elements?.single()?.transform != created.transform }
        val moved = singleElement()
        assertTrue(abs(moved.transform.translation.x - created.transform.translation.x) > .01)
        assertTrue(abs(moved.transform.translation.y - created.transform.translation.y) > .01)
        assertEquals(camera, frame().viewport)
        editAction("undo")
        assertEquals(created, singleElement())
    }

    @Test fun viewModeLayerAndSystemVisibilityPersistAndHiddenObjectsLoseSelection() {
        startProject("Saved appearance")
        val box = placeBox()
        val systemId = SystemId("power")
        val document = compose.runOnIdle { checkNotNull(model.state.value.document) }
        val fixture = document.copy(systems = listOf(BuildingSystem(systemId, document.project.id, "Power", Discipline.ELECTRICAL, 0xFF4477AA)),
            elements = listOf(box.copy(systemId = systemId)))
        reopenFixture(fixture)
        compose.runOnIdle { model.select(setOf(box.id)) }
        awaitRendered(fixture.elements.single())
        compose.onNodeWithTag("canvas_view_mode").performClick()
        compose.onNodeWithTag("view_mode_shaded_with_edges").performClick()
        compose.waitUntil(10_000) { rendered.get()?.viewMode == ViewMode.SHADED_WITH_EDGES }
        compose.runOnIdle { assertEquals(ViewMode.SHADED_WITH_EDGES, model.state.value.document?.project?.settings?.viewMode) }

        compose.onNodeWithTag("canvas_visibility").performClick()
        compose.onNodeWithTag("visibility_system_power").performClick()
        compose.runOnIdle {
            assertFalse(checkNotNull(model.state.value.document).systems.single().visible)
            assertTrue(model.state.value.selectedIds.isEmpty())
        }
        compose.onNodeWithTag("visibility_system_power").performClick()
        compose.onNodeWithTag("visibility_layer_${box.layerId.value}").performClick()
        compose.onNodeWithTag("visibility_measurements").performClick()
        compose.onNodeWithText(context.getString(R.string.close)).performClick()
        save()
        val id = fixture.project.id.value
        compose.runOnIdle { model.open(id) }
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.document?.project?.id?.value == id }
        compose.runOnIdle {
            val restored = checkNotNull(model.state.value.document)
            assertEquals(ViewMode.SHADED_WITH_EDGES, restored.project.settings.viewMode)
            assertFalse(restored.layers.single().visible)
            assertTrue(restored.systems.single().visible)
            assertFalse(restored.project.settings.showMeasurements)
        }
    }

    @Test fun measureToolbarCreatesAnEditableSavedMeasurementThatCanBeHiddenAndDeleted() {
        startProject("Position sharing")
        compose.onNodeWithTag("toolbar_measure").performClick()
        compose.onNodeWithTag("toolbar_measure_distance").performClick()
        compose.onNodeWithTag("canvas_surface").performTouchInput {
            click(Offset(width * .40f, height * .62f))
            click(Offset(width * .60f, height * .42f))
        }
        awaitElements(1)
        val annotation = singleElement()
        val summary = compose.runOnIdle { checkNotNull(resolveMeasurement(checkNotNull(model.state.value.document), annotation)) }
        assertTrue(summary.distance > 0.0)
        assertTrue(summary.showCoordinates)
        assertEquals("Measurement", summary.name)
        compose.onNodeWithTag("measurements_panel").assertIsDisplayed()
        compose.onNodeWithTag("measurement_edit_${annotation.id.value}").performScrollTo().performClick()
        compose.onNodeWithTag("measurement_name").performScrollTo().performTextReplacement("Shared position")
        compose.onNodeWithTag("measurement_apply").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("Shared position", resolveMeasurement(checkNotNull(model.state.value.document), singleElement())?.name)
        }
        compose.onNodeWithTag("measurements_visible").performClick()
        compose.runOnIdle {
            assertFalse(checkNotNull(model.state.value.document).project.settings.showMeasurements)
            assertTrue(model.state.value.selectedIds.isEmpty())
        }
        save()
        val saved = repositories.projects.documents.values.single()
        assertEquals("Shared position", resolveMeasurement(saved, saved.elements.single())?.name)
        compose.onNodeWithTag("measurement_delete_${annotation.id.value}").performScrollTo().performClick()
        awaitElements(0)
        compose.runOnIdle { model.undo() }
        awaitElements(1)
        compose.runOnIdle { assertEquals("Shared position", resolveMeasurement(checkNotNull(model.state.value.document), singleElement())?.name) }
    }

    @Test fun referenceDimensionUsesComponentInsertionHeightAndWallCorner() {
        startProject("Socket reference")
        val base = compose.runOnIdle { checkNotNull(model.state.value.document) }
        val level = Level(LevelId("floor"), base.project.id, "Ground floor", 0.0, .2, 3.0)
        val socketId = ElementId("socket")
        val wallId = ElementId("wall")
        val libraryId = LibraryItemId("socket-symbol")
        val socket = Element(socketId, base.project.id, base.layers.single().id,
            ElementBody.Component(libraryId, Size3(.12, .08, .12)), levelId = level.id,
            transform = Transform3(translation = Point3(.3, 0.0, 1.2)))
        val wall = Element(wallId, base.project.id, base.layers.single().id,
            ElementBody.Wall(Point2(0.0, 0.0), Point2(0.0, 2.0), .1, 3.0,WallReference.LEFT_FINISH_FACE), levelId = level.id)
        val item = LibraryItem(libraryId, "Socket", Discipline.ELECTRICAL, "Outlets", Size3(.12, .08, .12))
        reopenFixture(base.copy(levels = listOf(level), elements = listOf(socket, wall), libraryItems = listOf(item)))
        compose.onNodeWithTag("canvas_extents").assertIsEnabled().performClick()
        compose.onNodeWithTag("toolbar_measure").performClick()
        compose.onNodeWithTag("toolbar_reference_dimension").performClick()
        clickWorld(Point3(.3, 0.0, 1.2))
        clickWorld(Point3(0.0, 0.0, 0.0))
        clickWorld(Point3(.7, .5, 0.0))
        awaitElements(3)
        compose.runOnIdle {
            val doc = checkNotNull(model.state.value.document)
            val measurement = measurementElements(doc).single()
            val summary = checkNotNull(resolveMeasurement(doc, measurement))
            assertEquals(socketId, summary.sourceElementId)
            assertEquals(wallId, summary.referenceElementId)
            assertEquals(.3, summary.horizontalDistance, 1e-8)
            assertEquals(1.2, checkNotNull(summary.heightAboveFloorMetres), 1e-8)
        }
    }

    private fun startProject(name: String, bodyDragging: Boolean = false) {
        if (bodyDragging) repositories.settings.settings.value = repositories.settings.settings.value.copy(bodyDragMovesSelection = true)
        compose.mainClock.autoAdvance = false
        compose.setContent { EezyDrawApp(model, canvasSurface = { _, frame, modifier ->
            SideEffect { rendered.set(frame) }
            Box(modifier)
        }) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(3_000)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("start_menu").assertIsDisplayed()
        compose.runOnIdle { model.create(name, ProjectTemplate.EMPTY, LengthUnit.MILLIMETRES) }
        awaitCanvas()
    }

    private fun placeBox(): Element {
        chooseTool("box")
        compose.onNodeWithTag("canvas_surface").performTouchInput { click(Offset(width * .42f, height * .62f)) }
        awaitElements(1)
        return singleElement()
    }

    private fun chooseTool(tool: String) {
        collapseTools()
        compose.onNodeWithTag("toolbar_draw").performClick()
        compose.onNodeWithTag("tool_$tool").performScrollTo().performClick()
        collapseTools()
    }

    private fun editAction(action: String) {
        collapseTools()
        compose.onNodeWithTag("toolbar_edit").performClick()
        compose.onNodeWithTag("tools_$action").performScrollTo().assertIsEnabled().performClick()
    }

    private fun collapseTools() {
        if (compose.onAllNodesWithTag("tools_collapse").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag("tools_collapse").performClick()
    }

    private fun dragBody(change: Offset) {
        val before = singleElement()
        awaitRendered(before)
        val scene = checkNotNull(frame().selectionScene)
        val center = scene.bounds.center()
        val area = compose.onNodeWithTag("canvas_surface").fetchSemanticsNode().boundsInRoot
        val p = frame().viewport.worldToScreen(Point2(center.x, center.y), area.width.toDouble(), area.height.toDouble())
        val point = Offset(p.x.toFloat(), p.y.toFloat())
        compose.onNodeWithTag("canvas_surface").performTouchInput { swipe(point, point + change, 240) }
        compose.waitForIdle()
    }

    private fun clickWorld(point: Point3) {
        val area = compose.onNodeWithTag("canvas_surface").fetchSemanticsNode().boundsInRoot
        val screen = frame().viewport.worldToScreen(Point2(point.x, point.y), area.width.toDouble(), area.height.toDouble())
        compose.onNodeWithTag("canvas_surface").performTouchInput { click(Offset(screen.x.toFloat(), screen.y.toFloat())) }
        compose.waitForIdle()
    }

    private fun reopenFixture(document: ProjectDocument) {
        compose.runOnIdle {
            repositories.projects.documents[document.project.id.value] = document
            model.open(document.project.id.value)
        }
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.document == document }
        awaitCanvas()
        compose.waitUntil(10_000) {compose.onAllNodes(hasTestTag("canvas_extents") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        compose.waitUntil(10_000) { rendered.get()?.selectionDelta == null }
    }

    private fun save() {
        compose.onNodeWithTag("canvas_save").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && !model.state.value.dirty }
    }

    private fun awaitCanvas() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("canvas_ready").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun awaitRendered(element: Element) {
        val expected = compose.runOnIdle { elementWorldBounds(checkNotNull(model.state.value.document), setOf(element.id)) }
        compose.waitUntil(10_000) {
            rendered.get()?.let { it.selectionDelta == null && it.selectionScene?.bounds == expected &&
                it.selectionScene?.segments?.any { edge -> edge.elementId == element.id.value } == true } == true
        }
        compose.waitForIdle()
    }

    private fun awaitElements(count: Int) {
        compose.waitUntil(10_000) { model.state.value.document?.elements?.size == count }
        compose.waitForIdle()
    }
    private fun singleElement(): Element = compose.runOnIdle { checkNotNull(model.state.value.document).elements.single() }
    private fun frame(): CanvasFrame = compose.runOnIdle { checkNotNull(rendered.get()) }
}
