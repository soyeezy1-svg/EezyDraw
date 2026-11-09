package com.eezydraw.ui.canvas.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.eezydraw.R
import com.eezydraw.core.model.*
import com.eezydraw.ui.canvas.editing.objectDisplayLabel
import com.eezydraw.ui.canvas.editing.roomSideLabel
import com.eezydraw.ui.canvas.editing.roomSides

/** Pure panel content. Its owning panel chrome alone controls collapse, visibility and position. */
@Composable
fun ObjectVisibilityPanel(
    document:ProjectDocument,
    onLevelVisible:(LevelId,Boolean)->Unit,
    onElementDisplay:(ElementId,ObjectDisplay)->Unit,
    onSystemVisible:(SystemId,Boolean)->Unit,
    onShowAll:()->Unit,
    onShowHidden:(Boolean)->Unit,
    onAutoXray:(Boolean)->Unit,
    onRoomSideDisplay:(RoomSideRef,ObjectDisplay)->Unit,
    modifier:Modifier=Modifier,
    embedded:Boolean=false,
    onLayerVisible:(LayerId,Boolean)->Unit={_,_->},
    onMeasurementsVisible:(Boolean)->Unit={},
    onFloorSettings:(FloorServiceSettings)->Unit={},
    onFloorSchedule:()->Unit={},
    onWallSettings:(WallServiceSettings)->Unit={},
    onWallSchedule:()->Unit={},
    onResetAllDisplay:()->Unit={},
    onShowCeilings:(Boolean)->Unit={},
    onConfigureLevelCeiling:(LevelId)->Unit={},
    onConfigureRoomCeiling:(ElementId)->Unit={},
) {
    Column((if(embedded)modifier.fillMaxSize() else modifier.width(300.dp).heightIn(max=480.dp)).verticalScroll(rememberScrollState())
        .padding(8.dp).testTag("object_visibility_panel"),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        TextButton(onShowAll,Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("visibility_show_all")) {
            Text(stringResource(R.string.visibility_show_all))
        }
        TextButton(onResetAllDisplay,Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("visibility_reset_all_display")){Text("Reset all display")}
        VisibilityOption("Show ceilings",document.project.settings.showCeilings,"visibility_ceilings",onShowCeilings)
        VisibilityOption(stringResource(R.string.visibility_show_hidden),document.project.settings.showHiddenObjects,
            "visibility_show_hidden",onShowHidden)
        Text(stringResource(R.string.visibility_show_hidden_help),style=MaterialTheme.typography.bodySmall)
        VisibilityOption(stringResource(R.string.visibility_auto_xray),document.project.settings.autoXrayFrontWalls,
            "visibility_auto_xray",onAutoXray)
        VisibilityOption(stringResource(R.string.panel_saved_measurements),document.project.settings.showMeasurements,
            "object_visibility_measurements",onMeasurementsVisible)
        VisibilityOption("Wall chases, recesses and openings",document.project.settings.wallServices.showCuts,"visibility_wall_cuts",{onWallSettings(document.project.settings.wallServices.copy(showCuts=it))})
        TextButton(onWallSchedule,Modifier.testTag("visibility_wall_schedule")){Text("Wall services schedule")}
        VisibilityOption("Show underfloor services",document.project.settings.floorServices.showServices,"visibility_underfloor",{onFloorSettings(document.project.settings.floorServices.copy(showServices=it))})
        TextButton(onFloorSchedule,Modifier.testTag("floor_schedule_open")){Text("Floor services schedule")}
        VisibilityHeading(R.string.visibility_levels)
        document.levels.forEach {level->
            VisibilityEye(level.name,level.id !in document.project.settings.hiddenLevelIds,
                "visibility_level_${level.id.value}") {onLevelVisible(level.id,it)}
            TextButton({onConfigureLevelCeiling(level.id)},Modifier.testTag("level_ceiling_${level.id.value}")){Text("Ceiling: ${level.ceiling.type.name.lowercase().replaceFirstChar(Char::uppercase)}")}
        }
        VisibilityHeading(R.string.visibility_rooms)
        document.elements.filter {it.body is ElementBody.Room}.forEachIndexed {index,element->
            VisibilityEye(elementName(document,element,index),element.objectDisplay!=ObjectDisplay.HIDDEN,
                "visibility_element_${element.id.value}",detail=stringResource(objectDisplayLabel(element.objectDisplay))) {
                onElementDisplay(element.id,if(it)ObjectDisplay.NORMAL else ObjectDisplay.HIDDEN)
            }
            TextButton({onConfigureRoomCeiling(element.id)},Modifier.testTag("room_ceiling_${element.id.value}")){Text("Ceiling: ${((element.body as ElementBody.Room).ceiling?.type ?: document.levels.find {it.id==element.levelId}?.ceiling?.type ?: CeilingType.NONE).name.lowercase().replaceFirstChar(Char::uppercase)}")}
            roomSides(element).forEach {side->
                val presentation=com.eezydraw.core.geometry.ScenePresentation(document)
                val mode=presentation.linkedWall(element,side)?.let(presentation::display) ?: presentation.sideDisplay(element,side)
                VisibilityEye(roomSideLabel(side),mode!=ObjectDisplay.HIDDEN,
                    "visibility_room_${side.roomId.value}_${side.ringIndex ?: "outer"}_${side.edgeIndex}",
                    modifier=Modifier.padding(start=16.dp),detail=stringResource(objectDisplayLabel(mode))) {
                    onRoomSideDisplay(side,if(it)ObjectDisplay.NORMAL else ObjectDisplay.HIDDEN)
                }
            }
        }
        VisibilityHeading(R.string.visibility_walls)
        document.elements.filter {it.body is ElementBody.Wall}.forEachIndexed {index,element->
            VisibilityEye(elementName(document,element,index),element.objectDisplay!=ObjectDisplay.HIDDEN,
                "visibility_element_${element.id.value}",detail=stringResource(objectDisplayLabel(element.objectDisplay))) {
                onElementDisplay(element.id,if(it)ObjectDisplay.NORMAL else ObjectDisplay.HIDDEN)
            }
        }
        VisibilityHeading(R.string.visibility_other_objects)
        document.elements.filter {it.body !is ElementBody.Room && it.body !is ElementBody.Wall}.forEachIndexed {index,element->
            VisibilityEye(elementName(document,element,index),element.objectDisplay!=ObjectDisplay.HIDDEN,
                "visibility_element_${element.id.value}",detail=stringResource(objectDisplayLabel(element.objectDisplay))) {
                onElementDisplay(element.id,if(it)ObjectDisplay.NORMAL else ObjectDisplay.HIDDEN)
            }
        }
        VisibilityHeading(R.string.panel_systems)
        document.systems.forEach {system->
            VisibilityEye(system.name,system.visible,"object_visibility_system_${system.id.value}") {onSystemVisible(system.id,it)}
        }
        VisibilityHeading(R.string.panel_layers)
        document.layers.forEach {layer->
            VisibilityEye(layer.name,layer.visible,"object_visibility_layer_${layer.id.value}") {onLayerVisible(layer.id,it)}
        }
    }
}

