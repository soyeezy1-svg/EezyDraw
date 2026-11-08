package com.eezydraw.storage
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.graphics.*
import com.eezydraw.core.model.*
import com.eezydraw.core.geometry.*
import com.eezydraw.ui.canvas.*
import java.io.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
@RunWith(RobolectricTestRunner::class) @Config(sdk=[28]) @GraphicsMode(GraphicsMode.Mode.NATIVE)
class IssueReviewTest {
 val p=ProjectId("issue-test");val doc=ProjectDocument(Project(p,"Review",0,0))
 @Test fun cameraCaptureAndVisibilityRestore(){val frame=CanvasFrame(Viewport2D(Point2(2.0,3.0),100.0),OrbitCamera(target=Point3(2.0,3.0,4.0)),is3D=true,viewMode=ViewMode.XRAY);val view=captureIssueView(ViewExportRequest(doc,frame,400,300));assertEquals(frame.orbit.target,view.target);assertEquals(frame.viewport.center,view.centre);assertEquals(400,view.width);val styled=restoreIssuePresentation(doc,view);assertEquals(view.settings,styled.project.settings)}
 @Test fun historicalViewMarkupsRestoreWithoutChangingGeometry(){
  val frame=CanvasFrame(Viewport2D(Point2(2.0,3.0),100.0),OrbitCamera(),is3D=true)
  val old=captureIssueView(ViewExportRequest(doc,frame,400,300));val current=old.copy(id="current")
  val mark=IssueMarkup(MarkupTool.ARROW,listOf(Point2(.1,.1),Point2(.8,.8)),viewId=old.id)
  val issue=Issue(IssueId("review"),p,"Review",1,capture=current,savedViews=listOf(old),annotations=listOf(mark))
  val source=doc.copy(issues=listOf(issue))
  assertEquals(listOf(mark),issueMarkups(source,frame,old))
  assertTrue(issueMarkups(source,frame.copy(orbit=frame.orbit.copy(yaw=1.0)),old).isEmpty())
  assertTrue(issueMarkups(source,frame.copy(levelId="other-level"),old).isEmpty())
  assertTrue(issueMarkups(source.copy(layers=listOf(Layer(LayerId("issue-markups"),p,"Markups",visible=false))),frame,old).isEmpty())
  assertEquals(source,restoreIssuePresentation(source,old))
 }
 @Test fun portraitPhotoOrientationIsApplied(){val context=ApplicationProvider.getApplicationContext<Context>();val file=File(context.cacheDir,"portrait.jpg");val bitmap=Bitmap.createBitmap(20,10,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.RED);file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,100,it)};bitmap.recycle();androidx.exifinterface.media.ExifInterface(file.path).also {it.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,"6");it.saveAttributes()};val decoded=IssueMedia.decode(context,android.net.Uri.fromFile(file));try {assertEquals(10,decoded.width);assertEquals(20,decoded.height)}finally {decoded.recycle()}}
 @Test fun shareUsesAReadOnlyContentUri(){val context=ApplicationProvider.getApplicationContext<Context>();val file=File(context.cacheDir,"issue-share/test.png");file.parentFile!!.mkdirs();file.writeBytes(byteArrayOf(1));val intent=IssueMedia.shareIntent(context,file,"png");assertEquals("image/png",intent.type);assertEquals(android.content.Intent.ACTION_SEND,intent.action);assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION!=0);assertEquals("content",intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)!!.scheme)}
 @Test fun everyMarkupRendersAndIssueMediaExports(){val context=ApplicationProvider.getApplicationContext<Context>();File(context.filesDir,"projects/${p.value}").mkdirs();val b=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888);b.eraseColor(Color.WHITE);val marks=MarkupTool.entries.map {IssueMarkup(it,listOf(Point2(.1,.1),Point2(.7,.8)),text="Note")};for(mark in marks){b.eraseColor(Color.WHITE);paintIssueMarkups(Canvas(b),listOf(mark),400,300);assertTrue("${mark.tool}",(0 until b.width).any {x->(0 until b.height).any {y->b.getPixel(x,y)!=Color.WHITE}})};paintIssueMarkups(Canvas(b),marks,400,300);assertTrue((0 until b.width).any {x->(0 until b.height).any {y->b.getPixel(x,y)!=Color.WHITE}});val asset=IssueMedia.store(context,doc,b);b.recycle();val issue=Issue(IssueId("i"),p,"Test, quoted",1,screenshot=asset.id,capture=captureIssueView(ViewExportRequest(doc,CanvasFrame(Viewport2D(Point2(2.0,3.0),100.0),OrbitCamera()),400,300)).copy(screenshot=asset.id),annotations=marks);val source=doc.copy(issues=listOf(issue,issue.copy(id=IssueId("j"))),assets=listOf(asset));for(format in listOf("pdf","png","csv")){val out=ByteArrayOutputStream();IssueMedia.export(context,source,format,if(format=="png")issue.id else null,out);assertTrue(out.size()>100);File("/tmp/issue-export-$format.$format").writeBytes(out.toByteArray());if(format=="pdf")assertTrue(out.toString("ISO-8859-1").startsWith("%PDF"));if(format=="csv")assertTrue(out.toString("UTF-8").contains("data:image/png;base64,"))}}
}
