package com.eezydraw.ui.canvas.editing

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.eezydraw.core.editing.ElementProperties
import com.eezydraw.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class PresentationControlsTest {
    @get:Rule val compose=createComposeRule()
    private val project=ProjectId("presentation")
    private val room=Element(ElementId("room"),project,LayerId("base"),ElementBody.Room(
        Polygon2(listOf(Point2(0.0,0.0),Point2(2.0,0.0),Point2(2.0,2.0),Point2(0.0,2.0)))))

    @Test fun moreContainsLockPoliciesLevelActionsAndPerWallDisplay() {
        var lock by mutableStateOf(LockMode.NONE)
        var side by mutableStateOf<RoomSideRef?>(null)
        var display by mutableStateOf(ObjectDisplay.NORMAL)
        var levelMode:LockMode?=null
        var hiddenSide:RoomSideRef?=null
        var shown=false
        compose.setContent {MaterialTheme {Toolbar(lock,side,display,
            {lock=it},{levelMode=it},{side=it},{display=it},{hiddenSide=it},{shown=true})}}
        more("lock_options")
        compose.onNodeWithTag("selection_lock_position").performClick()
        assertEquals(LockMode.POSITION_ONLY,lock)
        more("lock_options")
        compose.onNodeWithTag("selection_lock_everything").performClick()
        assertEquals(LockMode.ALL,lock)
        more("lock_options")
        compose.onNodeWithTag("selection_unlock_all").performScrollTo().performClick()
        assertEquals(LockMode.NONE,levelMode)
        more("room_wall")
        compose.onNodeWithTag("selection_room_side_outer_1").performScrollTo().performClick()
        assertEquals(RoomSideRef(room.id,1),side)
        more("display")
        compose.onNodeWithTag("selection_display_xray").performClick()
        assertEquals(ObjectDisplay.XRAY,display)
        more("hide_wall")
        assertEquals(side,hiddenSide)
        more("show_room_walls")
        assertEquals(true,shown)
    }

    @Test fun panelOptionsReportOnlyExplicitLayoutChanges() {
        var panelChange:Pair<CanvasPanelId,PanelVisibility>?=null
        var hideAll:Boolean?=null
        compose.setContent {MaterialTheme {Toolbar(LockMode.NONE,null,ObjectDisplay.NORMAL,
            onPanel={id,state->panelChange=id to state},onHideAll={hideAll=it})}}
        assertEquals(null,panelChange)
        more("panels")
        compose.onNodeWithTag("selection_panel_properties").performScrollTo().performClick()
        compose.onNodeWithTag("selection_panel_state_collapsed").performClick()
        assertEquals(CanvasPanelId.PROPERTIES to PanelVisibility.COLLAPSED,panelChange)
        more("panels")
        compose.onNodeWithTag("selection_hide_all_panels").performScrollTo().performClick()
        assertEquals(true,hideAll)
    }

    @Test fun lockedPropertiesStayReadableAndPositionOnlyLeavesSizeAndRotationEditable() {
        var lock by mutableStateOf(LockMode.ALL)
        var applied=false
        val properties=ElementProperties(Point3(1.0,2.0,3.0),Rotation3(),Point3(1.0,1.0,1.0))
        compose.setContent {MaterialTheme {PropertiesEditor(properties,LengthUnit.METRES,2,{applied=true},{},lockMode=lock)}}
        compose.onNodeWithTag("properties_lock_message").assertTextContains("Object is locked. Unlock to edit.")
        compose.onNodeWithTag("property_x").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("property_rotation_z").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("property_width").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("properties_apply").assertIsNotEnabled()
        compose.runOnIdle {lock=LockMode.POSITION_ONLY}
        compose.onNodeWithTag("property_x").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("property_rotation_z").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("property_width").performScrollTo().assertIsEnabled()
        assertEquals(false,applied)
    }

    private fun more(action:String) {
        compose.onNodeWithTag("selection_more").performClick()
        compose.onNodeWithTag("selection_$action").performScrollTo().performClick()
    }

    @Composable private fun Toolbar(lock:LockMode,side:RoomSideRef?,display:ObjectDisplay,
        onLock:(LockMode)->Unit={},onLevel:(LockMode)->Unit={},onSide:(RoomSideRef?)->Unit={},
        onDisplay:(ObjectDisplay)->Unit={},onHide:(RoomSideRef)->Unit={},onShow:()->Unit={},
        onPanel:(CanvasPanelId,PanelVisibility)->Unit={_,_->},onHideAll:(Boolean)->Unit={}) {
        SelectionToolbar(GizmoMode.MOVE,true,false,false,false,emptyList(),null,lock!=LockMode.NONE,15,
            {},{},{},{},{},{},{},{},{},{},{},{},{},{},{},lockMode=lock,onLockMode=onLock,onLockLevel=onLevel,
            display=display,onDisplay=onDisplay,room=room,selectedRoomSide=side,onRoomSide=onSide,
            onHideRoomSide=onHide,onShowRoomWalls=onShow,onPanelVisibility=onPanel,onHideAllPanels=onHideAll)
    }
}
