package com.eezydraw.ui.canvas

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eezydraw.R

/** A single active-tool banner, hosted inside the canvas/view toolbar. */
@Composable
internal fun PlacementBanner(
    activeLabel: String, hint: String, canDone: Boolean,
    onCancel: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier, showCancel:Boolean=true,
) {
    Surface(modifier.fillMaxWidth().testTag("placement_banner"),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(activeLabel, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("placement_active_tool"))
                Text(hint, style = MaterialTheme.typography.bodySmall, maxLines = 2, modifier = Modifier.testTag("placement_hint"))
            }
            if(showCancel) TextButton(onCancel, Modifier.heightIn(min = 48.dp).testTag("placement_cancel")) {
                Text(stringResource(R.string.placement_cancel))
            }
            TextButton(onDone, Modifier.heightIn(min = 48.dp).testTag("placement_done"), enabled = canDone) {
                Text(stringResource(R.string.placement_done))
            }
        }
    }
}
