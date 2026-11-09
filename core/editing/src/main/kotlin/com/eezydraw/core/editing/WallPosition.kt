package com.eezydraw.core.editing

import com.eezydraw.core.model.*
import kotlin.math.*

data class WallMountProperties(val side:WallFaceSide,val fromLeft:Double,val fromRight:Double,
    val leftFace:String,val rightFace:String,val position:AlongWallPosition?=null,val swing:DoorSwing?=null,val architectural:Boolean=false) {
    val face get()=if(side==WallFaceSide.LEFT)leftFace else rightFace
}
fun wallFaceName(document:ProjectDocument,frame:WallFrame,side:WallFaceSide,along:Double=frame.length/2):String {
    val normal=frame.outward(side);val p=frame.world(along,frame.faceDepth(side),0.0)
    val probe=Point3(p.x+normal.x*.05,p.y+normal.y*.05,p.z)
    fun inside(ring:List<Point2>,p:Point3):Boolean {
        var yes=false
        for(i in ring.indices){val a=ring[i];val b=ring[(i+1)%ring.size]
            if((a.y>p.y)!=(b.y>p.y) && p.x<(b.x-a.x)*(p.y-a.y)/(b.y-a.y)+a.x)yes=!yes
        };return yes
    }
    val room=document.elements.firstOrNull {e->val body=e.body as? ElementBody.Room ?: return@firstOrNull false
        if(e.levelId!=frame.host.levelId)return@firstOrNull false
        val local=measurementToLocal(e.transform,probe)
        inside(body.boundary.outer,local) && body.boundary.holes.none {inside(it,local)}
    }
    return room?.let {"${it.name.ifBlank {(it.body as ElementBody.Room).number.ifBlank {"Room"}}} side (${side.name.lowercase()} face)"} ?: "Outside (${side.name.lowercase()} face)"
}
fun readWallMount(document:ProjectDocument,element:Element):WallMountProperties? {
    val mount=element.wallService() ?: return null
    val body=element.body as? ElementBody.Component ?: return null
    val f=wallFrames(document).firstOrNull {it.host.id==mount.wallId && it.roomSide==mount.roomSide} ?: return null
    val center=measurementToWorld(element.transform,Point3(body.size.width/2,0.0,body.size.height/2))
    val fromLeft=f.coordinates(center).x
    val kind=document.libraryItems.find {it.id==body.libraryItemId}?.openingKind()
    return WallMountProperties(mount.side,fromLeft,f.length-fromLeft,wallFaceName(document,f,WallFaceSide.LEFT,fromLeft),wallFaceName(document,f,WallFaceSide.RIGHT,fromLeft),
        swing=mount.options.swing ?: DoorSwing().takeIf {kind in setOf(OpeningKind.DOOR,OpeningKind.DOUBLE_DOOR)},architectural=kind!=null)
}
fun resolveAlongWallPosition(document:ProjectDocument,frame:WallFrame,position:AlongWallPosition,aim:Double,exclude:ElementId?=null):Double = when(position.reference) {
    WallPositionReference.LEFT_END->position.distance
    WallPositionReference.RIGHT_END->frame.length-position.distance
    WallPositionReference.NEAREST_CORNER->if(aim<=frame.length/2)position.distance else frame.length-position.distance
    WallPositionReference.NEAREST_DOOR->{
        val jambs=document.elements.flatMap {e->val body=e.body as? ElementBody.Component ?: return@flatMap emptyList()
            val a=e.wallService() ?: return@flatMap emptyList()
            val kind=document.libraryItems.find {it.id==body.libraryItemId}?.openingKind()
            if(e.id==exclude || a.wallId!=frame.host.id || a.roomSide!=frame.roomSide || kind !in setOf(OpeningKind.DOOR,OpeningKind.DOUBLE_DOOR,OpeningKind.SLIDING_DOOR))return@flatMap emptyList()
            listOf(0.0,body.size.width).map {frame.coordinates(measurementToWorld(e.transform,Point3(it))).x}
        }
        val jamb=requireNotNull(jambs.minByOrNull {abs(it-aim)}){"There is no door on this wall to measure from"}
        jamb+if(aim<jamb)-position.distance else position.distance
    }
}