@Composable
private fun VisibilityHeading(label:Int) {
    HorizontalDivider(Modifier.padding(top=4.dp))
    Text(stringResource(label),Modifier.padding(top=4.dp),style=MaterialTheme.typography.titleSmall)
}

@Composable
private fun VisibilityOption(label:String,checked:Boolean,tag:String,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag(tag).toggleable(checked,role=Role.Checkbox,onValueChange=onChange),
        verticalAlignment=Alignment.CenterVertically) {
        Checkbox(checked,onCheckedChange=null)
        Text(label,Modifier.padding(start=4.dp),style=MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun VisibilityEye(label:String,visible:Boolean,tag:String,modifier:Modifier=Modifier,detail:String?=null,onChange:(Boolean)->Unit) {
    Row(modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
        IconButton({onChange(!visible)},Modifier.size(48.dp).testTag(tag)) {
            Icon(if(visible)Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                stringResource(if(visible)R.string.visibility_hide_item else R.string.visibility_show_item,label))
        }
        Column(Modifier.weight(1f)) {
            Text(label,style=MaterialTheme.typography.bodyMedium)
            detail?.let {Text(it,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
    }
}

@Composable
private fun elementName(document:ProjectDocument,element:Element,index:Int):String {
    if(element.name.isNotBlank())return element.name
    val body=element.body
    if(body is ElementBody.Component)document.libraryItems.find {it.id==body.libraryItemId}?.let {return it.name}
    val label=when(body) {
        is ElementBody.Room->R.string.visibility_room
        is ElementBody.Wall->R.string.visibility_wall
        is ElementBody.Slab->R.string.visibility_slab
        is ElementBody.Route->R.string.visibility_route
        is ElementBody.Component->R.string.visibility_component
        is ElementBody.DrawingShape->R.string.visibility_shape
    }
    return stringResource(R.string.visibility_named_item,stringResource(label),index+1)
}
