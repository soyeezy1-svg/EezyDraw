package com.eezydraw.ui.canvas.panels

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eezydraw.R
import com.eezydraw.core.model.LibraryItem
import com.eezydraw.core.model.LibraryItemId

/** Displays only actual definitions carried by the active project. */
@Composable
fun LibraryPanel(
    items: List<LibraryItem>,
    onInsert: (LibraryItemId) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
) {
    val content: @Composable () -> Unit = {
        Column(Modifier.width(300.dp).heightIn(max=480.dp).testTag("library_panel")) {
            if(!embedded) Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.panel_library), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                CanvasActionButton(Icons.Outlined.Close, stringResource(R.string.close), onDismiss,
                    Modifier.testTag("library_close"))
            }
            if(!embedded) HorizontalDivider()
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (items.isEmpty()) Text(stringResource(R.string.panel_library_empty))
                items.groupBy { it.category }.toSortedMap().forEach { (category, definitions) ->
                    Text(category, style = MaterialTheme.typography.titleSmall)
                    definitions.sortedBy { it.name }.forEach { item ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(item.name, Modifier.weight(1f))
                            CanvasActionButton(Icons.Outlined.Add, stringResource(R.string.panel_insert_library, item.name),
                                { onInsert(item.id) }, Modifier.testTag("library_insert_${item.id.value}"))
                        }
                    }
                }
            }
        }
    }
    if(embedded) Box(modifier) {content()} else Surface(
        modifier.pointerInput(Unit) {detectTapGestures(onTap={})},
        shape=RoundedCornerShape(16.dp),tonalElevation=5.dp,shadowElevation=4.dp,
    ) {content()}
}
