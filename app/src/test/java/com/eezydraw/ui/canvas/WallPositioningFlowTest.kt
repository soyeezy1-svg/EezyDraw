package com.eezydraw.ui.canvas

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.eezydraw.core.model.*
import com.eezydraw.core.editing.*
import com.eezydraw.ui.*
import java.util.concurrent.atomic.AtomicReference
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class WallPositioningFlowTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var repositories:TestAppRepositories
    private lateinit var model:AppViewModel
    private val rendered=AtomicReference<CanvasFrame>()
    private val itemId=ElementId("clearance-item")
    private val horizontalId=ElementId("horizontal")
    private val verticalId=ElementId("vertical")
    @After fun close(){if(::repositories.isInitialized)repositories.close()}
    private fun doc()=model.state.value.document!!
    private fun item()=doc().elements.first {it.id==itemId}
    private fun start(precision:Boolean=false) {
        repositories=TestAppRepositories(ApplicationProvider.getApplicationContext<Context>(),AppSettings(autosaveIntervalSeconds=0,lockWallsByDefault=false,precisionPointer=PrecisionPointerSettings(enabled=precision)))
        model=repositories.newViewModel();compose.waitUntil(10000){model.state.value.ready}
        compose.runOnIdle {model.create("Wall positioning",ProjectTemplate.EMPTY,LengthUnit.MILLIMETRES)}
        compose.waitUntil(10000){model.state.value.document!=null && !model.state.value.busy}
        compose.runOnIdle {
            val base=doc();val layer=base.layers.first().id;val level=LevelId("ground")
            val wall=Element(horizontalId,base.project.id,layer,ElementBody.Wall(Point2(-1.0,0.0),Point2(5.0,0.0),.2,3.0),level,name="Horizontal wall")
            val vertical=wall.copy(id=verticalId,body=ElementBody.Wall(Point2(0.0,-1.0),Point2(0.0,5.0),.2,3.0),name="Vertical wall")
            val item=wall.copy(id=itemId,body=ElementBody.DrawingShape(Shape.Box(Size3(.6,.8,.6))),name="Cabinet",transform=Transform3(Point3(2.0,2.0)))
            repositories.projects.documents[base.project.id.value]=base.copy(levels=listOf(Level(level,base.project.id,"Ground",0.0,.2,3.0)),elements=listOf(wall,vertical,item));model.open(base.project.id.value)
        }
        compose.waitUntil(10000){doc().elements.size==3 && !model.state.value.busy}
        compose.setContent {EezyDrawApp(model,canvasSurface={_,frame,modifier->SideEffect {rendered.set(frame)};Box(modifier)})}
        compose.runOnIdle {model.select(setOf(itemId))}
        compose.cadSelection()
    }
    private fun wallPick(point:Point3,precision:Boolean=false) {
        val area=compose.onNodeWithTag("canvas_surface").fetchSemanticsNode().boundsInRoot
        val frame=rendered.get()
        val p=frame.viewport.worldToScreen(Point2(point.x,point.y),area.width.toDouble(),area.height.toDouble())
        // Stylus input is covered by the shared pointer tests; offset the finger aim here.
        val at=if(precision)Offset(p.x.toFloat()+80f,p.y.toFloat()+80f)else Offset(p.x.toFloat(),p.y.toFloat())
        compose.onNodeWithTag("canvas_surface").performTouchInput {click(at)}
        if(precision)compose.onNodeWithTag("precision_place").performClick()
    }
    @Test fun moreMenuWallPicksTwoDirectionsAndLockedDistancesFollowWallsThroughUndoAndSave() {
        start()
        compose.onNodeWithTag("selection_more").performClick();compose.onNodeWithTag("selection_move_from_wall").performClick()
        compose.onNodeWithTag("canvas_hide_panels").performClick()
        wallPick(Point3(2.0,.1))
        compose.onNodeWithTag("wall_distance_0").performTextReplacement("500")
        compose.onNodeWithTag("pick_second_wall").performClick()
        wallPick(Point3(.1,2.0))
        compose.onNodeWithTag("wall_distance_1").performTextReplacement("750")
        compose.onNodeWithTag("wall_reference_1_CENTER").performClick()
        compose.onNodeWithTag("wall_distance_lock").performClick();compose.onNodeWithTag("move_from_wall_apply").performClick()
        compose.waitUntil(10000){item().wallDistances.size==2}
        val positioned=doc()
        val constraints=item().wallDistances
        assertEquals(listOf(.5,.75),constraints.map {it.distance})
        constraints.forEach {c->assertEquals(c.distance,WallPositioning.clearance(doc(),item(),c).distance,1e-7)}
        compose.waitUntil(10000){compose.onAllNodesWithTag("item_wall_distance_0").fetchSemanticsNodes().isNotEmpty()}
        compose.runOnIdle {model.select(setOf(horizontalId,verticalId));assertTrue(model.transformSelection(EditDelta(translation=Point3(.3,.4))))}
        constraints.forEach {c->assertEquals(c.distance,WallPositioning.clearance(doc(),item(),c).distance,1e-7)}
        val moved=doc()
        compose.runOnIdle {model.undo()};assertEquals(positioned,doc())
        compose.runOnIdle {model.redo()};assertEquals(moved,doc())
        compose.runOnIdle {model.save()};compose.waitUntil(10000){!model.state.value.busy && !model.state.value.dirty}
        compose.runOnIdle {model.open(doc().project.id.value)};compose.waitUntil(10000){!model.state.value.busy}
        assertEquals(constraints,item().wallDistances)
    }
}
