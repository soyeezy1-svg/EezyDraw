package com.eezydraw.ui.canvas.panels

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eezydraw.R
import com.eezydraw.core.editing.ElementProperties
import com.eezydraw.core.editing.LibraryInstanceProperties
import com.eezydraw.core.model.LockMode
import com.eezydraw.core.model.LengthUnit
import com.eezydraw.ui.canvas.editing.PropertiesEditor

/** Nonmodal properties retain an unapplied draft when collapsed or the activity is recreated. */
@Composable
fun PropertiesSidePanel(
    properties: ElementProperties,
    units: LengthUnit,
    precision: Int,
    onApply: (ElementProperties) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    lockMode:LockMode=LockMode.NONE,
    layerLocked:Boolean=false,
    embedded:Boolean=false,
    libraryProperties: LibraryInstanceProperties? = null,
    onApplyLibrary: ((ElementProperties, LibraryInstanceProperties) -> Unit)? = null,
    onUpdateAllOfType: ((ElementProperties, LibraryInstanceProperties) -> Unit)? = null,
    onResetToLibraryDefault: (() -> Unit)? = null,
) {
    if(embedded) {
        PropertiesEditor(properties,units,precision,onApply,onDismiss,
            modifier.width(320.dp).heightIn(max=520.dp).testTag("properties_side_panel").padding(12.dp),
            lockMode=lockMode,layerLocked=layerLocked,libraryProperties=libraryProperties,
            onApplyLibrary=onApplyLibrary,onUpdateAllOfType=onUpdateAllOfType,
            onResetToLibraryDefault=onResetToLibraryDefault)
        return
    }
    var expanded by rememberSaveable { mutableStateOf(true) }
    val drafts = rememberSaveableStateHolder()
    Surface(modifier.width(320.dp).heightIn(max = 560.dp).testTag("properties_side_panel")
        .pointerInput(Unit) { detectTapGestures(onTap = {}) },
        shape = RoundedCornerShape(16.dp), tonalElevation = 5.dp, shadowElevation = 4.dp) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.editing_properties), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                CanvasActionButton(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    stringResource(if (expanded) R.string.panel_collapse_properties else R.string.panel_expand_properties),
                    { expanded = !expanded }, Modifier.testTag("properties_toggle"))
                CanvasActionButton(Icons.Outlined.Close, stringResource(R.string.panel_close_properties),
                    onDismiss, Modifier.testTag("properties_close"))
            }
            if (expanded) {
                HorizontalDivider()
                drafts.SaveableStateProvider("properties_editor") {
                    PropertiesEditor(properties, units, precision, onApply, onDismiss,
                        Modifier.fillMaxWidth().weight(1f, fill = false).padding(12.dp),
                        lockMode=lockMode,layerLocked=layerLocked,libraryProperties=libraryProperties,
                        onApplyLibrary=onApplyLibrary,onUpdateAllOfType=onUpdateAllOfType,
                        onResetToLibraryDefault=onResetToLibraryDefault)
                }
            }
        }
    }
}
