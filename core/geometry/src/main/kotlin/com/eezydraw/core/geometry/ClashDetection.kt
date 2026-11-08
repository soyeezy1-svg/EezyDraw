package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import java.security.MessageDigest
import kotlin.math.*

data class ClashRun(val clashes:List<Clash>,val checkedObjects:Int,val candidatePairs:Long,val checkKey:String,val options:ClashCheckOptions=ClashCheckOptions())

object ClashDetection {
    fun run(document:ProjectDocument,options:ClashCheckOptions,progress:(Float)->Unit={},cancel:()->Unit={}):ClashRun {
        cancel();progress(0f)
        val presentation=ScenePresentation(document,planView=options.planView)
        val elements=document.elements.filter {e->e.body !is ElementBody.Room &&
            (options.levelId==null || e.levelId==options.levelId || e.levelId==null) &&
            (!options.visibleOnly || presentation.display(e)!=ObjectDisplay.HIDDEN) &&
            (options.first.matches(document,e)||options.second.matches(document,e))}
        if(elements.isEmpty())return ClashRun(emptyList(),0,0,options.key(),options)
        val physical=unfilteredGeometryDocument(document).let {d->d.copy(project=d.project.copy(settings=d.project.settings.copy(
            viewMode=ViewMode.XRAY,wallServices=d.project.settings.wallServices.copy(showCuts=true),floorServices=d.project.settings.floorServices.copy(showChannels=true))))}
        val scene=buildSurfaceScene(physical,elements.mapTo(hashSetOf()){it.id},cancel)
        require(scene.chunks.sumOf {it.vertices.size.toLong()/30}<=200_000) {"Clash geometry exceeds 200,000 triangles. Check individual levels or systems."}
        data class Solid(val element:Element,val mesh:ClashMesh,val clearance:Double) {
            val signature by lazy {
                val type=when(val b=element.body){is ElementBody.Component->"component:${b.libraryItemId.value}";is ElementBody.Route->"route:${b.kind}";is ElementBody.DrawingShape->"shape:${b.shape::class.simpleName}";else->element.body::class.simpleName}
                type+mesh.vertices.map {v->listOf(v.x,v.y,v.z).joinToString(","){(it*1e6).roundToLong().toString()}}.sorted().joinToString(";")
            }
        }
        val byId=elements.associateBy {it.id.value};val library=document.libraryItems.associateBy {it.id}
        val solids=scene.chunks.groupBy {it.elementId}.mapNotNull { (id,chunks)->
            cancel()
            val vertices=chunks.flatMap {c->(c.vertices.indices step 10).map {i->CV(c.origin.x+c.vertices[i],c.origin.y+c.vertices[i+1],c.origin.z+c.vertices[i+2])}}
            val triangles=vertices.chunked(3).map {CT(it[0],it[1],it[2])}.filter {(it.b-it.a).cross(it.c-it.a).length()>1e-15}
            if(triangles.isEmpty())return@mapNotNull null
            val centre=bounds(vertices).centre()
            if(kotlin.math.abs(triangles.sumOf {t->(t.a-centre).dot((t.b-centre).cross(t.c-centre))})/6 < 1e-15)return@mapNotNull null
            val mesh=ClashMesh(triangles,cancel)
            val e=byId.getValue(id);val component=e.body as? ElementBody.Component
            val clearance=options.systemClearances[e.systemId] ?: if(component?.let {library[it.libraryItemId]?.symbol}==LibrarySymbol.PANEL)options.panelClearance else options.clearance
            Solid(e,mesh,clearance)
        }
        progress(.2f)
        if(solids.isEmpty())return ClashRun(emptyList(),0,0,options.key(),options)
        val tree=ClashTree(solids){it.mesh.box};val maxClearance=solids.maxOf {it.clearance}
        val results=mutableListOf<Clash>();var pairs=0L
        fun owner(e:Element)=(e.properties["routingOwner"] as? ParameterValue.Text)?.value
        fun connected(a:Element,b:Element):Boolean {
            if(owner(a)==b.id.value || owner(b)==a.id.value)return true
            val node=(a.properties["routingNode"] as? ParameterValue.Text)?.value
            if(node!=null && node==(b.properties["routingNode"] as? ParameterValue.Text)?.value)return true
            val x=(a.body as? ElementBody.Route)?.routing;val y=(b.body as? ElementBody.Route)?.routing
            return x!=null && y!=null && x.networkId==y.networkId && listOf(x.startNode,x.endNode).any {it==y.startNode || it==y.endNode}
        }
        fun hosted(a:Element,b:Element):Boolean {
            val host=when(val body=a.body){is ElementBody.Component->body.host;is ElementBody.Route->body.host;else->null}
            return host?.hostElementId==b.id || a.wallService()?.wallId==b.id ||
                (b.body is ElementBody.Slab && a.floorRouting()!=null && floorServiceCuts(document,b).any {it.sourceId==a.id})
        }
        for((i,a) in solids.withIndex()) {
            cancel()
            tree.query(a.mesh.box.expand(maxClearance)) {b->
                if(a.element.id.value>=b.element.id.value)return@query
                val e=a.element;val f=b.element
                if(!(options.first.matches(document,e)&&options.second.matches(document,f) || options.first.matches(document,f)&&options.second.matches(document,e)))return@query
                if(options.ignoreConnections && connected(e,f) && a.signature!=b.signature)return@query
                cancel();pairs++
                val clearance=max(a.clearance,b.clearance)
                var position=CV((max(a.mesh.box.low.x,b.mesh.box.low.x)+min(a.mesh.box.high.x,b.mesh.box.high.x))*.5,(max(a.mesh.box.low.y,b.mesh.box.low.y)+min(a.mesh.box.high.y,b.mesh.box.high.y))*.5,(max(a.mesh.box.low.z,b.mesh.box.low.z)+min(a.mesh.box.high.z,b.mesh.box.high.z))*.5)
                var overlap=0.0;var distance=0.0;var kind:ClashKind?=null
                if(a.signature==b.signature)kind=ClashKind.DUPLICATE
                else {
                    val penetration=if(a.mesh.box.overlaps(b.mesh.box))a.mesh.penetration(b.mesh) else -1.0
                    if(penetration!=null && penetration>options.tolerance+1e-8){kind=ClashKind.HARD;overlap=penetration}
                    else {
                        val contact=a.mesh.contact(b.mesh,clearance+1e-6,cancel)
                        distance=contact.distance;position=contact.position
                        val interior=if(penetration==null && a.mesh.box.overlaps(b.mesh.box))a.mesh.interiorOverlap(b.mesh,contact.intersections,options.tolerance,cancel)else null
                        if(interior!=null && interior.first>options.tolerance+1e-8){kind=ClashKind.HARD;overlap=interior.first;position=interior.second}
                        else if(interior!=null || penetration!=null && penetration>1e-8) { /* Ignore shallow hard overlaps rather than relabel them as clearance. */ }
                        else if(distance+options.tolerance<clearance && !hosted(e,f) && !hosted(f,e))kind=ClashKind.CLEARANCE
                    }
                }
                if(kind!=null){require(results.size<20_000){"More than 20,000 clashes; compare individual levels or systems"};results+=Clash(e.id,f.id,kind!!,position.point(),clearance,distance,overlap,
                    checkKey=options.key(),firstName=e.name.ifBlank {(e.body as? ElementBody.Component)?.let {library[it.libraryItemId]?.name} ?: e.body::class.simpleName.orEmpty()},secondName=f.name.ifBlank {(f.body as? ElementBody.Component)?.let {library[it.libraryItemId]?.name} ?: f.body::class.simpleName.orEmpty()},levelIds=setOfNotNull(e.levelId,f.levelId))}
            }
            if(i%16==0)progress(.2f+.8f*(i+1)/solids.size)
        }
        progress(1f);return ClashRun(results,solids.size,pairs,options.key(),options)
    }

    fun resultId(clash:Clash):IssueId {
        val key=listOf(clash.firstElementId.value,clash.secondElementId.value).sorted().joinToString("|")+"|${clash.kind}"
        return IssueId("clash-"+MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(12).joinToString(""){"%02x".format(it)})
    }
}
