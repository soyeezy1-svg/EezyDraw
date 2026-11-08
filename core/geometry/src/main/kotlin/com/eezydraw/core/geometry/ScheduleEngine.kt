package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.*

data class ScheduleRow(
    val cells:Map<ScheduleColumn,String>, val objectIds:Set<ElementId>, val unit:TakeoffUnit,
    val quantity:Double, val length:Double, val allowedLength:Double, val fittings:Int, val area:Double,
    val rateKey:String?, val libraryIds:Set<LibraryItemId>, val rate:Double?, val cost:BigDecimal?,
)
data class ScheduleTable(val definition:ScheduleDefinition,val rows:List<ScheduleRow>,val allowancePercent:Double,val currency:String) {
    val quantities get()=rows.groupBy {it.unit}.mapValues {(_,v)->v.sumOf {it.quantity}}
    val measuredLength get()=rows.sumOf {it.length}
    val allowedLength get()=rows.sumOf {it.allowedLength}
    val fittings get()=rows.sumOf {it.fittings}
    val area get()=rows.sumOf {it.area}
    val pricedTotal:BigDecimal get()=rows.mapNotNull {it.cost}.fold(BigDecimal.ZERO,BigDecimal::add)
    val unpricedRows get()=rows.count {it.cost==null}
}

/** One source fact per physical item/section/logical wall cut. No cached quantities are serialized. */
object ScheduleEngine {
    private data class Fact(val category:ScheduleCategory,val element:Element,val item:String,val type:String,val size:String,
        val unit:TakeoffUnit=TakeoffUnit.EACH,val quantity:Double=1.0,val length:Double=0.0,val area:Double=0.0,
        val fitting:Int=0,val routeKind:RouteKind?=null,val library:LibraryItem?=null,
        val width:Double=0.0,val height:Double=0.0,val depth:Double=0.0,val wall:String="",val position:Point3=element.transform.translation,
        val extraIds:Set<ElementId> = emptySet())

