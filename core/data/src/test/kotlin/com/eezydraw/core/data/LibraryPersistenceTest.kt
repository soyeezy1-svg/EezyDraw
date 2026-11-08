package com.eezydraw.core.data

import android.content.Context
import androidx.room.Room
import com.eezydraw.core.model.*
import com.eezydraw.core.editing.RoomWalls
import com.eezydraw.core.editing.WallServices
import com.eezydraw.core.editing.LibraryCommands
import com.eezydraw.core.editing.previewLibraryPlacement
import com.eezydraw.core.editing.RoutePick
import com.eezydraw.core.editing.ServiceRouting
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

    @Test fun `device catalogue browsing IDs survive save without placed definitions while repeated recents are rejected`() = runBlocking {
        val created = repository.create("Library validation", ProjectTemplate.EMPTY, ProjectSettings())
        projectIds += created.project.id.value
        val item = BuiltInLibrary.items.first()
        val deviceId = LibraryItemId("device-only-item")
        val browsing = LibraryState(favoriteIds=setOf(deviceId),recentIds=listOf(deviceId))
        val saved = repository.save(created.copy(libraryState=browsing))
        assertEquals(browsing,repository.load(created.project.id.value).libraryState)
        assertTrue(saved.libraryItems.isEmpty())
        assertFailsStorage { repository.save(created.copy(libraryState = LibraryState(recentIds = listOf(item.id, item.id)))) }
        assertEquals(created.elements, repository.load(created.project.id.value).elements)
    }

    @Test fun `sized fitting mesh ports route connection and parameters survive reopen and native archive`() = runBlocking {
        val created = repository.create("Pipe fitting connections", ProjectTemplate.EMPTY, ProjectSettings())
        projectIds += created.project.id.value
        val catalogueItem = BuiltInLibrary.items.single {
            it.fitting?.routeKind == RouteKind.PIPE && it.fitting?.kind == FittingKind.REDUCER
        }
        val inlet = RouteSection.Circular(.08)
        val outlet = RouteSection.Circular(.05)
        val adapted = requireNotNull(adaptedFittingItem(catalogueItem, inlet, outlet))
        val definition = requireNotNull(adapted.fitting)
        val meshes = adapted.geometry.filterIsInstance<Shape.Mesh>()
        assertNotEquals(catalogueItem.id, adapted.id)
        assertEquals(catalogueItem.id, definition.baseTypeId)
        assertTrue(meshes.isNotEmpty())
        assertEquals(listOf(inlet, outlet), definition.ports.map { it.section })
        val parameters = adapted.defaultParameters() + mapOf(
            LibraryParameterKeys.NAME_TAG to ParameterValue.Text("CHW-RD-04"),
            LibraryParameterKeys.MANUFACTURER to ParameterValue.Text("Example fittings"),
            LibraryParameterKeys.MODEL to ParameterValue.Text("R80-50"),
            LibraryParameterKeys.MOUNTING_HEIGHT to ParameterValue.Number(2.7, Quantity.LENGTH),
        )
        val route = Element(ElementId("pipe-run"), created.project.id, created.layers.first().id,
            ElementBody.Route(RouteKind.PIPE, listOf(Point3(), Point3(0.0, 4.0)), inlet,
                ElevationSetting(2.7, ElevationReference.ABOVE_FINISHED_FLOOR),
                fittings = listOf(RouteFitting(1, FittingKind.REDUCER, adapted.id, parameters))),
            levelId = created.levels.single().id, transform = Transform3(Point3(2.0, 3.0, 2.7)))
        val host = HostAttachment(route.id, HostMode.SURFACE, Point3(0.0, 4.0))
        val input = definition.ports.first().position
        val fitting = Element(ElementId("pipe-reducer"), created.project.id, created.layers.first().id,
            ElementBody.Component(adapted.id, adapted.defaultSize, parameters, host),
            levelId = created.levels.single().id, name = "CHW-RD-04",
            transform = Transform3(Point3(2.0 - input.x, 7.0 - input.y, 2.7 - input.z)))
        val state = LibraryState(setOf(catalogueItem.id, adapted.id), listOf(adapted.id), LibraryViewMode.LIST)
        val saved = repository.save(created.copy(elements = listOf(route, fitting),
            libraryItems = listOf(adapted), libraryState = state))

        database.close()
        reopen()
        val loaded = repository.load(saved.project.id.value)
        assertEquals(saved, loaded)
        val loadedDefinition = loaded.libraryItems.single()
        assertEquals(adapted, loadedDefinition)
        assertEquals(definition.ports, loadedDefinition.fitting?.ports)
        assertEquals(definition.centreline, loadedDefinition.fitting?.centreline)
        assertEquals(meshes, loadedDefinition.geometry.filterIsInstance<Shape.Mesh>())
        val loadedComponent = loaded.elements.last().body as ElementBody.Component
        assertEquals(host, loadedComponent.host)
        assertEquals(parameters, loadedComponent.parameters)
        assertEquals(ParameterValue.Number(.08, Quantity.LENGTH), loadedComponent.parameters[LibraryParameterKeys.DIAMETER])
        assertEquals(ParameterValue.Number(.05, Quantity.LENGTH), loadedComponent.parameters[LibraryParameterKeys.OUTLET_DIAMETER])
        assertEquals(adapted.defaultSize, loadedComponent.size)

        val archive = ByteArrayOutputStream().also { repository.exportProject(saved.project.id.value, it) }
        val imported = repository.importProject(ByteArrayInputStream(archive.toByteArray()))
        projectIds += imported.project.id.value
        assertNotEquals(saved.project.id, imported.project.id)
        assertEquals(listOf(adapted), imported.libraryItems)
        assertEquals(state, imported.libraryState)
        assertEquals(saved.elements, imported.elements.map { it.copy(projectId = saved.project.id) })
        assertEquals(imported, repository.load(imported.project.id.value))
    }

    @Test fun `routing graph defaults risers overrides and supports survive save and archive import`() = runBlocking {
        val created=repository.create("Connected routing",ProjectTemplate.EMPTY,ProjectSettings())
        projectIds+=created.project.id.value
        val config=defaultRoutingSettings(RouteKind.PIPE).copy(elevation=ElevationSetting(.4,ElevationReference.BELOW_SLAB))
        val topology=RouteTopology("network","a","b",config,fittingOverrides=mapOf("a" to FittingKind.OFFSET),suppressedFittings=setOf("b"),supportSpacing=1.2,supportKind=FittingKind.HANGER)
        val section=Element(ElementId("pipe"),created.project.id,created.layers.first().id,
            ElementBody.Route(RouteKind.PIPE,listOf(Point3(),Point3(z=3.0)),RouteSection.Circular(.08),config.elevation,routing=topology),levelId=created.levels.first().id)
        val item=BuiltInLibrary.items.first {it.fitting?.kind==FittingKind.HANGER && it.fitting?.routeKind==RouteKind.PIPE}
        val support=section.copy(id=ElementId("hanger"),body=ElementBody.Component(item.id,item.defaultSize,host=HostAttachment(section.id,HostMode.SURFACE,Point3(z=1.2))),properties=mapOf("routingOwner" to ParameterValue.Text(section.id.value)))
        val saved=repository.save(created.copy(elements=listOf(section,support),libraryItems=listOf(item),project=created.project.copy(settings=created.project.settings.copy(routeDefaults=mapOf(RouteKind.PIPE to config)))))
        database.close();reopen()
        assertEquals(saved,repository.load(saved.project.id.value))
        val archive=ByteArrayOutputStream().also {repository.exportProject(saved.project.id.value,it)}
        val imported=repository.importProject(ByteArrayInputStream(archive.toByteArray()));projectIds+=imported.project.id.value
        assertEquals(saved.elements,imported.elements.map {it.copy(projectId=saved.project.id)})
        assertEquals(topology,(imported.elements.first().body as ElementBody.Route).routing)
        assertEquals(saved.project.settings.routeDefaults,imported.project.settings.routeDefaults)
    }

    @Test fun `engine generated junctions reducers trims and supports round trip through real project storage`() = runBlocking {
        val base=repository.create("HVAC graph",ProjectTemplate.EMPTY,ProjectSettings());projectIds+=base.project.id.value
        val config=defaultRoutingSettings(RouteKind.DUCT)
        val level=base.levels.first().id
        fun point(x:Double,y:Double,settings:RoutingSettings=config)=RoutePick(Point3(x,y,2.5),settings,level)
        val main=ServiceRouting.create(base,RouteKind.DUCT,listOf(point(0.0,0.0),point(8.0,0.0),point(8.0,6.0))).preview(base)
        val branch=ServiceRouting.create(main,RouteKind.DUCT,listOf(point(4.0,0.0,config.copy(width=.6)),point(4.0,-4.0,config.copy(width=.6)))).preview(main)
        val section=branch.elements.first {it.body is ElementBody.Route}
        val supported=ServiceRouting.supports(branch,section.id,1.0).preview(branch)
        val saved=repository.save(supported)
        database.close();reopen()
        val loaded=repository.load(saved.project.id.value)
        assertEquals(saved,loaded)
        assertEquals(loaded,ServiceRouting.reconcile(loaded,loaded))
        val archive=ByteArrayOutputStream().also {repository.exportProject(saved.project.id.value,it)}
        val imported=repository.importProject(ByteArrayInputStream(archive.toByteArray()));projectIds+=imported.project.id.value
        assertEquals(saved.elements,imported.elements.map {it.copy(projectId=saved.project.id)})
        assertEquals(saved.libraryItems,imported.libraryItems)
        assertTrue(imported.elements.filter {it.body is ElementBody.Route}.all {(it.body as ElementBody.Route).fabricationPath!=null})
    }

    @Test fun `wall chases recesses penetrations warnings and schedule survive native save reopen and import`() = runBlocking {
        val created=repository.create("Wall services",ProjectTemplate.EMPTY,ProjectSettings(wallServices=WallServiceSettings(maxDepthFraction=.45,sectionHeight=1.4)))
        projectIds+=created.project.id.value
        val wall=Element(ElementId("services-wall"),created.project.id,created.layers.first().id,ElementBody.Wall(Point2(0.0,0.0),Point2(6.0,0.0),.2,3.0),levelId=created.levels.first().id,name="W-01")
        var drawing=created.copy(elements=listOf(wall))
        val socket=BuiltInLibrary.items.first {it.symbol==LibrarySymbol.SOCKET}
        val placement=previewLibraryPlacement(drawing,socket,Point3(2.0,.1,.5),wall.levelId,id=ElementId("socket"),wallOptions=WallServiceOptions(WallServiceMode.RECESS,depth=.11,drop=WallDrop.CEILING))
        drawing=LibraryCommands.place(drawing,placement).preview(drawing)
        drawing=WallServices.connectDevice(drawing,placement.element.id)
        val sleeve=wallServiceLibraryItems().first {it.wallService?.profile==OpeningProfile.CIRCULAR}
        val penetration=previewLibraryPlacement(drawing,sleeve,Point3(4.0,.1,1.0),wall.levelId,id=ElementId("sleeve"))
        drawing=LibraryCommands.place(drawing,penetration).preview(drawing)
        val schedule=wallServiceCuts(drawing);assertTrue(schedule.any {it.mode==WallServiceMode.CHASE});assertTrue(schedule.any {it.mode==WallServiceMode.RECESS});assertTrue(schedule.any {it.mode==WallServiceMode.OPENING})
        assertTrue(wallDepthWarnings(drawing).isNotEmpty())
        val saved=repository.save(drawing)
        database.close();reopen()
        val loaded=repository.load(saved.project.id.value)
        assertEquals(saved,loaded);assertEquals(schedule,wallServiceCuts(loaded))
        assertEquals(wallDepthWarnings(saved),wallDepthWarnings(loaded))
        val archive=ByteArrayOutputStream().also {repository.exportProject(saved.project.id.value,it)}
        val imported=repository.importProject(ByteArrayInputStream(archive.toByteArray()));projectIds+=imported.project.id.value
        assertEquals(schedule,wallServiceCuts(imported));assertEquals(saved.project.settings.wallServices,imported.project.settings.wallServices)
        assertEquals(saved.elements,imported.elements.map {it.copy(projectId=saved.project.id)})
    }

    @Test fun `generated room wall identities options profiles and materials survive native storage`() = runBlocking {
        val base=repository.create("Generated rooms",ProjectTemplate.EMPTY,ProjectSettings());projectIds+=base.project.id.value
        val built=RoomWalls.create(base,"room",listOf(Point3(),Point3(4.0,3.0)),base.levels.first().id,RoomWallOptions(.18,2.9,"Brick"),true).preview(base)
        val saved=repository.save(built)
        database.close();reopen()
        val loaded=repository.load(saved.project.id.value)
        assertEquals(saved,loaded)
        assertEquals(4,loaded.elements.count {it.body is ElementBody.Wall})
        assertTrue(loaded.elements.filter {it.body is ElementBody.Wall}.all {(it.body as ElementBody.Wall).joinProfile!=null})
        assertEquals(saved,RoomWalls.reconcile(saved,saved))
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
