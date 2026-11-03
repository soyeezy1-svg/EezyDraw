package com.eezydraw.core.data

import com.eezydraw.core.model.ProjectDocument
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class DecodedArchive(val document: ProjectDocument, val assets: Map<String, ByteArray>)

/** Portable v1 document archive. Limits apply before deserialization or writing into project storage. */
internal object ProjectArchive {
    private const val MAX_COMPRESSED = 32 * 1024 * 1024
    private const val MAX_EXPANDED = 32 * 1024 * 1024
    private const val MAX_DOCUMENT = 8 * 1024 * 1024
    private const val MAX_ASSET = 16 * 1024 * 1024
    private const val MAX_ENTRIES = 256
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val headerJson = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Header(val format: String, val version: Int)

    @Serializable
    private data class Envelope(val format: String = "eezydraw", val version: Int = 1, val document: ProjectDocument)

    fun read(input: InputStream): DecodedArchive = storageErrors {
        val compressed = readBounded(input, MAX_COMPRESSED)
        val expectedEntries = checkFooter(compressed)
        val files = linkedMapOf<String, ByteArray>()
        var expanded = 0L
        ZipInputStream(ByteArrayInputStream(compressed)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || !isAllowedEntry(entry.name) || files.containsKey(entry.name)) {
                    throw ProjectStorageException("The project archive has an invalid or duplicate entry.")
                }
                if (files.size >= MAX_ENTRIES) throw ProjectStorageException("The project archive has too many files.")
                val limit = if (entry.name == "document.json") MAX_DOCUMENT else MAX_ASSET
                if (entry.size > limit) throw ProjectStorageException("A project file exceeds the size limit.")
                val bytes = readBounded(zip, minOf(limit.toLong(), MAX_EXPANDED - expanded).toInt())
                expanded += bytes.size
                files[entry.name] = bytes
                zip.closeEntry() // Includes CRC validation for each fully consumed ZIP entry.
            }
        }
        if (files.size != expectedEntries) throw ProjectStorageException("The project archive is incomplete.")
        val documentBytes = files.remove("document.json")
            ?: throw ProjectStorageException("This file does not contain an EEZY DRAW project.")
        val document = decodeDocument(documentBytes)
        val assets = files.mapKeys { (path, _) -> path.removePrefix("assets/") }
        validateAssets(document, assets)
        DecodedArchive(document, assets)
    }

    fun write(output: OutputStream, document: ProjectDocument, assets: Map<String, ByteArray>) = storageErrors {
        validateDocument(document)
        validateAssets(document, assets)
        val documentBytes = encodeDocument(document)
        if (documentBytes.size > MAX_DOCUMENT || assets.size + 1 > MAX_ENTRIES ||
            documentBytes.size.toLong() + assets.values.sumOf { it.size.toLong() } > MAX_EXPANDED) {
            throw ProjectStorageException("The project exceeds the portable file size limit.")
        }
        // Buffering bounds the compressed result and keeps a failed encoding out of caller storage.
        val encoded = ByteArrayOutputStream()
        ZipOutputStream(object : FilterOutputStream(encoded) {
            override fun write(value: Int) {
                if (encoded.size() >= MAX_COMPRESSED) throw ProjectStorageException("The project archive is too large.")
                out.write(value)
            }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                if (encoded.size().toLong() + length > MAX_COMPRESSED) {
                    throw ProjectStorageException("The project archive is too large.")
                }
                out.write(buffer, offset, length)
            }
        }).use { zip ->
            fun entry(name: String, content: ByteArray) {
                zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                zip.write(content)
                zip.closeEntry()
            }
            entry("document.json", documentBytes)
            assets.toSortedMap().forEach { (name, content) -> entry("assets/$name", content) }
        }
        encoded.writeTo(output)
    }

    fun encodeDocument(document: ProjectDocument): ByteArray = storageErrors {
        validateDocument(document)
        val bytes = json.encodeToString(Envelope(document = document)).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_DOCUMENT) throw ProjectStorageException("The project data exceeds the size limit.")
        bytes
    }

    fun decodeDocument(bytes: ByteArray): ProjectDocument = storageErrors {
        if (bytes.size > MAX_DOCUMENT) throw ProjectStorageException("The project data exceeds the size limit.")
        val utf8 = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        // Probe the envelope without building a second object tree for the whole drawing.
        val header = headerJson.decodeFromString<Header>(utf8)
        if (header.format != "eezydraw" || header.version != 1) {
            throw ProjectStorageException("This project archive version is not supported.")
        }
        json.decodeFromString<Envelope>(utf8).document.also(::validateDocument)
    }

    private fun validateAssets(document: ProjectDocument, assets: Map<String, ByteArray>) {
        val expected = document.assets.associateBy { it.relativePath }
        if (assets.keys != expected.keys) throw ProjectStorageException("A referenced project attachment is missing or unexpected.")
        assets.forEach { (name, bytes) ->
            if (!isSafeAssetPath(name) || bytes.size > MAX_ASSET) {
                throw ProjectStorageException("A project attachment is invalid or too large.")
            }
            val expectedHash = expected.getValue(name).sha256
            if (expectedHash != null && !sha256(bytes).equals(expectedHash, ignoreCase = true)) {
                throw ProjectStorageException("A project attachment failed its checksum check.")
            }
        }
    }

    private fun isAllowedEntry(name: String): Boolean = name == "document.json" ||
        (name.startsWith("assets/") && isSafeAssetPath(name.removePrefix("assets/")))

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) return output.toByteArray()
            if (read == 0) continue
            if (output.size().toLong() + read > limit) throw ProjectStorageException("The project file exceeds the size limit.")
            output.write(buffer, 0, read)
        }
    }

    /** Require a complete single-disk ZIP footer, so truncated saves cannot masquerade as archives. */
    private fun checkFooter(bytes: ByteArray): Int {
        fun u16(index: Int) = (bytes[index].toInt() and 255) or ((bytes[index + 1].toInt() and 255) shl 8)
        fun u32(index: Int): Long = (0..3).fold(0L) { sum, n -> sum or ((bytes[index + n].toLong() and 255) shl (8 * n)) }
        if (bytes.size < 22) throw ProjectStorageException("The project file is not a complete archive.")
        for (index in bytes.size - 22 downTo maxOf(0, bytes.size - 22 - 65535)) {
            if (u32(index) != 0x06054b50L || index + 22 + u16(index + 20) != bytes.size) continue
            val count = u16(index + 10)
            if (u16(index + 4) != 0 || u16(index + 6) != 0 || u16(index + 8) != count ||
                count !in 1..MAX_ENTRIES || u32(index + 12) + u32(index + 16) != index.toLong()) {
                throw ProjectStorageException("This project archive layout is not supported.")
            }
            return count
        }
        throw ProjectStorageException("The project file is not a complete archive.")
    }

    private inline fun <T> storageErrors(block: () -> T): T = try {
        block()
    } catch (failure: ProjectStorageException) {
        throw failure
    } catch (failure: java.io.IOException) {
        throw ProjectStorageException("The project file is damaged or could not be accessed.", failure)
    } catch (failure: SerializationException) {
        throw ProjectStorageException("The project data is damaged or not supported.", failure)
    } catch (failure: IllegalArgumentException) {
        throw ProjectStorageException("The project data is invalid.", failure)
    }
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
