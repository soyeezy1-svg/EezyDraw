package com.eezydraw.ui.canvas

import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.eezydraw.R
import com.eezydraw.core.editing.EditDelta
import com.eezydraw.core.model.*
import com.eezydraw.ui.AppViewModel
import com.eezydraw.ui.EezyDrawApp
import com.eezydraw.ui.TestAppRepositories
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real canvas, menu and ViewModel commands; only the EGL surface is substituted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], qualifiers="w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class PersistentPresentationFlowTest {
    @get:Rule val compose=createComposeRule()
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private lateinit var repositories:TestAppRepositories
    private lateinit var model:AppViewModel
    private val frame=AtomicReference<CanvasFrame>()

    @Before fun prepare() {
        repositories=TestAppRepositories(context,AppSettings(autosaveIntervalSeconds=0,
            projectDefaults=ProjectSettings(snap=SnapSettings(enabled=false)),
            precisionPointer=PrecisionPointerSettings(enabled=false)))
        model=repositories.newViewModel()
    }
    @After fun close() {repositories.close()}

    @OptIn(ExperimentalTestApi::class)
    @Test fun selectionMovingPlacingAndMeasuringNeverRewriteTheUserPanelLayout() {
        startProject("Panels remain where placed")
        compose.runOnIdle {assertFalse(model.state.value.settings.autoHidePanelsWhileDrawing)}
        compose.cadGroup("draw")
        compose.onNodeWithTag("tool_box").performScrollTo().performClick()
        assertEquals(CanvasGroup.DRAW,layout().openGroup)
        compose.cadCloseStrip("draw")
        collapseView()
        val before=layout()
        tapCanvas(.40f,.50f)
        awaitElements(1)
        val box=document().elements.single()
        assertEquals("Completing placement must not reopen or close panels",before,layout())
        compose.runOnIdle {model.select(emptySet())}
        assertEquals(before,layout())
        compose.runOnIdle {model.select(setOf(box.id))}
        awaitSelected(box.id)
        assertEquals(before,layout())
        compose.onNodeWithTag("selection_gizmo").performCustomAccessibilityActionWithLabel(context.getString(R.string.gizmo_move_x))
        compose.waitUntil(10_000) {model.state.value.document?.elements?.single()?.transform!=box.transform}
        assertEquals("A completed move must retain each state and position",before,layout())
        compose.cadGroup("measure")
        compose.onNodeWithTag("canvas_measure").performClick()
        assertEquals("Choosing Measure keeps its strip open",CanvasGroup.MEASURE,layout().openGroup)
        compose.cadCloseStrip("measure")
        val measureLayout=layout()
        tapCanvas(.40f,.50f)
        assertEquals(measureLayout,layout())
        tapCanvas(.57f,.60f)
        awaitElements(2)
        assertEquals("Completing a measurement must retain the user's layout",measureLayout,layout())
    }

    @Test fun propertiesStayOpenForEmptySelectionAndFullscreenRestoresExactlyTheSamePanels() {
        startProject("Explicit panel visibility")
        val box=placeBox()
        compose.cadSelection();compose.onNodeWithTag("selection_properties").performClick()
        assertEquals(CanvasGroup.EDIT,layout().openGroup)
        compose.runOnIdle {model.select(emptySet())}
        compose.onNodeWithTag("cad_detail_close").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.presentation_select_properties)).assertIsDisplayed()
        val before=layout()
        compose.onNodeWithTag("canvas_hide_panels").performClick()
        assertTrue(layout().hideAll)
        assertEquals(before.panels,layout().panels)
        compose.onNodeWithTag("cad_detail_close").assertDoesNotExist()
        compose.onNodeWithTag("canvas_show_panels").assertIsDisplayed()
        compose.runOnIdle {model.select(setOf(box.id))}
        assertTrue("Selection cannot bring hidden panels back",layout().hideAll)
        compose.onNodeWithTag("cad_detail_close").assertDoesNotExist()
        compose.onNodeWithTag("canvas_show_panels").performClick()
        assertEquals(before,layout())
        compose.onNodeWithTag("cad_detail_close").assertIsDisplayed()
        compose.onNodeWithTag("properties_side_panel").assertIsDisplayed()
    }

    @Test fun panelPositionsSurviveReopeningAndANewViewModelWithAutosaveDisabled() {
        startProject("Durable layout")
        val id=document().project.id.value
        val custom=layout().withPanel(CanvasPanelId.DRAW,PanelPlacement(PanelVisibility.COLLAPSED,.73,.61))
            .withPanel(CanvasPanelId.VISIBILITY,PanelPlacement(PanelVisibility.OPEN,.18,.46)).copy(hideAll=true,openGroup=CanvasGroup.NAVIGATE,bottomBarVisible=false)
        compose.runOnIdle {model.setCanvasLayout(custom)}
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        compose.waitUntil(5_000) {repositories.settings.settings.value.canvasLayouts[id]==custom}
        compose.runOnIdle {
            assertEquals(0,model.state.value.settings.autosaveIntervalSeconds)
            assertTrue("Layout edits must not add geometry undo records",!model.state.value.canUndo)
            assertTrue("The panel cache does not force a document save",repositories.projects.savedSnapshots.isEmpty())
            model.open(id)
        }
        compose.waitUntil(10_000) {!model.state.value.busy && model.state.value.document?.project?.settings?.canvasLayout==custom}
        val restored=compose.runOnIdle {repositories.newViewModel().also {it.open(id)}}
        compose.waitUntil(10_000) {!restored.state.value.busy && restored.state.value.document!=null}
        compose.runOnIdle {assertEquals(custom,restored.state.value.document?.project?.settings?.canvasLayout)}
    }

    @Test fun moreLockProtectsGeometryShowsReadOnlyPropertiesAndHasAnUndoAction() {
        startProject("Lock controls")
        val box=placeBox()
        more("lock")
        compose.waitUntil(10_000) {model.state.value.document?.elements?.single()?.lockMode==LockMode.ALL}
        compose.onNodeWithTag("selection_padlock").assertIsDisplayed()
        compose.onNodeWithTag("selection_delete").assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.editing_undo)).performClick()
        compose.waitUntil(10_000) {model.state.value.document?.elements?.single()?.lockMode==LockMode.NONE}
        more("lock")
        compose.runOnIdle {
            val locked=checkNotNull(model.state.value.document).elements.single()
            assertFalse(model.transformSelection(EditDelta(translation=Point3(1.0,0.0,0.0))))
            assertEquals(locked,checkNotNull(model.state.value.document).elements.single())
            model.clearError();model.clearNotice()
        }
        compose.cadSelection();compose.onNodeWithTag("selection_properties").performClick()
        compose.onNodeWithText("Object is locked. Unlock to edit.").assertIsDisplayed()
        compose.onNodeWithTag("property_x").assertIsNotEnabled()
        compose.cadCloseDetail()
        more("lock")
        compose.waitUntil(10_000) {model.state.value.document?.elements?.single()?.lockMode==LockMode.NONE}
        compose.runOnIdle {
            model.clearNotice()
            assertTrue(model.transformSelection(EditDelta(translation=Point3(1.0,0.0,0.0))))
            assertEquals(box.transform.translation.x+1.0,checkNotNull(model.state.value.document).elements.single().transform.translation.x,1e-9)
            model.undo()
            assertEquals(box.transform,checkNotNull(model.state.value.document).elements.single().transform)
            model.undo()
            assertEquals(LockMode.ALL,checkNotNull(model.state.value.document).elements.single().lockMode)
        }
    }

    @Test fun roomSideDisplayAndVisibilityPanelAreUndoableWithoutChangingGeometryOrLayout() {
        startProject("Room wall visibility")
        val base=document()
        val room=Element(ElementId("room"),base.project.id,base.layers.first().id,
            ElementBody.Room(Polygon2(listOf(Point2(0.0,0.0),Point2(2.0,0.0),Point2(2.0,2.0),Point2(0.0,2.0)))),name="Office")
        reopen(base.copy(elements=listOf(room)))
        collapseView()
        compose.runOnIdle {model.select(setOf(room.id))}
        awaitSelected(room.id)
        more("room_wall")
        compose.onNodeWithTag("selection_room_side_outer_0").performScrollTo().performClick()
        more("hide_wall")
        val side=RoomSideRef(room.id,0)
        compose.waitUntil(10_000) {model.state.value.document?.elements?.single()?.roomSideDisplay(side)==ObjectDisplay.HIDDEN}
        compose.runOnIdle {model.clearNotice()}
        val wallHidden=document().elements.single()
        assertEquals(room.body,wallHidden.body)
        val before=layout()
        compose.runOnIdle {model.setSelectionDisplay(ObjectDisplay.HIDDEN);model.clearNotice()}
        compose.runOnIdle {model.select(setOf(room.id));assertTrue(model.state.value.selectedIds.isEmpty())}
        assertEquals(before,layout())
        compose.runOnIdle {model.showHiddenObjects(true);model.select(setOf(room.id))}
        awaitSelected(room.id)
        assertTrue(checkNotNull(frame.get().selectionScene).segments.any {it.display==ObjectDisplay.HIDDEN})
        more("visibility")
        compose.onNodeWithTag("object_visibility_panel").assertIsDisplayed()
        compose.onNodeWithTag("visibility_show_all").performScrollTo().performClick()
        val visible=document().elements.single()
        assertEquals(ObjectDisplay.NORMAL,visible.objectDisplay)
        assertEquals(ObjectDisplay.NORMAL,visible.roomSideDisplay(side))
        assertEquals(room.body,visible.body)
        val visiblePanelLayout=layout()
        compose.runOnIdle {model.undo()}
        assertEquals(ObjectDisplay.HIDDEN,document().elements.single().objectDisplay)
        assertEquals(ObjectDisplay.HIDDEN,document().elements.single().roomSideDisplay(side))
        assertEquals("Visibility undo must preserve the panel opened by the user",visiblePanelLayout,layout())
    }

    private fun startProject(name:String) {
        compose.mainClock.autoAdvance=false
        compose.setContent {EezyDrawApp(model,canvasSurface={_,value,modifier->SideEffect {frame.set(value)};Box(modifier)})}
        compose.mainClock.advanceTimeByFrame();compose.mainClock.advanceTimeBy(3_000)
        compose.mainClock.autoAdvance=true
        compose.onNodeWithText(context.getString(R.string.create_new_project)).performScrollTo().performClick()
        compose.onNodeWithTag("project_name").performTextInput(name)
        compose.onNodeWithTag("confirm_create_project").performScrollTo().performClick()
        ready()
    }
    private fun chooseBox() {
        compose.cadTool("box")
    }
    private fun placeBox():Element {
        chooseBox();tapCanvas(.40f,.50f);awaitElements(1)
        return document().elements.single().also {awaitSelected(it.id)}
    }
    private fun collapseView() {
        compose.cadCloseStrip("view")
    }
    private fun tapCanvas(x:Float,y:Float) {
        compose.cadTap(x, y)
        compose.waitForIdle()
    }
    private fun more(action:String) {
        compose.cadSelection()
        compose.cadSelection();compose.onNodeWithTag("selection_more").performClick()
        compose.onNodeWithTag("selection_$action").performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
    }
    private fun reopen(doc:ProjectDocument) {
        compose.runOnIdle {repositories.projects.documents[doc.project.id.value]=doc;model.open(doc.project.id.value)}
        compose.waitUntil(10_000) {!model.state.value.busy && model.state.value.document?.elements==doc.elements}
        ready()
    }
    private fun ready() {
        compose.waitUntil(10_000) {compose.onAllNodesWithTag("canvas_ready").fetchSemanticsNodes().isNotEmpty()}
        compose.waitForIdle()
    }
    private fun awaitSelected(id:ElementId) {
        compose.waitUntil(10_000) {frame.get()?.let {it.selectionDelta==null && it.selectionScene?.segments?.any {edge->edge.elementId==id.value}==true}==true}
        compose.waitForIdle()
    }
    private fun awaitElements(count:Int) {
        compose.waitUntil(10_000) {model.state.value.document?.elements?.size==count};compose.waitForIdle()
    }
    private fun document():ProjectDocument=compose.runOnIdle {checkNotNull(model.state.value.document)}
    private fun layout():CanvasLayout=document().project.settings.canvasLayout
    private fun layoutWithoutProperties(layout:CanvasLayout)=layout.withPanel(CanvasPanelId.PROPERTIES,
        layout.panel(CanvasPanelId.PROPERTIES).copy(state=PanelVisibility.HIDDEN))
}
