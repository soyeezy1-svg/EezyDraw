package com.eezydraw.ui.canvas

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.eezydraw.core.model.*

@Composable internal fun FloorDefaultsBar(value:FloorServiceSettings,onChange:(FloorServiceSettings)->Unit) {
    Column {
        FloorInstallation.entries.forEach {kind->RouteNumber("${floorInstallationLabel(kind)} default depth",value.defaultDepths[kind] ?: .1,{onChange(value.copy(defaultDepths=value.defaultDepths+(kind to it)))})}
        RouteNumber("Screed thickness",value.screedThickness,{onChange(value.copy(screedThickness=it))})
        RouteNumber("Floor clearance",value.clearance,{onChange(value.copy(clearance=it.coerceIn(0.0,1.0)))},false)
        var percent by remember(value.maxDepthFraction){mutableStateOf((value.maxDepthFraction*100).toInt().toString())}
        OutlinedTextField(percent,{percent=it;it.toDoubleOrNull()?.takeIf {n->n in 1.0..100.0}?.let {n->onChange(value.copy(maxDepthFraction=n/100))}},label={Text("Depth warning (%)")},modifier=Modifier.testTag("floor_depth_threshold"))
    }
}
@Composable internal fun FloorScheduleDialog(document:ProjectDocument,onSettings:(FloorServiceSettings)->Unit,onDismiss:()->Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={Text("Floor services schedule")},text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()).testTag("floor_schedule")) {
        FilterChip(document.project.settings.floorServices.showServices,{onSettings(document.project.settings.floorServices.copy(showServices=!document.project.settings.floorServices.showServices))},{Text("Show underfloor services")})
        FilterChip(document.project.settings.floorServices.showChannels,{onSettings(document.project.settings.floorServices.copy(showChannels=!document.project.settings.floorServices.showChannels))},{Text("Floor channels in X-ray / Section")})
        floorSchedule(document).groupBy {it.levelId}.forEach {(level,rows)->Text(document.levels.find {it.id==level}?.name ?: "Project datum",style=MaterialTheme.typography.titleSmall)
            rows.forEach {r->Text("${r.name} · ${r.size}\nDepth %.0f mm · %.2f m · X %.3f Y %.3f Z %.3f".format(java.util.Locale.ROOT,r.depth*1000,r.length,r.position.x,r.position.y,r.position.z))}}
        floorServiceWarnings(document).forEach {Text(it,color=MaterialTheme.colorScheme.error)}
        FloorDefaultsBar(document.project.settings.floorServices,onSettings)
    }},confirmButton={TextButton(onDismiss){Text("Close")}})
}
