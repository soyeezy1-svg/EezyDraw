package com.eezydraw.ui.help

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.eezydraw.R
import com.eezydraw.ui.theme.EezyDrawTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class HelpScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun guideGestureReferenceAndAboutAreReachableWithoutSendingFeedback() {
        val sent = mutableListOf<String>()
        compose.setContent { EezyDrawTheme { HelpScreen({}, sent::add) } }
        compose.onNodeWithText(context.getString(R.string.help_create_title)).assertIsDisplayed()
        compose.onNodeWithTag("help_section_gestures").performClick()
        compose.onNodeWithText(context.getString(R.string.help_gesture_scope)).assertIsDisplayed()
        compose.onNodeWithTag("help_section_about").performClick()
        compose.onNodeWithText(context.getString(R.string.help_version)).assertIsDisplayed()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun sharingFeedbackRequiresTextAndAnExplicitClick() {
        val sent = mutableListOf<String>()
        compose.setContent { EezyDrawTheme { HelpScreen({}, sent::add) } }
        compose.onNodeWithTag("help_section_feedback").performClick()
        compose.onNodeWithTag("share_feedback").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("help_feedback_text").performScrollTo().performTextInput("  Please improve routing  ")
        assertTrue(sent.isEmpty())
        compose.onNodeWithTag("share_feedback").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, sent.size)
        assertTrue(sent.single().endsWith("Please improve routing"))
        assertTrue(sent.single().startsWith(context.getString(R.string.help_feedback_subject)))
    }

    @Test
    fun drawingGuideAndKeyboardReferenceAreReachable() {
        compose.setContent { EezyDrawTheme { HelpScreen({}, {}) } }
        listOf(R.string.help_shapes_title, R.string.help_annotations_title, R.string.help_primitives_title,
            R.string.help_editing_title, R.string.help_history_title).forEach { title ->
            compose.onNodeWithText(context.getString(title)).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("help_section_gestures").performClick()
        compose.onNodeWithText(context.getString(R.string.help_shortcuts_title)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.help_shortcuts_body)).performScrollTo().assertIsDisplayed()
    }
}
