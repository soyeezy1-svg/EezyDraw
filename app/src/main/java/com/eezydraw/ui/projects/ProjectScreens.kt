package com.eezydraw.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eezydraw.R
import com.eezydraw.core.model.LengthUnit
import com.eezydraw.core.model.ProjectDocument
import com.eezydraw.core.model.ProjectSettings
import com.eezydraw.core.model.ProjectTemplate

@Composable
fun CreateProjectScreen(
    defaultSettings: ProjectSettings,
    defaultTemplate: ProjectTemplate,
    busy: Boolean,
    onCreate: (String, ProjectTemplate, LengthUnit) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var template by rememberSaveable { mutableStateOf(defaultTemplate) }
    var units by rememberSaveable { mutableStateOf(defaultSettings.lengthUnit) }
    val validName = name.trim().isNotEmpty() && name.length <= 120

    ProjectPage(stringResource(R.string.create_new_project), onBack, modifier.testTag("create_project_screen")) {
        item {
            Text(stringResource(R.string.project_create_intro), style = MaterialTheme.typography.bodyLarge)
        }
        item {
            ProjectNameField(name, { name = it }, busy)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProjectChoice(
                    label = stringResource(R.string.project_template),
                    selected = template,
                    options = ProjectTemplate.entries,
                    optionLabel = { stringResource(projectTemplateLabel(it)) },
                    enabled = !busy,
                    onSelect = { template = it },
                    modifier = Modifier.testTag("project_template_picker"),
                )
                Text(
                    stringResource(projectTemplateDescription(template)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            ProjectChoice(
                label = stringResource(R.string.project_units),
                selected = units,
                options = LengthUnit.entries,
                optionLabel = { stringResource(lengthUnitLabel(it)) },
                enabled = !busy,
                onSelect = { units = it },
                modifier = Modifier.testTag("project_units_picker"),
            )
        }
        item {
            Button(
                onClick = { onCreate(name.trim(), template, units) },
                enabled = validName && !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("confirm_create_project"),
            ) {
                Text(stringResource(if (busy) R.string.project_creating else R.string.project_create))
            }
        }
    }
}

@Composable
fun ProjectDetailsScreen(
    document: ProjectDocument,
    dirty: Boolean,
    busy: Boolean,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onUnitsChange: (LengthUnit) -> Unit,
    onSave: () -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val project = document.project
    var name by rememberSaveable(project.id.value) { mutableStateOf(project.name) }
    var description by rememberSaveable(project.id.value) { mutableStateOf(project.description) }
    val validName = name.trim().isNotEmpty() && name.length <= 120

    ProjectPage(stringResource(R.string.project_details), onBack, modifier.testTag("project_details_screen")) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(if (dirty) R.string.project_unsaved else R.string.project_saved),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(stringResource(R.string.project_template_summary, stringResource(projectTemplateLabel(project.template))))
                    Text(
                        pluralStringResource(R.plurals.project_level_count, document.levels.size, document.levels.size) +
                            " · " + pluralStringResource(R.plurals.project_element_count, document.elements.size, document.elements.size),
                    )
                }
            }
        }
        item {
            ProjectNameField(name, { updated ->
                name = updated
                if (updated.trim().isNotEmpty() && updated.length <= 120) onNameChange(updated.trim())
            }, busy)
        }
        item {
            OutlinedTextField(
                value = description,
                onValueChange = { description = it; onDescriptionChange(it) },
                enabled = !busy,
                label = { Text(stringResource(R.string.project_description)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().testTag("project_description"),
            )
        }
        item {
            ProjectChoice(
                label = stringResource(R.string.project_units),
                selected = project.settings.lengthUnit,
                options = LengthUnit.entries,
                optionLabel = { stringResource(lengthUnitLabel(it)) },
                enabled = !busy,
                onSelect = onUnitsChange,
                modifier = Modifier.testTag("project_units_picker"),
            )
        }
        item {
            Text(
                stringResource(R.string.project_units_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { name = name.trim(); onSave() },
                    enabled = dirty && validName && !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("save_project"),
                ) { Text(stringResource(R.string.project_save)) }
                OutlinedButton(
                    onClick = onExport,
                    enabled = validName && !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("export_project"),
                ) { Text(stringResource(R.string.project_export)) }
            }
        }
        if (busy) item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        item {
            Text(stringResource(R.string.project_details_scope), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProjectNameField(name: String, onChange: (String) -> Unit, busy: Boolean) {
    val invalid = name.isNotEmpty() && (name.trim().isEmpty() || name.length > 120)
    OutlinedTextField(
        value = name,
        onValueChange = onChange,
        enabled = !busy,
        label = { Text(stringResource(R.string.project_name)) },
        singleLine = true,
        isError = invalid,
        supportingText = {
            Text(stringResource(if (invalid) R.string.project_name_invalid else R.string.project_name_hint))
        },
        modifier = Modifier.fillMaxWidth().testTag("project_name"),
    )
}

@Composable
internal fun <T> ProjectChoice(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Text(optionLabel(selected), Modifier.weight(1f))
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = { expanded = false; onSelect(option) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.project_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxSize().testTag("project_form_list"),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                content = content,
            )
        }
    }
}
