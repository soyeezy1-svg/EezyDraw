package com.eezydraw.ui.canvas

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.eezydraw.core.model.*

@Composable
internal fun CeilingOptionsDialog(title:String,options:CeilingOptions,onApply:(CeilingOptions)->Unit,onDismiss:()->Unit,
    onInherit:(()->Unit)?=null) {
    var type by rememberSaveable(options){mutableStateOf(options.type.name)}
    var height by rememberSaveable(options){mutableStateOf((options.height*1000).toString())}
    var thickness by rememberSaveable(options){mutableStateOf((options.thickness*1000).toString())}
    val h=height.toDoubleOrNull()?.div(1000)?.takeIf {it.isFinite() && it>0}
    val t=thickness.toDoubleOrNull()?.div(1000)?.takeIf {it.isFinite() && it>0}
    AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        RouteChoice("Ceiling",CeilingType.valueOf(type),CeilingType.entries,{type=it.name}){if(it==CeilingType.NONE)"None"else "Flat"}
        OutlinedTextField(height,{height=it},label={Text("Ceiling height (mm AFFL)")},isError=h==null,singleLine=true,modifier=Modifier.testTag("ceiling_height"))
        OutlinedTextField(thickness,{thickness=it},label={Text("Ceiling thickness (mm)")},isError=t==null,singleLine=true,modifier=Modifier.testTag("ceiling_thickness"))
        Text("Flat ceilings follow room boundaries. Height also sets the mounting plane when Ceiling is None.",style=MaterialTheme.typography.bodySmall)
        onInherit?.let {TextButton(it,Modifier.testTag("ceiling_inherit")){Text("Use level ceiling")}}
    }},confirmButton={TextButton({onApply(CeilingOptions(CeilingType.valueOf(type),h!!,t!!))},enabled=h!=null && t!=null,modifier=Modifier.testTag("ceiling_apply")){Text("Apply")}},
        dismissButton={TextButton(onDismiss){Text("Cancel")}})
}
