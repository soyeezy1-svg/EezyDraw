package com.eezydraw.core.data

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.eezydraw.core.model.*
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RoomProjectRepository(context: Context, private val database: ProjectDatabase) : ProjectRepository {
    private val dao = database.projectDao()
    private val files = ProjectFiles(File(context.applicationContext.filesDir, "projects"))
    private val mutex = Mutex()

    override val recentProjects: Flow<List<StoredProject>> = dao.observeAll().map { rows ->
        rows.map { row ->
            StoredProject(
                row.id, row.name, ProjectTemplate.valueOf(row.template), LengthUnit.valueOf(row.units),
                row.updatedAt, row.lastOpenedAt, files.child(row.id, row.thumbnailFile).toURI().toString(),
            )
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun create(name: String, template: ProjectTemplate, settings: ProjectSettings): ProjectDocument = operation {
        if (name.isBlank() || name.trim().length > 120) {
            throw ProjectStorageException("Enter a project name between 1 and 120 characters.")
        }
        val document = createFromTemplate(name, template, settings)
        var committed = false
        try {
            val saved = writeRevision(document, previous = null, backupEnabled = false)
            committed = true
            saved
        } finally {
            if (!committed) withContext(NonCancellable) {
                if (dao.find(document.project.id.value) == null) files.removeProject(document.project.id.value)
            }
        }
    }

    override suspend fun load(id: String): ProjectDocument = operation {
        val row = dao.find(id) ?: throw ProjectStorageException("This project is no longer in your library.")
        val document = files.readSnapshot(id, row.currentFile, row.currentChecksum)
        files.readAssets(document)
        dao.touch(id, nextAccess())
        document
    }

    override suspend fun save(document: ProjectDocument, backupEnabled: Boolean): ProjectDocument = operation {
        val previous = dao.find(document.project.id.value)
            ?: throw ProjectStorageException("This project is not in your library. Import it before saving.")
        if (document.project.name.isBlank() || document.project.name.trim().length > 120) {
            throw ProjectStorageException("Enter a project name between 1 and 120 characters.")
        }
        files.readAssets(document)
        writeRevision(document, previous, backupEnabled)
    }

    override suspend fun importProject(input: InputStream): ProjectDocument = operation {
        val archive = ProjectArchive.read(input)
        val imported = cloneForImport(archive.document)
        val document = imported.copy(assets = imported.assets.map { asset ->
            // Imported CRC-checked bytes gain a durable checksum even if the sender omitted one.
            asset.copy(sha256 = asset.sha256 ?: sha256(archive.assets.getValue(asset.relativePath)))
        })
        var committed = false
        try {
            files.writeAssets(document.project.id.value, archive.assets)
            val saved = writeRevision(document, null, backupEnabled = false)
            committed = true
            saved
        } finally {
            if (!committed) withContext(NonCancellable) {
                if (dao.find(document.project.id.value) == null) files.removeProject(document.project.id.value)
            }
        }
    }

    override suspend fun exportProject(id: String, output: OutputStream) = operation {
        val row = dao.find(id) ?: throw ProjectStorageException("This project is no longer in your library.")
        val document = files.readSnapshot(id, row.currentFile, row.currentChecksum)
        ProjectArchive.write(output, document, files.readAssets(document))
    }

    override suspend fun restoreBackup(id: String): ProjectDocument = operation {
        val row = dao.find(id) ?: throw ProjectStorageException("This project is no longer in your library.")
        val backup = row.backupFile ?: throw ProjectStorageException("This project has no saved backup yet.")
        val checksum = row.backupChecksum ?: throw ProjectStorageException("This project's backup is unavailable.")
        val document = files.readSnapshot(id, backup, checksum)
        files.readAssets(document)
        writeRevision(document, row, backupEnabled = true)
    }

    private suspend fun writeRevision(
        document: ProjectDocument,
        previous: ProjectEntity?,
        backupEnabled: Boolean,
    ): ProjectDocument {
        val name = document.project.name.trim()
        val now = maxOf(System.currentTimeMillis(), document.project.createdAtEpochMillis, previous?.updatedAt ?: 0)
        val saved = document.copy(project = document.project.copy(name = name, updatedAtEpochMillis = now))
        validateDocument(saved)
        val id = saved.project.id.value
        val (revision, checksum) = files.writeSnapshot(saved)
        val thumbnail = "previews/${UUID.randomUUID()}.png"
        var committed = false
        try {
            ProjectFiles.writeAtomic(files.child(id, thumbnail), renderProjectPreview(saved))
            val backup = if (backupEnabled && previous != null) validBackup(previous) else null
            // Cancellation after Room commits must not delete the revision the catalogue points to.
            withContext(NonCancellable) {
                database.withTransaction {
                    dao.upsert(ProjectEntity(
                        id, name, saved.project.template.name, saved.project.settings.lengthUnit.name,
                        saved.project.createdAtEpochMillis, now, nextAccess(), revision, checksum, thumbnail,
                        backup?.first, backup?.second,
                    ))
                }
                committed = true
            }
            files.prune(id, setOfNotNull(revision, thumbnail, backup?.first))
            return saved
        } finally {
            if (!committed) {
                files.deleteRevision(id, revision)
                files.deleteRevision(id, thumbnail)
            }
        }
    }

    /** Never promote a corrupt primary into the only backup slot. */
    private fun validBackup(previous: ProjectEntity): Pair<String, String>? {
        val candidates = listOfNotNull(
            previous.currentFile to previous.currentChecksum,
            previous.backupFile?.let { path -> previous.backupChecksum?.let { path to it } },
        )
        return candidates.firstOrNull { (path, checksum) ->
            try {
                files.readSnapshot(previous.id, path, checksum)
                true
            } catch (_: IOException) {
                false
            }
        }
    }

    private suspend fun nextAccess(): Long = maxOf(System.currentTimeMillis(), dao.latestAccess() + 1)

    private suspend fun <T> operation(block: suspend () -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (failure: ProjectStorageException) {
                throw failure
            } catch (failure: IOException) {
                throw ProjectStorageException("Project storage could not be read or written. Your last saved revision is unchanged.", failure)
            } catch (failure: SQLiteException) {
                throw ProjectStorageException("The project catalogue could not be updated. Please try again.", failure)
            }
        }
    }
}

private fun cloneForImport(source: ProjectDocument): ProjectDocument {
    val id = ProjectId(UUID.randomUUID().toString())
    val now = System.currentTimeMillis()
    return source.copy(
        project = source.project.copy(id = id, createdAtEpochMillis = now, updatedAtEpochMillis = now),
        levels = source.levels.map { it.copy(projectId = id) },
        layers = source.layers.map { it.copy(projectId = id) },
        systems = source.systems.map { it.copy(projectId = id) },
        elements = source.elements.map { it.copy(projectId = id) },
        underlays = source.underlays.map { it.copy(projectId = id) },
        views = source.views.map { it.copy(projectId = id) },
        groups = source.groups.map { it.copy(projectId = id) },
        issues = source.issues.map { it.copy(projectId = id) },
        sheets = source.sheets.map { it.copy(projectId = id) },
        revisions = source.revisions.map { it.copy(projectId = id) },
        assets = source.assets.map { it.copy(projectId = id) },
    )
}
