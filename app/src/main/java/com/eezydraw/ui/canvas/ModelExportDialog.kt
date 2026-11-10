package com.eezydraw.ui.canvas

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.eezydraw.core.geometry.*
import com.eezydraw.core.model.*

@Composable fun ModelExportDialog(document:ProjectDocument,initial:ModelExportOptions,onExport:(ModelExportOptions)->Unit,onDismiss:()->Unit) {
    var value by remember {mutableStateOf(initial)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Export 3D model")},text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()).testTag("model_export_options")) {
        RouteChoice("Format",value.format,ModelFormat.entries,{value=value.copy(format=it,upAxis=null,units=if(it in listOf(ModelFormat.OBJ,ModelFormat.GLTF,ModelFormat.GLB))ModelUnits.M else value.units)}){if(it==ModelFormat.IFC)"IFC4 (BIM)"else it.name}
        if(value.format==ModelFormat.OBJ)Text("OBJ and its material file are saved together as a ZIP. Extract both files into the same folder before opening the OBJ.")
        if(value.format==ModelFormat.STL)Text("STL is colourless. Use OBJ, glTF, GLB or IFC to keep colours and transparency.",Modifier.testTag("stl_colourless_note"))
        RouteChoice("Colour by",value.colourBy,ModelColourBy.entries,{value=value.copy(colourBy=it)}){it.name.lowercase().replaceFirstChar {c->c.uppercase()}}
        Row {Checkbox(value.exportDisplayMode,{value=value.copy(exportDisplayMode=it)},Modifier.testTag("export_display_mode"));Text("Export display mode (X-ray as transparent)")}
        RouteChoice("What to export",value.scope,ModelScope.entries,{value=value.copy(scope=it)}){when(it){ModelScope.PROJECT->"Whole project";ModelScope.LEVEL->"Current level only";ModelScope.SELECTED->"Selected objects only";ModelScope.VISIBLE->"Visible objects only"}}
        RouteChoice("Level",value.levelId,document.levels.map {it.id},{value=value.copy(levelId=it)}){id->document.levels.find {it.id==id}?.name ?: "Choose a level"}
        RouteChoice("Units",value.units,ModelUnits.entries,{value=value.copy(units=it)}){it.name.lowercase()}
        val axes=when(value.format){ModelFormat.IFC->listOf(ModelUpAxis.Z_UP);ModelFormat.GLTF,ModelFormat.GLB->listOf(ModelUpAxis.Y_UP);else->ModelUpAxis.entries}
        RouteChoice("Up axis",value.resolvedUpAxis(),axes,{value=value.copy(upAxis=it)}){it.label}
        if(axes.size==1)Text(if(value.format==ModelFormat.IFC)"IFC uses standard Z-up coordinates."else "glTF/GLB requires Y-up coordinates.")
        RouteChoice("Position",value.moveToOrigin,listOf(false,true),{value=value.copy(moveToOrigin=it)}){if(it)"Move model to origin"else "Keep project coordinates"}
        Text("Move to origin places the export's base centre at zero. OBJ defaults to metres; match your viewer's import units to this choice.")
        Text("Systems (none selected includes all)")
        (listOf("Power","ICT","HVAC","Plumbing","Fire","Architecture","Structure")+document.systems.map {it.name}).distinct().forEach {system->
            FilterChip(system in value.systems,{value=value.copy(systems=if(system in value.systems)value.systems-system else value.systems+system)},{Text(system)})}
        Row {Checkbox(value.walls,{value=value.copy(walls=it)});Text("Walls and rooms")}
        Row {Checkbox(value.slabs,{value=value.copy(slabs=it)});Text("Floors and slabs")}
        Row {Checkbox(value.library,{value=value.copy(library=it)});Text("Library items")}
        Row {Checkbox(value.routes,{value=value.copy(routes=it)});Text("Routes")}
        Row {Checkbox(value.fittings,{value=value.copy(fittings=it)});Text("Fittings")}
        Row {Checkbox(value.underfloor,{value=value.copy(underfloor=it)});Text("Underfloor services")}
        RouteChoice("Chases and openings",value.cuts,ModelCuts.entries,{value=value.copy(cuts=it)}){when(it){ModelCuts.CUTS->"As cuts";ModelCuts.SEPARATE->"Separate objects";ModelCuts.NONE->"Exclude cuts"}}
        Text("IFC and OBJ/STL use the selected units. glTF preserves real-world metres using node scale.")
    }},confirmButton={TextButton({onExport(value)},Modifier.testTag("model_export_save"),enabled=value.scope!=ModelScope.SELECTED || value.selected.isNotEmpty()){Text("Save model")}},dismissButton={TextButton(onDismiss){Text("Cancel")}})
}
