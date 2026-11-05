package com.eezydraw.ui.canvas.panels

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.eezydraw.core.model.CanvasPanelId
import com.eezydraw.core.model.PanelPlacement
import com.eezydraw.core.model.PanelVisibility
import com.eezydraw.ui.theme.EezyDrawTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class PersistentPanelTest {
    @get:Rule val compose=createComposeRule()

    @Test fun contentChangesNeverPublishOrAlterTheUserPlacementAndOnlyControlsChangeState() {
        val original=PanelPlacement(PanelVisibility.OPEN,.2,.15)
        var placement by mutableStateOf(original)
        var selected by mutableStateOf(false)
        val edits=mutableListOf<PanelPlacement>()
        compose.setContent {EezyDrawTheme {Box(Modifier.size(300.dp,420.dp)) {
            PersistentPanel(CanvasPanelId.LIBRARY,"Library",placement,{edits+=it;placement=it},width=220.dp) {
                Text(if(selected)"Selected object" else "No selection",Modifier.size(180.dp,if(selected)180.dp else 48.dp).testTag("panel_content"))
            }
        }}}
        compose.onNodeWithTag("panel_library").assertIsDisplayed()
        val anchor=compose.onNodeWithTag("panel_library").fetchSemanticsNode().boundsInRoot.topLeft
        compose.runOnIdle {selected=true}
        compose.onNodeWithTag("panel_content").assertIsDisplayed()
        assertEquals("Growing the selected-object contents must not move the panel",anchor,
            compose.onNodeWithTag("panel_library").fetchSemanticsNode().boundsInRoot.topLeft)
        assertEquals(original,placement)
        assertTrue(edits.isEmpty())
        compose.onNodeWithTag("panel_library_collapse").assertWidthIsAtLeast(48.dp).performClick()
        assertEquals(original.copy(state=PanelVisibility.COLLAPSED),placement)
        compose.onNodeWithTag("panel_content").assertDoesNotExist()
        compose.onNodeWithTag("panel_library_expand").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(original,placement)
        compose.onNodeWithTag("panel_library_hide").performClick()
        compose.onNodeWithTag("panel_library").assertDoesNotExist()
        assertEquals(3,edits.size)
    }

    @Test fun draggingPublishesOneClampedPlacementOnlyAfterRelease() {
        var placement by mutableStateOf(PanelPlacement(PanelVisibility.OPEN,.1,.1))
        val edits=mutableListOf<PanelPlacement>()
        compose.setContent {EezyDrawTheme {Box(Modifier.size(300.dp,420.dp).testTag("panel_host")) {
            PersistentPanel(CanvasPanelId.PROPERTIES,"Properties",placement,{edits+=it;placement=it},width=180.dp) {
                Text("Properties",Modifier.size(160.dp,48.dp))
            }
        }}}
        compose.onNodeWithTag("panel_properties_drag").performTouchInput {
            down(center)
            moveTo(center+Offset(52f,48f),delayMillis=48)
        }
        compose.runOnIdle {assertTrue("A finger move is not a persistence write",edits.isEmpty())}
        compose.onNodeWithTag("panel_properties_drag").performTouchInput {up()}
        assertEquals(1,edits.size)
        assertTrue(placement.x>.1 && placement.y>.1)
        compose.onNodeWithTag("panel_properties_drag").performTouchInput {
            down(center)
            moveTo(center+Offset(900f,900f),delayMillis=64)
            up()
        }
        assertEquals(2,edits.size)
        val panel=compose.onNodeWithTag("panel_properties").fetchSemanticsNode().boundsInRoot
        val host=compose.onNodeWithTag("panel_host").fetchSemanticsNode().boundsInRoot
        assertTrue(panel.right<=host.right+1 && panel.bottom<=host.bottom+1)
        assertEquals(((panel.left-host.left)/host.width).toDouble(),placement.x,.005)
        assertEquals(((panel.top-host.top)/host.height).toDouble(),placement.y,.005)
    }

    @Test fun aCollapsedPanelCanBeMovedWithoutOpeningAndRestoresAtTheSavedPosition() {
        var placement by mutableStateOf(PanelPlacement(PanelVisibility.COLLAPSED,.2,.2))
        val restoration=StateRestorationTester(compose)
        val edits=mutableListOf<PanelPlacement>()
        restoration.setContent {EezyDrawTheme {Box(Modifier.size(300.dp,420.dp)) {
            PersistentPanel(CanvasPanelId.DRAW,"Draw",placement,{edits+=it;placement=it}) {
                Text("Draw body",Modifier.testTag("draw_body"))
            }
        }}}
        compose.onNodeWithTag("tools_expand").performTouchInput {
            down(center);moveTo(center+Offset(50f,44f),delayMillis=48);up()
        }
        assertEquals(1,edits.size)
        assertEquals(PanelVisibility.COLLAPSED,placement.state)
        compose.onNodeWithTag("draw_body").assertDoesNotExist()
        val saved=placement
        val before=compose.onNodeWithTag("panel_draw").fetchSemanticsNode().boundsInRoot
        restoration.emulateSavedInstanceStateRestore()
        assertEquals(saved,placement)
        assertEquals(before,compose.onNodeWithTag("panel_draw").fetchSemanticsNode().boundsInRoot)
        assertEquals("Restoration must not dispatch a layout edit",1,edits.size)
    }

    @Test fun launcherTogglesOnlyItsOwnPanelAndToolChangesNeverChangePanelStates() {
        val initial=mapOf(CanvasPanelId.DRAW to PanelPlacement(PanelVisibility.OPEN),
            CanvasPanelId.EDIT to PanelPlacement(PanelVisibility.OPEN))
        var panels by mutableStateOf(initial)
        var active by mutableStateOf<String?>(null)
        val changes=mutableListOf<Pair<CanvasPanelId,PanelVisibility>>()
        compose.setContent {EezyDrawTheme {
            CanvasToolbars({},{},{},{},{},{},{},{},false,activeTool=active,panelStates=panels,
                onPanelChange={id,state->changes+=id to state;panels=panels+(id to panels.getValue(id).copy(state=state))},
                showPanelToggle=false)
        }}
        compose.runOnIdle {active="Rectangle"}
        assertEquals(initial,panels)
        assertTrue(changes.isEmpty())
        compose.onNodeWithTag("toolbar_draw").performClick()
        assertEquals(PanelVisibility.COLLAPSED,panels.getValue(CanvasPanelId.DRAW).state)
        assertEquals(PanelVisibility.OPEN,panels.getValue(CanvasPanelId.EDIT).state)
        assertEquals(listOf(CanvasPanelId.DRAW to PanelVisibility.COLLAPSED),changes)
        compose.onNodeWithTag("canvas_hide_panels").assertDoesNotExist()
    }

    @Test fun choosingAMeasureToolNeverCollapsesItsPersistentPanel() {
        val placement=PanelPlacement(PanelVisibility.OPEN,.1,.1)
        var distancePicks by mutableStateOf(0)
        val changes=mutableListOf<PanelPlacement>()
        compose.setContent {EezyDrawTheme {Box(Modifier.size(300.dp,420.dp)) {
            PersistentPanel(CanvasPanelId.MEASURE,"Measure",placement,changes::add) {
                MeasureTools({distancePicks++},{},{})
            }
        }}}
        compose.onNodeWithTag("toolbar_measure_distance").performClick()
        assertEquals(1,distancePicks)
        assertTrue(changes.isEmpty())
        compose.onNodeWithTag("panel_measure_collapse").assertIsDisplayed()
        compose.onNodeWithTag("toolbar_reference_dimension").assertIsDisplayed()
    }
}
