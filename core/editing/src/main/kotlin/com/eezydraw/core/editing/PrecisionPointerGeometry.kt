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

/**
 * Search free rectangles, including positions beside measured chrome (not just corners).
 * Keep the current position until a control or pointer approaches it. Never return an
 * overlapping fallback: a fully occluded canvas has no valid display position.
 */
fun precisionLoupeBounds(finger:Point2?,crosshair:Point2,width:Double,height:Double,density:Double,
    sizeDp:Double=120.0,occupied:List<PointerRect> = emptyList(),
    safe:PointerRect=PointerRect(0.0,0.0,width,height),previous:PointerRect?=null,
):PointerRect? {
    require(width.isFinite() && height.isFinite() && width>=0 && height>=0 && density.isFinite() && density>0)
    require(sizeDp.isFinite() && sizeDp>0)
    val gap=8*density
    val area=PointerRect(max(0.0,safe.left)+gap,max(0.0,safe.top)+gap,
        min(width,safe.right)-gap,min(height,safe.bottom)-gap)
    val blockers=occupied+listOfNotNull(finger?.let {PointerRect(it.x-40*density,it.y-40*density,it.x+40*density,it.y+40*density)},
        PointerRect(crosshair.x-28*density,crosshair.y-28*density,crosshair.x+28*density,crosshair.y+28*density))
    fun fits(r:PointerRect)=r.left>=area.left && r.top>=area.top && r.right<=area.right && r.bottom<=area.bottom &&
        blockers.none {r.left<it.right+gap && r.right>it.left-gap && r.top<it.bottom+gap && r.bottom>it.top-gap}
    if(previous!=null && fits(previous))return previous
    // Compact landscapes can need a smaller lens, but retain the requested 2x–4x scale.
    for(dp in listOf(sizeDp,96.0,72.0,48.0).distinct().filter {it<=sizeDp}) {
        val side=min(dp*density,min(area.width,area.height))
        if(side<=0)continue
        val xs=(listOf(area.left,area.right-side)+blockers.flatMap {listOf(it.left-gap-side,it.right+gap)}).distinct()
        val ys=(listOf(area.top,area.bottom-side)+blockers.flatMap {listOf(it.top-gap-side,it.bottom+gap)}).distinct()
        val candidates=xs.flatMap {x->ys.map {y->PointerRect(x,y,x+side,y+side)}}.filter(::fits)
        val best=candidates.maxByOrNull {r->
            fun distance(p:Point2)=hypot(r.center.x-p.x,r.center.y-p.y)
            min(distance(crosshair),finger?.let(::distance) ?: Double.POSITIVE_INFINITY)
        }
        if(best!=null)return best
    }
    return null
}

fun projectPrecisionPoint(point:Point3,viewport:Viewport2D,orbit:OrbitCamera,is3D:Boolean,width:Double,height:Double):Point2? =
    if(is3D)orbit.project(point,width,height)?.let {Point2(it.x,it.y)} else viewport.worldToScreen(Point2(point.x,point.y),width,height)

/** Nudge directions use screen convention: +X right, +Y down. The step is a physical SI length. */
fun nudgePrecisionPoint(point:Point3,dx:Int,dy:Int,step:Double,viewport:Viewport2D,orbit:OrbitCamera,is3D:Boolean):Point3 {
    require(step.isFinite() && step>0)
    if(!is3D)return Point3(point.x+(cos(viewport.rotation)*dx-sin(viewport.rotation)*dy)*step,point.y+(-sin(viewport.rotation)*dx-cos(viewport.rotation)*dy)*step,point.z)
    val right=Point3(-sin(orbit.yaw),cos(orbit.yaw),0.0)
    val up=Point3(-sin(orbit.pitch)*cos(orbit.yaw),-sin(orbit.pitch)*sin(orbit.yaw),cos(orbit.pitch))
    return Point3(point.x+(right.x*dx-up.x*dy)*step,point.y+(right.y*dx-up.y*dy)*step,
        point.z+(right.z*dx-up.z*dy)*step)
}
