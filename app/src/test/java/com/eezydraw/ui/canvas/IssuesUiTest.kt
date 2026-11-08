package com.eezydraw.ui.canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eezydraw.core.model.*
import com.eezydraw.core.geometry.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
@RunWith(RobolectricTestRunner::class) @Config(sdk=[28],qualifiers="w1024dp-h1024dp") @LooperMode(LooperMode.Mode.PAUSED)
class IssuesUiTest {
 @get:Rule val compose=createComposeRule()
 private val p=ProjectId("p");private val issue=Issue(IssueId("i"),p,"Leak",1,position=Point3())
 private val doc=ProjectDocument(Project(p,"Review",0,0),issues=listOf(issue))
 @Test fun fieldsValidationAndSaving(){var saved:Issue?=null;compose.setContent {MaterialTheme {IssueEditor(issue,doc,{saved=it},{},{},{},{_,_->},{_,_->},{})}};compose.onNodeWithText("Title").performTextClearance();compose.onNodeWithText("Save issue").assertIsNotEnabled();compose.onNodeWithText("Title").performTextInput("Repair pipe");compose.onNodeWithText("Due date (YYYY-MM-DD)").performScrollTo().performTextInput("2026-12-12");compose.onNodeWithText("Save issue").performClick();assertEquals("Repair pipe",saved!!.title);assertEquals("2026-12-12",saved!!.dueDate)}
 @Test fun pinsProjectInPlanAndOrbitAndRespectHideLevels(){val frame=CanvasFrame(Viewport2D(),OrbitCamera());val points=issuePins(doc,frame,400,300);assertEquals(Point2(200.0,150.0),points[issue.id]);assertEquals(1,issuePins(doc,frame.copy(is3D=true),400,300).size);assertTrue(issuePins(doc.copy(issues=listOf(issue.copy(hidden=true))),frame,400,300).isEmpty());val level=LevelId("g");assertTrue(issuePins(doc.copy(issues=listOf(issue.copy(levelId=level)),project=doc.project.copy(settings=doc.project.settings.copy(hiddenLevelIds=setOf(level)))),frame,400,300).isEmpty())}
}
