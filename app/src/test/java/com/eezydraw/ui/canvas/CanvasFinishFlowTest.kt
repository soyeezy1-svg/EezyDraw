package com.eezydraw.ui.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class CanvasFinishFlowTest {
    @get:Rule val compose=createComposeRule()
    private val p=ProjectId("finish");private val layer=LayerId("base");private val room=ElementId("room")
    private fun fixture()=ProjectDocument(Project(p,"Finish",0,0),layers=listOf(Layer(layer,p,"Base")),levels=listOf(Level(LevelId("ground"),p,"Ground",0.0,.2,3.0)),
        elements=listOf(Element(room,p,layer,ElementBody.Room(Polygon2(listOf(Point2(0.0,0.0),Point2(4.0,0.0),Point2(4.0,3.0),Point2(0.0,3.0))),"Room",3.0))))
    private var document by mutableStateOf(fixture())
    private var generation by mutableIntStateOf(0)
    private var selected by mutableStateOf(setOf(room))
    private var settings by mutableStateOf(AppSettings())
    private fun show(){compose.setContent {key(generation){MaterialTheme {DrawingCanvasScreen(document,settings,true,false,{},{},{},{},{},{},
        editor=CanvasEditorActions(selectedIds=selected,canUndo=true,canRedo=true,canPaste=true,onAddSketch={_,_->false},onSelect={},onTransform={false},onProperties={},onCopy={},onPaste={},onDelete={},onUndo={},onRedo={},onMirror={_,_->},onArray={_,_,_,_->},
            onCanvasLayout={layout->document=document.copy(project=document.project.copy(settings=document.project.settings.copy(canvasLayout=layout)))}),
        surface={_,_,modifier->Box(modifier)})}}};ready()}
    private fun ready(){compose.waitUntil(10000){compose.onAllNodesWithTag("canvas_ready").fetchSemanticsNodes().isNotEmpty()};compose.waitForIdle()}
    private fun reset(){compose.runOnIdle {document=fixture();selected=setOf(room);generation++};ready()}

    @Test fun standaloneSheetReceivesHeaderDragsAndButtonTaps() {
        var closed=false
        compose.setContent {MaterialTheme {Box(Modifier.fillMaxSize(),contentAlignment=Alignment.BottomCenter){ResizableCanvasPanel("standalone","Panel",{closed=true},600.dp) {androidx.compose.material3.Button({closed=true},Modifier.testTag("inside")){androidx.compose.material3.Text("Inside")}}}}}
        val old=compose.onNodeWithTag("cad_detail_sheet").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("cad_detail_drag").performTouchInput {swipe(center,center-Offset(0f,100f),240)}
        compose.waitForIdle()
        val after=compose.onNodeWithTag("cad_detail_sheet").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Standalone resize $old → $after",after>old+50)
        compose.onNodeWithTag("inside").performClick();compose.runOnIdle {assertTrue("Content button",closed);closed=false}
        compose.onNodeWithTag("cad_detail_close").performClick();compose.runOnIdle {assertTrue("Standalone close",closed)}
    }

    @Test fun everyGroupAndToolHasADirectEntryWithinTwoTaps() {
        show();val visited=linkedSetOf<String>()
        CanvasGroup.entries.forEach {group->
            reset();val name=group.name.lowercase();compose.cadGroup(name)
            val tags=compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("cad_substrip_$name"))).fetchSemanticsNodes()
                .mapNotNull {it.config.getOrNull(SemanticsProperties.TestTag)}.filter {it.isNotBlank()}.distinct()
            assertTrue("$group must expose its tools",tags.isNotEmpty())
            tags.forEach {tag->
                reset();compose.cadGroup(name)
                val tool=compose.onNodeWithTag(tag).performScrollTo()
                if(tool.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled)==null){
                    try {tool.assertIsDisplayed().performClick();compose.waitForIdle()}catch(error:Throwable){throw AssertionError("$group → $tag was unreachable",error)}
                    visited+=tag
                } else {
                    // Clear reference needs a user-established reference; all other entries are enabled by this fixture.
                    assertEquals("Unexpected unavailable tool in the reachability fixture","canvas_clear_reference",tag)
                }
            }
        }
        assertTrue("Every drawing, routing and review entry is exercised: ${visited.size}",visited.size>=100)
        // Establish a reference through the public probe, then exercise its context-dependent Clear action.
        reset();compose.cadGroup("settings");compose.onNodeWithTag("canvas_probe").performScrollTo().performClick()
        compose.cadCloseStrip("settings");compose.cadTap(.65f,.6f)
        compose.cadGroup("settings");compose.onNodeWithTag("canvas_clear_reference").performScrollTo().assertIsEnabled().performClick()
    }

    @Test fun sheetsOnlyOpenOnRequestAndCanResizeCloseAndReopen() {
        show();compose.onNodeWithTag("cad_detail_sheet").assertDoesNotExist()
        compose.onNodeWithTag("selection_toolbar").assertIsDisplayed()
        compose.cadGroup("layers");compose.onNodeWithTag("cad_detail_sheet").assertIsDisplayed()
        val sheet=compose.onNodeWithTag("cad_detail_sheet").fetchSemanticsNode().boundsInRoot
        val old=sheet.height
        assertTrue("Visibility content fills its sheet",compose.onNodeWithTag("object_visibility_panel").fetchSemanticsNode().boundsInRoot.width>sheet.width*.9f)
        compose.onNodeWithTag("cad_detail_drag").performTouchInput {swipe(center,center-Offset(0f,100f),240)}
        compose.waitForIdle();val expanded=compose.onNodeWithTag("cad_detail_sheet").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Drag must expand the sheet: $old → $expanded",expanded>old+50)
        compose.onNodeWithTag("cad_detail_close").performClick()
        compose.runOnIdle {selected=emptySet()};compose.cadTap(.6f,.5f)
        compose.onNodeWithTag("cad_detail_sheet").assertDoesNotExist()
        compose.onNodeWithTag("canvas_visibility").performScrollTo().performClick()
        compose.onNodeWithTag("cad_detail_drag").performTouchInput {swipe(center,center+Offset(0f,300f),300)}
        compose.waitForIdle();compose.onNodeWithTag("cad_detail_sheet").assertDoesNotExist()
        compose.onNodeWithTag("canvas_visibility").performClick();compose.onNodeWithTag("cad_detail_sheet").assertIsDisplayed()
    }

    @Test fun leftRailAndHiddenLabelsKeepActionsTooltipsAndFullScreenAccessible() {
        settings=AppSettings(barPosition=CanvasBarPosition.LEFT,bottomBarLabels=false);show()
        compose.onNodeWithTag("cad_left_bar").assertIsDisplayed();compose.onNodeWithTag("cad_bottom_bar").assertDoesNotExist()
        compose.cadGroup("navigate");compose.onNodeWithTag("navigate_pan").performScrollTo().assertContentDescriptionEquals("Pan")
        compose.onNodeWithTag("navigate_pan").performTouchInput {longClick()}
        compose.onNodeWithText("Pan").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(2000);compose.waitForIdle()
        compose.onNodeWithTag("canvas_hide_panels").performClick();compose.onNodeWithTag("cad_left_bar").assertDoesNotExist()
        compose.onNodeWithTag("canvas_show_panels").performClick();compose.onNodeWithTag("cad_left_bar").assertIsDisplayed()
        assertEquals(CanvasGroup.NAVIGATE,document.project.settings.canvasLayout.openGroup)
    }
}
