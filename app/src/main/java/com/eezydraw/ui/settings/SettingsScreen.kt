package com.eezydraw.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.eezydraw.R
import com.eezydraw.core.model.AngleFormat
import com.eezydraw.core.model.AppSettings
import com.eezydraw.core.model.ElevationReference
import com.eezydraw.core.model.InputMode
import com.eezydraw.core.model.LengthUnit
import com.eezydraw.core.model.ProjectTemplate
import com.eezydraw.core.model.RenderQuality
import com.eezydraw.core.model.RouteKind
import com.eezydraw.core.model.SnapMode
import com.eezydraw.core.model.ThemeMode

/** Edits defaults without mutating existing project documents or dropping incomplete input. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSave: (AppSettings) -> Unit,
    onBack: () -> Unit,
    onChooseSaveLocation: () -> Unit,
    onResetSaveLocation: () -> Unit,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable(stateSaver = SettingsDraft.saver) { mutableStateOf(SettingsDraft.from(settings)) }
    var baseline by rememberSaveable(stateSaver = SettingsDraft.saver) { mutableStateOf(SettingsDraft.from(settings)) }
    var discard by rememberSaveable { mutableStateOf(false) }
    // Folder pickers leave this composition. Merge only the returned folder into the existing draft.
    LaunchedEffect(settings.saveLocationUri) {
        draft = draft.copy(settings = draft.settings.copy(saveLocationUri = settings.saveLocationUri))
        baseline = baseline.copy(settings = baseline.settings.copy(saveLocationUri = settings.saveLocationUri))
    }
    val dirty = draft != baseline
    val errors = draft.errors()
    val back = { if (dirty) discard = true else onBack() }
    BackHandler(onBack = back)
    val current = draft.settings
    val defaults = current.projectDefaults
    val unit = stringResource(unitLabel(defaults.lengthUnit))
    val input: (String, String) -> Unit = { key, text -> draft = draft.input(key, text) }
    val update: (AppSettings) -> Unit = { draft = draft.copy(settings = it) }

    Scaffold(
        modifier = modifier.testTag("settings_screen"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = back, modifier = Modifier.testTag("settings_back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.settings_back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = { draft.toSettings()?.let(onSave) },
                        enabled = errors.isEmpty(),
                        modifier = Modifier.testTag("settings_save"),
                    ) { Text(stringResource(R.string.settings_save_action)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).widthIn(max = 900.dp).fillMaxWidth().testTag("settings_list"),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SectionHeading(stringResource(R.string.settings_storage))
                Text(stringResource(R.string.settings_storage_description), style = MaterialTheme.typography.bodyMedium)
            }
            item {
                val folder = current.saveLocationUri?.toUri()?.lastPathSegment?.substringAfter(':')
                    ?.takeIf(String::isNotBlank)
                Text(
                    if (current.saveLocationUri == null) stringResource(R.string.settings_internal_storage)
                    else stringResource(R.string.settings_selected_folder, folder ?: stringResource(R.string.settings_custom_folder)),
                )
                OutlinedButton(onChooseSaveLocation, Modifier.testTag("settings_choose_folder")) {
                    Text(stringResource(R.string.settings_choose_folder))
                }
                if (current.saveLocationUri != null) {
                    TextButton(onResetSaveLocation, Modifier.testTag("settings_reset_folder")) {
                        Text(stringResource(R.string.settings_reset_folder))
                    }
                }
            }
            item {
                SettingSwitch(
                    stringResource(R.string.settings_backup), current.backupEnabled,
                    { update(current.copy(backupEnabled = it)) }, "settings_backup",
                )
                Text(stringResource(R.string.settings_backup_description), style = MaterialTheme.typography.bodySmall)
            }
            item {
                NumericField("autosave", R.string.settings_autosave, draft, errors, input, integer = true,
                    help = R.string.settings_autosave_help)
            }
            item { SectionHeading(stringResource(R.string.settings_project_defaults)) }
            item {
                ChoiceField(
                    stringResource(R.string.settings_units), defaults.lengthUnit,
                    LengthUnit.entries.map { it to stringResource(unitLabel(it)) },
                    { draft = draft.changeUnits(it) }, "settings_units",
                )
            }
            item {
                ChoiceField(
                    stringResource(R.string.settings_angle), defaults.angleFormat,
                    AngleFormat.entries.map { it to stringResource(if (it == AngleFormat.DEGREES) R.string.settings_degrees else R.string.settings_radians) },
                    { update(current.copy(projectDefaults = defaults.copy(angleFormat = it))) }, "settings_angle",
                )
            }
            item { NumericField("precision", R.string.settings_precision, draft, errors, input, integer = true, help = R.string.settings_precision_help) }
            item {
                ChoiceField(
                    stringResource(R.string.settings_default_template), current.defaultTemplate,
                    ProjectTemplate.entries.map { it to stringResource(templateLabel(it)) },
                    { update(current.copy(defaultTemplate = it)) }, "settings_template",
                )
            }
            item {
                NumericField("line_weight", R.string.settings_line_weight, draft, errors, input)
            }
            item { SectionHeading(stringResource(R.string.settings_snapping)) }
            item {
                SettingSwitch(
                    stringResource(R.string.settings_snapping_enabled), defaults.snap.enabled,
                    { update(current.copy(projectDefaults = defaults.copy(snap = defaults.snap.copy(enabled = it)))) },
                    "settings_snap_enabled",
                )
            }
            SnapMode.entries.forEach { mode ->
                item(key = "snap_${mode.name}") {
                    SettingCheckbox(
                        stringResource(snapLabel(mode)), mode in defaults.snap.modes,
                        { checked ->
                            val modes = if (checked) defaults.snap.modes + mode else defaults.snap.modes - mode
                            update(current.copy(projectDefaults = defaults.copy(snap = defaults.snap.copy(modes = modes))))
                        }, "settings_snap_${mode.name}",
                    )
                }
            }
            item {
                NumericField("grid", R.string.settings_grid, draft, errors, input, label = stringResource(R.string.settings_grid, unit))
            }
            item {
                NumericField("tolerance", R.string.settings_tolerance, draft, errors, input, label = stringResource(R.string.settings_tolerance, unit))
            }
            item {
                SectionHeading(stringResource(R.string.settings_service_heights))
                Text(stringResource(R.string.settings_service_heights_help), style = MaterialTheme.typography.bodySmall)
            }
            RouteKind.entries.forEach { kind ->
                item(key = "height_${kind.name}") {
                    NumericField(
                        "height_${kind.name}", R.string.settings_height, draft, errors, input,
                        label = stringResource(R.string.settings_height, stringResource(routeLabel(kind)), unit),
                    )
                    ChoiceField(
                        stringResource(R.string.settings_elevation_reference),
                        defaults.defaultRouteElevations[kind]?.reference ?: ElevationReference.ABOVE_FINISHED_FLOOR,
                        ElevationReference.entries.map { it to stringResource(referenceLabel(it)) },
                        { draft = draft.routeReference(kind, it) }, "settings_reference_${kind.name}",
                    )
                }
            }
            item { SectionHeading(stringResource(R.string.settings_appearance_input)) }
            item {
                ChoiceField(
                    stringResource(R.string.appearance), current.theme,
                    ThemeMode.entries.map { it to stringResource(when (it) {
                        ThemeMode.SYSTEM -> R.string.theme_system
                        ThemeMode.LIGHT -> R.string.theme_light
                        ThemeMode.DARK -> R.string.theme_dark
                    }) },
                    { update(current.copy(theme = it)) }, "settings_theme",
                )
            }
            item {
                Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.settings_english))
            }
            item {
                ChoiceField(
                    stringResource(R.string.settings_input_mode), current.inputMode,
                    InputMode.entries.map { it to stringResource(if (it == InputMode.TOUCH) R.string.settings_touch else R.string.settings_stylus) },
                    { update(current.copy(inputMode = it)) }, "settings_input",
                )
            }
            item { NumericField("gesture", R.string.settings_gesture, draft, errors, input, help = R.string.settings_gesture_help) }
            item {
                ChoiceField(
                    stringResource(R.string.settings_quality), current.renderQuality,
                    RenderQuality.entries.map { it to stringResource(when (it) {
                        RenderQuality.PERFORMANCE -> R.string.settings_performance
                        RenderQuality.BALANCED -> R.string.settings_balanced
                        RenderQuality.QUALITY -> R.string.settings_quality_high
                    }) },
                    { update(current.copy(renderQuality = it)) }, "settings_quality",
                )
                Text(stringResource(R.string.settings_editor_defaults_help), style = MaterialTheme.typography.bodySmall)
            }
            item { NumericField("undo", R.string.settings_undo, draft, errors, input, integer = true, help = R.string.settings_undo_help) }
            item { SectionHeading(stringResource(R.string.settings_exports)) }
            item {
                Text(stringResource(R.string.settings_export_format), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.settings_export_eezydraw))
                Text(stringResource(R.string.settings_export_formats_help), style = MaterialTheme.typography.bodySmall)
            }
            item {
                SettingCheckbox(
                    stringResource(R.string.settings_export_underlays), current.exportIncludeUnderlays,
                    { update(current.copy(exportIncludeUnderlays = it)) }, "settings_export_underlays",
                )
            }
            item {
                OutlinedTextField(
                    value = draft.inputs.getValue("file_naming"), onValueChange = { input("file_naming", it) },
                    modifier = Modifier.fillMaxWidth().testTag("settings_file_naming"),
                    label = { Text(stringResource(R.string.settings_file_naming)) },
                    isError = "file_naming" in errors,
                    supportingText = { Text(stringResource(if ("file_naming" in errors) R.string.settings_file_naming_error else R.string.settings_file_naming_help)) },
                    singleLine = true,
                )
            }
            item {
                SectionHeading(stringResource(R.string.settings_permissions))
                Text(stringResource(R.string.settings_permissions_help), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onOpenPermissions, Modifier.testTag("settings_permissions")) {
                    Text(stringResource(R.string.settings_open_permissions))
                }
            }
            item {
                Button(
                    onClick = { draft.toSettings()?.let(onSave) }, enabled = errors.isEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_save)) }
            }
        }
    }
    if (discard) {
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text(stringResource(R.string.settings_discard_title)) },
            text = { Text(stringResource(R.string.settings_discard_message)) },
            confirmButton = {
                TextButton(onClick = onBack, modifier = Modifier.testTag("settings_discard")) {
                    Text(stringResource(R.string.settings_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { discard = false }) { Text(stringResource(R.string.settings_keep_editing)) }
            },
        )
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun NumericField(
    key: String,
    labelRes: Int,
    draft: SettingsDraft,
    errors: Set<String>,
    onInput: (String, String) -> Unit,
    integer: Boolean = false,
    help: Int? = null,
    label: String = stringResource(labelRes),
) {
    OutlinedTextField(
        value = draft.inputs.getValue(key), onValueChange = { onInput(key, it) },
        modifier = Modifier.fillMaxWidth().testTag("settings_$key"),
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal),
        isError = key in errors,
        supportingText = {
            if (key in errors) Text(stringResource(when (key) {
                "precision" -> R.string.settings_precision_help
                "autosave" -> R.string.settings_autosave_help
                "undo" -> R.string.settings_undo_help
                "gesture" -> R.string.settings_gesture_help
                else -> R.string.settings_number_error
            }))
            else if (help != null) Text(stringResource(help))
        },
    )
}

@Composable
private fun <T> ChoiceField(label: String, selected: T, options: List<Pair<T, String>>, onSelect: (T) -> Unit, tag: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().testTag(tag)) {
                Text(options.first { it.first == selected }.second, Modifier.weight(1f))
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onSelect(value) })
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit, tag: String) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChecked).testTag(tag).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SettingCheckbox(label: String, checked: Boolean, onChecked: (Boolean) -> Unit, tag: String) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChecked).testTag(tag).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}

private fun unitLabel(value: LengthUnit): Int = when (value) {
    LengthUnit.MILLIMETRES -> R.string.settings_unit_mm
    LengthUnit.CENTIMETRES -> R.string.settings_unit_cm
    LengthUnit.METRES -> R.string.settings_unit_m
    LengthUnit.INCHES -> R.string.settings_unit_in
    LengthUnit.FEET -> R.string.settings_unit_ft
}
private fun templateLabel(value: ProjectTemplate): Int = when (value) {
    ProjectTemplate.EMPTY -> R.string.settings_template_empty
    ProjectTemplate.OFFICE -> R.string.settings_template_office
    ProjectTemplate.RESIDENTIAL -> R.string.settings_template_residential
    ProjectTemplate.DATA_CENTER -> R.string.settings_template_data_center
}
private fun snapLabel(value: SnapMode): Int = when (value) {
    SnapMode.GRID -> R.string.settings_snap_grid
    SnapMode.ENDPOINT -> R.string.settings_snap_endpoint
    SnapMode.MIDPOINT -> R.string.settings_snap_midpoint
    SnapMode.INTERSECTION -> R.string.settings_snap_intersection
    SnapMode.PERPENDICULAR -> R.string.settings_snap_perpendicular
    SnapMode.ORTHO -> R.string.settings_snap_ortho
}
private fun routeLabel(value: RouteKind): Int = when (value) {
    RouteKind.TRUNKING -> R.string.settings_route_trunking
    RouteKind.CABLE_TRAY -> R.string.settings_route_tray
    RouteKind.CABLE_LADDER -> R.string.settings_route_ladder
    RouteKind.CABLE_BASKET -> R.string.settings_route_basket
    RouteKind.CONDUIT -> R.string.settings_route_conduit
    RouteKind.DUCT -> R.string.settings_route_duct
    RouteKind.PIPE -> R.string.settings_route_pipe
}
private fun referenceLabel(value: ElevationReference): Int = when (value) {
    ElevationReference.ABOVE_FINISHED_FLOOR -> R.string.settings_reference_floor
    ElevationReference.ABOVE_CEILING -> R.string.settings_reference_ceiling
    ElevationReference.PROJECT_DATUM -> R.string.settings_reference_datum
}
