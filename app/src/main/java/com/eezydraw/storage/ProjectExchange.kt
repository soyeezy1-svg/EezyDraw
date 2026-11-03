package com.eezydraw.storage

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.eezydraw.core.data.ProjectRepository
import com.eezydraw.core.model.AppSettings
import com.eezydraw.core.model.ProjectDocument
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** SAF copies supplement the private library. A failed external write never replaces a good copy. */
class ProjectExchange(private val context: Context, private val projects: ProjectRepository) {
    suspend fun mirror(document: ProjectDocument, settings: AppSettings) {
        val location = settings.saveLocationUri ?: return
        withContext(Dispatchers.IO) {
            val folder = DocumentFile.fromTreeUri(context, location.toUri())
                ?.takeIf { it.isDirectory && it.canWrite() }
                ?: throw IOException("The selected folder is not writable")
            val file = folder.createFile("application/octet-stream", fileName(document, settings, versioned = true))
                ?: throw IOException("A project copy could not be created")
            try {
                context.contentResolver.openOutputStream(file.uri, "w")?.use {
                    projects.exportProject(document.project.id.value, it)
                } ?: throw IOException("The project copy could not be written")
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }
    }

    companion object {
        fun fileName(document: ProjectDocument, settings: AppSettings, versioned: Boolean = false): String {
            val date = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(document.project.updatedAtEpochMillis))
            val base = settings.fileNamingPattern.replace("{name}", document.project.name).replace("{date}", date).replace("{id}", document.project.id.value)
                .replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), "_")
                .trim().trim('.').take(100).ifBlank { "EEZY-DRAW" }
            val suffix = if (versioned) "-${document.project.id.value}-${document.project.updatedAtEpochMillis}" else ""
            return "$base$suffix.eezydraw"
        }
    }
}
