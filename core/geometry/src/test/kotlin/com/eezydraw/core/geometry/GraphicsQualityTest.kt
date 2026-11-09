package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class GraphicsQualityTest {
    @Test fun tightSceneRangeUsesDepthPrecisionWithoutClippingCorners() {
        val bounds=Bounds3(Point3(-3.0,-2.0,0.0),Point3(3.0,2.0,3.0))
        for(projection in Projection.entries) {
            val camera=OrbitCamera(distance=20.0,projection=projection).withSceneClipping(bounds)
            val clip=camera.clipDistances()
            assertTrue(clip.near>1.0);assertTrue(clip.far/clip.near<8)
            for(x in listOf(bounds.min.x,bounds.max.x))for(y in listOf(bounds.min.y,bounds.max.y))for(z in listOf(bounds.min.z,bounds.max.z)) {
                val p=Point3(x,y,z);val screen=camera.project(p,1200.0,800.0)!!
                assertTrue(screen.z in -1.0..1.0)
                val m=camera.viewProjectionMatrix(1.5);val dx=x-camera.target.x;val dy=y-camera.target.y;val dz=z-camera.target.z
                val projectedZ=(m[2]*dx+m[6]*dy+m[10]*dz+m[14])/(m[3]*dx+m[7]*dy+m[11]*dz+m[15])
                assertEquals(screen.z,projectedZ,2e-6)
            }
        }
    }
    @Test fun roamAndWalkKeepCloseSurfacesAndLimitAnInsideCamerasHorizon() {
        val camera=OrbitCamera(target=Point3(0.0,0.0,1.6),yaw=0.0,pitch=0.0,distance=1.0,projection=Projection.PERSPECTIVE)
        val local=camera.withSceneClipping(Bounds3(Point3(-10.0,-10.0),Point3(10.0,10.0,3.0)))
        assertTrue(local.clipDistances().near<=.02)
        assertNotNull(local.project(Point3(.95,0.0,1.6),1000.0,700.0))
        val huge=camera.withSceneClipping(Bounds3(Point3(-1e6,-1e6),Point3(1e6,1e6,100.0)))
        assertEquals(500.0,huge.clipDistances().far,0.0)
        assertTrue(huge.clipDistances().far/huge.clipDistances().near<=25_000)
        assertTrue(local.walk(.1,.1).viewProjectionMatrix(1.4).all {it.isFinite()})
    }
    @Test fun clippingIsDatumIndependentAndWorksForMillimetreScenes() {
        val b=Bounds3(Point3(-1.0,-2.0),Point3(1.0,2.0,3.0));val offset=1e9
        val camera=OrbitCamera(distance=12.0)
        val translated=camera.copy(target=Point3(offset,offset,offset))
        val shifted=Bounds3(Point3(b.min.x+offset,b.min.y+offset,b.min.z+offset),Point3(b.max.x+offset,b.max.y+offset,b.max.z+offset))
        assertEquals(adaptiveClip(camera,b),adaptiveClip(translated,shifted))
        val tiny=OrbitCamera(distance=.003,orthographicHeight=.002,projection=Projection.PERSPECTIVE).withSceneClipping(Bounds3(Point3(-.001,-.001),Point3(.001,.001,.001)))
        assertTrue(tiny.viewProjectionMatrix(1.0).all {it.isFinite()});assertNotNull(tiny.project(Point3(),100.0,100.0))
    }
    @Test fun cameraLightRotatesWithTheViewerAndSoftAmbientKeepsOppositeSidesReadable() {
        val a=OrbitCamera(yaw=0.0);val b=a.copy(yaw=PI/2)
        val x=cameraKeyLight(a);val y=cameraKeyLight(b)
        assertEquals(-x.y,y.x,1e-9);assertEquals(x.x,y.y,1e-9);assertEquals(x.z,y.z,1e-9)
        for(strength in LightingStrength.entries)for(n in listOf(Point3(1.0,0.0),Point3(0.0,1.0),Point3(z=1.0),Point3(-1.0,0.0)))
            assertTrue(matteIllumination(n,x,strength) in .32..1.0)
        val key=cameraKeyLight(OrbitCamera())
        assertTrue(abs(matteIllumination(Point3(1.0,0.0),key,LightingStrength.NORMAL)-matteIllumination(Point3(0.0,1.0),key,LightingStrength.NORMAL))>.1)
    }
    @Test fun thinDetailFadesBeforeItCanShimmerAndQualityLevelsHaveRealBudgets() {
        assertEquals(listOf(0,2,4),RenderQuality.entries.map {requestedSamples(it,true)})
        assertTrue(RenderQuality.entries.all {requestedSamples(it,false)==0})
        assertEquals(0.0,textureStrength(TextureDetail.HIGH,4.0),0.0)
        assertEquals(1.0,matteTexture(Point3(1.0,2.0,3.0),TextureDetail.LOW,0.0),0.0)
        val strength=textureStrength(TextureDetail.HIGH,120.0)
        assertTrue(strength>0)
        assertEquals(matteTexture(Point3(.2,.3,.4),TextureDetail.HIGH,strength),matteTexture(Point3(64.2,64.3,64.4),TextureDetail.HIGH,strength),1e-12)
        assertTrue(lodPixelThreshold(RenderQuality.PERFORMANCE)>lodPixelThreshold(RenderQuality.QUALITY))
        val bounds=Bounds3(Point3(),Point3(1.0,1.0,1.0))
        val near=OrbitCamera(distance=4.0,projection=Projection.PERSPECTIVE)
        assertTrue(projectedDiameter(bounds,near,1000)>projectedDiameter(bounds,near.copy(distance=400.0),1000))
    }
    @Test fun renderLodKeepsActualEdgesAndNeverReplacesWallOrCeilingGeometry() {
        val p=ProjectId("p");val layer=LayerId("l")
        val e=Element(ElementId("e"),p,layer,ElementBody.Component(LibraryItemId("box"),Size3(1.0,1.0,1.0)))
        assertTrue(renderLodEligible(e))
        assertFalse(renderLodEligible(e.copy(body=ElementBody.Wall(Point2(0.0,0.0),Point2(2.0,0.0),.2,3.0))))
        assertFalse(renderLodEligible(e.copy(properties=mapOf(CEILING_OBJECT to ParameterValue.Flag(true)))))
        val vertices=FloatArray(14*80){it.toFloat()};val coarse=coarseWire(vertices)
        assertTrue(coarse.size<vertices.size)
        for(i in coarse.indices step 14)assertTrue((vertices.indices step 14).any {j->coarse.slice(i until i+14)==vertices.slice(j until j+14)})
    }
    @Test fun wireAndSurfaceUseTheSameMaterialPrecedenceAndRoleDefaults() {
        val p=ProjectId("p");val l=LayerId("l");val layer=Layer(l,p,"Base")
        val wall=Element(ElementId("wall"),p,l,ElementBody.Wall(Point2(0.0,0.0),Point2(4.0,0.0),.2,3.0))
        val floor=wall.copy(id=ElementId("floor"),body=ElementBody.Slab(Polygon2(listOf(Point2(0.0,0.0),Point2(4.0,0.0),Point2(4.0,4.0),Point2(0.0,4.0))),.2))
        val doc=ProjectDocument(Project(p,"Materials",0,0),layers=listOf(layer),elements=listOf(wall,floor))
        for(e in doc.elements) {
            val colour=renderColour(e,layer,null,null)
            assertTrue(buildDrawingScene(doc).segments.filter {it.elementId==e.id.value}.all {it.colorArgb==colour})
            val chunks=buildSurfaceScene(doc).chunks.filter {it.elementId==e.id.value}
            assertTrue(chunks.isNotEmpty())
            assertEquals((colour shr 16 and 255)/255.0,chunks.first().vertices[6].toDouble(),1e-7)
        }
        assertEquals(0xFF112233L,renderColour(wall.copy(properties=mapOf(ELEMENT_COLOUR_KEY to ParameterValue.Colour(0xFF112233))),layer,null,null))
    }
}
