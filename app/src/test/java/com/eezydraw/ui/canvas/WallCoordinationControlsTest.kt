package com.eezydraw.ui.canvas

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.eezydraw.core.editing.*
import com.eezydraw.core.model.*
import com.eezydraw.ui.canvas.editing.PropertiesEditor
import com.eezydraw.ui.canvas.panels.ObjectVisibilityPanel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1200dp-h1000dp")
@LooperMode(LooperMode.Mode.PAUSED)
class WallCoordinationControlsTest {
    @get:Rule val compose=createComposeRule()
    private val socket=BuiltInLibrary.items.first {it.symbol==LibrarySymbol.SOCKET}
    private val mount=WallMountProperties(WallFaceSide.LEFT,2.0,4.0,"Room A side (left face)","Room B side (right face)")

    @Test fun placementExposesFaceFlipTypedReferencesAndNudgesWithoutConfirmingAnItem() {
        var options by mutableStateOf(defaultWallDeviceOptions(socket))
        compose.setContent {MaterialTheme {Box(Modifier.size(1100.dp,240.dp)) {
            WallDeviceBar(socket,options,{options=it},mount.copy(side=options.side ?: mount.side),.025)
        }}}
        compose.onNodeWithTag("wall_face_label").assertTextContains("Room A",substring=true)
        compose.onNodeWithTag("wall_flip_side").performScrollTo().performClick()
        assertEquals(WallFaceSide.RIGHT,options.side)
        compose.onNodeWithTag("wall_face_label").performScrollTo().assertTextContains("Room B",substring=true)
        compose.onNodeWithTag("wall_position_increase").performScrollTo().performClick()
        assertEquals(2.025,options.position!!.distance,1e-9)
        compose.onNodeWithTag("route_choice_Position along wall").performScrollTo().performClick()
        compose.onNodeWithText("Nearest door").performClick()
        assertEquals(WallPositionReference.NEAREST_DOOR,options.position!!.reference)
        compose.onNodeWithTag("route_input_Distance to item centre").performScrollTo().performTextReplacement("300")
        assertEquals(.3,options.position!!.distance,1e-9)
        compose.onNodeWithTag("wall_position_decrease").performScrollTo().performClick()
        assertEquals(.275,options.position!!.distance,1e-9)
    }
    @Test fun doorHingeSwingAndFlipAreExplicitPlacementControls() {
        val door=BuiltInLibrary.find(LibraryItemId("eezy.architecture.single-door"))!!
        var options by mutableStateOf(defaultWallDeviceOptions(door))
        compose.setContent {MaterialTheme {Box(Modifier.size(1100.dp,240.dp)) {WallDeviceBar(door,options,{options=it},mount)}}}
        compose.onNodeWithTag("door_flip").performScrollTo().performClick()
        assertEquals(DoorDirection.OUT,options.swing!!.direction)
        compose.onNodeWithTag("route_choice_Hinge").performScrollTo().performClick()
        compose.onNodeWithText("Right").performClick();assertEquals(DoorHinge.RIGHT,options.swing!!.hinge)
        compose.onNodeWithTag("route_choice_Wall mounting").assertDoesNotExist()
    }
    @Test fun propertiesEditFaceBothEndDistancesAndMountingHeightInOneDraft() {
        val props=LibraryInstanceProperties(socket.defaultSize,1.2,"Plastic",0xFF333333,"","","","Socket",wallMount=mount)
        var applied:LibraryInstanceProperties?=null
        compose.setContent {MaterialTheme {Box(Modifier.size(500.dp,900.dp)) {
            PropertiesEditor(ElementProperties(Point3(1.95,.1,1.2),Rotation3(),Point3(.1,.04,.1)),LengthUnit.MILLIMETRES,1,{},{},
                libraryProperties=props,onApplyLibrary={_,p->applied=p})
        }}}
        compose.onNodeWithTag("property_wall_right").performScrollTo().performTextReplacement("")
        compose.onNodeWithTag("properties_apply").assertIsNotEnabled()
        compose.onNodeWithTag("property_wall_right").performScrollTo().performTextInput("1200")
        compose.onNodeWithTag("property_wall_left").assertTextContains("4800.0",substring=true)
        compose.onNodeWithTag("route_choice_Face").performScrollTo().performClick()
        compose.onNodeWithText(mount.rightFace).performClick()
        compose.onNodeWithTag("property_mounting_height").performScrollTo().performTextReplacement("1450")
        compose.onNodeWithTag("properties_apply").assertIsEnabled().performClick()
        assertEquals(1.45,applied!!.mountingHeight,1e-9)
        assertEquals(WallFaceSide.RIGHT,applied!!.wallMount!!.side)
        assertEquals(AlongWallPosition(WallPositionReference.RIGHT_END,1.2),applied!!.wallMount!!.position)
    }
    @Test fun ceilingNoneFlatAndHeightThicknessValidateBeforeApply() {
        var applied:CeilingOptions?=null
        compose.setContent {MaterialTheme {CeilingOptionsDialog("Level ceiling",CeilingOptions(height=2.8),{applied=it},{})}}
        compose.onNodeWithTag("route_choice_Ceiling").performClick();compose.onNodeWithText("Flat").performClick()
        compose.onNodeWithTag("ceiling_height").performTextReplacement("bad")
        compose.onNodeWithTag("ceiling_apply").assertIsNotEnabled()
        compose.onNodeWithTag("ceiling_height").performTextReplacement("2600")
        compose.onNodeWithTag("ceiling_thickness").performTextReplacement("120")
        compose.onNodeWithTag("ceiling_apply").assertIsEnabled().performClick()
        assertEquals(CeilingOptions(CeilingType.FLAT,2.6,.12),applied)
    }
    @Test fun visibilityOffersCeilingToggleRoomLevelOptionsAndResetAllDisplay() {
        val p=ProjectId("visibility-ceilings");val level=Level(LevelId("level"),p,"Level 1",0.0,.2,3.0)
        val layer=LayerId("base");val room=Element(ElementId("room"),p,layer,ElementBody.Room(Polygon2(listOf(Point2(0.0,0.0),Point2(2.0,0.0),Point2(2.0,2.0),Point2(0.0,2.0)))),level.id)
        var doc by mutableStateOf(ProjectDocument(Project(p,"Ceilings",0,0),levels=listOf(level),layers=listOf(Layer(layer,p,"Base")),elements=listOf(room)))
        val actions=mutableListOf<String>()
        compose.setContent {MaterialTheme {ObjectVisibilityPanel(doc,{_,_->},{_,_->},{_,_->},{},{},{},{_,_->},
            onShowCeilings={doc=doc.copy(project=doc.project.copy(settings=doc.project.settings.copy(showCeilings=it)))},
            onResetAllDisplay={actions+="reset"},onConfigureLevelCeiling={actions+="level"},onConfigureRoomCeiling={actions+="room"})}}
        compose.onNodeWithTag("visibility_ceilings").performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("visibility_reset_all_display").performScrollTo().performClick()
        compose.onNodeWithTag("level_ceiling_level").performScrollTo().performClick()
        compose.onNodeWithTag("room_ceiling_room").performScrollTo().performClick()
        assertEquals(listOf("reset","level","room"),actions)
    }
}
