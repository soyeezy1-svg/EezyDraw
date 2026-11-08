package com.eezydraw.core.model

import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable enum class FloorInstallation { IN_SCREED, IN_SLAB, RAISED_FLOOR }
@Serializable enum class FloorDeviceKind { FLOOR_BOX, FLOOR_SOCKET, SLAB_SLEEVE }
@Serializable data class FloorServiceSettings(
    val showServices:Boolean=true,
    val showChannels:Boolean=true,
    val screedThickness:Double=.08,
    val clearance:Double=.005,
    val maxDepthFraction:Double=.5,
    val defaultDepths:Map<FloorInstallation,Double> = mapOf(FloorInstallation.IN_SCREED to .02,FloorInstallation.IN_SLAB to .1,FloorInstallation.RAISED_FLOOR to .3),
) {
    init {require(screedThickness.isFinite() && screedThickness>0);require(clearance.isFinite() && clearance in 0.0..1.0)
        require(maxDepthFraction in .01..1.0);require(defaultDepths.values.all {it.isFinite() && it>=0 && it<=100})}
}
fun floorInstallationLabel(value:FloorInstallation)=when(value){FloorInstallation.IN_SCREED->"In screed";FloorInstallation.IN_SLAB->"In slab";FloorInstallation.RAISED_FLOOR->"Under floor (raised floor void)"}
fun Element.floorRouting():RoutingSettings?=(body as? ElementBody.Route)?.routing?.settings?.takeIf {it.floorInstallation!=null}
fun floorDevice(item:LibraryItem):FloorDeviceKind?=item.floorService ?: if(item.symbol==LibrarySymbol.FLOOR_BOX)FloorDeviceKind.FLOOR_BOX else null
fun floorDepthAt(document:ProjectDocument,e:Element,p:Point3)=(document.levels.find {it.id==e.levelId}?.elevation ?: 0.0)-p.z
fun floorRoutingElevation(document:ProjectDocument,settings:RoutingSettings,level:LevelId?,point:Point3)=
    if(settings.floorInstallation!=null)(document.levels.find {it.id==level}?.elevation ?: 0.0)-settings.floorDepth else routingElevationAt(document,settings.elevation,level,point)
fun floorContains(slab:Element,world:Point3):Boolean {
    val b=slab.body as? ElementBody.Slab ?: return false
    val p=measurementToLocal(slab.transform,world)
    fun inside(ring:List<Point2>):Boolean {var hit=false
        for(i in ring.indices){val a=ring[i];val z=ring[(i+1)%ring.size];if((a.y>p.y)!=(z.y>p.y) && p.x<(z.x-a.x)*(p.y-a.y)/(z.y-a.y)+a.x)hit=!hit};return hit}
    return inside(b.boundary.outer) && b.boundary.holes.none(::inside)
}
fun floorSlab(document:ProjectDocument,point:Point3,level:LevelId?)=document.elements.filter {it.body is ElementBody.Slab && (level==null || it.levelId==level) && floorContains(it,point)}.minByOrNull {abs(it.transform.translation.z-point.z)}

