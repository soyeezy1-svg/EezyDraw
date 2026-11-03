package com.eezydraw.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.eezydraw.R
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class StartupFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var repositories: TestAppRepositories
    private lateinit var model: AppViewModel

    @Before fun prepareModel() {
        repositories = TestAppRepositories(context)
        model = repositories.newViewModel()
    }

    @After fun clearModel() { repositories.close() }

    @Test fun splashAnimatesBeforeTransitioningToStartMenu() {
        compose.mainClock.autoAdvance = false
        compose.setContent { EezyDrawApp(model) }
        compose.onNodeWithTag("startup_splash").assertIsDisplayed()
        compose.onNodeWithTag("start_menu").assertDoesNotExist()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("startup_splash").assertIsDisplayed()

        completeStartup()

        compose.onNodeWithTag("startup_splash").assertDoesNotExist()
    }

    @Test fun restoringCompletedStartupDoesNotReplayTheSplash() {
        compose.mainClock.autoAdvance = false
        val restoration = StateRestorationTester(compose)
        restoration.setContent { EezyDrawApp(model) }
        completeStartup()

        restoration.emulateSavedInstanceStateRestore()

        // The clock remains paused: a mistakenly replayed splash cannot silently finish.
        compose.onNodeWithTag("start_menu").assertIsDisplayed()
        compose.onNodeWithTag("startup_splash").assertDoesNotExist()
    }

    @Test fun fullSettingsScreenSurvivesRestorationAndReturnsWithoutReplayingSplash() {
        compose.mainClock.autoAdvance = false
        val restoration = StateRestorationTester(compose)
        restoration.setContent { EezyDrawApp(model) }
        completeStartup()
        compose.mainClock.autoAdvance = true

        compose.onNodeWithContentDescription(context.getString(R.string.settings)).performClick()
        compose.onNodeWithTag("settings_screen").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("settings_screen").assertIsDisplayed()
        compose.onNodeWithTag("startup_splash").assertDoesNotExist()
        compose.onNodeWithTag("settings_back").performClick()

        compose.onNodeWithTag("start_menu").assertIsDisplayed()
        compose.onNodeWithTag("startup_splash").assertDoesNotExist()
    }

    @Test fun fullHelpScreenShowsTheGuideAndReturnsToTheMenu() {
        compose.mainClock.autoAdvance = false
        compose.setContent { EezyDrawApp(model) }
        completeStartup()
        compose.mainClock.autoAdvance = true

        compose.onNodeWithContentDescription(context.getString(R.string.help)).performClick()
        compose.onNodeWithTag("help_screen").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.help_create_title)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.help_back)).performClick()

        compose.onNodeWithTag("help_screen").assertDoesNotExist()
        compose.onNodeWithTag("start_menu").assertIsDisplayed()
        compose.onNodeWithTag("startup_splash").assertDoesNotExist()
    }

    private fun completeStartup() {
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        compose.onNodeWithTag("start_menu").assertIsDisplayed()
    }
}
