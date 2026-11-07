package com.eezydraw.core.model

import kotlinx.serialization.Serializable

@Serializable
data class RoutingSettings(
    val width: Double = .1,
    val height: Double = .05,
    val diameter: Double = .05,
    val round: Boolean = false,
    val elevation: ElevationSetting = ElevationSetting(2.5, ElevationReference.ABOVE_FINISHED_FLOOR),
    val system: String = "Power",
    val colourArgb: Long = 0xFFCA8735,
    val ortho: Boolean = true,
    val autoFittings: Boolean = true,
    val bendDegrees: Int = 90,
) {
    init {
        require(listOf(width,height,diameter).all {it.isFinite() && it > 0 && it <= 100})
        require(system in listOf("Power","ICT","HVAC","Plumbing","Fire"))
        require(bendDegrees == 45 || bendDegrees == 90)
    }
    fun section(kind:RouteKind):RouteSection = if(round || kind==RouteKind.PIPE || kind==RouteKind.CONDUIT)
        RouteSection.Circular(diameter) else RouteSection.Rectangular(width,height)
}

/** Shared node identities retain topology independently of coordinates, level and section size. */
@Serializable
data class RouteTopology(
    val networkId:String,
    val startNode:String,
    val endNode:String,
    val settings:RoutingSettings,
    val startAnchor:RouteAnchor? = null,
    val endAnchor:RouteAnchor? = null,
    val fittingOverrides:Map<String,FittingKind> = emptyMap(),
    val suppressedFittings:Set<String> = emptySet(),
    val supportSpacing:Double? = null,
    val supportKind:FittingKind = FittingKind.SUPPORT,
) {
    init {require(networkId.isNotBlank() && startNode.isNotBlank() && endNode.isNotBlank() && startNode!=endNode)
        require(supportSpacing==null || supportSpacing.isFinite() && supportSpacing>=.01)}
}
@Serializable
data class RouteAnchor(val elementId:ElementId,val localPoint:Point3)

fun defaultRoutingSettings(kind:RouteKind):RoutingSettings = when(kind) {
    RouteKind.CONDUIT -> RoutingSettings(diameter=.025,round=true)
    RouteKind.PIPE -> RoutingSettings(round=true,system="Plumbing",colourArgb=0xFF377FBD)
    RouteKind.DUCT -> RoutingSettings(width=.5,height=.3,system="HVAC",colourArgb=0xFF459E95)
    RouteKind.CABLE_BASKET -> RoutingSettings(width=.3,system="ICT")
    RouteKind.CABLE_TRAY -> RoutingSettings(width=.3)
    RouteKind.CABLE_LADDER -> RoutingSettings(width=.45,height=.1)
    RouteKind.TRUNKING -> RoutingSettings()
}

/** Below slab means below the underside of the next level's slab, or the current storey top. */
fun routingElevation(setting:ElevationSetting, level:Level?, levels:List<Level>):Double {
    val floor=level?.elevation ?: 0.0
    val top=levels.filter {it.elevation>floor+1e-6}.minByOrNull {it.elevation}
    val ceiling=floor+(level?.storeyHeight ?: 3.0)-(top?.slabThickness ?: level?.slabThickness ?: .2)
    return when(setting.reference) {
        ElevationReference.ABOVE_FINISHED_FLOOR -> floor+setting.value
        ElevationReference.ABOVE_CEILING -> ceiling+setting.value
        ElevationReference.BELOW_CEILING,ElevationReference.BELOW_SLAB -> (if(setting.reference==ElevationReference.BELOW_SLAB && top!=null) top.elevation-top.slabThickness else ceiling)-setting.value
        ElevationReference.PROJECT_DATUM -> setting.value
    }
}

/** The lowest containing room ceiling overrides the storey fallback for ceiling references. */
fun routingElevationAt(document:ProjectDocument,setting:ElevationSetting,levelId:LevelId?,point:Point3):Double {
    val level=document.levels.find {it.id==levelId}
    if(setting.reference !in setOf(ElevationReference.BELOW_CEILING,ElevationReference.ABOVE_CEILING))return routingElevation(setting,level,document.levels)
    fun contains(ring:List<Point2>,p:Point3):Boolean {
        var inside=false
        for(i in ring.indices) {val a=ring[i];val b=ring[(i+1)%ring.size]
            if((a.y>p.y)!=(b.y>p.y) && p.x<(b.x-a.x)*(p.y-a.y)/(b.y-a.y)+a.x)inside=!inside
        }
        return inside
    }
    val ceiling=document.elements.mapNotNull {e->val room=e.body as? ElementBody.Room ?: return@mapNotNull null
        if(e.levelId!=null && e.levelId!=levelId)return@mapNotNull null
        val p=measurementToLocal(e.transform,point)
        if(!contains(room.boundary.outer,p) || room.boundary.holes.any {contains(it,p)})null else measurementToWorld(e.transform,Point3(p.x,p.y,room.ceilingHeight)).z
    }.minOrNull() ?: return routingElevation(setting,level,document.levels)
    return ceiling+if(setting.reference==ElevationReference.BELOW_CEILING)-setting.value else setting.value
}
