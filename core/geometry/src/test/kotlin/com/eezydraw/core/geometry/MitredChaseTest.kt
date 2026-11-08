package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.operation.buffer.BufferOp
import org.locationtech.jts.operation.buffer.BufferParameters
import org.locationtech.jts.operation.union.UnaryUnionOp
import kotlin.math.*

class MitredChaseTest {
    private val project=ProjectId("mitres")
    private val layer=LayerId("services")
    private val wall=Element(ElementId("wall"),project,layer,ElementBody.Wall(Point2(0.0,0.0),Point2(6.0,0.0),.2,3.0))
    private val base=ProjectDocument(Project(project,"Mitres",0,0),layers=listOf(Layer(layer,project,"Services")),elements=listOf(wall))
    private val factory=GeometryFactory()
    private fun route(id:String,points:List<Point2>,kind:RouteKind=RouteKind.CONDUIT,depth:Double=.04,side:WallFaceSide=WallFaceSide.LEFT):Element {
        val path=points.map {Point3(it.x,.08,it.y)}
        return Element(ElementId(id),project,layer,ElementBody.Route(kind,path,
            if(kind==RouteKind.CONDUIT)RouteSection.Circular(.025)else RouteSection.Rectangular(.1,.05),
            ElevationSetting(1.0,ElevationReference.PROJECT_DATUM),
            // Deliberately shortened: chase coverage must not depend on the physical fitting trims.
            fabricationPath=path.mapIndexed {i,p->if(i==0)p.copy(x=p.x+.01)else p.copy(x=p.x-.01)},
            wallService=WallServiceAttachment(wall.id,side,WallServiceOptions(WallServiceMode.CHASE,depth=depth))))
    }
    private fun polygon(cut:WallCut)=factory.createPolygon((cut.outline+cut.outline.first()).map {Coordinate(it.x,it.y)}.toTypedArray())
    private fun footprint(cuts:List<WallCut>):Geometry=UnaryUnionOp.union(cuts.map(::polygon))
    private fun expected(path:List<Point2>,radius:Double):Geometry {
        val parameters=BufferParameters().apply {joinStyle=BufferParameters.JOIN_MITRE;endCapStyle=BufferParameters.CAP_FLAT}
        return BufferOp.bufferOp(factory.createLineString(path.map {Coordinate(it.x,it.y)}.toTypedArray()),radius,parameters)
    }
    private fun volume(mesh:Shape.Mesh)=abs(mesh.triangles.indices.step(3).sumOf {i->
        val a=mesh.vertices[mesh.triangles[i]];val b=mesh.vertices[mesh.triangles[i+1]];val c=mesh.vertices[mesh.triangles[i+2]]
        (a.x*(b.y*c.z-b.z*c.y)+a.y*(b.z*c.x-b.x*c.z)+a.z*(b.x*c.y-b.y*c.x))/6
    })
    @Test fun `45 and 90 degree conduit and trunking cuts match exact mitre buffers with unchanged width depth`() {
        for(degrees in listOf(45,90))for(kind in listOf(RouteKind.CONDUIT,RouteKind.TRUNKING))for(side in WallFaceSide.entries) {
            val angle=degrees*PI/180
            val path=listOf(Point2(1.0,1.0),Point2(3.0,1.0),Point2(3+cos(angle)*1.5,1+sin(angle)*1.5))
            val elements=path.zipWithNext().mapIndexed {i,(a,b)->route("$i",listOf(a,b),kind,side=side)}
            val document=base.copy(elements=base.elements+elements)
            val cuts=wallServiceCuts(document);val actual=footprint(cuts)
            val radius=if(kind==RouteKind.CONDUIT).0175 else .055
            assertEquals(0.0,actual.symDifference(expected(path,radius)).area,1e-10)
            assertTrue(actual.coordinates.any {abs(it.x-(3+radius*tan(angle/2)))<1e-9 && abs(it.y-(1-radius))<1e-9})
            assertTrue(cuts.all {abs(it.depth-.04)<1e-9})
            assertEquals(3.6-3.5*radius*2*.04,volume(wallCutMesh(wallFrames(document).single(),cuts)!!),1e-9)
            // The corner wedges share only their seam; the mesh union removes the strip intersection.
            val wedges=cuts.filter {it.outline.size==3}
            assertEquals(2,wedges.size)
            assertEquals(0.0,polygon(wedges[0]).intersection(polygon(wedges[1])).area,1e-12)
        }
    }
    @Test fun `legacy multi point paths reversed bends and duplicate incoming chases keep the same sharp footprint`() {
        val path=listOf(Point2(1.0,1.0),Point2(3.0,1.0),Point2(4.0,2.0))
        val document=base.copy(elements=base.elements+route("legacy",path.reversed())+route("duplicate",path.take(2)))
        val cuts=wallServiceCuts(document)
        assertEquals(0.0,footprint(cuts).symDifference(expected(path,.0175)).area,1e-10)
        assertEquals(3.6-(2+sqrt(2.0))*.035*.04,volume(wallCutMesh(wallFrames(document).single(),cuts)!!),1e-9)
    }
    @Test fun `tee branches and overlapping routes union once with no internal junction faces`() {
        val main=route("main",listOf(Point2(1.0,1.0),Point2(5.0,1.0)))
        val branch=route("branch",listOf(Point2(3.0,1.0),Point2(3.0,2.5)))
        val overlap=route("overlap",listOf(Point2(2.0,1.0),Point2(4.0,1.0)))
        val document=base.copy(elements=base.elements+listOf(main,branch,overlap))
        val cuts=wallServiceCuts(document)
        val shape=footprint(cuts)
        assertEquals(1,shape.numGeometries)
        assertEquals(5.5*.035-.035*.035/2,shape.area,1e-10)
        val mesh=wallCutMesh(wallFrames(document).single(),cuts)!!
        assertEquals(3.6-shape.area*.04,volume(mesh),1e-9)
        val withoutOverlap=wallCutMesh(wallFrames(document).single(),wallServiceCuts(document.copy(elements=document.elements-overlap)))!!
        assertEquals(volume(withoutOverlap),volume(mesh),1e-10)
    }
    @Test fun `several chase depths and opposite faces retain independent depth bands and warnings`() {
        val main=route("main",listOf(Point2(1.0,1.0),Point2(5.0,1.0)))
        val branch=route("branch",listOf(Point2(3.0,1.0),Point2(3.0,2.5)),depth=.15)
        val back=route("back",listOf(Point2(1.0,1.0),Point2(5.0,1.0)),depth=.03,side=WallFaceSide.RIGHT)
        val document=base.copy(elements=base.elements+listOf(main,branch,back))
        val mesh=wallCutMesh(wallFrames(document).single(),wallServiceCuts(document))!!
        val removed=4*.035*(.04+.03)+1.5*.035*.15-.035*.035/2*.04
        assertEquals(3.6-removed,volume(mesh),1e-9)
        assertEquals(1,wallDepthWarnings(document).size)
    }
    @Test fun `x ray wireframe and capped section use the same mitred cut solid and visibility restores the wall`() {
        val path=listOf(Point2(1.0,1.0),Point2(3.0,1.0),Point2(3.0,2.5))
        val document=base.copy(elements=base.elements+route("route",path))
        fun view(mode:ViewMode,show:Boolean=true)=document.copy(project=document.project.copy(settings=document.project.settings.copy(
            viewMode=mode,wallServices=WallServiceSettings(showCuts=show,sectionHeight=1.5))))
        val shaded=buildSurfaceScene(view(ViewMode.SHADED),setOf(wall.id))
        val xray=buildSurfaceScene(view(ViewMode.XRAY),setOf(wall.id))
        assertEquals(shaded.triangleCount,xray.triangleCount)
        assertEquals(shaded.bounds,xray.bounds)
        assertTrue(buildDrawingScene(view(ViewMode.WIREFRAME)).segments.any {abs(it.a.x-3.0175)<1e-8 && abs(it.a.z-.9825)<1e-8})
        val section=wallCutMesh(wallFrames(document).single(),wallServiceCuts(document),1.5)!!
        assertEquals(6*.2*1.5-2.5*.035*.04,volume(section),1e-9)
        assertTrue(buildDrawingScene(view(ViewMode.SECTION)).segments.all {it.a.z<=1.5+1e-8 && it.b.z<=1.5+1e-8})
        assertTrue(buildSurfaceScene(view(ViewMode.SECTION),setOf(wall.id)).triangleCount>0)
        assertNotEquals(shaded,buildSurfaceScene(view(ViewMode.SHADED,false),setOf(wall.id)))
        assertEquals(.2,(document.elements.first().body as ElementBody.Wall).thickness,0.0)
    }
}
