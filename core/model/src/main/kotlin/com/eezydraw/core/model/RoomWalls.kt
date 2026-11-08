package com.eezydraw.core.model
import kotlinx.serialization.Serializable
@Serializable enum class RoomWallAlignment { CENTERLINE, INSIDE_FACE, OUTSIDE_FACE }
@Serializable data class RoomWallOptions(val thickness:Double=.2,val height:Double=3.0,val material:String="Concrete",val alignment:RoomWallAlignment=RoomWallAlignment.CENTERLINE) {
 init {require(thickness.isFinite() && thickness>0 && height.isFinite() && height>0);require(material.isNotBlank())}
}
