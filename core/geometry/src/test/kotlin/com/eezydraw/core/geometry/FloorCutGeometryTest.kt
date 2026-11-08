package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class FloorCutGeometryTest {
    private val project=ProjectId("slab")
    private val layer=LayerId("floor")
    private val level=Level(LevelId("g"),project,"Ground",0.0,.2,3.0)
    private val slab=Element(ElementId("slab"),project,layer,ElementBody.Slab(Polygon2(listOf(Point2(0.0,0.0),Point2(6.0,0.0),Point2(6.0,4.0),Point2(0.0,4.0))),.2),level.id)
    private val base=ProjectDocument(Project(project,"Channels",0,0),levels=listOf(level),layers=listOf(Layer(layer,project,"Floor")),elements=listOf(slab))
    private fun route(installation:FloorInstallation,points:List<Point3>,id:String="route")=slab.copy(id=ElementId(id),body=ElementBody.Route(RouteKind.CONDUIT,points,RouteSection.Circular(.02),ElevationSetting(-.04,ElevationReference.ABOVE_FINISHED_FLOOR),routing=RouteTopology(id,"$id-a","$id-b",defaultRoutingSettings(RouteKind.CONDUIT).copy(floorInstallation=installation,floorDepth=.04))))
    private fun signedVolume(mesh:Shape.Mesh)=mesh.triangles.indices.step(3).sumOf {i->
        val a=mesh.vertices[mesh.triangles[i]];val b=mesh.vertices[mesh.triangles[i+1]];val c=mesh.vertices[mesh.triangles[i+2]]
        (a.x*(b.y*c.z-b.z*c.y)+a.y*(b.z*c.x-b.x*c.z)+a.z*(b.x*c.y-b.y*c.x))/6
    }
    @Test fun `screed and slab channels remove actual volume with outward normals and unchanged thickness`() {
        for(kind in listOf(FloorInstallation.IN_SCREED,FloorInstallation.IN_SLAB)) {
            val doc=base.copy(elements=base.elements+route(kind,listOf(Point3(1.0,1.0,-.04),Point3(5.0,1.0,-.04))))
            val mesh=floorCutMesh(doc,slab)!!
            assertEquals(4.8-4*.03*.055,signedVolume(mesh),1e-8)
            assertTrue(mesh.vertices.any {abs(it.z+.055)<1e-9})
            assertEquals(.2,(slab.body as ElementBody.Slab).thickness,0.0)
            val xray=doc.copy(project=doc.project.copy(settings=doc.project.settings.copy(viewMode=ViewMode.XRAY)))
            val normal=buildSurfaceScene(doc,setOf(slab.id));val channels=buildSurfaceScene(xray,setOf(slab.id))
            assertNotEquals(normal,channels)
            val floorXray=doc.copy(elements=doc.elements.map {if(it.id==slab.id)it.withDisplay(ObjectDisplay.XRAY)else it})
            assertEquals(channels.triangleCount,buildSurfaceScene(floorXray,setOf(slab.id)).triangleCount)
            assertTrue(buildSurfaceScene(xray.copy(project=xray.project.copy(settings=xray.project.settings.copy(viewMode=ViewMode.SECTION))),setOf(slab.id)).triangleCount>0)
        }
    }
    @Test fun `mitred floor bends overlaps and slab holes keep one cut solid`() {
        val a=route(FloorInstallation.IN_SLAB,listOf(Point3(1.0,1.0,-.04),Point3(3.0,1.0,-.04)))
        val bend=route(FloorInstallation.IN_SLAB,listOf(Point3(3.0,1.0,-.04),Point3(3.0,3.0,-.04)),"bend")
        val overlap=route(FloorInstallation.IN_SLAB,listOf(Point3(1.5,1.0,-.04),Point3(2.5,1.0,-.04)),"overlap")
        val doc=base.copy(elements=base.elements+listOf(a,bend,overlap))
        assertEquals(4.8-4*.03*.055,signedVolume(floorCutMesh(doc,slab)!!),1e-8)
        val hole=slab.copy(body=(slab.body as ElementBody.Slab).copy(boundary=(slab.body as ElementBody.Slab).boundary.copy(holes=listOf(listOf(Point2(4.0,2.0),Point2(5.0,2.0),Point2(5.0,3.0),Point2(4.0,3.0))))))
        val holed=doc.copy(elements=doc.elements.map {if(it.id==slab.id)hole else it})
        assertEquals(4.6-4*.03*.055,signedVolume(floorCutMesh(holed,hole)!!),1e-8)
    }
    @Test fun `raised void services do not cut and explicit sleeve removes full thickness with clearance`() {
        val sleeve=BuiltInLibrary.items.first {it.floorService==FloorDeviceKind.SLAB_SLEEVE}
        val component=slab.copy(id=ElementId("sleeve"),body=ElementBody.Component(sleeve.id,Size3(.15,.15,.2)),transform=Transform3(Point3(2.0,2.0,-.2)))
        val doc=base.copy(libraryItems=listOf(sleeve),elements=base.elements+component+route(FloorInstallation.RAISED_FLOOR,listOf(Point3(1.0,1.0,-.3),Point3(5.0,1.0,-.3))))
        assertEquals(4.8-.16*.16*.2,signedVolume(floorCutMesh(doc,slab)!!),1e-8)
        assertEquals(1,floorServiceCuts(doc,slab).size)
        assertTrue(floorSchedule(doc).any {it.sourceId==component.id})
    }
    @Test fun `scaled mirrored slabs retain physical channel width and nominal dimensions`() {
        val transformed=slab.copy(transform=Transform3(Point3(10.0,20.0),Rotation3(z=.4),Scale3(-2.0,1.5,2.0)))
        val a=measurementToWorld(transformed.transform,Point3(1.0,1.0,-.02))
        val b=measurementToWorld(transformed.transform,Point3(3.0,1.0,-.02))
        val r=route(FloorInstallation.IN_SLAB,listOf(a,b))
        val doc=base.copy(elements=listOf(transformed,r))
        val mesh=floorCutMesh(doc,transformed)!!
        assertEquals(24.0*.2-4*.03*.055/6,abs(signedVolume(mesh)),1e-8)
        assertEquals(transformed.body,doc.elements.first().body)
    }

}