/** Physical metres in the slab's orthonormal plane; signed scaling remains in its host transform. */
fun floorLocalMetres(slab:Element,world:Point3):Point3=measurementToLocal(slab.transform,world).let {Point3(it.x*abs(slab.transform.scale.x),it.y*abs(slab.transform.scale.y),it.z*abs(slab.transform.scale.z))}
data class FloorCut(val slabId:ElementId,val sourceId:ElementId,val outline:List<Point2>,val depth:Double)
fun floorServiceCuts(document:ProjectDocument,slab:Element):List<FloorCut> {
    val thickness=(slab.body as ElementBody.Slab).thickness*abs(slab.transform.scale.z)
    val clearance=document.project.settings.floorServices.clearance
    val direct=document.elements.flatMap {e->when(val body=e.body) {
        is ElementBody.Route->{val config=e.floorRouting()
            if(config==null || config.floorInstallation==FloorInstallation.RAISED_FLOOR || e.levelId!=slab.levelId)emptyList() else {
                val width=when(val s=body.section){is RouteSection.Circular->s.diameter;is RouteSection.Rectangular->s.width}+2*clearance
                val h=when(val s=body.section){is RouteSection.Circular->s.diameter;is RouteSection.Rectangular->s.height}
                body.path.map {floorLocalMetres(slab,measurementToWorld(e.transform,it))}.zipWithNext().mapNotNull {(a,b)->
                    val length=hypot(b.x-a.x,b.y-a.y);val depth=-min(a.z,b.z)+h/2+clearance
                    if(depth<=0.0)return@mapNotNull null
                    val x=if(length<1e-9)width/2 else -(b.y-a.y)/length*width/2
                    val y=if(length<1e-9)width/2 else (b.x-a.x)/length*width/2
                    val outline=if(length<1e-9)listOf(Point2(a.x-x,a.y-y),Point2(a.x+x,a.y-y),Point2(a.x+x,a.y+y),Point2(a.x-x,a.y+y))else listOf(Point2(a.x+x,a.y+y),Point2(b.x+x,b.y+y),Point2(b.x-x,b.y-y),Point2(a.x-x,a.y-y))
                    FloorCut(slab.id,e.id,outline,depth)
                }
            }}
        is ElementBody.Component->{val item=document.libraryItems.find {it.id==body.libraryItemId} ?: BuiltInLibrary.items.find {it.id==body.libraryItemId}
            val kind=item?.let(::floorDevice)
            if(kind==null || e.levelId!=slab.levelId || body.host?.hostElementId?.let {it!=slab.id}==true || !floorContains(slab,e.transform.translation))emptyList()else {
                val p=listOf(Point3(),Point3(body.size.width),Point3(body.size.width,body.size.depth),Point3(y=body.size.depth)).map {floorLocalMetres(slab,measurementToWorld(e.transform,it))}
                val minX=p.minOf {it.x}-clearance;val maxX=p.maxOf {it.x}+clearance;val minY=p.minOf {it.y}-clearance;val maxY=p.maxOf {it.y}+clearance
                val depth=if(kind==FloorDeviceKind.SLAB_SLEEVE)thickness else -p.minOf {it.z}+clearance
                if(depth<=0.0)emptyList()else listOf(FloorCut(slab.id,e.id,listOf(Point2(minX,minY),Point2(maxX,minY),Point2(maxX,maxY),Point2(minX,maxY)),depth))
            }}
        else->emptyList()
    }}
    val fake=slab.copy(transform=Transform3())
    val frame=WallFrame(fake,null,Point3(),Point3(x=1.0),Point3(y=1.0),1.0,thickness,1.0)
    val routes=document.elements.mapNotNull {e->val b=e.body as? ElementBody.Route ?: return@mapNotNull null
        val config=e.floorRouting() ?: return@mapNotNull null
        if(config.floorInstallation==FloorInstallation.RAISED_FLOOR || e.levelId!=slab.levelId)return@mapNotNull null
        val h=when(val section=b.section){is RouteSection.Circular->section.diameter;is RouteSection.Rectangular->section.height}
        val path=b.path.map {floorLocalMetres(slab,measurementToWorld(e.transform,it))}
        val depth=-path.minOf {it.z}+h/2+clearance
        if(depth<=0.0)return@mapNotNull null
        e.copy(transform=Transform3(),body=b.copy(path=path.map {Point3(it.x,0.0,it.y)},wallService=WallServiceAttachment(slab.id,WallFaceSide.LEFT,WallServiceOptions(WallServiceMode.CHASE,clearance=clearance,depth=depth.coerceAtMost(10.0)))))
    }
    val joins=wallChaseMitres(document.copy(elements=routes),mapOf((slab.id to null) to frame)).map {FloorCut(slab.id,it.sourceId,it.outline,it.depth)}
    return direct+joins
}

