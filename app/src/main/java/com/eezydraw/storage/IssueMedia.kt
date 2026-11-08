package com.eezydraw.storage
import android.content.Context
import android.graphics.*
import android.net.Uri
import androidx.core.graphics.createBitmap
import com.eezydraw.core.model.*
import com.eezydraw.core.geometry.*
import com.eezydraw.ui.canvas.*
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
object IssueMedia {
 fun shareIntent(context:Context,file:File,format:String):android.content.Intent {
  val uri=androidx.core.content.FileProvider.getUriForFile(context,context.packageName+".issues",file)
  return android.content.Intent(android.content.Intent.ACTION_SEND).setType(if(format=="pdf")"application/pdf"else "image/png").putExtra(android.content.Intent.EXTRA_STREAM,uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
 }
 fun decode(context:Context,uri:Uri):Bitmap {
  val bytes=context.contentResolver.openInputStream(uri)?.use {input->val out=ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=16*1024*1024){"Photo exceeds 16 MiB"};out.write(buffer,0,n)};out.toByteArray()} ?: error("Photo unavailable")
  val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds);require(bounds.outWidth>0 && bounds.outHeight>0){"Invalid photo"}
  var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>2048)sample*=2
  val decoded=requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {inSampleSize=sample}))
  val orientation=runCatching {androidx.exifinterface.media.ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,1)}.getOrDefault(1)
  if(orientation==1)return decoded
  val matrix=Matrix().apply {when(orientation){2->setScale(-1f,1f);3->setRotate(180f);4->setScale(1f,-1f);5->{setRotate(90f);postScale(-1f,1f)};6->setRotate(90f);7->{setRotate(-90f);postScale(-1f,1f)};8->setRotate(-90f)}}
  return Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,matrix,true).also {if(it!==decoded)decoded.recycle()}
 }
 fun store(context:Context,doc:ProjectDocument,bitmap:Bitmap):ProjectAsset {
  require(doc.assets.size<255){"Project attachment limit reached"}
  val bytes=ByteArrayOutputStream().also {require(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}.toByteArray();require(bytes.size<=16*1024*1024)
  val root=File(context.filesDir,"projects/${doc.project.id.value}/assets");require(File(context.filesDir,"projects/${doc.project.id.value}").isDirectory){"Save the project before attaching a photo"}
  require(doc.assets.sumOf {File(root,it.relativePath).length()}+bytes.size<24*1024*1024){"Project attachment budget exceeded"}
  val path="issue-${UUID.randomUUID()}.png";root.mkdirs();val target=File(root,path);val atomic=android.util.AtomicFile(target);val stream=atomic.startWrite();try {stream.write(bytes);atomic.finishWrite(stream)}catch(e:Exception){atomic.failWrite(stream);throw e}
  return ProjectAsset(AssetId(UUID.randomUUID().toString()),doc.project.id,path,"image/png",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})
 }
 private fun file(context:Context,doc:ProjectDocument,id:AssetId):File {val a=requireNotNull(doc.assets.find {it.id==id});val root=File(context.filesDir,"projects/${doc.project.id.value}/assets").canonicalFile;return File(root,a.relativePath).canonicalFile.also {require(it.path.startsWith(root.path+File.separator) && it.isFile){"Issue attachment unavailable"}}}
 fun export(context:Context,doc:ProjectDocument,format:String,id:IssueId?,output:OutputStream) {
  val issues=doc.issues.filter {it.clash==null && (id==null||it.id==id)}
  require(issues.isNotEmpty()) {"No issues to export"}
  if(format=="csv") {val w=output.bufferedWriter();fun cell(s:String)="\""+s.replace("\"","\"\"")+"\""
   w.appendLine("ID,Title,Description,Priority,Status,Assigned,Due,Level,Position,Objects,View,Attachments,Screenshot")
   for(i in issues){val images=(i.attachments+listOfNotNull(i.screenshot)+i.savedViews.mapNotNull {it.screenshot}).distinct().joinToString(";"){a->"data:image/png;base64,"+java.util.Base64.getEncoder().encodeToString(file(context,doc,a).readBytes())};w.appendLine(listOf(i.id.value,i.title,i.description,i.priority.name,i.status.name,i.assignedTo.orEmpty(),i.dueDate.orEmpty(),i.levelId?.value.orEmpty(),i.position.toString(),i.elementIds.joinToString {it.value},Json.encodeToString(listOfNotNull(i.capture)+i.savedViews),images,i.screenshot?.value.orEmpty()).joinToString(",",transform=::cell))};w.flush();return}
  if(format=="png"){val image=card(context,doc,issues.single());try {require(image.compress(Bitmap.CompressFormat.PNG,100,output))}finally {image.recycle()};return}
  val sheets=issues.flatMap {i->val descriptions=i.description.chunked(600).ifEmpty {listOf("")};val photos=i.attachments.chunked(4).ifEmpty {listOf(emptyList())};(0 until maxOf(descriptions.size,photos.size)).map {n->i.copy(description=descriptions.getOrElse(n){""},attachments=photos.getOrElse(n){emptyList()},screenshot=if(n==0)i.screenshot else null)}}
  val pages=sheets.map {i->DrawingPdf(595,842).also {pdf->val image=card(context,doc,i);try {pdf.image(image,listOf(Point2(20.0,20.0),Point2(575.0,20.0),Point2(575.0,822.0),Point2(20.0,822.0)))}finally {image.recycle()}}};DrawingPdf.writePages(pages,output)
 }
 fun card(context:Context,doc:ProjectDocument,i:Issue):Bitmap {
  val lines=i.description.lines().flatMap {it.chunked(75).ifEmpty {listOf("")}};val cardHeight=maxOf(1440,320+lines.size*32+550+((i.attachments.size+3)/4)*160).coerceAtMost(16384);val image=createBitmap(1000,cardHeight);val c=Canvas(image);c.drawColor(Color.WHITE);val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=24f};var y=40f
  for(line in (i.title.chunked(75)+listOf("ID: ${i.id.value}","${i.priority} · ${i.status}","Assigned: ${i.assignedTo.orEmpty()} · Due: ${i.dueDate.orEmpty()}","Level: ${i.levelId?.value.orEmpty()} · ${i.position}","Objects: ${i.elementIds.joinToString {it.value}}","Clash: ${i.sourceClashId?.value.orEmpty()}"))+lines){c.drawText(line.take(100),24f,y,p);y+=32}
  fun draw(b:Bitmap,rect:RectF){val scale=minOf(rect.width()/b.width,rect.height()/b.height);val w=b.width*scale;val h=b.height*scale;c.drawBitmap(b,null,RectF(rect.centerX()-w/2,rect.centerY()-h/2,rect.centerX()+w/2,rect.centerY()+h/2),null)}
  val view=i.capture
  val snapshot=i.screenshot?.let {decode(context,Uri.fromFile(file(context,doc,it)))} ?: run {
   val position=i.position ?: Point3()
   val f=if(view!=null)CanvasFrame(Viewport2D(view.centre,view.pixelsPerMetre),OrbitCamera(view.target,view.yaw,view.pitch,view.distance,view.orthographicHeight,view.projection),view.is3D,view.levelId?.value,showGrid=view.grid,viewMode=view.settings.viewMode) else CanvasFrame(Viewport2D(Point2(position.x,position.y),100.0),OrbitCamera(target=position),levelId=i.levelId?.value)
   ViewExport.render(ViewExportRequest(restoreIssuePresentation(doc,view),f,view?.width ?: 1000,view?.height ?: 650)).also {image->if(i.markupVisible)paintIssueMarkups(Canvas(image),i.annotations.filter {it.viewId==view?.id || it.viewId.isEmpty()},image.width,image.height)}
  }
  try {draw(snapshot,RectF(24f,y+10,976f,y+510))}finally {snapshot.recycle()};y+=530

  for((index,id) in i.attachments.withIndex()){val b=decode(context,Uri.fromFile(file(context,doc,id)));try {val x=24f+(index%4)*240;val top=y+(index/4)*160;draw(b,RectF(x,top,x+225,top+150))}finally {b.recycle()}}
  return image
 }
}
