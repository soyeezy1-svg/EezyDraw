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
    val mounting: MountingType = MountingType.FLOOR,
    val defaultMountingHeight: Double = 0.0,
    val symbol: LibrarySymbol = LibrarySymbol.BOX,
    val symbolGeometry: List<Shape> = emptyList(),
    val manufacturer: String = "",
    val model: String = "",
    val defaultSystem: String = "",
    val defaultMaterial: String = "",
    val defaultColourArgb: Long = 0xFF777777,
) {
    init {
        require(name.isNotBlank() && category.isNotBlank() && version > 0)
        require(defaultMountingHeight.isFinite() && defaultMountingHeight >= 0)
    }
}

/** Local +Y is the outward face of a wall-mounted item; local Z=0 is its base. */
@Serializable enum class MountingType { FLOOR, WALL, CEILING }

@Serializable enum class LibrarySymbol {
    SWITCH, DOUBLE_SWITCH, DIMMER, SOCKET, FLOOR_BOX, ISOLATOR, PANEL, LIGHT,
    EMERGENCY_LIGHT, EXIT_SIGN, JUNCTION_BOX, PULL_BOX, TRAY, TRUNKING, CONDUIT,
    EARTH, RACK, PATCH_PANEL, BASKET, DATA_OUTLET, FIBRE_BOX, WIFI, CAMERA,
    READER, INTERCOM, SPEAKER, TV_OUTLET, BOX,
}

/** Shared stable keys; numeric dimensions and heights are always metres. */
object LibraryParameterKeys {
    const val MOUNTING_HEIGHT = "mountingHeight"
    const val MATERIAL = "material"
    const val COLOUR = "colour"
    const val SYSTEM = "system"
    const val MANUFACTURER = "manufacturer"
    const val MODEL = "model"
    const val NAME_TAG = "nameTag"
}

/** Copies library defaults into each instance; subsequent instance edits never mutate a definition. */
fun LibraryItem.defaultParameters(): Map<String, ParameterValue> = mapOf(
    LibraryParameterKeys.MOUNTING_HEIGHT to ParameterValue.Number(defaultMountingHeight, Quantity.LENGTH),
    LibraryParameterKeys.MATERIAL to ParameterValue.Text(defaultMaterial),
    LibraryParameterKeys.COLOUR to ParameterValue.Colour(defaultColourArgb),
    LibraryParameterKeys.SYSTEM to ParameterValue.Text(defaultSystem),
    LibraryParameterKeys.MANUFACTURER to ParameterValue.Text(manufacturer),
    LibraryParameterKeys.MODEL to ParameterValue.Text(model),
    LibraryParameterKeys.NAME_TAG to ParameterValue.Text(name),
) + parameters

@Serializable enum class LibraryViewMode { GRID, LIST }

/** Project browsing preferences refer to both built-in and project-carried definitions by stable ID. */
@Serializable
data class LibraryState(
    val favoriteIds: Set<LibraryItemId> = emptySet(),
    val recentIds: List<LibraryItemId> = emptyList(),
    val viewMode: LibraryViewMode = LibraryViewMode.GRID,
) {
    fun toggledFavorite(id: LibraryItemId): LibraryState = copy(
        favoriteIds = if (id in favoriteIds) favoriteIds - id else favoriteIds + id,
    )

    fun recordUsed(id: LibraryItemId): LibraryState = copy(recentIds = (listOf(id) + recentIds.filterNot { it == id }).take(24))
}

/** All words must match; lowercase() uses Kotlin's invariant locale, including on Turkish devices. */
fun librarySearch(items: List<LibraryItem>, query: String): List<LibraryItem> {
    fun normalized(value: String) = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    val words = normalized(query).split(Regex("\\s+")).filter(String::isNotEmpty)
    if (words.isEmpty()) return items
    return items.filter { item ->
        val text = normalized(listOf(item.name, item.category, item.discipline.name, item.defaultSystem,
            item.manufacturer, item.model).joinToString(" "))
        val compact = text.replace(" ", "")
        words.all { it in text || it in compact }
    }
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
