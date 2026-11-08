package com.eezydraw.core.editing

import com.eezydraw.core.geometry.ScenePresentation
import com.eezydraw.core.model.*
import java.util.UUID
import kotlin.math.*

private const val EPS=1e-6
const val ROUTING_OWNER="routingOwner"
const val ROUTING_NODE="routingNode"
data class RoutePick(val point:Point3,val settings:RoutingSettings,val levelId:LevelId?=null)
data class RouteSnap(val point:Point3,val node:String?=null,val sectionId:ElementId?=null,val anchor:RouteAnchor?=null)
fun routeLength(element:Element):Double = (element.body as? ElementBody.Route)?.path?.map {measurementToWorld(element.transform,it)}?.zipWithNext()?.sumOf {(a,b)->distance3(a,b)} ?: 0.0
private fun distance3(a:Point3,b:Point3)=sqrt((a.x-b.x).pow(2)+(a.y-b.y).pow(2)+(a.z-b.z).pow(2))
private fun minus(a:Point3,b:Point3)=Point3(a.x-b.x,a.y-b.y,a.z-b.z)
private fun lerp(a:Point3,b:Point3,t:Double)=Point3(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t,a.z+(b.z-a.z)*t)
private fun unit(a:Point3):Point3 {val n=distance3(a,Point3());require(n>EPS){"Route sections need distinct endpoints"};return Point3(a.x/n,a.y/n,a.z/n)}
private fun sectionNodes(e:Element):List<Pair<String,Point3>> {
    val b=e.body as? ElementBody.Route ?: return emptyList();val t=b.routing ?: return emptyList()
    return listOf(t.startNode to measurementToWorld(e.transform,b.path.first()),t.endNode to measurementToWorld(e.transform,b.path.last()))
}

/** Same resolver for preview and commit. Capture is bounded in XYZ so stacked services do not join. */
fun snapRoute(document:ProjectDocument,kind:RouteKind,point:Point3,tolerance:Double=.15,levelId:LevelId?=null,captureEquipmentInPlan:Boolean=false):RouteSnap {
    val display=ScenePresentation(document,planView=true)
    val candidates=mutableListOf<RouteSnap>()
    for(e in document.elements) {
        if(ROUTING_OWNER in e.properties || display.display(e)==ObjectDisplay.HIDDEN || e.lockMode!=LockMode.NONE || document.layers.find {it.id==e.layerId}?.locked==true)continue
        when(val b=e.body) {
            is ElementBody.Route -> if(b.kind==kind) {
                val nodes=sectionNodes(e)
                val path=b.path.map {measurementToWorld(e.transform,it)}
                for(i in 0 until path.lastIndex) {
                    val a=path[i];val z=path[i+1];val d=minus(z,a);val v=minus(point,a)
                    val n=d.x*d.x+d.y*d.y+d.z*d.z
                    if(n<EPS*EPS)continue
                    val t=((v.x*d.x+v.y*d.y+v.z*d.z)/n).coerceIn(0.0,1.0)
                    val p=lerp(a,z,t)
                    val node=nodes.firstOrNull {distance3(it.second,p)<EPS}?.first
                    candidates+=RouteSnap(p,node,e.id)
                }
            }
            is ElementBody.Component -> {
                if(levelId!=null && e.levelId!=null && e.levelId!=levelId)continue
                val item=document.libraryItems.find {it.id==b.libraryItemId}
                val ports=item?.fitting?.ports?.map {p->val size=item.geometrySize ?: item.defaultSize
                    Point3(p.position.x*b.size.width/size.width,p.position.y*b.size.depth/size.depth,p.position.z*b.size.height/size.height)}
                    ?: listOf(Point3(0.0,0.0,b.size.height),Point3(0.0,-b.size.depth/2,b.size.height/2),Point3(0.0,b.size.depth/2,b.size.height/2),Point3(-b.size.width/2,0.0,b.size.height/2),Point3(b.size.width/2,0.0,b.size.height/2))
                for(p in ports)candidates+=RouteSnap(measurementToWorld(e.transform,p),anchor=RouteAnchor(e.id,p))
            }
            else -> Unit
        }
    }
    fun distance(candidate:RouteSnap)=if(captureEquipmentInPlan && candidate.anchor!=null)hypot(candidate.point.x-point.x,candidate.point.y-point.y)else distance3(candidate.point,point)
    return candidates.filter {distance(it)<=tolerance}.minWithOrNull(compareBy<RouteSnap> {distance(it)}.thenBy {abs(it.point.z-point.z)}) ?: RouteSnap(point)
}

