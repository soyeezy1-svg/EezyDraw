package com.eezydraw.ui.canvas

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eezydraw.core.geometry.*
import com.eezydraw.core.model.*
import com.eezydraw.storage.ScheduleFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun SchedulesPanel(document:ProjectDocument,planView:Boolean,onSelect:(Set<ElementId>)->Unit,
    onSettings:(TakeoffSettings)->Unit,onRate:(String,Set<LibraryItemId>,Double?,Boolean)->Unit,
    onShare:(ScheduleDefinition,Boolean,ScheduleFormat)->Unit) {
    val settings=document.project.settings.takeoff
    val definitions=(providedSchedules()+settings.schedules).associateBy {it.id}.values.toList()
    var selected by remember {mutableStateOf("equipment")}
    val definition=definitions.find {it.id==selected} ?: definitions.first()
    var custom by remember {mutableStateOf<ScheduleDefinition?>(null)}
    var options by remember {mutableStateOf(false)}
    var rateRow by remember {mutableStateOf<ScheduleRow?>(null)}
    var highlighted by remember {mutableStateOf<Set<ElementId>>(emptySet())}
    var table by remember {mutableStateOf<ScheduleTable?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(document,definition,planView){table=null;error=null;try {table=withContext(Dispatchers.Default){ScheduleEngine.build(document,definition,planView)}}catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;error=e.message}}
    custom?.let {draft->ScheduleEditor(draft,document,{value->onSettings(settings.copy(schedules=settings.schedules.filterNot {it.id==value.id}+value));selected=value.id;custom=null},{custom=null})}
    if(options)TakeoffOptions(settings,{onSettings(it);options=false},{options=false})
    rateRow?.let {row->RateEditor(row,settings.currency,{amount,library->row.rateKey?.let {onRate(it,row.libraryIds,amount,library)};rateRow=null},{rateRow=null})}
    Column(Modifier.padding(8.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            RouteChoice("Schedule",definition,definitions,{selected=it.id}){it.name}
            TextButton({custom=definition.copy(id=java.util.UUID.randomUUID().toString(),name="Custom schedule")}){Text("Custom schedule")}
            TextButton({custom=definition}){Text("Configure")}
            TextButton({options=true}){Text("Quantity settings")}
        }
        Text("${settings.allowancePercent}% length allowance · ${settings.currency}")
        error?.let {Text(it)}
        val current=table
        if(current==null && error==null)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(current!=null) {
            Row {ScheduleFormat.entries.forEach {format->TextButton({onShare(definition,planView,format)}){Text("Share ${format.extension.uppercase()}")}}}
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                Row {definition.columns.forEach {Text(it.label,Modifier.width(140.dp).padding(6.dp),style=MaterialTheme.typography.labelMedium)}}
                LazyColumn(Modifier.heightIn(max=260.dp)) {
                    itemsIndexed(current.rows){_,row->
                        Row(Modifier.background(if(row.objectIds==highlighted)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface).clickable {highlighted=row.objectIds;onSelect(row.objectIds)}) {
                            definition.columns.forEach {column->Text(row.cells[column].orEmpty(),Modifier.width(140.dp).padding(6.dp))}
                            if(definition.category==ScheduleCategory.BOQ && row.rateKey!=null)TextButton({rateRow=row}){Text("Edit rate")}
                        }
                    }
                }
            }
            if(current.rows.isEmpty())Text("No matching objects")
            if(definition.totals)Column(Modifier.heightIn(max=150.dp).verticalScroll(rememberScrollState())) {
                com.eezydraw.storage.ScheduleExport.totals(current).forEach {(label,value)->Text("$label: $value")}
            }
            Text("${current.rows.size} rows · Tap a row to highlight its objects")
            TextButton({highlighted=emptySet();onSelect(emptySet())}){Text("Clear highlight")}
        }
    }
}

@Composable fun TakeoffOptions(initial:TakeoffSettings,onSave:(TakeoffSettings)->Unit,onDismiss:()->Unit) {
    var allowance by remember {mutableStateOf(initial.allowancePercent.toString())};var currency by remember {mutableStateOf(initial.currency)}
    val amount=allowance.toDoubleOrNull();val valid=amount!=null && amount.isFinite() && amount in 0.0..1000.0 && currency.matches(Regex("[A-Z]{3}"))
    AlertDialog(onDismissRequest=onDismiss,title={Text("Quantity settings")},text={Column {
        OutlinedTextField(allowance,{allowance=it},label={Text("Length allowance (%)")})
        OutlinedTextField(currency,{currency=it.uppercase().take(3)},label={Text("Currency (ISO code)")})
        Text("Lengths use metres; room areas use m². Allowance applies to lengths only. Rates remain in their saved currencies.")
    }},confirmButton={TextButton({onSave(initial.copy(allowancePercent=amount!!,currency=currency))},enabled=valid){Text("Save quantities")}},dismissButton={TextButton(onDismiss){Text("Cancel")}})
}

