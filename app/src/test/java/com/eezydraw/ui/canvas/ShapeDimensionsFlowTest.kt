package com.eezydraw.ui.canvas

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import com.eezydraw.core.editing.ElementProperties
import com.eezydraw.core.editing.elementWorldBounds
import com.eezydraw.core.editing.readProperties
import com.eezydraw.core.editing.resizeFrame
import com.eezydraw.core.model.AppSettings
import com.eezydraw.core.model.PrecisionPointerSettings
import com.eezydraw.core.model.Element
import com.eezydraw.core.model.ElementBody
import com.eezydraw.core.model.LengthUnit
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.Shape
import com.eezydraw.ui.AppViewModel
import com.eezydraw.ui.EezyDrawApp
import com.eezydraw.ui.TestAppRepositories
import com.eezydraw.ui.canvas.editing.GizmoHandle
import com.eezydraw.ui.canvas.editing.GizmoMode
import com.eezydraw.ui.canvas.editing.gizmoHandles
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
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

/** Actual placement/resize gestures and Properties, with only the native EGL surface replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class ShapeDimensionsFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var repositories: TestAppRepositories
    private lateinit var model: AppViewModel
    private val renderedFrame = AtomicReference<CanvasFrame>()

    @Before fun prepare() {
        // These regressions exercise the optional direct-tap workflow.
        repositories = TestAppRepositories(context, AppSettings(autosaveIntervalSeconds = 0,
            precisionPointer = PrecisionPointerSettings(enabled = false)))
        model = repositories.newViewModel()
    }

    @After fun close() { repositories.close() }

    @Test
    fun everySizedToolPlacesOneMetreDefaultsInMetricAndImperialProjectsWithoutPixelDimensions() {
        createEmptyProject("Proportioned defaults")
        val tools = listOf("box", "sphere", "cylinder", "cone", "pyramid",
            "rectangle", "circle", "ellipse", "polygon", "triangle")
        val units = listOf(LengthUnit.MILLIMETRES, LengthUnit.METRES, LengthUnit.FEET,
            LengthUnit.INCHES, LengthUnit.CENTIMETRES)
        val solids = setOf("box", "sphere", "cylinder", "cone", "pyramid")
        tools.forEachIndexed { index, tool ->
            compose.runOnIdle { model.changeUnits(units[index % units.size]) }
            chooseTool(tool)
            // Deliberately vary the screen location. Placement pixels must never become dimensions.
            compose.cadTap((.36f + .025f * index), .62f)
            awaitElements(index + 1)
            val placed = compose.runOnIdle { checkNotNull(model.state.value.document).elements.last() }
            val size = properties(placed).size
            assertSize("$tool in ${units[index % units.size]}", size, 1.0, 1.0, if (tool in solids) 1.0 else 0.0)
            if (tool == "sphere") {
                assertEquals(.5, ((placed.body as ElementBody.DrawingShape).shape as Shape.Sphere).radius, 1e-12)
            }
            awaitRendered(placed)
            val scene = checkNotNull(frame().selectionScene)
            assertSize("$tool rendered world bounds", Point3(scene.bounds.max.x - scene.bounds.min.x,
                scene.bounds.max.y - scene.bounds.min.y, scene.bounds.max.z - scene.bounds.min.z),
                1.0, 1.0, if (tool in solids) 1.0 else 0.0)
        }
        val before = compose.runOnIdle { checkNotNull(model.state.value.document).elements }
        compose.cubeHome()
        assertTrue(frame().is3D)
        compose.runOnIdle {
            assertEquals("Changing the camera must not rescale metres", before, checkNotNull(model.state.value.document).elements)
        }
        compose.cubeFace(context, R.string.canvas_top)
        assertTrue(frame().is3D)
        compose.runOnIdle {
            assertEquals(before, checkNotNull(model.state.value.document).elements)
        }
    }

    @Test
    fun allPlanResizeHandlesAffectTheirOwnDimensionsAndOneUndoRestoresTheCube() {
        createEmptyProject("Independent resize axes")
        placeSingle("box")
        chooseTool("select")
        compose.onNodeWithTag("canvas_extents").assertIsEnabled().performClick()
        val original = singleElement()
        val widthHandles = listOf(GizmoHandle.RESIZE_WIDTH_NEGATIVE, GizmoHandle.RESIZE_WIDTH_POSITIVE)
        val depthHandles = listOf(GizmoHandle.RESIZE_DEPTH_NEGATIVE, GizmoHandle.RESIZE_DEPTH_POSITIVE)
        val corners = listOf(GizmoHandle.RESIZE_CORNER_MIN_MIN, GizmoHandle.RESIZE_CORNER_MAX_MIN,
            GizmoHandle.RESIZE_CORNER_MAX_MAX, GizmoHandle.RESIZE_CORNER_MIN_MAX)
        val changes = mapOf(
            GizmoHandle.RESIZE_WIDTH_NEGATIVE to Offset(35f, 0f),
            GizmoHandle.RESIZE_WIDTH_POSITIVE to Offset(-35f, 0f),
            GizmoHandle.RESIZE_DEPTH_NEGATIVE to Offset(0f, -35f),
            GizmoHandle.RESIZE_DEPTH_POSITIVE to Offset(0f, 35f),
            GizmoHandle.RESIZE_CORNER_MIN_MIN to Offset(35f, -25f),
            GizmoHandle.RESIZE_CORNER_MAX_MIN to Offset(-35f, -25f),
            GizmoHandle.RESIZE_CORNER_MAX_MAX to Offset(-35f, 25f),
            GizmoHandle.RESIZE_CORNER_MIN_MAX to Offset(35f, 25f),
        )
        (widthHandles + depthHandles + corners).forEach { handle ->
            dragHandle(handle, changes.getValue(handle))
            val size = properties(singleElement()).size
            if (handle in widthHandles || handle in corners) assertChanged("$handle width", 1.0, size.x)
            else assertEquals("$handle leaves width", 1.0, size.x, 1e-10)
            if (handle in depthHandles || handle in corners) assertChanged("$handle depth", 1.0, size.y)
            else assertEquals("$handle leaves depth", 1.0, size.y, 1e-10)
            assertEquals("$handle must never change height", 1.0, size.z, 1e-10)
            compose.runOnIdle { model.undo() }
            compose.waitUntil(10_000) { model.state.value.document?.elements?.single() == original }
            awaitRendered(original)
        }
    }

    @Test
    fun topHandleChangesOnlyHeightAndPropertiesEditsTheSamePhysicalValues() {
        createEmptyProject("Top height and Properties")
        placeSingle("box")
        chooseTool("select")
        compose.cubeFace(context, R.string.canvas_front)
        compose.onNodeWithTag("canvas_extents").assertIsEnabled().performClick()
        val original = singleElement()
        val originalBounds = compose.runOnIdle {
            elementWorldBounds(checkNotNull(model.state.value.document), setOf(original.id))
        }
        dragHandle(GizmoHandle.RESIZE_HEIGHT, Offset(0f, 40f))
        val resized = singleElement()
        val physical = properties(resized)
        assertEquals(1.0, physical.size.x, 1e-10)
        assertEquals(1.0, physical.size.y, 1e-10)
        assertChanged("The top handle must change height", 1.0, physical.size.z)
        compose.runOnIdle {
            val bounds = elementWorldBounds(checkNotNull(model.state.value.document), setOf(resized.id))
            assertEquals("Height resize keeps the base fixed", originalBounds.min.z, bounds.min.z, 1e-10)
        }
        toolsAction("properties")
        compose.onNodeWithTag("property_width").performScrollTo().assertTextContains("1000.00")
        compose.onNodeWithTag("property_depth").performScrollTo().assertTextContains("1000.00")
        compose.onNodeWithTag("property_height").performScrollTo()
            .assertTextContains(String.format(Locale.ROOT, "%.2f", physical.size.z * 1000.0))
        compose.onNodeWithTag("property_width").performScrollTo().performTextReplacement("2500")
        compose.onNodeWithTag("properties_apply").performClick()
        compose.onNodeWithTag("properties_side_panel").assertIsDisplayed()
        compose.cadCloseDetail()
        val edited = singleElement()
        val editedSize = properties(edited).size
        assertEquals(2.5, editedSize.x, 1e-10)
        assertEquals(physical.size.y, editedSize.y, 1e-10)
        assertEquals(physical.size.z, editedSize.z, 1e-10)
        collapseTools()
        compose.cadGroup("settings");compose.onNodeWithTag("canvas_save").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { !model.state.value.busy && !model.state.value.dirty }
        val projectId = compose.runOnIdle { checkNotNull(model.state.value.document).project.id.value }
        compose.runOnIdle { model.open(projectId) }
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.selectedIds.isEmpty() }
        assertEquals("Save/reopen retains the same resized model", edited, singleElement())
    }

    @Test
    fun planarCornerProportionLockChangesWidthAndDepthTogetherWithoutAddingHeight() {
        createEmptyProject("Flat proportional resize")
        placeSingle("rectangle")
        chooseTool("select")
        compose.onNodeWithTag("canvas_extents").assertIsEnabled().performClick()
        compose.cadSelection();compose.onNodeWithTag("selection_more").performClick()
        compose.onNodeWithTag("selection_keep_proportions").performScrollTo().performClick()
        dragHandle(GizmoHandle.RESIZE_CORNER_MAX_MAX, Offset(-50f, 25f))
        val rectangle = singleElement()
        val size = properties(rectangle).size
        assertChanged("Corner scales the flat outline", 1.0, size.x)
        assertEquals("Proportion lock preserves width/depth ratio", size.x, size.y, 1e-10)
        assertEquals(0.0, size.z, 0.0)
        compose.cubeHome()
        toolsAction("scale")
        awaitRendered(rectangle)
        assertFalse("Flat geometry cannot gain thickness from a top handle", controls().any { it.handle == GizmoHandle.RESIZE_HEIGHT })
        toolsAction("properties")
        compose.onNodeWithTag("property_height").performScrollTo().assertIsNotEnabled().assertTextContains("0.00")
    }

    private fun createEmptyProject(name: String) {
        compose.mainClock.autoAdvance = false
        compose.setContent { EezyDrawApp(model, canvasSurface = { _, frame, modifier ->
            SideEffect { renderedFrame.set(frame) }
            Box(modifier)
        }) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithTag("start_menu").assertIsDisplayed()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText(context.getString(R.string.create_new_project)).performScrollTo().performClick()
        compose.onNodeWithTag("project_name").performTextInput(name)
        compose.onNodeWithTag("confirm_create_project").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("canvas_ready").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun placeSingle(tool: String) {
        chooseTool(tool)
        compose.cadTap(.43f, .62f)
        awaitElements(1)
        awaitRendered(singleElement())
    }

    private fun chooseTool(tool: String) {
        compose.cadTool(tool)
    }

    private fun toolsAction(action: String) {
        compose.cadAction(action)
    }

    private fun collapseTools() {
        compose.cadCloseStrip("draw")
    }

    private fun dragHandle(handle: GizmoHandle, change: Offset) {
        toolsAction("scale")
        compose.onNodeWithTag("canvas_extents").assertIsEnabled().performClick()
        val before = singleElement()
        awaitRendered(before)
        val control = controls().single { it.handle == handle }
        val start = Offset(control.center.x.toFloat(), control.center.y.toFloat())
        compose.onNodeWithTag("canvas_surface").performTouchInput { swipe(start, start + change, 240) }
        try {compose.waitUntil(10_000) { model.state.value.document?.elements?.single() != before }}catch(error:Throwable){throw AssertionError("Resize $handle at $start by $change; bar=${compose.onNodeWithTag("cad_bottom_controls").fetchSemanticsNode().boundsInRoot}; old=${before.transform}; frame=${frame().viewport}",error)}
        awaitRendered(singleElement())
    }

    private fun controls(): List<com.eezydraw.ui.canvas.editing.GizmoControl> {
        val current = frame()
        val bounds = compose.onNodeWithTag("canvas_surface").fetchSemanticsNode().boundsInRoot
        val localFrame = compose.runOnIdle {
            val document = checkNotNull(model.state.value.document)
            resizeFrame(document, document.elements.single())
        }
        return gizmoHandles(checkNotNull(current.selectionScene).bounds, current.viewport, current.orbit, current.is3D,
            bounds.width.toDouble(), bounds.height.toDouble(), context.resources.displayMetrics.density.toDouble(),
            resizeFrame = localFrame, mode = GizmoMode.SCALE)
    }

    private fun awaitElements(count: Int) {
        compose.waitUntil(10_000) { model.state.value.document?.elements?.size == count }
        compose.waitForIdle()
    }

    private fun awaitRendered(element: Element) {
        val expected = compose.runOnIdle { elementWorldBounds(checkNotNull(model.state.value.document), setOf(element.id)) }
        compose.waitUntil(10_000) {
            renderedFrame.get()?.let {
                val scene = it.selectionScene
                it.selectionDelta == null && scene != null && scene.bounds == expected &&
                    scene.segments.any { segment -> segment.elementId == element.id.value }
            } == true
        }
        compose.waitForIdle()
    }

    private fun singleElement(): Element = compose.runOnIdle { checkNotNull(model.state.value.document).elements.single() }
    private fun properties(element: Element): ElementProperties = compose.runOnIdle { readProperties(checkNotNull(model.state.value.document), element) }
    private fun frame(): CanvasFrame = compose.runOnIdle { checkNotNull(renderedFrame.get()) }
    private fun assertChanged(message: String, before: Double, after: Double) = assertTrue(message, abs(before - after) > 1e-6)
    private fun assertSize(message: String, size: Point3, width: Double, depth: Double, height: Double) {
        assertEquals("$message width", width, size.x, 1e-10)
        assertEquals("$message depth", depth, size.y, 1e-10)
        assertEquals("$message height", height, size.z, 1e-10)
    }
}
