package com.eezydraw.core.model

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToLong
import kotlin.math.sign

/**
 * Chase corners follow logical route tangents, independently of fitting trims/curves.
 * Straight strips and these outer wedges are unioned by the wall solid builder in each depth band.
 * Keeping the wedges per owner preserves requested depths, schedule ownership and warning rules.
 */
internal fun wallChaseMitres(
    document:ProjectDocument,
    frames:Map<Pair<ElementId,RoomSideRef?>,WallFrame>,
):List<WallCut> {
    data class Key(val wall:ElementId,val roomSide:RoomSideRef?,val side:WallFaceSide,val x:Long,val y:Long)
    data class End(val owner:Element,val frame:WallFrame,val mount:WallServiceAttachment,
        val point:Point2,val direction:Point2,val radius:Double,val depth:Double)
    val nodes=linkedMapOf<Key,MutableList<End>>()
    for(element in document.elements) {
        val route=element.body as? ElementBody.Route ?: continue
        val mount=route.wallService ?: continue
        if(mount.options.mode!=WallServiceMode.CHASE)continue
        val frame=frames[mount.wallId to mount.roomSide] ?: continue
        val radius=(when(val section=route.section){is RouteSection.Circular->section.diameter;is RouteSection.Rectangular->section.width}+2*mount.options.clearance)/2
        val depth=mount.options.depth ?: (when(val section=route.section){is RouteSection.Circular->section.diameter;is RouteSection.Rectangular->section.height}+2*mount.options.clearance)
        val path=route.path.map {frame.coordinates(measurementToWorld(element.transform,it))}.map {Point2(it.x,it.z)}
        for((a,b) in path.zipWithNext()) {
            val length=hypot(b.x-a.x,b.y-a.y)
            if(length<1e-9)continue
            for((p,q) in listOf(a to b,b to a)) {
                val key=Key(mount.wallId,mount.roomSide,mount.side,(p.x*1e7).roundToLong(),(p.y*1e7).roundToLong())
                nodes.getOrPut(key){mutableListOf()}.add(End(element,frame,mount,p,Point2((q.x-p.x)/length,(q.y-p.y)/length),radius,depth))
            }
        }
    }
    fun cross(a:Point2,b:Point2)=a.x*b.y-a.y*b.x
    fun dot(a:Point2,b:Point2)=a.x*b.x+a.y*b.y
    fun cut(end:End,outline:List<Point2>):WallCut {
        val left=end.mount.side==WallFaceSide.LEFT
        return WallCut(end.mount.wallId,end.owner.id,end.mount.roomSide,WallServiceMode.CHASE,outline,
            if(left)0.0 else (end.frame.thickness-end.depth).coerceAtLeast(0.0),
            if(left)end.depth.coerceAtMost(end.frame.thickness)else end.frame.thickness,
            end.frame.thickness,end.owner.name,end.depth,end.mount.side)
    }
    return nodes.values.flatMap {ends->
        // Duplicate/overlapping sections on the same ray must not turn a bend into a false tee.
        val rays=mutableListOf<MutableList<End>>()
        for(end in ends) {
            val ray=rays.firstOrNull {abs(cross(it.first().direction,end.direction))<1e-8 && dot(it.first().direction,end.direction)>0}
            if(ray==null)rays.add(mutableListOf(end))else ray.add(end)
        }
        // T/cross junctions use the union of their straight strips; there is no outside bend gap.
        if(rays.size!=2)return@flatMap emptyList()
        val result=mutableListOf<WallCut>()
        for(a in rays[0].distinctBy {it.radius to it.depth})for(b in rays[1].distinctBy {it.radius to it.depth}) {
            val turn=cross(a.direction,b.direction)
            if(abs(turn)<1e-8)continue // Straight continuation or reversal: butt ends suffice.
            val side=-sign(turn)
            val p=a.point
            val first=Point2(p.x-a.direction.y*a.radius*side,p.y+a.direction.x*a.radius*side)
            val second=Point2(p.x+b.direction.y*b.radius*side,p.y-b.direction.x*b.radius*side)
            val t=cross(Point2(second.x-first.x,second.y-first.y),b.direction)/turn
            val mitre=Point2(first.x+a.direction.x*t,first.y+a.direction.y*t)
            // Shared bisector edge: the two outer triangles meet without overlapping.
            result+=cut(a,listOf(p,first,mitre))
            result+=cut(b,listOf(p,mitre,second))
        }
        result
    }
}
