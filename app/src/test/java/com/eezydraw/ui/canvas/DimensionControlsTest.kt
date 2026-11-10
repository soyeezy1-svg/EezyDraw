package com.eezydraw.ui.canvas

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eezydraw.core.model.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class DimensionControlsTest {
    @get:Rule val compose=createComposeRule()
    @Test fun dimensionEditorConvertsUnitsOffersAllFixedEndsAndUsesRememberedDefault() {
        var result:Pair<Double,FixedEnd>?=null
        compose.setContent {MaterialTheme {DimensionValueDialog(DimensionValueRequest(2.0),ProjectSettings(dimensionFixedEnd=FixedEnd.CENTER),{v,f->result=v to f;true},{})}}
        compose.onNodeWithTag("dimension_fixed_CENTER").assertIsSelected()
        FixedEnd.entries.forEach {fixed->
            compose.onNodeWithTag("dimension_fixed_${fixed.name}").performClick()
            compose.onNodeWithTag("dimension_value").performTextReplacement("3750")
            compose.onNodeWithTag("dimension_apply").performClick()
            assertEquals(3.75,result!!.first,1e-8);assertEquals(fixed,result!!.second)
        }
        compose.onNodeWithTag("dimension_value").performTextReplacement("0")
        compose.onNodeWithTag("dimension_apply").assertIsNotEnabled()
    }
    @Test fun wallOptionsAllowFaceLengthThicknessAndHeightChoices() {
        var faces:Set<WallLengthFace>?=null;var thickness=false;var height=false
        compose.setContent {MaterialTheme {WallDimensionOptionsDialog({f,t,h->faces=f;thickness=t;height=h},{})}}
        compose.onNodeWithTag("wall_dimension_INSIDE").performClick()
        compose.onNodeWithTag("wall_dimension_OUTSIDE").performClick()
        compose.onNodeWithTag("wall_dimension_apply").performClick()
        assertEquals(WallLengthFace.entries.toSet(),faces);assertTrue(thickness);assertTrue(height)
    }
    @Test fun moveDialogSupportsEachReferenceLockAndASecondWall() {
        val project=ProjectId("p");val layer=LayerId("l")
        val wall=Element(ElementId("wall"),project,layer,ElementBody.Wall(Point2(0.0,0.0),Point2(4.0,0.0),.2,3.0))
        val item=wall.copy(id=ElementId("item"),body=ElementBody.DrawingShape(Shape.Box(Size3(1.0,1.0,1.0))))
        val document=ProjectDocument(Project(project,"UI QA",0,0),layers=listOf(Layer(layer,project,"Drawing")),elements=listOf(wall,item))
        var result:List<WallDistance>?=null;var locked=false;var second:List<WallDistance>?=null
        compose.setContent {MaterialTheme {MoveFromWallDialog(document,item,listOf(WallDistance(wall.id,WallFaceSide.LEFT,1.0)),{c,l->result=c;locked=l;true},{second=it},{})}}
        compose.onNodeWithTag("wall_distance_0").performTextReplacement("250")
        DistanceReference.entries.forEach {reference->
            compose.onNodeWithTag("wall_reference_0_${reference.name}").performClick()
            compose.onNodeWithTag("move_from_wall_apply").performClick()
            assertEquals(reference,result!!.single().reference);assertEquals(.25,result!!.single().distance,1e-8)
        }
        compose.onNodeWithTag("wall_distance_lock").performClick()
        compose.onNodeWithTag("move_from_wall_apply").performClick();assertTrue(locked)
        compose.onNodeWithTag("pick_second_wall").performClick();assertEquals(result,second)
    }
    @Test fun selectedItemNudgesUseCanonicalGridSteps() {
        val moves=mutableListOf<Point3>();var snapped=false
        compose.setContent {MaterialTheme {SelectedItemNudges(ProjectSettings(),{moves+=it},{snapped=true})}}
        compose.onNodeWithTag("item_nudge_step").performTextReplacement("25")
        (0..3).forEach {compose.onNodeWithTag("item_nudge_$it").performClick()}
        assertEquals(listOf(Point3(-.025),Point3(.025),Point3(y=.025),Point3(y= -.025)),moves)
        compose.onNodeWithTag("item_snap_grid").performClick();assertTrue(snapped)
    }
}
