package com.eezydraw.core.editing

import com.eezydraw.core.model.*
import kotlin.math.*

object FloorServices {
    fun validateCuts(before:ProjectDocument,after:ProjectDocument) {
        for(slab in after.elements.filter {it.body is ElementBody.Slab && (it.lockMode!=LockMode.NONE || after.layers.any {l->l.id==it.layerId && l.locked})}) {
            val old=before.elements.find {it.id==slab.id} ?: continue
            require(floorServiceCuts(before,old)==floorServiceCuts(after,slab)){"Unlock the floor slab before changing its channels or openings"}
        }
    }
    fun place(document:ProjectDocument,item:LibraryItem,element:Element):Element {
        val kind=item.floorService ?: return element
        val slab=floorSlab(document,element.transform.translation,element.levelId)
        require(kind!=FloorDeviceKind.SLAB_SLEEVE || slab!=null){"No slab on this level. Add a floor slab before placing a sleeve."}
        require(slab==null || slab.lockMode==LockMode.NONE && document.layers.none {it.id==slab.layerId && it.locked}){"Unlock the floor slab before cutting it"}
        val body=element.body as ElementBody.Component
        val height=if(kind==FloorDeviceKind.SLAB_SLEEVE)(slab!!.body as ElementBody.Slab).thickness*abs(slab.transform.scale.z) else body.size.height
        val top=if(kind==FloorDeviceKind.SLAB_SLEEVE)slab!!.transform.translation.z else document.levels.find {it.id==element.levelId}?.elevation ?: 0.0
        val position=element.transform.translation.copy(z=top-height)
        return element.copy(transform=element.transform.copy(translation=position),body=body.copy(size=body.size.copy(height=height),host=slab?.let {HostAttachment(it.id,if(kind==FloorDeviceKind.SLAB_SLEEVE)HostMode.THROUGH_OPENING else HostMode.RECESSED,measurementToLocal(it.transform,position),height)}))
    }
    fun rise(document:ProjectDocument,id:ElementId,targetId:ElementId):EditCommand {
        val source=document.elements.first {it.id==id};val route=source.body as ElementBody.Route
        require(source.lockMode==LockMode.NONE && document.layers.none {it.id==source.layerId && it.locked}){"Unlock the floor route before connecting it"}
        val config=requireNotNull(source.floorRouting()){"Select an underfloor route"}
        val target=document.elements.first {it.id==targetId};val b=target.body as ElementBody.Component
        require(target.lockMode==LockMode.NONE && document.layers.none {it.id==target.layerId && it.locked} && com.eezydraw.core.geometry.ScenePresentation(document,planView=true).display(target)!=ObjectDisplay.HIDDEN){"Unlock the connection target"}
        val item=document.libraryItems.first {it.id==b.libraryItemId}
        val local=if(item.mounting==MountingType.WALL)Point3(0.0,b.size.depth/2,b.size.height/2)else Point3(z=b.size.height)
        val end=measurementToWorld(target.transform,local)
        val start=route.path.map {measurementToWorld(source.transform,it)}.let {listOf(it.first(),it.last())}.minBy {hypot(it.x-end.x,it.y-end.y)}
        val floor=Point3(end.x,end.y,start.z)
        val points=listOf(start,floor,end).distinct().map {RoutePick(it,config,source.levelId)}
        require(points.size>=2){"The route is already connected"}
        return ServiceRouting.create(document,route.kind,points)
    }
    fun reconcile(before:ProjectDocument,after:ProjectDocument):ProjectDocument=after.copy(elements=after.elements.map {e->
        val body=e.body as? ElementBody.Component ?: return@map e
        val host=body.host?.hostElementId?.let {id->after.elements.find {it.id==id && it.body is ElementBody.Slab}} ?: return@map e
        val old=before.elements.find {it.id==e.id};val oldHost=before.elements.find {it.id==host.id}
        if(old==e && oldHost!=null && oldHost.transform!=host.transform)e.copy(transform=e.transform.copy(translation=measurementToWorld(host.transform,measurementToLocal(oldHost.transform,e.transform.translation))))else e
    })
}