    fun build(document:ProjectDocument,definition:ScheduleDefinition,planView:Boolean=true):ScheduleTable {
        val settings=document.project.settings.takeoff
        val presentation=ScenePresentation(document,planView)
        val byId=document.elements.associateBy {it.id}
        val definitions=document.libraryItems.associateBy {it.id}
        val levels=document.levels.associateBy {it.id};val systems=document.systems.associateBy {it.id}
        fun visible(e:Element)=!definition.visibleOnly || presentation.display(e)!=ObjectDisplay.HIDDEN
        fun scoped(e:Element)=visible(e) && (definition.levels.isEmpty() || e.levelId in definition.levels) && (definition.systems.isEmpty() || e.systemId in definition.systems)
        fun title(e:Element,fallback:String)=e.name.ifBlank {fallback}
        val facts=ArrayList<Fact>()
        for(e in document.elements) {
            if(!scoped(e))continue
            when(val b=e.body) {
                is ElementBody.Component->{
                    val item=definitions[b.libraryItemId] ?: BuiltInLibrary.find(b.libraryItemId) ?: continue
                    val fitting=item.fitting
                    val w=b.size.width*abs(e.transform.scale.x);val h=b.size.height*abs(e.transform.scale.z);val d=b.size.depth*abs(e.transform.scale.y)
                    facts+=Fact(if(fitting==null)ScheduleCategory.EQUIPMENT else ScheduleCategory.FITTINGS,e,title(e,item.name),fitting?.let {"${it.routeKind.name} ${it.kind.name}"} ?: item.name,
                        fitting?.let {section(it.section)+it.outletSection?.let {out->" → "+section(out)}.orEmpty()} ?: dimensions(w,d,h),
                        fitting=if(fitting==null)0 else 1,routeKind=fitting?.routeKind,library=item,width=w,height=h,depth=d,
                        position=measurementToWorld(e.transform,Point3(b.size.width/2,b.size.depth/2,b.size.height/2)))
                }
                is ElementBody.Route->{
                    val path=b.path.map {measurementToWorld(e.transform,it)}
                    val length=path.zipWithNext().sumOf {(a,z)->distance(a,z)}
                    facts+=Fact(ScheduleCategory.ROUTES,e,title(e,b.kind.name),b.kind.name,section(b.section),TakeoffUnit.METRE,length,length,routeKind=b.kind,position=path.first())
                    // Modern routing owns separate fitting components; embedded fittings belong to legacy routes.
                    if(b.routing==null)for(f in b.fittings)facts+=Fact(ScheduleCategory.FITTINGS,e,f.kind.name,"${b.kind.name} ${f.kind.name}",section(b.section),
                        fitting=1,routeKind=b.kind,library=f.libraryItemId?.let {definitions[it] ?: BuiltInLibrary.find(it)},position=path[f.pathVertexIndex])
                }
                is ElementBody.Room->{
                    val area=polygonArea(b.boundary.outer,e.transform)-b.boundary.holes.sumOf {polygonArea(it,e.transform)}
                    facts+=Fact(ScheduleCategory.ROOMS,e,title(e,b.number.ifBlank {"Room"}),"Room","",TakeoffUnit.SQUARE_METRE,area=area.coerceAtLeast(0.0),quantity=area.coerceAtLeast(0.0))
                }
                else->Unit
            }
        }
        if(definition.category in setOf(ScheduleCategory.WALL_CUTS,ScheduleCategory.BOQ)) {
            val frames=wallFrames(document).associateBy {it.host.id to it.roomSide}
            // Mitre wedges and straight strips describe one logical source cut, never extra BOQ items.
            for((_,cuts) in wallServiceCuts(document).groupBy {Triple(it.wallId,it.sourceId,it.roomSide)}) {
                val cut=cuts.first();val source=byId[cut.sourceId] ?: continue;val host=byId[cut.wallId] ?: continue
                if(!scoped(source) || !visible(host) || definition.visibleOnly && !document.project.settings.wallServices.showCuts)continue
                val frame=frames[cut.wallId to cut.roomSide] ?: continue
                val outline=cuts.flatMap {it.outline};val minX=outline.minOf {it.x};val maxX=outline.maxOf {it.x};val minY=outline.minOf {it.y};val maxY=outline.maxOf {it.y}
                val route=source.body as? ElementBody.Route
                val length=if(cut.mode==WallServiceMode.CHASE && route!=null)route.path.map {measurementToWorld(source.transform,it)}.zipWithNext().sumOf {(a,b)->distance(a,b)}else 0.0
                val w=if(route!=null && cut.mode==WallServiceMode.CHASE)when(val section=route.section){is RouteSection.Circular->section.diameter;is RouteSection.Rectangular->section.width}+2*source.wallService()!!.options.clearance else maxX-minX
                val h=maxY-minY;val depth=cuts.maxOf {it.depth}
                facts+=Fact(ScheduleCategory.WALL_CUTS,source,title(source,"Wall cut"),cut.mode.name,dimensions(w,h,depth),
                    if(length>0)TakeoffUnit.METRE else TakeoffUnit.EACH,if(length>0)length else 1.0,length,
                    width=w,height=h,depth=depth,wall=title(host,host.id.value),position=frame.world((minX+maxX)/2,(cut.fromDepth+cut.toDepth)/2,(minY+maxY)/2),extraIds=setOf(host.id))
            }
        }
        val rooms=document.elements.filter {it.body is ElementBody.Room}
        fun room(f:Fact):String {
            val e=if(f.category==ScheduleCategory.ROOMS)f.element else rooms.firstOrNull {r->
                (r.levelId==null || r.levelId==f.element.levelId) && insideRoom(r,f.position)
            } ?: return ""
            return title(e,(e.body as ElementBody.Room).number.ifBlank {"Room"})
        }
        fun system(f:Fact)=systems[f.element.systemId]?.name ?: (f.element.body as? ElementBody.Route)?.routing?.settings?.system ?: f.library?.defaultSystem.orEmpty()
        fun level(f:Fact)=levels[f.element.levelId]?.name ?: "Unassigned"
        fun position(f:Fact)=f.position.let {p->val o=document.project.origin;"${number(p.x+o.x)}, ${number(p.y+o.y)}, ${number(p.z+o.z)}"}
        fun group(f:Fact,g:ScheduleGroup):String=when(g){ScheduleGroup.ITEM->f.item;ScheduleGroup.TYPE->f.type;ScheduleGroup.SIZE->f.size;ScheduleGroup.SYSTEM->system(f);ScheduleGroup.LEVEL->f.element.levelId?.value.orEmpty();ScheduleGroup.ROOM->room(f);ScheduleGroup.WALL->f.wall;ScheduleGroup.POSITION->position(f)}
        fun rateKey(f:Fact)=listOf(f.category.name,f.library?.let {it.catalogSourceId ?: it.fitting?.baseTypeId ?: it.id}?.value ?: f.type,f.size,f.unit.name).joinToString("|") {"${it.length}:$it"}
        fun rate(f:Fact)=settings.rates[rateKey(f)]?.get(settings.currency) ?: f.library?.unitRates?.get(settings.currency)
        val selected=facts.filter {f->
            (definition.category==ScheduleCategory.BOQ || f.category==definition.category || definition.category==ScheduleCategory.ROUTES && f.category==ScheduleCategory.FITTINGS) &&
                (definition.routeKind==null || f.routeKind==definition.routeKind) &&
                listOf(f.item,f.type,f.size,system(f),level(f),room(f),f.wall).joinToString(" ").contains(definition.query,true)
        }
        val routeMode=definition.category==ScheduleCategory.ROUTES
        val routeFacts=facts.filter {it.category==ScheduleCategory.ROUTES}.associateBy {it.element.id}
        val grouped=selected.groupBy {f->
            definition.grouping.map {g->
                val owner=(f.element.properties["routingOwner"] as? ParameterValue.Text)?.value?.let(::ElementId) ?: f.element.id
                val route=if(routeMode && f.fitting>0)routeFacts[owner]else null
                if(routeMode && route==null && f.fitting>0)when(g){ScheduleGroup.TYPE->f.routeKind?.name.orEmpty();ScheduleGroup.SIZE->f.size.substringBefore(" → ");else->group(f,g)}else group(route ?: f,g)
            }+listOf(if(routeMode)TakeoffUnit.METRE.name else f.unit.name)+
                if(definition.category==ScheduleCategory.BOQ)listOf(rateKey(f)) else emptyList()
        }
        val rows=grouped.values.mapNotNull {members->
            val primary=if(routeMode)members.filter {it.category==ScheduleCategory.ROUTES}else members
            if(primary.isEmpty())return@mapNotNull null
            fun combined(value:(Fact)->String)=primary.map(value).distinct().filter {it.isNotBlank()}.joinToString(" / ")
            val length=primary.sumOf {it.length};val allowed=length*(1+settings.allowancePercent/100)
            val unit=if(routeMode)TakeoffUnit.METRE else primary.first().unit
            val quantity=if(unit==TakeoffUnit.METRE)allowed else primary.sumOf {it.quantity}
            val area=primary.sumOf {it.area};val fittings=members.sumOf {it.fitting}
            val keys=primary.map(::rateKey).distinct();val rates=primary.map(::rate).distinct();val unitRate=rates.singleOrNull()
            val cost=if(primary.any {rate(it)==null})null else primary.fold(BigDecimal.ZERO) {sum,f->
                val q=f.quantity*(if(f.unit==TakeoffUnit.METRE)1+settings.allowancePercent/100 else 1.0)
                sum+BigDecimal.valueOf(q).multiply(BigDecimal.valueOf(rate(f)!!))
            }.setScale(2,RoundingMode.HALF_UP)
            val cells=mapOf(ScheduleColumn.ITEM to combined {it.item},ScheduleColumn.TYPE to combined {it.type},ScheduleColumn.SIZE to combined {it.size},
                ScheduleColumn.SYSTEM to combined(::system),ScheduleColumn.LEVEL to combined(::level),ScheduleColumn.ROOM to combined(::room),
                ScheduleColumn.QUANTITY to number(quantity),ScheduleColumn.UNIT to unit.symbol,ScheduleColumn.LENGTH to number(length),ScheduleColumn.ALLOWED_LENGTH to number(allowed),
                ScheduleColumn.FITTINGS to fittings.toString(),ScheduleColumn.AREA to number(area),ScheduleColumn.WIDTH to combined {number(it.width*1000)},ScheduleColumn.HEIGHT to combined {number(it.height*1000)},
                ScheduleColumn.DEPTH to combined {number(it.depth*1000)},ScheduleColumn.WALL to combined {it.wall},ScheduleColumn.POSITION to combined(::position),
                ScheduleColumn.RATE to (unitRate?.let(::number) ?: ""),ScheduleColumn.COST to (cost?.toPlainString() ?: ""))
            ScheduleRow(cells,members.flatMap {listOf(it.element.id)+it.extraIds}.toSet(),unit,quantity,length,allowed,fittings,area,keys.singleOrNull(),primary.mapNotNull {it.library?.id}.toSet(),unitRate,cost)
        }
        val numeric=setOf(ScheduleColumn.QUANTITY,ScheduleColumn.LENGTH,ScheduleColumn.ALLOWED_LENGTH,ScheduleColumn.FITTINGS,ScheduleColumn.AREA,ScheduleColumn.RATE,ScheduleColumn.COST)
        val sorted=rows.sortedWith(if(definition.sort in numeric)compareBy {it.cells[definition.sort]?.toDoubleOrNull() ?: Double.NEGATIVE_INFINITY}else compareBy {it.cells[definition.sort].orEmpty().lowercase()})
        return ScheduleTable(definition,if(definition.descending)sorted.reversed()else sorted,settings.allowancePercent,settings.currency)
    }

