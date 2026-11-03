package com.eezydraw.ui.start

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.eezydraw.R
import com.eezydraw.ui.theme.EezyDrawTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StartMenuScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun emptyCatalogExplainsRecentProjectsAndDisablesContinue() {
        val actions = mutableListOf<StartMenuAction>()
        compose.setContent {
            EezyDrawTheme { Surface { StartMenuScreen(StartMenuUiState(), actions::add) } }
        }

        compose.onNodeWithText(context.getString(R.string.continue_project)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.recent_projects_empty_title))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.create_new_project))
            .performScrollTo().assertIsEnabled().performClick()

        assertEquals(listOf(StartMenuAction.CreateProject), actions)
    }

    @Test
    fun primaryAndToolbarActionsDispatchTheirOwnDestination() {
        val actions = mutableListOf<StartMenuAction>()
        compose.setContent {
            EezyDrawTheme { Surface { StartMenuScreen(StartMenuUiState(), actions::add) } }
        }

        compose.onNodeWithText(context.getString(R.string.create_new_project))
            .performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.open_from_file))
            .performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.templates))
            .performScrollTo().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.settings)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.help)).performClick()

        assertEquals(
            listOf(
                StartMenuAction.CreateProject,
                StartMenuAction.OpenFile,
                StartMenuAction.Templates,
                StartMenuAction.Settings,
                StartMenuAction.Help,
            ),
            actions,
        )
    }

    @Test
    fun continueAndRecentProjectSelectionDispatchDistinctActions() {
        val project = RecentProject("project-42", "West Wing", "Level 2 · Electrical")
        val actions = mutableListOf<StartMenuAction>()
        lateinit var previewView: View
        compose.setContent {
            previewView = LocalView.current
            EezyDrawTheme {
                Surface { StartMenuScreen(StartMenuUiState(listOf(project)), actions::add) }
            }
        }

        compose.onNodeWithText(context.getString(R.string.continue_project))
            .assertIsEnabled().performClick()
        compose.onNodeWithText(project.name).performScrollTo().performClick()
        compose.onNodeWithContentDescription(
            context.getString(R.string.project_thumbnail_description, project.name),
        ).assertIsDisplayed()

        assertEquals(
            listOf(StartMenuAction.ContinueProject, StartMenuAction.OpenRecent(project.id)),
            actions,
        )
        exportPreview(previewView, "start-menu-tablet.png")
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun compactLayoutKeepsActionsReachableAtLargeFontScale() {
        lateinit var previewView: View
        compose.setContent {
            previewView = LocalView.current
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                EezyDrawTheme { Surface { StartMenuScreen(StartMenuUiState(), {}) } }
            }
        }

        compose.onNodeWithContentDescription(context.getString(R.string.settings)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.help)).assertIsDisplayed()
        exportPreview(previewView, "start-menu-phone-large-text.png")
        for (label in listOf(
            R.string.create_new_project,
            R.string.open_from_file,
            R.string.templates,
            R.string.recent_projects_empty_title,
        )) {
            val text = context.getString(label)
            compose.onNodeWithTag("start_menu_list").performScrollToNode(hasText(text))
            compose.onNodeWithText(text).assertIsDisplayed()
        }
    }

    /** Review artifact from the real composed view; assertions above remain the test's contract. */
    private fun exportPreview(view: View, name: String) {
        compose.runOnIdle {
            runCatching {
                val output = File("build/outputs/previews", name)
                check(output.parentFile.mkdirs() || output.parentFile.isDirectory)
                val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(image))
                    output.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    println("Rendered UI preview: ${output.absolutePath}")
                } finally {
                    image.recycle()
                }
            }.onFailure { failure ->
                System.err.println("UI preview capture unavailable: ${failure.message}")
            }
        }
    }
}
