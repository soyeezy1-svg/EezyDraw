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
class WallServicesFlowTest {
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
    @Test fun deviceMountingVisibilityScheduleThresholdAndSectionUseUndoableProjectSettings() {
        start()
        compose.runOnIdle {
            val socket=BuiltInLibrary.items.first {it.symbol==LibrarySymbol.SOCKET}
            assertTrue(model.insertWallLibraryItem(socket.id,Point3(0.0,.1,.3),null,WallServiceOptions(WallServiceMode.RECESS,depth=.15)))
        }
        compose.waitUntil(10000){model.state.value.document!!.elements.size==2}
        assertTrue(wallDepthWarnings(model.state.value.document!!).isNotEmpty())
        compose.onNodeWithTag("wall_service_properties").assertIsDisplayed()
        compose.onNodeWithTag("wall_schedule_open").performClick()
        compose.onNodeWithTag("wall_schedule").assertIsDisplayed()
        compose.onNodeWithTag("wall_depth_threshold").performTextReplacement("90")
        assertTrue(wallDepthWarnings(model.state.value.document!!).isEmpty())
        compose.onNodeWithTag("wall_section_view").performClick()
        assertEquals(ViewMode.SECTION,model.state.value.document!!.project.settings.viewMode)
        compose.runOnIdle {model.wallServiceSettings(model.state.value.document!!.project.settings.wallServices.copy(showCuts=false))}
        assertFalse(model.state.value.document!!.project.settings.wallServices.showCuts)
        compose.runOnIdle {model.undo()}
        assertTrue(model.state.value.document!!.project.settings.wallServices.showCuts)
    }
    @Test fun concealedConduitLauncherUsesSharedGhostAndCancelWithoutChangingWall() {
        start()
        compose.onNodeWithTag("toolbar_draw").performClick()
        compose.onNodeWithTag("tool_route_CONDUIT").performScrollTo().performClick()
        compose.onNodeWithTag("route_choice_Wall route").performClick()
        compose.onNodeWithText("Concealed (chase)").performClick()
        for((label,angle) in listOf("45°" to WallSnapAngles.FORTY_FIVE,"Free" to WallSnapAngles.FREE,"0°" to WallSnapAngles.HORIZONTAL,"90°" to WallSnapAngles.NINETY)) {
            compose.onNodeWithTag("route_choice_Snap angles").performScrollTo().performClick()
            compose.onNodeWithText(label).performClick()
            assertEquals(angle,model.state.value.settings.lastRoutingSettings[RouteKind.CONDUIT]!!.effectiveWallSnapAngles())
        }
        compose.onNodeWithTag("tools_collapse").performClick()
        compose.onNodeWithTag("route_ghost").assertExists()
        compose.onNodeWithTag("canvas_surface").performTouchInput {click(Offset(width*.4f,height*.5f))}
        compose.onNodeWithTag("placement_cancel").performClick()
        assertEquals(listOf(wall),model.state.value.document!!.elements)
        assertTrue(wallServiceCuts(model.state.value.document!!).isEmpty())
    }
}
