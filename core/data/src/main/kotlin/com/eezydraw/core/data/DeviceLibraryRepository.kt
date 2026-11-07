package com.eezydraw.core.data

import android.util.AtomicFile
import com.eezydraw.core.model.LibraryCatalog
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Versioned, self-contained single-file backup. Photos are bounded inline thumbnails. */
object LibraryArchive {
    const val MAX_BYTES = 16 * 1024 * 1024
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    @Serializable private data class Envelope(val format: String = "eezydraw-library", val version: Int = 1, val catalog: LibraryCatalog)

    fun encode(catalog: LibraryCatalog): ByteArray {
        require(catalog.items.sumOf { it.thumbnailBase64?.length?.toLong() ?: 0L } <= MAX_BYTES) {
            "The library exceeds the 16 MiB backup limit."
        }
        return json.encodeToString(Envelope(catalog = catalog)).toByteArray(Charsets.UTF_8).also {
            require(it.size <= MAX_BYTES) { "The library exceeds the 16 MiB backup limit." }
        }
    }
    fun decode(input: InputStream): LibraryCatalog {
        val buffer = java.io.ByteArrayOutputStream()
        val block = ByteArray(8192)
        while (buffer.size() <= MAX_BYTES) {
            val count = input.read(block, 0, minOf(block.size, MAX_BYTES + 1 - buffer.size()))
            if (count < 0) break
            if (count == 0) continue
            buffer.write(block, 0, count)
        }
        val bytes = buffer.toByteArray()
        require(bytes.size <= MAX_BYTES) { "The library exceeds the 16 MiB backup limit." }
        val envelope = json.decodeFromString<Envelope>(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString())
        require(envelope.format == "eezydraw-library" && envelope.version == 1) { "Unsupported library file." }
        return envelope.catalog
    }
    fun write(catalog: LibraryCatalog, output: OutputStream) { output.write(encode(catalog)) }
}

interface LibraryRepository {
    suspend fun load(): LibraryCatalog
    suspend fun save(catalog: LibraryCatalog)
}

/** App-private files are retained across app updates; no external URI grants are needed. */
class DeviceLibraryRepository(directory: File) : LibraryRepository {
    private val file = AtomicFile(File(directory, "library/catalog.eezy-library"))
    override suspend fun load(): LibraryCatalog = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) LibraryCatalog()
        else file.openRead().use(LibraryArchive::decode)
    }
    override suspend fun save(catalog: LibraryCatalog) = withContext(Dispatchers.IO) {
        val bytes = LibraryArchive.encode(catalog)
        check(file.baseFile.parentFile!!.isDirectory || file.baseFile.parentFile!!.mkdirs()) { "Cannot create the library directory." }
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
    }
}
