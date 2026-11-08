package com.eezydraw.ui.help

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eezydraw.ui.components.CadTooltip
import com.eezydraw.ui.components.CadPageBar
import com.eezydraw.ui.components.CadStyle
import androidx.compose.foundation.layout.size
import com.eezydraw.R

private enum class HelpSection { GUIDE, GESTURES, ABOUT, FEEDBACK }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(
    onBack: () -> Unit,
    onFeedback: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var section by rememberSaveable { mutableStateOf(HelpSection.GUIDE) }
    var feedback by rememberSaveable { mutableStateOf("") }
    Scaffold(
        modifier = modifier.fillMaxSize().testTag("help_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CadPageBar(
                title = stringResource(R.string.help),
                navigation = {
                    CadTooltip(stringResource(R.string.help_back)) {IconButton(onClick = onBack,modifier=Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.help_back),modifier=Modifier.size(24.dp))
                    }}
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 900.dp).fillMaxSize()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    HelpSection.entries.forEach { entry ->
                        item {
                            FilterChip(
                                selected = entry == section,
                                onClick = { section = entry },
                                label = { Text(stringResource(sectionLabel(entry))) },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("help_section_${entry.name.lowercase()}"),
                            )
                        }
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("help_content"),
                    contentPadding = PaddingValues(24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    when (section) {
                        HelpSection.GUIDE -> {
                            item { HelpArticle(R.string.help_create_title, R.string.help_create_body) }
                            item { HelpArticle(R.string.help_shapes_title, R.string.help_shapes_body) }
                            item { HelpArticle(R.string.help_annotations_title, R.string.help_annotations_body) }
                            item { HelpArticle(R.string.help_primitives_title, R.string.help_primitives_body) }
                            item { HelpArticle(R.string.help_editing_title, R.string.help_editing_body) }
                            item { HelpArticle(R.string.help_view_modes_title, R.string.help_view_modes_body) }
                            item { HelpArticle(R.string.help_measurements_title, R.string.help_measurements_body) }
                            item { HelpArticle(R.string.help_history_title, R.string.help_history_body) }
                            item { HelpArticle(R.string.help_save_title, R.string.help_save_body) }
                            item { HelpArticle(R.string.help_open_title, R.string.help_open_body) }
                            item { HelpArticle(R.string.help_defaults_title, R.string.help_defaults_body) }
                            item { HelpArticle(R.string.help_backups_title, R.string.help_backups_body) }
                        }
                        HelpSection.GESTURES -> {
                            item { Text(stringResource(R.string.help_gesture_scope), style = MaterialTheme.typography.bodyLarge) }
                            item { HelpArticle(R.string.help_current_gestures_title, R.string.help_current_gestures_body) }
                            item { HelpArticle(R.string.help_drawing_gestures_title, R.string.help_drawing_gestures_body) }
                            item { HelpArticle(R.string.help_shortcuts_title, R.string.help_shortcuts_body) }
                        }
                        HelpSection.ABOUT -> {
                            item {
                                Card(Modifier.fillMaxWidth(),shape=CadStyle.shape) {
                                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                                        Text(stringResource(R.string.help_version))
                                        Text(stringResource(R.string.help_about_body))
                                    }
                                }
                            }
                            item { HelpArticle(R.string.help_privacy_title, R.string.help_privacy_body) }
                        }
                        HelpSection.FEEDBACK -> {
                            item { Text(stringResource(R.string.help_feedback_intro), style = MaterialTheme.typography.bodyLarge) }
                            item {
                                OutlinedTextField(
                                    value = feedback,
                                    onValueChange = { feedback = it },
                                    label = { Text(stringResource(R.string.help_feedback_label)) },
                                    minLines = 5,
                                    isError = feedback.length > 4000,
                                    supportingText = { Text(stringResource(R.string.help_feedback_limit)) },
                                    modifier = Modifier.fillMaxWidth().testTag("help_feedback_text"),
                                )
                            }
                            item {
                                val subject = stringResource(R.string.help_feedback_subject)
                                Button(
                                    onClick = { onFeedback("$subject\n\n${feedback.trim()}") },
                                    enabled = feedback.isNotBlank() && feedback.length <= 4000,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("share_feedback"),
                                ) { Text(stringResource(R.string.help_share_feedback)) }
                            }
                            item { Text(stringResource(R.string.help_feedback_privacy), style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
    }
}

private fun sectionLabel(section: HelpSection): Int = when (section) {
    HelpSection.GUIDE -> R.string.help_user_guide
    HelpSection.GESTURES -> R.string.help_gesture_sheet
    HelpSection.ABOUT -> R.string.help_about
    HelpSection.FEEDBACK -> R.string.help_feedback
}

@Composable
private fun HelpArticle(title: Int, body: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(stringResource(body), style = MaterialTheme.typography.bodyLarge)
    }
}
