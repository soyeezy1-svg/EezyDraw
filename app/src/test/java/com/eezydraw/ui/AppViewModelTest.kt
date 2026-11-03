package com.eezydraw.ui

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.eezydraw.core.data.ProjectStorageException
import com.eezydraw.core.model.AngleFormat
import com.eezydraw.core.model.AppSettings
import com.eezydraw.core.model.LengthUnit
import com.eezydraw.core.model.ProjectSettings
import com.eezydraw.core.model.ProjectTemplate
import com.eezydraw.core.model.SnapSettings
import com.eezydraw.core.model.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class AppViewModelTest {
    @get:Rule val main = AppMainDispatcherRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val repositories = mutableListOf<TestAppRepositories>()

    @After fun closeModels() {
        repositories.forEach(TestAppRepositories::close)
    }

    @Test fun createEditSaveAndReopenRetainsChosenProjectData() = runTest {
        val preferences = AppSettings(
            autosaveIntervalSeconds = 0,
            defaultLineWeightMm = 0.6,
            projectDefaults = ProjectSettings(
                angleFormat = AngleFormat.RADIANS,
                decimalPlaces = 4,
                snap = SnapSettings(gridSpacing = 0.5),
            ),
        )
        val store = fixture(preferences)
        val model = store.newViewModel()
        runCurrent()
        assertTrue(model.state.value.ready)

        model.create("  East Wing  ", ProjectTemplate.OFFICE, LengthUnit.FEET)
        runCurrent()
        val created = requireNotNull(model.state.value.document)
        assertEquals(AppScreen.PROJECT, model.state.value.screen)
        assertEquals("East Wing", created.project.name)
        assertEquals(ProjectTemplate.OFFICE, created.project.template)
        assertEquals(LengthUnit.FEET, created.project.settings.lengthUnit)
        assertEquals(AngleFormat.RADIANS, created.project.settings.angleFormat)
        assertEquals(0.5, created.project.settings.snap.gridSpacing, 0.0)
        assertEquals(0.6, created.layers.single().lineWeightMm, 0.0)
        assertFalse(model.state.value.dirty)

        model.rename("East Wing Rev 2")
        model.describe("Coordination review")
        model.changeUnits(LengthUnit.MILLIMETRES)
        assertTrue(model.state.value.dirty)
        model.save()
        runCurrent()
        assertFalse(model.state.value.dirty)
        assertFalse(model.state.value.busy)

        val reopened = store.newViewModel()
        runCurrent()
        reopened.open(created.project.id.value)
        runCurrent()
        val document = requireNotNull(reopened.state.value.document)
        assertEquals("East Wing Rev 2", document.project.name)
        assertEquals("Coordination review", document.project.description)
        assertEquals(LengthUnit.MILLIMETRES, document.project.settings.lengthUnit)
        assertEquals(document.project.name, reopened.state.value.recentProjects.first().name)
    }

    @Test fun failedSaveKeepsDraftAndPreventsBackNavigation() = runTest {
        val store = fixture()
        val model = store.newViewModel()
        runCurrent()
        model.create("Original", ProjectTemplate.EMPTY, LengthUnit.METRES)
        runCurrent()
        val id = requireNotNull(model.state.value.document).project.id.value
        model.rename("Unsaved draft")
        store.projects.saveFailure = ProjectStorageException("The disk is full.")

        model.backFromProject()
        runCurrent()

        assertEquals(AppScreen.PROJECT, model.state.value.screen)
        assertEquals("Unsaved draft", model.state.value.document?.project?.name)
        assertEquals("Original", store.projects.documents.getValue(id).project.name)
        assertTrue(model.state.value.dirty)
        assertFalse(model.state.value.busy)
        assertEquals("The disk is full.", model.state.value.error)
        assertEquals(null, model.state.value.notice)
    }

    @Test fun autosaveUsesConfiguredIntervalAndStopsWhenDisabled() = runTest {
        val store = fixture(AppSettings(autosaveIntervalSeconds = 2))
        val model = store.newViewModel()
        runCurrent()
        model.create("Scheduled", ProjectTemplate.EMPTY, LengthUnit.METRES)
        runCurrent()
        model.rename("Scheduled revision")

        advanceTimeBy(1_999)
        runCurrent()
        assertTrue(store.projects.savedSnapshots.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals("Scheduled revision", store.projects.savedSnapshots.single().project.name)
        assertFalse(model.state.value.dirty)

        model.saveSettings(model.state.value.settings.copy(autosaveIntervalSeconds = 0))
        runCurrent()
        model.navigate(AppScreen.PROJECT)
        model.rename("Manual-only revision")
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(1, store.projects.savedSnapshots.size)
        assertTrue(model.state.value.dirty)
    }

    @Test fun preferencesAreAvailableToARecreatedModelAndFutureCreation() = runTest {
        val store = fixture()
        val model = store.newViewModel()
        runCurrent()
        val changed = model.state.value.settings.copy(
            theme = ThemeMode.DARK,
            defaultTemplate = ProjectTemplate.DATA_CENTER,
            backupEnabled = false,
            undoLimit = 750,
            projectDefaults = ProjectSettings(lengthUnit = LengthUnit.INCHES, decimalPlaces = 3),
        )
        model.saveSettings(changed)
        runCurrent()
        assertEquals(changed, store.settings.settings.value)

        val recreated = store.newViewModel()
        runCurrent()
        assertEquals(changed, recreated.state.value.settings)
        recreated.create("Server Hall", changed.defaultTemplate, changed.projectDefaults.lengthUnit)
        runCurrent()
        val document = requireNotNull(recreated.state.value.document)
        assertEquals(ProjectTemplate.DATA_CENTER, document.project.template)
        assertEquals(LengthUnit.INCHES, document.project.settings.lengthUnit)
        assertEquals(3, document.project.settings.decimalPlaces)
    }

    @Test fun savedProjectRouteReloadsAfterModelRecreation() = runTest {
        val store = fixture()
        val handle = SavedStateHandle()
        val model = store.newViewModel(handle)
        runCurrent()
        model.create("Restored", ProjectTemplate.RESIDENTIAL, LengthUnit.CENTIMETRES)
        runCurrent()
        model.describe("Saved before recreation")
        model.save()
        runCurrent()
        val id = requireNotNull(model.state.value.document).project.id.value
        val restoredHandle = SavedStateHandle(mapOf(
            "screen" to handle.get<String>("screen"),
            "projectId" to handle.get<String>("projectId"),
        ))

        val recreated = store.newViewModel(restoredHandle)
        runCurrent()

        assertEquals(AppScreen.PROJECT, recreated.state.value.screen)
        assertEquals(id, recreated.state.value.document?.project?.id?.value)
        assertEquals("Saved before recreation", recreated.state.value.document?.project?.description)
        assertFalse(recreated.state.value.dirty)
    }

    @Test fun continueUsesMostRecentlyOpenedProject() = runTest {
        val store = fixture()
        val model = store.newViewModel()
        runCurrent()
        model.create("First", ProjectTemplate.EMPTY, LengthUnit.METRES)
        runCurrent()
        val firstId = requireNotNull(model.state.value.document).project.id.value
        model.create("Second", ProjectTemplate.EMPTY, LengthUnit.METRES)
        runCurrent()
        model.navigate(AppScreen.MENU)
        model.continueProject()
        runCurrent()
        assertEquals("Second", model.state.value.document?.project?.name)

        model.open(firstId)
        runCurrent()
        model.backFromProject()
        model.continueProject()
        runCurrent()
        assertEquals("First", model.state.value.document?.project?.name)
    }

    @Test fun explicitBackupRecoveryLoadsLastSavedVersionAfterReadFailure() = runTest {
        val store = fixture()
        val model = store.newViewModel()
        runCurrent()
        model.create("Last good", ProjectTemplate.EMPTY, LengthUnit.METRES)
        runCurrent()
        val id = requireNotNull(model.state.value.document).project.id.value
        model.rename("New revision")
        model.save()
        runCurrent()
        model.navigate(AppScreen.MENU)
        store.projects.loadFailureIds += id
        model.open(id)
        runCurrent()
        assertEquals(id, model.state.value.failedProjectId)
        assertNotNull(model.state.value.error)

        model.restoreBackup()
        runCurrent()

        assertEquals("Last good", model.state.value.document?.project?.name)
        assertEquals(AppScreen.PROJECT, model.state.value.screen)
        assertEquals(null, model.state.value.error)
        assertEquals(null, model.state.value.failedProjectId)
        assertFalse(model.state.value.dirty)
    }

    @Test fun rapidCreateRequestsDoNotCreateDuplicateProjects() = runTest {
        val store = fixture()
        val model = store.newViewModel()
        runCurrent()

        model.create("One request", ProjectTemplate.EMPTY, LengthUnit.METRES)
        model.create("Duplicate tap", ProjectTemplate.EMPTY, LengthUnit.METRES)
        runCurrent()

        assertEquals(1, store.projects.documents.size)
        assertEquals("One request", model.state.value.document?.project?.name)
    }

    private fun fixture(settings: AppSettings = AppSettings(autosaveIntervalSeconds = 0)) =
        TestAppRepositories(context, settings).also(repositories::add)
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppMainDispatcherRule(val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) { Dispatchers.setMain(dispatcher) }
    override fun finished(description: Description) { Dispatchers.resetMain() }
}
