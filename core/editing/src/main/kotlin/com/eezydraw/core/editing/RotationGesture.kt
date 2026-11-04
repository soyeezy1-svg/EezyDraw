package com.eezydraw.core.editing

import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.Viewport2D
import com.eezydraw.core.model.Point2
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.Rotation3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

enum class RotationAxis { X, Y, Z }

/** A ring gesture changes one project axis. Returned angles are snapped radians. */
fun rotationGestureAngle(
    pivot:Point3, axis:RotationAxis, start:Point2, current:Point2,
    viewport:Viewport2D, orbit:OrbitCamera, is3D:Boolean, width:Double, height:Double,
    snapDegrees:Int=15,
):Double {
    require(snapDegrees in listOf(1,5,15,45,90)) { "Choose a 1, 5, 15, 45 or 90 degree rotation snap." }
    if(start==current || width<=0.0 || height<=0.0 || !width.isFinite() || !height.isFinite())return 0.0
    val normal=when(axis) {
        RotationAxis.X->Point3(x=1.0)
        RotationAxis.Y->Point3(y=1.0)
        RotationAxis.Z->Point3(z=1.0)
    }
    val u=when(axis) {RotationAxis.X->Point3(y=1.0);RotationAxis.Y->Point3(z=1.0);RotationAxis.Z->Point3(x=1.0)}
    val v=when(axis) {RotationAxis.X->Point3(z=1.0);RotationAxis.Y->Point3(x=1.0);RotationAxis.Z->Point3(y=1.0)}
    val outward=if(is3D)Point3(cos(orbit.pitch)*cos(orbit.yaw),cos(orbit.pitch)*sin(orbit.yaw),sin(orbit.pitch)) else Point3(z=1.0)
    fun ray(point:Point2):PickRay=if(is3D)cameraPickRay(orbit,point.x,point.y,width,height) else {
        val p=viewport.screenToWorld(point.x,point.y,width,height)
        PickRay(DragVector(p.x,p.y,pivot.z+1.0),DragVector(0.0,0.0,-1.0))
    }
    fun plane(point:Point2):Point3? {
        val ray=ray(point)
        val direction=Point3(ray.direction.x,ray.direction.y,ray.direction.z)
        val denominator=dot(direction,normal)
        if(abs(denominator)<.15)return null
        val t=((pivot.x-ray.origin.x)*normal.x+(pivot.y-ray.origin.y)*normal.y+(pivot.z-ray.origin.z)*normal.z)/denominator
        if(!t.isFinite() || is3D && t<0.0)return null
        return Point3(ray.origin.x+ray.direction.x*t-pivot.x,
            ray.origin.y+ray.direction.y*t-pivot.y,ray.origin.z+ray.direction.z*t-pivot.z)
    }
    val first=if(abs(dot(outward,normal))>=.15)plane(start) else null
    val last=if(first!=null)plane(current) else null
    val raw=if(first!=null && last!=null && hypot(dot(first,u),dot(first,v))>1e-9 && hypot(dot(last,u),dot(last,v))>1e-9) {
        atan2(dot(last,v),dot(last,u))-atan2(dot(first,v),dot(first,u))
    } else {
        // Looking edge-on collapses a physical ring to a line. Keep a visible screen dial.
        val center=if(is3D)orbit.project(pivot,width,height)?.let {Point2(it.x,it.y)}
            else viewport.worldToScreen(Point2(pivot.x,pivot.y),width,height)
        if(center==null || hypot(start.x-center.x,start.y-center.y)<1e-9 || hypot(current.x-center.x,current.y-center.y)<1e-9)return 0.0
        val sign=if(dot(outward,normal)<-1e-8)-1.0 else 1.0
        (atan2(center.y-current.y,current.x-center.x)-atan2(center.y-start.y,start.x-center.x))*sign
    }
    val wrapped=atan2(sin(raw),cos(raw))
    val step=snapDegrees*PI/180.0
    return round(wrapped/step)*step
}

fun rotationGestureDelta(
    pivot:Point3, axis:RotationAxis, start:Point2, current:Point2,
    viewport:Viewport2D, orbit:OrbitCamera, is3D:Boolean, width:Double, height:Double,
    snapDegrees:Int=15,
):EditDelta {
    val angle=rotationGestureAngle(pivot,axis,start,current,viewport,orbit,is3D,width,height,snapDegrees)
    val rotation=when(axis) {
        RotationAxis.X->Rotation3(x=angle)
        RotationAxis.Y->Rotation3(y=angle)
        RotationAxis.Z->Rotation3(z=angle)
    }
    return EditDelta(pivot=pivot,rotation=rotation)
}

private fun dot(a:Point3,b:Point3)=a.x*b.x+a.y*b.y+a.z*b.z
