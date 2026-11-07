package com.eezydraw.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.eezydraw.core.editing.*
import com.eezydraw.core.model.*
import com.eezydraw.core.data.LibraryArchive
import java.io.File
import kotlinx.coroutines.flow.first
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@LooperMode(LooperMode.Mode.PAUSED)
class AppViewModelLibraryAuthoringTest {
    @get:Rule val main=AppMainDispatcherRule()
    private val stores=mutableListOf<TestAppRepositories>()
    private fun fixture()=TestAppRepositories(ApplicationProvider.getApplicationContext<Context>()).also {stores+=it}
    @After fun close()=stores.forEach(TestAppRepositories::close)
    @Test fun editingBuiltInRestoreDuplicateAndDeleteArePersistedAndReversible()=runTest {
        val store=fixture()
        val model=store.newViewModel()
        runCurrent()
        assertTrue(model.state.value.libraryReady)
        val builtin=BuiltInLibrary.items.first()
        val changed=builtin.copy(name="Office switch",category="Office",defaultSize=Size3(.2,.1,.1),geometrySize=builtin.defaultSize)
        model.saveLibraryItem(changed);runCurrent()
        assertEquals(changed,store.library.catalog.find(builtin.id))
        assertTrue(model.state.value.libraryCanUndo)
        model.undoLibrary();runCurrent()
        assertEquals(builtin,store.library.catalog.find(builtin.id))
        model.redoLibrary();runCurrent()
        assertEquals(changed,store.library.catalog.find(builtin.id))
        model.duplicateLibraryItem(builtin.id);runCurrent()
        val copy=store.library.catalog.items.single {!it.builtIn}
        assertEquals(changed.geometry,copy.geometry)
        assertEquals(changed,store.library.catalog.find(builtin.id))
        model.deleteLibraryItem(copy.id);runCurrent()
        assertNull(store.library.catalog.find(copy.id))
        model.undoLibrary();runCurrent()
        assertEquals(copy,store.library.catalog.find(copy.id))
        model.restoreLibraryItem(builtin.id);runCurrent()
        assertEquals(builtin,store.library.catalog.find(builtin.id))
        val restarted=store.newViewModel();runCurrent()
        assertEquals(store.library.catalog,restarted.state.value.libraryCatalog)
        assertFalse(restarted.state.value.libraryCanUndo)
    }
    @Test fun changedDefaultsAffectNewPlacementsAndLibraryDeletionRetainsPlacedObjects()=runTest {
        val store=fixture()
        val model=store.newViewModel();runCurrent()
        model.create("Custom items",ProjectTemplate.EMPTY,LengthUnit.MILLIMETRES);runCurrent()
        val item=createLibraryItem(LibraryItemId("custom"),LibraryBaseShape.BOX)
        model.saveLibraryItem(item);runCurrent()
        assertTrue(model.insertLibraryItem(item.id,Point3(),null))
        val original=model.state.value.document!!.elements.single()
        model.saveLibraryItem(item.copy(defaultSize=Size3(2.0,3.0,4.0),geometrySize=item.defaultSize));runCurrent()
        assertTrue(model.insertLibraryItem(item.id,Point3(5.0),null))
        val doc=model.state.value.document!!
        assertEquals(original,doc.elements.first())
        assertEquals(Size3(2.0,3.0,4.0),(doc.elements.last().body as ElementBody.Component).size)
        assertEquals(2,doc.libraryItems.size)
        model.deleteLibraryItem(item.id);runCurrent()
        assertNull(model.state.value.libraryCatalog.find(item.id))
        assertFalse(model.insertLibraryItem(item.id,Point3(9.0),null))
        assertEquals(doc.elements,model.state.value.document!!.elements)
        model.save();runCurrent()
        model.open(doc.project.id.value);runCurrent()
        assertEquals(doc.elements,model.state.value.document!!.elements)
    }
    @Test fun favoriteOfUnplacedCustomItemCarriesItsDefinitionForProjectSave()=runTest {
        val store=fixture()
        val model=store.newViewModel();runCurrent()
        model.create("Favorites",ProjectTemplate.EMPTY,LengthUnit.MILLIMETRES);runCurrent()
        val item=createLibraryItem(LibraryItemId("favorite"),LibraryBaseShape.CYLINDER)
        model.saveLibraryItem(item);runCurrent()
        model.setLibraryState(LibraryState(favoriteIds=setOf(item.id)))
        assertEquals(item,model.state.value.document!!.libraryItems.single())
        assertTrue(model.state.value.document!!.elements.isEmpty())
    }
    @Test fun libraryBackupImportMergesCanBeUndoneAndExportLeavesHistoryUnchanged()=runTest {
        val store=fixture()
        val model=store.newViewModel();runCurrent()
        val existing=createLibraryItem(LibraryItemId("existing"),LibraryBaseShape.BOX)
        model.saveLibraryItem(existing);runCurrent()
        val imported=createLibraryItem(LibraryItemId("imported"),LibraryBaseShape.CONE)
        val context=ApplicationProvider.getApplicationContext<Context>()
        val input=File.createTempFile("library-input", ".eezy-library",context.cacheDir)
        val output=File.createTempFile("library-output", ".eezy-library",context.cacheDir)
        try {
            input.writeBytes(LibraryArchive.encode(LibraryCatalog(listOf(imported))))
            model.importLibrary(input.toURI().toString())
            model.state.first {!it.busy}
            assertNull(model.state.value.error)
            assertEquals(setOf(existing.id,imported.id),model.state.value.libraryCatalog.items.map {it.id}.toSet())
            model.undoLibrary();runCurrent()
            assertNull(model.state.value.libraryCatalog.find(imported.id))
            assertEquals(existing,model.state.value.libraryCatalog.find(existing.id))
            model.redoLibrary();runCurrent()
            val before=model.state.value
            model.exportLibrary(output.toURI().toString())
            model.state.first {!it.busy}
            assertNull(model.state.value.error)
            val exported=output.inputStream().use(LibraryArchive::decode)
            assertEquals(before.libraryCatalog,exported)
            assertEquals(before.libraryCanUndo,model.state.value.libraryCanUndo)
            assertEquals(before.libraryCanRedo,model.state.value.libraryCanRedo)
            input.writeText("broken archive")
            model.importLibrary(input.toURI().toString())
            model.state.first {!it.busy}
            assertEquals(before.libraryCatalog,model.state.value.libraryCatalog)
            assertNotNull(model.state.value.error)
        } finally {input.delete();output.delete()}
    }
    @Test fun failedLibrarySaveRetainsDefaultsAndUndoHistory()=runTest {
        val store=fixture()
        val model=store.newViewModel();runCurrent()
        val builtin=BuiltInLibrary.items.first()
        store.library.failSave=true
        model.saveLibraryItem(builtin.copy(name="Unsaved"));runCurrent()
        assertEquals(builtin,model.state.value.libraryCatalog.find(builtin.id))
        assertFalse(model.state.value.libraryCanUndo)
        assertNotNull(model.state.value.error)
        store.library.failSave=false
        model.saveLibraryItem(builtin.copy(name="Saved"));runCurrent()
        store.library.failSave=true
        model.undoLibrary();runCurrent()
        assertEquals("Saved",model.state.value.libraryCatalog.find(builtin.id)!!.name)
        assertTrue(model.state.value.libraryCanUndo)
        assertFalse(model.state.value.libraryCanRedo)
    }
}
