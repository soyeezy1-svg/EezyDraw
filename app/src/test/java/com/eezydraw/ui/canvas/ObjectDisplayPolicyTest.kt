package com.eezydraw.ui.canvas

import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.ViewPreset
import com.eezydraw.core.model.*
import kotlin.math.PI
import org.junit.Assert.*
import org.junit.Test

class ObjectDisplayPolicyTest {
    private val room = ElementId("room")
    private val center = Point3(0.0, 0.0, 1.5)
    private val front = RoomSideRef(room, 0)
    private val back = RoomSideRef(room, 2)
    private val right = RoomSideRef(room, 1)
    private val left = RoomSideRef(room, 3)
    private val walls = RoomWallIndex(listOf(
        RoomWallPart(front, Bounds3(Point3(-1.0,-1.0,0.0),Point3(1.0,-1.0,3.0)),center),
        RoomWallPart(back, Bounds3(Point3(-1.0,1.0,0.0),Point3(1.0,1.0,3.0)),center),
        RoomWallPart(right, Bounds3(Point3(1.0,-1.0,0.0),Point3(1.0,1.0,3.0)),center),
        RoomWallPart(left, Bounds3(Point3(-1.0,-1.0,0.0),Point3(-1.0,1.0,3.0)),center),
    ))

    @Test fun individualPresentationOverridesEveryProjectMode() {
        for(mode in ViewMode.entries) {
            assertEquals(viewRenderPlan(mode), objectRenderPlan(mode,ObjectDisplay.NORMAL))
            assertFalse(objectRenderPlan(mode,ObjectDisplay.WIREFRAME).surfaces)
            assertTrue(objectRenderPlan(mode,ObjectDisplay.WIREFRAME).fullEdges)
            assertFalse(objectRenderPlan(mode,ObjectDisplay.HIDDEN).surfaces)
            val xray=objectRenderPlan(mode,ObjectDisplay.XRAY)
            assertTrue(xray.surfaces);assertTrue(xray.transparent);assertTrue(xray.fullEdges)
            assertEquals(.18f,objectEdgeOpacity(ObjectDisplay.HIDDEN),0f)
        }
        assertEquals(ViewMode.XRAY,objectViewMode(ViewMode.WIREFRAME,ObjectDisplay.NORMAL,frontWall=true))
        assertEquals(ViewMode.WIREFRAME,objectViewMode(ViewMode.SHADED,ObjectDisplay.WIREFRAME,frontWall=true))
        assertEquals(.65f,objectEdgeOpacity(ObjectDisplay.NORMAL,frontWall=true),0f)
    }

    @Test fun frontWallsChangeAsTheCameraOrbitsAndRespectTheToggleAndPlanView() {
        val camera=OrbitCamera(target=center).preset(ViewPreset.FRONT)
        assertTrue(walls.isFront(front,camera,true,true))
        assertFalse(walls.isFront(back,camera,true,true))
        assertFalse(walls.isFront(left,camera,true,true))
        assertFalse(walls.isFront(right,camera,true,true))
        val opposite=camera.copy(yaw=camera.yaw+PI)
        assertFalse(walls.isFront(front,opposite,true,true))
        assertTrue(walls.isFront(back,opposite,true,true))
        assertFalse(walls.isFront(front,camera,false,true))
        assertFalse(walls.isFront(front,camera,true,false))
        assertFalse(walls.isFront(null,camera,true,true))
        val iso=camera.copy(yaw=-PI/4,pitch=.5)
        assertTrue(walls.isFront(front,iso,true,true));assertTrue(walls.isFront(right,iso,true,true))
        assertFalse(walls.isFront(back,iso,true,true));assertFalse(walls.isFront(left,iso,true,true))
    }

    @Test fun subdividedWallsUseTheirWholeSideAndOrthographicPanDoesNotFlipTheResult() {
        val index=RoomWallIndex(listOf(
            RoomWallPart(front,Bounds3(Point3(-10.0,-1.0,0.0),Point3(0.0,-1.0,1.5)),center),
            RoomWallPart(front,Bounds3(Point3(0.0,-1.0,1.5),Point3(10.0,-1.0,3.0)),center),
        ))
        val camera=OrbitCamera(target=center).preset(ViewPreset.FRONT)
        assertTrue(index.isFront(front,camera,true,true))
        assertTrue(index.isFront(front,camera.copy(target=Point3(1000.0,1000.0,1000.0)),true,true))
        assertFalse(index.isFront(front,camera.preset(ViewPreset.TOP),true,true))
    }
}
