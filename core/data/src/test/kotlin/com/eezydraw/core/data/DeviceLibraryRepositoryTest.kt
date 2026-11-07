package com.eezydraw.core.data

import com.eezydraw.core.model.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class DeviceLibraryRepositoryTest {
    @get:Rule val files=TemporaryFolder()
    private val builtIn=BuiltInLibrary.items.first()
    // A self-contained 1x1 PNG; no URI or outside asset can be lost on restart/export.
    private val photo="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Zl1sAAAAASUVORK5CYII="
    private fun catalog()=LibraryCatalog(listOf(builtIn.copy(name="Local default"),builtIn.copy(id=LibraryItemId("custom"),
        name="My custom item",builtIn=false,thumbnailBase64=photo,mounting=MountingType.FREE,geometrySize=Size3(1.0,1.0,1.0))))
    @Test fun `defaults custom geometry and embedded photos survive repository restart and library backup`() = runBlocking {
        val directory=files.newFolder()
        val expected=catalog()
        DeviceLibraryRepository(directory).save(expected)
        assertEquals(expected,DeviceLibraryRepository(directory).load())
        val bytes=ByteArrayOutputStream().also {LibraryArchive.write(expected,it)}.toByteArray()
        val imported=LibraryArchive.decode(ByteArrayInputStream(bytes))
        assertEquals(expected,imported)
        assertEquals(photo,imported.find(LibraryItemId("custom"))!!.thumbnailBase64)
        val other=files.newFolder()
        DeviceLibraryRepository(other).save(imported)
        assertEquals(expected,DeviceLibraryRepository(other).load())
    }
    @Test fun `restore duplicate delete and undo snapshots persist after restart`() = runBlocking {
        val directory=files.newFolder()
        val store=DeviceLibraryRepository(directory)
        store.save(catalog())
        val copy=catalog().duplicate(LibraryItemId("custom"),LibraryItemId("duplicate"),"Copy")
        store.save(copy)
        assertEquals(copy,DeviceLibraryRepository(directory).load())
        val changed=copy.remove(LibraryItemId("custom")).restore(builtIn.id)
        store.save(changed)
        assertEquals(changed,DeviceLibraryRepository(directory).load())
        assertNull(DeviceLibraryRepository(directory).load().find(LibraryItemId("custom"),catalog().items))
        val restoredBackup=LibraryArchive.decode(ByteArrayInputStream(LibraryArchive.encode(changed)))
        assertEquals(changed,restoredBackup)
        store.save(copy) // Persisted undo result.
        assertEquals(copy,DeviceLibraryRepository(directory).load())
    }
    @Test fun `invalid import rejects versions formats duplicate ids truncated and excessive input`() {
        val valid=LibraryArchive.encode(catalog()).toString(Charsets.UTF_8)
        for(invalid in listOf(valid.replace("eezydraw-library","other"),valid.replace("\"version\":1","\"version\":9"),valid.dropLast(5),"{}")) {
            assertThrows(Exception::class.java) {LibraryArchive.decode(ByteArrayInputStream(invalid.toByteArray()))}
        }
        assertThrows(Exception::class.java) {LibraryArchive.decode(ByteArrayInputStream(ByteArray(LibraryArchive.MAX_BYTES+1)))}
    }
    @Test fun `failed encode retains the last committed file and missing library starts empty`() = runBlocking {
        val directory=files.newFolder()
        val store=DeviceLibraryRepository(directory)
        assertEquals(LibraryCatalog(),store.load())
        store.save(catalog())
        val huge=LibraryCatalog((1..60).map {builtIn.copy(id=LibraryItemId("large-$it"),builtIn=false,thumbnailBase64="A".repeat(350_000))})
        try {store.save(huge);fail("Expected size limit")} catch (_:IllegalArgumentException) { }
        assertEquals(catalog(),DeviceLibraryRepository(directory).load())
    }
    @Test fun `corrupt storage is reported without overwriting the stored library`() = runBlocking {
        val directory=files.newFolder()
        val path=File(directory,"library/catalog.eezy-library")
        path.parentFile!!.mkdirs()
        path.writeText("broken")
        try {DeviceLibraryRepository(directory).load();fail("Expected corruption error")} catch (_:Exception) { }
        assertEquals("broken",path.readText())
    }
}
