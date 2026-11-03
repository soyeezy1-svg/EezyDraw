package com.eezydraw.ui.start

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.eezydraw.R
import com.eezydraw.core.model.ThemeMode

/** Limited destinations for the startup slice; project workflows attach through onAction later. */
@Composable
internal fun MenuActionDialog(
    action: String,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val title = when (action) {
        "settings" -> R.string.settings
        "help" -> R.string.help
        "templates" -> R.string.templates
        "open" -> R.string.open_from_file
        "create" -> R.string.create_new_project
        else -> R.string.continue_project
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            if (action == "settings") {
                Column {
                    Text(stringResource(R.string.appearance))
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            Modifier.fillMaxWidth().selectable(
                                selected = mode == themeMode,
                                onClick = { onThemeModeChange(mode) },
                                role = Role.RadioButton,
                            ).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = mode == themeMode, onClick = null)
                            Text(stringResource(when (mode) {
                                ThemeMode.SYSTEM -> R.string.theme_system
                                ThemeMode.LIGHT -> R.string.theme_light
                                ThemeMode.DARK -> R.string.theme_dark
                            }), Modifier.padding(start = 8.dp))
                        }
                    }
                }
            } else {
                Text(stringResource(when (action) {
                    "help" -> R.string.start_menu_help
                    "templates" -> R.string.templates_availability
                    "open" -> R.string.open_availability
                    "create" -> R.string.create_availability
                    else -> R.string.editor_availability
                }))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
