package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import kotlin.math.pow

enum class ModelColourBy { MATERIAL, SYSTEM, LEVEL }

/** Portable, named matte material. ARGB stays sRGB; glTF factors are converted to linear RGB. */
data class ExportMaterial(val name:String,val colourArgb:Long,val roughness:Double=.9,val metallic:Double=0.0)

internal fun exportMaterial(document:ProjectDocument,element:Element,options:ModelExportOptions,display:ObjectDisplay):ExportMaterial {
    val definition=(element.body as? ElementBody.Component)?.let {body->document.libraryItems.find {it.id==body.libraryItemId}}
    val material=(element.materialId ?: definition?.materialId)?.let {id->document.materials.find {it.id==id}}
    val layer=document.layers.find {it.id==element.layerId}
    val system=document.systems.find {it.id==element.systemId}
    val source=renderColour(element,layer,system,material,definition)
    val role=renderMaterial(element,definition).name.lowercase().replaceFirstChar {it.uppercase()}
    val sourceName=when {
        element.colourOverride!=null || (element.body as? ElementBody.Component)?.parameters?.get(LibraryParameterKeys.COLOUR) is ParameterValue.Colour->element.name.ifBlank {role}+" colour"
        material!=null->material.name
        system!=null->system.name
        layer!=null && layer.colourArgb!=0xFF333333L->layer.name
        else->"$role matte"
    }
    val level=document.levels.find {it.id==element.levelId}
    val palette=listOf(0xFF518BB8L,0xFFBF8956L,0xFF6D9D77L,0xFF9B7BB5L,0xFFBC707BL,0xFF509E9BL)
    val (name,colour)=when(options.colourBy) {
        ModelColourBy.MATERIAL->sourceName to source
        ModelColourBy.SYSTEM->if(system!=null)system.name to system.colourArgb else sourceName to source
        ModelColourBy.LEVEL->if(level!=null)"Level: ${level.name}" to palette[Math.floorMod(level.id.value.hashCode(),palette.size)] else "Project datum" to MattePalette.OTHER
    }
    // Colour modes replace RGB only: an intentional transparent item must never turn opaque.
    val opacity=minOf(alpha(source),alpha(colour))
    val xray=options.exportDisplayMode && (display==ObjectDisplay.XRAY || document.project.settings.viewMode in listOf(ViewMode.XRAY,ViewMode.TRANSPARENT))
    val result=(colour and 0xFFFFFFL) or ((kotlin.math.round(opacity*(if(xray).28 else 1.0)*255).toLong().coerceIn(0,255)) shl 24)
    return ExportMaterial(name.ifBlank {"$role matte"}+if(xray)" (X-ray)" else "",result)
}

internal fun linearRgb(colour:Long)=rgb(colour).map {if(it<=.04045)it/12.92 else ((it+.055)/1.055).pow(2.4)}
internal fun alpha(colour:Long)=(colour ushr 24 and 255)/255.0
