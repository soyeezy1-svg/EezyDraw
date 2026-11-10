package com.eezydraw.ui.canvas

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eezydraw.core.geometry.*
import com.eezydraw.core.model.*
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
class ModelExportDialogTest {
    @get:Rule val compose=createComposeRule()
    @Test fun formatScopeUnitsAndSaveUseExplicitUserChoices() {
        val p=ProjectId("export")
        val level=Level(LevelId("g"),p,"Ground",0.0,.2,3.0)
        val document=ProjectDocument(Project(p,"Export",0,0),levels=listOf(level))
        var result:ModelExportOptions?=null
        compose.setContent {MaterialTheme {ModelExportDialog(document,ModelExportOptions(levelId=level.id),{result=it},{})}}
        compose.onNodeWithTag("route_choice_Format").performScrollTo().performClick()
        compose.onNodeWithText("GLB").performClick()
        compose.onNodeWithTag("route_choice_What to export").performScrollTo().performClick()
        compose.onNodeWithText("Selected objects only").performClick()
        compose.onNodeWithTag("model_export_save").assertIsNotEnabled()
        compose.onNodeWithTag("route_choice_What to export").performScrollTo().performClick()
        compose.onNodeWithText("Current level only").performClick()
        compose.onNodeWithTag("route_choice_Units").performScrollTo().performClick()
        compose.onNodeWithText("cm").performClick()
        compose.onNodeWithTag("model_export_save").performClick()
        assertEquals(ModelFormat.GLB,result!!.format);assertEquals(ModelUnits.CM,result!!.units);assertEquals(ModelScope.LEVEL,result!!.scope);assertEquals(level.id,result!!.levelId)
    }
    @Test fun axisDefaultsOriginAndFormatConstraintsReachTheExporter() {
        val document=ProjectDocument(Project(ProjectId("axes"),"Axes",0,0))
        var result:ModelExportOptions?=null
        compose.setContent {MaterialTheme {ModelExportDialog(document,ModelExportOptions(),{result=it},{})}}
        compose.onNodeWithTag("route_choice_Format").performScrollTo().performClick()
        compose.onNodeWithText("OBJ").performClick()
        compose.onNodeWithTag("route_choice_Up axis").performScrollTo().assertTextContains("Up axis: Y-up")
        compose.onNodeWithTag("route_choice_Units").performScrollTo().assertTextContains("Units: m")
        compose.onNodeWithTag("route_choice_Up axis").performScrollTo().performClick()
        compose.onNodeWithText("Z-up").performClick()
        compose.onNodeWithTag("route_choice_Position").performScrollTo().performClick()
        compose.onNodeWithText("Move model to origin").performClick()
        compose.onNodeWithTag("model_export_save").performClick()
        assertEquals(ModelUpAxis.Z_UP,result!!.resolvedUpAxis())
        assertTrue(result!!.moveToOrigin)
        compose.onNodeWithTag("route_choice_Format").performScrollTo().performClick()
        compose.onNodeWithText("GLB").performClick()
        compose.onNodeWithTag("route_choice_Up axis").performScrollTo().performClick()
        compose.onNodeWithText("Z-up").assertDoesNotExist()
        compose.onNodeWithText("Y-up").performClick()
        compose.onNodeWithTag("model_export_save").performClick()
        assertEquals(ModelUpAxis.Y_UP,result!!.resolvedUpAxis())
        assertEquals(ModelUnits.M,result!!.units)
        assertTrue(result!!.moveToOrigin)
    }
    @Test fun materialOptionsStlNoteAndObjPackageAreReviewable() {
        val document=ProjectDocument(Project(ProjectId("colours"),"Colours",0,0))
        var result:ModelExportOptions?=null
        compose.setContent {MaterialTheme {ModelExportDialog(document,ModelExportOptions(),{result=it},{})}}
        compose.onNodeWithTag("route_choice_Colour by").performScrollTo().performClick()
        compose.onNodeWithText("System").performClick()
        compose.onNodeWithTag("export_display_mode").performScrollTo().performClick()
        compose.onNodeWithTag("model_export_save").performClick()
        assertEquals(ModelColourBy.SYSTEM,result!!.colourBy);assertTrue(result!!.exportDisplayMode)
        compose.onNodeWithTag("route_choice_Colour by").performScrollTo().performClick()
        compose.onNodeWithText("Level").performClick()
        compose.onNodeWithTag("route_choice_Format").performScrollTo().performClick()
        compose.onNodeWithText("STL").performClick()
        compose.onNodeWithTag("stl_colourless_note").performScrollTo().assertTextContains("STL is colourless.",substring=true)
        compose.onNodeWithTag("route_choice_Format").performScrollTo().performClick()
        compose.onNodeWithText("OBJ").performClick()
        compose.onNodeWithText("OBJ and its material file are saved together as a ZIP. Extract both files into the same folder before opening the OBJ.").performScrollTo().assertExists()
        compose.onNodeWithTag("model_export_save").performClick()
        assertEquals("zip",result!!.fileExtension);assertEquals(ModelColourBy.LEVEL,result!!.colourBy)
    }

}
