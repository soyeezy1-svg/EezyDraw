package com.eezydraw.ui.canvas

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.eezydraw.core.model.*
import com.eezydraw.core.editing.*
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
class EditableDimensionsFlowTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var repositories:TestAppRepositories
    private lateinit var model:AppViewModel
    @After fun close(){if(::repositories.isInitialized)repositories.close()}
    private fun start(precision:Boolean=false) {
        repositories=TestAppRepositories(ApplicationProvider.getApplicationContext<Context>(),AppSettings(autosaveIntervalSeconds=0,lockWallsByDefault=false,precisionPointer=PrecisionPointerSettings(enabled=precision)))
        model=repositories.newViewModel();compose.waitUntil(10000){model.state.value.ready}
        compose.runOnIdle {model.create("Editable dimensions",ProjectTemplate.EMPTY,LengthUnit.MILLIMETRES)}
        compose.waitUntil(10000){model.state.value.document!=null && !model.state.value.busy}
        compose.setContent {EezyDrawApp(model,canvasSurface={_,_,modifier->Box(modifier)})}
    }
    private fun doc()=model.state.value.document!!
    private fun choose(tag:String){compose.cadGroup("draw");compose.onNodeWithTag("tool_$tag").performScrollTo().performClick();compose.cadCloseStrip("draw")}
    @Test fun typedLineLengthCommitsExactlyAndUndoRedoAreAtomic() {
        start();choose("line")
        compose.cadTap(.35f,.6f)
        compose.onNodeWithTag("construction_exact_length").performTextReplacement("1750")
        compose.cadTap(.6f,.65f)
        compose.waitUntil(10000){doc().elements.size==1}
        val line=doc().elements.single();val shape=(line.body as ElementBody.DrawingShape).shape as Shape.Line
        assertEquals(1.75,pointDistance(shape.start,shape.end),1e-7)
        compose.onNodeWithTag("view_undo").performClick();compose.waitUntil(10000){doc().elements.isEmpty()}
        compose.onNodeWithTag("view_redo").performClick();compose.waitUntil(10000){doc().elements.size==1}
        assertEquals(line,doc().elements.single())
    }
    @OptIn(ExperimentalTestApi::class)
    @Test fun hardwareDigitsAndEnterCanSetLengthBeforeTheNextPoint() {
        start();choose("line");compose.cadTap(.35f,.6f)
        compose.onNodeWithTag("canvas_surface").performKeyInput {pressKey(Key.One);pressKey(Key.Two);pressKey(Key.Five);pressKey(Key.Zero);pressKey(Key.Enter)}
        compose.waitUntil(10000){doc().elements.size==1}
        val shape=(doc().elements.single().body as ElementBody.DrawingShape).shape as Shape.Line
        assertEquals(1.25,pointDistance(shape.start,shape.end),1e-7)
    }
    @Test fun typedWallLengthUsesThePrecisionCandidateAndPreservesTheChainedStart() {
        start(true);choose("wall")
        compose.cadTap(.35f,.6f);compose.onNodeWithTag("precision_place").performClick()
        compose.onNodeWithTag("construction_exact_length").performTextReplacement("2300")
        compose.cadTap(.6f,.65f)
        compose.onNodeWithTag("construction_live_distance").assertTextContains("2300",substring=true)
        compose.onNodeWithTag("precision_place").performClick()
        compose.waitUntil(10000){doc().elements.size==1}
        val wall=doc().elements.single();val body=wall.body as ElementBody.Wall
        assertEquals(2.3,kotlin.math.hypot(body.end.x-body.start.x,body.end.y-body.start.y),1e-7)
        compose.onNodeWithTag("construction_exact_length").performTextReplacement("1100")
        compose.cadTap(.5f,.7f);compose.onNodeWithTag("precision_place").performClick()
        compose.waitUntil(10000){doc().elements.size==2}
        assertEquals(measurementToWorld(wall.transform,Point3(body.end.x,body.end.y)),doc().elements.last().transform.translation)
    }
    @Test fun measureShowsLiveDistanceDuringDragThenSavesTwoAttachedPoints() {
        start(true);compose.cadGroup("measure");compose.onNodeWithTag("canvas_measure").performClick();compose.cadCloseStrip("measure")
        compose.cadTap(.35f,.6f);compose.onNodeWithTag("precision_place").performClick()
        compose.onNodeWithTag("canvas_surface").performTouchInput {down(center);moveTo(center+androidx.compose.ui.geometry.Offset(100f,-40f),48)}
        compose.onNodeWithTag("construction_live_distance").assertExists()
        assertTrue(doc().elements.isEmpty())
        compose.onNodeWithTag("canvas_surface").performTouchInput {up()}
        compose.onNodeWithTag("precision_place").performClick();compose.waitUntil(10000){doc().elements.size==1}
        assertTrue(isMeasurement(doc().elements.single()))
        val before=doc().elements.single()
        compose.waitUntil(10000){compose.onAllNodesWithTag("dimension_label_${before.id.value}").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("dimension_label_${before.id.value}").performClick()
        compose.onNodeWithTag("dimension_value").performTextReplacement("3250")
        compose.onNodeWithTag("dimension_fixed_CENTER").performClick();compose.onNodeWithTag("dimension_apply").performClick()
        compose.waitUntil(10000){doc().project.settings.dimensionFixedEnd==FixedEnd.CENTER}
        assertEquals(3.25,resolveMeasurement(doc(),doc().elements.single())!!.distance,1e-7)
        compose.onNodeWithTag("view_undo").performClick();compose.waitUntil(10000){doc().elements.single()==before}
    }
    @Test fun oneTapWallDimensionsUseTheSelectedWallAndGeometryChangesOnValueEdit() {
        start();choose("wall");compose.cadTap(.35f,.6f);compose.cadTap(.6f,.65f)
        compose.waitUntil(10000){doc().elements.size==1}
        compose.onNodeWithTag("placement_cancel").performScrollTo().performClick()
        val wall=doc().elements.single();compose.runOnIdle {model.select(setOf(wall.id))}
        compose.cadSelection();compose.onNodeWithTag("selection_dimension_wall").performClick()
        compose.waitUntil(10000){doc().elements.count(::isMeasurement)==3}
        val length=doc().elements.first {measurementShape(it)?.measurement?.binding?.property==DimensionProperty.LENGTH}
        compose.waitUntil(10000){compose.onAllNodesWithTag("dimension_label_${length.id.value}").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("dimension_label_${length.id.value}").performClick()
        compose.onNodeWithTag("dimension_value").performTextReplacement("4000")
        compose.onNodeWithTag("dimension_fixed_END").performClick();compose.onNodeWithTag("dimension_apply").performClick()
        compose.waitUntil(10000){doc().project.settings.dimensionFixedEnd==FixedEnd.END}
        assertEquals(4.0,resolveMeasurement(doc(),doc().elements.first {it.id==length.id})!!.distance,1e-7)
    }
}
