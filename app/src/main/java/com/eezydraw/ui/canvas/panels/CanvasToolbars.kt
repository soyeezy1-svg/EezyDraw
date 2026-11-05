package com.eezydraw.ui.canvas.panels

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Architecture
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.eezydraw.R
import com.eezydraw.core.model.CanvasPanelId
import com.eezydraw.core.model.PanelPlacement
import com.eezydraw.core.model.PanelVisibility

enum class CanvasToolGroup { DRAW, EDIT }

/** One consistent 48dp target and tooltip for canvas actions, in either application theme. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanvasActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean? = null,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        val target = modifier.size(48.dp).semantics { if (selected != null) this.selected = selected }
        if (selected == true) FilledTonalIconButton(onClick, target, enabled = enabled) {
            Icon(icon, label)
        } else IconButton(onClick, target, enabled = enabled) { Icon(icon, label) }
    }
}

/** Compact toolbar launchers; each opens a focused group instead of a wall of text buttons. */
@Composable
fun CanvasToolbars(
    onDraw: () -> Unit,
    onEdit: () -> Unit,
    onView: () -> Unit,
    onMeasure: () -> Unit,
    onReferenceDimension: () -> Unit,
    onMeasurements: () -> Unit,
    onLibrary: () -> Unit,
    onTogglePanels: () -> Unit,
    panelsHidden: Boolean,
    modifier: Modifier = Modifier,
    activeTool: String? = null,
    measureActive: Boolean = false,
    libraryActive: Boolean = false,
    panelStates: Map<CanvasPanelId, PanelPlacement> = emptyMap(),
    onPanelChange: ((CanvasPanelId, PanelVisibility) -> Unit)? = null,
    showPanelToggle: Boolean = true,
) {
    var measuresOpen by rememberSaveable { mutableStateOf(false) }
    fun toggle(id: CanvasPanelId, fallback: () -> Unit) {
        val callback = onPanelChange
        if(callback == null) fallback() else callback(id,
            if(panelStates[id]?.state == PanelVisibility.OPEN) PanelVisibility.COLLAPSED else PanelVisibility.OPEN)
    }
    fun open(id: CanvasPanelId) = panelStates[id]?.state == PanelVisibility.OPEN
    Surface(modifier.pointerInput(Unit) { detectTapGestures(onTap = {}) },
        shape = RoundedCornerShape(16.dp), tonalElevation = 4.dp, shadowElevation = 2.dp) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!panelsHidden) {
                ToolbarGroup(Icons.Outlined.Draw, R.string.panel_draw, "toolbar_draw", { toggle(CanvasPanelId.DRAW, onDraw) },open(CanvasPanelId.DRAW) || activeTool!=null && !measureActive && !libraryActive)
                ToolbarGroup(Icons.Outlined.Tune, R.string.panel_edit, "toolbar_edit", { toggle(CanvasPanelId.EDIT, onEdit) },open(CanvasPanelId.EDIT))
                ToolbarGroup(Icons.Outlined.Visibility, R.string.panel_view, "toolbar_view", { toggle(CanvasPanelId.VIEW, onView) },open(CanvasPanelId.VIEW))
                Box {
                    ToolbarGroup(Icons.Outlined.Straighten, R.string.panel_measure, "toolbar_measure", { toggle(CanvasPanelId.MEASURE) { measuresOpen = !measuresOpen } },measureActive || open(CanvasPanelId.MEASURE))
                    DropdownMenu(measuresOpen && onPanelChange == null, { measuresOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.panel_measure_distance)) },
                            leadingIcon = { Icon(Icons.Outlined.Straighten, null) },
                            modifier = Modifier.testTag("toolbar_measure_distance"),
                            onClick = { measuresOpen = false; onMeasure() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.panel_reference_dimension)) },
                            leadingIcon = { Icon(Icons.Outlined.Architecture, null) },
                            modifier = Modifier.testTag("toolbar_reference_dimension"),
                            onClick = { measuresOpen = false; onReferenceDimension() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.panel_saved_measurements)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ListAlt, null) },
                            modifier = Modifier.testTag("toolbar_measurements"),
                            onClick = { measuresOpen = false; onMeasurements() })
                    }
                }
                ToolbarGroup(Icons.Outlined.Inventory2, R.string.panel_library, "toolbar_library", { toggle(CanvasPanelId.LIBRARY, onLibrary) },libraryActive || open(CanvasPanelId.LIBRARY))
            }
            if(showPanelToggle) CanvasActionButton(if (panelsHidden) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                stringResource(if (panelsHidden) R.string.panel_show_panels else R.string.panel_hide_panels),
                onTogglePanels, Modifier.testTag(if (panelsHidden) "canvas_show_panels" else "canvas_hide_panels"))
        }
    }
}

@Composable
private fun ToolbarGroup(icon: ImageVector, label: Int, tag: String, onClick: () -> Unit, selected:Boolean=false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CanvasActionButton(icon, stringResource(label), onClick, Modifier.testTag(tag),selected=selected)
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
    }
}

/** Persistent Measure content; choosing a tool does not change the enclosing panel state. */
@Composable
fun MeasureTools(
    onMeasure: () -> Unit,
    onReferenceDimension: () -> Unit,
    onMeasurements: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(4.dp)) {
        TextButton(onMeasure, Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("toolbar_measure_distance")) {
            Icon(Icons.Outlined.Straighten, null)
            Text(stringResource(R.string.panel_measure_distance), Modifier.padding(start=8.dp))
        }
        TextButton(onReferenceDimension, Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("toolbar_reference_dimension")) {
            Icon(Icons.Outlined.Architecture, null)
            Text(stringResource(R.string.panel_reference_dimension), Modifier.padding(start=8.dp))
        }
        TextButton(onMeasurements, Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("toolbar_measurements")) {
            Icon(Icons.AutoMirrored.Outlined.ListAlt, null)
            Text(stringResource(R.string.panel_saved_measurements), Modifier.padding(start=8.dp))
        }
    }
}
