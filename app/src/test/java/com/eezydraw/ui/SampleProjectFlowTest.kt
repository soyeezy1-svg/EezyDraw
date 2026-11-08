package com.eezydraw.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.eezydraw.core.editing.TestBuilding
import com.eezydraw.core.model.*
import com.eezydraw.ui.developer.*
import com.eezydraw.ui.settings.SettingsScreen
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28],qualifiers="w1024dp-h768dp") @LooperMode(LooperMode.Mode.PAUSED)
class SampleProjectFlowTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var repos:TestAppRepositories
    private lateinit var model:AppViewModel
    @Before fun start(){repos=TestAppRepositories(ApplicationProvider.getApplicationContext<Context>(),AppSettings(autosaveIntervalSeconds=0));model=repos.newViewModel()}
    @After fun stop(){repos.close()}
    private fun awaitBuilding(levels:Int) {
        try {compose.waitUntil(30000){compose.waitForIdle();model.state.value.error!=null || !model.state.value.busy && model.state.value.document?.levels?.size==levels}}
        catch(error:Throwable){throw AssertionError("Generation timed out: busy=${model.state.value.busy}, progress=${model.state.value.generationProgress}, levels=${model.state.value.document?.levels?.size}, error=${model.state.value.error}, projects=${repos.projects.documents.size}",error)}
    }
    @Test fun sampleProjectsCreatesAnEditableSavedBuildingFromTheStartMenu() {
        compose.mainClock.autoAdvance=false
        compose.setContent {EezyDrawApp(model,canvasSurface={_,_,modifier->Box(modifier)})}
        compose.mainClock.advanceTimeBy(3000);compose.mainClock.autoAdvance=true
        compose.onNodeWithTag("sample_projects").performScrollTo().performClick()
        compose.onNodeWithTag("open_test_building").performClick()
        awaitBuilding(3)
        assertNull("Sample generation error",model.state.value.error)
        compose.waitUntil(30000){compose.onAllNodesWithTag("canvas_ready").fetchSemanticsNodes().isNotEmpty()}
        val doc=checkNotNull(model.state.value.document)
        assertEquals(TestBuilding.NAME,doc.project.name);assertEquals(27,doc.elements.count {it.body is ElementBody.Room})
        assertFalse(model.state.value.dirty);assertEquals(doc,repos.projects.documents[doc.project.id.value])
        assertTrue(doc.issues.any {it.clash!=null});assertNull(model.state.value.stressRun)
    }
    @Test fun developerChoicesCreateASeparateStressProjectAndPreserveTheActiveDraft() {
        compose.setContent {MaterialTheme {SettingsScreen(AppSettings(),{},{},{},{},{},onGenerateStress={model.createTestBuilding(it)})}}
        compose.waitUntil(10000){model.state.value.ready}
        compose.runOnIdle {model.create("Existing project",ProjectTemplate.EMPTY,LengthUnit.MILLIMETRES)}
        compose.waitUntil(10000){model.state.value.document!=null && !model.state.value.busy}
        val old=model.state.value.document!!.project.id
        compose.runOnIdle {model.rename("Keep this edit")}
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("generate_stress_test"))
        compose.onNodeWithTag("generate_stress_test").performClick()
        listOf(5,10,20).forEach {compose.onNodeWithTag("stress_levels_$it").assertExists()}
        compose.onNodeWithTag("stress_levels_5").performClick();compose.onNodeWithTag("stress_generate").performClick()
        awaitBuilding(5)
        assertNull("Stress generation error",model.state.value.error)
        assertEquals("Keep this edit",repos.projects.documents[old.value]!!.project.name)
        assertNotEquals(old,model.state.value.document!!.project.id)
        val run=checkNotNull(model.state.value.stressRun)
        assertEquals(5,run.levels);assertTrue(run.generationMillis>0);assertTrue(run.loadMillis>=0)
        assertNull("No FPS is invented without a rendered frame",run.counter.firstFrameNanos)
    }
    @Test fun stressReportShowsLoadFrameRateAndMemoryAndClosesExplicitly() {
        var closed=false
        val run=StressRun(ProjectId("metrics"),10,1000,1200.0,30.0,RenderFrameCounter(0),StressResult(FrameRate(250,5.0,50.0),100.0,MemoryReading(10485760,2097152,12582912)))
        compose.setContent {MaterialTheme {StressStatus(run,{closed=true})}}
        compose.onNodeWithTag("stress_results").assertExists()
        compose.onNodeWithText("Rendered FPS: 50.0",substring=true).assertIsDisplayed()
        compose.onNodeWithText("Storage load: 30.0 ms",substring=true).assertExists()
        compose.onNodeWithText("Java heap: 10.0 MiB",substring=true).assertExists()
        compose.onNodeWithTag("stress_close").performClick();compose.runOnIdle {assertTrue(closed)}
    }
}
