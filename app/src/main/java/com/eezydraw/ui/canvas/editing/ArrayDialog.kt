package com.eezydraw.ui.canvas.editing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eezydraw.R
import com.eezydraw.core.model.LengthUnit

@Composable
fun ArrayDialog(
    units: LengthUnit,
    onApply: (columns: Int, rows: Int, dxMetres: Double, dyMetres: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val factor = editingMetresPerUnit(units)
    var columns by rememberSaveable { mutableStateOf("2") }
    var rows by rememberSaveable { mutableStateOf("1") }
    var dx by rememberSaveable(units) { mutableStateOf(editingNumber(1.0 / factor)) }
    var dy by rememberSaveable(units) { mutableStateOf(editingNumber(1.0 / factor)) }
    var applied by rememberSaveable { mutableStateOf(false) }
    val columnCount = columns.toIntOrNull()?.takeIf { it in 1..100 }
    val rowCount = rows.toIntOrNull()?.takeIf { it in 1..100 }
    val x = dx.toDoubleOrNull()?.times(factor)?.takeIf(Double::isFinite)
    val y = dy.toDoubleOrNull()?.times(factor)?.takeIf(Double::isFinite)
    val countValid = columnCount != null && rowCount != null && columnCount * rowCount in 2..1000
    val xValid = x != null && (columnCount == 1 || x != 0.0)
    val yValid = y != null && (rowCount == 1 || y != 0.0)
    val valid = countValid && xValid && yValid
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editing_array)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.editing_array_hint), style = MaterialTheme.typography.bodySmall)
                EditingNumberField(columns, { columns = it }, stringResource(R.string.editing_array_columns),
                    "array_columns", columnCount == null, error = stringResource(R.string.editing_array_count_error), wholeNumber = true)
                EditingNumberField(rows, { rows = it }, stringResource(R.string.editing_array_rows),
                    "array_rows", rowCount == null, error = stringResource(R.string.editing_array_count_error), wholeNumber = true)
                if (!countValid) Text(stringResource(R.string.editing_array_total_error), color = MaterialTheme.colorScheme.error)
                EditingNumberField(dx, { dx = it }, stringResource(R.string.editing_array_dx, stringResource(editingUnitLabel(units))),
                    "array_dx", !xValid, error = stringResource(R.string.editing_array_spacing_error))
                EditingNumberField(dy, { dy = it }, stringResource(R.string.editing_array_dy, stringResource(editingUnitLabel(units))),
                    "array_dy", !yValid, error = stringResource(R.string.editing_array_spacing_error))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (valid && !applied) {
                        applied = true
                        onApply(columnCount!!, rowCount!!, x!!, y!!)
                    }
                }, enabled = valid && !applied, modifier = Modifier.testTag("array_apply"),
            ) { Text(stringResource(R.string.editing_apply)) }
        },
        dismissButton = {
            TextButton(onDismiss, Modifier.testTag("array_cancel")) { Text(stringResource(R.string.editing_cancel)) }
        },
    )
}
