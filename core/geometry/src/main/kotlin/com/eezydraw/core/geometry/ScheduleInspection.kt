package com.eezydraw.core.geometry

import com.eezydraw.core.model.*

/** Reveal an explicitly selected schedule row without changing any saved visibility or locks. */
fun scheduleInspection(document:ProjectDocument,ids:Set<ElementId>):ProjectDocument {
    if(ids.isEmpty())return document
    return document.copy(elements=document.elements.map {element->if(element.id !in ids)element else
        element.withDisplay(ObjectDisplay.NORMAL).copy(properties=element.properties-ELEMENT_DISPLAY_KEY+("eezydraw.clashInspection" to ParameterValue.Flag(true)))})
}
