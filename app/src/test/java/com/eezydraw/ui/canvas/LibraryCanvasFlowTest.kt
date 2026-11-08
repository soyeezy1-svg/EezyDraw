package com.eezydraw.ui.canvas

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.eezydraw.R
import com.eezydraw.core.editing.EditDelta
import com.eezydraw.core.model.*
import com.eezydraw.ui.AppViewModel
import com.eezydraw.ui.EezyDrawApp
import com.eezydraw.ui.TestAppRepositories
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Whole-canvas library flow with production panels, precision input, commands and history. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1024dp-h768dp")
@LooperMode(LooperMode.Mode.PAUSED)
class LibraryCanvasFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var repositories: TestAppRepositories
    private lateinit var model: AppViewModel
    private val frame = AtomicReference<CanvasFrame>()

    @Before fun prepare() {
        repositories = TestAppRepositories(context, AppSettings(autosaveIntervalSeconds = 0,
            autoHidePanelsWhileDrawing = true, keepToolActiveAfterPlacing = true,
            projectDefaults = ProjectSettings(snap = SnapSettings(enabled = false))))
        model = repositories.newViewModel()
    }
    @After fun close() = repositories.close()

    @Test fun libraryNeverAutoHidesAndPlacementReturnsToSelectWithoutChangingItsLayout() {
        startProject("Persistent library placement")
        val item = BuiltInLibrary.items.first { it.symbol == LibrarySymbol.FLOOR_BOX }
        compose.cadLibrary()
        compose.onNodeWithTag("library_search").performTextInput(item.name)
        val opened = layout()
        assertEquals(CanvasGroup.LIBRARY,opened.openGroup)
        compose.cadLibraryItem("library_insert_${item.id.value}").performClick()
        compose.onNodeWithTag("library_panel").assertIsDisplayed()
        compose.onNodeWithTag("library_ghost").assertExists()
        compose.onNodeWithTag("library_mounting_height").assertIsDisplayed()
        assertEquals(opened, layout())
        assertElements(0)
        touchCanvas()
        assertElements(0)
        compose.onNodeWithTag("precision_crosshair").assertExists()
        compose.onNodeWithTag("precision_place").performClick()
        awaitElements(1)
        val placed = document().elements.single()
        assertEquals(item.id, (placed.body as ElementBody.Component).libraryItemId)
        assertEquals(setOf(placed.id), model.state.value.selectedIds)
        compose.onNodeWithTag("library_panel").assertIsDisplayed()
        compose.onNodeWithTag("precision_place").assertDoesNotExist()
        compose.onNodeWithTag("placement_banner").assertDoesNotExist()
        compose.onNodeWithTag("library_mounting_height").assertDoesNotExist()
        assertEquals("Tool completion must keep the explicit panel layout", opened, layout())
        compose.runOnIdle { model.transformSelection(EditDelta(translation = Point3(.5, .25))) }
        compose.onNodeWithTag("library_panel").assertIsDisplayed()
        assertEquals(opened, layout())
        compose.runOnIdle { model.undo(); model.undo() }
        awaitElements(0)
        compose.onNodeWithTag("library_panel").assertIsDisplayed()
        assertEquals(opened, layout())
        compose.runOnIdle { model.redo() }
        awaitElements(1)
        compose.onNodeWithTag("library_panel").assertIsDisplayed()
        assertEquals(opened, layout())
    }

    @Test fun explicitlyCollapsedOrHiddenLibraryStaysThatWayAfterCancelSelectionAndPlacement() {
        startProject("Explicit library state")
        val item = BuiltInLibrary.items.first { it.symbol == LibrarySymbol.FLOOR_BOX }
        compose.cadLibrary()
        compose.onNodeWithTag("library_search").performTextInput(item.name)
        compose.cadLibraryItem("library_insert_${item.id.value}").performClick()
        compose.cadCloseDetail()
        val collapsed = layout()
        compose.onNodeWithTag("library_panel").assertDoesNotExist()
        compose.onNodeWithTag("canvas_library").assertIsDisplayed()
        touchCanvas()
        compose.onNodeWithTag("precision_place").performClick()
        awaitElements(1)
        compose.onNodeWithTag("canvas_library").assertIsDisplayed()
        assertEquals(collapsed, layout())
        compose.cadLibrary()
        compose.cadLibraryItem("library_recent_insert_${item.id.value}").performClick()
        compose.cadCloseDetail()
        val hidden = layout()
        compose.onNodeWithTag("panel_library").assertDoesNotExist()
        compose.onNodeWithTag("precision_cancel").performClick()
        compose.onNodeWithTag("panel_library").assertDoesNotExist()
        val id = document().elements.single().id
        compose.runOnIdle { model.select(emptySet()); model.select(setOf(id)) }
        compose.onNodeWithTag("panel_library").assertDoesNotExist()
        assertEquals(hidden, layout())
        assertElements(1)
    }

    private fun startProject(name: String) {
        compose.mainClock.autoAdvance = false
        compose.setContent { EezyDrawApp(model, canvasSurface = { _, value, modifier ->
            SideEffect { frame.set(value) }; Box(modifier)
        }) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(3_000)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText(context.getString(R.string.create_new_project)).performScrollTo().performClick()
        compose.onNodeWithTag("project_name").performTextInput(name)
        compose.onNodeWithTag("confirm_create_project").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("canvas_ready").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun touchCanvas() {
        compose.cadTap(.4f, .5f)
        compose.waitForIdle()
    }
    private fun assertElements(count: Int) = compose.runOnIdle {
        assertEquals(count, checkNotNull(model.state.value.document).elements.size)
    }
    private fun awaitElements(count: Int) {
        compose.waitUntil(10_000) { model.state.value.document?.elements?.size == count }
        compose.waitForIdle()
    }
    private fun document(): ProjectDocument = compose.runOnIdle { checkNotNull(model.state.value.document) }
    private fun layout(): CanvasLayout = document().project.settings.canvasLayout
}
