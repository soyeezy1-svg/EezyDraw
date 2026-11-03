package com.eezydraw.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.eezydraw.R
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
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun splashAnimatesBeforeTransitioningToStartMenu() {
        compose.mainClock.autoAdvance = false
        compose.setContent { EezyDrawApp() }

        compose.onNodeWithTag("startup_splash").assertIsDisplayed()
        compose.onNodeWithTag("start_menu").assertDoesNotExist()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("startup_splash").assertIsDisplayed()

        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()

        compose.onNodeWithTag("start_menu").assertIsDisplayed()
        compose.onNodeWithTag("startup_splash").assertDoesNotExist()
    }

    @Test
    fun restoringCompletedStartupDoesNotReplayTheSplash() {
        compose.mainClock.autoAdvance = false
        val restoration = StateRestorationTester(compose)
        restoration.setContent { EezyDrawApp() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        compose.onNodeWithTag("start_menu").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        // Keep the clock paused: a restarted splash cannot silently finish during this assertion.
        compose.onNodeWithTag("start_menu").assertIsDisplayed()
        compose.onNodeWithTag("startup_splash").assertDoesNotExist()
    }

    @Test
    fun settingsSelectionAndOpenDialogSurviveSavedStateRestoration() {
        compose.mainClock.autoAdvance = false
        val restoration = StateRestorationTester(compose)
        restoration.setContent { EezyDrawApp() }
        completeStartup()
        // New Dialog windows require their own layout frames after startup has completed.
        compose.mainClock.autoAdvance = true

        compose.onNodeWithContentDescription(context.getString(R.string.settings)).performClick()
        compose.onNodeWithText(context.getString(R.string.appearance)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.theme_dark)).performClick().assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.theme_light)).assertIsNotSelected()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag("startup_splash").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.appearance)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.theme_dark)).assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.theme_light)).assertIsNotSelected()
        compose.onNodeWithText(context.getString(R.string.close)).performClick()
        compose.onNodeWithText(context.getString(R.string.appearance)).assertDoesNotExist()
        compose.onNodeWithTag("start_menu").assertIsDisplayed()
    }

    @Test
    fun helpExplainsTheAvailableStartupFeaturesAndCanBeClosed() {
        compose.mainClock.autoAdvance = false
        compose.setContent { EezyDrawApp() }
        completeStartup()
        compose.mainClock.autoAdvance = true

        compose.onNodeWithContentDescription(context.getString(R.string.help)).performClick()
        compose.onNodeWithText(context.getString(R.string.start_menu_help)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.close)).performClick()

        compose.onNodeWithText(context.getString(R.string.start_menu_help)).assertDoesNotExist()
        compose.onNodeWithTag("start_menu").assertIsDisplayed()
    }

    private fun completeStartup() {
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        compose.onNodeWithTag("start_menu").assertIsDisplayed()
    }
}