    private fun section(s:RouteSection)=when(s){is RouteSection.Circular->"Ø${number(s.diameter*1000)}";is RouteSection.Rectangular->"${number(s.width*1000)} × ${number(s.height*1000)}"}
    private fun dimensions(vararg v:Double)=v.joinToString(" × "){number(it*1000)}
    fun number(n:Double)=String.format(Locale.ROOT,"%.4f",n).trimEnd('0').trimEnd('.').let {if(it=="-0")"0"else it}
    private fun distance(a:Point3,b:Point3)=hypot(hypot(a.x-b.x,a.y-b.y),a.z-b.z)
    private fun polygonArea(ring:List<Point2>,transform:Transform3):Double {
        val p=ring.map {measurementToWorld(transform,Point3(it.x,it.y))};val o=p.first();var x=0.0;var y=0.0;var z=0.0
        for(i in 1 until p.lastIndex){val a=p[i];val b=p[i+1];val ax=a.x-o.x;val ay=a.y-o.y;val az=a.z-o.z;val bx=b.x-o.x;val by=b.y-o.y;val bz=b.z-o.z;x+=ay*bz-az*by;y+=az*bx-ax*bz;z+=ax*by-ay*bx}
        return hypot(hypot(x,y),z)/2
    }
    private fun insideRoom(e:Element,p:Point3):Boolean {
        val q=measurementToLocal(e.transform,p);val b=e.body as ElementBody.Room
        fun ring(points:List<Point2>):Boolean {var hit=false;for(i in points.indices){val a=points[i];val z=points[(i+1)%points.size];if((a.y>q.y)!=(z.y>q.y) && q.x<(z.x-a.x)*(q.y-a.y)/(z.y-a.y)+a.x)hit=!hit};return hit}
        return ring(b.boundary.outer) && b.boundary.holes.none(::ring)
    }
}
