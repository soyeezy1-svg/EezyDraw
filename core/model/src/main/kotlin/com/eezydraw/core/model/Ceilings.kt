package com.eezydraw.core.model

import kotlinx.serialization.Serializable

@Serializable enum class CeilingType { NONE, FLAT }
/** Height is the finished underside above the room/level floor, even when no ceiling is drawn. */
@Serializable data class CeilingOptions(val type:CeilingType=CeilingType.NONE,val height:Double=3.0,val thickness:Double=.1) {
    init {require(height.isFinite() && height>0 && thickness.isFinite() && thickness>0)}
}
const val CEILING_OWNER="eezydraw.ceilingRoom"
const val CEILING_OBJECT="eezydraw.ceiling"
val Element.isCeiling:Boolean get()= CEILING_OWNER in properties || (properties[CEILING_OBJECT] as? ParameterValue.Flag)?.value==true ||
    (body as? ElementBody.Component)?.libraryItemId?.value=="eezy.architecture.ceiling"
fun roomCeiling(document:ProjectDocument,room:Element):CeilingOptions = (room.body as ElementBody.Room).ceiling
    ?: document.levels.find {it.id==room.levelId}?.ceiling ?: CeilingOptions()
fun ceilingElevation(document:ProjectDocument,point:Point3,level:Level?):Double {
    fun contains(ring:List<Point2>,x:Double,y:Double):Boolean {
        var inside=false
        for(i in ring.indices) {val a=ring[i];val b=ring[(i+1)%ring.size]
            if((a.y>y)!=(b.y>y) && x<(b.x-a.x)*(y-a.y)/(b.y-a.y)+a.x)inside=!inside
        };return inside
    }
    val room=document.elements.firstOrNull {e->val r=e.body as? ElementBody.Room ?: return@firstOrNull false
        if(level!=null && e.levelId!=null && e.levelId!=level.id)return@firstOrNull false
        val p=measurementToLocal(e.transform,point)
        contains(r.boundary.outer,p.x,p.y) && r.boundary.holes.none {contains(it,p.x,p.y)}
    }
    return room?.let {measurementToWorld(it.transform,Point3(z=roomCeiling(document,it).height)).z}
        ?: ((level?.elevation ?: 0.0)+(level?.ceiling?.height ?: 3.0))
}
