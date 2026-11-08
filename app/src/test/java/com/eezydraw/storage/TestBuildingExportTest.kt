package com.eezydraw.storage

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.eezydraw.core.editing.TestBuilding
import com.eezydraw.core.geometry.*
import com.eezydraw.core.model.*
import com.eezydraw.ui.canvas.CanvasFrame
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28]) @GraphicsMode(GraphicsMode.Mode.NATIVE)
class TestBuildingExportTest {
    private val doc by lazy {TestBuilding.build(ProjectDocument(Project(ProjectId("test-building-export"),TestBuilding.NAME,1,1)))}
    private val folder=File("/tmp/eezy-test-building-exports").apply {mkdirs()}
    @Test fun sampleExportsEverySupportedModelFormat() {
        for(format in ModelFormat.entries) {
            val file=File(folder,"building.${format.extension}")
            file.outputStream().use {ModelExport.write(doc,ModelExportOptions(format=format,planView=false,appVersion="sample-test"),it)}
            assertTrue("$format produced geometry",file.length()>1000)
            when(format) {
                ModelFormat.IFC->{val text=file.readText();assertTrue(text.startsWith("ISO-10303-21"));assertTrue(text.contains("IFC4"));assertTrue(text.trimEnd().endsWith("END-ISO-10303-21;"))}
                ModelFormat.OBJ->{val lines=file.readLines();val count=lines.count {it.startsWith("v ")};assertTrue(count>0);assertTrue(lines.any {it.startsWith("f ")});assertFalse(lines.any {it.contains("NaN")})}
                ModelFormat.STL->{val text=file.readText();assertTrue(text.startsWith("solid "));assertTrue(text.contains("facet normal"));assertTrue(text.contains("endsolid"))}
                ModelFormat.GLTF->{val json=Json.parseToJsonElement(file.readText()).jsonObject;assertEquals("2.0",json.getValue("asset").jsonObject.getValue("version").jsonPrimitive.content);assertTrue(json.getValue("meshes").jsonArray.isNotEmpty())}
                ModelFormat.GLB->{val buffer=ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN);assertEquals(0x46546C67,buffer.int);assertEquals(2,buffer.int);assertEquals(file.length().toInt(),buffer.int)}
            }
        }
    }
    @Test fun sampleExportsPlanSheetsCurrentViewSchedulesAndIssues() {
        val bounds=buildDrawingScene(doc).bounds
        val frame=CanvasFrame(Viewport2D().fit(bounds,1000.0,750.0),OrbitCamera(),levelId=doc.levels.first().id.value,showGrid=false)
        val request=ViewExportRequest(doc,frame,1000,750)
        for(format in DrawingFormat.entries) {
            val file=File(folder,"plan.${format.extension}")
            file.outputStream().use {DrawingExport.write(request,DrawingExportOptions(format=format,scope=if(format==DrawingFormat.PNG)DrawingScope.VIEW else DrawingScope.SHEET,paper=DrawingPaper.A3,scale=100.0,includeUnderlay=false),folder,it)}
            assertTrue(file.length()>100)
            when(format){DrawingFormat.PDF->assertTrue(file.readText(Charsets.ISO_8859_1).startsWith("%PDF"));DrawingFormat.DXF->assertTrue(file.readText().contains("EOF"));DrawingFormat.PNG->{val bitmap=BitmapFactory.decodeFile(file.path);assertNotNull(bitmap);assertEquals(1000,bitmap.width);bitmap.recycle()}}
        }
        val table=ScheduleEngine.build(doc,providedSchedules().first {it.category==ScheduleCategory.BOQ})
        for(format in ScheduleFormat.entries) {
            val out=ByteArrayOutputStream();ScheduleExport.write(doc,table,format,out);val file=File(folder,"boq.${format.name.lowercase()}").apply {writeBytes(out.toByteArray())};assertTrue(file.length()>100)
            if(format==ScheduleFormat.XLSX)ZipInputStream(file.inputStream()).use {zip->var count=0;while(zip.nextEntry!=null)count++;assertTrue(count>=5)}
        }
        // Managed sized fitting definitions belong to project archives; reusable stock overrides use the device catalogue contract.
        val reusable=doc.libraryItems.filter {BuiltInLibrary.find(it.id)!=null}
        val libraryBytes=com.eezydraw.core.data.LibraryArchive.encode(LibraryCatalog(items=reusable))
        assertEquals(reusable,com.eezydraw.core.data.LibraryArchive.decode(libraryBytes.inputStream()).items)
        val context=ApplicationProvider.getApplicationContext<Context>()
        for(format in listOf("pdf","csv","png")) {
            val issue=doc.issues.first {it.clash==null}
            val out=ByteArrayOutputStream();IssueMedia.export(context,doc,format,if(format=="png")issue.id else null,out)
            File(folder,"issues.$format").writeBytes(out.toByteArray());assertTrue(out.size()>100)
        }
    }
}
