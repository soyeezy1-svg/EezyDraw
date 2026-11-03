package com.eezydraw.core.data

import com.eezydraw.core.model.AppSettings
import com.eezydraw.core.model.LengthUnit
import com.eezydraw.core.model.ProjectDocument
import com.eezydraw.core.model.ProjectSettings
import com.eezydraw.core.model.ProjectTemplate
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow

data class StoredProject(
    val id: String,
    val name: String,
    val template: ProjectTemplate,
    val units: LengthUnit,
    val updatedAtEpochMillis: Long,
    val lastOpenedAtEpochMillis: Long,
    val thumbnailUri: String?,
)

interface ProjectRepository {
    val recentProjects: Flow<List<StoredProject>>
    suspend fun create(name: String, template: ProjectTemplate, settings: ProjectSettings): ProjectDocument
    suspend fun load(id: String): ProjectDocument
    suspend fun save(document: ProjectDocument, backupEnabled: Boolean = true): ProjectDocument
    suspend fun importProject(input: InputStream): ProjectDocument
    suspend fun exportProject(id: String, output: OutputStream)
    suspend fun restoreBackup(id: String): ProjectDocument
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun save(settings: AppSettings)
}

/** Messages describe recoverable storage failures and can be presented without a stack trace. */
class ProjectStorageException(message: String, cause: Throwable? = null) : IOException(message, cause)
