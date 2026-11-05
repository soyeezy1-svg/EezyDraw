package com.eezydraw.core.editing

import com.eezydraw.core.geometry.*
import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class PointPickContextTest {
    private val project=ProjectId("pointer")
    private val layer=LayerId("base")
    private val viewport=Viewport2D(pixelsPerMetre=100.0)
    private val camera=OrbitCamera(target=Point3(0.0,0.0,2.4),orthographicHeight=8.0,distance=12.0)
    private val width=1000.0;private val height=800.0
    private fun scene(edges:List<SceneSegment>)=DrawingScene(edges,emptyList(),Bounds3(Point3(-10.0,-10.0,-10.0),Point3(10.0,10.0,10.0)))
    private fun edge(a:Point3,b:Point3,id:String="other",level:String?=null)=SceneSegment(a,b,id,level)
    private fun context(edges:List<SceneSegment>,walls:Boolean=false)=PointPickContext(ProjectDocument(Project(project,"Pointer",0,0),
        layers=listOf(Layer(layer,project,"Base")),elements=if(walls)listOf(Element(ElementId("wall"),project,layer,
            ElementBody.Wall(Point2(-1.0,0.0),Point2(1.0,0.0),.2,3.0))) else emptyList()),scene(edges))
    private fun pick(context:PointPickContext,at:Point3,modes:Set<SnapMode>,three:Boolean=false,camera:OrbitCamera=this.camera,anchor:Point3?=null,level:String?=null):PrecisionPick {
        val screen=projectPrecisionPoint(at,viewport,camera,three,width,height)!!
        return context.pick(screen,viewport,camera,three,width,height,2.4,level,SnapSettings(modes=modes,tolerance=.001),anchor,14.0)
    }
    private fun assertPoint(expected:Point3,actual:Point3) {assertEquals(expected.x,actual.x,1e-7);assertEquals(expected.y,actual.y,1e-7);assertEquals(expected.z,actual.z,1e-7)}
    @Test fun planSnapsAllSixModesAndRetainsTheSelectedFloor() {
        val ctx=context(listOf(edge(Point3(),Point3(2.0,0.0)),edge(Point3(1.0,-1.0),Point3(1.0,1.0),"cross")))
        assertEquals(SnapMode.ENDPOINT,pick(ctx,Point3(.01,.02),setOf(SnapMode.ENDPOINT)).mode)
        assertEquals(SnapMode.MIDPOINT,pick(ctx,Point3(.99,.01),setOf(SnapMode.MIDPOINT)).mode)
        assertEquals(SnapMode.INTERSECTION,pick(ctx,Point3(1.02,.01),setOf(SnapMode.INTERSECTION)).mode)
        assertEquals(SnapMode.PERPENDICULAR,pick(ctx,Point3(.5,.01),setOf(SnapMode.PERPENDICULAR),anchor=Point3(.5,1.0,2.4)).mode)
        assertPoint(Point3(.3,.2,2.4),pick(ctx,Point3(.28,.21),setOf(SnapMode.GRID)).point)
        assertPoint(Point3(.7,0.0,2.4),pick(ctx,Point3(.7,.2),setOf(SnapMode.ORTHO),anchor=Point3(0.0,0.0,2.4)).point)
    }
    @Test fun everyCameraAndLensFindsActualElevatedEndpoints() {
        val target=Point3(.37,.28,2.8)
        val ctx=context(listOf(edge(target,Point3(1.7,.2,3.4))))
        for(projection in Projection.entries)for(preset in ViewPreset.entries) {
            val view=camera.copy(projection=projection).preset(preset)
            val result=pick(ctx,target,setOf(SnapMode.ENDPOINT),true,view)
            assertEquals("$preset/$projection",SnapMode.ENDPOINT,result.mode)
            assertPoint(target,result.point)
        }
    }
    @Test fun spatialIntersectionIsNotConfusedWithAScreenOnlyCrossing() {
        val at=Point3(0.0,0.0,2.4)
        val trueCross=context(listOf(edge(Point3(-1.0,0.0,2.4),Point3(1.0,0.0,2.4)),edge(Point3(0.0,-1.0,2.4),Point3(0.0,1.0,2.4),"second")))
        val top=camera.preset(ViewPreset.TOP)
        val hit=pick(trueCross,at,setOf(SnapMode.INTERSECTION),true,top)
        assertEquals(SnapMode.INTERSECTION,hit.mode);assertPoint(at,hit.point)
        val skew=context(listOf(edge(Point3(-1.0,0.0,2.4),Point3(1.0,0.0,2.4)),edge(Point3(0.0,-1.0,3.4),Point3(0.0,1.0,3.4),"above")))
        assertNull(pick(skew,at,setOf(SnapMode.INTERSECTION),true,top).mode)
    }
    @Test fun midpointPerpendicularGridAndOrthoWorkInThreeDimensions() {
        val view=camera.preset(ViewPreset.ISOMETRIC)
        val middle=Point3(0.0,0.0,2.8)
        val ctx=context(listOf(edge(Point3(-1.0,0.0,2.8),Point3(1.0,0.0,2.8))))
        assertEquals(SnapMode.MIDPOINT,pick(ctx,middle,setOf(SnapMode.MIDPOINT),true,view).mode)
        assertEquals(SnapMode.PERPENDICULAR,pick(ctx,middle,setOf(SnapMode.PERPENDICULAR),true,view,anchor=Point3(0.0,1.0,2.8)).mode)
        val empty=context(emptyList())
        assertPoint(Point3(.3,.2,2.4),pick(empty,Point3(.28,.21,2.4),setOf(SnapMode.GRID),true,view).point)
        val ortho=empty.pick(Point2(560.0,410.0),viewport,view,true,width,height,2.4,null,
            SnapSettings(modes=setOf(SnapMode.ORTHO)),anchor=Point3(0.0,0.0,2.4))
        val changed=listOf(ortho.point.x,ortho.point.y,ortho.point.z-2.4).count {abs(it)>1e-8}
        assertEquals(1,changed);assertEquals(SnapMode.ORTHO,ortho.mode)
    }
    @Test fun wallEdgesHaveASeparateMarkerAndNamedSnapsWinAnExactTie() {
        val ctx=context(listOf(edge(Point3(-1.0,0.0,2.4),Point3(1.0,0.0,2.4),"wall")),walls=true)
        val edge=pick(ctx,Point3(.34,.01,2.4),setOf(SnapMode.ENDPOINT))
        assertTrue(edge.wallEdge);assertEquals(ElementId("wall"),edge.elementId);assertPoint(Point3(.34,0.0,2.4),edge.point)
        val endpoint=pick(ctx,Point3(1.0,0.0,2.4),setOf(SnapMode.ENDPOINT))
        assertFalse(endpoint.wallEdge);assertEquals(SnapMode.ENDPOINT,endpoint.mode)
        val three=pick(ctx,Point3(.34,0.0,2.4),setOf(SnapMode.ENDPOINT),true,camera.preset(ViewPreset.FRONT))
        assertTrue(three.wallEdge)
    }
    @Test fun levelsAndDisabledSnapsAreRespectedAndOffscreenTargetsAreNeverChosen() {
        val ctx=context(listOf(edge(Point3(0.0,0.0,3.4),Point3(1.0,0.0,3.4),level="upper")))
        val view=camera.preset(ViewPreset.FRONT)
        assertNull(pick(ctx,Point3(0.0,0.0,3.4),setOf(SnapMode.ENDPOINT),true,view,level="lower").mode)
        val at=Point2(501.0,399.0)
        val off=context(listOf(edge(Point3(-5.01,0.0),Point3(-6.0,0.0))))
        val result=off.pick(Point2(0.0,400.0),viewport,camera,false,width,height,2.4,null,SnapSettings(modes=setOf(SnapMode.ENDPOINT)))
        assertNull(result.mode);assertTrue(result.screen.x>=0)
        val disabled=ctx.pick(at,viewport,camera,false,width,height,2.4,null,SnapSettings(enabled=false))
        assertNull(disabled.mode);assertFalse(disabled.wallEdge)
    }
    @Test fun visibleEndpointsRemainQueryableWhenTheirEdgeCrossesTheEyePlane() {
        val view=camera.copy(target=Point3(0.0,0.0,2.4),yaw=0.0,pitch=0.0,distance=10.0,projection=Projection.PERSPECTIVE)
        val visible=Point3(0.0,.2,2.6)
        val hidden=Point3(12.0,.2,2.6)
        val ctx=context(listOf(edge(visible,hidden)))
        assertNull(view.project(hidden,width,height))
        val found=pick(ctx,visible,setOf(SnapMode.ENDPOINT),true,view)
        assertEquals(SnapMode.ENDPOINT,found.mode);assertPoint(visible,found.point)
        val midpoint=mixForTest(visible,hidden,.5)
        val mid=pick(ctx,midpoint,setOf(SnapMode.MIDPOINT),true,view)
        assertEquals(SnapMode.MIDPOINT,mid.mode);assertPoint(midpoint,mid.point)
    }
    @Test fun geometryOutsideTheCameraDepthRangeCannotAttractAVisiblePick() {
        val view=camera.preset(ViewPreset.FRONT)
        val far=Point3(0.0,2000.0,2.4)
        val ctx=context(listOf(edge(far,far.copy(x=1.0))))
        val result=pick(ctx,Point3(0.0,0.0,2.4),setOf(SnapMode.ENDPOINT),true,view)
        assertNull(result.mode)
    }
    private fun mixForTest(a:Point3,b:Point3,t:Double)=Point3(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t,a.z+(b.z-a.z)*t)
    @Test fun loupeNeighborhoodIsLocalAndBoundedOnLargeDrawingsInPlanAndFront() {
        val edges=(0 until 100_000).map {i->edge(Point3(i*2.0,0.0,2.4),Point3(i*2.0+1.0,0.0,3.4),"$i")}
        val ctx=context(edges)
        val center=Point3(100000.5,0.0,2.9)
        val view=camera.copy(target=center).preset(ViewPreset.FRONT)
        val planView=viewport.copy(center=Point2(center.x,center.y))
        val screen=Point2(500.0,400.0)
        for(three in listOf(false,true)) {
            val local=ctx.nearbyScreenSegments(screen,40.0,planView,view,three,width,height,null)
            assertTrue(local.isNotEmpty());assertTrue(local.size<=256)
            assertTrue(local.all {abs(it.a.x-center.x)<3.0})
        }
    }
}
