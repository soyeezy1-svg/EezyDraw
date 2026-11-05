package com.eezydraw.ui.canvas.panels

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.eezydraw.core.model.*
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
class ObjectVisibilityPanelTest {
    @get:Rule val compose=createComposeRule()
    private val id=ProjectId("visibility")
    private val layer=LayerId("base")
    private val level=Level(LevelId("first"),id,"Level 1",0.0,.2,3.4)
    private val room=Element(ElementId("room"),id,layer,ElementBody.Room(
        Polygon2(listOf(Point2(0.0,0.0),Point2(2.0,0.0),Point2(2.0,2.0),Point2(0.0,2.0)))),level.id,name="Office")
    private val wall=Element(ElementId("wall"),id,layer,ElementBody.Wall(Point2(0.0,0.0),Point2(2.0,0.0),.2,3.0),level.id)
    private val shape=Element(ElementId("shape"),id,layer,ElementBody.DrawingShape(Shape.Box(Size3(1.0,1.0,1.0))),level.id)

    @Test fun levelsRoomSidesWallsAndHiddenShapesHaveIndependentEyeActions() {
        var document by mutableStateOf(ProjectDocument(Project(id,"Visible",0,0),levels=listOf(level),
            layers=listOf(Layer(layer,id,"Base")),elements=listOf(room,wall,shape.withDisplay(ObjectDisplay.HIDDEN))))
        val events=mutableListOf<String>()
        compose.setContent {MaterialTheme {ObjectVisibilityPanel(document,
            onLevelVisible={key,visible->events+="level:${key.value}:$visible"},
            onElementDisplay={key,mode->
                events+="object:${key.value}:$mode"
                document=document.copy(elements=document.elements.map {if(it.id==key)it.withDisplay(mode)else it})
            },onSystemVisible={_,_->},onShowAll={events+="all"},
            onShowHidden={value->events+="hidden:$value"},onAutoXray={value->events+="xray:$value"},
            onRoomSideDisplay={side,mode->events+="side:${side.edgeIndex}:$mode"})}}
        compose.onNodeWithTag("visibility_level_first").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("visibility_room_room_outer_2").performScrollTo().performClick()
        compose.onNodeWithTag("visibility_element_wall").performScrollTo().performClick()
        compose.onNodeWithTag("visibility_element_shape").performScrollTo().performClick()
        assertEquals(listOf("level:first:false","side:2:HIDDEN","object:wall:HIDDEN","object:shape:NORMAL"),events)
        assertEquals(shape.body,document.elements.single {it.id==shape.id}.body)
        assertEquals(ObjectDisplay.NORMAL,document.elements.single {it.id==shape.id}.objectDisplay)
    }

    @Test fun showAllAndHiddenAndAutomaticXrayOptionsAreExplicit() {
        val document=ProjectDocument(Project(id,"Options",0,0))
        val events=mutableListOf<String>()
        compose.setContent {MaterialTheme {ObjectVisibilityPanel(document,{_,_->},{_,_->},{_,_->},
            {events+="all"},{events+="hidden:$it"},{events+="xray:$it"},{_,_->})}}
        assertTrue(events.isEmpty())
        compose.onNodeWithTag("visibility_show_hidden").performScrollTo().assertHasClickAction().performClick()
        compose.onNodeWithTag("visibility_auto_xray").performScrollTo().performClick()
        compose.onNodeWithTag("visibility_show_all").performScrollTo().performClick()
        assertEquals(listOf("hidden:true","xray:true","all"),events)
    }
}
