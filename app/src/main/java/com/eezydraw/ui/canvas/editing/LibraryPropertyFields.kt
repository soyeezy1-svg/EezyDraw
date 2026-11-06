package com.eezydraw.ui.canvas.editing

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.eezydraw.R
import com.eezydraw.core.model.LengthUnit

/** Metadata shares the transform draft, so Apply is one instance edit. */
@Composable
internal fun LibraryPropertyFields(
    fields: List<String>,
    onChange: (Int, String) -> Unit,
    mountingInvalid: Boolean,
    colourInvalid: Boolean,
    units: LengthUnit,
    enabled: Boolean,
    mountingEnabled: Boolean,
    signedMountingHeight: Boolean = false,
) {
    EditingNumberField(fields[0], { onChange(0, it) },
        stringResource(R.string.library_mounting_height, stringResource(editingUnitLabel(units))),
        "property_mounting_height", mountingInvalid, mountingEnabled,
        stringResource(if (signedMountingHeight) R.string.editing_finite_error else R.string.library_mounting_error))
    val labels = listOf(R.string.library_property_material, R.string.library_property_colour,
        R.string.library_property_system, R.string.library_property_manufacturer,
        R.string.library_property_model, R.string.library_property_name_tag)
    val tags = listOf("property_material", "property_colour", "property_system",
        "property_manufacturer", "property_model", "property_name_tag")
    labels.forEachIndexed { index, label ->
        val fieldIndex = index + 1
        val invalid = fieldIndex == 2 && colourInvalid
        OutlinedTextField(fields[fieldIndex], { onChange(fieldIndex, it) },
            modifier = Modifier.fillMaxWidth().testTag(tags[index]),
            label = { Text(stringResource(label)) }, singleLine = true,
            enabled = enabled, isError = invalid,
            supportingText = if (invalid) ({ Text(stringResource(R.string.library_colour_error)) }) else null,
        )
    }
}

/** Accept familiar RGB or ARGB hex notation without depending on Android's colour parser. */
internal fun parseLibraryColour(value: String): Long? {
    val hex = value.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
    if (hex.length != 6 && hex.length != 8) return null
    if (hex.any { it.digitToIntOrNull(16) == null }) return null
    val colour = hex.toLongOrNull(16) ?: return null
    return if (hex.length == 6) colour or 0xFF000000 else colour
}
