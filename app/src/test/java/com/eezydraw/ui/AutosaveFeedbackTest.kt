package com.eezydraw.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.eezydraw.R
import com.eezydraw.core.model.LengthUnit
import com.eezydraw.core.model.ProjectTemplate
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Replaces only EGL so the real application snackbar/overlay behavior is exercised. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class AutosaveFeedbackTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var repositories: TestAppRepositories

    @Before fun prepare() { repositories = TestAppRepositories(context) }
    @After fun close() { repositories.close() }

    @Test fun defaultAutosaveDoesNotDisplayASuccessPopup() {
        val model = repositories.newViewModel()
        compose.mainClock.autoAdvance = false
        compose.setContent { EezyDrawApp(model, canvasSurface = { _, _, modifier -> Box(modifier) }) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(3_000)
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { model.create("Quiet drawing", ProjectTemplate.EMPTY, LengthUnit.METRES) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("canvas_ready").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle {
            model.describe("Saved silently")
            model.saveIfDirty()
        }
        compose.waitUntil(10_000) { !model.state.value.dirty && !model.state.value.autosaving }
        compose.waitForIdle()

        compose.onNodeWithText(context.getString(R.string.project_saved_notice)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.project_autosaved_notice)).assertDoesNotExist()
        compose.runOnIdle {
            assertNull(model.state.value.notice)
            assertFalse(model.state.value.busy)
            assertFalse(model.state.value.dirty)
        }
    }
}
