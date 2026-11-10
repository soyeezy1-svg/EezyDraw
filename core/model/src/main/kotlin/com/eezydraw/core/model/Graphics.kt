package com.eezydraw.core.model

import kotlinx.serialization.Serializable

@Serializable enum class LightingStrength { SOFT, NORMAL, STRONG }
@Serializable enum class TextureDetail { LOW, MEDIUM, HIGH }
@Serializable data class GraphicsOptions(
    val antiAliasing:Boolean=true,
    val lighting:LightingStrength=LightingStrength.NORMAL,
    val textureDetail:TextureDetail=TextureDetail.MEDIUM,
    val contactShadows:Boolean=true,
    val automaticQuality:Boolean=true,
    val showFrameRate:Boolean=false,
)
/** Stable semantic defaults; explicit object/system/material and custom layer colours win. */
enum class RenderMaterial { WALL, FLOOR, SLAB, CEILING, DOOR, WINDOW, EQUIPMENT, ROUTE, FITTING, OTHER, CONTACT_SHADOW }
object MattePalette {
    const val WALL=0xFFD0C7B8L
    const val FLOOR=0xFF788997L
    const val CEILING=0xFFE5E8ECL
    const val EQUIPMENT=0xFF416681L
    const val SLAB=0xFF8D8095L
    const val DOOR=0xFFAC784BL
    const val WINDOW=0xFF78AEBCL
    const val ROUTE=0xFFBD9054L
    const val FITTING=0xFF69927CL
    const val OTHER=0xFF6688AAL
    fun colour(role:RenderMaterial):Long=when(role) {
        RenderMaterial.WALL->WALL;RenderMaterial.FLOOR->FLOOR;RenderMaterial.CEILING->CEILING
        RenderMaterial.SLAB->SLAB;RenderMaterial.DOOR->DOOR;RenderMaterial.WINDOW->WINDOW
        RenderMaterial.ROUTE->ROUTE;RenderMaterial.FITTING->FITTING;RenderMaterial.EQUIPMENT->EQUIPMENT;RenderMaterial.OTHER->OTHER;RenderMaterial.CONTACT_SHADOW->0xFF000000L
    }
}
fun renderMaterial(element:Element,definition:LibraryItem?=null):RenderMaterial=when {
    element.isCeiling->RenderMaterial.CEILING
    element.body is ElementBody.Wall->RenderMaterial.WALL
    element.body is ElementBody.Room->RenderMaterial.FLOOR
    element.body is ElementBody.Slab->RenderMaterial.SLAB
    definition?.fitting!=null->RenderMaterial.FITTING
    definition?.opening in listOf(OpeningKind.DOOR,OpeningKind.DOUBLE_DOOR,OpeningKind.SLIDING_DOOR) || definition?.discipline==Discipline.ARCHITECTURE && Regex("\\bdoor\\b",RegexOption.IGNORE_CASE).containsMatchIn(definition.name)->RenderMaterial.DOOR
    definition?.opening in listOf(OpeningKind.WINDOW,OpeningKind.DOUBLE_WINDOW) || definition?.discipline==Discipline.ARCHITECTURE && Regex("\\bwindow\\b",RegexOption.IGNORE_CASE).containsMatchIn(definition.name)->RenderMaterial.WINDOW
    element.body is ElementBody.Route->RenderMaterial.ROUTE
    element.body is ElementBody.Component->RenderMaterial.EQUIPMENT
    else->RenderMaterial.OTHER
}
fun renderColour(element:Element,layer:Layer?,system:BuildingSystem?,material:Material?,definition:LibraryItem?=null):Long {
    element.colourOverride?.let {return it}
    ((element.body as? ElementBody.Component)?.parameters?.get(LibraryParameterKeys.COLOUR) as? ParameterValue.Colour)?.let {return it.argb}
    material?.let {return it.colourArgb}
    system?.let {return it.colourArgb}
    val role=renderMaterial(element,definition)
    if(layer!=null && (layer.colourArgb!=0xFF333333L || role==RenderMaterial.OTHER))return layer.colourArgb
    return MattePalette.colour(role)
}
