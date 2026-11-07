package com.eezydraw.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.eezydraw.core.editing.EditDelta
import com.eezydraw.core.editing.readLibraryProperties
import com.eezydraw.core.editing.readProperties
import com.eezydraw.core.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Uses the production ViewModel command/history and save baseline; core:data checks real files. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class AppViewModelLibraryTest {
    @get:Rule val main = AppMainDispatcherRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val repositories = mutableListOf<TestAppRepositories>()
    @After fun closeModels() = repositories.forEach(TestAppRepositories::close)

    @Test fun builtInFloorPlacementCarriesOnlyUsedDefinitionsAndUndoRedoIsAtomic() = runTest {
        val store = fixture()
        val model = store.newViewModel()
        runCurrent()
        model.create("Floor components", ProjectTemplate.EMPTY, LengthUnit.MILLIMETRES)
        runCurrent()
        val initial = model.state.value.document!!
        val level = Level(LevelId("raised-floor"), initial.project.id, "Raised floor", 4.0, .2, 3.8)
        store.projects.documents[initial.project.id.value] = initial.copy(levels = listOf(level))
        model.open(initial.project.id.value)
        runCurrent()
        val baseline = model.state.value.document!!
        val item = BuiltInLibrary.items.first { it.symbol == LibrarySymbol.FLOOR_BOX }

        assertTrue(model.insertLibraryItem(item.id, Point3(2.0, 3.0, 99.0), level.id.value))
        val placed = model.state.value.document!!
        val element = placed.elements.single()
        assertEquals(setOf(element.id), model.state.value.selectedIds)
        assertEquals(level.id, element.levelId)
        assertEquals(Point3(2.0, 3.0, level.elevation), element.transform.translation)
        assertEquals(item.defaultSize, (element.body as ElementBody.Component).size)
        assertEquals(listOf(item.id), placed.libraryItems.map { it.id })
        assertEquals(listOf(item.id), placed.libraryState.recentIds)
        assertTrue(model.state.value.canUndo)
        assertTrue(model.state.value.dirty)
        model.undo()
        assertEquals(baseline.copy(libraryState = placed.libraryState), model.state.value.document)
        assertTrue("Recent browsing state remains saved even after undoing geometry", model.state.value.dirty)
        assertFalse(model.state.value.canUndo)
        model.redo()
        assertEquals(placed, model.state.value.document)
    }

    @Test fun wallPlacementUsesFinishedFaceAndDefaultHeightOnTheSelectedLevel() = runTest {
        val store = fixture()
        val model = store.newViewModel()
        runCurrent()
        model.create("Wall devices", ProjectTemplate.EMPTY, LengthUnit.MILLIMETRES)
        runCurrent()
        val initial = model.state.value.document!!
        val level = Level(LevelId("level-two"), initial.project.id, "Level 2", 4.0, .2, 3.8)
        val wall = Element(ElementId("wall"), initial.project.id, initial.layers.single().id,
            ElementBody.Wall(Point2(0.0, 0.0), Point2(8.0, 0.0), .2, 3.6),
            levelId = level.id, transform = Transform3(translation = Point3(z = 4.0)))
        store.projects.documents[initial.project.id.value] = initial.copy(levels = listOf(level), elements = listOf(wall))
        model.open(initial.project.id.value)
        runCurrent()
        val item = BuiltInLibrary.items.first { it.symbol == LibrarySymbol.SWITCH }
        assertEquals(1.2, item.defaultMountingHeight, 1e-9)

        assertTrue(model.insertLibraryItem(item.id, Point3(3.0, .7, 99.0), level.id.value))
        val placed = model.state.value.document!!
        val switch = placed.elements.single { it.id in model.state.value.selectedIds }
        val body = switch.body as ElementBody.Component
        assertEquals(wall.id, body.host?.hostElementId)
        assertEquals(.1, switch.transform.translation.y + body.size.depth, 1e-9)
        assertEquals(HostMode.RECESSED,body.host?.mode)
        assertEquals(5.2, switch.transform.translation.z, 1e-9)
        assertEquals(0.0, switch.transform.rotation.z, 1e-9)
        assertEquals(1.2, readLibraryProperties(placed, switch).mountingHeight, 1e-9)
        model.undo()
        assertEquals(listOf(wall), model.state.value.document!!.elements)
        model.redo()
        assertEquals(placed, model.state.value.document)
    }

    @Test fun editingIsInstanceLocalAndUpdateAllPreservesOtherLocationsAndTypes() = runTest {
        val store = fixture()
        val model = store.newViewModel()
        runCurrent()
        model.create("Rack parameters", ProjectTemplate.EMPTY, LengthUnit.MILLIMETRES)
        runCurrent()
        val rack = BuiltInLibrary.items.first { it.symbol == LibrarySymbol.RACK && it.name.contains("42") }
        val floorBox = BuiltInLibrary.items.first { it.symbol == LibrarySymbol.FLOOR_BOX }
        assertTrue(model.insertLibraryItem(rack.id, Point3(1.0, 2.0), null))
        val firstId = model.state.value.selectedIds.single()
        assertTrue(model.insertLibraryItem(rack.id, Point3(7.0, 8.0), null))
        val secondId = model.state.value.selectedIds.single()
        assertTrue(model.insertLibraryItem(floorBox.id, Point3(-4.0, -3.0), null))
        val floorBoxId = model.state.value.selectedIds.single()
        val before = model.state.value.document!!
        val second = before.elements.single { it.id == secondId }
        val unrelated = before.elements.single { it.id == floorBoxId }
        model.select(setOf(firstId))
        val first = before.elements.single { it.id == firstId }
        val original = readLibraryProperties(before, first)
        val edited = original.copy(size = Size3(.9, 1.1, 2.2), material = "Powder-coated steel",
            colourArgb = 0xFF264B78, system = "ICT", manufacturer = "Example manufacturer", model = "R42",
            nameTag = "Rack A-01")
        val transform = readProperties(before, first).copy(size = Point3(.9, 1.1, 2.2))

        model.updateLibraryProperties(transform, edited)
        val local = model.state.value.document!!
        assertNull(model.state.value.error)
        assertEquals(edited, readLibraryProperties(local, local.elements.single { it.id == firstId }))
        assertEquals(second, local.elements.single { it.id == secondId })
        assertEquals(unrelated, local.elements.single { it.id == floorBoxId })
        assertEquals(rack, local.libraryItems.single { it.id == rack.id })
        model.undo()
        assertEquals(before, model.state.value.document)
        model.redo()
        assertEquals(local, model.state.value.document)

        model.updateLibraryProperties(transform, edited, updateAll = true)
        val all = model.state.value.document!!
        val updatedSecond = all.elements.single { it.id == secondId }
        assertEquals(edited, readLibraryProperties(all, updatedSecond))
        assertEquals(second.transform.translation, updatedSecond.transform.translation)
        assertEquals(second.transform.rotation, updatedSecond.transform.rotation)
        assertEquals(unrelated, all.elements.single { it.id == floorBoxId })
        model.undo()
        assertEquals(local, model.state.value.document)
        model.redo()
        assertEquals(all, model.state.value.document)
    }

    @Test fun resetAndLockGuardUseTheSameUndoHistoryAndResetOnlyTheSelectedInstance() = runTest {
        val store = fixture()
        val model = store.newViewModel()
        runCurrent()
        model.create("Reset and lock", ProjectTemplate.EMPTY, LengthUnit.METRES)
        runCurrent()
        val item = BuiltInLibrary.items.first { it.symbol == LibrarySymbol.FLOOR_BOX }
        assertTrue(model.insertLibraryItem(item.id, Point3(1.0, 2.0), null))
        val firstId = model.state.value.selectedIds.single()
        assertTrue(model.insertLibraryItem(item.id, Point3(5.0, 6.0), null))
        val other = model.state.value.document!!.elements.single { it.id in model.state.value.selectedIds }
        model.select(setOf(firstId))
        val before = model.state.value.document!!
        val first = before.elements.single { it.id == firstId }
        val defaults = readLibraryProperties(before, first)
        val edited = defaults.copy(size = Size3(.5, .4, .2), manufacturer = "Maker", model = "B",
            nameTag = "Custom floor box")
        model.updateLibraryProperties(readProperties(before, first).copy(size = Point3(.5, .4, .2)), edited)
        val customized = model.state.value.document!!
        model.setSelectionLockMode(LockMode.ALL)
        val locked = model.state.value.document!!
        model.resetLibraryProperties()
        assertEquals(locked, model.state.value.document)
        assertNotNull(model.state.value.error)
        model.setSelectionLockMode(LockMode.NONE)
        model.resetLibraryProperties()
        val reset = model.state.value.document!!
        assertEquals(defaults, readLibraryProperties(reset, reset.elements.single { it.id == firstId }))
        assertEquals(other, reset.elements.single { it.id == other.id })
        assertEquals(first.transform.translation, reset.elements.single { it.id == firstId }.transform.translation)
        model.undo()
        assertEquals(customized.elements, model.state.value.document!!.elements)
        model.redo()
        assertEquals(reset, model.state.value.document)
    }

    @Test fun saveAndFreshOpenRetainBrowsingStateTypeDefinitionsAndInstanceParameters() = runTest {
        val store = fixture()
        val model = store.newViewModel()
        runCurrent()
        model.create("Saved library", ProjectTemplate.EMPTY, LengthUnit.MILLIMETRES)
        runCurrent()
        val item = BuiltInLibrary.items.first { it.symbol == LibrarySymbol.FLOOR_BOX }
        val unused = BuiltInLibrary.items.first { it.symbol == LibrarySymbol.WIFI }
        val preferences = LibraryState(favoriteIds = setOf(item.id, unused.id), viewMode = LibraryViewMode.LIST)
        model.setLibraryState(preferences)
        assertTrue(model.insertLibraryItem(item.id, Point3(2.0, 3.0), null))
        val document = model.state.value.document!!
        val component = document.elements.single()
        val customized = readLibraryProperties(document, component).copy(manufacturer = "Sample", model = "FB-6",
            nameTag = "FB 06", colourArgb = 0xFF006699)
        model.updateLibraryProperties(readProperties(document, component), customized)
        model.transformSelection(EditDelta(translation = Point3(1.0, -1.0)))
        val edited = model.state.value.document!!
        model.save()
        runCurrent()
        assertFalse(model.state.value.dirty)
        val saved = model.state.value.document!!

        val reopened = store.newViewModel()
        runCurrent()
        reopened.open(saved.project.id.value)
        runCurrent()
        val loaded = reopened.state.value.document!!
        assertEquals(edited.elements, loaded.elements)
        assertEquals(edited.libraryItems, loaded.libraryItems)
        assertEquals(preferences.favoriteIds, loaded.libraryState.favoriteIds)
        assertEquals(LibraryViewMode.LIST, loaded.libraryState.viewMode)
        assertEquals(listOf(item.id), loaded.libraryState.recentIds)
        assertEquals(customized, readLibraryProperties(loaded, loaded.elements.single()))
        assertEquals(Point3(3.0, 2.0, 0.0), loaded.elements.single().transform.translation)
        assertFalse(reopened.state.value.dirty)
        assertFalse(reopened.state.value.canUndo)
        assertFalse(reopened.state.value.canRedo)
    }

    @Test fun geometryUndoPreservesFavoritesViewModeAndRecentBrowsingState() = runTest {
        val store = fixture()
        val model = store.newViewModel()
        runCurrent()
        model.create("Library browsing is independent", ProjectTemplate.EMPTY, LengthUnit.METRES)
        runCurrent()
        val item = BuiltInLibrary.items.first { it.symbol == LibrarySymbol.FLOOR_BOX }
        val favorite = BuiltInLibrary.items.first { it.symbol == LibrarySymbol.WIFI }
        assertTrue(model.insertLibraryItem(item.id, Point3(2.0, 3.0), null))
        val placed = model.state.value.document!!
        val browsing = placed.libraryState.copy(favoriteIds = setOf(favorite.id), viewMode = LibraryViewMode.LIST)
        model.setLibraryState(browsing)
        model.undo()
        assertTrue(model.state.value.document!!.elements.isEmpty())
        assertEquals(browsing, model.state.value.document!!.libraryState)
        model.redo()
        assertEquals(placed.elements, model.state.value.document!!.elements)
        assertEquals(browsing, model.state.value.document!!.libraryState)
        model.select(setOf(placed.elements.single().id))
        assertTrue(model.transformSelection(EditDelta(translation = Point3(1.0))))
        val newerBrowsing = browsing.toggledFavorite(item.id).copy(viewMode = LibraryViewMode.GRID)
        model.setLibraryState(newerBrowsing)
        model.undo()
        assertEquals(placed.elements, model.state.value.document!!.elements)
        assertEquals(newerBrowsing, model.state.value.document!!.libraryState)
        assertNull(model.state.value.error)
    }

    private fun fixture() = TestAppRepositories(context, AppSettings(autosaveIntervalSeconds = 0)).also(repositories::add)
}
