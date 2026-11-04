package com.eezydraw.ui.canvas.panels

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
) {
    var measuresOpen by rememberSaveable { mutableStateOf(false) }
    Surface(modifier.pointerInput(Unit) { detectTapGestures(onTap = {}) },
        shape = RoundedCornerShape(16.dp), tonalElevation = 4.dp, shadowElevation = 2.dp) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!panelsHidden) {
                ToolbarGroup(Icons.Outlined.Draw, R.string.panel_draw, "toolbar_draw", onDraw,activeTool!=null && !measureActive && !libraryActive)
                ToolbarGroup(Icons.Outlined.Tune, R.string.panel_edit, "toolbar_edit", onEdit)
                ToolbarGroup(Icons.Outlined.Visibility, R.string.panel_view, "toolbar_view", onView)
                Box {
                    ToolbarGroup(Icons.Outlined.Straighten, R.string.panel_measure, "toolbar_measure", { measuresOpen = !measuresOpen },measureActive)
                    DropdownMenu(measuresOpen, { measuresOpen = false }) {
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
                ToolbarGroup(Icons.Outlined.Inventory2, R.string.panel_library, "toolbar_library", onLibrary,libraryActive)
            }
            CanvasActionButton(if (panelsHidden) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
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
