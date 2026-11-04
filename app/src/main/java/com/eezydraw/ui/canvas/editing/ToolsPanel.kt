package com.eezydraw.ui.canvas.editing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eezydraw.R
import com.eezydraw.core.editing.DrawingTool
import com.eezydraw.core.editing.ToolOptions
import com.eezydraw.core.editing.supportsDefaultPlacement
import com.eezydraw.core.model.LengthUnit

/** Compact launcher keeps the canvas clear; expanding never changes the current tool. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolsPanel(
    tool: DrawingTool,
    options: ToolOptions,
    units: LengthUnit,
    selectedCount: Int,
    canUndo: Boolean,
    canRedo: Boolean,
    canPaste: Boolean,
    canFinish: Boolean,
    onTool: (DrawingTool) -> Unit,
    onOptions: (ToolOptions) -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onMirrorX: () -> Unit,
    onMirrorY: () -> Unit,
    onArray: () -> Unit,
    onDelete: () -> Unit,
    onProperties: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // A blank panel area still owns its down event. Child buttons and scrolling handle
    // their gestures first; the canvas underneath must never start a pan from this area.
    Surface(modifier.pointerInput(Unit) { detectTapGestures(onTap = {}) },
        shape = RoundedCornerShape(16.dp), tonalElevation = 5.dp, shadowElevation = 4.dp) {
        if (!expanded) {
            TextButton(onClick = { expanded = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("tools_expand")) {
                Icon(Icons.Outlined.Construction, stringResource(R.string.editing_expand_tools))
                Text(stringResource(R.string.editing_tools), Modifier.padding(start = 8.dp))
            }
        } else {
            Column(Modifier.width(300.dp).heightIn(max = 480.dp).testTag("tools_panel")) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.editing_tools), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { expanded = false }, modifier = Modifier.testTag("tools_collapse")) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.editing_collapse_tools))
                    }
                }
                HorizontalDivider()
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ToolChoices(listOf(DrawingTool.SELECT), tool, onTool)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PanelAction(R.string.editing_undo, "tools_undo", canUndo, onUndo, Modifier.weight(1f))
                        PanelAction(R.string.editing_redo, "tools_redo", canRedo, onRedo, Modifier.weight(1f))
                    }
                    if (tool != DrawingTool.SELECT) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PanelAction(R.string.editing_finish, "tools_finish", canFinish, onFinish, Modifier.weight(1f))
                            PanelAction(R.string.editing_cancel, "tools_cancel", true, onCancel, Modifier.weight(1f))
                        }
                    }
                    ToolOptionsFields(tool, options, units, onOptions)
                    Text(stringResource(R.string.editing_selection_count, selectedCount), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.editing_selection_hint), style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), maxItemsInEachRow = 2) {
                        PanelAction(R.string.editing_properties, "tools_properties", selectedCount == 1, onProperties, Modifier.weight(1f))
                        PanelAction(R.string.editing_delete, "tools_delete", selectedCount > 0, onDelete, Modifier.weight(1f))
                        PanelAction(R.string.editing_copy, "tools_copy", selectedCount > 0, onCopy, Modifier.weight(1f))
                        PanelAction(R.string.editing_paste, "tools_paste", canPaste, onPaste, Modifier.weight(1f))
                        PanelAction(R.string.editing_mirror_x, "tools_mirror_x", selectedCount > 0, onMirrorX, Modifier.weight(1f))
                        PanelAction(R.string.editing_mirror_y, "tools_mirror_y", selectedCount > 0, onMirrorY, Modifier.weight(1f))
                        PanelAction(R.string.editing_array, "tools_array", selectedCount > 0, onArray, Modifier.weight(1f))
                    }
                    ToolSection(R.string.editing_shapes, listOf(DrawingTool.LINE, DrawingTool.POLYLINE,
                        DrawingTool.ARC, DrawingTool.CIRCLE, DrawingTool.ELLIPSE, DrawingTool.RECTANGLE,
                        DrawingTool.POLYGON, DrawingTool.TRIANGLE, DrawingTool.SPLINE), tool, onTool)
                    ToolSection(R.string.editing_annotations, listOf(DrawingTool.TEXT, DrawingTool.LEADER,
                        DrawingTool.DIM_ALIGNED, DrawingTool.DIM_ANGULAR, DrawingTool.DIM_RADIAL), tool, onTool)
                    ToolSection(R.string.editing_solids, listOf(DrawingTool.BOX, DrawingTool.SPHERE,
                        DrawingTool.CYLINDER, DrawingTool.CONE, DrawingTool.PYRAMID), tool, onTool)
                }
            }
        }
    }
}

@Composable
private fun PanelAction(label: Int, tag: String, enabled: Boolean, action: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(action, modifier.heightIn(min = 48.dp).testTag(tag), enabled = enabled) { Text(stringResource(label)) }
}

@Composable
private fun ToolSection(title: Int, tools: List<DrawingTool>, selected: DrawingTool, onTool: (DrawingTool) -> Unit) {
    HorizontalDivider()
    Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
    ToolChoices(tools, selected, onTool)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolChoices(tools: List<DrawingTool>, selected: DrawingTool, onTool: (DrawingTool) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), maxItemsInEachRow = 2) {
        tools.forEach { item ->
            FilterChip(
                selected = item == selected, onClick = { onTool(item) },
                label = { Text(stringResource(drawingToolLabel(item))) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("tool_${item.name.lowercase()}"),
            )
        }
    }
}

@Composable
private fun ToolOptionsFields(tool: DrawingTool, options: ToolOptions, units: LengthUnit, onOptions: (ToolOptions) -> Unit) {
    if (supportsDefaultPlacement(tool)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(!options.useDefaultDimensions, { onOptions(options.copy(useDefaultDimensions = !it)) },
                Modifier.testTag("tool_draw_by_points"))
            Text(stringResource(R.string.editing_draw_by_points))
        }
        Text(stringResource(if (options.useDefaultDimensions) R.string.editing_default_placement_hint
            else R.string.editing_point_placement_hint), style = MaterialTheme.typography.bodySmall)
        if (options.useDefaultDimensions) {
            val radial = tool in setOf(DrawingTool.CIRCLE, DrawingTool.SPHERE, DrawingTool.CYLINDER, DrawingTool.CONE)
            OptionLength(options.width, units, if (radial) R.string.editing_default_diameter else R.string.editing_default_width,
                "tool_default_width", tool) { onOptions(options.copy(width = it)) }
            if (!radial) OptionLength(options.depth, units, R.string.editing_default_depth,
                "tool_default_depth", tool) { onOptions(options.copy(depth = it)) }
        }
    }
    if (tool == DrawingTool.SELECT) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(options.keepProportions, { onOptions(options.copy(keepProportions = it)) },
                Modifier.testTag("tool_keep_proportions"))
            Text(stringResource(R.string.editing_keep_proportions))
        }
    }
    if (tool == DrawingTool.TEXT || tool == DrawingTool.LEADER) {
        var text by rememberSaveable(tool, options.text) { mutableStateOf(options.text) }
        val invalid = text.isBlank() || text.length > 512
        OutlinedTextField(
            text, { next ->
                text = next
                if (next.isNotBlank() && next.length <= 512) onOptions(options.copy(text = next))
            }, modifier = Modifier.fillMaxWidth().testTag("tool_text"), label = { Text(stringResource(R.string.editing_text_content)) },
            isError = invalid, maxLines = 4,
            supportingText = if (invalid) ({ Text(stringResource(R.string.editing_text_error)) }) else null,
        )
        if (tool == DrawingTool.TEXT) OptionLength(
            options.textHeight, units, R.string.editing_text_height, "tool_text_height", tool,
        ) { onOptions(options.copy(textHeight = it)) }
    }
    if (tool in setOf(DrawingTool.BOX, DrawingTool.CYLINDER, DrawingTool.CONE, DrawingTool.PYRAMID)) {
        OptionLength(options.height, units, R.string.editing_solid_height, "tool_solid_height", tool) {
            onOptions(options.copy(height = it))
        }
    }
    if (tool == DrawingTool.POLYLINE) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(options.closed, { onOptions(options.copy(closed = it)) }, Modifier.testTag("tool_polyline_closed"))
            Text(stringResource(R.string.editing_closed_polyline))
        }
    }
}

@Composable
private fun OptionLength(value: Double, units: LengthUnit, label: Int, tag: String, tool: DrawingTool, onValue: (Double) -> Unit) {
    val factor = editingMetresPerUnit(units)
    var text by rememberSaveable(tool, tag) { mutableStateOf(editingNumber(value / factor)) }
    var accepted by rememberSaveable(tool, tag) { mutableDoubleStateOf(value) }
    var displayedUnits by rememberSaveable(tool, tag) { mutableStateOf(units) }
    LaunchedEffect(value, units) {
        val externalChange = value != accepted
        val unitChange = displayedUnits != units
        // Locally accepted edits keep their raw form (including a trailing decimal separator).
        // Switching units reformats valid input from metres; invalid drafts remain editable.
        val validDraft = text.toDoubleOrNull()?.let { it.isFinite() && it > 0.0 } == true
        if (externalChange || unitChange && validDraft) {
            text = editingNumber(value / factor)
        }
        accepted = value
        displayedUnits = units
    }
    val canonical = text.toDoubleOrNull()?.times(factor)?.takeIf { it.isFinite() && it > 0.0 }
    EditingNumberField(text, { next ->
        text = next
        next.toDoubleOrNull()?.times(factor)?.takeIf { it.isFinite() && it > 0.0 }?.let {
            accepted = it
            onValue(it)
        }
    }, stringResource(label, stringResource(editingUnitLabel(units))), tag, canonical == null,
        error = stringResource(R.string.editing_positive_error))
}

internal fun drawingToolLabel(tool: DrawingTool): Int = when (tool) {
    DrawingTool.SELECT -> R.string.editing_select
    DrawingTool.LINE -> R.string.editing_line
    DrawingTool.POLYLINE -> R.string.editing_polyline
    DrawingTool.ARC -> R.string.editing_arc
    DrawingTool.CIRCLE -> R.string.editing_circle
    DrawingTool.ELLIPSE -> R.string.editing_ellipse
    DrawingTool.RECTANGLE -> R.string.editing_rectangle
    DrawingTool.POLYGON -> R.string.editing_polygon
    DrawingTool.TRIANGLE -> R.string.editing_triangle
    DrawingTool.SPLINE -> R.string.editing_spline
    DrawingTool.TEXT -> R.string.editing_text
    DrawingTool.LEADER -> R.string.editing_leader
    DrawingTool.DIM_ALIGNED -> R.string.editing_dim_aligned
    DrawingTool.DIM_ANGULAR -> R.string.editing_dim_angular
    DrawingTool.DIM_RADIAL -> R.string.editing_dim_radial
    DrawingTool.BOX -> R.string.editing_box
    DrawingTool.SPHERE -> R.string.editing_sphere
    DrawingTool.CYLINDER -> R.string.editing_cylinder
    DrawingTool.CONE -> R.string.editing_cone
    DrawingTool.PYRAMID -> R.string.editing_pyramid
}
