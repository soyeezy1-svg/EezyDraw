package com.eezydraw.core.geometry

import com.eezydraw.core.model.*

/** Render-only copy. Reveal the pair for all-object checks; source visibility is never edited. */
fun clashInspection(document:ProjectDocument,clash:Clash?,xray:Boolean):ProjectDocument {
    if(clash==null)return document
    val ids=setOf(clash.firstElementId,clash.secondElementId)
    val visibility=ScenePresentation(document,planView=true)
    val pair=document.elements.filter {it.id in ids}
    val layers=pair.map {it.layerId}.toSet();val systems=pair.mapNotNull {it.systemId}.toSet();val levels=pair.mapNotNull {it.levelId}.toSet()
    return document.copy(project=document.project.copy(settings=document.project.settings.copy(
        hiddenLevelIds=document.project.settings.hiddenLevelIds-levels,viewMode=if(xray)ViewMode.XRAY else ViewMode.SHADED_WITH_EDGES,wallServices=document.project.settings.wallServices.copy(showCuts=true),floorServices=document.project.settings.floorServices.copy(showServices=true,showChannels=true))),
        layers=document.layers.map {if(it.id in layers)it.copy(visible=true)else it},
        systems=document.systems.map {if(it.id in systems)it.copy(visible=true)else it},
        elements=document.elements.map {e->
            if(e.id !in ids && visibility.display(e)==ObjectDisplay.HIDDEN)return@map e.withDisplay(ObjectDisplay.HIDDEN)
            val color=when(e.id){clash.firstElementId->0xFFFF3030L;clash.secondElementId->0xFF00B5FFL;else->0x30808080L}
            val next=e.copy(properties=e.properties+(ELEMENT_COLOUR_KEY to ParameterValue.Colour(color)))
            if(e.id in ids)next.copy(properties=next.properties.filterKeys {!it.startsWith(ROOM_SIDE_DISPLAY_PREFIX)}+("eezydraw.clashInspection" to ParameterValue.Flag(true))).withDisplay(if(xray)ObjectDisplay.XRAY else ObjectDisplay.NORMAL)
            else if(xray && e.objectDisplay!=ObjectDisplay.HIDDEN)next.withDisplay(ObjectDisplay.XRAY)else next
        })
}
