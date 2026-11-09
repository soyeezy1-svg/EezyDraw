package com.eezydraw.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.eezydraw.core.data.SettingsRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import com.eezydraw.core.editing.RoomWalls
import com.eezydraw.core.geometry.ScenePresentation
import com.eezydraw.core.editing.EditDelta
import com.eezydraw.core.editing.SketchGeometry
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@LooperMode(LooperMode.Mode.PAUSED)
class AppViewModelPresentationTest {
    @get:Rule val main=AppMainDispatcherRule()
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private val stores=mutableListOf<TestAppRepositories>()
    @After fun close()=stores.forEach(TestAppRepositories::close)

    @Test fun rotateNinetyUsesTheFixedInsertionForPositionOnlyLocks()=runTest {
        val store=fixture();val model=store.newViewModel();runCurrent()
        model.create("Position lock",ProjectTemplate.EMPTY,LengthUnit.METRES);runCurrent()
        model.addSketch(box(),null)
        val original=model.state.value.document!!.elements.single()
        model.setSelectionLockMode(LockMode.POSITION_ONLY)
        model.rotateSelection90()
        val rotated=model.state.value.document!!.elements.single()
        assertNull(model.state.value.error)
        assertEquals(original.transform.translation,rotated.transform.translation)
        assertEquals(Math.PI/2,rotated.transform.rotation.z,1e-9)
        assertEquals(original.body,rotated.body)
        assertEquals(LockMode.POSITION_ONLY,rotated.lockMode)
        model.undo()
        assertEquals(original.transform,model.state.value.document!!.elements.single().transform)
        assertEquals(LockMode.POSITION_ONLY,model.state.value.document!!.elements.single().lockMode)
        model.redo()
        assertEquals(rotated,model.state.value.document!!.elements.single())
        assertFalse(model.transformSelection(EditDelta(translation=Point3(1.0,0.0,0.0))))
        assertEquals(rotated,model.state.value.document!!.elements.single())
    }

    @Test fun unlockOnALockedLayerUnlocksTheLayerFirstAndBothStepsAreUndoable()=runTest {
        val store=fixture();val model=store.newViewModel();runCurrent()
        model.create("Layer and object locks",ProjectTemplate.EMPTY,LengthUnit.METRES);runCurrent()
        model.addSketch(box(),null)
        val initial=model.state.value.document!!
        val selected=initial.elements.single()
        val locked=initial.copy(elements=listOf(selected.withLock(LockMode.ALL)),
            layers=initial.layers.map {if(it.id==selected.layerId)it.copy(locked=true) else it})
        store.projects.documents[initial.project.id.value]=locked
        model.open(initial.project.id.value);runCurrent()
        model.select(setOf(selected.id))
        model.setSelectionLockMode(LockMode.NONE)
        val layerUnlocked=model.state.value.document!!
        assertFalse(layerUnlocked.layers.single {it.id==selected.layerId}.locked)
        assertEquals(LockMode.ALL,layerUnlocked.elements.single().lockMode)
        assertTrue(model.state.value.noticeUndo)
        model.undo()
        assertEquals(locked,model.state.value.document)
        model.redo()
        assertEquals(layerUnlocked,model.state.value.document)
        model.setSelectionLockMode(LockMode.NONE)
        assertEquals(LockMode.NONE,model.state.value.document!!.elements.single().lockMode)
        assertFalse(model.state.value.document!!.layers.single {it.id==selected.layerId}.locked)
        model.undo()
        assertEquals(layerUnlocked,model.state.value.document)
    }

    @Test fun anUnchangedDisplayDoesNotOfferUndoForAnUnrelatedEarlierEdit()=runTest {
        val store=fixture();val model=store.newViewModel();runCurrent()
        model.create("No-op display",ProjectTemplate.EMPTY,LengthUnit.METRES);runCurrent()
        model.addSketch(box(),null)
        val before=model.state.value.document
        assertTrue(model.state.value.canUndo)
        model.setSelectionDisplay(ObjectDisplay.NORMAL)
        assertEquals(before,model.state.value.document)
        assertNull("No-op display must not post a success message",model.state.value.notice)
        assertFalse(model.state.value.noticeUndo)
        model.undo()
        assertTrue("The original placement remains the only undo entry",model.state.value.document!!.elements.isEmpty())
        assertFalse(model.state.value.canUndo)
    }

