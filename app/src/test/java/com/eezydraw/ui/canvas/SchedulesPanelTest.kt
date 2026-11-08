package com.eezydraw.ui.canvas

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28],qualifiers="w1024dp-h1024dp") @LooperMode(LooperMode.Mode.PAUSED)
class SchedulesPanelTest {
    @get:Rule val compose=createComposeRule()
    @Test fun optionsValidateAllowanceCurrencyAndSave() {
        var saved:TakeoffSettings?=null
        compose.setContent {MaterialTheme {TakeoffOptions(TakeoffSettings(),{saved=it},{})}}
        compose.onNodeWithText("Length allowance (%)").performTextReplacement("-1")
        compose.onNodeWithText("Save quantities").assertIsNotEnabled()
        compose.onNodeWithText("Length allowance (%)").performTextReplacement("10")
        compose.onNodeWithText("Currency (ISO code)").performTextReplacement("MYR")
        compose.onNodeWithText("Save quantities").performClick()
        assertEquals(10.0,saved!!.allowancePercent,0.0);assertEquals("MYR",saved!!.currency)
    }
    @Test fun enteringTotalCostDerivesRateWithoutChangingQuantity() {
        val row=com.eezydraw.core.geometry.ScheduleRow(emptyMap(),emptySet(),TakeoffUnit.EACH,2.0,0.0,0.0,0,0.0,"key",emptySet(),null,null)
        var saved:Double?=null
        compose.setContent {MaterialTheme {RateEditor(row,"USD",{rate,_->saved=rate},{})}}
        compose.onNodeWithText("Total cost (sets unit rate)").performTextInput("30")
        compose.onNodeWithText("Save rate").performClick()
        assertEquals(15.0,saved!!,0.0)
    }
    @Test fun modelRowsHighlightObjectsAndShareCurrentSchedule() {
        val p=ProjectId("p");val layer=LayerId("base")
        val item=LibraryItem(LibraryItemId("item"),"Socket",Discipline.ELECTRICAL,"Devices",Size3(.1,.1,.1))
        val element=Element(ElementId("socket"),p,layer,ElementBody.Component(item.id,item.defaultSize),name="Socket A")
        val document=ProjectDocument(Project(p,"Schedules",0,0),layers=listOf(Layer(layer,p,"Base")),libraryItems=listOf(item),elements=listOf(element))
        var selected:Set<ElementId> = emptySet();var shared:ScheduleDefinition?=null
        compose.setContent {MaterialTheme {SchedulesPanel(document,true,{selected=it},{},{_,_,_,_->},{definition,_,_->shared=definition})}}
        compose.waitUntil(10000){compose.onAllNodesWithText("Socket A").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Socket A").performClick();assertEquals(setOf(element.id),selected)
        compose.onNodeWithText("Share CSV").performClick();assertEquals("equipment",shared!!.id)
    }
}
