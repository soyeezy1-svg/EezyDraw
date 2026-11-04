package com.eezydraw.core.model

/** Reserved per-object presentation/edit state, preserved in native archives and sparse edits. */
const val ELEMENT_LOCKED_KEY = "eezydraw.locked"
const val ELEMENT_COLOUR_KEY = "eezydraw.colour"

val Element.objectLocked: Boolean get() = (properties[ELEMENT_LOCKED_KEY] as? ParameterValue.Flag)?.value == true
val Element.colourOverride: Long? get() = (properties[ELEMENT_COLOUR_KEY] as? ParameterValue.Colour)?.argb
