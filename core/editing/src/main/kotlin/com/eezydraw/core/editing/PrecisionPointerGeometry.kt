package com.eezydraw.core.editing

import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.Viewport2D
import com.eezydraw.core.model.*
import kotlin.math.*

/** All screen values are physical pixels; conversion from the device preference happens once here. */
fun offsetCrosshair(finger:Point2,width:Double,height:Double,density:Double,
    settings:PrecisionPointerSettings,stylus:Boolean=false):Point2 {
    require(width.isFinite() && height.isFinite() && width>=0 && height>=0 && density.isFinite() && density>0)
    if(stylus || !settings.enabled)return Point2(finger.x.coerceIn(0.0,width),finger.y.coerceIn(0.0,height))
    val margin=min(12*density,min(width,height)/2)
    val horizontal=settings.offsetDp*density*.45
    val vertical=settings.offsetDp*density*sqrt(1-.45*.45)
    fun flipped(value:Double,amount:Double,extent:Double):Double {
        val requested=value+amount
        val alternate=value-amount
        val position=when {
            requested in margin..extent-margin->requested
            alternate in margin..extent-margin->alternate
            else->requested
        }
        return position.coerceIn(margin,extent-margin)
    }
    return Point2(flipped(finger.x,if(settings.side==PrecisionOffsetSide.ABOVE_LEFT)-horizontal else horizontal,width),
        flipped(finger.y,-vertical,height))
}

data class PointerRect(val left:Double,val top:Double,val right:Double,val bottom:Double) {
    val width get()=right-left
    val height get()=bottom-top
    val center get()=Point2((left+right)/2,(top+bottom)/2)
    fun contains(point:Point2)=point.x in left..right && point.y in top..bottom
}

/** Choose a visible corner away from the finger and the precision target, with deterministic ties. */
fun precisionLoupeBounds(finger:Point2?,crosshair:Point2,width:Double,height:Double,density:Double,sizeDp:Double=120.0):PointerRect {
    require(width.isFinite() && height.isFinite() && width>=0 && height>=0 && density.isFinite() && density>0)
    require(sizeDp.isFinite() && sizeDp>0)
    val margin=min(12*density,min(width,height)/4)
    val side=min(sizeDp*density,min(width,height)-2*margin).coerceAtLeast(0.0)
    val corners=listOf(Point2(width-margin-side,margin),Point2(margin,margin),
        Point2(width-margin-side,height-margin-side),Point2(margin,height-margin-side))
    fun separation(rect:PointerRect,p:Point2):Double {
        val dx=max(0.0,max(rect.left-p.x,p.x-rect.right))
        val dy=max(0.0,max(rect.top-p.y,p.y-rect.bottom))
        return hypot(dx,dy)
    }
    return corners.map {PointerRect(it.x,it.y,it.x+side,it.y+side)}.maxBy {rect->
        val target=separation(rect,crosshair)
        val touch=finger?.let {separation(rect,it)} ?: Double.POSITIVE_INFINITY
        min(target,touch)*10+min(1e4,hypot(rect.center.x-crosshair.x,rect.center.y-crosshair.y))
    }
}

fun projectPrecisionPoint(point:Point3,viewport:Viewport2D,orbit:OrbitCamera,is3D:Boolean,width:Double,height:Double):Point2? =
    if(is3D)orbit.project(point,width,height)?.let {Point2(it.x,it.y)} else viewport.worldToScreen(Point2(point.x,point.y),width,height)

/** Nudge directions use screen convention: +X right, +Y down. The step is a physical SI length. */
fun nudgePrecisionPoint(point:Point3,dx:Int,dy:Int,step:Double,viewport:Viewport2D,orbit:OrbitCamera,is3D:Boolean):Point3 {
    require(step.isFinite() && step>0)
    if(!is3D)return Point3(point.x+dx*step,point.y-dy*step,point.z)
    val right=Point3(-sin(orbit.yaw),cos(orbit.yaw),0.0)
    val up=Point3(-sin(orbit.pitch)*cos(orbit.yaw),-sin(orbit.pitch)*sin(orbit.yaw),cos(orbit.pitch))
    return Point3(point.x+(right.x*dx-up.x*dy)*step,point.y+(right.y*dx-up.y*dy)*step,
        point.z+(right.z*dx-up.z*dy)*step)
}
