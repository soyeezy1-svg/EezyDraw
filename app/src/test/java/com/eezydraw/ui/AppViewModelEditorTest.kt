package com.eezydraw.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.eezydraw.core.data.ProjectStorageException
import com.eezydraw.core.editing.EditDelta
import com.eezydraw.core.editing.ElementProperties
import com.eezydraw.core.editing.MirrorAxis
import com.eezydraw.core.editing.SketchGeometry
import com.eezydraw.core.model.AppSettings
import com.eezydraw.core.model.Element
import com.eezydraw.core.model.ElementBody
import com.eezydraw.core.model.ElementId
import com.eezydraw.core.model.LengthUnit
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.ProjectTemplate
import com.eezydraw.core.model.Rotation3
import com.eezydraw.core.model.Shape
import com.eezydraw.core.model.Transform3
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class AppViewModelEditorTest {
    @get:Rule val main = AppMainDispatcherRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val repositories = mutableListOf<TestAppRepositories>()

    @After fun closeModels() = repositories.forEach(TestAppRepositories::close)

    @Test fun saveRetainsHistoryAndUndoRedoTrackTheSavedBaseline() = runTest {
        val store = fixture()
        val model = store.newViewModel()
        runCurrent()
        model.create("Drawing", ProjectTemplate.EMPTY, LengthUnit.METRES)
        runCurrent()

        model.addSketch(line(), null)
        val objectId = model.state.value.selectedIds.single()
        assertTrue(model.state.value.dirty)
        assertTrue(model.state.value.canUndo)
        model.undo()
        assertTrue(model.state.value.document!!.elements.isEmpty())
        assertFalse(model.state.value.dirty)
        model.redo()
        assertEquals(objectId, model.state.value.document!!.elements.single().id)

        model.save()
        runCurrent()
        assertFalse(model.state.value.dirty)
        assertTrue(model.state.value.canUndo)
        model.undo()
        assertTrue(model.state.value.dirty)
        model.redo()
        assertFalse(model.state.value.dirty)

        // Project metadata remains independent from drawing history.
        model.rename("Renamed drawing")
        model.undo()
        assertEquals("Renamed drawing", model.state.value.document!!.project.name)
        model.redo()
        assertTrue(model.state.value.dirty)
        model.rename("Drawing")
        assertFalse(model.state.value.dirty)

        model.select(setOf(objectId))
        model.copySelection()
        model.open(model.state.value.document!!.project.id.value)
        runCurrent()
        assertEquals(objectId, model.state.value.document!!.elements.single().id)
        assertTrue(model.state.value.selectedIds.isEmpty())
        assertFalse(model.state.value.canUndo)
        assertFalse(model.state.value.canRedo)
        assertFalse(model.state.value.canPaste)
    }

    @Test fun clipboardOffsetsFreshIdsAndEditorActionsShareOneHistory() = runTest {
        val store = fixture()
        val model = store.newViewModel()
        runCurrent()
        model.create("Objects", ProjectTemplate.EMPTY, LengthUnit.METRES)
        runCurrent()
        model.addSketch(line(), null)
        val originalId = model.state.value.selectedIds.single()

        model.transformSelection(EditDelta(translation = Point3(1.0, 2.0, 3.0)))
        model.updateProperties(ElementProperties(Point3(10.0, 20.0, 3.0), Rotation3(z = 0.25), Point3(6.0, 3.0)))
        val resized = model.state.value.document!!.elements.single()
        assertEquals(Point3(10.0, 20.0, 3.0), resized.transform.translation)
        assertEquals(3.0, resized.transform.scale.x, 0.0)

        model.mirrorSelection(MirrorAxis.X, Point3())
        assertEquals(-10.0, model.state.value.document!!.elements.single().transform.translation.x, 0.0)
        model.arraySelection(columns = 2, rows = 2, dx = 5.0, dy = 6.0)
        assertEquals(4, model.state.value.document!!.elements.size)
        assertEquals(setOf(originalId), model.state.value.selectedIds)

        model.copySelection()
        assertTrue(model.state.value.canPaste)
        model.pasteSelection()
        val firstPaste = model.state.value.document!!.elements.single { it.id in model.state.value.selectedIds }
        assertNotEquals(originalId, firstPaste.id)
        assertEquals(Point3(-9.5, 20.5, 3.0), firstPaste.transform.translation)
        model.pasteSelection()
        val secondPaste = model.state.value.document!!.elements.single { it.id in model.state.value.selectedIds }
        assertNotEquals(firstPaste.id, secondPaste.id)
        assertEquals(Point3(-9.0, 21.0, 3.0), secondPaste.transform.translation)
        assertEquals(6, model.state.value.document!!.elements.size)

        model.deleteSelection()
        assertEquals(5, model.state.value.document!!.elements.size)
        assertTrue(model.state.value.selectedIds.isEmpty())
        model.undo()
        assertEquals(6, model.state.value.document!!.elements.size)
        model.redo()
        assertEquals(5, model.state.value.document!!.elements.size)
    }

    @Test fun lockedLayersRejectChangesAndFallbackCreationIsOneUndoStep() = runTest {
        val store = fixture()
        val model = store.newViewModel()
        runCurrent()
        model.create("Locked", ProjectTemplate.EMPTY, LengthUnit.METRES)
        runCurrent()
        val initial = model.state.value.document!!
        val lockedElement = Element(
            ElementId("locked-object"), initial.project.id, initial.layers.single().id,
            ElementBody.DrawingShape(Shape.Circle(1.0)),
        )
        val locked = initial.copy(layers = initial.layers.map { it.copy(locked = true) }, elements = listOf(lockedElement))
        store.projects.documents[initial.project.id.value] = locked
        model.open(initial.project.id.value)
        runCurrent()

        model.select(setOf(lockedElement.id))
        model.transformSelection(EditDelta(translation = Point3(1.0)))
        assertEquals(locked, model.state.value.document)
        assertTrue(model.state.value.error!!.contains("locked"))
        assertFalse(model.state.value.dirty)
        assertFalse(model.state.value.canUndo)

        model.copySelection()
        model.pasteSelection()
        val pasted = model.state.value.document!!
        assertEquals(2, pasted.layers.size)
        assertEquals(2, pasted.elements.size)
        val pastedElement = pasted.elements.single { it.id in model.state.value.selectedIds }
        assertFalse(pasted.layers.single { it.id == pastedElement.layerId }.locked)
        model.undo()
        assertEquals(locked, model.state.value.document)
        assertFalse(model.state.value.canUndo)
        assertFalse(model.state.value.dirty)

        model.addSketch(line(), null)
        assertEquals(2, model.state.value.document!!.layers.size)
        model.undo()
        assertEquals(locked, model.state.value.document)
        assertFalse(model.state.value.dirty)
    }

    @Test fun busyAndFailedSaveKeepDrawingHistoryAndUnsavedDraftIntact() = runTest {
        val store = fixture()
        val model = store.newViewModel()
        runCurrent()
        model.create("Draft", ProjectTemplate.EMPTY, LengthUnit.METRES)
        runCurrent()
        model.addSketch(line(), null)
        val snapshot = model.state.value.document
        store.projects.saveFailure = ProjectStorageException("The disk is full.")
        model.save()
        assertTrue(model.state.value.busy)
        model.deleteSelection()
        model.undo()
        model.copySelection()
        assertEquals(snapshot, model.state.value.document)
        assertFalse(model.state.value.canPaste)
        runCurrent()
        assertTrue(model.state.value.dirty)
        assertTrue(model.state.value.canUndo)
        assertEquals("The disk is full.", model.state.value.error)

        store.projects.saveFailure = null
        model.save()
        runCurrent()
        model.undo()
        assertTrue(model.state.value.document!!.elements.isEmpty())
        model.redo()
        assertFalse(model.state.value.dirty)
    }

    @Test fun configuredHistoryLimitEvictsOlderCommandsAndNewEditsClearRedo() = runTest {
        val store = fixture(AppSettings(autosaveIntervalSeconds = 0, undoLimit = 1))
        val model = store.newViewModel()
        runCurrent()
        model.create("Short history", ProjectTemplate.EMPTY, LengthUnit.METRES)
        runCurrent()
        model.addSketch(line(), null)
        model.transformSelection(EditDelta(translation = Point3(1.0)))
        model.undo()
        assertFalse(model.state.value.canUndo)
        assertTrue(model.state.value.canRedo)
        assertEquals(1, model.state.value.document!!.elements.size)
        model.transformSelection(EditDelta(translation = Point3(2.0)))
        assertFalse(model.state.value.canRedo)
    }

    private fun line() = SketchGeometry(
        Shape.Line(Point3(), Point3(2.0, 1.0)),
        Transform3(translation = Point3(3.0, 4.0)),
    )

    private fun fixture(settings: AppSettings = AppSettings(autosaveIntervalSeconds = 0)) =
        TestAppRepositories(context, settings).also(repositories::add)
}
