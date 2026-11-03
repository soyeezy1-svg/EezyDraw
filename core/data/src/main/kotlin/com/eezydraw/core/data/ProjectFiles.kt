package com.eezydraw.core.data

import android.util.AtomicFile
import com.eezydraw.core.model.ProjectDocument
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Immutable revision names let Room publish a fully-written snapshot with one pointer update. */
internal class ProjectFiles(private val root: File) {
    fun directory(id: String): File {
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) {
            throw ProjectStorageException("The project identifier is invalid.")
        }
        return File(root, id).also { directory ->
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw ProjectStorageException("Project storage is unavailable.")
            }
        }
    }

    fun child(id: String, relative: String): File = safeChild(directory(id), relative)

    fun writeSnapshot(document: ProjectDocument): Pair<String, String> {
        val bytes = ProjectArchive.encodeDocument(document)
        val path = "revisions/${UUID.randomUUID()}.json"
        writeAtomic(child(document.project.id.value, path), bytes)
        return path to checksum(bytes)
    }

    fun readSnapshot(id: String, path: String, expectedChecksum: String): ProjectDocument {
        val file = child(id, path)
        if (!file.isFile || file.length() !in 1..MAX_DOCUMENT_BYTES) {
            throw ProjectStorageException("This project file is missing or damaged. Try restoring its backup.")
        }
        val bytes = AtomicFile(file).readFully()
        if (checksum(bytes) != expectedChecksum) {
            throw ProjectStorageException("This project file is damaged. Try restoring its backup.")
        }
        val document = ProjectArchive.decodeDocument(bytes)
        if (document.project.id.value != id) throw ProjectStorageException("The project file does not match its catalogue entry.")
        return document
    }

    fun writeAssets(id: String, assets: Map<String, ByteArray>) {
        assets.forEach { (relative, bytes) -> writeAtomic(assetFile(id, relative), bytes) }
    }

    fun readAssets(document: ProjectDocument): Map<String, ByteArray> {
        var total = 0L
        return document.assets.associate { asset ->
            val file = assetFile(document.project.id.value, asset.relativePath)
            if (!file.isFile || file.length() > MAX_ASSET_BYTES) {
                throw ProjectStorageException("A project attachment is missing or too large: ${asset.relativePath}")
            }
            total += file.length()
            if (total > MAX_ARCHIVE_BYTES) throw ProjectStorageException("Project attachments exceed the supported archive size.")
            val bytes = file.readBytes()
            if (asset.sha256 != null && !checksum(bytes).equals(asset.sha256, ignoreCase = true)) {
                throw ProjectStorageException("A project attachment is damaged: ${asset.relativePath}")
            }
            asset.relativePath to bytes
        }
    }

    fun assetFile(id: String, relative: String): File = safeChild(File(directory(id), "assets"), relative)

    fun deleteRevision(id: String, relative: String) { child(id, relative).delete() }

    fun removeProject(id: String) { directory(id).deleteRecursively() }

    fun prune(id: String, keep: Set<String>) {
        // Cleanup occurs only after publication. Its failure must not turn a durable save into an error.
        try {
            listOf("revisions", "previews").forEach { folder ->
                child(id, folder).listFiles()?.forEach { file ->
                    if ("$folder/${file.name}" !in keep) file.delete()
                }
            }
        } catch (_: IOException) {
            // A later save can retry removing these unreferenced generated files.
        } catch (_: SecurityException) {
            // Retain old generated files rather than endanger the published revision.
        }
    }

    companion object {
        const val MAX_DOCUMENT_BYTES = 8L * 1024 * 1024
        const val MAX_ASSET_BYTES = 16L * 1024 * 1024
        const val MAX_ARCHIVE_BYTES = 32L * 1024 * 1024

        fun checksum(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }

        fun writeAtomic(file: File, bytes: ByteArray) {
            val parent = file.parentFile ?: throw IOException("Invalid destination")
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create project directory")
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                output.write(bytes)
                atomic.finishWrite(output)
            } catch (failure: Exception) {
                atomic.failWrite(output)
                throw failure
            }
        }

        private fun safeChild(parent: File, relative: String): File {
            if (relative.isBlank() || '\\' in relative || ':' in relative ||
                relative.split('/').any { it.isBlank() || it == "." || it == ".." }) {
                throw ProjectStorageException("The project contains an unsafe attachment path.")
            }
            val root = parent.canonicalFile
            val result = File(root, relative).canonicalFile
            if (!result.path.startsWith(root.path + File.separator)) {
                throw ProjectStorageException("The project contains an unsafe attachment path.")
            }
            return result
        }
    }
}
