package com.eezydraw.storage

import com.eezydraw.core.geometry.*
import com.eezydraw.core.model.*
import java.io.*
import java.time.LocalDate
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class ScheduleExportTest {
    private val p=ProjectId("schedules");private val layer=LayerId("base");private val level=LevelId("g")
    private val item=LibraryItem(LibraryItemId("test-device"),"Device, quoted",Discipline.ELECTRICAL,"Devices",Size3(.2,.1,.3),unitRates=mapOf("USD" to 2.5))
    private val doc=ProjectDocument(Project(p,"Schedule project",0,0),levels=listOf(Level(level,p,"Ground",0.0,.2,3.0)),
        layers=listOf(Layer(layer,p,"Base")),libraryItems=listOf(item),elements=List(80){i->Element(ElementId("e$i"),p,layer,ElementBody.Component(item.id,item.defaultSize),level,name="Device $i")})
    private val definition=providedSchedules().last().copy(grouping=listOf(ScheduleGroup.ITEM),levels=setOf(level))
    private val folder=File("/tmp/eezy-schedule-export").apply {mkdirs()}
    @Test fun exportsEveryFormatWithMetadataNumericCellsAndMultipagePDF() {
        val table=ScheduleEngine.build(doc,definition)
        for(format in ScheduleFormat.entries) {
            val output=ByteArrayOutputStream();ScheduleExport.write(doc,table,format,output,LocalDate.of(2026,11,8))
            val bytes=output.toByteArray();assertTrue(bytes.size>100);File(folder,"boq.${format.extension}").writeBytes(bytes)
            if(format==ScheduleFormat.PDF) {val text=bytes.toString(Charsets.ISO_8859_1);assertTrue(text.startsWith("%PDF-1.4"));assertTrue(Regex("/Count ([0-9]+)").find(text)!!.groupValues[1].toInt()>1)}
            if(format==ScheduleFormat.CSV) {val text=bytes.toString(Charsets.UTF_8);assertTrue(text.contains("2026-11-08"));assertTrue(text.contains("Ground"));assertTrue(text.contains("200.00"));assertTrue(text.contains("Device, quoted"))}
            if(format==ScheduleFormat.XLSX) {
                val entries=mutableMapOf<String,String>();ZipInputStream(ByteArrayInputStream(bytes)).use {zip->while(true){val entry=zip.nextEntry ?: break;entries[entry.name]=zip.readBytes().toString(Charsets.UTF_8)}}
                assertTrue("[Content_Types].xml" in entries);val sheet=entries["xl/worksheets/sheet1.xml"]!!
                assertTrue(sheet.contains("<v>2.5</v>"));assertTrue(sheet.contains("Schedule project"));assertTrue(sheet.contains("Ground"));assertFalse(sheet.contains("<f>"))
            }
        }
    }
    @Test fun csvNeutralizesSpreadsheetFormulaTextAndTotalsCanBeExcluded() {
        val source=doc.copy(libraryItems=listOf(item.copy(unitRates=mapOf("USD" to 2.123456))),elements=listOf(doc.elements.first().copy(name="=HYPERLINK(\"bad\")")))
        val table=ScheduleEngine.build(source,definition.copy(totals=false))
        val output=ByteArrayOutputStream();ScheduleExport.write(source,table,ScheduleFormat.CSV,output)
        val csv=output.toString("UTF-8");assertTrue(csv.contains("'=HYPERLINK"));assertFalse(csv.contains("Priced total"));assertTrue(csv.contains("2.123456"))
    }
}
