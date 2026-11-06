package com.eezydraw.ui.canvas.panels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.eezydraw.core.model.*
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
class LibraryPanelTest {
    @get:Rule val compose = createComposeRule()
    private val outlet = LibraryItem(LibraryItemId("test-outlet"), "Project data outlet", Discipline.ICT,
        "ICT / low current", Size3(.086, .035, .086), manufacturer = "Example", model = "Data100")

    @Test fun searchMatchesCategoryManufacturerAndModelAndClearingRestoresTheCatalog() {
        compose.setContent { EezyDrawTheme { LibraryPanel(listOf(outlet), {}, {}) } }
        compose.onNodeWithTag("library_search").performTextReplacement("  EXAMPLE data100  ")
        compose.onNodeWithTag("library_insert_test-outlet").assertExists()
        compose.onNodeWithText("86 × 35 × 86 mm").assertExists()
        compose.onNodeWithTag("library_folder_category_Electrical").assertDoesNotExist()
        compose.onNodeWithTag("library_search").performTextReplacement("no such device")
        compose.onNodeWithTag("library_no_results").assertExists()
        compose.onNodeWithTag("library_insert_test-outlet").assertDoesNotExist()
        compose.onNodeWithTag("library_search_clear").performClick()
        compose.onNodeWithTag("library_folder_category_Electrical").assertExists()
    }

    @Test fun favoritesAndRecentlyUsedHaveTheirOwnFoldersAndSelectionDoesNotDismiss() {
        var state by mutableStateOf(LibraryState())
        val picks = mutableListOf<LibraryItemId>()
        var active by mutableStateOf<LibraryItemId?>(null)
        var dismisses = 0
        compose.setContent { EezyDrawTheme { LibraryPanel(listOf(outlet), { picks += it; active = it }, { dismisses++ },
            state = state, onStateChange = { state = it }, activeItemId = active) } }
        compose.onNodeWithTag("library_search").performTextReplacement("Project data outlet")
        compose.onNodeWithTag("library_favorite_test-outlet").assertIsOff().performClick()
        compose.runOnIdle { assertEquals(setOf(outlet.id), state.favoriteIds) }
        compose.onNodeWithTag("library_favorites_insert_test-outlet").performClick()
        assertEquals(listOf(outlet.id), picks)
        compose.onNodeWithTag("library_favorites_insert_test-outlet").assertIsSelected()
        compose.runOnIdle { active = null }
        compose.onNodeWithTag("library_favorites_insert_test-outlet").assertIsNotSelected()
        assertEquals(0, dismisses)
        compose.runOnIdle { state = state.recordUsed(outlet.id) }
        compose.onNodeWithTag("library_recent_insert_test-outlet").assertExists()
        compose.onNodeWithTag("library_favorites_favorite_test-outlet").assertIsOn().performClick()
        compose.onNodeWithTag("library_favorites_insert_test-outlet").assertDoesNotExist()
        compose.runOnIdle { assertTrue(state.favoriteIds.isEmpty()) }
    }

    @Test fun folderCollapseSearchAndViewModeSurviveStateRestoration() {
        var state by mutableStateOf(LibraryState())
        val edits = mutableListOf<LibraryState>()
        val restore = StateRestorationTester(compose)
        restore.setContent { EezyDrawTheme { LibraryPanel(listOf(outlet), {}, {}, state = state,
            onStateChange = { state = it; edits += it }) } }
        compose.onNodeWithTag("library_search").performTextReplacement("Project data outlet")
        compose.onNodeWithTag("library_list").performClick().assertIsOn()
        compose.onNodeWithTag("library_folder_category_ICT / low current").performClick()
        compose.onNodeWithTag("library_insert_test-outlet").assertDoesNotExist()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("library_search").assertTextContains("Project data outlet")
        compose.onNodeWithTag("library_list").assertIsOn()
        compose.onNodeWithTag("library_insert_test-outlet").assertDoesNotExist()
        compose.onNodeWithTag("library_folder_category_ICT / low current").performClick()
        compose.onNodeWithTag("library_insert_test-outlet").assertExists()
        compose.onNodeWithTag("library_grid").performClick().assertIsOn()
        assertEquals(listOf(LibraryViewMode.LIST, LibraryViewMode.GRID), edits.map { it.viewMode })
    }

    @Test fun hundredsOfDefinitionsComposeOnlyTheVisibleRowsAndCanBeSearched() {
        val items = (0..499).map { index -> outlet.copy(id = LibraryItemId("bulk-$index"), name = "Device $index", category = "Bulk") }
        var state by mutableStateOf(LibraryState(viewMode = LibraryViewMode.LIST))
        compose.setContent { EezyDrawTheme { LibraryPanel(items, {}, {}, state = state, onStateChange = { state = it }) } }
        compose.onNodeWithTag("library_search").performTextReplacement("Bulk")
        compose.onNodeWithTag("library_insert_bulk-0").assertExists()
        compose.onNodeWithTag("library_insert_bulk-499").assertDoesNotExist()
        compose.onNodeWithTag("library_items").performScrollToNode(hasTestTag("library_insert_bulk-499"))
        compose.onNodeWithTag("library_insert_bulk-499").assertExists()
        compose.onNodeWithTag("library_search").performTextReplacement("Device 499")
        compose.onNodeWithTag("library_insert_bulk-499").assertExists()
    }

    @Test fun expandedCategoriesAndRouteFittingsCanBeSearchedFavoritedAndChosen() {
        val categories = listOf("Fire alarm and safety", "Mechanical / HVAC", "Plumbing and drainage",
            "Architecture", "Civil and structure")
        val examples = categories.map { category -> BuiltInLibrary.items.first { it.category == category } } +
            BuiltInLibrary.items.first { it.fitting?.routeKind == RouteKind.PIPE && it.fitting?.kind == FittingKind.REDUCER }
        var state by mutableStateOf(LibraryState())
        val selected = mutableListOf<LibraryItemId>()
        var dismisses = 0
        compose.setContent { EezyDrawTheme { LibraryPanel(emptyList(), selected::add, { dismisses++ },
            state = state, onStateChange = { state = it }) } }

        examples.forEach { item ->
            compose.onNodeWithTag("library_search").performTextReplacement(item.name)
            compose.onNodeWithTag("library_items").performScrollToNode(hasTestTag("library_insert_${item.id.value}"))
            compose.onNodeWithTag("library_favorite_${item.id.value}").assertIsOff().performClick()
            compose.onNodeWithTag("library_favorites_insert_${item.id.value}").performClick()
        }
        compose.runOnIdle {
            assertEquals(examples.map { it.id }, selected)
            assertEquals(examples.map { it.id }.toSet(), state.favoriteIds)
            assertEquals(0, dismisses)
        }
    }
}
