package com.eezydraw.ui.canvas

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.eezydraw.core.model.*
import com.eezydraw.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class BuildingFlowTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var repositories:TestAppRepositories
    private lateinit var model:AppViewModel
    private lateinit var wall:Element
    @After fun close(){repositories.close()}
    private fun start() {
        repositories=TestAppRepositories(ApplicationProvider.getApplicationContext<Context>(),AppSettings(autosaveIntervalSeconds=0,precisionPointer=PrecisionPointerSettings(enabled=false)))
        model=repositories.newViewModel()
        compose.waitUntil(10000){model.state.value.ready}
        compose.runOnIdle {model.create("Walls",ProjectTemplate.EMPTY,LengthUnit.MILLIMETRES)}
        compose.waitUntil(10000){model.state.value.document!=null && !model.state.value.busy}
        compose.runOnIdle {
            val doc=model.state.value.document!!
            wall=Element(ElementId("wall"),doc.project.id,doc.layers.single().id,ElementBody.Wall(Point2(-1.5,0.0),Point2(1.5,0.0),.2,3.0),name="W-01")
            repositories.projects.documents[doc.project.id.value]=doc.copy(elements=listOf(wall));model.open(doc.project.id.value)
        }
        compose.waitUntil(10000){model.state.value.document?.elements?.size==1 && !model.state.value.busy}
        compose.setContent {EezyDrawApp(model,canvasSurface={_,_,modifier->Box(modifier)})}
    }
    @Test fun roomDefaultsGenerateWallsAndSelectionHasDirectAction() {
        start()
        compose.runOnIdle {model.deleteElements(setOf(wall.id))}
        compose.onNodeWithTag("toolbar_draw").performClick()
        compose.onNodeWithTag("tool_room").performScrollTo().performClick()
        compose.onNodeWithTag("room_auto_walls").assertExists()
        compose.onNodeWithTag("tools_collapse").performClick()
        compose.onNodeWithTag("canvas_surface").performTouchInput {click(Offset(width*.35f,height*.5f));click(Offset(width*.65f,height*.75f))}
        compose.waitUntil(10000){model.state.value.document!!.elements.count {it.body is ElementBody.Wall}==4}
        val room=model.state.value.document!!.elements.first {it.body is ElementBody.Room}
        compose.runOnIdle {model.select(setOf(room.id))}
        compose.onNodeWithTag("selection_generate_walls").performClick()
        compose.onNodeWithTag("generate_walls_create").performClick()
        assertEquals(4,model.state.value.document!!.elements.count {it.body is ElementBody.Wall})
    }
    @Test fun wallToolChainsAndCancelEndsTheChain() {
        start()
        compose.runOnIdle {model.deleteElements(setOf(wall.id))}
        compose.onNodeWithTag("toolbar_draw").performClick()
        compose.onNodeWithTag("tool_wall").performScrollTo().performClick()
        compose.onNodeWithTag("tools_collapse").performClick()
        compose.onNodeWithTag("canvas_surface").performTouchInput {click(Offset(width*.3f,height*.55f));click(Offset(width*.55f,height*.55f))}
        compose.waitUntil(10000){model.state.value.document!!.elements.size==1}
        compose.onNodeWithTag("canvas_surface").performTouchInput {click(Offset(width*.55f,height*.8f))}
        compose.waitUntil(10000){model.state.value.document!!.elements.size==2}
        compose.onNodeWithTag("placement_cancel").performClick()
        assertEquals(2,model.state.value.document!!.elements.size)
    }
    @Test fun missingWallMessageOffersWallTool() {
        start()
        compose.runOnIdle {model.deleteElements(setOf(wall.id))}
        val socket=BuiltInLibrary.items.first {it.symbol==LibrarySymbol.SOCKET}
        compose.onNodeWithTag("toolbar_library").performClick()
        compose.onNodeWithTag("library_search").performTextInput(socket.name)
        compose.onNodeWithTag("library_insert_${socket.id.value}").performScrollTo().performClick()
        compose.onAllNodesWithText("No wall on this level. Draw a room or wall first.").onFirst().assertExists()
        compose.onNodeWithTag("missing_wall_open_tool").performClick()
        compose.onNodeWithTag("route_input_Wall thickness").assertExists()
        compose.onNodeWithTag("placement_cancel").performClick()
        compose.onNodeWithTag("toolbar_draw").performClick()
        compose.onNodeWithTag("tool_box").performScrollTo().performClick()
        compose.onNodeWithTag("tools_collapse").performClick()
        compose.onNodeWithTag("canvas_surface").performTouchInput {click(Offset(width*.4f,height*.7f))}
        compose.waitUntil(10000){model.state.value.document!!.elements.size==1}
        assertTrue(model.state.value.document!!.elements.single().body is ElementBody.DrawingShape)
    }
}