@Composable internal fun RateEditor(row:ScheduleRow,currency:String,onSave:(Double?,Boolean)->Unit,onDismiss:()->Unit) {
    var amount by remember {mutableStateOf(row.rate?.toString().orEmpty())};var library by remember {mutableStateOf(false)}
    var total by remember {mutableStateOf(row.cost?.toPlainString().orEmpty())}
    val number=amount.toDoubleOrNull();val cost=total.toDoubleOrNull();val valid=(amount.isBlank() || number!=null && number.isFinite() && number in 0.0..1e12) && (total.isBlank() || cost!=null && cost.isFinite() && cost in 0.0..1e15)
    AlertDialog(onDismissRequest=onDismiss,title={Text("Unit rate · $currency / ${row.unit.symbol}")},text={Column {
        OutlinedTextField(amount,{amount=it;total=it.toDoubleOrNull()?.let {rate->(rate*row.quantity).toString()}.orEmpty()},label={Text("Unit rate (blank to clear)")})
        OutlinedTextField(total,{total=it;if(it.isBlank())amount="" else it.toDoubleOrNull()?.let {cost->if(row.quantity>0)amount=(cost/row.quantity).toString()}},label={Text("Total cost (sets unit rate)")},enabled=row.quantity>0)
        if(row.libraryIds.isNotEmpty())Row {Checkbox(library,{library=it});Text("Also save to reusable library")}
        Text("The rate applies to matching type and size across levels and systems. Entering total cost derives a unit rate; costs then follow model quantities.")
    }},confirmButton={TextButton({onSave(number,library)},enabled=valid){Text("Save rate")}},dismissButton={TextButton(onDismiss){Text("Cancel")}})
}

@Composable fun ScheduleEditor(initial:ScheduleDefinition,document:ProjectDocument,onSave:(ScheduleDefinition)->Unit,onDismiss:()->Unit) {
    var value by remember {mutableStateOf(initial)};var name by remember {mutableStateOf(initial.name)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Schedule definition")},text={Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState())) {
        OutlinedTextField(name,{name=it.take(120)},label={Text("Schedule name")})
        RouteChoice("Category",value.category,ScheduleCategory.entries,{value=value.copy(category=it,routeKind=null)}){it.name}
        if(value.category==ScheduleCategory.ROUTES)RouteChoice("Service type",value.routeKind,listOf<RouteKind?>(null)+RouteKind.entries,{value=value.copy(routeKind=it)}){it?.name ?: "All services"}
        OutlinedTextField(value.query,{value=value.copy(query=it.take(500))},label={Text("Filter text")})
        ChoiceCheck("Visible objects only",value.visibleOnly){value=value.copy(visibleOnly=it)}
        ChoiceCheck("Show totals",value.totals){value=value.copy(totals=it)}
        Text("Columns (selection order)")
        ScheduleColumn.entries.forEach {column->ChoiceCheck(column.label,column in value.columns){on->val next=if(on)value.columns+column else value.columns-column;if(next.isNotEmpty())value=value.copy(columns=next)}}
        Text("Group by")
        ScheduleGroup.entries.forEach {group->ChoiceCheck(group.name,group in value.grouping){on->value=value.copy(grouping=if(on)value.grouping+group else value.grouping-group)}}
        RouteChoice("Sort by",value.sort,ScheduleColumn.entries,{value=value.copy(sort=it)}){it.label}
        ChoiceCheck("Descending",value.descending){value=value.copy(descending=it)}
        Text("Levels (none means all)")
        document.levels.forEach {level->ChoiceCheck(level.name,level.id in value.levels){on->value=value.copy(levels=if(on)value.levels+level.id else value.levels-level.id)}}
        Text("Systems (none means all)")
        document.systems.forEach {system->ChoiceCheck(system.name,system.id in value.systems){on->value=value.copy(systems=if(on)value.systems+system.id else value.systems-system.id)}}
    }},confirmButton={TextButton({onSave(value.copy(name=name))},enabled=name.isNotBlank()){Text("Save schedule")}},dismissButton={TextButton(onDismiss){Text("Cancel")}})
}
@Composable private fun ChoiceCheck(label:String,checked:Boolean,onChange:(Boolean)->Unit) {Row {Checkbox(checked,onChange);Text(label,Modifier.padding(top=12.dp))}}
