package com.eezydraw.ui.canvas.panels

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.eezydraw.R
import com.eezydraw.core.model.LayerId
import com.eezydraw.core.model.ProjectDocument
import com.eezydraw.core.model.SystemId
import com.eezydraw.core.model.ViewMode

/** View style and persisted layer/system visibility are shared by plan and orbit views. */
@Composable
fun ViewControls(
    document: ProjectDocument,
    mode: ViewMode,
    onMode: (ViewMode) -> Unit,
    onLayerVisibility: (LayerId, Boolean) -> Unit,
    onSystemVisibility: (SystemId, Boolean) -> Unit,
    onMeasurementsVisibility: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var modesOpen by rememberSaveable { mutableStateOf(false) }
    var visibilityOpen by rememberSaveable { mutableStateOf(false) }
    Surface(modifier.pointerInput(Unit) { detectTapGestures(onTap = {}) }, tonalElevation = 3.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                CanvasActionButton(Icons.Outlined.ViewInAr, stringResource(viewModeLabel(mode)),
                    { modesOpen = !modesOpen }, Modifier.testTag("canvas_view_mode"))
                DropdownMenu(modesOpen, { modesOpen = false }) {
                    canvasViewModes.forEach { item ->
                        DropdownMenuItem(text = { Text(stringResource(viewModeLabel(item))) },
                            leadingIcon = if (item == mode) ({ Icon(Icons.Outlined.Check, null) }) else null,
                            modifier = Modifier.testTag("view_mode_${item.name.lowercase()}"),
                            onClick = { modesOpen = false; onMode(item) })
                    }
                }
            }
            Text(stringResource(viewModeLabel(mode)), style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 4.dp))
            CanvasActionButton(Icons.Outlined.Layers, stringResource(R.string.panel_visibility),
                { visibilityOpen = true }, Modifier.testTag("canvas_visibility"))
        }
    }
    if (visibilityOpen) VisibilityPanel(document, onLayerVisibility, onSystemVisibility,
        onMeasurementsVisibility, onDismiss = { visibilityOpen = false })
}

val canvasViewModes = listOf(ViewMode.WIREFRAME, ViewMode.HIDDEN_LINE, ViewMode.SHADED,
    ViewMode.SHADED_WITH_EDGES, ViewMode.XRAY, ViewMode.REALISTIC)

fun viewModeLabel(mode: ViewMode): Int = when (mode) {
    ViewMode.WIREFRAME -> R.string.panel_mode_wireframe
    ViewMode.HIDDEN_LINE, ViewMode.SECTION -> R.string.panel_mode_hidden_line
    ViewMode.SHADED -> R.string.panel_mode_shaded
    ViewMode.SHADED_WITH_EDGES -> R.string.panel_mode_shaded_edges
    ViewMode.XRAY, ViewMode.TRANSPARENT -> R.string.panel_mode_xray
    ViewMode.REALISTIC -> R.string.panel_mode_realistic
}

@Composable
fun VisibilityPanel(
    document: ProjectDocument,
    onLayerVisibility: (LayerId, Boolean) -> Unit,
    onSystemVisibility: (SystemId, Boolean) -> Unit,
    onMeasurementsVisibility: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.panel_visibility)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VisibilityRow(stringResource(R.string.panel_saved_measurements), document.project.settings.showMeasurements,
                    "visibility_measurements", onMeasurementsVisibility)
                HorizontalDivider()
                Text(stringResource(R.string.panel_layers), style = MaterialTheme.typography.titleSmall)
                if (document.layers.isEmpty()) Text(stringResource(R.string.panel_no_layers))
                document.layers.forEach { layer -> VisibilityRow(layer.name, layer.visible,
                    "visibility_layer_${layer.id.value}", { onLayerVisibility(layer.id, it) }) }
                HorizontalDivider()
                Text(stringResource(R.string.panel_systems), style = MaterialTheme.typography.titleSmall)
                if (document.systems.isEmpty()) Text(stringResource(R.string.panel_no_systems))
                document.systems.forEach { system -> VisibilityRow(system.name, system.visible,
                    "visibility_system_${system.id.value}", { onSystemVisibility(system.id, it) }) }
            }
        }, confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } })
}

@Composable
private fun VisibilityRow(label: String, visible: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
        .toggleable(visible, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(visible, onCheckedChange = null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}
