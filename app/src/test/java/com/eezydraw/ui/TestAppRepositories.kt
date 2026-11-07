package com.eezydraw.ui

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.eezydraw.core.data.ProjectRepository
import com.eezydraw.core.data.ProjectStorageException
import com.eezydraw.core.data.SettingsRepository
import com.eezydraw.core.data.StoredProject
import com.eezydraw.core.model.AppSettings
import com.eezydraw.core.model.Layer
import com.eezydraw.core.model.LayerId
import com.eezydraw.core.model.Project
import com.eezydraw.core.model.ProjectDocument
import com.eezydraw.core.model.ProjectId
import com.eezydraw.core.model.ProjectSettings
import com.eezydraw.core.model.ProjectTemplate
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.MutableStateFlow

/** Event-observable repositories for host tests; real Room/archive durability is tested in core:data. */
internal class TestAppRepositories(
    private val context: Context,
    initialSettings: AppSettings = AppSettings(autosaveIntervalSeconds = 0),
) : AutoCloseable {
    val projects = TestProjectRepository()
    val settings = TestSettingsRepository(initialSettings)
    val library = TestLibraryRepository()
    private val stores = mutableListOf<ViewModelStore>()

    fun newViewModel(savedState: SavedStateHandle = SavedStateHandle()): AppViewModel {
        val model = AppViewModel(context, projects, settings, savedState, library)
        stores += ViewModelStore().apply { put("app", model) }
        return model
    }

    override fun close() {
        stores.forEach(ViewModelStore::clear)
        stores.clear()
    }
}

internal class TestSettingsRepository(initial: AppSettings) : SettingsRepository {
    override val settings = MutableStateFlow(initial)
    var saveFailure: ProjectStorageException? = null

    override suspend fun save(settings: AppSettings) {
        saveFailure?.let { throw it }
        this.settings.value = settings
    }
}

internal class TestProjectRepository : ProjectRepository {
    override val recentProjects = MutableStateFlow<List<StoredProject>>(emptyList())
    val documents = linkedMapOf<String, ProjectDocument>()
    val savedSnapshots = mutableListOf<ProjectDocument>()
    val backupFlags = mutableListOf<Boolean>()
    val loadFailureIds = mutableSetOf<String>()
    var saveFailure: ProjectStorageException? = null
    private val backups = mutableMapOf<String, ProjectDocument>()
    private val openedAt = mutableMapOf<String, Long>()
    private var clock = 10_000L
    private var nextId = 1

    override suspend fun create(name: String, template: ProjectTemplate, settings: ProjectSettings): ProjectDocument {
        val id = ProjectId("test-project-${nextId++}")
        val document = ProjectDocument(
            project = Project(id, name, ++clock, clock, settings = settings, template = template),
            layers = listOf(Layer(LayerId("base-layer"), id, "Base layer")),
        )
        documents[id.value] = document
        openedAt[id.value] = ++clock
        publish()
        return document
    }

    override suspend fun load(id: String): ProjectDocument {
        if (id in loadFailureIds) throw ProjectStorageException("The saved project is unreadable.")
        val document = documents[id] ?: throw ProjectStorageException("Project not found.")
        openedAt[id] = ++clock
        publish()
        return document
    }

    override suspend fun save(document: ProjectDocument, backupEnabled: Boolean): ProjectDocument {
        saveFailure?.let { throw it }
        val id = document.project.id.value
        if (backupEnabled) documents[id]?.let { backups[id] = it }
        val saved = document.copy(project = document.project.copy(updatedAtEpochMillis = ++clock))
        documents[id] = saved
        savedSnapshots += saved
        backupFlags += backupEnabled
        publish()
        return saved
    }

    override suspend fun importProject(input: InputStream): ProjectDocument {
        throw AssertionError("Configure an archive integration test in core:data for project import")
    }

    override suspend fun exportProject(id: String, output: OutputStream) {
        check(id in documents)
        output.write("test-export:$id".toByteArray())
    }

    override suspend fun restoreBackup(id: String): ProjectDocument {
        val previous = backups[id] ?: throw ProjectStorageException("No previous backup is available.")
        loadFailureIds -= id
        val restored = previous.copy(project = previous.project.copy(updatedAtEpochMillis = ++clock))
        documents[id] = restored
        openedAt[id] = ++clock
        publish()
        return restored
    }

    private fun publish() {
        recentProjects.value = documents.map { (id, document) ->
            StoredProject(
                id, document.project.name, document.project.template, document.project.settings.lengthUnit,
                document.project.updatedAtEpochMillis, openedAt[id] ?: 0L, thumbnailUri = null,
            )
        }.sortedByDescending { it.lastOpenedAtEpochMillis }
    }
}

internal class TestLibraryRepository : com.eezydraw.core.data.LibraryRepository {
    var catalog = com.eezydraw.core.model.LibraryCatalog()
    var failSave = false
    override suspend fun load() = catalog
    override suspend fun save(catalog: com.eezydraw.core.model.LibraryCatalog) {
        if(failSave) throw ProjectStorageException("Device library could not be saved")
        this.catalog=catalog
    }
}
