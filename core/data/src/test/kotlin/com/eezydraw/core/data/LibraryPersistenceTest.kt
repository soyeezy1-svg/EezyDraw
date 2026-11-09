package com.eezydraw.core.data

import android.content.Context
import androidx.room.Room
import com.eezydraw.core.model.*
import com.eezydraw.core.editing.Ceilings
import com.eezydraw.core.editing.PresentationCommands
import com.eezydraw.core.editing.EditCommands
import com.eezydraw.core.editing.readWallMount
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

    @Test fun `wall angles mitred chases and both bend sizes survive native storage and import`() = runBlocking {
        val base=repository.create("Wall angles",ProjectTemplate.EMPTY,ProjectSettings());projectIds+=base.project.id.value
        val level=base.levels.first().id
        val wall=Element(ElementId("angle-wall"),base.project.id,base.layers.first().id,
            ElementBody.Wall(Point2(0.0,0.0),Point2(12.0,0.0),.2,3.0),level)
        var document=base.copy(elements=listOf(wall))
        val configs=listOf(RouteKind.CONDUIT,RouteKind.TRUNKING).associateWith {kind->defaultRoutingSettings(kind).copy(
            wallOptions=WallServiceOptions(WallServiceMode.CHASE,depth=.11),wallSnapAngles=WallSnapAngles.FORTY_FIVE)}
        for((index,entry) in configs.entries.withIndex()) {
            val (kind,config)=entry
            val picks=listOf(Point3(1.0,.08,.5),Point3(3.0,.08,.5),Point3(4.0,.08,1.5),Point3(4.0,.08,2.5),Point3(5.0,.08,2.5))
                .map {RoutePick(it.copy(x=it.x+index*6),config,level)}
            document=ServiceRouting.create(document,kind,picks).preview(document)
        }
        val conduit=configs.getValue(RouteKind.CONDUIT)
        document=ServiceRouting.create(document,RouteKind.CONDUIT,listOf(RoutePick(Point3(2.0,.0825,.5),conduit,level),RoutePick(Point3(2.0,.0825,2.0),conduit,level))).preview(document)
        val routes=document.elements.filter {it.body is ElementBody.Route}.mapTo(hashSetOf()){it.id}
        val shifted=com.eezydraw.core.editing.EditCommands.transform(document,routes,com.eezydraw.core.editing.EditDelta(translation=Point3(.1,0.0,.1))).preview(document)
        document=ServiceRouting.reconcile(document,WallServices.reconcile(document,shifted))
        val cuts=wallServiceCuts(document)
        assertTrue(cuts.any {it.outline.size==3});assertTrue(cuts.all {it.outline.size<=4})
        val saved=repository.save(document.copy(project=document.project.copy(settings=document.project.settings.copy(routeDefaults=configs,
            viewMode=ViewMode.XRAY,wallServices=WallServiceSettings(sectionHeight=1.4)))))
        database.close();reopen()
        val loaded=repository.load(saved.project.id.value)
        assertEquals(saved,loaded)
        assertEquals(configs,loaded.project.settings.routeDefaults)
        assertEquals(wallServiceCuts(saved),wallServiceCuts(loaded))
        assertTrue(wallDepthWarnings(loaded).isNotEmpty())
        assertEquals(loaded,ServiceRouting.reconcile(loaded,loaded))
        val archive=ByteArrayOutputStream().also {repository.exportProject(saved.project.id.value,it)}
        val imported=repository.importProject(ByteArrayInputStream(archive.toByteArray()));projectIds+=imported.project.id.value
        assertEquals(saved.elements,imported.elements.map {it.copy(projectId=saved.project.id)})
        assertEquals(saved.libraryItems,imported.libraryItems)
        assertEquals(wallServiceCuts(saved),wallServiceCuts(imported))
        assertEquals(configs,imported.project.settings.routeDefaults)
    }

    @Test fun `underfloor routing channels sleeves rises defaults schedule and warnings survive native storage`() = runBlocking {
        val base=repository.create("Underfloor",ProjectTemplate.EMPTY,ProjectSettings(floorServices=FloorServiceSettings(screedThickness=.07,maxDepthFraction=.45)))
        projectIds+=base.project.id.value
        val level=base.levels.first().id
        val slab=Element(ElementId("floor"),base.project.id,base.layers.first().id,ElementBody.Slab(Polygon2(listOf(Point2(0.0,0.0),Point2(8.0,0.0),Point2(8.0,8.0),Point2(0.0,8.0))),.2),level)
        val config=defaultRoutingSettings(RouteKind.CONDUIT).copy(floorInstallation=FloorInstallation.IN_SLAB,floorDepth=.08)
        var doc=base.copy(elements=listOf(slab))
        doc=ServiceRouting.create(doc,RouteKind.CONDUIT,listOf(RoutePick(Point3(1.0,1.0,-.08),config,level),RoutePick(Point3(4.0,1.0,-.08),config,level),RoutePick(Point3(4.0,4.0,-.08),config,level))).preview(doc)
        for(kind in listOf(FloorDeviceKind.FLOOR_BOX,FloorDeviceKind.SLAB_SLEEVE)) {
            val item=floorServiceLibraryItems().first {it.floorService==kind}
            val placement=previewLibraryPlacement(doc,item,Point3(5.0,4.0),level,id=ElementId(kind.name))
            doc=LibraryCommands.place(doc,placement).preview(doc)
        }
        val route=doc.elements.first {it.body is ElementBody.Route}
        doc=com.eezydraw.core.editing.FloorServices.rise(doc,route.id,ElementId("FLOOR_BOX")).preview(doc)
        val saved=repository.save(doc.copy(project=doc.project.copy(settings=doc.project.settings.copy(viewMode=ViewMode.XRAY,floorServices=doc.project.settings.floorServices.copy(showServices=false)))))
        database.close();reopen()
        val loaded=repository.load(saved.project.id.value)
        assertEquals(saved,loaded)
        assertEquals(floorSchedule(saved),floorSchedule(loaded))
        assertEquals(floorServiceCuts(saved,slab),floorServiceCuts(loaded,slab))
        assertEquals(floorServiceWarnings(saved),floorServiceWarnings(loaded))
        assertEquals(loaded,ServiceRouting.reconcile(loaded,loaded))
        val archive=ByteArrayOutputStream().also {repository.exportProject(saved.project.id.value,it)}
        val imported=repository.importProject(ByteArrayInputStream(archive.toByteArray()));projectIds+=imported.project.id.value
        assertEquals(saved.elements,imported.elements.map {it.copy(projectId=saved.project.id)})
        assertEquals(floorSchedule(saved),floorSchedule(imported))
        assertEquals(floorServiceCuts(saved,slab),floorServiceCuts(imported,imported.elements.first {it.id==slab.id}))
    }

    private suspend fun assertFailsStorage(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected invalid library data to be rejected")
        } catch (_: ProjectStorageException) { }
    }

    @Test fun `architectural openings faces individual wall display and ceiling options survive real storage and archives`()=runBlocking {
        val created=repository.create("Wall coordination",ProjectTemplate.EMPTY,ProjectSettings())
        projectIds+=created.project.id.value
        val level=created.levels.single();val layer=created.layers.first().id;val p=created.project.id
        val boundary=Polygon2(listOf(Point2(0.0,0.0),Point2(6.0,0.0),Point2(6.0,4.0),Point2(0.0,4.0)))
        val a=Element(ElementId("room-a"),p,layer,ElementBody.Room(boundary),level.id,name="Room A")
        val b=a.copy(id=ElementId("room-b"),name="Room B",transform=Transform3(Point3(6.0,0.0)))
        val rooms=created.copy(elements=listOf(a,b))
        var document=RoomWalls.generate(rooms,setOf(a.id,b.id),RoomWallOptions()).preview(rooms)
        fun add(slug:String,id:String,point:Point3,options:WallServiceOptions?=null) {
            val item=BuiltInLibrary.find(LibraryItemId("eezy.architecture.$slug"))!!
            val placement=previewLibraryPlacement(document,item,point,level.id,id=ElementId(id),wallOptions=options)
            document=LibraryCommands.place(document,placement).preview(document)
        }
        add("single-door","door",Point3(5.9,2.0),WallServiceOptions(WallServiceMode.OPENING,side=WallFaceSide.LEFT,swing=DoorSwing(DoorHinge.RIGHT,DoorDirection.OUT)))
        add("single-window","window",Point3(6.3,3.0),WallServiceOptions(WallServiceMode.OPENING,side=WallFaceSide.RIGHT))
        val socket=BuiltInLibrary.items.first {it.symbol==LibrarySymbol.SOCKET}
        val placement=previewLibraryPlacement(document,socket,Point3(6.3,.7),level.id,id=ElementId("socket"),wallOptions=defaultWallDeviceOptions(socket).copy(
            side=WallFaceSide.RIGHT,position=AlongWallPosition(WallPositionReference.NEAREST_DOOR,.3)))
        document=LibraryCommands.place(document,placement).preview(document)
        document=Ceilings.setLevel(document,level.id,CeilingOptions(CeilingType.FLAT,2.7,.12)).preview(document)
        document=Ceilings.setRoom(document,a.id,CeilingOptions(CeilingType.FLAT,2.65,.08)).preview(document)
        val shared=document.elements.first {it.id.value=="door"}.wallService()!!.wallId
        document=RoomWalls.reconcile(document,PresentationCommands.setDisplay(document,setOf(shared),ObjectDisplay.XRAY).preview(document))
        val ceiling=document.elements.first {(it.properties[CEILING_OWNER] as? ParameterValue.Text)?.value==a.id.value}
        document=PresentationCommands.setDisplay(document,setOf(ceiling.id),ObjectDisplay.HIDDEN).preview(document)
        document=document.copy(project=document.project.copy(settings=document.project.settings.copy(showCeilings=false)))
        val saved=repository.save(document)
        database.close();reopen()
        val loaded=repository.load(saved.project.id.value)
        assertEquals(saved,loaded)
        assertEquals(2,wallServiceCuts(loaded).count {it.mode==WallServiceMode.OPENING})
        assertEquals(DoorSwing(DoorHinge.RIGHT,DoorDirection.OUT),loaded.elements.first {it.id.value=="door"}.wallService()!!.options.swing)
        assertEquals(WallFaceSide.RIGHT,readWallMount(loaded,loaded.elements.first {it.id.value=="socket"})!!.side)
        assertEquals(ObjectDisplay.XRAY,loaded.elements.first {it.id==shared}.objectDisplay)
        assertFalse(loaded.project.settings.showCeilings)
        assertEquals(CeilingOptions(CeilingType.FLAT,2.7,.12),loaded.levels.single().ceiling)
        assertEquals(CeilingOptions(CeilingType.FLAT,2.65,.08),(loaded.elements.first {it.id==a.id}.body as ElementBody.Room).ceiling)
        val archive=ByteArrayOutputStream().also {repository.exportProject(saved.project.id.value,it)}
        val imported=repository.importProject(ByteArrayInputStream(archive.toByteArray()));projectIds+=imported.project.id.value
        assertNotEquals(loaded.project.id,imported.project.id)
        assertEquals(loaded.elements.map {it.copy(projectId=imported.project.id)},imported.elements)
        assertEquals(wallServiceCuts(loaded),wallServiceCuts(imported))
        val closed=EditCommands.delete(imported,setOf(ElementId("door"))).preview(imported)
        assertFalse(wallServiceCuts(closed).any {it.sourceId.value=="door"})
        val reset=PresentationCommands.resetAllDisplay(imported).preview(imported)
        assertTrue(reset.elements.all {it.objectDisplay==ObjectDisplay.NORMAL})
    }

    private fun reopen() {
        database = Room.databaseBuilder(context, ProjectDatabase::class.java, databaseName).build()
        repository = RoomProjectRepository(context, database)
    }
}
