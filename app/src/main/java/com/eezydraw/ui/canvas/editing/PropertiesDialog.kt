package com.eezydraw.ui.canvas.editing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.eezydraw.R
import com.eezydraw.core.editing.ElementProperties
import com.eezydraw.core.model.LockMode
import com.eezydraw.core.model.LengthUnit
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.Rotation3
import java.math.BigDecimal
import java.util.Locale

/** Raw draft strings survive recreation; the document changes only through Apply. */
@Composable
fun PropertiesDialog(
    properties: ElementProperties,
    units: LengthUnit,
    precision: Int,
    onApply: (ElementProperties) -> Unit,
    onDismiss: () -> Unit,
    lockMode:LockMode=LockMode.NONE,
    layerLocked:Boolean=false,
) {
    PropertiesEditor(properties, units, precision, onApply, onDismiss, dialog = true,lockMode=lockMode,layerLocked=layerLocked)
}

/** Shared atomic numeric draft for the modal editor and the collapsible canvas side panel. */
@Composable
fun PropertiesEditor(
    properties: ElementProperties,
    units: LengthUnit,
    precision: Int,
    onApply: (ElementProperties) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dialog: Boolean = false,
    lockMode:LockMode=LockMode.NONE,
    layerLocked:Boolean=false,
) {
    val factor = editingMetresPerUnit(units)
    val source = remember(properties) { listOf(
        properties.position.x, properties.position.y, properties.position.z,
        properties.rotation.x, properties.rotation.y, properties.rotation.z,
        properties.size.x, properties.size.y, properties.size.z,
    ) }
    val initial = remember(properties, units, precision) { source.mapIndexed { index, value ->
        val displayed = if (index in 3..5) Math.toDegrees(value) else value / factor
        String.format(Locale.ROOT, "%.${precision.coerceIn(0, 8)}f", displayed)
    } }
    var fields by rememberSaveable(properties, units, precision, stateSaver = StringFieldsSaver) {
        mutableStateOf(initial)
    }
    val fullyLocked=layerLocked || lockMode==LockMode.ALL
    LaunchedEffect(lockMode,layerLocked,initial) {
        if(fullyLocked || lockMode==LockMode.POSITION_ONLY)fields=fields.mapIndexed {index,value->
            if(fullyLocked || index<3)initial[index] else value
        }
    }
    // An untouched rounded field retains its original canonical value, including tiny dimensions.
    val parsed = fields.mapIndexed { index, text ->
        if (text == initial[index]) source[index] else text.toDoubleOrNull()?.let {
            if (index in 3..5) Math.toRadians(it) else it * factor
        }?.takeIf(Double::isFinite)
    }
    val errors = parsed.mapIndexed { index, value ->
        value == null || (index >= 6 && source[index] > 0.0 && value <= 0.0)
    }
    var applied by remember(properties) { mutableStateOf(false) }
    val labels = listOf(R.string.editing_property_x, R.string.editing_property_y, R.string.editing_property_z,
        R.string.editing_property_x, R.string.editing_property_y, R.string.editing_property_z,
        R.string.editing_property_width, R.string.editing_property_depth, R.string.editing_property_height)
    val tags = listOf("property_x", "property_y", "property_z", "property_rotation_x", "property_rotation_y",
        "property_rotation_z", "property_width", "property_depth", "property_height")
    val fieldsContent: @Composable () -> Unit = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if(fullyLocked || lockMode==LockMode.POSITION_ONLY)Text(
                stringResource(if(fullyLocked)R.string.selection_locked_properties else R.string.selection_position_locked_properties),
                modifier=Modifier.testTag("properties_lock_message"),style=MaterialTheme.typography.bodyMedium,
            )
            fields.forEachIndexed { index, value ->
                if (index % 3 == 0) {
                    val heading = when (index) {
                        0 -> stringResource(R.string.editing_property_position, stringResource(editingUnitLabel(units)))
                        3 -> stringResource(R.string.editing_property_rotation)
                        else -> stringResource(R.string.editing_property_size, stringResource(editingUnitLabel(units)))
                    }
                    Text(heading, style = MaterialTheme.typography.titleSmall)
                }
                val flat = index >= 6 && source[index] == 0.0
                EditingNumberField(
                    value, { next -> fields = fields.toMutableList().also { it[index] = next }; applied=false },
                    stringResource(labels[index]), tags[index], errors[index], enabled = !flat && !fullyLocked && !(lockMode==LockMode.POSITION_ONLY && index<3),
                    error = stringResource(if (index >= 6) R.string.editing_positive_error else R.string.editing_finite_error),
                )
            }
            if (source.drop(6).any { it == 0.0 }) Text(
                stringResource(R.string.editing_property_flat_hint), style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    val applyButton: @Composable () -> Unit = {
        TextButton(
            onClick = {
                if (!fullyLocked && !applied && errors.none { it }) {
                    applied = true
                    onApply(ElementProperties(
                        Point3(parsed[0]!!, parsed[1]!!, parsed[2]!!),
                        Rotation3(parsed[3]!!, parsed[4]!!, parsed[5]!!),
                        Point3(parsed[6]!!, parsed[7]!!, parsed[8]!!),
                    ))
                }
            }, enabled = !fullyLocked && !applied && errors.none { it }, modifier = Modifier.testTag("properties_apply"),
        ) { Text(stringResource(R.string.editing_apply)) }
    }
    val cancelButton: @Composable () -> Unit = {
        TextButton(onDismiss, Modifier.testTag("properties_cancel")) { Text(stringResource(R.string.editing_cancel)) }
    }
    if (dialog) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.editing_properties)) },
            text = fieldsContent,
            confirmButton = applyButton,
            dismissButton = cancelButton,
        )
    } else {
        Column(modifier.testTag("properties_form")) {
            Column(Modifier.weight(1f, fill = false)) { fieldsContent() }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                cancelButton()
                applyButton()
            }
        }
    }
}

internal val StringFieldsSaver = listSaver<List<String>, String>(save = { it }, restore = { it })

@Composable
internal fun EditingNumberField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    tag: String,
    invalid: Boolean,
    enabled: Boolean = true,
    error: String = stringResource(R.string.editing_finite_error),
    wholeNumber: Boolean = false,
) {
    OutlinedTextField(
        value, onValue, modifier = Modifier.fillMaxWidth().testTag(tag), label = { Text(label) },
        singleLine = true, enabled = enabled, isError = invalid,
        keyboardOptions = KeyboardOptions(keyboardType = if (wholeNumber) KeyboardType.Number else KeyboardType.Decimal),
        supportingText = if (invalid) ({ Text(error) }) else null,
    )
}

internal fun editingMetresPerUnit(units: LengthUnit): Double = when (units) {
    LengthUnit.MILLIMETRES -> .001
    LengthUnit.CENTIMETRES -> .01
    LengthUnit.METRES -> 1.0
    LengthUnit.INCHES -> .0254
    LengthUnit.FEET -> .3048
}

internal fun editingUnitLabel(units: LengthUnit): Int = when (units) {
    LengthUnit.MILLIMETRES -> R.string.editing_unit_mm
    LengthUnit.CENTIMETRES -> R.string.editing_unit_cm
    LengthUnit.METRES -> R.string.editing_unit_m
    LengthUnit.INCHES -> R.string.editing_unit_in
    LengthUnit.FEET -> R.string.editing_unit_ft
}

internal fun editingNumber(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
