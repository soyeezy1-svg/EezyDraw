package com.eezydraw.ui.canvas.editing

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.eezydraw.R
import com.eezydraw.core.model.LengthUnit
import com.eezydraw.core.model.RouteSection

internal fun fittingSectionValues(section: RouteSection?, outlet: RouteSection?): List<Double> {
    fun values(value: RouteSection?): List<Double> = when (value) {
        is RouteSection.Circular -> listOf(value.diameter)
        is RouteSection.Rectangular -> listOf(value.width, value.height)
        null -> emptyList()
    }
    return values(section) + values(outlet)
}

internal fun fittingSectionFromValues(section: RouteSection?, values: List<Double>): RouteSection? = when (section) {
    is RouteSection.Circular -> RouteSection.Circular(values[0])
    is RouteSection.Rectangular -> RouteSection.Rectangular(values[0], values[1])
    null -> null
}

/** Profile dimensions regenerate both physical geometry and the connection ports on Apply. */
@Composable
internal fun FittingConnectionFields(
    section: RouteSection?,
    outlet: RouteSection?,
    fields: List<String>,
    errors: List<Boolean>,
    units: LengthUnit,
    enabled: Boolean,
    onChange: (Int, String) -> Unit,
) {
    if (section == null) return
    Text(stringResource(R.string.fitting_connection_parameters), style = MaterialTheme.typography.titleSmall)
    val labels = when (section) {
        is RouteSection.Circular -> listOf(R.string.fitting_diameter)
        is RouteSection.Rectangular -> listOf(R.string.fitting_section_width, R.string.fitting_section_height)
    } + when (outlet) {
        is RouteSection.Circular -> listOf(R.string.fitting_outlet_diameter)
        is RouteSection.Rectangular -> listOf(R.string.fitting_outlet_width, R.string.fitting_outlet_height)
        null -> emptyList()
    }
    val tags = when (section) {
        is RouteSection.Circular -> listOf("property_fitting_diameter")
        is RouteSection.Rectangular -> listOf("property_fitting_section_width", "property_fitting_section_height")
    } + when (outlet) {
        is RouteSection.Circular -> listOf("property_fitting_outlet_diameter")
        is RouteSection.Rectangular -> listOf("property_fitting_outlet_width", "property_fitting_outlet_height")
        null -> emptyList()
    }
    fields.forEachIndexed { index, value ->
        EditingNumberField(value, { onChange(index, it) },
            stringResource(labels[index], stringResource(editingUnitLabel(units))), tags[index],
            errors[index], enabled, stringResource(R.string.editing_positive_error))
    }
}
