package com.eezydraw.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LibraryItem(
    val id: LibraryItemId,
    val name: String,
    val discipline: Discipline,
    val category: String,
    val defaultSize: Size3,
    val parameters: Map<String, ParameterValue> = emptyMap(),
    val geometry: List<Shape> = emptyList(),
    val materialId: MaterialId? = null,
    val builtIn: Boolean = false,
    val version: Int = 1,
) {
    init { require(name.isNotBlank() && category.isNotBlank() && version > 0) }
}

/** Parameter keys are stable library-defined names. Dimensional numeric values use SI units. */
@Serializable
sealed interface ParameterValue {
    @Serializable @SerialName("number")
    data class Number(val value: Double, val quantity: Quantity = Quantity.SCALAR) : ParameterValue {
        init { require(value.isFinite()) }
    }
    @Serializable @SerialName("text") data class Text(val value: String) : ParameterValue
    @Serializable @SerialName("flag") data class Flag(val value: Boolean) : ParameterValue
    @Serializable @SerialName("colour") data class Colour(val argb: Long) : ParameterValue
}

@Serializable enum class Quantity { SCALAR, LENGTH, AREA, VOLUME, ANGLE }
@Serializable enum class RouteKind { TRUNKING, CABLE_TRAY, CABLE_LADDER, CABLE_BASKET, CONDUIT, DUCT, PIPE }
@Serializable enum class ElevationReference { ABOVE_FINISHED_FLOOR, ABOVE_CEILING, PROJECT_DATUM }

@Serializable
data class ElevationSetting(val value: Double, val reference: ElevationReference) {
    init { require(value.isFinite()) }
}

@Serializable
sealed interface RouteSection {
    @Serializable @SerialName("circular")
    data class Circular(val diameter: Double) : RouteSection {
        init { require(diameter.isFinite() && diameter > 0) }
    }
    @Serializable @SerialName("rectangular")
    data class Rectangular(val width: Double, val height: Double) : RouteSection {
        init { require(width.isFinite() && width > 0 && height.isFinite() && height > 0) }
    }
}

@Serializable enum class FittingKind {
    ELBOW_45, ELBOW_90, TEE, CROSS, REDUCER, RISER_UP, RISER_DOWN, FLEXIBLE_CONNECTOR,
    COUPLER, END_CAP, BEND, OFFSET, SADDLE, SUPPORT, HANGER, BRACKET, EXPANSION_JOINT,
}

@Serializable
data class RouteFitting(
    val pathVertexIndex: Int,
    val kind: FittingKind,
    val libraryItemId: LibraryItemId? = null,
    val parameters: Map<String, ParameterValue> = emptyMap(),
) {
    init { require(pathVertexIndex >= 0) }
}
