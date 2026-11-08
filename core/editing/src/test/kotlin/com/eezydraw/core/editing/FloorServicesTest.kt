package com.eezydraw.core.editing

import com.eezydraw.core.geometry.*
import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test

class FloorServicesTest {
    private val p=ProjectId("floor")
    private val layer=LayerId("base")
    private val level=Level(LevelId("g"),p,"Ground",3.0,.2,3.0)
    private val slab=Element(ElementId("slab"),p,layer,ElementBody.Slab(Polygon2(listOf(Point2(0.0,0.0),Point2(8.0,0.0),Point2(8.0,8.0),Point2(0.0,8.0))),.2),level.id,transform=Transform3(Point3(z=3.0)))
    private val doc=ProjectDocument(Project(p,"Floor",0,0),levels=listOf(level),layers=listOf(Layer(layer,p,"Base")),elements=listOf(slab))
    private fun config(kind:RouteKind=RouteKind.CONDUIT,installation:FloorInstallation=FloorInstallation.IN_SLAB,depth:Double=.08)=defaultRoutingSettings(kind).copy(floorInstallation=installation,floorDepth=depth)
    private fun draw(document:ProjectDocument=doc,kind:RouteKind=RouteKind.CONDUIT,installation:FloorInstallation=FloorInstallation.IN_SLAB,depth:Double=.08):ProjectDocument {
        val settings=config(kind,installation,depth)
        var picks=emptyList<RoutePick>()
        for(point in listOf(Point3(1.0,1.0),Point3(4.0,1.0),Point3(4.0,4.0)))picks=appendRoutingPick(document,kind,picks,point,settings,level)
        return ServiceRouting.create(document,kind,picks).preview(document)
    }
    @Test fun `all four floor services draw at negative AFFL and preserve automatic fittings layers and history`() {
        for(kind in listOf(RouteKind.CONDUIT,RouteKind.TRUNKING,RouteKind.CABLE_TRAY,RouteKind.PIPE)) {
            val after=draw(kind=kind)
            val routes=after.elements.filter {it.body is ElementBody.Route}
            assertEquals(2,routes.size)
            assertTrue(routes.all {it.transform.translation.z==2.92})
            assertTrue(routes.all {after.layers.first {l->l.id==it.layerId}.name=="Underfloor services"})
            assertTrue(after.elements.any {it.body is ElementBody.Component})
            val history=EditHistory();val command=EditCommands.between(doc,after,"Floor route")
            assertEquals(after,history.execute(doc,command));assertEquals(doc,history.undo(after));assertEquals(after,history.redo(doc))
        }
        val upper=level.copy(id=LevelId("up"),elevation=6.0)
        val document=doc.copy(levels=listOf(level,upper))
        val first=appendRoutingPick(document,RouteKind.CONDUIT,emptyList(),Point3(1.0,1.0),config(),level)
        val next=appendRoutingPick(document,RouteKind.CONDUIT,first,Point3(4.0,1.0),config(),upper)
        assertEquals(3,next.size);assertEquals(5.92,next[1].point.z,1e-8)
    }
    @Test fun `depth edits update channels schedule and warnings without changing nominal slab dimensions`() {
        val after=draw(installation=FloorInstallation.IN_SCREED,depth=.02)
        assertTrue(floorServiceCuts(after,slab).isNotEmpty())
        val route=after.elements.first {it.body is ElementBody.Route}
        val deeper=ServiceRouting.edit(after,route.id,config(installation=FloorInstallation.IN_SCREED,depth=.05)).preview(after)
        assertNotEquals(floorServiceCuts(after,slab),floorServiceCuts(deeper,slab))
        assertTrue(floorServiceWarnings(deeper).any {"screed" in it})
        assertEquals(slab,deeper.elements.first {it.id==slab.id})
        assertTrue(floorSchedule(deeper).any {it.sourceId==route.id && it.depth>.049})
        val raised=draw(installation=FloorInstallation.RAISED_FLOOR,depth=.3)
        assertTrue(floorServiceCuts(raised,slab).isEmpty())
        assertTrue(floorServiceWarnings(raised).isEmpty())
    }
    @Test fun `sleeves cut full slab boxes recess and floor rises anchor targets`() {
        var drawing=draw()
        for(kind in listOf(FloorDeviceKind.FLOOR_BOX,FloorDeviceKind.SLAB_SLEEVE)) {
            val item=floorServiceLibraryItems().first {it.floorService==kind}
            val placement=previewLibraryPlacement(drawing,item,Point3(5.0,4.0),level.id,id=ElementId(kind.name))
            drawing=LibraryCommands.place(drawing,placement).preview(drawing)
        }
        val cuts=floorServiceCuts(drawing,slab)
        assertEquals(.2,cuts.first {it.sourceId==ElementId("SLAB_SLEEVE")}.depth,0.0)
        val route=drawing.elements.first {it.body is ElementBody.Route}
        val connected=FloorServices.rise(drawing,route.id,ElementId("FLOOR_BOX")).preview(drawing)
        assertTrue(connected.elements.filter {it.body is ElementBody.Route}.any {(it.body as ElementBody.Route).routing?.endAnchor?.elementId==ElementId("FLOOR_BOX")})
        assertTrue(connected.elements.filter {it.body is ElementBody.Route}.any {val b=it.body as ElementBody.Route;b.path.first().z!=b.path.last().z})
        assertTrue(floorSchedule(connected).any {it.sourceId==ElementId("SLAB_SLEEVE")})
        val deleted=EditCommands.delete(connected,setOf(ElementId("SLAB_SLEEVE"))).preview(connected)
        assertTrue(floorServiceCuts(deleted,slab).none {it.sourceId==ElementId("SLAB_SLEEVE")})
    }
    @Test fun `wall rises use native anchors and changing level or target updates risers`() {
        val wall=slab.copy(id=ElementId("wall"),body=ElementBody.Wall(Point2(0.0,0.0),Point2(8.0,0.0),.2,3.0))
        var drawing=draw(document=doc.copy(elements=listOf(slab,wall)))
        val item=BuiltInLibrary.items.first {it.symbol==LibrarySymbol.SOCKET}
        val placement=previewLibraryPlacement(drawing,item,Point3(5.0,.1,3.0),level.id,id=ElementId("socket"))
        drawing=LibraryCommands.place(drawing,placement).preview(drawing)
        val source=drawing.elements.first {it.body is ElementBody.Route}
        val connected=FloorServices.rise(drawing,source.id,placement.element.id).preview(drawing)
        val anchored=connected.elements.first {e->(e.body as? ElementBody.Route)?.routing?.endAnchor?.elementId==placement.element.id}
        val moved=EditCommands.transform(connected,setOf(placement.element.id),EditDelta(translation=Point3(.2,0.0,.3))).preview(connected)
        val reconciled=ServiceRouting.reconcile(connected,WallServices.reconcile(connected,moved))
        assertNotEquals(anchored,reconciled.elements.first {it.id==anchored.id})
    }
    @Test fun `crossing warnings distinguish same depth from stacked services and locked slab rejects cutting`() {
        fun section(id:String,a:Point3,b:Point3)=slab.copy(id=ElementId(id),body=ElementBody.Route(RouteKind.PIPE,listOf(a,b),RouteSection.Circular(.025),ElevationSetting(-.1,ElevationReference.ABOVE_FINISHED_FLOOR),routing=RouteTopology(id,"$id-a","$id-b",config(RouteKind.PIPE))))
        val a=section("a",Point3(1.0,2.0,2.9),Point3(6.0,2.0,2.9)).copy(transform=Transform3())
        val b=section("b",Point3(3.0,1.0,2.9),Point3(3.0,4.0,2.9)).copy(transform=Transform3())
        val crossing=doc.copy(elements=listOf(slab,a,b))
        assertTrue(floorServiceWarnings(crossing).any {"clash" in it})
        val stacked=crossing.copy(elements=listOf(slab,a,b.copy(transform=Transform3(Point3(z=-.2)))))
        assertFalse(floorServiceWarnings(stacked).any {"clash" in it})
        try {draw(document=doc.copy(elements=listOf(slab.withLock(LockMode.ALL))));fail("Locked slab was cut")}catch(_:IllegalArgumentException){}
    }
    @Test fun `visibility hides solid routes shows dashed plan and reveals floor Xray while toggle is undoable`() {
        val document=draw()
        val routes=document.elements.filter {it.body is ElementBody.Route}.map {it.id.value}.toSet()
        assertTrue(buildDrawingScene(document).segments.none {it.elementId in routes})
        assertTrue(buildDrawingScene(document,planSymbols=true).segments.count {it.elementId in routes}>2)
        val xray=document.copy(project=document.project.copy(settings=document.project.settings.copy(viewMode=ViewMode.XRAY)))
        assertTrue(buildDrawingScene(xray).segments.any {it.elementId in routes})
        val hidden=xray.copy(project=xray.project.copy(settings=xray.project.settings.copy(floorServices=FloorServiceSettings(showServices=false))))
        assertTrue(buildDrawingScene(hidden,planSymbols=true).segments.none {it.elementId in routes})
    }
    @Test fun `floor routing retains 45 degree bends tees reducers and cross level riser sections`() {
        val settings=config(RouteKind.PIPE).copy(bendDegrees=45)
        val main=ServiceRouting.create(doc,RouteKind.PIPE,listOf(RoutePick(Point3(1.0,1.0,2.92),settings,level.id),RoutePick(Point3(4.0,1.0,2.92),settings,level.id),RoutePick(Point3(4.0,4.0,2.92),settings,level.id))).preview(doc)
        val small=settings.copy(diameter=.025)
        val branch=ServiceRouting.create(main,RouteKind.PIPE,listOf(RoutePick(Point3(2.0,1.0,2.92),small,level.id),RoutePick(Point3(2.0,4.0,2.92),small,level.id))).preview(main)
        val kinds=branch.libraryItems.mapNotNull {it.fitting?.kind}.toSet()
        assertTrue(kinds.containsAll(listOf(FittingKind.ELBOW_45,FittingKind.TEE,FittingKind.REDUCER)))
        val upper=level.copy(id=LevelId("upper"),elevation=6.0)
        val document=doc.copy(levels=listOf(level,upper))
        val picks=appendRoutingPick(document,RouteKind.CONDUIT,appendRoutingPick(document,RouteKind.CONDUIT,emptyList(),Point3(1.0,1.0),config(),level),Point3(4.0,1.0),config(),upper)
        val cross=ServiceRouting.create(document,RouteKind.CONDUIT,picks).preview(document)
        assertTrue(cross.elements.any {e->(e.body as? ElementBody.Route)?.let {kotlin.math.abs(it.path.last().z-it.path.first().z)>2.9}==true})
    }

}
