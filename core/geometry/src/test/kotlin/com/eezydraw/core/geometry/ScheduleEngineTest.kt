package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ScheduleEngineTest {
    private val p=ProjectId("takeoff");private val layer=LayerId("base");private val ground=LevelId("g");private val upper=LevelId("u")
    private val power=SystemId("power");private val hvac=SystemId("hvac")
    private val socket=LibraryItem(LibraryItemId("socket-test"),"Socket",Discipline.ELECTRICAL,"Devices",Size3(.1,.05,.1),unitRates=mapOf("USD" to 12.5))
    private fun element(id:String,body:ElementBody,level:LevelId=ground,system:SystemId?=power,at:Point3=Point3())=Element(ElementId(id),p,layer,body,level,system,name=id,transform=Transform3(at))
    private fun route(id:String,kind:RouteKind=RouteKind.CONDUIT,level:LevelId=ground,length:Double=5.0)=element(id,ElementBody.Route(kind,listOf(Point3(),Point3(length)),RouteSection.Circular(.025),ElevationSetting(0.0,ElevationReference.PROJECT_DATUM)),level)
    private fun doc(vararg elements:Element)=ProjectDocument(Project(p,"Take-off",0,0),
        levels=listOf(Level(ground,p,"Ground",0.0,.2,3.0),Level(upper,p,"Upper",3.0,.2,3.0)),layers=listOf(Layer(layer,p,"Base")),
        systems=listOf(BuildingSystem(power,p,"Power",Discipline.ELECTRICAL,0xFFCC0000),BuildingSystem(hvac,p,"HVAC",Discipline.HVAC,0xFF00CC00)),libraryItems=listOf(socket),elements=elements.toList())
    private fun definition(category:ScheduleCategory)=providedSchedules().first {it.category==category}

    @Test fun everyServiceMeasuresTrueTransformedThreeDimensionalLengthAndAllowance() {
        for(kind in RouteKind.entries) {
            val a=route("a",kind).let {e->e.copy(body=(e.body as ElementBody.Route).copy(path=listOf(Point3(),Point3(3.0,4.0),Point3(3.0,4.0,2.0))),transform=e.transform.copy(scale=Scale3(-2.0,2.0,1.0)))}
            val source=doc(a,route("b",kind,upper,3.0));val adjusted=source.copy(project=source.project.copy(settings=source.project.settings.copy(takeoff=TakeoffSettings(allowancePercent=10.0))))
            val result=ScheduleEngine.build(adjusted,definition(ScheduleCategory.ROUTES).copy(routeKind=kind))
            assertEquals(2,result.rows.size);assertEquals(15.0,result.measuredLength,1e-8);assertEquals(16.5,result.allowedLength,1e-8)
            assertEquals(16.5,result.quantities[TakeoffUnit.METRE]!!,1e-8)
        }
    }
    @Test fun equipmentUsesRoomWithHolesAndRoomsUseTransformedNetArea() {
        val boundary=Polygon2(listOf(Point2(0.0,0.0),Point2(4.0,0.0),Point2(4.0,4.0),Point2(0.0,4.0)),listOf(listOf(Point2(1.0,1.0),Point2(2.0,1.0),Point2(2.0,2.0),Point2(1.0,2.0))))
        val room=element("Office",ElementBody.Room(boundary))
        val a=element("Socket A",ElementBody.Component(socket.id,socket.defaultSize),at=Point3(.2,.2))
        val b=a.copy(id=ElementId("b"),name="Socket B",transform=Transform3(Point3(1.2,1.2)))
        val source=doc(room,a,b)
        val rows=ScheduleEngine.build(source,definition(ScheduleCategory.EQUIPMENT)).rows
        assertEquals("Office",rows.first {a.id in it.objectIds}.cells[ScheduleColumn.ROOM]);assertEquals("",rows.first {b.id in it.objectIds}.cells[ScheduleColumn.ROOM])
        assertEquals(15.0,ScheduleEngine.build(source,definition(ScheduleCategory.ROOMS)).area,1e-9)
        val scaled=source.copy(elements=listOf(room.copy(transform=Transform3(scale=Scale3(-2.0,3.0,1.0)))))
        assertEquals(90.0,ScheduleEngine.build(scaled,definition(ScheduleCategory.ROOMS)).area,1e-9)
    }
    @Test fun managedAndLegacyFittingsCountOnceAndLinkToServiceRows() {
        val r=route("route").let {e->e.copy(body=(e.body as ElementBody.Route).copy(fittings=listOf(RouteFitting(1,FittingKind.ELBOW_45))))}
        val fitting=BuiltInLibrary.items.first {it.fitting?.routeKind==RouteKind.CONDUIT && it.fitting?.kind==FittingKind.TEE}
        val item=element("tee",ElementBody.Component(fitting.id,fitting.defaultSize)).copy(properties=mapOf("routingOwner" to ParameterValue.Text(r.id.value)))
        val source=doc(r,item)
        assertEquals(2,ScheduleEngine.build(source,definition(ScheduleCategory.FITTINGS)).rows.sumOf {it.quantity}.toInt())
        val routes=ScheduleEngine.build(source,definition(ScheduleCategory.ROUTES).copy(routeKind=RouteKind.CONDUIT))
        assertEquals(2,routes.fittings);assertEquals(setOf(r.id,item.id),routes.rows.single().objectIds)
    }
    @Test fun groupingSortingAndSystemLevelTextAndVisibilityFilters() {
        val a=route("a");val b=route("b",level=upper);val c=route("c").withDisplay(ObjectDisplay.HIDDEN)
        val source=doc(a,b,c);val d=definition(ScheduleCategory.ROUTES).copy(routeKind=RouteKind.CONDUIT)
        assertEquals(10.0,ScheduleEngine.build(source,d).measuredLength,0.0)
        assertEquals(15.0,ScheduleEngine.build(source,d.copy(visibleOnly=false,grouping=emptyList())).measuredLength,0.0)
        assertEquals(1,ScheduleEngine.build(source,d.copy(levels=setOf(upper))).rows.size)
        assertTrue(ScheduleEngine.build(source,d.copy(systems=setOf(hvac))).rows.isEmpty())
        assertEquals(1,ScheduleEngine.build(source,d.copy(query="Upper")).rows.size)
        assertEquals("Upper",ScheduleEngine.build(source,d.copy(sort=ScheduleColumn.LEVEL,descending=true)).rows.first().cells[ScheduleColumn.LEVEL])
        val layerHidden=source.copy(layers=source.layers.map {it.copy(visible=false)})
        assertTrue(ScheduleEngine.build(layerHidden,d).rows.isEmpty())
        val levelHidden=source.copy(project=source.project.copy(settings=source.project.settings.copy(hiddenLevelIds=setOf(ground),showHiddenObjects=true)))
        assertEquals(5.0,ScheduleEngine.build(levelHidden,d).measuredLength,0.0)
    }
    @Test fun wallCutsCoalesceMitresAndKeepOpeningSizesAndPositions() {
        val wall=element("Wall",ElementBody.Wall(Point2(0.0,0.0),Point2(5.0,0.0),.2,3.0),system=null)
        val attachment=WallServiceAttachment(wall.id,WallFaceSide.LEFT,WallServiceOptions(WallServiceMode.CHASE))
        val r=route("Concealed").let {e->e.copy(body=(e.body as ElementBody.Route).copy(path=listOf(Point3(1.0,.09,.5),Point3(2.0,.09,1.5),Point3(3.0,.09,1.5)),wallService=attachment))}
        val opening=element("Opening",ElementBody.Component(socket.id,Size3(.2,.3,.4),wallService=attachment.copy(options=WallServiceOptions(WallServiceMode.OPENING))),at=Point3(3.5,0.0,1.0))
        val source=doc(wall,r,opening);val table=ScheduleEngine.build(source,definition(ScheduleCategory.WALL_CUTS))
        assertEquals(2,table.rows.size);val chase=table.rows.first {r.id in it.objectIds}
        assertEquals("35",chase.cells[ScheduleColumn.WIDTH]);assertEquals(1+kotlin.math.sqrt(2.0),chase.length,1e-8)
        assertTrue(chase.cells[ScheduleColumn.POSITION]!!.isNotBlank());assertTrue(wall.id in chase.objectIds)
        val changed=source.copy(elements=listOf(wall,opening));assertEquals(1,ScheduleEngine.build(changed,definition(ScheduleCategory.WALL_CUTS)).rows.size)
    }
    @Test fun rowInspectionRevealsHiddenObjectsWithoutChangingSourceVisibilityOrLocks() {
        val hidden=route("Hidden").withDisplay(ObjectDisplay.HIDDEN).withLock(LockMode.ALL)
        val source=doc(hidden).copy(layers=listOf(Layer(layer,p,"Base",visible=false)))
        val revealed=scheduleInspection(source,setOf(hidden.id))
        assertEquals(ObjectDisplay.NORMAL,ScenePresentation(revealed).display(revealed.elements.single()))
        assertEquals(LockMode.ALL,revealed.elements.single().lockMode)
        assertFalse(revealed.layers.single().visible);assertEquals(ObjectDisplay.HIDDEN,hidden.objectDisplay)
        assertEquals(source,scheduleInspection(source,emptySet()))
    }
    @Test fun boqKeepsUnitsSeparateAndUsesAllowanceAndCurrencySpecificRates() {
        val equipment=element("Socket",ElementBody.Component(socket.id,socket.defaultSize))
        val source=doc(equipment,route("run"));val d=definition(ScheduleCategory.BOQ)
        val initial=ScheduleEngine.build(source,d);val key=initial.rows.first {it.unit==TakeoffUnit.METRE}.rateKey!!
        val configured=source.copy(project=source.project.copy(settings=source.project.settings.copy(takeoff=TakeoffSettings(10.0,rates=mapOf(key to mapOf("USD" to 2.0))))))
        val result=ScheduleEngine.build(configured,d)
        assertEquals("23.50",result.pricedTotal.toPlainString());assertEquals(0,result.unpricedRows)
        assertEquals(5.5,result.quantities[TakeoffUnit.METRE]!!,0.0);assertEquals(1.0,result.quantities[TakeoffUnit.EACH]!!,0.0)
        val other=configured.copy(project=configured.project.copy(settings=configured.project.settings.copy(takeoff=configured.project.settings.takeoff.copy(currency="EUR"))))
        assertEquals(2,ScheduleEngine.build(other,d).unpricedRows)
    }
}
