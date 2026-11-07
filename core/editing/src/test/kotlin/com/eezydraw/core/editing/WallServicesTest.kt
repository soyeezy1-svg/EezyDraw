package com.eezydraw.core.editing

import com.eezydraw.core.model.*
import com.eezydraw.core.geometry.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class WallServicesTest {
    private val project=ProjectId("services")
    private val layer=LayerId("services")
    private val level=Level(LevelId("ground"),project,"Ground",0.0,.2,3.2)
    private val wall=Element(ElementId("wall"),project,layer,ElementBody.Wall(Point2(0.0,0.0),Point2(6.0,0.0),.2,3.0),levelId=level.id,name="Wall 01")
    private val doc=ProjectDocument(Project(project,"Services",0,0),levels=listOf(level),layers=listOf(Layer(layer,project,"Services")),elements=listOf(wall))
    private val socket=BuiltInLibrary.items.first {it.symbol==LibrarySymbol.SOCKET}
    private fun device(options:WallServiceOptions=defaultWallDeviceOptions(socket)):ProjectDocument {
        val placement=previewLibraryPlacement(doc,socket,Point3(2.0,.1,.3),level.id,id=ElementId("socket"),wallOptions=options)
        return LibraryCommands.place(doc,placement).preview(doc)
    }
    @Test fun `wall device creates correctly sized recess and depth warning without changing wall`() {
        val placed=device()
        val cut=wallServiceCuts(placed).single()
        assertEquals(WallServiceMode.RECESS,cut.mode)
        assertEquals(socket.defaultSize.width+.01,cut.width,1e-8)
        assertEquals(socket.defaultSize.height+.01,cut.height,1e-8)
        assertEquals(socket.defaultSize.depth+.005,cut.depth,1e-8)
        assertEquals(wall,placed.elements.first())
        val deep=device(WallServiceOptions(WallServiceMode.RECESS,depth=.15))
        assertEquals(1,wallDepthWarnings(deep).size)
        assertEquals(.2,readProperties(deep,wall).size.y,1e-8)
    }
    @Test fun `wall route ortho constrains along height and follows the chosen surface`() {
        val settings=defaultRoutingSettings(RouteKind.CONDUIT).copy(wallOptions=WallServiceOptions(WallServiceMode.CHASE))
        val first=appendRoutingPick(doc,RouteKind.CONDUIT,emptyList(),Point3(1.0,.1,.5),settings,level)
        val picks=appendRoutingPick(doc,RouteKind.CONDUIT,first,Point3(1.05,.1,2.5),settings,level)
        assertEquals(1.0,picks.last().point.x,1e-9);assertEquals(2.5,picks.last().point.z,1e-9)
        val routed=ServiceRouting.create(doc,RouteKind.CONDUIT,picks).preview(doc)
        val route=routed.elements.first {it.body is ElementBody.Route}
        assertEquals(wall.id,route.wallService()!!.wallId)
        assertEquals(HostMode.CHASE,(route.body as ElementBody.Route).host!!.mode)
        assertTrue(wallServiceCuts(routed).isNotEmpty())
        val surface=WallServices.edit(routed,route.id,WallServiceOptions()).preview(routed)
        assertTrue(wallServiceCuts(surface).isEmpty())
        val at=measurementToWorld(surface.elements.first {it.id==route.id}.transform,(surface.elements.first {it.id==route.id}.body as ElementBody.Route).path.first())
        assertTrue(at.y>.1)
        val pick=WallServices.pick(doc,Point2(500.0,400.0),OrbitCamera(target=Point3(3.0,0.0,1.5),yaw=-PI/2,pitch=0.0),1000.0,800.0,level.id)!!
        assertEquals(WallFaceSide.RIGHT,pick.side);assertEquals(1.5,pick.point.z,1e-8)
    }
    @Test fun `moves deletes host transforms and undo redo update derived schedule`() {
        val placed=device();val e=placed.elements.last()
        val history=EditHistory()
        val moved=EditCommands.transform(placed,setOf(e.id),EditDelta(translation=Point3(1.0,0.0,.5))).preview(placed)
        val after=WallServices.reconcile(placed,moved)
        val command=EditCommands.between(placed,after,"Move wall device")
        val changed=history.execute(placed,command)
        assertEquals(wallServiceCuts(placed).single().position.x+1,wallServiceCuts(changed).single().position.x,1e-8)
        assertEquals(wallServiceCuts(placed).single().position.y+.5,wallServiceCuts(changed).single().position.y,1e-8)
        assertEquals(placed,history.undo(changed));assertEquals(changed,history.redo(placed))
        val gone=EditCommands.delete(changed,setOf(e.id)).preview(changed)
        assertTrue(wallServiceCuts(gone).isEmpty())
        val hostMoved=EditCommands.transform(placed,setOf(wall.id),EditDelta(translation=Point3(5.0,4.0))).preview(placed)
        val attached=WallServices.reconcile(placed,hostMoved)
        assertEquals(wallServiceCuts(placed).single().position,wallServiceCuts(attached).single().position)
        val locked=placed.copy(elements=placed.elements.map {if(it.id==e.id)it.withLock(LockMode.ALL)else it})
        try {WallServices.edit(locked,e.id,WallServiceOptions());fail("Locked device changed")}catch(_:IllegalArgumentException){}
    }
    @Test fun `ceiling feed anchors device and deleting it removes the generated feed chase`() {
        val placed=device(WallServiceOptions(WallServiceMode.RECESS,depth=.04,drop=WallDrop.CEILING))
        val connected=WallServices.connectDevice(placed,ElementId("socket"))
        val routes=connected.elements.filter {it.body is ElementBody.Route}
        assertEquals(1,routes.size)
        assertEquals(ElementId("socket"),(routes.single().body as ElementBody.Route).routing!!.endAnchor!!.elementId)
        val deviceMoved=EditCommands.transform(connected,setOf(ElementId("socket")),EditDelta(translation=Point3(0.0,0.0,.4))).preview(connected)
        val moved=ServiceRouting.reconcile(connected,WallServices.reconcile(connected,deviceMoved))
        val beforeRoute=routes.single();val afterRoute=moved.elements.first {it.id==beforeRoute.id}
        assertEquals(measurementToWorld(beforeRoute.transform,(beforeRoute.body as ElementBody.Route).path.last()).z+.4,measurementToWorld(afterRoute.transform,(afterRoute.body as ElementBody.Route).path.last()).z,1e-8)
        val deleted=EditCommands.delete(moved,setOf(ElementId("socket"))).preview(moved)
        val cleaned=ServiceRouting.reconcile(moved,WallServices.reconcile(moved,deleted))
        assertTrue(cleaned.elements.none {it.body is ElementBody.Route});assertTrue(wallServiceCuts(cleaned).isEmpty())
    }
    @Test fun `wall bends retain the in-face profile and their curved centreline receives a chase`() {
        val settings=defaultRoutingSettings(RouteKind.TRUNKING).copy(wallOptions=WallServiceOptions(WallServiceMode.CHASE))
        var points=appendRoutingPick(doc,RouteKind.TRUNKING,emptyList(),Point3(1.0,.1,1.0),settings,level)
        points=appendRoutingPick(doc,RouteKind.TRUNKING,points,Point3(3.0,.1,1.0),settings,level)
        points=appendRoutingPick(doc,RouteKind.TRUNKING,points,Point3(3.0,.1,2.0),settings,level)
        val routed=ServiceRouting.create(doc,RouteKind.TRUNKING,points).preview(doc)
        val definitions=routed.libraryItems.associateBy {it.id}
        val elbow=routed.elements.first {e->(e.body as? ElementBody.Component)?.let {definitions[it.libraryItemId]?.fitting?.kind==FittingKind.ELBOW_90}==true}
        val component=elbow.body as ElementBody.Component
        val item=definitions.getValue(component.libraryItemId)
        val local=item.fitting!!.centreline[item.fitting!!.centreline.size/2]
        val world=measurementToWorld(elbow.transform,local)
        val q=wallFrames(routed).single().coordinates(world)
        fun inside(ring:List<Point2>):Boolean {
            var result=false
            for(i in ring.indices){val a=ring[i];val b=ring[(i+1)%ring.size]
                if((a.y>q.z)!=(b.y>q.z) && q.x<(b.x-a.x)*(q.z-a.y)/(b.y-a.y)+a.x)result=!result}
            return result
        }
        assertTrue(wallServiceCuts(routed).any {inside(it.outline)})
        assertEquals(.05,item.fitting!!.section.let {(it as RouteSection.Rectangular).height},1e-8)
    }

    @Test fun `catalogue has flush surface and real circular or rectangular penetrations`() {
        val items=wallServiceLibraryItems()
        assertTrue(items.any {it.wallService?.mode==WallServiceMode.RECESS && it.name.contains("flush")})
        assertTrue(items.any {it.wallService?.mode==WallServiceMode.SURFACE && it.name.contains("surface")})
        val sleeve=items.first {it.wallService?.profile==OpeningProfile.CIRCULAR}
        val candidate=previewLibraryPlacement(doc,sleeve,Point3(3.0,.1,1.0),level.id,id=ElementId("sleeve"))
        val placed=LibraryCommands.place(doc,candidate).preview(doc)
        val cut=wallServiceCuts(placed).single()
        assertEquals(WallServiceMode.OPENING,cut.mode);assertEquals(.2,cut.toDepth-cut.fromDepth,1e-8)
        assertEquals(48,cut.outline.size)
        assertTrue(wallDepthWarnings(placed).isEmpty())
        assertTrue(items.any {it.name.contains("AC unit")});assertTrue(items.any {it.name.contains("extinguisher cabinet")})
    }
}
