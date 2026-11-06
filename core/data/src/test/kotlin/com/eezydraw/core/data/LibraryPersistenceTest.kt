package com.eezydraw.core.data

import android.content.Context
import androidx.room.Room
import com.eezydraw.core.model.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual Room/file/archive round trips protect instance parameters and library browsing state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryPersistenceTest {
    private lateinit var context: Context
    private lateinit var database: ProjectDatabase
    private lateinit var repository: RoomProjectRepository
    private val databaseName = "library-${UUID.randomUUID()}.db"
    private val projectIds = mutableSetOf<String>()

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        reopen()
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
        projectIds.forEach { File(File(context.filesDir, "projects"), it).deleteRecursively() }
    }

    @Test fun `placed library items and instance edits survive save reopen and portable import`() = runBlocking {
        val created = repository.create("Electrical library", ProjectTemplate.EMPTY, ProjectSettings())
        projectIds += created.project.id.value
        val switch = BuiltInLibrary.items.single { it.symbol == LibrarySymbol.SWITCH }
        val accessPoint = BuiltInLibrary.items.single { it.symbol == LibrarySymbol.WIFI }
        val wall = Element(ElementId("wall"), created.project.id, created.layers.first().id,
            ElementBody.Wall(Point2(0.0, 0.0), Point2(5.0, 0.0), .2, 3.0), levelId = created.levels.single().id)
        val editedParameters = switch.defaultParameters() + mapOf(
            LibraryParameterKeys.MANUFACTURER to ParameterValue.Text("Acme"),
            LibraryParameterKeys.MODEL to ParameterValue.Text("S-42"),
            LibraryParameterKeys.NAME_TAG to ParameterValue.Text("SW-01"),
            LibraryParameterKeys.MOUNTING_HEIGHT to ParameterValue.Number(1.35, Quantity.LENGTH),
            LibraryParameterKeys.COLOUR to ParameterValue.Colour(0xFF123456),
        )
        val component = Element(ElementId("switch"), created.project.id, created.layers.first().id,
            ElementBody.Component(switch.id, Size3(.1, .045, .12), editedParameters,
                HostAttachment(wall.id, HostMode.SURFACE, Point3(1.0, .1, 1.35))),
            levelId = created.levels.single().id, name = "SW-01",
            transform = Transform3(Point3(1.0, .1, 1.35)))
        val state = LibraryState(favoriteIds = setOf(switch.id, accessPoint.id),
            recentIds = listOf(switch.id), viewMode = LibraryViewMode.LIST)
        val saved = repository.save(created.copy(elements = listOf(wall, component),
            libraryItems = listOf(switch), libraryState = state))

        database.close()
        reopen()
        val loaded = repository.load(saved.project.id.value)
        assertEquals(saved, loaded)
        assertEquals(editedParameters, (loaded.elements.last().body as ElementBody.Component).parameters)
        assertEquals(listOf(switch), loaded.libraryItems)
        assertEquals(state, loaded.libraryState)
        // An unplaced built-in favorite is valid without copying the complete catalogue into a project.
        assertFalse(loaded.libraryItems.any { it.id == accessPoint.id })

        val archive = ByteArrayOutputStream().also { repository.exportProject(saved.project.id.value, it) }
        val imported = repository.importProject(ByteArrayInputStream(archive.toByteArray()))
        projectIds += imported.project.id.value
        assertNotEquals(saved.project.id, imported.project.id)
        assertEquals(saved.libraryItems, imported.libraryItems)
        assertEquals(state, imported.libraryState)
        assertEquals(saved.elements, imported.elements.map { it.copy(projectId = saved.project.id) })
        assertEquals(imported, repository.load(imported.project.id.value))
    }

    @Test fun `storage rejects dangling or repeated library preferences`() = runBlocking {
        val created = repository.create("Library validation", ProjectTemplate.EMPTY, ProjectSettings())
        projectIds += created.project.id.value
        val item = BuiltInLibrary.items.first()
        assertFailsStorage { repository.save(created.copy(libraryState = LibraryState(favoriteIds = setOf(LibraryItemId("missing"))))) }
        assertFailsStorage { repository.save(created.copy(libraryState = LibraryState(recentIds = listOf(item.id, item.id)))) }
        assertEquals(created.elements, repository.load(created.project.id.value).elements)
    }

    private suspend fun assertFailsStorage(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected invalid library data to be rejected")
        } catch (_: ProjectStorageException) { }
    }

    private fun reopen() {
        database = Room.databaseBuilder(context, ProjectDatabase::class.java, databaseName).build()
        repository = RoomProjectRepository(context, database)
    }
}
