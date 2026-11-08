package com.eezydraw.storage

import android.graphics.Color
import com.eezydraw.core.geometry.*
import com.eezydraw.core.model.*
import java.io.OutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ScheduleFormat(val extension:String,val mime:String) {
    CSV("csv","text/csv"), XLSX("xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"), PDF("pdf","application/pdf")
}

/** All formats consume the same frozen table. Read-only export never mutates model or rates. */
object ScheduleExport {
    private val numeric=setOf(ScheduleColumn.QUANTITY,ScheduleColumn.LENGTH,ScheduleColumn.ALLOWED_LENGTH,ScheduleColumn.FITTINGS,
        ScheduleColumn.AREA,ScheduleColumn.WIDTH,ScheduleColumn.HEIGHT,ScheduleColumn.DEPTH,ScheduleColumn.RATE,ScheduleColumn.COST)

    private fun exportedCell(row:ScheduleRow,column:ScheduleColumn):String {
        val number=when(column) {
            ScheduleColumn.QUANTITY->row.quantity;ScheduleColumn.LENGTH->row.length
            ScheduleColumn.ALLOWED_LENGTH->row.allowedLength;ScheduleColumn.AREA->row.area
            ScheduleColumn.RATE->row.rate
            else->null
        }
        return number?.let {java.math.BigDecimal.valueOf(it).stripTrailingZeros().toPlainString()} ?: row.cells[column].orEmpty()
    }

