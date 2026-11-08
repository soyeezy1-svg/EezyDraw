package com.eezydraw.ui.canvas

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.SideEffect
import com.eezydraw.core.geometry.DrawingScene
import java.util.concurrent.atomic.AtomicReference
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
class FloorServicesFlowTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var repositories:TestAppRepositories
    private lateinit var model:AppViewModel
    private val scene=AtomicReference<DrawingScene?>()
    private val frame=AtomicReference<CanvasFrame?>()
    private fun shown(id:String)=(scene.get()?.segments.orEmpty()+frame.get()?.selectionScene?.segments.orEmpty()).any {it.elementId==id}
    @After fun close(){repositories.close()}
    private fun start() {
        repositories=TestAppRepositories(ApplicationProvider.getApplicationContext<Context>(),AppSettings(autosaveIntervalSeconds=0,precisionPointer=PrecisionPointerSettings(enabled=false)))
        model=repositories.newViewModel()
        compose.waitUntil(10000){model.state.value.ready}
        compose.runOnIdle {model.create("Underfloor",ProjectTemplate.EMPTY,LengthUnit.MILLIMETRES)}
        compose.waitUntil(10000){model.state.value.document!=null && !model.state.value.busy}
        compose.setContent {EezyDrawApp(model,canvasSurface={value,state,modifier->SideEffect {scene.set(value);frame.set(state)};Box(modifier)})}
    }
    @Test fun installationDepthGhostDrawingScheduleVisibilityAndUndoUseTheExistingFlow() {
        start()
        compose.onNodeWithTag("toolbar_draw").performClick()
        compose.onNodeWithTag("tool_route_CONDUIT").performScrollTo().performClick()
        compose.onNodeWithTag("tools_collapse").performClick()
        compose.onNodeWithTag("route_choice_Installation").performScrollTo().performClick()
        compose.onNodeWithText("In screed").performClick()
        compose.onNodeWithTag("route_input_Depth below finished floor").performTextReplacement("20")
        compose.onNodeWithTag("route_live_label").assertTextContains("-20",substring=true)
        compose.onNodeWithTag("canvas_surface").performTouchInput {click(Offset(width*.35f,height*.65f));click(Offset(width*.65f,height*.65f))}
        compose.onNodeWithTag("route_ghost").assertExists()
        compose.onNodeWithTag("placement_done").performClick()
        compose.waitUntil(10000){model.state.value.document!!.elements.any {it.body is ElementBody.Route}}
        val doc=model.state.value.document!!
        assertEquals(.02,doc.elements.first {it.body is ElementBody.Route}.floorRouting()!!.floorDepth,1e-9)
        compose.runOnIdle {model.select(setOf(doc.elements.first {it.body is ElementBody.Route}.id))}
        compose.onNodeWithTag("rise_to_floor_box").performClick()
        compose.onNodeWithText("Place a floor box or floor socket from the library first.").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        val routeId=doc.elements.first {it.body is ElementBody.Route}.id.value
        compose.waitUntil(10000){shown(routeId)}
        compose.runOnIdle {model.floorServiceSettings(model.state.value.document!!.project.settings.floorServices.copy(showServices=false))}
        assertFalse(model.state.value.document!!.project.settings.floorServices.showServices)
        compose.waitUntil(10000){!shown(routeId)}
        compose.runOnIdle {model.undo()}
        assertTrue(model.state.value.document!!.project.settings.floorServices.showServices)
        compose.waitUntil(10000){shown(routeId)}
    }
    @Test fun slabLauncherAndFloorLibraryAreAvailable() {
        start()
        compose.onNodeWithTag("toolbar_draw").performClick()
        compose.onNodeWithTag("tool_slab").performScrollTo().performClick()
        compose.onNodeWithTag("tools_collapse").performClick()
        compose.onNodeWithTag("route_input_Slab thickness").performTextReplacement("220")
        compose.onNodeWithTag("canvas_surface").performTouchInput {click(Offset(width*.35f,height*.55f));click(Offset(width*.65f,height*.8f))}
        compose.waitUntil(10000){model.state.value.document!!.elements.any {it.body is ElementBody.Slab}}
        assertEquals(.22,(model.state.value.document!!.elements.first {it.body is ElementBody.Slab}.body as ElementBody.Slab).thickness,1e-9)
        assertTrue(BuiltInLibrary.items.any {it.floorService==FloorDeviceKind.SLAB_SLEEVE})
    }
}
