package com.eezydraw.core.editing

import com.eezydraw.core.geometry.wallCutMesh
import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class WallRoutingAnglesTest {
    private val project=ProjectId("wall-angles")
    private val layer=LayerId("services")
    private val level=Level(LevelId("ground"),project,"Ground",0.0,.2,3.2)
    private val wall=Element(ElementId("wall"),project,layer,ElementBody.Wall(Point2(0.0,0.0),Point2(6.0,0.0),.2,3.0),level.id)
    private val doc=ProjectDocument(Project(project,"Angles",0,0),levels=listOf(level),layers=listOf(Layer(layer,project,"Services")),elements=listOf(wall))
    private fun settings(kind:RouteKind,mode:WallServiceMode=WallServiceMode.CHASE)=defaultRoutingSettings(kind).copy(
        wallOptions=WallServiceOptions(mode),wallSnapAngles=WallSnapAngles.FORTY_FIVE,bendDegrees=45)
    private fun draw(kind:RouteKind,mode:WallServiceMode=WallServiceMode.CHASE):ProjectDocument {
        val config=settings(kind,mode)
        var picks=emptyList<RoutePick>()
        for(p in listOf(Point3(1.0,.1,.5),Point3(3.0,.1,.5),Point3(4.0,.1,1.5),Point3(4.0,.1,2.5),Point3(5.0,.1,2.5)))
            picks=appendRoutingPick(doc,kind,picks,p,config,level)
        return ServiceRouting.create(doc,kind,picks).preview(doc)
    }
    private fun bends(document:ProjectDocument)=document.elements.mapNotNull {e->
        val b=e.body as? ElementBody.Component ?: return@mapNotNull null
        val definition=document.libraryItems.first {it.id==b.libraryItemId}.fitting ?: return@mapNotNull null
        if(definition.kind in setOf(FittingKind.ELBOW_45,FittingKind.ELBOW_90))e to definition else null
    }
    @Test fun `angle choices constrain the physical wall plane on rotated walls and both faces`() {
        val rotated=wall.copy(transform=Transform3(Point3(10.0,20.0,2.0),Rotation3(z=PI/3)))
        val document=doc.copy(elements=listOf(rotated));val frame=wallFrames(document).single()
        for(side in WallFaceSide.entries)for(mode in WallSnapAngles.entries) {
            val config=settings(RouteKind.CONDUIT).copy(wallSnapAngles=mode)
            val first=appendRoutingPick(document,RouteKind.CONDUIT,emptyList(),frame.world(1.0,frame.faceDepth(side),.5),config,level)
            val picks=appendRoutingPick(document,RouteKind.CONDUIT,first,frame.world(2.0,frame.faceDepth(side),1.3),config,level)
            val q=frame.coordinates(picks.last().point)
            when(mode) {
                WallSnapAngles.HORIZONTAL,WallSnapAngles.NINETY->{assertEquals(2.0,q.x,1e-8);assertEquals(.5,q.z,1e-8)}
                WallSnapAngles.FORTY_FIVE->{assertEquals(1.9,q.x,1e-8);assertEquals(1.4,q.z,1e-8)}
                WallSnapAngles.FREE->{assertEquals(2.0,q.x,1e-8);assertEquals(1.3,q.z,1e-8)}
            }
            assertEquals(if(side==WallFaceSide.LEFT).0175 else .1825,q.y,1e-8)
        }
        val legacy=settings(RouteKind.CONDUIT).copy(wallSnapAngles=null)
        assertEquals(WallSnapAngles.NINETY,legacy.effectiveWallSnapAngles())
        assertEquals(WallSnapAngles.FREE,legacy.copy(ortho=false).effectiveWallSnapAngles())
    }
    @Test fun `snapped diagonals clip at wall bounds without changing their angle`() {
        val config=settings(RouteKind.CONDUIT)
        val first=appendRoutingPick(doc,RouteKind.CONDUIT,emptyList(),Point3(5.5,.1,1.0),config,level)
        val next=appendRoutingPick(doc,RouteKind.CONDUIT,first,Point3(6.0,.1,2.0),config,level)
        assertEquals(6.0,next.last().point.x,1e-8)
        assertEquals(1.5,next.last().point.z,1e-8)
        val vertical=appendRoutingPick(doc,RouteKind.CONDUIT,first,Point3(5.55,.1,2.0),config.copy(wallSnapAngles=WallSnapAngles.NINETY),level)
        assertEquals(5.5,vertical.last().point.x,1e-8);assertEquals(2.0,vertical.last().point.z,1e-8)
    }
    @Test fun `conduit and trunking insert correctly oriented sized 45 and 90 wall bends`() {
        for(kind in listOf(RouteKind.CONDUIT,RouteKind.TRUNKING))for(mode in listOf(WallServiceMode.SURFACE,WallServiceMode.CHASE)) {
            val routed=draw(kind,mode);val bends=bends(routed)
            assertEquals(listOf(FittingKind.ELBOW_45,FittingKind.ELBOW_45,FittingKind.ELBOW_90),bends.map {it.second.kind})
            assertEquals(4,routed.elements.count {it.body is ElementBody.Route})
            val frame=wallFrames(routed).single()
            for((element,definition) in bends) {
                assertEquals(settings(kind).section(kind),definition.section)
                val points=definition.ports.map {frame.coordinates(measurementToWorld(element.transform,it.position))}
                assertEquals(points.first().y,points.last().y,1e-8)
                val input=measurementToWorld(element.transform,definition.ports.first().position)
                val output=measurementToWorld(element.transform,definition.ports.last().position)
                val angle=if(definition.kind==FittingKind.ELBOW_45)PI/4 else PI/2
                val dirs=definition.ports.map {p->measurementToWorld(element.transform.copy(translation=Point3()),p.direction)}
                assertEquals(cos(angle),-(dirs.first().x*dirs.last().x+dirs.first().y*dirs.last().y+dirs.first().z*dirs.last().z),1e-8)
                assertTrue(input!=output)
                val node=(element.properties[ROUTING_NODE] as ParameterValue.Text).value
                val incident=routed.elements.filter {e->val r=e.body as? ElementBody.Route;r?.routing?.let {it.startNode==node || it.endNode==node}==true}
                assertEquals(2,incident.size)
                for(section in incident) {
                    val r=section.body as ElementBody.Route
                    val physical=requireNotNull(r.fabricationPath)
                    val endpoint=measurementToWorld(section.transform,if(r.routing!!.startNode==node)physical.first()else physical.last())
                    assertTrue(listOf(input,output).any {p->sqrt((p.x-endpoint.x).pow(2)+(p.y-endpoint.y).pow(2)+(p.z-endpoint.z).pow(2))<1e-8})
                }
            }
            assertEquals(mode==WallServiceMode.CHASE,wallServiceCuts(routed).isNotEmpty())
        }
    }
    @Test fun `diagonal chase covers its centreline and retains depth warnings`() {
        for(kind in listOf(RouteKind.CONDUIT,RouteKind.TRUNKING)) {
            val routed=draw(kind);val frame=wallFrames(routed).single()
            val diagonal=routed.elements.first {e->val r=e.body as? ElementBody.Route; r!=null && abs(r.path.last().x)> .5 && abs(r.path.last().z)>.5}
            val cut=wallServiceCuts(routed).first {it.sourceId==diagonal.id}
            // The chase rectangle has sloped long edges rather than an axis-aligned bounding box.
            assertTrue(abs(cut.outline[1].x-cut.outline[0].x)>.5)
            assertTrue(abs(cut.outline[1].y-cut.outline[0].y)>.5)
            assertNotNull(wallCutMesh(frame,wallServiceCuts(routed)))
            val deep=WallServices.edit(routed,diagonal.id,WallServiceOptions(WallServiceMode.CHASE,depth=.15)).preview(routed)
            assertTrue(wallDepthWarnings(deep).isNotEmpty())
            assertEquals(.2,(deep.elements.first {it.id==wall.id}.body as ElementBody.Wall).thickness,0.0)
        }
    }
    @Test fun `editing a wall junction regenerates bend angle connected endpoints and chase with undo`() {
        val routed=draw(RouteKind.CONDUIT)
        val horizontal=routed.elements.first {it.body is ElementBody.Route}
        val oldEnd=measurementToWorld(horizontal.transform,(horizontal.body as ElementBody.Route).path.last())
        val command=ServiceRouting.edit(routed,horizontal.id,settings(RouteKind.CONDUIT),end=oldEnd.copy(x=4.0))
        val history=EditHistory();val edited=history.execute(routed,command)
        assertEquals(listOf(FittingKind.ELBOW_90,FittingKind.ELBOW_90),bends(edited).map {it.second.kind})
        assertNotEquals(wallServiceCuts(routed),wallServiceCuts(edited))
        assertEquals(edited,ServiceRouting.reconcile(edited,edited))
        assertEquals(routed,history.undo(edited));assertEquals(edited,history.redo(routed))
        val shifted=EditCommands.transform(routed,setOf(horizontal.id),EditDelta(translation=Point3(.2,0.0,0.0))).preview(routed)
        val moved=ServiceRouting.reconcile(routed,WallServices.reconcile(routed,shifted))
        assertNotEquals(wallServiceCuts(routed),wallServiceCuts(moved))
        assertEquals(3,bends(moved).size)
    }
    @Test fun `mitres follow connected edits and moves while fitting geometry remains curved`() {
        for(kind in listOf(RouteKind.CONDUIT,RouteKind.TRUNKING)) {
            val routed=draw(kind)
            val radius=if(kind==RouteKind.CONDUIT).0175 else .055
            val horizontal=routed.elements.first {it.body is ElementBody.Route}
            val oldEnd=measurementToWorld(horizontal.transform,(horizontal.body as ElementBody.Route).path.last())
            val edited=ServiceRouting.edit(routed,horizontal.id,settings(kind),end=oldEnd.copy(x=4.0)).preview(routed)
            val corners=wallServiceCuts(edited).flatMap {it.outline}
            assertTrue(corners.any {abs(it.x-(4+radius))<1e-8 && abs(it.y-(.5-radius))<1e-8})
            assertFalse(corners.any {abs(it.x-(3+radius*tan(PI/8)))<1e-8 && abs(it.y-(.5-radius))<1e-8})
            val ids=edited.elements.filter {it.body is ElementBody.Route}.mapTo(hashSetOf()){it.id}
            val shifted=EditCommands.transform(edited,ids,EditDelta(translation=Point3(.2,0.0,.1))).preview(edited)
            val moved=ServiceRouting.reconcile(edited,WallServices.reconcile(edited,shifted))
            val beforeCuts=wallServiceCuts(edited);val afterCuts=wallServiceCuts(moved)
            assertEquals(beforeCuts.size,afterCuts.size)
            beforeCuts.zip(afterCuts).forEach {(a,b)->
                assertEquals(a.depth,b.depth,0.0)
                a.outline.zip(b.outline).forEach {(p,q)->assertEquals(p.x+.2,q.x,1e-8);assertEquals(p.y+.1,q.y,1e-8)}
            }
            assertTrue(bends(moved).all {it.second.centreline.size>2})
            val history=EditHistory();val command=EditCommands.between(edited,moved,"Move mitred chase")
            assertEquals(moved,history.execute(edited,command));assertEquals(edited,history.undo(moved));assertEquals(moved,history.redo(edited))
        }
    }
    @Test fun `engine tees retain a clean union alongside mitred bends and unchanged fitting definitions`() {
        val routed=draw(RouteKind.CONDUIT)
        val config=settings(RouteKind.CONDUIT)
        val tee=ServiceRouting.create(routed,RouteKind.CONDUIT,listOf(RoutePick(Point3(2.0,.0825,.5),config,level.id),RoutePick(Point3(2.0,.0825,2.0),config,level.id))).preview(routed)
        val definitions=tee.libraryItems.associateBy {it.id}
        assertEquals(1,tee.elements.count {e->(e.body as? ElementBody.Component)?.let {definitions[it.libraryItemId]?.fitting?.kind==FittingKind.TEE}==true})
        val cuts=wallServiceCuts(tee)
        assertTrue(cuts.all {it.outline.size<=4})
        assertNotNull(wallCutMesh(wallFrames(tee).single(),cuts))
        assertEquals(tee,ServiceRouting.reconcile(tee,tee))
    }

}
