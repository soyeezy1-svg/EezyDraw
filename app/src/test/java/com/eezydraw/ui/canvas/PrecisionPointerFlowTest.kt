package com.eezydraw.ui.canvas

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
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
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.eezydraw.R
import com.eezydraw.core.editing.offsetCrosshair
import com.eezydraw.core.model.*
import com.eezydraw.ui.AppViewModel
import com.eezydraw.ui.EezyDrawApp
import com.eezydraw.ui.TestAppRepositories
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.hypot
import kotlin.math.round
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real input, tool state and document history; only EGL drawing is substituted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class PrecisionPointerFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var repositories: TestAppRepositories
    private lateinit var model: AppViewModel
    private val rendered = AtomicReference<CanvasFrame>()
    private val pointerView = AtomicReference<View>()
    private val hapticEvents = mutableListOf<HapticFeedbackType>()
    private val feedback = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            hapticEvents += hapticFeedbackType
        }
    }

    @Before fun prepare() {
        repositories = TestAppRepositories(context)
        model = repositories.newViewModel()
    }
    @After fun close() { repositories.close() }

    @Test
    fun touchAndLiftRetainAnOffsetCandidateUntilPlaceAndOneUndoRemovesTheObject() {
        startProject("Confirm precision placement")
        disableSnaps()
        chooseTool("box")
        compose.onNodeWithTag("precision_place").assertIsNotEnabled()
            .assertWidthIsAtLeast(56.dp).assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag("precision_cancel").assertHeightIsAtLeast(56.dp)
        val current = frame()
        val area = canvasSize()
        val finger = Point2(area.x * .40 + 12, area.y * .80 + 6)
        val expected = expectedPlanPoint(finger, current, area)
        compose.onNodeWithTag("canvas_surface").performTouchInput {
            down(Offset(width * .40f, height * .80f))
            moveTo(Offset(finger.x.toFloat(), finger.y.toFloat()), delayMillis = 48)
        }
        compose.onNodeWithTag("precision_loupe").assertIsDisplayed()
        compose.onNodeWithTag("precision_crosshair").assertExists()
        assertElements(0)
        assertEquals("Precision dragging must not pan the drawing", current.viewport, frame().viewport)
        compose.onNodeWithTag("canvas_surface").performTouchInput { up() }
        assertElements(0)
        compose.onNodeWithTag("precision_loupe").assertDoesNotExist()
        compose.onNodeWithTag("precision_crosshair").assertExists()
        confirm()
        awaitElements(1)
        val placed = singleElement()
        assertPoint(expected, placed.transform.translation)
        val atFinger = current.viewport.screenToWorld(finger.x, finger.y, area.x, area.y)
        assertTrue("The confirmed point must stay away from the finger", hypot(expected.x - atFinger.x, expected.y - atFinger.y) > .01)
        compose.onNodeWithTag("precision_place").assertDoesNotExist()
        compose.onNodeWithTag("view_undo").performScrollTo().performClick()
        awaitElements(0)
        compose.runOnIdle { assertFalse(model.state.value.dirty) }
        compose.onNodeWithTag("view_redo").performScrollTo().performClick()
        awaitElements(1)
        assertEquals(placed, singleElement())
    }

    @Test
    fun cancellingTheRetainedCandidateEndsTheToolWithoutChangingTheDocument() {
        startProject("Cancel pointing")
        chooseTool("rectangle")
        touch(.40f, .80f)
        assertElements(0)
        compose.onNodeWithTag("precision_place").assertIsEnabled()
        compose.onNodeWithTag("precision_cancel").performClick()
        compose.onNodeWithTag("precision_pointer").assertDoesNotExist()
        compose.onNodeWithTag("placement_banner").assertDoesNotExist()
        assertElements(0)
        compose.runOnIdle { assertFalse(model.state.value.canUndo); assertFalse(model.state.value.dirty) }
    }

    @Test
    fun linePicksRequireSeparateConfirmationsAndPartialPicksSupportUndoRedo() {
        startProject("Confirmed line endpoints")
        disableSnaps()
        chooseTool("line")
        val first = expectedPlanPoint(finger(.40f, .80f), frame(), canvasSize())
        touch(.40f, .80f)
        assertElements(0)
        confirm()
        assertElements(0)
        compose.onNodeWithTag("view_undo").performScrollTo().assertIsEnabled().performClick()
        assertElements(0)
        compose.onNodeWithTag("view_redo").performScrollTo().assertIsEnabled().performClick()
        touch(.40f, .88f)
        assertElements(0)
        compose.onNodeWithTag("precision_distance").assertExists()
        confirm()
        awaitElements(1)
        val line = singleElement()
        val shape = (line.body as ElementBody.DrawingShape).shape as Shape.Line
        assertPoint(first, measurementToWorld(line.transform, shape.start))
        assertTrue(hypot(shape.end.x - shape.start.x, shape.end.y - shape.start.y) > .01)
        compose.onNodeWithTag("view_undo").performScrollTo().performClick()
        awaitElements(0)
    }

    @Test
    fun measureUsesConfirmedPointsAndNeverSavesOnFingerRelease() {
        startProject("Confirmed measurement")
        compose.onNodeWithTag("toolbar_measure").performClick()
        compose.onNodeWithTag("toolbar_measure_distance").performClick()
        touch(.40f, .80f)
        assertElements(0)
        confirm()
        assertElements(0)
        touch(.40f, .88f)
        assertElements(0)
        compose.onNodeWithTag("precision_distance").assertExists()
        confirm()
        awaitElements(1)
        compose.runOnIdle {
            val document = checkNotNull(model.state.value.document)
            assertTrue(checkNotNull(resolveMeasurement(document, document.elements.single())).distance > .01)
        }
        compose.onNodeWithTag("view_undo").performScrollTo().performClick()
        awaitElements(0)
    }

    @Test
    fun aLibraryItemWaitsForPlaceAndCommitsAtTheSamePrecisionCandidate() {
        startProject("Precision library placement")
        val initial = document()
        val socket = LibraryItem(LibraryItemId("precision-socket"), "Precision socket", Discipline.ELECTRICAL,
            "Electrical", Size3(.12, .08, .12))
        reopen(initial.copy(libraryItems = listOf(socket), project = initial.project.copy(settings = initial.project.settings.copy(
            snap = initial.project.settings.snap.copy(enabled = false)))))
        compose.onNodeWithTag("toolbar_library").performClick()
        compose.onNodeWithTag("library_insert_precision-socket").performClick()
        compose.onNodeWithTag("library_panel").assertDoesNotExist()
        assertElements(0)
        val expected = expectedPlanPoint(finger(.40f, .80f), frame(), canvasSize())
        touch(.40f, .80f)
        assertElements(0)
        compose.onNodeWithTag("library_ghost").assertExists()
        confirm()
        awaitElements(1)
        val component = singleElement()
        assertEquals(socket.id, (component.body as ElementBody.Component).libraryItemId)
        assertPoint(expected, component.transform.translation)
        compose.onNodeWithTag("precision_place").assertDoesNotExist()
        compose.onNodeWithTag("view_undo").performScrollTo().performClick()
        awaitElements(0)
        compose.runOnIdle { assertFalse(model.state.value.dirty) }
    }

    @Test
    fun aPlanReferenceShowsAndSavesTheRaisedComponentInsertionHeight() {
        startProject("Raised socket reference")
        compose.onNodeWithTag("toolbar_measure").performClick()
        compose.onNodeWithTag("toolbar_reference_dimension").performClick()
        // Build the host around a safe raw aim point in the active tool layout.
        // Grid is enabled deliberately: a grid candidate must not replace the
        // component's mounting/insertion position with a floor-plane position.
        val initial = document()
        val aim = expectedPlanPoint(finger(.40f, .80f), frame(), canvasSize())
        val socket = LibraryItem(LibraryItemId("raised-socket"), "Raised socket", Discipline.ELECTRICAL,
            "Electrical", Size3(.6, .6, .2))
        val insertion = Point3(aim.x - .3, aim.y - .3, 1.2)
        val host = Element(ElementId("raised-socket-instance"), initial.project.id, initial.layers.first().id,
            ElementBody.Component(socket.id, socket.defaultSize), name = socket.name,
            transform = Transform3(translation = insertion))
        reopen(initial.copy(libraryItems = listOf(socket), elements = listOf(host), project = initial.project.copy(
            settings = initial.project.settings.copy(snap = SnapSettings(modes = setOf(SnapMode.GRID),
                gridSpacing = .1, tolerance = .1)))))
        touch(.40f, .80f)
        val cursor = compose.onNodeWithTag("precision_crosshair").fetchSemanticsNode().config
        assertPoint(insertion, Point3(cursor[PrecisionWorldX], cursor[PrecisionWorldY], cursor[PrecisionWorldZ]))
        assertElements(1)
        confirm()
        assertElements(1)
        touch(.40f, .38f)
        confirm()
        assertElements(1)
        touch(.40f, .90f)
        confirm()
        awaitElements(2)
        compose.runOnIdle {
            val document = checkNotNull(model.state.value.document)
            val annotation = document.elements.single { it.id != host.id }
            val summary = checkNotNull(resolveMeasurement(document, annotation))
            assertPoint(insertion, summary.start)
            assertEquals(1.2, checkNotNull(summary.heightAboveFloorMetres), 1e-9)
        }
        compose.onNodeWithTag("view_undo").performScrollTo().performClick()
        awaitElements(1)
        compose.runOnIdle { assertEquals(host, checkNotNull(model.state.value.document).elements.single()) }
    }

    @Test
    fun nudgeMovesByTheConfiguredPhysicalStepInMillimetreProjects() {
        repositories.settings.settings.value = repositories.settings.settings.value.copy(
            precisionPointer = PrecisionPointerSettings(nudgeStepMetres = .025))
        startProject("Exact pointer nudge")
        disableSnaps()
        chooseTool("box")
        val before = expectedPlanPoint(finger(.40f, .80f), frame(), canvasSize())
        touch(.40f, .80f)
        compose.onNodeWithTag("precision_nudge_right").performClick()
        compose.onNodeWithTag("precision_nudge_right").performClick()
        compose.onNodeWithTag("precision_nudge_up").performClick()
        assertElements(0)
        confirm()
        awaitElements(1)
        assertPoint(Point3(before.x + .05, before.y + .025, before.z), singleElement().transform.translation)
    }

    @Test
    fun snappingDisplaysItsMarkerAndConfirmsTheGridPointRatherThanTheFingerPoint() {
        startProject("Visible snap marker")
        val initial = document()
        reopen(initial.copy(project = initial.project.copy(settings = initial.project.settings.copy(
            snap = SnapSettings(modes = setOf(SnapMode.GRID), gridSpacing = .1, tolerance = .1)))))
        chooseTool("box")
        touch(.40f, .80f)
        compose.onNodeWithTag("precision_snap").assertExists()
            .assertTextContains("grid", substring = true, ignoreCase = true)
        confirm()
        awaitElements(1)
        val at = singleElement().transform.translation
        assertEquals(round(at.x / .1) * .1, at.x, 1e-9)
        assertEquals(round(at.y / .1) * .1, at.y, 1e-9)
    }

    @Test
    fun snapChangesVibrateOnceAndThePreferenceCanSilenceThem() {
        startProject("Snap haptic feedback")
        val initial = document()
        reopen(initial.copy(project = initial.project.copy(settings = initial.project.settings.copy(
            snap = SnapSettings(modes = setOf(SnapMode.GRID), gridSpacing = .1, tolerance = .1)))))
        chooseTool("box")
        touch(.40f, .80f)
        compose.runOnIdle {
            assertEquals(listOf(HapticFeedbackType.TextHandleMove), hapticEvents)
        }
        touch(.40f, .80f)
        compose.runOnIdle { assertEquals("The same snap must not buzz again on lift or retouch", 1, hapticEvents.size) }
        touch(.40f, .88f)
        compose.runOnIdle {
            assertEquals("A different grid point gives one new cue", 2, hapticEvents.size)
            val current = repositories.settings.settings.value
            repositories.settings.settings.value = current.copy(
                precisionPointer = current.precisionPointer.copy(hapticFeedback = false))
        }
        compose.waitForIdle()
        touch(.40f, .67f)
        compose.runOnIdle {
            assertEquals("The haptic preference must suppress subsequent snap cues", 2, hapticEvents.size)
            assertFalse(model.state.value.dirty)
        }
        assertElements(0)
    }

    @Test
    fun precisionPlacementWorksInFrontAndSideViewsOnAnElevatedLevel() {
        startProject("Precision on another floor")
        val initial = document()
        val level = Level(LevelId("pointer-floor"), initial.project.id, "Pointer floor", 2.4, .2, 3.4)
        reopen(initial.copy(levels = listOf(level), project = initial.project.copy(settings = initial.project.settings.copy(
            snap = initial.project.settings.snap.copy(enabled = false)))))
        compose.onNodeWithTag("canvas_level").performScrollTo().performClick()
        compose.onNodeWithText(level.name).performClick()
        for (face in listOf(R.string.canvas_front, R.string.canvas_side)) {
            chooseTool("box")
            compose.cubeFace(context, face)
            assertTrue(frame().is3D)
            assertEquals(level.elevation, frame().floorElevation, 1e-9)
            val camera = frame().orbit
            touch(.40f, .80f)
            assertElements(0)
            assertEquals(camera, frame().orbit)
            val crosshair = compose.onNodeWithTag("precision_crosshair").fetchSemanticsNode().config
            val candidate = Point3(crosshair[PrecisionWorldX], crosshair[PrecisionWorldY], crosshair[PrecisionWorldZ])
            confirm()
            awaitElements(1)
            val placed = singleElement()
            assertEquals(level.id, placed.levelId)
            // Edge-on front/side picks use the camera-facing construction plane;
            // its Z can vary above the active floor and must not be flattened on commit.
            assertPoint(candidate, placed.transform.translation)
            compose.onNodeWithTag("view_undo").performScrollTo().performClick()
            awaitElements(0)
        }
    }

    @Test
    fun turningPrecisionOffRestoresDirectTapPlacement() {
        repositories.settings.settings.value = repositories.settings.settings.value.copy(
            precisionPointer = PrecisionPointerSettings(enabled = false))
        startProject("Direct tap preference")
        disableSnaps()
        chooseTool("box")
        val current = frame()
        val area = canvasSize()
        val at = finger(.40f, .80f)
        val world = current.viewport.screenToWorld(at.x, at.y, area.x, area.y)
        touch(.40f, .80f)
        awaitElements(1)
        assertPoint(Point3(world.x, world.y, current.floorElevation), singleElement().transform.translation)
        compose.onNodeWithTag("precision_place").assertDoesNotExist()
    }

    @Test
    fun aStylusUsesItsTipWithoutFingerOffsetButStillWaitsForPlace() {
        startProject("Stylus precision")
        disableSnaps()
        chooseTool("box")
        val current = frame()
        val area = canvasSize()
        val tip = finger(.40f, .80f)
        val world = current.viewport.screenToWorld(tip.x, tip.y, area.x, area.y)
        val bounds = compose.onNodeWithTag("canvas_surface").fetchSemanticsNode().boundsInRoot
        val downTime = SystemClock.uptimeMillis()
        fun pen(action: Int, elapsed: Long) {
            compose.runOnIdle {
                val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
                val coordinates = MotionEvent.PointerCoords().apply {
                    x = bounds.left + tip.x.toFloat(); y = bounds.top + tip.y.toFloat(); pressure = .5f; size = .05f
                }
                val event = MotionEvent.obtain(downTime, downTime + elapsed, action, 1, arrayOf(properties), arrayOf(coordinates),
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
                try { pointerView.get().dispatchTouchEvent(event) } finally { event.recycle() }
            }
        }
        pen(MotionEvent.ACTION_DOWN, 0)
        pen(MotionEvent.ACTION_MOVE, 32)
        pen(MotionEvent.ACTION_UP, 48)
        assertElements(0)
        compose.onNodeWithTag("precision_place").assertIsEnabled()
        confirm()
        awaitElements(1)
        assertPoint(Point3(world.x, world.y, current.floorElevation), singleElement().transform.translation)
    }

    private fun startProject(name: String) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                EezyDrawApp(model, canvasSurface = { _, current, modifier ->
                    val view = LocalView.current
                    SideEffect { rendered.set(current); pointerView.set(view) }
                    Box(modifier)
                })
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(3_000)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText(context.getString(R.string.create_new_project)).performScrollTo().performClick()
        compose.onNodeWithTag("project_name").performTextInput(name)
        compose.onNodeWithTag("confirm_create_project").performScrollTo().performClick()
        awaitReady()
    }

    private fun chooseTool(name: String) {
        if (compose.onAllNodesWithTag("tools_expand").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("tools_expand").performClick()
        else if (compose.onAllNodesWithTag("tool_$name").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("toolbar_draw").performClick()
        compose.onNodeWithTag("tool_$name").performScrollTo().performClick()
        if (compose.onAllNodesWithTag("tools_collapse").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("tools_collapse").performClick()
        compose.waitForIdle()
    }

    private fun touch(x: Float, y: Float) {
        compose.onNodeWithTag("canvas_surface").performTouchInput { click(Offset(width * x, height * y)) }
        compose.waitForIdle()
    }
    private fun confirm() { compose.onNodeWithTag("precision_place").assertIsEnabled().performClick(); compose.waitForIdle() }
    private fun disableSnaps() {
        val initial = document()
        reopen(initial.copy(project = initial.project.copy(settings = initial.project.settings.copy(
            snap = initial.project.settings.snap.copy(enabled = false)))))
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
    private fun awaitElements(count: Int) { compose.waitUntil(10_000) { model.state.value.document?.elements?.size == count }; compose.waitForIdle() }
    private fun assertElements(count: Int) { compose.runOnIdle { assertEquals(count, model.state.value.document?.elements?.size) } }
    private fun document(): ProjectDocument = compose.runOnIdle { checkNotNull(model.state.value.document) }
    private fun singleElement(): Element = document().elements.single()
    private fun frame(): CanvasFrame = compose.runOnIdle { checkNotNull(rendered.get()) }
    private fun canvasSize(): Point2 = compose.onNodeWithTag("canvas_surface").fetchSemanticsNode().boundsInRoot.let { Point2(it.width.toDouble(), it.height.toDouble()) }
    private fun finger(x: Float, y: Float): Point2 = canvasSize().let { Point2(it.x * x, it.y * y) }
    private fun expectedPlanPoint(finger: Point2, frame: CanvasFrame, size: Point2): Point3 {
        val settings = compose.runOnIdle { model.state.value.settings.precisionPointer }
        val screen = offsetCrosshair(finger, size.x, size.y, context.resources.displayMetrics.density.toDouble(), settings)
        return frame.viewport.screenToWorld(screen.x, screen.y, size.x, size.y).let { Point3(it.x, it.y, frame.floorElevation) }
    }
    private fun assertPoint(expected: Point3, actual: Point3) {
        assertEquals(expected.x, actual.x, 1e-6); assertEquals(expected.y, actual.y, 1e-6); assertEquals(expected.z, actual.z, 1e-6)
    }
}
