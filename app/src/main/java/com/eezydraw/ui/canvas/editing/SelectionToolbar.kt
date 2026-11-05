package com.eezydraw.ui.canvas.editing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eezydraw.R
import com.eezydraw.core.model.*
import com.eezydraw.ui.canvas.panels.CanvasActionButton

private enum class SelectionMenuPage { MAIN, LAYER, COLOUR, ROTATION_SNAP, LOCK, DISPLAY, ROOM_WALLS, PANELS, PANEL_STATE }

/** The five primary actions stay visible; all other selection operations live in More. */
@Composable
fun SelectionToolbar(
    mode: GizmoMode,
    canEdit: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    canPaste: Boolean,
    layers: List<Layer>,
    selectedLayer: LayerId?,
    selectionLocked: Boolean,
    rotationSnapDegrees: Int,
    onMode: (GizmoMode) -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onProperties: () -> Unit,
    onMirrorX: () -> Unit,
    onMirrorY: () -> Unit,
    onArray: () -> Unit,
    onRotate90: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onPaste: () -> Unit,
    onLayer: (LayerId) -> Unit,
    onColour: (Long) -> Unit,
    onLock: (Boolean) -> Unit,
    onRotationSnap: (Int) -> Unit,
    modifier: Modifier = Modifier,
    keepProportions: Boolean = false,
    onKeepProportions: (Boolean) -> Unit = {},
    layerLocked: Boolean = false,
    lockMode: LockMode = if (selectionLocked) LockMode.ALL else LockMode.NONE,
    onLockMode: ((LockMode) -> Unit)? = null,
    onLockLevel: (LockMode) -> Unit = {},
    display: ObjectDisplay = ObjectDisplay.NORMAL,
    onDisplay: (ObjectDisplay) -> Unit = {},
    room: Element? = null,
    selectedRoomSide: RoomSideRef? = null,
    onRoomSide: (RoomSideRef?) -> Unit = {},
    onHideRoomSide: (RoomSideRef) -> Unit = {},
    onShowRoomWalls: () -> Unit = {},
    layout: CanvasLayout = CanvasLayout(),
    onPanelVisibility: (CanvasPanelId, PanelVisibility) -> Unit = { _, _ -> },
    onHideAllPanels: (Boolean) -> Unit = {},
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf(SelectionMenuPage.MAIN) }
    var panel by rememberSaveable { mutableStateOf(CanvasPanelId.PROPERTIES) }
    fun changeLock(next:LockMode) { if (onLockMode != null) onLockMode(next) else onLock(next != LockMode.NONE) }
    fun close() { expanded = false; page = SelectionMenuPage.MAIN }
    fun act(action: () -> Unit) { close(); action() }

    Surface(
        modifier.testTag("selection_toolbar").pointerInput(Unit) { detectTapGestures(onTap = {}) },
        shape = RoundedCornerShape(12.dp), tonalElevation = 5.dp, shadowElevation = 4.dp,
    ) {
        Row {
            CanvasActionButton(
                if (mode == GizmoMode.ROTATE) Icons.AutoMirrored.Outlined.RotateRight else Icons.Outlined.OpenWith,
                stringResource(if (mode == GizmoMode.MOVE) R.string.selection_switch_rotate else R.string.selection_switch_move),
                { onMode(if (mode == GizmoMode.MOVE) GizmoMode.ROTATE else GizmoMode.MOVE) },
                Modifier.testTag("selection_mode"), selected = mode == GizmoMode.ROTATE,
            )
            CanvasActionButton(Icons.Outlined.ContentCopy, stringResource(R.string.selection_copy),
                onCopy, Modifier.testTag("selection_copy"))
            CanvasActionButton(Icons.Outlined.Delete, stringResource(R.string.selection_delete),
                onDelete, Modifier.testTag("selection_delete"), enabled = canEdit)
            CanvasActionButton(Icons.Outlined.Tune, stringResource(R.string.selection_properties),
                onProperties, Modifier.testTag("selection_properties"))
            Box {
                CanvasActionButton(Icons.Outlined.MoreHoriz, stringResource(R.string.selection_more),
                    { page = SelectionMenuPage.MAIN; expanded = !expanded }, Modifier.testTag("selection_more"))
                DropdownMenu(expanded, { close() }) {
                    if (page != SelectionMenuPage.MAIN) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.selection_menu_back)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) },
                            modifier = Modifier.testTag("selection_more_back"),
                            onClick = { page = SelectionMenuPage.MAIN },
                        )
                        HorizontalDivider()
                    }
                    when (page) {
                        SelectionMenuPage.MAIN -> {
                            SelectionMenuItem(R.string.selection_scale, "selection_scale", canEdit,
                                checked = mode == GizmoMode.SCALE) { act { onMode(GizmoMode.SCALE) } }
                            SelectionMenuItem(R.string.selection_keep_proportions,"selection_keep_proportions",canEdit,
                                checked=keepProportions) {act {onKeepProportions(!keepProportions)}}
                            SelectionMenuItem(R.string.selection_mirror_x, "selection_mirror_x", canEdit) { act(onMirrorX) }
                            SelectionMenuItem(R.string.selection_mirror_y, "selection_mirror_y", canEdit) { act(onMirrorY) }
                            SelectionMenuItem(R.string.selection_array, "selection_array", canEdit) { act(onArray) }
                            SelectionMenuItem(R.string.selection_layer, "selection_layer", canEdit && layers.isNotEmpty()) { page = SelectionMenuPage.LAYER }
                            SelectionMenuItem(R.string.selection_colour, "selection_colour", canEdit) { page = SelectionMenuPage.COLOUR }
                            SelectionMenuItem(when {layerLocked->R.string.selection_unlock_layer;selectionLocked->R.string.selection_unlock;else->R.string.selection_lock},
                                "selection_lock", true, checked = lockMode != LockMode.NONE || layerLocked) {
                                act { if(layerLocked)onLock(false) else changeLock(if(lockMode==LockMode.NONE)LockMode.ALL else LockMode.NONE) }
                            }
                            SelectionMenuItem(R.string.selection_lock_options,"selection_lock_options",true) { page=SelectionMenuPage.LOCK }
                            SelectionMenuItem(R.string.selection_display,"selection_display",true) { page=SelectionMenuPage.DISPLAY }
                            if(room?.body is ElementBody.Room) {
                                SelectionMenuItem(R.string.selection_room_wall,"selection_room_wall",true,checked=selectedRoomSide!=null) {page=SelectionMenuPage.ROOM_WALLS}
                                SelectionMenuItem(R.string.selection_hide_wall,"selection_hide_wall",selectedRoomSide!=null) {
                                    selectedRoomSide?.let {side->act {onHideRoomSide(side)}}
                                }
                                SelectionMenuItem(R.string.selection_show_room_walls,"selection_show_room_walls",true) {act(onShowRoomWalls)}
                            }
                            SelectionMenuItem(R.string.selection_visibility,"selection_visibility",true) {act {onPanelVisibility(CanvasPanelId.VISIBILITY,PanelVisibility.OPEN)}}
                            SelectionMenuItem(R.string.selection_panels,"selection_panels",true) {page=SelectionMenuPage.PANELS}
                            SelectionMenuItem(R.string.selection_rotate_90, "selection_rotate_90", canEdit) { act(onRotate90) }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.selection_rotation_snap, rotationSnapDegrees)) },
                                modifier = Modifier.testTag("selection_rotation_snap"),
                                onClick = { page = SelectionMenuPage.ROTATION_SNAP },
                            )
                            HorizontalDivider()
                            SelectionMenuItem(R.string.selection_paste, "selection_paste", canPaste) { act(onPaste) }
                            SelectionMenuItem(R.string.selection_undo, "selection_undo", canUndo) { act(onUndo) }
                            SelectionMenuItem(R.string.selection_redo, "selection_redo", canRedo) { act(onRedo) }
                        }
                        SelectionMenuPage.LAYER -> layers.forEach { layer ->
                            DropdownMenuItem(
                                text = { Text(layer.name) },
                                leadingIcon = if (layer.id == selectedLayer) ({ Icon(Icons.Outlined.Check, null) }) else null,
                                modifier = Modifier.testTag("selection_layer_${layer.id.value}"),
                                onClick = { act { onLayer(layer.id) } },
                            )
                        }
                        SelectionMenuPage.COLOUR -> selectionColours.forEach { (name, colour) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(name)) },
                                leadingIcon = {
                                    Box(Modifier.size(20.dp).background(Color(colour), CircleShape)
                                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                                },
                                modifier = Modifier.testTag("selection_colour_${colour.toString(16)}"),
                                onClick = { act { onColour(colour) } },
                            )
                        }
                        SelectionMenuPage.LOCK -> {
                            SelectionMenuItem(R.string.selection_lock_position,"selection_lock_position",!layerLocked,lockMode==LockMode.POSITION_ONLY) {act {changeLock(LockMode.POSITION_ONLY)}}
                            SelectionMenuItem(R.string.selection_lock_everything,"selection_lock_everything",!layerLocked,lockMode==LockMode.ALL) {act {changeLock(LockMode.ALL)}}
                            SelectionMenuItem(R.string.selection_unlock,"selection_unlock",true,lockMode==LockMode.NONE && !layerLocked) {act {if(layerLocked)onLock(false) else changeLock(LockMode.NONE)}}
                            HorizontalDivider()
                            SelectionMenuItem(R.string.selection_lock_all,"selection_lock_all",true) {act {onLockLevel(LockMode.ALL)}}
                            SelectionMenuItem(R.string.selection_unlock_all,"selection_unlock_all",true) {act {onLockLevel(LockMode.NONE)}}
                        }
                        SelectionMenuPage.DISPLAY -> ObjectDisplay.entries.forEach {item->
                            SelectionMenuItem(objectDisplayLabel(item),"selection_display_${item.name.lowercase()}",true,display==item) {act {onDisplay(item)}}
                        }
                        SelectionMenuPage.ROOM_WALLS -> {
                            SelectionMenuItem(R.string.selection_room_whole,"selection_room_whole",true,selectedRoomSide==null) {act {onRoomSide(null)}}
                            val body=room?.body as? ElementBody.Room
                            if(room!=null && body!=null)roomSides(room).forEach {side->
                                DropdownMenuItem(text={Text(roomSideLabel(side))},
                                    leadingIcon=if(side==selectedRoomSide)({Icon(Icons.Outlined.Check,null)})else null,
                                    modifier=Modifier.testTag("selection_room_side_${side.ringIndex ?: "outer"}_${side.edgeIndex}"),
                                    onClick={act {onRoomSide(side)}})
                            }
                        }
                        SelectionMenuPage.PANELS -> {
                            CanvasPanelId.entries.forEach {id->
                                DropdownMenuItem(text={Text(stringResource(canvasPanelLabel(id)))},
                                    modifier=Modifier.testTag("selection_panel_${id.name.lowercase()}"),
                                    onClick={panel=id;page=SelectionMenuPage.PANEL_STATE})
                            }
                            HorizontalDivider()
                            SelectionMenuItem(R.string.selection_hide_all_panels,"selection_hide_all_panels",true,layout.hideAll) {act {onHideAllPanels(!layout.hideAll)}}
                        }
                        SelectionMenuPage.PANEL_STATE -> PanelVisibility.entries.forEach {state->
                            SelectionMenuItem(panelVisibilityLabel(state),"selection_panel_state_${state.name.lowercase()}",true,layout.panel(panel).state==state) {
                                act {onPanelVisibility(panel,state)}
                            }
                        }
                        SelectionMenuPage.ROTATION_SNAP -> listOf(1, 5, 15, 45, 90).forEach { degrees ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.selection_degrees, degrees)) },
                                leadingIcon = if (degrees == rotationSnapDegrees) ({ Icon(Icons.Outlined.Check, null) }) else null,
                                modifier = Modifier.testTag("selection_snap_$degrees"),
                                onClick = { act { onRotationSnap(degrees) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionMenuItem(label: Int, tag: String, enabled: Boolean, checked: Boolean = false, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        leadingIcon = if (checked) ({ Icon(Icons.Outlined.Check, null) }) else null,
        modifier = Modifier.testTag(tag), enabled = enabled, onClick = onClick,
    )
}

private val selectionColours = listOf(
    R.string.selection_red to 0xFFD64747L,
    R.string.selection_green to 0xFF23965CL,
    R.string.selection_blue to 0xFF3976D2L,
    R.string.selection_amber to 0xFFF2A900L,
    R.string.selection_purple to 0xFF9252BDL,
    R.string.selection_cyan to 0xFF0097A7L,
    R.string.selection_black to 0xFF20252AL,
    R.string.selection_white to 0xFFF5F5F5L,
)


fun objectDisplayLabel(display:ObjectDisplay):Int=when(display) {
    ObjectDisplay.NORMAL->R.string.selection_display_normal
    ObjectDisplay.HIDDEN->R.string.selection_display_hidden
    ObjectDisplay.XRAY->R.string.selection_display_xray
    ObjectDisplay.WIREFRAME->R.string.selection_display_wireframe
}

fun canvasPanelLabel(id:CanvasPanelId):Int=when(id) {
    CanvasPanelId.DRAW->R.string.panel_draw
    CanvasPanelId.EDIT->R.string.panel_edit
    CanvasPanelId.VIEW->R.string.panel_view
    CanvasPanelId.MEASURE->R.string.panel_measure
    CanvasPanelId.LIBRARY->R.string.panel_library
    CanvasPanelId.PROPERTIES->R.string.editing_properties
    CanvasPanelId.MEASUREMENTS->R.string.panel_saved_measurements
    CanvasPanelId.VISIBILITY->R.string.panel_visibility
}

private fun panelVisibilityLabel(state:PanelVisibility):Int=when(state) {
    PanelVisibility.OPEN->R.string.selection_panel_open
    PanelVisibility.COLLAPSED->R.string.selection_panel_collapsed
    PanelVisibility.HIDDEN->R.string.selection_panel_hidden
}

fun roomSides(element:Element):List<RoomSideRef> {
    val room=element.body as? ElementBody.Room ?: return emptyList()
    return room.boundary.outer.indices.map {RoomSideRef(element.id,it)}+room.boundary.holes.flatMapIndexed {ring,points->
        points.indices.map {RoomSideRef(element.id,it,ring)}
    }
}

@Composable
fun roomSideLabel(side:RoomSideRef):String {
    val ring=side.ringIndex
    return if(ring==null)stringResource(R.string.selection_room_side,side.edgeIndex+1)
        else stringResource(R.string.selection_room_hole_side,ring+1,side.edgeIndex+1)
}