/** Elevation changes insert a vertical leg at the previous XY before continuing horizontally. */
fun appendRoutePoint(picks:List<RoutePick>,point:Point3,settings:RoutingSettings,level:Level?,levels:List<Level>,document:ProjectDocument?=null):List<RoutePick> {
    val z=document?.let {floorRoutingElevation(it,settings,level?.id,point)} ?: routingElevation(settings.elevation,level,levels)
    var at=point.copy(z=z)
    val last=picks.lastOrNull()
    if(last!=null && settings.ortho)at=if(abs(at.x-last.point.x)>=abs(at.y-last.point.y))at.copy(y=last.point.y)else at.copy(x=last.point.x)
    val result=picks.toMutableList()
    if(last!=null && abs(last.point.z-z)>EPS)result+=RoutePick(last.point.copy(z=z),settings,level?.id)
    if(result.lastOrNull()?.point?.let {distance3(it,at)>EPS}!=false)result+=RoutePick(at,settings,level?.id)
    require(result.size<=512){"Finish the route before adding more points"}
    return result
}

/** Connection capture may add an end riser to an equipment port at another elevation. */
fun appendRoutingPick(document:ProjectDocument,kind:RouteKind,picks:List<RoutePick>,point:Point3,settings:RoutingSettings,level:Level?):List<RoutePick> {
    if(settings.wallOptions!=null)return WallServices.append(document,kind,picks,point,settings,level)
    val next=appendRoutePoint(picks,point,settings,level,document.levels,document)
    val desired=next.last()
    val capture=snapRoute(document,kind,desired.point,levelId=level?.id,captureEquipmentInPlan=true)
    val result=next.dropLast(1).toMutableList()
    if(picks.isNotEmpty() && capture.anchor!=null && abs(capture.point.z-desired.point.z)>EPS) {
        val horizontal=desired.copy(point=Point3(capture.point.x,capture.point.y,desired.point.z))
        if(result.lastOrNull()?.point!=horizontal.point)result+=horizontal
    }
    if(result.lastOrNull()?.point!=capture.point)result+=desired.copy(point=capture.point)
    return result
}

