package com.eezydraw.ui.canvas.panels

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eezydraw.core.editing.*
import java.util.UUID
import com.eezydraw.R
import com.eezydraw.core.model.*
import com.eezydraw.ui.canvas.coordinate
import com.eezydraw.ui.canvas.editing.editingUnitLabel

/** Lazy, vector-only catalogue. Search and folder state never change the enclosing panel state. */
@Composable
fun LibraryPanel(
    items: List<LibraryItem>,
    onInsert: (LibraryItemId) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
    state: LibraryState = LibraryState(),
    onStateChange: (LibraryState) -> Unit = {},
    units: LengthUnit = LengthUnit.MILLIMETRES,
    decimalPlaces: Int = 0,
    activeItemId: LibraryItemId? = null,
    onSaveItem: (LibraryItem) -> Unit = {},
    onRestoreItem: (LibraryItemId) -> Unit = {},
    onDeleteItem: (LibraryItemId) -> Unit = {},
    onDuplicateItem: (LibraryItemId) -> Unit = {},
    onFromDrawing: () -> LibraryItem? = { null },
    onImportLibrary: () -> Unit = {},
    onExportLibrary: () -> Unit = {},
    onUndoLibrary: () -> Unit = {},
    onRedoLibrary: () -> Unit = {},
    canUndoLibrary: Boolean = false,
    canRedoLibrary: Boolean = false,
    authoringEnabled: Boolean = true,
    projectItemIds: Set<LibraryItemId> = items.mapTo(HashSet()) {it.id},
) {
    var editingItem by remember { mutableStateOf<LibraryItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }
    var drawingError by remember { mutableStateOf(false) }
    editingItem?.let { item -> LibraryItemEditor(item,creating,onSaveItem,{editingItem=null}) }
    if(drawingError) AlertDialog(onDismissRequest={drawingError=false},title={Text("Create from drawing")},
        text={Text("Use the drawing tools to draw your item, select its shapes, then choose From selected drawing.")},
        confirmButton={TextButton({drawingError=false}) {Text("OK")}})
    val editItem: (LibraryItem) -> Unit = { creating=false;editingItem=it }
    var query by rememberSaveable { mutableStateOf("") }
    var collapsedFolders by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val catalog = remember(items) { (BuiltInLibrary.items + items).associateBy { it.id }.values.toList() }
    val matches = remember(catalog, query) { librarySearch(catalog, query) }
    val matchedIds = remember(matches) { matches.mapTo(HashSet()) { it.id } }
    val projectIds = projectItemIds
    val byId = remember(catalog) { catalog.associateBy { it.id } }
    val folders = remember(matches, state.favoriteIds, state.recentIds, projectIds) {
        listOf(
            LibraryFolder("favorites", null, state.favoriteIds.mapNotNull { byId[it] }.filter { it.id in matchedIds }.sortedBy { it.name }),
            LibraryFolder("recent", null, state.recentIds.mapNotNull { byId[it] }.filter { it.id in matchedIds }),
        ) + matches.groupBy { it.category }.map { (category, definitions) ->
            // Keep project-specific definitions immediately accessible alongside the built-ins.
            LibraryFolder("category_$category", category,
                definitions.sortedWith(compareBy<LibraryItem> { it.id !in projectIds }.thenBy { it.name }))
        }
    }
    val content: @Composable () -> Unit = {
        Column(Modifier.width(320.dp).heightIn(max = 540.dp).testTag("library_panel")) {
            if (!embedded) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.panel_library), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    CanvasActionButton(Icons.Outlined.Close, stringResource(R.string.close), onDismiss, Modifier.testTag("library_close"))
                }
                HorizontalDivider()
            }
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).testTag("library_search"),
                label = { Text(stringResource(R.string.library_search)) }, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }, Modifier.testTag("library_search_clear")) {
                    Icon(Icons.Outlined.Clear, stringResource(R.string.library_clear_search))
                } },
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.library_result_count,matches.size,matches.size), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Box {
                    IconButton({managing=true},modifier=Modifier.testTag("library_manage")) {Icon(Icons.Outlined.MoreVert,"Manage library")}
                    DropdownMenu(managing,{managing=false}) {
                        DropdownMenuItem(text={Text("Create custom item")},enabled=authoringEnabled,onClick={managing=false;creating=true;editingItem=createLibraryItem(LibraryItemId(UUID.randomUUID().toString()),LibraryBaseShape.BOX)},modifier=Modifier.testTag("library_create"))
                        DropdownMenuItem(text={Text("From selected drawing")},enabled=authoringEnabled,onClick={managing=false;val item=onFromDrawing();if(item==null)drawingError=true else {creating=true;editingItem=item}},modifier=Modifier.testTag("library_from_drawing"))
                        DropdownMenuItem(text={Text("Import library")},onClick={managing=false;onImportLibrary()},modifier=Modifier.testTag("library_import"))
                        DropdownMenuItem(text={Text("Export library")},enabled=authoringEnabled,onClick={managing=false;onExportLibrary()},modifier=Modifier.testTag("library_export"))
                        DropdownMenuItem(text={Text("Undo library edit")},enabled=canUndoLibrary,onClick={managing=false;onUndoLibrary()},modifier=Modifier.testTag("library_undo"))
                        DropdownMenuItem(text={Text("Redo library edit")},enabled=canRedoLibrary,onClick={managing=false;onRedoLibrary()},modifier=Modifier.testTag("library_redo"))
                    }
                }
                IconToggleButton(state.viewMode == LibraryViewMode.GRID,
                    { onStateChange(state.copy(viewMode = LibraryViewMode.GRID)) }, Modifier.size(48.dp).testTag("library_grid")) {
                    Icon(Icons.Outlined.GridView, stringResource(R.string.library_grid))
                }
                IconToggleButton(state.viewMode == LibraryViewMode.LIST,
                    { onStateChange(state.copy(viewMode = LibraryViewMode.LIST)) }, Modifier.size(48.dp).testTag("library_list")) {
                    Icon(Icons.AutoMirrored.Outlined.ViewList, stringResource(R.string.library_list))
                }
            }
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("library_items"), contentPadding = PaddingValues(bottom = 10.dp)) {
                if (matches.isEmpty()) item("no_results") {
                    Text(stringResource(R.string.library_no_results), Modifier.padding(12.dp).testTag("library_no_results"), style = MaterialTheme.typography.bodyMedium)
                }
                folders.forEach { folder ->
                    item("header_${folder.key}") {
                        val expanded = folder.key !in collapsedFolders
                        val title = folder.title ?: stringResource(if (folder.key == "favorites") R.string.library_favorites else R.string.library_recent)
                        LibraryFolderHeader(folder, title, expanded) {
                            collapsedFolders = if (expanded) collapsedFolders + folder.key else collapsedFolders - folder.key
                        }
                    }
                    if (folder.key !in collapsedFolders) {
                        if (state.viewMode == LibraryViewMode.LIST) {
                            items(folder.items, key = { "${folder.key}_${it.id.value}" }, contentType = { "library_list_item" }) { item ->
                                LibraryEntry(item, folder.key, item.id in state.favoriteIds, units, decimalPlaces, false, item.id == activeItemId,
                                    onEdit={editItem(item)},onRestore={onRestoreItem(item.id)},onDelete={onDeleteItem(item.id)},onDuplicate={onDuplicateItem(item.id)},authoringEnabled=authoringEnabled,
                                    onInsert = { onInsert(item.id) }, onFavorite = { onStateChange(state.toggledFavorite(item.id)) },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp))
                            }
                        } else {
                            items(folder.items.chunked(2), key = { "${folder.key}_${it.first().id.value}" }, contentType = { "library_grid_row" }) { row ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    row.forEach { item ->
                                        LibraryEntry(item, folder.key, item.id in state.favoriteIds, units, decimalPlaces, true, item.id == activeItemId,
                                            onEdit={editItem(item)},onRestore={onRestoreItem(item.id)},onDelete={onDeleteItem(item.id)},onDuplicate={onDuplicateItem(item.id)},authoringEnabled=authoringEnabled,
                                    onInsert = { onInsert(item.id) }, onFavorite = { onStateChange(state.toggledFavorite(item.id)) }, modifier = Modifier.weight(1f))
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (embedded) Box(modifier) { content() } else Surface(
        modifier.pointerInput(Unit) { detectTapGestures(onTap = {}) },
        shape = RoundedCornerShape(16.dp), tonalElevation = 5.dp, shadowElevation = 4.dp,
    ) { content() }
}

private data class LibraryFolder(val key: String, val title: String?, val items: List<LibraryItem>)

@Composable
private fun LibraryFolderHeader(folder: LibraryFolder, title: String, expanded: Boolean, onToggle: () -> Unit) {
    Surface(onClick = onToggle, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("library_folder_${folder.key}"),
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon: ImageVector = when (folder.key) {
                "favorites" -> Icons.Outlined.StarOutline
                "recent" -> Icons.Outlined.History
                else -> when (folder.items.firstOrNull()?.discipline) {
                    Discipline.ELECTRICAL -> Icons.Outlined.ElectricalServices
                    Discipline.ICT -> Icons.Outlined.Router
                    Discipline.HVAC -> Icons.Outlined.AcUnit
                    Discipline.PLUMBING -> Icons.Outlined.Plumbing
                    Discipline.FIRE_PROTECTION -> Icons.Outlined.LocalFireDepartment
                    Discipline.ARCHITECTURE -> Icons.Outlined.Apartment
                    Discipline.STRUCTURE -> Icons.Outlined.Foundation
                    Discipline.CIVIL -> Icons.Outlined.Terrain
                    else -> Icons.Outlined.Folder
                }
            }
            Icon(icon, null, Modifier.size(20.dp))
            Text(title, Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.titleSmall)
            Text(folder.items.size.toString(), style = MaterialTheme.typography.labelMedium)
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                stringResource(if (expanded) R.string.library_collapse_folder else R.string.library_expand_folder, title), Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun LibraryEntry(item: LibraryItem, folder: String, favorite: Boolean, units: LengthUnit, decimalPlaces: Int, grid: Boolean, active: Boolean,
    onInsert: () -> Unit, onFavorite: () -> Unit, onEdit:()->Unit,onRestore:()->Unit,onDelete:()->Unit,onDuplicate:()->Unit,authoringEnabled:Boolean, modifier: Modifier = Modifier) {
    val tagPrefix = if (folder.startsWith("category_")) "library" else "library_$folder"
    val dimensions = remember(item.defaultSize, units, decimalPlaces) {
        listOf(item.defaultSize.width, item.defaultSize.depth, item.defaultSize.height)
            .joinToString(" × ") { coordinate(it, units, decimalPlaces) }
    }
    val unit = stringResource(editingUnitLabel(units))
    OutlinedCard(onClick = onInsert,
        modifier = modifier.testTag("${tagPrefix}_insert_${item.id.value}").semantics { selected = active },
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        if (grid) {
            Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    LibraryThumbnail(item, Modifier.weight(1f).height(48.dp))
                    FavoriteButton(item, favorite, onFavorite, tagPrefix)
                    LibraryItemMenu(item,onEdit,onRestore,onDelete,onDuplicate,authoringEnabled,tagPrefix)
                }
                Text(item.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
                Text("$dimensions $unit", style = MaterialTheme.typography.labelSmall, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                LibraryThumbnail(item, Modifier.size(48.dp))
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(item.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("$dimensions $unit", style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                FavoriteButton(item, favorite, onFavorite, tagPrefix)
                    LibraryItemMenu(item,onEdit,onRestore,onDelete,onDuplicate,authoringEnabled,tagPrefix)
            }
        }
    }
}

@Composable
private fun FavoriteButton(item: LibraryItem, favorite: Boolean, onFavorite: () -> Unit, prefix: String) {
    IconToggleButton(favorite, { onFavorite() }, Modifier.size(48.dp).testTag("${prefix}_favorite_${item.id.value}")) {
        Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarOutline,
            stringResource(if (favorite) R.string.library_remove_favorite else R.string.library_add_favorite, item.name),
            tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LibraryItemMenu(item:LibraryItem,onEdit:()->Unit,onRestore:()->Unit,onDelete:()->Unit,onDuplicate:()->Unit,enabled:Boolean,prefix:String) {
    var expanded by remember(item.id) {mutableStateOf(false)}
    Box {
        IconButton({expanded=true},enabled=enabled,modifier=Modifier.size(40.dp).testTag("${prefix}_edit_${item.id.value}")) {Icon(Icons.Outlined.MoreVert,"Edit ${item.name}")}
        DropdownMenu(expanded,{expanded=false}) {
            DropdownMenuItem(text={Text("Edit item")},onClick={expanded=false;onEdit()},modifier=Modifier.testTag("library_edit_item"))
            DropdownMenuItem(text={Text("Duplicate")},onClick={expanded=false;onDuplicate()},modifier=Modifier.testTag("library_duplicate_item"))
            if(BuiltInLibrary.find(item.id)!=null) DropdownMenuItem(text={Text("Restore default")},onClick={expanded=false;onRestore()},modifier=Modifier.testTag("library_restore_item"))
            else DropdownMenuItem(text={Text("Delete item")},onClick={expanded=false;onDelete()},modifier=Modifier.testTag("library_delete_item"))
        }
    }
}
