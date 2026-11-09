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
enum class RenderMaterial { WALL, FLOOR, CEILING, EQUIPMENT, OTHER, CONTACT_SHADOW }
object MattePalette {
    const val WALL=0xFFD0C7B8L
    const val FLOOR=0xFF788997L
    const val CEILING=0xFFE5E8ECL
    const val EQUIPMENT=0xFF416681L
    const val OTHER=0xFF6688AAL
    fun colour(role:RenderMaterial):Long=when(role) {
        RenderMaterial.WALL->WALL;RenderMaterial.FLOOR->FLOOR;RenderMaterial.CEILING->CEILING
        RenderMaterial.EQUIPMENT->EQUIPMENT;RenderMaterial.OTHER->OTHER;RenderMaterial.CONTACT_SHADOW->0xFF000000L
    }
}
fun renderMaterial(element:Element):RenderMaterial=when {
    element.isCeiling->RenderMaterial.CEILING
    element.body is ElementBody.Wall->RenderMaterial.WALL
    element.body is ElementBody.Room || element.body is ElementBody.Slab->RenderMaterial.FLOOR
    element.body is ElementBody.Component || element.body is ElementBody.Route->RenderMaterial.EQUIPMENT
    else->RenderMaterial.OTHER
}
fun renderColour(element:Element,layer:Layer?,system:BuildingSystem?,material:Material?):Long {
    element.colourOverride?.let {return it}
    system?.let {return it.colourArgb}
    material?.let {return it.colourArgb}
    val role=renderMaterial(element)
    if(layer!=null && (layer.colourArgb!=0xFF333333L || role==RenderMaterial.OTHER))return layer.colourArgb
    return MattePalette.colour(role)
}
