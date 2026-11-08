package com.eezydraw.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.eezydraw.R
import com.eezydraw.core.model.LengthUnit
import com.eezydraw.core.model.ProjectTemplate
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class CanvasProjectFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var repositories: TestAppRepositories
    private lateinit var model: AppViewModel

    @Before fun prepareModel() {
        repositories = TestAppRepositories(context)
        model = repositories.newViewModel()
    }

    @After fun clearModel() { repositories.close() }

    @Test
    fun creatingAndContinuingAProjectBothOpenItsDrawingCanvas() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EezyDrawApp(model, canvasSurface = { _, _, modifier -> Box(modifier) })
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithTag("start_menu").assertIsDisplayed()
        compose.mainClock.autoAdvance = true

        compose.onNodeWithText(context.getString(R.string.create_new_project))
            .performScrollTo().performClick()
        compose.onNodeWithTag("create_project_screen").assertIsDisplayed()
        compose.onNodeWithTag("project_name").performTextInput("  Coordination plan  ")
        compose.onNodeWithText(context.getString(R.string.project_template_empty))
            .performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.project_template_office)).performClick()
        compose.onNodeWithText(context.getString(R.string.project_unit_millimetres))
            .performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.project_unit_metres)).performClick()
        compose.onNodeWithTag("confirm_create_project").performScrollTo().performClick()

        awaitCanvas()
        compose.onNodeWithTag("cad_bottom_bar").assertIsDisplayed()
        compose.onNodeWithTag("project_name").assertDoesNotExist()
        val projectId = compose.runOnIdle {
            val document = checkNotNull(model.state.value.document)
            assertEquals(AppScreen.PROJECT, model.state.value.screen)
            assertEquals("Coordination plan", document.project.name)
            assertEquals(ProjectTemplate.OFFICE, document.project.template)
            assertEquals(LengthUnit.METRES, document.project.settings.lengthUnit)
            document.project.id
        }

        compose.onNodeWithTag("canvas_close").performClick()
        compose.onNodeWithTag("start_menu").assertIsDisplayed()
        compose.onNodeWithTag("drawing_canvas").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.continue_project))
            .performScrollTo().assertIsEnabled().performClick()

        awaitCanvas()
        compose.onNodeWithTag("cad_bottom_bar").assertIsDisplayed()
        compose.onNodeWithTag("project_name").assertDoesNotExist()
        compose.onNodeWithTag("create_project_screen").assertDoesNotExist()
        compose.runOnIdle {
            val document = checkNotNull(model.state.value.document)
            assertEquals(AppScreen.PROJECT, model.state.value.screen)
            assertEquals(projectId, document.project.id)
            assertEquals(LengthUnit.METRES, document.project.settings.lengthUnit)
            assertEquals(1, repositories.projects.documents.size)
        }
    }

    private fun awaitCanvas() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("canvas_ready").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("drawing_canvas").assertIsDisplayed()
        compose.onNodeWithTag("canvas_surface").assertIsDisplayed()
    }
}
