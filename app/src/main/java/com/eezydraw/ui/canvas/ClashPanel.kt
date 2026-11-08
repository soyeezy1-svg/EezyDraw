package com.eezydraw.ui.canvas

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.eezydraw.core.model.*
import java.util.Locale

@Composable fun ClashCheckDialog(document:ProjectDocument,level:String?,onRun:(ClashCheckOptions)->Unit,onDismiss:()->Unit) {
    var value by remember {mutableStateOf(document.project.settings.clashCheck.let {it.copy(levelId=if(it.levelId==null)null else level?.let(::LevelId))})}
    val validNumbers=remember {mutableStateMapOf<String,Boolean>()}
    val groups=listOf(ClashGroup())+Discipline.entries.map {ClashGroup(category=it)}+ClashObjectCategory.entries.map {ClashGroup(objects=it)}+document.systems.map {ClashGroup(system=it.id)}
    fun name(g:ClashGroup)=g.system?.let {id->"System: "+document.systems.find {it.id==id}?.name} ?: g.category?.let {"Category: "+it.name.replace('_',' ')} ?: g.objects?.let {"Objects: "+it.name.replace('_',' ')} ?: "All"
    AlertDialog(onDismissRequest=onDismiss,title={Text("Clash check")},text={Column(Modifier.heightIn(max=450.dp).verticalScroll(rememberScrollState())) {
        RouteChoice("Preset","Choose",listOf("All vs All","Electrical vs HVAC","Plumbing vs Structure"),{preset->value=value.copy(first=when(preset){"Electrical vs HVAC"->ClashGroup(category=Discipline.ELECTRICAL);"Plumbing vs Structure"->ClashGroup(category=Discipline.PLUMBING);else->ClashGroup()},second=when(preset){"Electrical vs HVAC"->ClashGroup(category=Discipline.HVAC);"Plumbing vs Structure"->ClashGroup(category=Discipline.STRUCTURE);else->ClashGroup()})}){it}
        RouteChoice("First group",value.first,groups,{value=value.copy(first=it)},::name)
        RouteChoice("Second group",value.second,groups,{value=value.copy(second=it)},::name)
        RouteChoice("Check levels",value.levelId,listOf<LevelId?>(null)+listOfNotNull(level?.let(::LevelId)),{value=value.copy(levelId=it)}){if(it==null)"All levels"else "Current level"}
        Row {Checkbox(value.visibleOnly,{value=value.copy(visibleOnly=it)});Text("Visible objects only")}
        ClashLength("Clearance",value.clearance,{validNumbers["clearance"]=it},{if(it in 0.0..100.0)value=value.copy(clearance=it)})
        ClashLength("Ignore overlap under",value.tolerance,{validNumbers["tolerance"]=it},{if(it in 0.0..100.0)value=value.copy(tolerance=it)})
        ClashLength("Distribution board clearance",value.panelClearance,{validNumbers["panel"]=it},{if(it in 0.0..100.0)value=value.copy(panelClearance=it)})
        document.systems.forEach {s->ClashLength("${s.name} clearance",value.systemClearances[s.id] ?: value.clearance,{validNumbers[s.id.value]=it},{if(it in 0.0..100.0)value=value.copy(systemClearances=value.systemClearances+(s.id to it))})}
        Row {Checkbox(value.ignoreConnections,{value=value.copy(ignoreConnections=it)});Text("Ignore connected joints")}
        Text("Uses physical solids and wall/floor cuts. Lock does not exclude an object.")
    }},confirmButton={TextButton({onRun(value)},Modifier.testTag("clash_run"),enabled=validNumbers.values.all {it}){Text("Run check")}},dismissButton={TextButton(onDismiss){Text("Cancel")}})
}

@Composable private fun ClashLength(label:String,value:Double,onValid:(Boolean)->Unit,onChange:(Double)->Unit) {
    var input by remember(label) {mutableStateOf((value*1000).toString())}
    val number=input.toDoubleOrNull()?.div(1000)?.takeIf {it.isFinite() && it in 0.0..100.0}
    OutlinedTextField(input,{input=it;val n=it.toDoubleOrNull()?.div(1000)?.takeIf {d->d.isFinite() && d in 0.0..100.0};onValid(n!=null);n?.let(onChange)},
        label={Text("$label (mm)")},singleLine=true,isError=number==null,modifier=Modifier.testTag("clash_length_$label"))
}

