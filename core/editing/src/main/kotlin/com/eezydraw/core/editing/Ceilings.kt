package com.eezydraw.core.editing

import com.eezydraw.core.model.*
import java.util.UUID
import kotlin.math.abs

object Ceilings {
    fun setRoom(document:ProjectDocument,id:ElementId,options:CeilingOptions?):EditCommand {
        val room=document.elements.first {it.id==id};val body=room.body as ElementBody.Room
        val after=document.copy(elements=document.elements.map {if(it.id==id)it.copy(body=body.copy(ceiling=options))else it})
        return EditCommands.between(document,reconcile(document,after),"Change room ceiling")
    }
    fun setLevel(document:ProjectDocument,id:LevelId,options:CeilingOptions):EditCommand {
        val after=document.copy(levels=document.levels.map {if(it.id==id)it.copy(ceiling=options)else it})
        return EditCommands.between(document,reconcile(document,after),"Change level ceiling")
    }
    fun reconcile(before:ProjectDocument,after:ProjectDocument):ProjectDocument {
        val beforeCeilings=before.elements.filter {CEILING_OWNER in it.properties}
        val deletedOwners=beforeCeilings.filter {old->after.elements.none {it.id==old.id}}
            .mapNotNull {(it.properties[CEILING_OWNER] as? ParameterValue.Text)?.value}.toSet()
        val rooms=after.elements.filter {it.body is ElementBody.Room}.map {room->
            val options=roomCeiling(after,room)
            if(room.id.value in deletedOwners && options.type==CeilingType.FLAT)room.copy(body=(room.body as ElementBody.Room).copy(ceiling=options.copy(type=CeilingType.NONE)))else room
        }
        val document=after.copy(elements=after.elements.map {e->rooms.find {it.id==e.id} ?: e})
        val ceilingByRoom=document.elements.filter {CEILING_OWNER in it.properties}.associateBy {(it.properties[CEILING_OWNER] as ParameterValue.Text).value}
        val generated=rooms.mapNotNull {room->
            val options=roomCeiling(document,room)
            if(options.type==CeilingType.NONE)return@mapNotNull null
            val existing=ceilingByRoom[room.id.value]
            val previous=before.elements.find {it.id==room.id}
            val oldOptions=previous?.let {roomCeiling(before,it)}
            if(existing!=null && room.transform==previous?.transform && (room.body as ElementBody.Room).boundary==(previous?.body as? ElementBody.Room)?.boundary && options==oldOptions)return@mapNotNull existing
            val id=existing?.id ?: ElementId(UUID.nameUUIDFromBytes(("ceiling:"+room.id.value).toByteArray()).toString())
            val body=ElementBody.Slab((room.body as ElementBody.Room).boundary,options.thickness)
            val base=existing ?: Element(id,document.project.id,room.layerId,body,room.levelId,name="${room.name.ifBlank {"Room"}} ceiling")
            base.copy(body=body,levelId=room.levelId,transform=room.transform.copy(translation=measurementToWorld(room.transform,Point3(z=options.height+options.thickness))),
                properties=base.properties+(CEILING_OWNER to ParameterValue.Text(room.id.value))+(CEILING_OBJECT to ParameterValue.Flag(true)))
        }
        val generatedIds=generated.mapTo(hashSetOf()){it.id}
        val byId=generated.associateBy {it.id}
        val removed=document.elements.filter {CEILING_OWNER in it.properties && it.id !in generatedIds}.mapTo(hashSetOf()){it.id}
        val clean=if(removed.isEmpty())document else EditCommands.delete(document,removed).preview(document)
        val elements=clean.elements.mapNotNull {e->if(CEILING_OWNER in e.properties)byId[e.id]else e}+generated.filter {g->clean.elements.none {it.id==g.id}}
        val result=clean.copy(elements=elements)
        val heightsChanged=before.levels!=result.levels || rooms.any {room->
            val old=before.elements.find {it.id==room.id}?.body as? ElementBody.Room
            old?.ceiling!=(room.body as ElementBody.Room).ceiling
        }
        if(!heightsChanged)return result
        val definitions=result.libraryItems.associateBy {it.id}
        return result.copy(elements=result.elements.map {e->
            val component=e.body as? ElementBody.Component ?: return@map e
            if(definitions[component.libraryItemId]?.mounting!=MountingType.CEILING)return@map e
            val old=before.elements.find {it.id==e.id} ?: return@map e
            if(old.transform!=e.transform)return@map e
            val oldPlane=ceilingElevation(before,old.transform.translation,before.levels.find {it.id==old.levelId})
            val newLevel=result.levels.find {it.id==e.levelId}
            val plane=ceilingElevation(result,e.transform.translation,newLevel)
            if(abs(old.transform.translation.z-oldPlane)>1e-7 || abs(plane-oldPlane)<1e-8)return@map e
            e.copy(transform=e.transform.copy(translation=e.transform.translation.copy(z=plane)),
                body=component.copy(parameters=component.parameters+(LibraryParameterKeys.MOUNTING_HEIGHT to ParameterValue.Number(plane-(newLevel?.elevation ?: 0.0),Quantity.LENGTH))))
        })
    }
}
