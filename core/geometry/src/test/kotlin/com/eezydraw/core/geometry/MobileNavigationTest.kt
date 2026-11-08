package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class MobileNavigationTest {
    @Test fun rotatedPlanRoundTripsPansAndPreservesZoomAndTwistAnchor() {
        val view=Viewport2D(Point2(1000000000.0,-1000000000.0),80.0,PI/3)
        val p=view.screenToWorld(500.0,200.0,800.0,600.0)
        val screen=view.worldToScreen(p,800.0,600.0)
        assertEquals(500.0,screen.x,1e-4);assertEquals(200.0,screen.y,1e-4)
        val zoom=view.zoom(2.0,500.0,200.0,800.0,600.0).rotate(.2,500.0,200.0,800.0,600.0)
        val anchor=zoom.screenToWorld(500.0,200.0,800.0,600.0)
        assertEquals(p.x,anchor.x,1e-6);assertEquals(p.y,anchor.y,1e-6)
        val pan=view.pan(80.0,0.0).worldToScreen(p,800.0,600.0)
        assertEquals(screen.x+80,pan.x,1e-4);assertEquals(screen.y,pan.y,1e-4)
        val fitted=view.fit(Bounds3(Point3(),Point3(5.0,4.0,3.0)),800.0,600.0)
        assertEquals(view.rotation,fitted.rotation,0.0)
        assertEquals(view.rotation,view.zoomWindow(100.0,100.0,500.0,400.0,800.0,600.0).rotation,0.0)
    }
    @Test fun lookAroundKeepsEyeWhileWalkMovesItAndFingerZoomKeepsTargetPlaneAnchor() {
        val camera=OrbitCamera(target=Point3(5.0,6.0,2.0),projection=Projection.PERSPECTIVE)
        val eye=camera.eye();val turned=camera.lookAround(.3,-.2)
        assertEquals(eye.x,turned.eye().x,1e-10);assertEquals(eye.y,turned.eye().y,1e-10);assertEquals(eye.z,turned.eye().z,1e-10)
        assertNotEquals(eye,camera.walk(1.0,2.0).eye());assertEquals(eye.z,camera.walk(1.0,2.0).eye().z,1e-10)
        val point=camera.rayToPlaneZ(550.0,400.0,800.0,600.0,camera.target.z)!!
        val projected=camera.zoomAt(1.5,550.0,400.0,800.0,600.0).project(point,800.0,600.0)!!
        assertEquals(550.0,projected.x,1e-8);assertEquals(400.0,projected.y,1e-8)
    }
    @Test fun sectionClipsEdgesAndSurfacesWithoutChangingTheSource() {
        val box=Bounds3(Point3(.5,-1.0,-1.0),Point3(1.5,3.0,4.0))
        val p=ProjectId("clip");val l=LayerId("base")
        val e=Element(ElementId("box"),p,l,ElementBody.DrawingShape(Shape.Box(Size3(2.0,2.0,3.0))))
        val doc=ProjectDocument(Project(p,"Clip",0,0),layers=listOf(Layer(l,p,"Base")),elements=listOf(e))
        val original=buildDrawingScene(doc);val clipped=SectionClip.drawing(original,box)
        assertTrue(clipped.segments.isNotEmpty());assertTrue(clipped.segments.all {SectionClip.contains(box,it.a) && SectionClip.contains(box,it.b)})
        assertTrue(original.segments.any {it.a.x<box.min.x})
        val surface=SectionClip.surfaces(buildSurfaceScene(doc),box)
        assertTrue(surface.triangleCount>0)
        for(chunk in surface.chunks)for(i in chunk.vertices.indices step 10)assertTrue(SectionClip.contains(box,Point3(chunk.origin.x+chunk.vertices[i],chunk.origin.y+chunk.vertices[i+1],chunk.origin.z+chunk.vertices[i+2])))
        assertNull(SectionClip.segment(Point3(-2.0,-2.0),Point3(-1.0,-1.0),box))
    }
}
