package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class WallCutGeometryTest {
    private val project=ProjectId("wall-cuts")
    private val layer=LayerId("base")
    private val wall=Element(ElementId("wall"),project,layer,ElementBody.Wall(Point2(0.0,0.0),Point2(6.0,0.0),.2,3.0),name="W-01")
    private val base=ProjectDocument(Project(project,"Wall services",0,0),layers=listOf(Layer(layer,project,"Base")),elements=listOf(wall))
    private fun route(id:String="conduit",start:Point3=Point3(1.0,.08,1.0),end:Point3=Point3(5.0,.08,1.0),side:WallFaceSide=WallFaceSide.LEFT)=Element(ElementId(id),project,layer,
        ElementBody.Route(RouteKind.CONDUIT,listOf(start,end),RouteSection.Circular(.025),ElevationSetting(1.0,ElevationReference.PROJECT_DATUM),wallService=WallServiceAttachment(wall.id,side,WallServiceOptions(WallServiceMode.CHASE))),name="C-01")
    private fun volume(mesh:Shape.Mesh):Double = abs(mesh.triangles.indices.step(3).sumOf {i->
        val a=mesh.vertices[mesh.triangles[i]];val b=mesh.vertices[mesh.triangles[i+1]];val c=mesh.vertices[mesh.triangles[i+2]]
        (a.x*(b.y*c.z-b.z*c.y)+a.y*(b.z*c.x-b.x*c.z)+a.z*(b.x*c.y-b.y*c.x))/6
    })
    @Test fun `chase is a real void while nominal thickness and bounds remain unchanged`() {
        val doc=base.copy(elements=listOf(wall,route()))
        val cut=wallServiceCuts(doc).single()
        assertEquals(.035,cut.width.coerceAtMost(cut.height),1e-9)
        assertEquals(.035,cut.depth,1e-9)
        val mesh=wallCutMesh(wallFrames(doc).single(),listOf(cut))!!
        assertEquals(6*.2*3-4*.035*.035,volume(mesh),1e-8)
        assertTrue(mesh.vertices.any {abs(it.y-(.1-.035))<1e-8 && abs(it.z-(1-.0175))<1e-8})
        assertEquals(.2,(doc.elements.first().body as ElementBody.Wall).thickness,0.0)
        assertTrue(wallMeshEdges(mesh).isNotEmpty())
        val hidden=doc.copy(project=doc.project.copy(settings=doc.project.settings.copy(wallServices=WallServiceSettings(showCuts=false))))
        assertNotEquals(buildSurfaceScene(doc),buildSurfaceScene(hidden))
    }
    @Test fun `overlapping chases are unioned and do not create interior faces`() {
        val doc=base.copy(elements=listOf(wall,route(end=Point3(4.0,.08,1.0)),route("second",Point3(2.0,.08,1.0),Point3(5.0,.08,1.0))))
        val mesh=wallCutMesh(wallFrames(doc).single(),wallServiceCuts(doc))!!
        assertEquals(3.6-4*.035*.035,volume(mesh),1e-8)
    }
    @Test fun `recess and penetration depths carve the selected face and through opening`() {
        val box=Element(ElementId("box"),project,layer,ElementBody.Component(LibraryItemId("box-type"),Size3(.1,.04,.1),wallService=WallServiceAttachment(wall.id,WallFaceSide.RIGHT,WallServiceOptions(WallServiceMode.RECESS,clearance=0.0))),transform=Transform3(Point3(2.0,-.1,1.0)))
        val doc=base.copy(elements=listOf(wall,box))
        val cut=wallServiceCuts(doc).single();assertEquals(.16,cut.fromDepth,1e-9);assertEquals(.2,cut.toDepth,1e-9)
        assertEquals(3.6-.1*.1*.04,volume(wallCutMesh(wallFrames(doc).single(),listOf(cut))!!),1e-8)
        val opening=box.withWallService(box.wallService()!!.copy(options=WallServiceOptions(WallServiceMode.OPENING,clearance=0.0)))
        val opened=doc.copy(elements=listOf(wall,opening));val openingCut=wallServiceCuts(opened).single()
        assertEquals(3.6-.1*.1*.2,volume(wallCutMesh(wallFrames(opened).single(),listOf(openingCut))!!),1e-8)
    }
    @Test fun `section caps retain cuts and rotated hosts retain original wall volume`() {
        val doc=base.copy(elements=listOf(wall,route()))
        val section=wallCutMesh(wallFrames(doc).single(),wallServiceCuts(doc),1.2)!!
        assertTrue(section.vertices.all {it.z<=1.2+1e-8})
        assertEquals(6*.2*1.2-4*.035*.035,volume(section),1e-8)
        val rotated=wall.copy(transform=Transform3(Point3(100.0,50.0,4.0),Rotation3(z=PI/3)))
        val frame=wallFrames(base.copy(elements=listOf(rotated))).single()
        assertEquals(3.6,volume(wallCutMesh(frame,emptyList())!!),1e-8)
        val settings=doc.project.settings.copy(viewMode=ViewMode.SECTION,wallServices=WallServiceSettings(sectionHeight=1.2))
        assertTrue(buildDrawingScene(doc.copy(project=doc.project.copy(settings=settings))).segments.all {it.a.z<=1.2+1e-8 && it.b.z<=1.2+1e-8})
    }
    @Test fun `nonuniform mirrored hosts keep their nominal boundary when a cut mesh is built`() {
        val transformed=wall.copy(body=ElementBody.Wall(Point2(0.0,0.0),Point2(6.0,4.0),.2,3.0),transform=Transform3(Point3(1000.0,-300.0,3.0),Rotation3(z=.4),Scale3(-2.0,1.3,-1.0)))
        val document=base.copy(elements=listOf(transformed))
        val frame=wallFrames(document).single()
        val mesh=wallCutMesh(frame,emptyList())!!
        val actual=mesh.vertices.map {measurementToWorld(transformed.transform,it)}
        val expected=buildDrawingScene(document).bounds
        assertEquals(expected.min.x,actual.minOf {it.x},1e-6);assertEquals(expected.min.y,actual.minOf {it.y},1e-6);assertEquals(expected.min.z,actual.minOf {it.z},1e-6)
        assertEquals(expected.max.x,actual.maxOf {it.x},1e-6);assertEquals(expected.max.y,actual.maxOf {it.y},1e-6);assertEquals(expected.max.z,actual.maxOf {it.z},1e-6)
    }

    @Test fun `hiding room wall cuts restores the same full wall envelope`() {
        val room=wall.copy(body=ElementBody.Room(Polygon2(listOf(Point2(0.0,0.0),Point2(6.0,0.0),Point2(6.0,4.0),Point2(0.0,4.0)))))
        val side=RoomSideRef(room.id,0)
        val conduit=route().withWallService(WallServiceAttachment(room.id,WallFaceSide.LEFT,WallServiceOptions(WallServiceMode.CHASE),side))
        val document=base.copy(elements=listOf(room,conduit))
        val shown=buildSurfaceScene(document)
        val hidden=buildSurfaceScene(document.copy(project=document.project.copy(settings=document.project.settings.copy(wallServices=WallServiceSettings(showCuts=false)))))
        assertEquals(shown.bounds,hidden.bounds)
        assertTrue(shown.triangleCount>hidden.triangleCount)
    }

    @Test fun `depth warning uses requested depth and an opening is not a chase warning`() {
        val deep=route().withWallService(route().wallService()!!.copy(options=WallServiceOptions(WallServiceMode.CHASE,depth=.15)))
        val doc=base.copy(elements=listOf(wall,deep))
        assertEquals(1,wallDepthWarnings(doc).size)
        assertTrue(wallDepthWarnings(doc).single().contains("50%"))
        val changed=doc.copy(project=doc.project.copy(settings=doc.project.settings.copy(wallServices=WallServiceSettings(maxDepthFraction=.9))))
        assertTrue(wallDepthWarnings(changed).isEmpty())
    }
}