    @Test fun saveAndSettingsSuccessMessagesNeverInheritPresentationUndoActions()=runTest {
        val store=fixture();val model=store.newViewModel();runCurrent()
        model.create("Notice actions",ProjectTemplate.EMPTY,LengthUnit.METRES);runCurrent()
        model.addSketch(box(),null)
        model.setSelectionLockMode(LockMode.ALL)
        assertTrue(model.state.value.noticeUndo)
        model.save();runCurrent()
        assertEquals(context.getString(com.eezydraw.R.string.project_saved_notice),model.state.value.notice)
        assertFalse(model.state.value.noticeUndo)
        model.setSelectionLockMode(LockMode.NONE)
        assertTrue(model.state.value.noticeUndo)
        model.saveSettings(model.state.value.settings.copy(autoHidePanelsWhileDrawing=true));runCurrent()
        assertEquals(context.getString(com.eezydraw.R.string.settings_saved_notice),model.state.value.notice)
        assertFalse(model.state.value.noticeUndo)
    }

    @Test fun lateFirstSettingsEmissionRestoresPanelCacheWithoutDirtyingTheLoadedProject()=runTest {
        val store=fixture()
        val created=store.projects.create("Cold panel restore",ProjectTemplate.EMPTY,ProjectSettings())
        val element=Element(ElementId("saved-box"),created.project.id,created.layers.single().id,
            ElementBody.DrawingShape(Shape.Box(Size3(1.0,2.0,3.0))),transform=box().transform)
        val saved=created.copy(elements=listOf(element))
        val id=saved.project.id.value
        store.projects.documents[id]=saved
        // A Room settings query can finish after the project-file read. Hold its first
        // emission so construction deliberately takes that cold-start ordering.
        val values=MutableSharedFlow<AppSettings>(replay=1)
        val preferences=object:SettingsRepository {
            override val settings=values
            override suspend fun save(settings:AppSettings) {values.emit(settings)}
        }
        val model=AppViewModel(context,store.projects,preferences,SavedStateHandle(mapOf(
            "screen" to AppScreen.PROJECT.name,"projectId" to id)),store.library)
        val owner=ViewModelStore().apply {put("cold-restored",model)}
        try {
            runCurrent()
            assertEquals(saved,model.state.value.document)
            assertFalse("Settings have not emitted yet",model.state.value.ready)
            assertFalse(model.state.value.dirty)
            val remembered=saved.project.settings.canvasLayout
                .withPanel(CanvasPanelId.DRAW,PanelPlacement(PanelVisibility.OPEN,.74,.63))
                .withPanel(CanvasPanelId.VISIBILITY,PanelPlacement(PanelVisibility.COLLAPSED,.12,.48))
                .copy(hideAll=true)
            values.emit(AppSettings(autosaveIntervalSeconds=0,canvasLayouts=mapOf(id to remembered)))
            runCurrent()
            assertTrue(model.state.value.ready)
            assertEquals(AppScreen.PROJECT,model.state.value.screen)
            assertEquals(remembered,model.state.value.document!!.project.settings.canvasLayout)
            assertEquals("Restoring layout must preserve the loaded geometry",saved.elements,model.state.value.document!!.elements)
            assertFalse("Layout recovery also updates the saved baseline",model.state.value.dirty)
            assertFalse(model.state.value.canUndo)
            assertEquals(0,model.state.value.settings.autosaveIntervalSeconds)
            assertTrue("Recovering presentation does not force a project save",store.projects.savedSnapshots.isEmpty())
            model.rename("Changed metadata")
            assertTrue(model.state.value.dirty)
            model.rename(saved.project.name)
            assertFalse("Reverting metadata compares against the recovered layout baseline",model.state.value.dirty)
        } finally {owner.clear()}
    }

