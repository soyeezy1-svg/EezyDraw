package com.eezydraw.ui.canvas
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eezydraw.core.model.*
import com.eezydraw.storage.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable fun IssuesPanel(doc:ProjectDocument,onOpen:(Issue)->Unit,onNew:()->Unit,onExport:(String)->Unit) {
 var query by remember {mutableStateOf("")};var status by remember {mutableStateOf<IssueStatus?>(null)};var priority by remember {mutableStateOf<IssuePriority?>(null)};var level by remember {mutableStateOf<LevelId?>(null)};var sort by remember {mutableStateOf(IssueSort.CREATED)}
 Column(Modifier.padding(8.dp).verticalScroll(rememberScrollState())) {
  Row {TextButton(onNew){Text("New issue")};TextButton({onExport("pdf")}){Text("PDF all")};TextButton({onExport("csv")}){Text("CSV all")}}
  OutlinedTextField(query,{query=it},label={Text("Search issues / assignee")})
  RouteChoice("Issue status",status,listOf<IssueStatus?>(null)+IssueStatus.entries,{status=it}){it?.name ?: "All"}
  RouteChoice("Priority",priority,listOf<IssuePriority?>(null)+IssuePriority.entries,{priority=it}){it?.name ?: "All"}
  RouteChoice("Issue level",level,listOf<LevelId?>(null)+doc.levels.map {it.id},{level=it}){id->doc.levels.find {it.id==id}?.name ?: "All"}
  RouteChoice("Issue sort",sort,IssueSort.entries,{sort=it}){it.name}
  val rows=filterIssues(doc,query,status,priority,level,sort)
  LazyColumn(Modifier.heightIn(max=300.dp)){items(rows,key={it.id.value}){i->TextButton({onOpen(i)}){Text("${i.priority} · ${i.title}\n${i.status} · ${i.assignedTo.orEmpty()} · ${i.dueDate.orEmpty()}")}}}
  doc.issues.filter {it.clash==null && it.hidden}.forEach {i->TextButton({onOpen(i)}){Text("Hidden: ${i.title}")}}
 }
}
@Composable fun IssueEditor(initial:Issue,doc:ProjectDocument,onSave:(Issue)->Unit,onDismiss:()->Unit,onMarkup:(Issue)->Unit,onCapture:(Issue)->Unit,onPhoto:(Issue,Boolean)->Unit,onShare:(Issue,String)->Unit,onDelete:(Issue)->Unit,onRestore:(Issue)->Unit={}) {
 var value by remember(initial) {mutableStateOf(initial)};var due by remember(initial){mutableStateOf(initial.dueDate.orEmpty())}
 var titleText by remember(initial){mutableStateOf(initial.title)};var descriptionText by remember(initial){mutableStateOf(initial.description)}
 val validDate=due.isBlank() || runCatching {LocalDate.parse(due)}.isSuccess
 val validFields=titleText.isNotBlank() && titleText.length<=200 && descriptionText.length<=10000 && validDate
 fun draft()=value.copy(title=titleText,description=descriptionText,dueDate=due.takeIf {it.isNotBlank()})
 AlertDialog(onDismissRequest=onDismiss,title={Text("Issue")},text={Column(Modifier.heightIn(max=470.dp).verticalScroll(rememberScrollState())) {
  OutlinedTextField(titleText,{titleText=it},label={Text("Title")},enabled=!value.locked)
  OutlinedTextField(descriptionText,{descriptionText=it},label={Text("Description")},enabled=!value.locked)
  RouteChoice("Priority",value.priority,IssuePriority.entries,{if(!value.locked)value=value.copy(priority=it)}){it.name}
  RouteChoice("Status",value.status,IssueStatus.entries,{if(!value.locked)value=value.copy(status=it)}){it.name}
  OutlinedTextField(value.assignedTo.orEmpty(),{value=value.copy(assignedTo=it)},label={Text("Assigned to")},enabled=!value.locked)
  OutlinedTextField(due,{due=it},label={Text("Due date (YYYY-MM-DD)")},isError=!validDate,enabled=!value.locked)
  RouteChoice("Level",value.levelId,listOf<LevelId?>(null)+doc.levels.map {it.id},{if(!value.locked)value=value.copy(levelId=it)}){id->doc.levels.find {it.id==id}?.name ?: "Global"}
  value.position?.let {p->
   RouteNumber("Pin X",p.x,{if(!value.locked)value=value.copy(position=p.copy(x=it))},positive=false)
   RouteNumber("Pin Y",p.y,{if(!value.locked)value=value.copy(position=p.copy(y=it))},positive=false)
   RouteNumber("Pin Z",p.z,{if(!value.locked)value=value.copy(position=p.copy(z=it))},positive=false)
  }
  if(value.capture!=null)RouteChoice("Saved view",value.capture,(value.savedViews+listOfNotNull(value.capture)).distinctBy {it.id},{v->if(v!=null && validFields)onRestore(draft().copy(capture=v,screenshot=v.screenshot))}){it?.id?.take(8) ?: "None"}
  Text("Position: ${value.position} · Objects: ${value.elementIds.joinToString {it.value}}")
  value.sourceClashId?.let {Text("Linked clash: ${it.value}")}
  Row {Checkbox(value.locked,{value=value.copy(locked=it)});Text("Lock issue")}
  Row {Checkbox(value.hidden,{if(!value.locked)value=value.copy(hidden=it)});Text("Hide pin")}
  Row {Checkbox(value.markupVisible,{if(!value.locked)value=value.copy(markupVisible=it)});Text("Markup layer visible")}
  TextButton({onCapture(draft())},enabled=!value.locked && validFields){Text("Save view")}
  TextButton({onMarkup(draft())},enabled=!value.locked && validFields){Text("Draw markup / screenshot")}
  Row {TextButton({onPhoto(draft(),false)},enabled=!value.locked && validFields){Text("Gallery")};TextButton({onPhoto(draft(),true)},enabled=!value.locked && validFields){Text("Camera")}}
  Text("${value.attachments.size} photos · ${value.annotations.size} markups")
  val context=LocalContext.current
  LazyRow(Modifier.heightIn(max=130.dp)){items(value.attachments+listOfNotNull(value.screenshot)){id->
   val preview by produceState<android.graphics.Bitmap?>(null,id){this.value=withContext(Dispatchers.IO){val asset=doc.assets.find {it.id==id};asset?.let {a->val root=java.io.File(context.filesDir,"projects/${doc.project.id.value}/assets").canonicalFile;val file=java.io.File(root,a.relativePath).canonicalFile;require(file.path.startsWith(root.path+java.io.File.separator));val bounds=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true};android.graphics.BitmapFactory.decodeFile(file.path,bounds);var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>256)sample*=2;android.graphics.BitmapFactory.decodeFile(file.path,android.graphics.BitmapFactory.Options().apply {inSampleSize=sample})}}}
   Column {preview?.let {Image(it.asImageBitmap(),"Issue attachment",Modifier.size(90.dp))};TextButton({value=value.copy(attachments=value.attachments-id,screenshot=value.screenshot?.takeUnless {it==id})},enabled=!value.locked){Text("Remove")}}
  }}
  Row {TextButton({onShare(draft(),"png")},enabled=validFields){Text("Share image")};TextButton({onShare(draft(),"pdf")},enabled=validFields){Text("Share PDF")}}
  TextButton({onDelete(value)},enabled=!value.locked){Text("Delete issue")}
 }},confirmButton={TextButton({onSave(draft())},enabled=validFields){Text("Save issue")}},dismissButton={TextButton(onDismiss){Text("Cancel")}})
}
@Composable fun IssueMarkupEditor(issue:Issue,request:ViewExportRequest,onSave:(List<IssueMarkup>,android.graphics.Bitmap)->Unit,onDismiss:()->Unit) {
 var bitmap by remember {mutableStateOf<android.graphics.Bitmap?>(null)};var error by remember {mutableStateOf<String?>(null)}
 LaunchedEffect(request){try {bitmap=withContext(Dispatchers.Default){ViewExport.render(request)}}catch(e:Exception){error=e.message}}
 DisposableEffect(Unit){onDispose {bitmap?.recycle()}}
 var marks by remember {mutableStateOf(issue.annotations.filter {it.viewId==issue.capture?.id || it.viewId.isEmpty()})};var undone by remember {mutableStateOf(emptyList<IssueMarkup>())};var pending by remember {mutableStateOf(emptyList<Point2>())};var tool by remember {mutableStateOf(MarkupTool.CLOUD)};var colour by remember {mutableLongStateOf(0xFFFF3030L)};var width by remember {mutableFloatStateOf(3f)};var text by remember {mutableStateOf("Note")}
 Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){Surface {Column(Modifier.fillMaxSize().padding(8.dp)) {
  Row(Modifier.horizontalScroll(rememberScrollState())) {RouteChoice("Markup",tool,MarkupTool.entries,{tool=it}){it.name};RouteChoice("Colour",colour,listOf(0xFFFF3030L,0xFF0099FFL,0xFF00AA55L,0xFFFFFF00L),{colour=it}){it.toString(16)};TextButton({if(marks.isNotEmpty()){undone=undone+marks.last();marks=marks.dropLast(1)}}){Text("Undo")};TextButton({if(undone.isNotEmpty()){marks=marks+undone.last();undone=undone.dropLast(1)}}){Text("Redo")}}
  Slider(width,{width=it},valueRange=1f..20f);if(tool==MarkupTool.TEXT)OutlinedTextField(text,{text=it.take(2000)},label={Text("Text note")})
  error?.let {Text(it)}
  bitmap?.let {image->Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){Canvas(Modifier.fillMaxWidth().aspectRatio(image.width.toFloat()/image.height).clipToBounds().pointerInput(tool,colour,width,text){detectDragGestures(onDragStart={p->pending=listOf(Point2((p.x/size.width).toDouble().coerceIn(0.0,1.0),(p.y/size.height).toDouble().coerceIn(0.0,1.0)))},onDrag={change,_->change.consume();if(pending.size<10000)pending=pending+Point2((change.position.x/size.width).toDouble().coerceIn(0.0,1.0),(change.position.y/size.height).toDouble().coerceIn(0.0,1.0))},onDragEnd={if(pending.isNotEmpty()){marks=marks+IssueMarkup(tool,pending,colour,width.toDouble(),text,issue.capture?.id.orEmpty(),issue.levelId);undone=emptyList()};pending=emptyList()},onDragCancel={pending=emptyList()})}){
    drawContext.canvas.nativeCanvas.drawBitmap(image,null,android.graphics.RectF(0f,0f,size.width,size.height),null)
    paintIssueMarkups(drawContext.canvas.nativeCanvas,marks+if(pending.isEmpty())emptyList()else listOf(IssueMarkup(tool,pending,colour,width.toDouble(),text)),size.width.toInt(),size.height.toInt())
  }}}
  Row {TextButton({bitmap?.let {onSave(marks,it)}},enabled=bitmap!=null){Text("Save markup screenshot")};TextButton(onDismiss){Text("Cancel")}}
 }}}
}
