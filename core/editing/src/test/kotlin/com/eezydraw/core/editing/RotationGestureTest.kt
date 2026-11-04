package com.eezydraw.core.editing

import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.ViewPreset
import com.eezydraw.core.geometry.Viewport2D
import com.eezydraw.core.model.*
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class RotationGestureTest {
    private val width=1000.0
    private val height=800.0
    private val viewport=Viewport2D(pixelsPerMetre=100.0)
    private val pivot=Point3(3.0,4.0,5.0)

    @Test fun eachProjectAxisTurnsTheExpectedCoordinatePlaneAndPreservesPivot() {
        val cases=listOf(Rotation3(x=PI/2) to (Point3(y=1.0) to Point3(z=1.0)),
            Rotation3(y=PI/2) to (Point3(z=1.0) to Point3(x=1.0)),
            Rotation3(z=PI/2) to (Point3(x=1.0) to Point3(y=1.0)))
        for((rotation,points)in cases) {
            val delta=EditDelta(pivot=pivot,rotation=rotation)
            assertPoint(pivot,delta.apply(pivot))
            assertPoint(plus(pivot,points.second),delta.apply(plus(pivot,points.first)))
        }
    }

    @Test fun allSnapChoicesUseWorldPlaneAnglesInBothLensTypes() {
        for(axis in RotationAxis.entries)for(step in listOf(1,5,15,45,90))for(projection in Projection.entries)for(sign in listOf(-1.0,1.0)) {
            val camera=axisCamera(axis).copy(projection=projection)
            val u=when(axis){RotationAxis.X->Point3(y=1.0);RotationAxis.Y->Point3(z=1.0);RotationAxis.Z->Point3(x=1.0)}
            val v=when(axis){RotationAxis.X->Point3(z=1.0);RotationAxis.Y->Point3(x=1.0);RotationAxis.Z->Point3(y=1.0)}
            val angle=sign*Math.toRadians(step*.6)
            val start=project(camera,plus(pivot,u))
            val current=project(camera,plus(pivot,Point3(u.x*cos(angle)+v.x*sin(angle),u.y*cos(angle)+v.y*sin(angle),u.z*cos(angle)+v.z*sin(angle))))
            val actual=rotationGestureAngle(pivot,axis,start,current,viewport,camera,true,width,height,step)
            assertEquals("$axis/$step/$projection",sign*Math.toRadians(step.toDouble()),actual,1e-10)
            val delta=rotationGestureDelta(pivot,axis,start,current,viewport,camera,true,width,height,step)
            assertEquals(when(axis){RotationAxis.X->Rotation3(x=actual);RotationAxis.Y->Rotation3(y=actual);RotationAxis.Z->Rotation3(z=actual)},delta.rotation)
        }
    }

    @Test fun bandsStillTurnInPlanAndEveryCameraPresetIncludingEdgeOnViews() {
        for(is3D in listOf(false,true))for(preset in ViewPreset.entries)for(axis in RotationAxis.entries) {
            val camera=OrbitCamera(target=pivot,orthographicHeight=6.0).preset(preset)
            val center=if(is3D)project(camera,pivot) else viewport.worldToScreen(Point2(pivot.x,pivot.y),width,height)
            val start=Point2(center.x+80.0,center.y)
            val end=Point2(center.x,center.y-80.0)
            val angle=rotationGestureAngle(pivot,axis,start,end,viewport,camera,is3D,width,height)
            assertTrue("No dead $axis band in $preset/$is3D",abs(angle)>=Math.toRadians(15.0))
            assertEquals("15 degree snap",round(Math.toDegrees(angle)/15)*15,Math.toDegrees(angle),1e-10)
        }
    }

    @Test fun fullXYZDeltasMatchGPUAndCommitWithSignedScalesAtGimbalLock() {
        val rotations=listOf(Rotation3(.4,-.7,.2),Rotation3(.2,PI/2,-.8),Rotation3(-.1,-PI/2,.9))
        for(originalRotation in rotations) {
            val original=Transform3(Point3(7.0,-2.0,8.0),originalRotation,Scale3(-2.0,3.0,.7))
            val delta=EditDelta(pivot=pivot,rotationZ=.1,rotation=Rotation3(.3,-.4,.5),axisScale=Point3(1.4,.8,1.2),axes=originalRotation)
            val result=EditTransforms.apply(original,delta)
            val matrix=delta.linearMatrixDouble()
            for(local in listOf(Point3(),Point3(1.0,2.0,3.0),Point3(-.5,1.2,-3.0))) {
                assertPoint(delta.apply(world(original,local)),world(result,local),2e-8)
                val point=world(original,local)
                val v=Point3(point.x-pivot.x,point.y-pivot.y,point.z-pivot.z)
                assertPoint(delta.apply(point),Point3(pivot.x+matrix[0]*v.x+matrix[4]*v.y+matrix[8]*v.z,
                    pivot.y+matrix[1]*v.x+matrix[5]*v.y+matrix[9]*v.z,pivot.z+matrix[2]*v.x+matrix[6]*v.y+matrix[10]*v.z),1e-10)
            }
            assertEquals(original.scale.x*1.4,result.scale.x,0.0)
            assertEquals(original.scale.y*.8,result.scale.y,0.0)
            assertEquals(original.scale.z*1.2,result.scale.z,0.0)
        }
    }

    @Test fun eachAxisRotationIsOneUndoStepAndRedoRestoresTheExactTransform() {
        val document=EditFixtures.document()
        for(axis in RotationAxis.entries) {
            val angle=PI/2
            val rotation=when(axis){RotationAxis.X->Rotation3(x=angle);RotationAxis.Y->Rotation3(y=angle);RotationAxis.Z->Rotation3(z=angle)}
            val history=EditHistory()
            val edited=history.execute(document,EditCommands.transform(document,setOf(document.elements.single().id),EditDelta(pivot=pivot,rotation=rotation)))
            assertEquals(1,history.undoCount)
            assertEquals(document,history.undo(edited))
            assertEquals(edited,history.redo(document))
        }
    }

    @Test fun invalidSnapAndStationaryDragsCannotCreatePartialRotation() {
        val point=Point2(500.0,400.0)
        assertTrue(rotationGestureDelta(pivot,RotationAxis.X,point,point,viewport,axisCamera(RotationAxis.X),true,width,height).isIdentity)
        for(value in listOf(0,2,30,-15))assertThrows(IllegalArgumentException::class.java) {
            rotationGestureAngle(pivot,RotationAxis.Z,point,Point2(500.0,300.0),viewport,axisCamera(RotationAxis.Z),true,width,height,value)
        }
        assertThrows(IllegalArgumentException::class.java) {EditDelta(rotationZ=Double.MAX_VALUE,rotation=Rotation3(z=Double.MAX_VALUE))}
    }

    private fun axisCamera(axis:RotationAxis)=OrbitCamera(target=pivot,orthographicHeight=6.0).preset(
        when(axis){RotationAxis.X->ViewPreset.SIDE;RotationAxis.Y->ViewPreset.FRONT;RotationAxis.Z->ViewPreset.TOP})
    private fun project(camera:OrbitCamera,point:Point3)=camera.project(point,width,height)!!.let {Point2(it.x,it.y)}
    private fun plus(a:Point3,b:Point3)=Point3(a.x+b.x,a.y+b.y,a.z+b.z)
    private fun world(transform:Transform3,point:Point3):Point3 {
        val matrix=editRotationMatrix(transform.rotation)
        val x=point.x*transform.scale.x;val y=point.y*transform.scale.y;val z=point.z*transform.scale.z
        return Point3(transform.translation.x+matrix[0]*x+matrix[1]*y+matrix[2]*z,
            transform.translation.y+matrix[3]*x+matrix[4]*y+matrix[5]*z,
            transform.translation.z+matrix[6]*x+matrix[7]*y+matrix[8]*z)
    }
    private fun assertPoint(a:Point3,b:Point3,tolerance:Double=1e-9) {assertEquals(a.x,b.x,tolerance);assertEquals(a.y,b.y,tolerance);assertEquals(a.z,b.z,tolerance)}
}