    fun write(document:ProjectDocument,table:ScheduleTable,format:ScheduleFormat,output:OutputStream,date:LocalDate=LocalDate.now()) {
        val d=table.definition
        val metadata=listOf("Project" to document.project.name,"Schedule" to d.name,"Date" to date.toString(),
            "Levels" to (if(d.levels.isEmpty())"All levels"else document.levels.filter {it.id in d.levels}.joinToString {it.name}),
            "Systems" to (if(d.systems.isEmpty())"All systems"else document.systems.filter {it.id in d.systems}.joinToString {it.name}),
            "Scope" to if(d.visibleOnly)"Visible objects"else "All objects", "Allowance" to "${ScheduleEngine.number(table.allowancePercent)}%", "Currency" to table.currency)
        val totals=totals(table)
        when(format){
            ScheduleFormat.CSV->{
                val writer=output.bufferedWriter()
                fun safe(s:String)=if(s.trimStart().firstOrNull() in listOf('=','+','-','@'))"'$s"else s
                fun row(values:List<String>){writer.appendLine(values.joinToString(","){"\"${it.replace("\"","\"\"")}\""})}
                metadata.forEach {(a,b)->row(listOf(a,safe(b)))}
                row(d.columns.map {it.label})
                table.rows.forEach {r->row(d.columns.map {c->val value=exportedCell(r,c);if(c in numeric && value.toDoubleOrNull()!=null)value else safe(value)})}
                if(d.totals)totals.forEach {(label,value)->row(listOf(label,value))}
                writer.flush()
            }
            ScheduleFormat.XLSX->xlsx(table,metadata,if(d.totals)totals else emptyList(),output)
            ScheduleFormat.PDF->pdf(table,metadata,if(d.totals)totals else emptyList(),output)
        }
    }

    fun totals(t:ScheduleTable):List<Pair<String,String>> = t.quantities.map {(unit,q)->"Total (${unit.symbol})" to ScheduleEngine.number(q)}+
        listOf("Measured length (m)" to ScheduleEngine.number(t.measuredLength),"Length with allowance (m)" to ScheduleEngine.number(t.allowedLength),
            "Fittings" to t.fittings.toString(),"Area (m²)" to ScheduleEngine.number(t.area))+
        if(t.definition.category==ScheduleCategory.BOQ)listOf("Priced total (${t.currency})" to t.pricedTotal.toPlainString(),"Unpriced rows" to t.unpricedRows.toString())else emptyList()

    private fun xlsx(table:ScheduleTable,metadata:List<Pair<String,String>>,totals:List<Pair<String,String>>,output:OutputStream) {
        fun xml(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").filter {it=='\t'||it=='\n'||it=='\r'||it>=' '}
        fun letter(column:Int):String {var n=column+1;var s="";while(n>0){n--;s=('A'+n%26)+s;n/=26};return s}
        val sheet=buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"9\" topLeftCell=\"A10\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><sheetData>")
            var row=0
            fun add(values:List<Pair<String,Boolean>>,heading:Boolean=false){row++;append("<row r=\"$row\">");values.forEachIndexed {column,(value,number)->val cell="${letter(column)}$row";if(number && value.toDoubleOrNull()!=null)append("<c r=\"$cell\" s=\"2\"><v>$value</v></c>")else append("<c r=\"$cell\" t=\"inlineStr\" s=\"${if(heading)1 else 0}\"><is><t xml:space=\"preserve\">${xml(value)}</t></is></c>")};append("</row>")}
            metadata.forEach {(a,b)->add(listOf(a to false,b to false))}
            add(table.definition.columns.map {it.label to false},true)
            table.rows.forEach {r->add(table.definition.columns.map {c->exportedCell(r,c) to (c in numeric)})}
            totals.forEach {(a,b)->add(listOf(a to false,b to true))}
            append("</sheetData></worksheet>")
        }
        // finish(), rather than close(), leaves the caller's SAF stream ownership unchanged.
        val zip=ZipOutputStream(output)
        fun entry(path:String,text:String){zip.putNextEntry(ZipEntry(path));zip.write(text.toByteArray(Charsets.UTF_8));zip.closeEntry()}
        entry("[Content_Types].xml","""<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""")
        entry("_rels/.rels","""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
        entry("xl/workbook.xml","""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Schedule" sheetId="1" r:id="rId1"/></sheets></workbook>""")
        entry("xl/_rels/workbook.xml.rels","""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
        entry("xl/styles.xml","""<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0"/><xf numFmtId="4" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
        entry("xl/worksheets/sheet1.xml",sheet)
        zip.finish();zip.flush()
    }

    private fun pdf(table:ScheduleTable,metadata:List<Pair<String,String>>,totals:List<Pair<String,String>>,output:OutputStream) {
        val pages=ArrayList<DrawingPdf>()
        for(columns in table.definition.columns.chunked(6)) {
            val width=782.0/columns.size
            fun wrap(value:String,chars:Int)=value.lines().flatMap {it.chunked(chars).ifEmpty {listOf("")}}
            lateinit var page:DrawingPdf;var y=0.0
            fun next(){page=DrawingPdf(842,595);pages+=page;y=26.0;metadata.forEach {(a,b)->wrap("$a: $b",155).forEach {line->page.text(line,30.0,y,8.0);y+=11}};y+=8
                columns.forEachIndexed {i,c->wrap(c.label,(width/4.5).toInt()).forEachIndexed {n,line->page.text(line,30+i*width,y+n*10,8.0)}};y+=30}
            next()
            for(row in table.rows) {
                val cells=columns.map {wrap(row.cells[it].orEmpty(),(width/4.5).toInt().coerceAtLeast(8))}
                val lines=cells.maxOf {it.size};var offset=0
                while(offset<lines){if(y+24>550)next();val count=minOf(lines-offset,((550-y-8)/11).toInt().coerceAtLeast(1))
                    cells.forEachIndexed {i,cell->cell.drop(offset).take(count).forEachIndexed {n,line->page.text(line,30+i*width,y+n*11,8.0)}}
                    y+=count*11+8;page.line(Point2(30.0,y-4),Point2(812.0,y-4),Color.LTGRAY,.3);offset+=count
                }
            }
            for((label,value)in totals){if(y+15>550)next();page.text("$label: $value",30.0,y,9.0);y+=13}
        }
        pages.forEachIndexed {i,p->p.text("${i+1} / ${pages.size}",780.0,578.0,8.0)}
        DrawingPdf.writePages(pages,output)
    }
}
