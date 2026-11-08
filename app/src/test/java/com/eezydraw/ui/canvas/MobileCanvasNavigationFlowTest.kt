package com.eezydraw.ui.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eezydraw.core.geometry.*
import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28],qualifiers="w1024dp-h768dp") @LooperMode(LooperMode.Mode.PAUSED)
class MobileCanvasNavigationFlowTest {
    @get:Rule val compose=createComposeRule()
    private val p=ProjectId("mobile");private val l=LayerId("base")
    private var document by mutableStateOf(ProjectDocument(Project(p,"Mobile CAD",0,0),layers=listOf(Layer(l,p,"Base")),elements=listOf(Element(ElementId("box"),p,l,ElementBody.DrawingShape(Shape.Box(Size3(2.0,3.0,4.0)))))))
    private var settings by mutableStateOf(AppSettings())
    private var dirty by mutableStateOf(false)
    private var selected by mutableStateOf(emptySet<ElementId>())
    private val frame=AtomicReference<CanvasFrame>();private val scene=AtomicReference<DrawingScene?>()
    private var exits=0;private var discarded=0;private var saves=0
    private fun show(){compose.setContent {MaterialTheme {DrawingCanvasScreen(document,settings,dirty,false,{},{},{saves++;dirty=false},{},{},{exits++},
        editor=CanvasEditorActions(selectedIds=selected,onAddSketch={_,_->false},onSelect={selected=it},onTransform={false},onProperties={},onCopy={},onPaste={},onDelete={},onUndo={},onRedo={},onMirror={_,_->},onArray={_,_,_,_->},
            onCanvasLayout={layout->document=document.copy(project=document.project.copy(settings=document.project.settings.copy(canvasLayout=layout)))}),
        onDiscardExit={discarded++},onNavigationSettings={settings=it},onSaveCameraView={view->document=document.copy(views=document.views+view)},onDeleteCameraView={id->document=document.copy(views=document.views.filterNot {it.id==id})},
        surface={value,f,modifier->SideEffect {frame.set(f);scene.set(value)};Box(modifier)})}};compose.waitUntil(10000){compose.onAllNodesWithTag("canvas_ready").fetchSemanticsNodes().isNotEmpty()};compose.waitForIdle()}
    private fun nav(tag:String){compose.cadGroup("navigate");compose.onNodeWithTag(tag).performScrollTo().performClick();compose.waitForIdle()}
    @Test fun fullSurfaceEmptyTapDoesNotCloseStripAndPinchWorksDuringDrawing() {
        show();val surface=compose.onNodeWithTag("canvas_surface").fetchSemanticsNode().boundsInRoot
        val root=compose.onNodeWithTag("drawing_canvas").fetchSemanticsNode().boundsInRoot
        assertEquals(root,surface)
        compose.cadGroup("draw");compose.cadTap(.5f,.5f);assertEquals(CanvasGroup.DRAW,document.project.settings.canvasLayout.openGroup)
        compose.cadTool("line");val before=frame.get().viewport
        val anchor=compose.cadPoint(.5f,.5f)
        compose.onNodeWithTag("canvas_surface").performTouchInput {pinch(anchor-Offset(30f,0f),anchor-Offset(60f,0f),anchor+Offset(30f,0f),anchor+Offset(60f,0f),240)}
        compose.waitForIdle()
        assertTrue(frame.get().viewport.pixelsPerMetre>before.pixelsPerMetre*1.5);assertEquals(1,document.elements.size)
        compose.onNodeWithTag("precision_cancel").performClick()
        compose.runOnIdle {selected=setOf(document.elements.single().id)}
        compose.waitUntil(10000){compose.onAllNodesWithTag("selection_gizmo").fetchSemanticsNodes().isNotEmpty()}
        nav("navigate_pan");compose.onNodeWithTag("navigate_pan").assertIsSelected()
        compose.onNodeWithTag("selection_gizmo").assertDoesNotExist()
        val prior=frame.get().viewport.center
        compose.onNodeWithTag("canvas_surface").performTouchInput {swipe(center,center+Offset(60f,20f),200)}
        compose.waitForIdle()
        assertNotEquals(prior,frame.get().viewport.center)
    }
    @Test fun planRotationNamedViewsAndSectionFacesRestoreAndUpdateClippedScene() {
        show();nav("navigate_rotate_plan")
        compose.onNodeWithTag("plan_rotation_angle").performTextReplacement("37.5");compose.onNodeWithTag("plan_rotation_apply").performClick()
        compose.waitForIdle()
        assertEquals(37.5*PI/180,frame.get().viewport.rotation,1e-10)
        nav("navigate_saved_views");compose.onNodeWithTag("camera_view_name").performTextInput("Rotated plan");compose.onNodeWithTag("camera_view_save").performClick();compose.waitForIdle()
        assertEquals(1,document.views.size);assertEquals(frame.get().viewport.rotation,document.views.single().capture!!.planRotation,0.0)
        compose.cadCloseDetail();nav("navigate_reset_north");assertEquals(0.0,frame.get().viewport.rotation,0.0)
        nav("navigate_saved_views");compose.onNodeWithText("Rotated plan").performClick();compose.waitForIdle();assertEquals(37.5*PI/180,frame.get().viewport.rotation,1e-10)
        compose.cadCloseDetail();nav("navigate_section_box")
        compose.onNodeWithText("X maximum (m)").performScrollTo().performTextReplacement("0.5")
        compose.waitUntil(10000){frame.get().sectionBox?.max?.x==.5 && scene.get()?.segments?.let {it.isNotEmpty() && it.all {s->s.a.x<=.500001 && s.b.x<=.500001}}==true}
        assertEquals(1,document.elements.size)
        compose.onNodeWithText("Disable section box").performScrollTo().performClick();compose.waitUntil(10000){frame.get().sectionBox==null}
        nav("navigate_saved_views");compose.onNodeWithTag("camera_view_delete_${document.views.single().id.value}").performClick();assertTrue(document.views.isEmpty())
    }
    @Test fun dirtyCloseOffersCancelDiscardAndSaveAndCleanCloseLeavesImmediately() {
        dirty=true;show();compose.onNodeWithTag("canvas_close").performClick();compose.onNodeWithText("Save changes?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick();assertEquals(0,exits)
        compose.onNodeWithTag("canvas_close").performClick();compose.onNodeWithTag("canvas_exit_discard").performClick();assertEquals(1,discarded)
        compose.onNodeWithTag("canvas_close").performClick();compose.onNodeWithTag("canvas_exit_save").performClick();compose.waitForIdle();assertEquals("Save callback",1,saves);assertEquals("Exit after successful save",1,exits)
        compose.onNodeWithTag("canvas_close").performClick();assertEquals(2,exits)
    }
    @Test fun settingsInvertOrbitHideAxesAndWalkMovesTheEye() {
        show();compose.cadGroup("settings");compose.onNodeWithTag("navigation_settings").performScrollTo().performClick()
        compose.onAllNodes(isToggleable())[0].performClick();compose.onAllNodes(isToggleable())[2].performClick()
        compose.onNodeWithTag("navigation_settings_save").performClick();assertTrue(settings.invertOrbit);assertFalse(settings.showAxisIndicator)
        compose.onNodeWithTag("canvas_axes").assertDoesNotExist()
        nav("navigate_orbit");val yaw=frame.get().orbit.yaw
        compose.onNodeWithTag("canvas_surface").performTouchInput {swipe(center,center+Offset(70f,0f),200)}
        compose.waitForIdle()
        assertTrue("Invert yaw: $yaw -> ${frame.get().orbit.yaw}",frame.get().orbit.yaw>yaw)
        nav("navigate_walk");assertEquals(Projection.PERSPECTIVE,frame.get().orbit.projection)
        val eye=frame.get().orbit.eye();compose.onNodeWithTag("walk_joystick").performTouchInput {swipe(center,center+Offset(15f,-30f),200)}
        compose.waitForIdle()
        val moved=frame.get().orbit.eye();assertNotEquals(eye,moved);assertEquals(eye.z,moved.z,1e-8)
    }
}