enum class ClashSort { ID, TYPE, LEVEL, DISTANCE, STATUS }
fun filteredClashes(document:ProjectDocument,kind:ClashKind?,system:SystemId?,level:LevelId?,sort:ClashSort):List<Issue> {
    val objects=document.elements.associateBy {it.id}
    val results=document.issues.filter {i->i.clash?.let {c->(kind==null||c.kind==kind) && (level==null||level in c.levelIds) &&
        (system==null||objects[c.firstElementId]?.systemId==system||objects[c.secondElementId]?.systemId==system)}==true}
    val order=when(sort) {
        ClashSort.ID->compareBy<Issue> {it.id.value}
        ClashSort.TYPE->compareBy {it.clash!!.kind.name}
        ClashSort.STATUS->compareBy {it.clash!!.status.ordinal}
        ClashSort.DISTANCE->compareBy {it.clash!!.distance}
        ClashSort.LEVEL->compareBy {issue->issue.clash!!.levelIds.mapNotNull {id->document.levels.find {it.id==id}?.elevation}.minOrNull() ?: Double.NEGATIVE_INFINITY}
    }
    return results.sortedWith(order.thenBy {it.id.value})
}
@Composable fun ClashPanel(document:ProjectDocument,progress:Float?,summary:String?,onCheck:()->Unit,onCancel:()->Unit,
    onFocus:(Issue)->Unit,onClear:()->Unit,xray:Boolean,onXray:(Boolean)->Unit,onStatus:(IssueId,ClashStatus,String)->Unit) {
    var kind by remember {mutableStateOf<ClashKind?>(null)};var system by remember {mutableStateOf<SystemId?>(null)}
    var level by remember {mutableStateOf<LevelId?>(null)};var sort by remember {mutableStateOf(ClashSort.ID)}
    var editing by remember {mutableStateOf<IssueId?>(null)}
    Column(Modifier.padding(8.dp).verticalScroll(rememberScrollState()).testTag("clash_panel")) {
        Row {TextButton(onCheck,enabled=progress==null){Text("Clash check")};TextButton(onClear){Text("Clear focus")}}
        if(progress!=null){LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth());TextButton(onCancel,Modifier.testTag("clash_cancel")){Text("Cancel check")}}
        summary?.let {Text(it)}
        Row {Checkbox(xray,onXray);Text("X-ray inspection")}
        RouteChoice("Clash type",kind,listOf<ClashKind?>(null)+ClashKind.entries,{kind=it}){it?.name ?: "All types"}
        RouteChoice("Clash system",system,listOf<SystemId?>(null)+document.systems.map {it.id},{system=it}){id->document.systems.find {it.id==id}?.name ?: "All systems"}
        RouteChoice("Clash level",level,listOf<LevelId?>(null)+document.levels.map {it.id},{level=it}){id->document.levels.find {it.id==id}?.name ?: "All levels"}
        RouteChoice("Sort clashes",sort,ClashSort.entries,{sort=it}){it.name}
        val rows=remember(document.issues,document.elements,kind,system,level,sort){filteredClashes(document,kind,system,level,sort)}
        Text("${rows.size} results")
        LazyColumn(Modifier.heightIn(max=300.dp)) {items(rows,key={it.id.value}) {issue->val c=issue.clash!!
            HorizontalDivider();TextButton({onFocus(issue)},Modifier.testTag("clash_focus_${issue.id.value}")){Text("C-${issue.id.value.takeLast(12)} · ${c.kind} · ${c.status}")}
            Text("${c.firstName.ifBlank {c.firstElementId.value}} / ${c.secondName.ifBlank {c.secondElementId.value}}")
            Text("${c.firstElementId.value} / ${c.secondElementId.value}")
            Text(c.levelIds.joinToString {id->document.levels.find {it.id==id}?.name ?: id.value}.ifBlank {"Project datum"})
            Text(String.format(Locale.ROOT,"%s %.2f mm · (%.3f, %.3f, %.3f) m",if(c.kind==ClashKind.HARD)"Overlap"else "Distance",1000*(if(c.kind==ClashKind.HARD)c.overlap else c.distance),c.position.x,c.position.y,c.position.z))
            if(c.note.isNotBlank())Text(c.note)
            TextButton({editing=issue.id}){Text("Status and note")}
        }}
    }
    document.issues.find {it.id==editing}?.let {i->
        var status by remember(i.id,i.clash) {mutableStateOf(i.clash!!.status)};var note by remember(i.id,i.clash){mutableStateOf(i.clash!!.note)}
        AlertDialog(onDismissRequest={editing=null},title={Text("Clash status")},text={Column {
            RouteChoice("Status",status,ClashStatus.entries,{status=it}){it.name}
            OutlinedTextField(note,{note=it},label={Text("Note")},isError=note.length>4000,modifier=Modifier.testTag("clash_note"))
        }},confirmButton={TextButton({onStatus(i.id,status,note);editing=null},enabled=note.length<=4000){Text("Save status")}},dismissButton={TextButton({editing=null}){Text("Cancel")}})
    }
}
