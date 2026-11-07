package com.eezydraw.ui

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.eezydraw.R
import com.eezydraw.core.data.ProjectRepository
import com.eezydraw.core.data.ProjectStorageException
import com.eezydraw.core.model.AppSettings
import com.eezydraw.core.model.LengthUnit
import com.eezydraw.core.model.ProjectDocument
import com.eezydraw.core.model.ProjectTemplate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class AutosaveBehaviorTest {
    @get:Rule val main = AppMainDispatcherRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val fixtures = mutableListOf<TestAppRepositories>()
    private val models = mutableListOf<ViewModelStore>()

    @After fun close() {
        models.forEach(ViewModelStore::clear)
        fixtures.forEach(TestAppRepositories::close)
    }

    @Test fun scheduledAutosaveIsSilentByDefaultWhileExplicitSaveStillConfirms() = runTest {
        val store = fixture(AppSettings(autosaveIntervalSeconds = 1))
        val model = store.newViewModel()
        try {
            runCurrent()
            model.create("Silent", ProjectTemplate.EMPTY, LengthUnit.METRES)
            runCurrent()
            model.describe("Saved by timer")
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals("Saved by timer", store.projects.savedSnapshots.single().project.description)
            assertFalse(model.state.value.dirty)
            assertFalse(model.state.value.busy)
            assertFalse(model.state.value.autosaving)
            assertNull("Successful autosave must not enqueue a popup", model.state.value.notice)

            model.describe("Explicit revision")
            model.save()
            runCurrent()
            assertEquals(context.getString(R.string.project_saved_notice), model.state.value.notice)
        } finally {
            // runTest drains scheduled work before JUnit @After. Stop the recurring timer first.
            store.close()
            runCurrent()
        }
    }

    @Test fun autosaveNotificationCanBeEnabledInSettings() = runTest {
        val store = fixture(AppSettings(autosaveIntervalSeconds = 1, showAutosaveNotification = true))
        val model = store.newViewModel()
        try {
            runCurrent()
            model.create("Opt in", ProjectTemplate.EMPTY, LengthUnit.FEET)
            runCurrent()
            model.describe("Notify me")
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(context.getString(R.string.project_autosaved_notice), model.state.value.notice)
            assertFalse(model.state.value.dirty)
        } finally {
            store.close()
            runCurrent()
        }
    }

    @Test fun failedAutosaveStillReportsFailureAndKeepsTheDraft() = runTest {
        val store = fixture()
        val model = store.newViewModel()
        runCurrent()
        model.create("Failure", ProjectTemplate.EMPTY, LengthUnit.METRES)
        runCurrent()
        model.describe("Unsaved")
        store.projects.saveFailure = ProjectStorageException("The disk is full.")
        model.saveIfDirty()
        runCurrent()

        assertEquals("The disk is full.", model.state.value.error)
        assertNull(model.state.value.notice)
        assertTrue(model.state.value.dirty)
        assertFalse(model.state.value.busy)
        assertFalse(model.state.value.autosaving)
        assertEquals("Unsaved", model.state.value.document?.project?.description)
    }

    @Test fun editsDuringAutosaveArePreservedAndTheSavedBaselineStaysAccurate() = runTest {
        val store = fixture()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delayed = object : ProjectRepository by store.projects {
            override suspend fun save(document: ProjectDocument, backupEnabled: Boolean): ProjectDocument {
                started.complete(Unit)
                release.await()
                return store.projects.save(document, backupEnabled)
            }
        }
        val model = model(store, delayed)
        runCurrent()
        model.create("Editable", ProjectTemplate.EMPTY, LengthUnit.METRES)
        runCurrent()
        model.describe("Snapshot")
        model.saveIfDirty()
        runCurrent()
        assertTrue(started.isCompleted)
        assertTrue(model.state.value.autosaving)
        assertFalse("Autosave must not activate the blocking busy overlay", model.state.value.busy)

        model.describe("Newer draft")
        release.complete(Unit)
        runCurrent()

        assertEquals("Snapshot", store.projects.savedSnapshots.single().project.description)
        assertEquals("Newer draft", model.state.value.document?.project?.description)
        assertTrue(model.state.value.dirty)
        assertFalse(model.state.value.autosaving)
        assertNull(model.state.value.notice)
        model.describe("Snapshot")
        assertFalse("Returning to saved contents must be clean despite repository timestamp changes", model.state.value.dirty)
    }

    @Test fun explicitSaveWaitsForAnInFlightAutosaveAndThenSavesTheLatestDraft() = runTest {
        val store = fixture()
        val release = CompletableDeferred<Unit>()
        var active = 0
        var maximumActive = 0
        var writes = 0
        val delayed = object : ProjectRepository by store.projects {
            override suspend fun save(document: ProjectDocument, backupEnabled: Boolean): ProjectDocument {
                active++
                maximumActive = maxOf(maximumActive, active)
                writes++
                try {
                    if (writes == 1) release.await()
                    return store.projects.save(document, backupEnabled)
                } finally { active-- }
            }
        }
        val model = model(store, delayed)
        runCurrent()
        model.create("Serial writes", ProjectTemplate.EMPTY, LengthUnit.METRES)
        runCurrent()
        model.describe("Autosave snapshot")
        model.saveIfDirty()
        runCurrent()
        model.describe("Explicit snapshot")
        model.save()
        runCurrent()
        assertEquals(1, writes)
        assertTrue(model.state.value.busy)
        release.complete(Unit)
        runCurrent()

        assertEquals(1, maximumActive)
        assertEquals(listOf("Autosave snapshot", "Explicit snapshot"), store.projects.savedSnapshots.map { it.project.description })
        assertEquals("Explicit snapshot", model.state.value.document?.project?.description)
        assertFalse(model.state.value.dirty)
        assertFalse(model.state.value.busy)
        assertFalse(model.state.value.autosaving)
        assertEquals(context.getString(R.string.project_saved_notice), model.state.value.notice)
    }

    @Test fun oldSettingsDefaultToSilentAndTheOptInRoundTrips() {
        assertFalse(AppSettings().showAutosaveNotification)
        assertFalse(Json.decodeFromString<AppSettings>("{}").showAutosaveNotification)
        val optedIn = AppSettings(showAutosaveNotification = true)
        assertEquals(optedIn, Json.decodeFromString<AppSettings>(Json.encodeToString(optedIn)))
    }

    private fun fixture(settings: AppSettings = AppSettings(autosaveIntervalSeconds = 0)) =
        TestAppRepositories(context, settings).also(fixtures::add)

    private fun model(store: TestAppRepositories, projects: ProjectRepository): AppViewModel =
        AppViewModel(context, projects, store.settings, deviceLibrary=store.library).also { model ->
            models += ViewModelStore().apply { put("autosave", model) }
        }
}