data class FloorScheduleRow(val sourceId:ElementId,val levelId:LevelId?,val name:String,val size:String,val depth:Double,val position:Point3,val length:Double)
fun floorSchedule(document:ProjectDocument):List<FloorScheduleRow> = document.elements.mapNotNull {e->
    val route=e.body as? ElementBody.Route
    val item=(e.body as? ElementBody.Component)?.let {b->document.libraryItems.find {it.id==b.libraryItemId} ?: BuiltInLibrary.items.find {it.id==b.libraryItemId}}
    if(e.floorRouting()==null && item?.let(::floorDevice)==null)return@mapNotNull null
    val points=route?.path?.map {measurementToWorld(e.transform,it)} ?: listOf(e.transform.translation)
    val size=when(val s=route?.section){is RouteSection.Circular->"Ø${s.diameter*1000} mm";is RouteSection.Rectangular->"${s.width*1000} × ${s.height*1000} mm";null->(e.body as ElementBody.Component).size.let {"${it.width*1000} × ${it.depth*1000} × ${it.height*1000} mm"}}
    FloorScheduleRow(e.id,e.levelId,e.name.ifBlank {item?.name ?: "Floor route"},size,points.maxOf {floorDepthAt(document,e,it)},points.first(),points.zipWithNext().sumOf {(a,b)->sqrt((a.x-b.x).pow(2)+(a.y-b.y).pow(2)+(a.z-b.z).pow(2))})
}
fun floorServiceWarnings(document:ProjectDocument):List<String> {
    val settings=document.project.settings.floorServices
    val routes=document.elements.filter {it.floorRouting()!=null}
    val warnings=mutableListOf<String>()
    for(e in routes) {
        val config=e.floorRouting()!!;if(config.floorInstallation==FloorInstallation.RAISED_FLOOR)continue
        val b=e.body as ElementBody.Route
        val points=b.path.map {measurementToWorld(e.transform,it)}
        val h=when(val s=b.section){is RouteSection.Circular->s.diameter;is RouteSection.Rectangular->s.height}
        val depth=points.maxOf {floorDepthAt(document,e,it)}+h/2+settings.clearance
        val thickness=if(config.floorInstallation==FloorInstallation.IN_SCREED)settings.screedThickness else floorSlab(document,points.first(),e.levelId)?.let {(it.body as ElementBody.Slab).thickness*abs(it.transform.scale.z)} ?: document.levels.find {it.id==e.levelId}?.slabThickness ?: .2
        if(depth>thickness*settings.maxDepthFraction)warnings+="${e.name}: floor cut depth exceeds ${(settings.maxDepthFraction*100).roundToInt()}% of ${if(config.floorInstallation==FloorInstallation.IN_SCREED)"screed"else "slab"} thickness."
    }
    for(i in routes.indices)for(j in i+1 until routes.size) {
        val a=routes[i];val b=routes[j];if(a.levelId!=b.levelId)continue
        val ar=a.body as ElementBody.Route;val br=b.body as ElementBody.Route
        if(ar.routing?.let {t->br.routing?.let {u->setOf(t.startNode,t.endNode).intersect(setOf(u.startNode,u.endNode)).isNotEmpty()}}==true)continue
        for((p,q)in ar.path.map {measurementToWorld(a.transform,it)}.zipWithNext())for((r,s)in br.path.map {measurementToWorld(b.transform,it)}.zipWithNext()) {
            val dx=q.x-p.x;val dy=q.y-p.y;val ex=s.x-r.x;val ey=s.y-r.y;val den=dx*ey-dy*ex
            if(abs(den)<1e-10)continue
            val t=((r.x-p.x)*ey-(r.y-p.y)*ex)/den;val u=((r.x-p.x)*dy-(r.y-p.y)*dx)/den
            if(t>1e-7 && t<1-1e-7 && u>1e-7 && u<1-1e-7 && abs(p.z+(q.z-p.z)*t-r.z-(s.z-r.z)*u)<.01)warnings+="Floor route clash: ${a.name} crosses ${b.name} at the same depth."
        }
    }
    return warnings.distinct()
}
