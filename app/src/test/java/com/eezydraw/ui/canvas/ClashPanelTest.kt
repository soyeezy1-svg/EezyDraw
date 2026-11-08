package com.eezydraw.ui.canvas

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1024dp-h1024dp")
@LooperMode(LooperMode.Mode.PAUSED)
class ClashPanelTest {
    @get:Rule val compose=createComposeRule()
    private val p=ProjectId("p");private val level=LevelId("g");private val layer=LayerId("l")
    private val a=Element(ElementId("a"),p,layer,ElementBody.DrawingShape(Shape.Box(Size3(1.0,1.0,1.0))),level)
    private val b=a.copy(id=ElementId("b"))
    private val issue=Issue(IssueId("clash-1"),p,"Clash",1,elementIds=setOf(a.id,b.id),clash=Clash(a.id,b.id,ClashKind.HARD,Point3(),overlap=.1,checkKey="all",levelIds=setOf(level)))
    private val doc=ProjectDocument(Project(p,"Clashes",0,0),levels=listOf(Level(level,p,"Ground",0.0,.2,3.0)),layers=listOf(Layer(layer,p,"Base")),elements=listOf(a,b),issues=listOf(issue))
    @Test fun groupPresetScopeAndRunUseExplicitOptions() {
        var result:ClashCheckOptions?=null
        compose.setContent {MaterialTheme {ClashCheckDialog(doc,level.value,{result=it},{})}}
        compose.onNodeWithTag("route_choice_Preset").performScrollTo().performClick()
        compose.onNodeWithText("Electrical vs HVAC").performClick()
        compose.onNodeWithTag("route_choice_Check levels").performScrollTo().performClick()
        compose.onNodeWithText("Current level").performClick()
        compose.onNodeWithTag("clash_length_Ignore overlap under").performScrollTo().performTextReplacement("-1")
        compose.onNodeWithTag("clash_run").assertIsNotEnabled()
        compose.onNodeWithTag("clash_length_Ignore overlap under").performTextReplacement("1")
        compose.onNodeWithTag("clash_run").performClick()
        assertEquals(Discipline.ELECTRICAL,result!!.first.category);assertEquals(Discipline.HVAC,result!!.second.category)
        assertEquals(level,result!!.levelId);assertTrue(result!!.visibleOnly);assertEquals(.001,result!!.tolerance,0.0)
    }
    @Test fun focusCancelAndStatusNoteUseTheRealPanelCallbacks() {
        var focus:Issue?=null;var cancelled=false;var saved:Triple<IssueId,ClashStatus,String>?=null
        compose.setContent {MaterialTheme {
            ClashPanel(doc,.5f,"Checking",{},{cancelled=true},{focus=it},{},true,{}, {id,status,note->saved=Triple(id,status,note)})
        }}
        compose.onNodeWithTag("clash_cancel").performScrollTo().performClick();assertTrue(cancelled)
        compose.onNodeWithTag("clash_focus_clash-1").performScrollTo().performClick();assertEquals(issue,focus)
        compose.onNodeWithText("Status and note").performScrollTo().performClick()
        compose.onNodeWithTag("route_choice_Status").performClick();compose.onNodeWithText("APPROVED").performClick()
        compose.onNodeWithTag("clash_note").performTextReplacement("Accepted opening")
        compose.onNodeWithText("Save status").performClick()
        assertEquals(Triple(issue.id,ClashStatus.APPROVED,"Accepted opening"),saved)
    }
    @Test fun typeSystemLevelAndSortFiltersAreIndependent() {
        val second=issue.copy(id=IssueId("clash-2"),clash=issue.clash!!.copy(kind=ClashKind.CLEARANCE,distance=.02,levelIds=setOf(LevelId("upper"))))
        val source=doc.copy(issues=listOf(issue,second))
        assertEquals(listOf(issue),filteredClashes(source,ClashKind.HARD,null,null,ClashSort.ID))
        assertEquals(listOf(second),filteredClashes(source,null,null,LevelId("upper"),ClashSort.LEVEL))
        assertTrue(filteredClashes(source,null,SystemId("other"),null,ClashSort.ID).isEmpty())
        assertEquals(listOf(issue,second),filteredClashes(source,null,null,null,ClashSort.DISTANCE))
    }
}
