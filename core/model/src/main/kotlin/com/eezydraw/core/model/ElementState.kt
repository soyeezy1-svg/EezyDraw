package com.eezydraw.core.model

import kotlinx.serialization.Serializable

/** Reserved per-object presentation/edit state, preserved in native archives and sparse edits. */
const val ELEMENT_LOCKED_KEY = "eezydraw.locked"
const val ELEMENT_LOCK_MODE_KEY = "eezydraw.lockMode"
const val ELEMENT_COLOUR_KEY = "eezydraw.colour"
const val ELEMENT_DISPLAY_KEY = "eezydraw.display"
const val ROOM_SIDE_DISPLAY_PREFIX = "eezydraw.roomSide."

@Serializable enum class LockMode { NONE, POSITION_ONLY, ALL }
@Serializable enum class ObjectDisplay { NORMAL, HIDDEN, XRAY, WIREFRAME }

/** A side is stable within one room boundary; null ringIndex denotes its outer ring. */
@Serializable
data class RoomSideRef(val roomId: ElementId, val edgeIndex: Int, val ringIndex: Int? = null) {
    init { require(edgeIndex >= 0 && (ringIndex == null || ringIndex >= 0)) }
}

val Element.lockMode: LockMode get() {
    val value = (properties[ELEMENT_LOCK_MODE_KEY] as? ParameterValue.Text)?.value
    return LockMode.entries.find { it.name == value }
        ?: if ((properties[ELEMENT_LOCKED_KEY] as? ParameterValue.Flag)?.value == true) LockMode.ALL else LockMode.NONE
}
val Element.objectLocked: Boolean get() = lockMode != LockMode.NONE
val Element.colourOverride: Long? get() = (properties[ELEMENT_COLOUR_KEY] as? ParameterValue.Colour)?.argb
val Element.objectDisplay: ObjectDisplay get() = displayValue(properties[ELEMENT_DISPLAY_KEY])

fun Element.withLock(mode: LockMode): Element = copy(properties = when (mode) {
    LockMode.NONE -> properties - ELEMENT_LOCK_MODE_KEY - ELEMENT_LOCKED_KEY
    // Preserve the legacy flag for readers that predate separate lock modes.
    LockMode.ALL -> properties + (ELEMENT_LOCK_MODE_KEY to ParameterValue.Text(mode.name)) +
        (ELEMENT_LOCKED_KEY to ParameterValue.Flag(true))
    LockMode.POSITION_ONLY -> properties - ELEMENT_LOCKED_KEY + (ELEMENT_LOCK_MODE_KEY to ParameterValue.Text(mode.name))
})

fun Element.withDisplay(display: ObjectDisplay): Element = copy(properties = if (display == ObjectDisplay.NORMAL)
    properties - ELEMENT_DISPLAY_KEY else properties + (ELEMENT_DISPLAY_KEY to ParameterValue.Text(display.name)))

fun Element.roomSideDisplay(side: RoomSideRef): ObjectDisplay =
    if (side.roomId == id) displayValue(properties[side.displayKey]) else ObjectDisplay.NORMAL

fun Element.hasRoomSideDisplay(side: RoomSideRef): Boolean = side.roomId == id && side.displayKey in properties

fun Element.withRoomSideDisplay(side: RoomSideRef, display: ObjectDisplay): Element {
    require(side.roomId == id) { "The wall side belongs to a different room." }
    val room = requireNotNull(body as? ElementBody.Room) { "Select a room wall side." }
    val ring = if (side.ringIndex == null) room.boundary.outer else room.boundary.holes.getOrNull(side.ringIndex)
    require(ring != null && side.edgeIndex in ring.indices) { "The room wall side no longer exists." }
    // An explicit NORMAL side can override a whole-room X-ray or wireframe choice.
    return copy(properties = properties + (side.displayKey to ParameterValue.Text(display.name)))
}

fun Element.withAllRoomSidesNormal(): Element = copy(properties = properties.filterKeys { !it.startsWith(ROOM_SIDE_DISPLAY_PREFIX) })

private val RoomSideRef.displayKey: String get() = "$ROOM_SIDE_DISPLAY_PREFIX${ringIndex?.toString() ?: "outer"}.$edgeIndex.display"
private fun displayValue(value: ParameterValue?): ObjectDisplay =
    ObjectDisplay.entries.find { it.name == (value as? ParameterValue.Text)?.value } ?: ObjectDisplay.NORMAL
