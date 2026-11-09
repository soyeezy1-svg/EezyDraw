package com.eezydraw.core.editing

import com.eezydraw.core.geometry.*
import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class PrecisionPointerGeometryTest {
    private val settings=PrecisionPointerSettings()
    @Test fun defaultOffsetIsEightyDpAboveLeftAndMirrorsForLeftHandedUse() {
        val finger=Point2(500.0,500.0)
        val target=offsetCrosshair(finger,1000.0,1000.0,2.0,settings)
        assertTrue(target.x<finger.x && target.y<finger.y)
        assertEquals(160.0,hypot(target.x-finger.x,target.y-finger.y),1e-9)
        val mirrored=offsetCrosshair(finger,1000.0,1000.0,2.0,settings.copy(side=PrecisionOffsetSide.ABOVE_RIGHT))
        assertEquals(finger.x-target.x,mirrored.x-finger.x,1e-9)
        assertEquals(target.y,mirrored.y,1e-9)
    }
    @Test fun everyEdgeFlipsAndSmallScreensClampWithoutLosingAVisibleTarget() {
        val topLeft=offsetCrosshair(Point2(4.0,4.0),1000.0,800.0,1.0,settings)
        assertTrue(topLeft.x>4 && topLeft.y>4)
        val right=offsetCrosshair(Point2(995.0,400.0),1000.0,800.0,1.0,settings.copy(side=PrecisionOffsetSide.ABOVE_RIGHT))
        assertTrue(right.x<995)
        for(x in listOf(0.0,5.0,19.0))for(y in listOf(0.0,5.0,9.0)) {
            val target=offsetCrosshair(Point2(x,y),20.0,10.0,3.0,settings)
            assertTrue(target.x in 0.0..20.0 && target.y in 0.0..10.0)
        }
    }
    @Test fun stylusAndDisabledPrecisionUseTheExactInputTip() {
        val point=Point2(400.0,300.0)
        assertEquals(point,offsetCrosshair(point,1000.0,800.0,2.0,settings,stylus=true))
        assertEquals(point,offsetCrosshair(point,1000.0,800.0,2.0,settings.copy(enabled=false)))
    }
    @Test fun loupeMovesAwayFromTargetAndFingerAndAlwaysFits() {
        val first=checkNotNull(precisionLoupeBounds(Point2(120.0,120.0),Point2(80.0,80.0),1000.0,800.0,1.0))
        assertFalse(first.contains(Point2(120.0,120.0)));assertFalse(first.contains(Point2(80.0,80.0)))
        assertEquals(120.0,first.width,0.0)
        val moved=precisionLoupeBounds(Point2(first.center.x+20,first.center.y+20),first.center,1000.0,800.0,1.0,previous=first)
        assertNotEquals(first,moved)
        assertNull(precisionLoupeBounds(null,Point2(4.0,4.0),40.0,30.0,2.0))
    }
    @Test fun measuredChromeAndSafeAreasAreExcludedOnPhoneAndTabletInBothOrientations() {
        for((w,h) in listOf(360.0 to 800.0,800.0 to 360.0,800.0 to 1280.0,1280.0 to 800.0)) {
            val safe=PointerRect(24.0,24.0,w-24.0,h-24.0)
            val occupied=listOf(PointerRect(24.0,24.0,200.0,140.0), // Close and view cube
                PointerRect(w-72,24.0,w-24,if(w<520)264.0 else 72.0), // Top toolbar
                PointerRect(24.0,h-160,w-24,h-24), // Bottom / options / hint
                PointerRect(w-220,h-276,w-24,h-160)) // Place / Cancel
            for(x in listOf(w*.2,w*.5,w*.8))for(y in listOf(h*.2,h*.5,h*.8)) {
                val target=Point2(x,y);val finger=Point2(x+36,y+72)
                val r=checkNotNull(precisionLoupeBounds(finger,target,w,h,1.0,occupied=occupied,safe=safe))
                assertTrue(r.left>=safe.left && r.top>=safe.top && r.right<=safe.right && r.bottom<=safe.bottom)
                assertFalse(r.contains(target));assertFalse(r.contains(finger))
                assertTrue(occupied.none {r.left<it.right && r.right>it.left && r.top<it.bottom && r.bottom>it.top})
                val same=precisionLoupeBounds(finger,target,w,h,1.0,occupied=occupied,safe=safe,previous=r)
                assertEquals("Keep a free location stable",r,same)
            }
        }
    }
    @Test fun fullyCoveredCanvasNeverReturnsAnOverlappingFallback() {
        assertNull(precisionLoupeBounds(null,Point2(200.0,200.0),400.0,400.0,1.0,
            occupied=listOf(PointerRect(0.0,0.0,400.0,400.0))))
    }
    @Test fun nudgingUsesPhysicalUnitsAndProjectsInScreenDirections() {
        val point=Point3(1e9,-1e9,2.4)
        val viewport=Viewport2D(pixelsPerMetre=80.0)
        assertEquals(Point3(point.x+.1,point.y+.1,point.z),nudgePrecisionPoint(point,1,-1,.1,viewport,OrbitCamera(),false))
        val rotated=viewport.copy(center=Point2(point.x,point.y),rotation=Math.PI/2)
        val planOrigin=projectPrecisionPoint(point,rotated,OrbitCamera(),false,1000.0,800.0)!!
        val planRight=projectPrecisionPoint(nudgePrecisionPoint(point,1,0,.1,rotated,OrbitCamera(),false),rotated,OrbitCamera(),false,1000.0,800.0)!!
        assertTrue(planRight.x>planOrigin.x);assertEquals(planOrigin.y,planRight.y,1e-4)
        for(projection in Projection.entries)for(preset in ViewPreset.entries) {
            val camera=OrbitCamera(target=Point3(1e9,-1e9,2.4),projection=projection).preset(preset)
            val origin=projectPrecisionPoint(point,viewport,camera,true,1000.0,800.0)!!
            val right=projectPrecisionPoint(nudgePrecisionPoint(point,1,0,.1,viewport,camera,true),viewport,camera,true,1000.0,800.0)!!
            val up=projectPrecisionPoint(nudgePrecisionPoint(point,0,-1,.1,viewport,camera,true),viewport,camera,true,1000.0,800.0)!!
            assertTrue("Right in $preset/$projection",right.x>origin.x)
            assertTrue("Up in $preset/$projection",up.y<origin.y)
            assertEquals(origin.y,right.y,1e-4);assertEquals(origin.x,up.x,1e-4)
        }
    }
}