object ServiceRouting {
    fun create(document:ProjectDocument,kind:RouteKind,picks:List<RoutePick>):EditCommand {
        require(picks.size>=2){"Place at least two route points"}
        var doc=document
        val floor=picks.any {it.settings.floorInstallation!=null}
        if(floor && doc.layers.none {it.name=="Underfloor services"})doc=doc.copy(layers=doc.layers+Layer(LayerId(UUID.randomUUID().toString()),doc.project.id,"Underfloor services"))
        val layer=doc.layers.firstOrNull {it.visible && !it.locked && (!floor || it.name=="Underfloor services")} ?: error("Unlock and show the routing layer")
        val network=UUID.randomUUID().toString()
        val nodes=mutableMapOf<Int,String>()
        val anchors=mutableMapOf<Int,RouteAnchor>()
        // Splitting preserves host identity and references; each piece shares the new junction node.
        val beveled=mutableListOf<RoutePick>()
        picks.forEachIndexed {i,p->
            val a=picks.getOrNull(i-1);val b=picks.getOrNull(i+1)
            if(a!=null && b!=null && p.settings.wallOptions==null && p.settings.autoFittings && p.settings.bendDegrees==45 &&
                abs(a.point.z-p.point.z)<EPS && abs(b.point.z-p.point.z)<EPS) {
                val incoming=unit(minus(p.point,a.point));val outgoing=unit(minus(b.point,p.point))
                if(abs(incoming.x*outgoing.x+incoming.y*outgoing.y)<EPS) {
                    val setback=minOf(distance3(a.point,p.point)/4,distance3(p.point,b.point)/4,p.settings.width.coerceAtLeast(p.settings.diameter))
                    beveled+=p.copy(point=lerp(p.point,a.point,setback/distance3(a.point,p.point)))
                    beveled+=p.copy(point=lerp(p.point,b.point,setback/distance3(b.point,p.point)))
                } else beveled+=p
            } else beveled+=p
        }
        val resolved=beveled.mapIndexed {i,pick->
            val captured=snapRoute(doc,kind,pick.point)
            val snap=if(pick.settings.wallOptions!=null && captured.anchor!=null)RouteSnap(pick.point)else captured
            var node=snap.node
            if(snap.sectionId!=null && node==null) {
                val host=doc.elements.first {it.id==snap.sectionId};val b=host.body as ElementBody.Route
                require(b.path.size==2){"Connect to a section endpoint on legacy multi-point routes"}
                val t=b.routing ?: RouteTopology(network,UUID.randomUUID().toString(),UUID.randomUUID().toString(),pick.settings)
                val atStart=distance3(measurementToWorld(host.transform,b.path.first()),snap.point)<EPS
                val atEnd=distance3(measurementToWorld(host.transform,b.path.last()),snap.point)<EPS
                if(atStart || atEnd) {
                    node=if(atStart)t.startNode else t.endNode
                    doc=doc.copy(elements=doc.elements.map {if(it.id==host.id)host.copy(body=b.copy(routing=t))else it})
                } else {
                node=UUID.randomUUID().toString()
                val local=measurementToLocal(host.transform,snap.point)
                val first=host.copy(body=b.copy(path=listOf(b.path.first(),local),routing=t.copy(endNode=node,endAnchor=null)))
                val second=host.copy(id=ElementId(UUID.randomUUID().toString()),body=b.copy(path=listOf(local,b.path.last()),routing=t.copy(startNode=node,startAnchor=null)))
                doc=doc.copy(elements=doc.elements.map {if(it.id==host.id)first else it}+second)
                }
            }
            nodes[i]=node ?: nodes.entries.firstOrNull {entry->distance3(beveled[entry.key].point,snap.point)<EPS}?.value ?: UUID.randomUUID().toString()
            snap.anchor?.let {anchors[i]=it}
            pick.copy(point=snap.point)
        }
        val additions=resolved.zipWithNext().mapIndexed {i,(a,b)->
            require(distance3(a.point,b.point)>EPS){"A route section has zero length"}
            val config=(if(abs(a.point.z-b.point.z)>EPS)b.settings else a.settings).let {if(it.floorInstallation!=null)it.copy(elevation=ElevationSetting(-it.floorDepth,ElevationReference.ABOVE_FINISHED_FLOOR))else it}
            val system=doc.systems.find {it.name.equals(config.system,true)} ?: BuildingSystem(SystemId(UUID.randomUUID().toString()),doc.project.id,config.system,discipline(config.system),config.colourArgb).also {doc=doc.copy(systems=doc.systems+it)}
            require(system.visible){"Show the selected system before routing"}
            Element(ElementId(UUID.randomUUID().toString()),doc.project.id,layer.id,
                ElementBody.Route(kind,listOf(Point3(),minus(b.point,a.point)),config.section(kind),config.elevation,
                    routing=RouteTopology(network,nodes.getValue(i),nodes.getValue(i+1),config,anchors[i],anchors[i+1])),
                levelId=a.levelId,systemId=system.id,name=kind.name.replace('_',' '),transform=Transform3(a.point),
                properties=mapOf(ELEMENT_COLOUR_KEY to ParameterValue.Colour(config.colourArgb)))
        }
        val hosted=additions.map {e->val options=(e.body as ElementBody.Route).routing!!.settings.wallOptions
            if(options==null)e else WallServices.attach(doc,e,options)
        }
        val result=reconcile(document,doc.copy(elements=doc.elements+hosted))
        FloorServices.validateCuts(document,result)
        return EditCommands.between(document,result,"Draw service route")
    }

