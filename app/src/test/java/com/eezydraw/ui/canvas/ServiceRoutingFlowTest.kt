package com.eezydraw.ui.canvas

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.SideEffect
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
class ServiceRoutingFlowTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var repositories:TestAppRepositories
    private lateinit var model:AppViewModel
    @After fun close(){repositories.close()}
    private fun start(precision:Boolean) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        repositories=TestAppRepositories(context,AppSettings(autosaveIntervalSeconds=0,precisionPointer=PrecisionPointerSettings(enabled=precision)))
        model=repositories.newViewModel()
        compose.waitUntil(10000){model.state.value.ready}
        compose.runOnIdle {model.create("Service routes",ProjectTemplate.EMPTY,LengthUnit.MILLIMETRES)}
        compose.waitUntil(10000){model.state.value.document!=null && !model.state.value.busy}
        compose.setContent {EezyDrawApp(model,canvasSurface={_,_,modifier->Box(modifier)})}
        compose.onNodeWithTag("toolbar_draw").performClick()
        compose.onNodeWithTag("tool_route_CABLE_TRAY").performScrollTo().performClick()
        compose.onNodeWithTag("routing_settings").assertIsDisplayed()
        compose.onNodeWithTag("tools_collapse").performClick()
    }
    @Test fun routeUsesGhostCancelRiserAndRememberedSettingsThenUndoRedo() {
        start(false)
        val before=compose.runOnIdle {model.state.value.document!!.project.settings.canvasLayout}
        compose.onNodeWithTag("canvas_surface").performTouchInput {click(Offset(width*.4f,height*.6f))}
        compose.onNodeWithTag("route_ghost").assertExists()
        compose.onNodeWithTag("canvas_surface").performTouchInput {click(Offset(width*.7f,height*.6f))}
        compose.onNodeWithTag("route_input_Elevation").performTextReplacement("3000")
        compose.waitForIdle()
        assertEquals(AppScreen.PROJECT,model.state.value.screen)
        compose.onNodeWithTag("canvas_surface").performTouchInput {click(Offset(width*.7f,height*.8f))}
        compose.onNodeWithTag("placement_done").performClick()
        compose.waitUntil(10000){model.state.value.document!!.elements.count {it.body is ElementBody.Route}==3}
        assertEquals(3.0,model.state.value.settings.lastRoutingSettings.getValue(RouteKind.CABLE_TRAY).elevation.value,1e-8)
        val drawing=compose.runOnIdle {model.state.value.document!!}
        assertEquals(before,drawing.project.settings.canvasLayout)
        assertTrue(drawing.elements.any {e->val b=e.body as? ElementBody.Route;b!=null && b.path.first().x==b.path.last().x && b.path.first().y==b.path.last().y && b.path.first().z!=b.path.last().z})
        compose.runOnIdle {model.undo()};assertTrue(model.state.value.document!!.elements.isEmpty())
        compose.runOnIdle {model.redo()};assertEquals(drawing,model.state.value.document)
    }
    @Test fun precisionPointerRequiresPlaceAndCancelLeavesNoObjects() {
        start(true)
        compose.onNodeWithTag("canvas_surface").performTouchInput {click(Offset(width*.5f,height*.65f))}
        assertTrue(model.state.value.document!!.elements.isEmpty())
        compose.onNodeWithTag("precision_place").performClick()
        assertTrue(model.state.value.document!!.elements.isEmpty())
        compose.onNodeWithTag("precision_cancel").performClick()
        assertTrue(model.state.value.document!!.elements.isEmpty())
        compose.onNodeWithTag("routing_settings").assertDoesNotExist()
    }
}
