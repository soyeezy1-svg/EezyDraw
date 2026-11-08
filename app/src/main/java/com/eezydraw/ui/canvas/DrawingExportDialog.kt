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
import com.eezydraw.storage.*

@Composable fun DrawingExportDialog(request:ViewExportRequest,initial:DrawingExportOptions,onExport:(ViewExportRequest,DrawingExportOptions)->Unit,onDismiss:()->Unit) {
    var value by remember {mutableStateOf(initial)}
    var level by remember {mutableStateOf(request.frame.levelId?.let(::LevelId) ?: request.document.levels.firstOrNull()?.id)}
    var scaleText by remember {mutableStateOf(initial.scale.toString())}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Export 2D drawing")},text={Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()).testTag("drawing_export_options")) {
        RouteChoice("Format",value.format,DrawingFormat.entries,{value=value.copy(format=it,scope=if(it==DrawingFormat.PNG)DrawingScope.VIEW else if(it==DrawingFormat.DXF)DrawingScope.LEVEL else value.scope)}){it.name}
        val scopes=when(value.format){DrawingFormat.PNG->listOf(DrawingScope.VIEW);DrawingFormat.DXF->listOf(DrawingScope.LEVEL,DrawingScope.SHEET);else->DrawingScope.entries}
        RouteChoice("Drawing",value.scope,scopes,{value=value.copy(scope=it)}){when(it){DrawingScope.VIEW->"Current view";DrawingScope.LEVEL->"Current level (fit)";DrawingScope.SHEET->"Paper size and scale"}}
        if(value.scope!=DrawingScope.VIEW)RouteChoice("Level",level,request.document.levels.map {it.id},{level=it}){id->request.document.levels.find {it.id==id}?.name ?: "Choose a level"}
        if(value.format==DrawingFormat.DXF) {
            RouteChoice("Units",value.units,ModelUnits.entries,{value=value.copy(units=it)}){it.name.lowercase()}
            Text("DXF is a plan in model coordinates. Underlays use a bounded embedded preview; drawing lines remain vector.")
        }
        if(value.format==DrawingFormat.PDF || value.scope==DrawingScope.SHEET) {
            RouteChoice("Paper",value.paper,DrawingPaper.entries,{value=value.copy(paper=it)}){it.name}
            FilterChip(value.landscape,{value=value.copy(landscape=!value.landscape)},{Text(if(value.landscape)"Landscape"else "Portrait")})
            if(value.scope==DrawingScope.SHEET) {
                RouteChoice("Scale",value.scale,listOf(1.0,10.0,20.0,25.0,50.0,100.0,200.0,250.0,500.0),{value=value.copy(scale=it);scaleText=it.toString()}){"1:${it.toInt()}"}
                OutlinedTextField(scaleText,{scaleText=it;it.toDoubleOrNull()?.takeIf {n->n.isFinite() && n in 1.0..100000.0}?.let {n->value=value.copy(scale=n)}},label={Text("Custom scale denominator (1:x)")},isError=scaleText.toDoubleOrNull()?.let {it.isFinite() && it in 1.0..100000.0}!=true,modifier=Modifier.testTag("drawing_export_scale"))
                Text("The drawing is centred and clipped to the chosen sheet at this scale.")
            }
        }
        Row {Checkbox(value.includeUnderlay,{value=value.copy(includeUnderlay=it)},Modifier.testTag("drawing_export_underlay"));Text("Include underlay")}
        if(value.format==DrawingFormat.PDF) {
            Row {Checkbox(value.titleBlock,{value=value.copy(titleBlock=it)});Text("Title block")}
            Row {Checkbox(value.northArrow,{value=value.copy(northArrow=it)});Text("North arrow (plan)")}
            Row {Checkbox(value.scaleBar,{value=value.copy(scaleBar=it)});Text("Scale bar (uniform views)")}
            Text("Visible dimensions and labels are included. Hidden objects are excluded.")
        }
    }},confirmButton={TextButton({val selected=request.copy(frame=request.frame.copy(levelId=if(value.scope==DrawingScope.VIEW)request.frame.levelId else level?.value));onExport(selected,value)},Modifier.testTag("drawing_export_save"),enabled=(value.scope==DrawingScope.VIEW || level!=null) && (value.scope!=DrawingScope.SHEET || scaleText.toDoubleOrNull()?.let {it.isFinite() && it in 1.0..100000.0}==true)){Text("Save drawing")}},dismissButton={TextButton(onDismiss){Text("Cancel")}})
}
