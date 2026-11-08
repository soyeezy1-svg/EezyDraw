package com.eezydraw.ui.canvas

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eezydraw.core.geometry.*
import com.eezydraw.core.model.*
import com.eezydraw.storage.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class DrawingExportDialogTest {
    @get:Rule val compose=createComposeRule()
    private val p=ProjectId("sheet")
    private val level=Level(LevelId("g"),p,"Ground",0.0,.2,3.0)
    private val document=ProjectDocument(Project(p,"Plan",0,0),levels=listOf(level))
    private val request=ViewExportRequest(document,CanvasFrame(Viewport2D(),OrbitCamera(),levelId=level.id.value),400,240)

    @Test fun paperScaleUnderlayAndLevelChoicesReachTheExportRequest() {
        var result:Pair<ViewExportRequest,DrawingExportOptions>?=null
        compose.setContent {MaterialTheme {DrawingExportDialog(request,DrawingExportOptions(),{r,o->result=r to o},{})}}
        compose.onNodeWithTag("route_choice_Drawing").performScrollTo().performClick()
        compose.onNodeWithText("Paper size and scale").performClick()
        compose.onNodeWithTag("route_choice_Paper").performScrollTo().performClick()
        compose.onNodeWithText("A1").performClick()
        compose.onNodeWithTag("route_choice_Scale").performScrollTo().performClick()
        compose.onNodeWithText("1:50").performClick()
        compose.onNodeWithTag("drawing_export_underlay").performScrollTo().performClick()
        compose.onNodeWithTag("drawing_export_save").performClick()
        val (snapshot,options)=result!!
        assertEquals(DrawingPaper.A1,options.paper);assertEquals(50.0,options.scale,0.0)
        assertEquals(DrawingScope.SHEET,options.scope);assertFalse(options.includeUnderlay)
        assertEquals(level.id.value,snapshot.frame.levelId)
    }

    @Test fun invalidScaleIsBlockedAndFormatChoicesConstrainScopeAndUnits() {
        var result:DrawingExportOptions?=null
        compose.setContent {MaterialTheme {DrawingExportDialog(request,DrawingExportOptions(scope=DrawingScope.SHEET),{_,o->result=o},{})}}
        compose.onNodeWithTag("drawing_export_scale").performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag("drawing_export_save").assertIsNotEnabled()
        compose.onNodeWithTag("drawing_export_scale").performTextReplacement("75")
        compose.onNodeWithTag("drawing_export_save").performClick()
        assertEquals(75.0,result!!.scale,0.0)
        compose.onNodeWithTag("route_choice_Format").performScrollTo().performClick()
        compose.onNodeWithText("PNG").performClick()
        compose.onNodeWithTag("route_choice_Drawing").assertTextContains("Drawing: Current view")
        compose.onNodeWithTag("drawing_export_save").performClick()
        assertEquals(DrawingScope.VIEW,result!!.scope)
        compose.onNodeWithTag("route_choice_Format").performScrollTo().performClick()
        compose.onNodeWithText("DXF").performClick()
        compose.onNodeWithTag("route_choice_Units").performScrollTo().performClick()
        compose.onNodeWithText("cm").performClick()
        compose.onNodeWithTag("drawing_export_save").performClick()
        assertEquals(DrawingScope.LEVEL,result!!.scope);assertEquals(ModelUnits.CM,result!!.units)
    }
}
