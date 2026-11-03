package com.eezydraw.ui.projects

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.eezydraw.R
import com.eezydraw.core.model.LengthUnit
import com.eezydraw.core.model.Project
import com.eezydraw.core.model.ProjectDocument
import com.eezydraw.core.model.ProjectId
import com.eezydraw.core.model.ProjectSettings
import com.eezydraw.core.model.ProjectTemplate
import com.eezydraw.ui.theme.EezyDrawTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class ProjectScreensTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun creationRequiresANameAndReturnsSelectedTemplateAndUnits() {
        val creations = mutableListOf<Triple<String, ProjectTemplate, LengthUnit>>()
        var busy by mutableStateOf(false)
        compose.setContent {
            EezyDrawTheme {
                CreateProjectScreen(ProjectSettings(), ProjectTemplate.EMPTY, busy,
                    { name, template, units -> creations += Triple(name, template, units) }, {})
            }
        }
        compose.onNodeWithTag("confirm_create_project").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("project_name").performTextInput("  West Wing  ")
        compose.onNodeWithText(context.getString(R.string.project_template_empty)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.project_template_office)).performClick()
        compose.onNodeWithText(context.getString(R.string.project_unit_millimetres)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.project_unit_metres)).performClick()
        compose.onNodeWithTag("confirm_create_project").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(Triple("West Wing", ProjectTemplate.OFFICE, LengthUnit.METRES)), creations)

        compose.runOnIdle { busy = true }
        compose.onNodeWithTag("confirm_create_project").assertIsNotEnabled()
    }

    @Test
    fun blankOrOverlongNamesCannotCreateAProject() {
        var called = false
        compose.setContent {
            EezyDrawTheme {
                CreateProjectScreen(ProjectSettings(), ProjectTemplate.EMPTY, false, { _, _, _ -> called = true }, {})
            }
        }
        compose.onNodeWithTag("project_name").performTextInput("   ")
        compose.onNodeWithTag("confirm_create_project").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("project_name").performScrollTo().performTextReplacement("x".repeat(121))
        compose.onNodeWithTag("confirm_create_project").performScrollTo().assertIsNotEnabled()
        assertEquals(false, called)
    }

    @Test
    fun loadedProjectEditsEnableSaveAndDispatchExport() {
        val names = mutableListOf<String>()
        val descriptions = mutableListOf<String>()
        val units = mutableListOf<LengthUnit>()
        var dirty by mutableStateOf(false)
        var saves = 0
        var exports = 0
        compose.setContent {
            EezyDrawTheme {
                ProjectDetailsScreen(
                    document(), dirty, false,
                    { names += it; dirty = true }, { descriptions += it; dirty = true },
                    { units += it; dirty = true }, { saves++ }, { exports++ }, {},
                )
            }
        }
        compose.onNodeWithTag("save_project").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("project_name").performScrollTo().performTextReplacement("Renamed project")
        compose.onNodeWithTag("project_description").performScrollTo().performTextInput("Coordination notes")
        compose.onNodeWithText(context.getString(R.string.project_unit_millimetres)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.project_unit_feet)).performClick()
        compose.onNodeWithTag("save_project").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("export_project").performClick()
        assertEquals(listOf("Renamed project"), names)
        assertEquals(listOf("Coordination notes"), descriptions)
        assertEquals(listOf(LengthUnit.FEET), units)
        assertEquals(1, saves)
        assertEquals(1, exports)
    }

    @Test
    fun invalidRenameCannotSaveOrExportEvenWhenDocumentIsDirty() {
        val names = mutableListOf<String>()
        compose.setContent {
            EezyDrawTheme {
                ProjectDetailsScreen(document(), true, false, names::add, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("project_name").performTextReplacement("  ")
        compose.onNodeWithTag("save_project").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("export_project").assertIsNotEnabled()
        assertEquals(emptyList<String>(), names)
    }

    private fun document() = ProjectDocument(Project(ProjectId("screen-test"), "Ground", 1, 2))
}