    @Test fun generatedWallDisplayResetAndCeilingDeletionUseTheRealReconciliationAndHistoryPipeline()=runTest {
        val store=fixture();val model=store.newViewModel();runCurrent()
        model.create("Wall and ceiling controls",ProjectTemplate.EMPTY,LengthUnit.METRES);runCurrent()
        val base=model.state.value.document!!;val layer=base.layers.first().id;val level=LevelId("coordination-level")
        val room=Element(ElementId("room"),base.project.id,layer,ElementBody.Room(Polygon2(listOf(Point2(0.0,0.0),Point2(4.0,0.0),Point2(4.0,4.0),Point2(0.0,4.0)))),level,name="Room A")
        val seed=base.copy(elements=listOf(room),levels=listOf(Level(level,base.project.id,"Level 1",0.0,.2,3.0)))
        val generated=RoomWalls.generate(seed,setOf(room.id),RoomWallOptions()).preview(seed)
        store.projects.documents[base.project.id.value]=generated;model.open(base.project.id.value);runCurrent()
        val wall=generated.elements.first {it.body is ElementBody.Wall}
        model.select(setOf(wall.id));model.setSelectionDisplay(ObjectDisplay.HIDDEN)
        assertNull(model.state.value.error)
        val hidden=model.state.value.document!!
        assertEquals(ObjectDisplay.HIDDEN,hidden.elements.first {it.id==wall.id}.objectDisplay)
        assertEquals(generated.elements.filter {it.id!=wall.id},hidden.elements.filter {it.id!=wall.id})
        model.undo();assertEquals(generated,model.state.value.document)
        model.redo();assertEquals(hidden,model.state.value.document)
        // Hiding clears selection. Restore via the Visibility command, then reset a visible selection.
        model.setElementsDisplay(setOf(wall.id),ObjectDisplay.NORMAL)
        model.select(setOf(wall.id));model.setSelectionDisplay(ObjectDisplay.XRAY)
        model.resetSelectionDisplay()
        assertEquals(ObjectDisplay.NORMAL,ScenePresentation(model.state.value.document!!).display(model.state.value.document!!.elements.first {it.id==wall.id}))
        model.setRoomCeiling(room.id,CeilingOptions(CeilingType.FLAT,2.6,.1))
        assertNull(model.state.value.error)
        val flat=model.state.value.document!!;val ceiling=flat.elements.single {it.isCeiling}
        model.select(setOf(ceiling.id));model.deleteSelection()
        assertNull(model.state.value.error);assertFalse(model.state.value.document!!.elements.any {it.isCeiling})
        assertEquals(CeilingType.NONE,(model.state.value.document!!.elements.first {it.id==room.id}.body as ElementBody.Room).ceiling!!.type)
        model.undo();assertEquals(flat,model.state.value.document)
        model.redo();val removed=model.state.value.document!!
        model.showCeilings(false);assertFalse(model.state.value.document!!.project.settings.showCeilings)
        model.undo();assertEquals(removed,model.state.value.document)
        model.setLevelCeiling(level,CeilingOptions(CeilingType.FLAT,2.5,.12))
        assertNull(model.state.value.error)
        assertEquals(2.5,model.state.value.document!!.levels.single().ceiling.height,0.0)
        model.setRoomCeiling(room.id,null)
        assertEquals(1,model.state.value.document!!.elements.count {it.isCeiling})
        model.setLevelCeiling(level,CeilingOptions(CeilingType.NONE,2.5,.12))
        assertFalse(model.state.value.document!!.elements.any {it.isCeiling})
        assertNull((model.state.value.document!!.elements.first {it.id==room.id}.body as ElementBody.Room).ceiling)
        model.setLevelCeiling(level,CeilingOptions(CeilingType.FLAT,2.5,.12))
        assertEquals(1,model.state.value.document!!.elements.count {it.isCeiling})
        model.save();runCurrent();val saved=model.state.value.document!!
        model.open(saved.project.id.value);runCurrent();assertEquals(saved,model.state.value.document)
        model.resetAllDisplay();assertNull(model.state.value.error)
        assertTrue(model.state.value.document!!.elements.all {it.objectDisplay==ObjectDisplay.NORMAL})
    }

    private fun fixture()=TestAppRepositories(context,AppSettings(autosaveIntervalSeconds=0)).also(stores::add)
    private fun box()=SketchGeometry(Shape.Box(Size3(1.0,2.0,3.0)),Transform3(translation=Point3(4.0,-2.0,3.0)))
}
