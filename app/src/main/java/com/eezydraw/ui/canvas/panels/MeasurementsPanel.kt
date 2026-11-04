package com.eezydraw.ui.canvas.panels

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import com.eezydraw.R
import com.eezydraw.core.model.*
import com.eezydraw.ui.canvas.coordinate
import com.eezydraw.ui.canvas.unitFactor
import java.util.Locale
import kotlin.math.hypot

@Composable
fun MeasurementsPanel(document: ProjectDocument,onVisibility:(Boolean)->Unit,
    onEdit:(ElementId,Point3,Point3,Point3,String,Boolean,Boolean)->Unit,onDelete:(Set<ElementId>)->Unit,
    onDismiss:()->Unit,modifier:Modifier=Modifier) {
    var editing by remember { mutableStateOf<ElementId?>(null) }
    Surface(modifier.width(320.dp).pointerInput(Unit){detectTapGestures(onTap={})},tonalElevation=5.dp,shadowElevation=4.dp) {
        Column(Modifier.fillMaxHeight().testTag("measurements_panel")) {
            Row(Modifier.fillMaxWidth().padding(start=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(stringResource(R.string.canvas_measurements),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                IconButton(onDismiss) { Icon(Icons.Outlined.Close,stringResource(R.string.close)) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(stringResource(R.string.canvas_show_measurements),Modifier.weight(1f))
                Switch(document.project.settings.showMeasurements,onVisibility,Modifier.testTag("measurements_visible"))
            }
            HorizontalDivider()
            val entries=remember(document) {measurementElements(document).mapNotNull {element->resolveMeasurement(document,element)?.let {element to it}}}
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if(entries.isEmpty()) Text(stringResource(R.string.canvas_measurements_empty))
                entries.forEach { (element,summary) ->
                    Card(Modifier.fillMaxWidth().testTag("measurement_${element.id.value}")) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text(summary.name,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
                                IconButton({editing=element.id},Modifier.testTag("measurement_edit_${element.id.value}")) { Icon(Icons.Outlined.Edit,stringResource(R.string.canvas_edit_measurement)) }
                                IconButton({onDelete(setOf(element.id))},Modifier.testTag("measurement_delete_${element.id.value}")) { Icon(Icons.Outlined.Delete,stringResource(R.string.editing_delete)) }
                            }
                            measurementText(summary,document.project.settings).forEach { Text(it,style=MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
    }
    editing?.let { id -> document.elements.find { it.id==id }?.let { element ->
        resolveMeasurement(document,element)?.let { summary ->
            MeasurementEditor(document,element,summary,onApply={a,b,label,name,coords,affl->
                onEdit(id,a,b,label,name,coords,affl);editing=null
            },onDismiss={editing=null})
        }
    } }
}

@Composable
private fun MeasurementEditor(document:ProjectDocument,element:Element,summary:MeasurementSummary,
    onApply:(Point3,Point3,Point3,String,Boolean,Boolean)->Unit,onDismiss:()->Unit) {
    val data=((element.body as ElementBody.DrawingShape).shape as Shape.Dimension).measurement!!
    val settings=document.project.settings
    val factor=unitFactor(settings.lengthUnit)
    val source=listOf(summary.start.x,summary.start.y,summary.start.z,summary.end.x,summary.end.y,summary.end.z,
        summary.labelPoint.x,summary.labelPoint.y,summary.labelPoint.z)
    val initial=remember(source,settings.lengthUnit,settings.decimalPlaces) { source.map { String.format(Locale.ROOT,"%.${settings.decimalPlaces}f",it*factor) } }
    var fields by rememberSaveable(element.id.value,stateSaver=listSaver(save={it},restore={it})) { mutableStateOf(initial) }
    var name by rememberSaveable(element.id.value) { mutableStateOf(summary.name) }
    var coordinates by rememberSaveable(element.id.value) { mutableStateOf(data.showCoordinates) }
    var affl by rememberSaveable(element.id.value) { mutableStateOf(data.showHeightAboveFloor) }
    val parsed=fields.mapIndexed { index,text -> if(text==initial[index])source[index] else text.toDoubleOrNull()?.div(factor)?.takeIf(Double::isFinite) }
    val valid=parsed.all { it!=null } && name.isNotBlank() && name.length<=120 &&
        if(parsed.all {it!=null}) hypot(hypot(parsed[3]!!-parsed[0]!!,parsed[4]!!-parsed[1]!!),parsed[5]!!-parsed[2]!!)>1e-9 else false
    AlertDialog(onDismissRequest=onDismiss,title={Text(stringResource(R.string.canvas_edit_measurement))},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name,{name=it},Modifier.fillMaxWidth().testTag("measurement_name"),label={Text(stringResource(R.string.canvas_measurement_name))},isError=name.isBlank()||name.length>120)
            val groups=listOf(R.string.canvas_measurement_start,R.string.canvas_measurement_end,R.string.canvas_measurement_label)
            groups.forEachIndexed { group,label ->
                Text(stringResource(label),style=MaterialTheme.typography.titleSmall)
                listOf("X","Y","Z").forEachIndexed { axis,axisName ->
                    val index=group*3+axis
                    OutlinedTextField(fields[index],{value->fields=fields.toMutableList().also {it[index]=value}},
                        Modifier.fillMaxWidth().testTag("measurement_${group}_$axisName"),label={Text(axisName)},singleLine=true,isError=parsed[index]==null)
                }
            }
            Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(coordinates,{coordinates=it});Text(stringResource(R.string.canvas_measurement_coordinates)) }
            Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(affl,{affl=it});Text(stringResource(R.string.canvas_measurement_affl)) }
        }},confirmButton={TextButton({onApply(Point3(parsed[0]!!,parsed[1]!!,parsed[2]!!),Point3(parsed[3]!!,parsed[4]!!,parsed[5]!!),
            Point3(parsed[6]!!,parsed[7]!!,parsed[8]!!),name.trim(),coordinates,affl)},enabled=valid,modifier=Modifier.testTag("measurement_apply")) {Text(stringResource(R.string.editing_apply))}},
        dismissButton={TextButton(onDismiss){Text(stringResource(R.string.editing_cancel))}})
}
