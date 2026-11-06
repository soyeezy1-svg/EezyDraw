package com.eezydraw.ui.canvas.panels

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.eezydraw.core.editing.ElementProperties
import com.eezydraw.core.model.BuildingSystem
import com.eezydraw.core.model.Discipline
import com.eezydraw.core.model.Layer
import com.eezydraw.core.model.LayerId
import com.eezydraw.core.model.LengthUnit
import com.eezydraw.core.model.LibraryItem
import com.eezydraw.core.model.LibraryItemId
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.Project
import com.eezydraw.core.model.ProjectDocument
import com.eezydraw.core.model.ProjectId
import com.eezydraw.core.model.Rotation3
import com.eezydraw.core.model.Size3
import com.eezydraw.core.model.SystemId
import com.eezydraw.core.model.ThemeMode
import com.eezydraw.core.model.ViewMode
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
class CanvasPanelsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun groupedToolbarHasFingerTargetsAndFullscreenLeavesOnlyTheRestoreControl() {
        var hidden by mutableStateOf(false)
        var theme by mutableStateOf(ThemeMode.LIGHT)
        val actions = mutableListOf<String>()
        compose.setContent { EezyDrawTheme(theme) {
            CanvasToolbars({ actions += "draw" }, { actions += "edit" }, { actions += "view" },
                { actions += "measure" }, { actions += "reference" }, { actions += "list" },
                { actions += "library" }, { hidden = !hidden }, hidden)
        } }
        listOf("draw", "edit", "view", "measure", "library").forEach { group ->
            compose.onNodeWithTag("toolbar_$group").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithTag("toolbar_draw").performClick()
        compose.onNodeWithTag("toolbar_edit").performClick()
        compose.onNodeWithTag("toolbar_view").performClick()
        compose.onNodeWithTag("toolbar_measure").performClick()
        compose.onNodeWithTag("toolbar_reference_dimension").performClick()
        compose.onNodeWithTag("toolbar_measure").performClick()
        compose.onNodeWithTag("toolbar_measure_distance").performClick()
        compose.onNodeWithTag("toolbar_measure").performClick()
        compose.onNodeWithTag("toolbar_measurements").performClick()
        compose.onNodeWithTag("toolbar_library").performClick()
        assertEquals(listOf("draw", "edit", "view", "reference", "measure", "list", "library"), actions)
        compose.onNodeWithTag("canvas_hide_panels").performClick()
        compose.onNodeWithTag("toolbar_draw").assertDoesNotExist()
        compose.onNodeWithTag("toolbar_measure").assertDoesNotExist()
        compose.runOnIdle { theme = ThemeMode.DARK }
        compose.onNodeWithTag("canvas_show_panels").assertWidthIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("toolbar_library").assertExists()
    }

    @Test
    fun allSixViewModesAndEachVisibilityToggleReachTheDocumentCallbacks() {
        val id = ProjectId("p")
        var document by mutableStateOf(ProjectDocument(Project(id, "Views", 0, 0),
            layers = listOf(Layer(LayerId("drawing"), id, "Drawing")),
            systems = listOf(BuildingSystem(SystemId("power"), id, "Power", Discipline.ELECTRICAL, 0xFF4477AA))))
        val modes = mutableListOf<ViewMode>()
        compose.setContent { EezyDrawTheme {
            ViewControls(document, document.project.settings.viewMode,
                onMode = { mode ->
                    modes += mode
                    document = document.copy(project = document.project.copy(settings = document.project.settings.copy(viewMode = mode)))
                }, onLayerVisibility = { layer, visible ->
                    document = document.copy(layers = document.layers.map { if (it.id == layer) it.copy(visible = visible) else it })
                }, onSystemVisibility = { system, visible ->
                    document = document.copy(systems = document.systems.map { if (it.id == system) it.copy(visible = visible) else it })
                }, onMeasurementsVisibility = { visible ->
                    document = document.copy(project = document.project.copy(settings = document.project.settings.copy(showMeasurements = visible)))
                })
        } }
        canvasViewModes.forEach { mode ->
            compose.onNodeWithTag("canvas_view_mode").performClick()
            compose.onNodeWithTag("view_mode_${mode.name.lowercase()}").performClick()
            assertEquals(mode, document.project.settings.viewMode)
        }
        assertEquals(canvasViewModes, modes)
        compose.onNodeWithTag("canvas_visibility").performClick()
        listOf("visibility_layer_drawing", "visibility_system_power", "visibility_measurements").forEach { tag ->
            compose.onNodeWithTag(tag).performScrollTo().assertIsOn().performClick().assertIsOff()
        }
        assertTrue(document.layers.none { it.visible })
        assertTrue(document.systems.none { it.visible })
        assertTrue(!document.project.settings.showMeasurements)
    }

    @Test
    fun sidePropertiesKeepsAnAtomicDraftThroughCollapseAndRestoration() {
        val source = ElementProperties(Point3(1.0, 2.0), Rotation3(), Point3(1.0, 1.0))
        val edits = mutableListOf<ElementProperties>()
        val restore = StateRestorationTester(compose)
        restore.setContent { EezyDrawTheme { Box(Modifier.size(600.dp, 650.dp)) {
            PropertiesSidePanel(source, LengthUnit.MILLIMETRES, 2, edits::add, {})
        } } }
        compose.onNodeWithTag("property_x").performTextReplacement("1500")
        compose.onNodeWithTag("properties_toggle").performClick()
        compose.onNodeWithTag("property_x").assertDoesNotExist()
        assertTrue(edits.isEmpty())
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("properties_toggle").performClick()
        compose.onNodeWithTag("property_x").assertTextContains("1500")
        compose.onNodeWithTag("property_height").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("properties_apply").performClick()
        assertEquals(1, edits.size)
        assertEquals(1.5, edits.single().position.x, 1e-12)
        assertEquals(source.size, edits.single().size)
    }

    @Test
    fun libraryOffersBuiltInsAndProjectSpecificDefinitions() {
        var items by mutableStateOf(emptyList<LibraryItem>())
        val inserted = mutableListOf<LibraryItemId>()
        compose.setContent { EezyDrawTheme { LibraryPanel(items, inserted::add, {}) } }
        compose.onNodeWithTag("library_search").assertExists()
        compose.onNodeWithTag("library_folder_category_Electrical").assertExists()
        val socket = LibraryItem(LibraryItemId("socket"), "Socket", Discipline.ELECTRICAL, "Electrical", Size3(.1, .05, .1))
        compose.runOnIdle { items = listOf(socket) }
        compose.onNodeWithTag("library_insert_socket").performClick()
        assertEquals(listOf(socket.id), inserted)
    }
}