    /** A changed node drives every incident endpoint. Locks are checked by the resulting sparse command. */
    fun reconcile(before:ProjectDocument,after:ProjectDocument):ProjectDocument {
        val old=before.elements.associateBy {it.id}
        val moved=linkedMapOf<String,Point3>()
        for(e in after.elements) {
            val previous=old[e.id] ?: continue
            if(e.body is ElementBody.Route && previous!=e) {
                val oldNodes=sectionNodes(previous).toMap()
                for((id,p) in sectionNodes(e))if(oldNodes[id]?.let {distance3(it,p)>EPS}==true) {
                    require(moved[id]?.let {distance3(it,p)<EPS}!=false){"Connected endpoints have conflicting edits"};moved[id]=p
                }
            }
        }
        val elementsById=after.elements.associateBy {it.id}
        for(e in after.elements) {
            val r=e.body as? ElementBody.Route ?: continue;val t=r.routing ?: continue
            for((node,anchor) in listOf(t.startNode to t.startAnchor,t.endNode to t.endAnchor)) {
                val host=anchor?.elementId?.let {elementsById[it]} ?: continue
                moved[node]=measurementToWorld(host.transform,anchor.localPoint)
            }
        }
        val routes=after.elements.map {e->
            val b=e.body as? ElementBody.Route;val t=b?.routing
            if(b==null || t==null)e else {
                val path=b.path.toMutableList()
                moved[t.startNode]?.let {path[0]=measurementToLocal(e.transform,it)}
                moved[t.endNode]?.let {path[path.lastIndex]=measurementToLocal(e.transform,it)}
                require(path.zipWithNext().all {(a,z)->distance3(a,z)>EPS}){"Connected edit collapses a route section"}
                e.copy(body=b.copy(path=path,routing=t.copy(startAnchor=t.startAnchor?.takeIf {it.elementId in elementsById},endAnchor=t.endAnchor?.takeIf {it.elementId in elementsById})))
            }
        }.filterNot {ROUTING_OWNER in it.properties}
        var doc=after.copy(elements=routes)
        val definitions=after.libraryItems.toMutableList()
        val generated=mutableListOf<Element>()
        val trimmed=mutableMapOf<Pair<ElementId,String>,Point3>()
        fun fitting(owner:Element,node:String,point:Point3,requestedKind:FittingKind,section:RouteSection,outlet:RouteSection?=null,direction:Point3=Point3(y=1.0),suffix:String="",incident:List<Pair<Element,Point3>> = emptyList(),bendAngle:Double?=null,offset:Pair<Double,Double>?=null) {
            val body=owner.body as ElementBody.Route;val t=body.routing ?: return
            val kind=t.fittingOverrides[node] ?: requestedKind
            val id=ElementId("routing:$node$suffix")
            if(id.value in t.suppressedFittings)return
            val base=BuiltInLibrary.items.firstOrNull {it.fitting?.let {f->f.routeKind==body.kind && f.kind==kind && (f.section is RouteSection.Circular)==(section is RouteSection.Circular)}==true} ?: error("No $kind fitting for ${body.kind}")
            val config=base.parameters + if(bendAngle!=null && kind in setOf(FittingKind.ELBOW_45,FittingKind.ELBOW_90,FittingKind.BEND_90_VERTICAL_INSIDE,FittingKind.BEND_90_VERTICAL_OUTSIDE))mapOf("routingAngleRadians" to ParameterValue.Number(bendAngle,Quantity.ANGLE))else emptyMap()
            val configured=base.copy(catalogSourceId=base.fitting!!.baseTypeId,parameters=config+mapOf("routingGenerated" to ParameterValue.Flag(true))+(offset?.let {mapOf("routingOffsetX" to ParameterValue.Number(it.first,Quantity.LENGTH),"routingOffsetLength" to ParameterValue.Number(it.second,Quantity.LENGTH))} ?: emptyMap()))
            val item=requireNotNull(adaptedFittingItem(configured,section,outlet)) {"Connect matching round or rectangular profile families"}
            if(definitions.none {it.id==item.id})definitions+=item
            val definition=item.fitting!!
            val d=unit(direction)
            val inward=Point3(-definition.ports.first().direction.x,-definition.ports.first().direction.y,-definition.ports.first().direction.z)
            fun cross(a:Point3,b:Point3)=Point3(a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x)
            fun perpendicular(v:Point3,axis:Point3):Point3 {val dot=v.x*axis.x+v.y*axis.y+v.z*axis.z;return minus(v,Point3(axis.x*dot,axis.y*dot,axis.z*dot))}
            fun away(e:Element):Point3 {val r=e.body as ElementBody.Route;val t=r.routing!!;return unit(minus(measurementToWorld(e.transform,if(t.startNode==node)r.path.last()else r.path.first()),point))}
            val outgoing=incident.drop(1).map {away(it.first)}.maxByOrNull {distance3(perpendicular(it,d),Point3())}
            val localRight=definition.ports.drop(1).map {perpendicular(it.direction,inward)}.firstOrNull {distance3(it,Point3())>EPS}?.let(::unit)
                ?: unit(cross(if(abs(inward.z)<.9)Point3(z=1.0)else Point3(y=1.0),inward))
            val worldRight=outgoing?.let {perpendicular(it,d)}?.takeIf {distance3(it,Point3())>EPS}?.let(::unit)
                ?: unit(cross(if(abs(d.z)<.9)Point3(z=1.0)else Point3(y=1.0),d))
            val rotation=matrixRotation(basisRotation(inward,localRight,cross(localRight,inward),d,worldRight,cross(worldRight,d)))
            val input=definition.ports.first().position
            val output=definition.ports.getOrNull(1)
            val corner=when {
                kind==FittingKind.TEE || kind==FittingKind.CROSS || kind==FittingKind.REDUCER -> output?.let {lerp(input,it.position,.5)} ?: input
                kind in setOf(FittingKind.ELBOW_45,FittingKind.ELBOW_90,FittingKind.BEND_90_VERTICAL_INSIDE,FittingKind.BEND_90_VERTICAL_OUTSIDE) && output!=null -> {
                    // Intersect the incoming and outgoing tangents to retain the logical CAD vertex.
                    val v=minus(output.position,input);val out=output.direction
                    val dot=inward.x*out.x+inward.y*out.y+inward.z*out.z
                    val denom=1-dot*dot
                    val a=if(abs(denom)>EPS)((v.x*inward.x+v.y*inward.y+v.z*inward.z)-(v.x*out.x+v.y*out.y+v.z*out.z)*dot)/denom else 0.0
                    Point3(input.x+inward.x*a,input.y+inward.y*a,input.z+inward.z*a)
                }
                else->input
            }
            val offset=measurementToWorld(Transform3(rotation=rotation),corner)
            val transform=Transform3(minus(point,offset),rotation)
            for((e,_) in incident) {
                val away=away(e)
                val port=definition.ports.maxByOrNull {p->val v=minus(measurementToWorld(Transform3(rotation=rotation),p.direction),Point3());v.x*away.x+v.y*away.y+v.z*away.z} ?: continue
                val endpoint=measurementToWorld(transform,port.position)
                // Short sections keep the logical end rather than reversing their physical body.
                if(distance3(endpoint,point)<routeLength(e)*.45)trimmed[e.id to node]=endpoint
            }
            val prior=after.elements.firstOrNull {it.id==id} ?: old[id]
            generated+=Element(id,doc.project.id,owner.layerId,ElementBody.Component(item.id,item.defaultSize,item.defaultParameters(),HostAttachment(owner.id,HostMode.SURFACE,measurementToLocal(owner.transform,point))),
                owner.levelId,owner.systemId,kind.name.replace('_',' '),transform,properties=(prior?.properties ?: emptyMap())+mapOf(ROUTING_OWNER to ParameterValue.Text(owner.id.value),ROUTING_NODE to ParameterValue.Text(node),ELEMENT_COLOUR_KEY to (owner.properties[ELEMENT_COLOUR_KEY] ?: ParameterValue.Colour(t.settings.colourArgb))))
        }
        val nodes=linkedMapOf<String,MutableList<Pair<Element,Point3>>>()
        for(e in routes)for((id,p) in sectionNodes(e))nodes.getOrPut(id){mutableListOf()}.add(e to p)
        // A diagonal dogleg between parallel horizontal sections is one sized offset assembly.
        val offsetNodes=mutableSetOf<String>()
        val offsetSections=mutableSetOf<ElementId>()
        for(e in routes) {
            val b=e.body as? ElementBody.Route ?: continue;val t=b.routing ?: continue
            if(b.wallService!=null || !t.settings.autoFittings || t.settings.bendDegrees!=45)continue
            val a=measurementToWorld(e.transform,b.path.first());val z=measurementToWorld(e.transform,b.path.last())
            if(abs(a.z-z.z)>EPS)continue
            val left=nodes[t.startNode].orEmpty().singleOrNull {it.first.id!=e.id}?.first ?: continue
            val right=nodes[t.endNode].orEmpty().singleOrNull {it.first.id!=e.id}?.first ?: continue
            if(nodes[t.startNode]?.size!=2 || nodes[t.endNode]?.size!=2 || (left.body as ElementBody.Route).section!=b.section || (right.body as ElementBody.Route).section!=b.section)continue
            fun other(section:Element,node:String):Point3 {val route=section.body as ElementBody.Route;return measurementToWorld(section.transform,if(route.routing!!.startNode==node)route.path.last()else route.path.first())}
            val incoming=unit(minus(a,other(left,t.startNode)));val outgoing=unit(minus(other(right,t.endNode),z))
            if(abs(incoming.z)>EPS || distance3(incoming,outgoing)>EPS)continue
            val rotation=Rotation3(z=atan2(-incoming.x,incoming.y))
            val delta=measurementToLocal(Transform3(rotation=rotation),minus(z,a))
            if(delta.y<=EPS || abs(abs(delta.x)-delta.y)>EPS)continue
            if(delta.y>minOf(routeLength(left),routeLength(right))*.4)continue
            if(t.startNode in t.suppressedFittings || t.endNode in t.suppressedFittings || t.startNode in t.fittingOverrides || t.endNode in t.fittingOverrides)continue
            val input=Point3(a.x-incoming.x*delta.y,a.y-incoming.y*delta.y,a.z)
            fitting(e,t.startNode,input,FittingKind.OFFSET,b.section,direction=incoming,offset=delta.x to delta.y*3)
            val instance=generated.lastOrNull {it.id.value=="routing:${t.startNode}"} ?: continue
            val def=definitions.first {it.id==(instance.body as ElementBody.Component).libraryItemId}.fitting!!
            trimmed[left.id to t.startNode]=measurementToWorld(instance.transform,def.ports.first().position)
            trimmed[right.id to t.endNode]=measurementToWorld(instance.transform,def.ports[1].position)
            offsetNodes+=t.startNode;offsetNodes+=t.endNode;offsetSections+=e.id
        }
        for((node,ends) in nodes) {
            if(node in offsetNodes)continue
            require(ends.size<=4){"Use a separate junction for more than four branches"}
            val owner=ends.first().first;val body=owner.body as ElementBody.Route;val topology=body.routing!!
            if(!ends.any {((it.first.body as ElementBody.Route).routing!!.settings.autoFittings)} || node in topology.suppressedFittings)continue
            val point=ends.first().second
            val directions=ends.map {(e,_)->val b=e.body as ElementBody.Route;val t=b.routing!!;val other=if(t.startNode==node)b.path.last()else b.path.first();unit(minus(measurementToWorld(e.transform,other),point))}
            val outlet=(ends.getOrNull(1)?.first?.body as? ElementBody.Route)?.section
            val d=directions.first()
            val kind=when {
                ends.size>=4->FittingKind.CROSS
                ends.size==3->FittingKind.TEE
                ends.size==1->FittingKind.END_CAP
                outlet!=body.section && directions.size==2 && abs(d.x*directions[1].x+d.y*directions[1].y+d.z*directions[1].z+1)<EPS->FittingKind.REDUCER
                body.wallService==null && (abs(d.z)> .5 || abs(directions[1].z)>.5) -> if((if(abs(d.z)>.5)d.z else directions[1].z)>0)FittingKind.BEND_90_VERTICAL_INSIDE else FittingKind.BEND_90_VERTICAL_OUTSIDE
                abs(d.x*directions[1].x+d.y*directions[1].y+d.z*directions[1].z+1)<EPS->null
                abs(d.x*directions[1].x+d.y*directions[1].y+d.z*directions[1].z)>.1->FittingKind.ELBOW_45
                else->FittingKind.ELBOW_90
            }
            val override=ends.firstNotNullOfOrNull {((it.first.body as ElementBody.Route).routing!!).fittingOverrides[node]}
            val selectedKind=override ?: kind
            selectedKind?.let {fitting(owner,node,point,it,body.section,outlet?.takeIf {it!=body.section && it::class==body.section::class && selectedKind==FittingKind.REDUCER},Point3(-d.x,-d.y,-d.z),incident=ends,
                bendAngle=if(ends.size==2 && override==null)acos((-(d.x*directions[1].x+d.y*directions[1].y+d.z*directions[1].z)).coerceIn(-1.0,1.0))else null)}
            // A branch or a turning section with another size needs a separate reducer as well.
            if(selectedKind!=null && selectedKind!=FittingKind.REDUCER)for((index,end) in ends.withIndex()) {
                val e=end.first;val branch=e.body as ElementBody.Route
                if(branch.section==body.section)continue
                val away=directions[index]
                val teePort=trimmed[e.id to node] ?: point
                val base=BuiltInLibrary.items.first {it.fitting?.let {f->f.routeKind==branch.kind && f.kind==FittingKind.REDUCER && (f.section is RouteSection.Circular)==(body.section is RouteSection.Circular)}==true}
                val reducer=requireNotNull(adaptedFittingItem(base,body.section,branch.section)) {"Connect matching round or rectangular profile families"}
                val ports=reducer.fitting!!.ports
                val length=distance3(ports.first().position,ports[1].position)
                val midpoint=Point3(teePort.x+away.x*length/2,teePort.y+away.y*length/2,teePort.z+away.z*length/2)
                fitting(e,"$node:reducer:${e.id.value}",midpoint,FittingKind.REDUCER,body.section,branch.section,away)
                val adapter=generated.lastOrNull {it.id.value=="routing:$node:reducer:${e.id.value}"}
                if(adapter!=null) {
                    val def=definitions.first {it.id==(adapter.body as ElementBody.Component).libraryItemId}.fitting!!
                    trimmed[e.id to node]=measurementToWorld(adapter.transform,def.ports[1].position)
                }
            }
        }
        for(e in routes) {
            val b=e.body as? ElementBody.Route ?: continue;val t=b.routing ?: continue;val spacing=t.supportSpacing ?: continue
            val path=b.path.map {measurementToWorld(e.transform,it)};val length=routeLength(e)
            require(length/spacing<=2000){"Use wider support spacing (maximum 2000 per section)"}
            var at=spacing;var index=1
            while(at<length-EPS) {
                var remaining=at
                for((a,z) in path.zipWithNext()) {val len=distance3(a,z);if(remaining<=len) {fitting(e,"support:${e.id.value}",lerp(a,z,remaining/len),t.supportKind,b.section,direction=minus(z,a),suffix=":${index++}");break};remaining-=len}
                at+=spacing
            }
        }
        // Retain order of existing generated elements; sparse history forbids reordering survivors.
        val fabricated=routes.map {e->val b=e.body as? ElementBody.Route;val t=b?.routing
            if(b==null || t==null)e else {val path=b.path.toMutableList()
                trimmed[e.id to t.startNode]?.let {path[0]=measurementToLocal(e.transform,it)}
                trimmed[e.id to t.endNode]?.let {path[path.lastIndex]=measurementToLocal(e.transform,it)}
                e.copy(body=b.copy(fabricationPath=if(e.id in offsetSections)listOf(path.first(),path.first())else path))
            }
        }
        val replacements=generated.associateBy {it.id};val physical=fabricated.associateBy {it.id};val ids=physical.keys
        val ordered=after.elements.mapNotNull {e->if(e.id in ids)physical[e.id]else replacements[e.id]}+generated.filter {g->after.elements.none {it.id==g.id}}
        val referenced=ordered.mapNotNull {(it.body as? ElementBody.Component)?.libraryItemId}.toSet()+doc.libraryState.favoriteIds+doc.libraryState.recentIds
        doc=doc.copy(elements=ordered,libraryItems=definitions.filter {it.id in referenced || (it.parameters["routingGenerated"] as? ParameterValue.Flag)?.value!=true})
        return doc
    }
    fun edit(document:ProjectDocument,id:ElementId,settings:RoutingSettings,start:Point3?=null,end:Point3?=null):EditCommand {
        val element=document.elements.first {it.id==id};val body=element.body as ElementBody.Route;val topology=requireNotNull(body.routing)
        val world=body.path.map {measurementToWorld(element.transform,it)}
        val level=document.levels.find {it.id==element.levelId}
        val destination=if(settings.floorInstallation!=null)document.layers.firstOrNull {it.name=="Underfloor services"} ?: Layer(LayerId(UUID.randomUUID().toString()),document.project.id,"Underfloor services")else
            document.layers.firstOrNull {it.id==element.layerId && it.name!="Underfloor services"} ?: document.layers.firstOrNull {it.name!="Underfloor services" && it.visible && !it.locked} ?: error("Choose a visible unlocked layer")
        require(destination.visible && !destination.locked){"Unlock and show the routing layer"}
        val dz=floorRoutingElevation(document,settings,level?.id,world.first())-floorRoutingElevation(document,topology.settings,level?.id,world.first())
        val path=world.map {it.copy(z=it.z+dz)}.toMutableList();start?.let {path[0]=it};end?.let {path[path.lastIndex]=it}
        val system=document.systems.find {it.name==settings.system} ?: BuildingSystem(SystemId(UUID.randomUUID().toString()),document.project.id,settings.system,discipline(settings.system),settings.colourArgb)
        var next=element.copy(layerId=destination.id,body=body.copy(path=path.map {measurementToLocal(element.transform,it)},section=settings.section(body.kind),elevation=if(settings.floorInstallation!=null)ElevationSetting(-settings.floorDepth,ElevationReference.ABOVE_FINISHED_FLOOR)else settings.elevation,wallService=body.wallService?.takeIf {settings.wallOptions!=null},host=if(body.wallService!=null && settings.wallOptions==null)null else body.host,routing=topology.copy(settings=settings,startAnchor=if(start!=null)null else topology.startAnchor,endAnchor=if(end!=null)null else topology.endAnchor)),systemId=system.id,properties=element.properties+(ELEMENT_COLOUR_KEY to ParameterValue.Colour(settings.colourArgb)))
        if(settings.wallOptions!=null)next=WallServices.attach(document,next,settings.wallOptions!!)
        val after=document.copy(layers=if(document.layers.any {it.id==destination.id})document.layers else document.layers+destination,elements=document.elements.map {if(it.id==id)next else it},systems=if(document.systems.any {it.id==system.id})document.systems else document.systems+system)
        val result=reconcile(document,after)
        FloorServices.validateCuts(document,result)
        return EditCommands.between(document,result,"Edit connected route")
    }
    fun fitting(document:ProjectDocument,id:ElementId,kind:FittingKind?):EditCommand {
        val element=document.elements.first {it.id==id};val owner=(element.properties[ROUTING_OWNER] as? ParameterValue.Text)?.value ?: error("Select an automatic fitting")
        val node=(element.properties[ROUTING_NODE] as ParameterValue.Text).value
        val after=document.copy(elements=document.elements.map {e->val b=e.body as? ElementBody.Route;val t=b?.routing
            if(b==null || t==null || e.id.value!=owner && t.startNode!=node && t.endNode!=node)e else e.copy(body=b.copy(routing=if(kind==null)t.copy(suppressedFittings=t.suppressedFittings+node+id.value)else t.copy(fittingOverrides=t.fittingOverrides+(node to kind),suppressedFittings=t.suppressedFittings-node-id.value)))})
        return EditCommands.between(document,reconcile(document,after),if(kind==null)"Remove fitting"else "Replace fitting")
    }
    fun supports(document:ProjectDocument,id:ElementId,spacing:Double,kind:FittingKind=FittingKind.SUPPORT):EditCommand {
        require(kind in setOf(FittingKind.SUPPORT,FittingKind.HANGER,FittingKind.BRACKET,FittingKind.COUPLER,FittingKind.EXPANSION_JOINT))
        val after=document.copy(elements=document.elements.map {e->if(e.id!=id)e else {val b=e.body as ElementBody.Route;e.copy(body=b.copy(routing=requireNotNull(b.routing).copy(supportSpacing=spacing,supportKind=kind)))}})
        return EditCommands.between(document,reconcile(document,after),"Add route accessories")
    }
    private fun discipline(system:String)=when(system){"ICT"->Discipline.ICT;"HVAC"->Discipline.HVAC;"Plumbing"->Discipline.PLUMBING;"Fire"->Discipline.FIRE_PROTECTION;else->Discipline.ELECTRICAL}
}
