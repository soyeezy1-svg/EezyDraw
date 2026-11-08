package com.eezydraw.storage

import android.graphics.*
import androidx.core.graphics.createBitmap
import com.eezydraw.core.geometry.*
import com.eezydraw.core.model.*
import java.io.File
import java.io.OutputStream
import java.util.Locale
import kotlin.math.*

enum class DrawingFormat(val extension:String,val mime:String) { PDF("pdf","application/pdf"),DXF("dxf","application/dxf"),PNG("png","image/png") }
enum class DrawingScope { VIEW,LEVEL,SHEET }
data class DrawingExportOptions(val format:DrawingFormat=DrawingFormat.PDF,val scope:DrawingScope=DrawingScope.VIEW,
    val paper:DrawingPaper=DrawingPaper.A3,val scale:Double=100.0,val landscape:Boolean=true,val units:ModelUnits=ModelUnits.MM,
    val includeUnderlay:Boolean=true,val titleBlock:Boolean=true,val northArrow:Boolean=true,val scaleBar:Boolean=true) {
    init {require(scale.isFinite() && scale in 1.0..100000.0)}
}
data class DrawingSheet(val width:Int,val height:Int,val drawing:RectF,val centre:Point2,val pointsPerMetre:Double) {
    fun project(p:Point3)=Point2(drawing.centerX()+(p.x-centre.x)*pointsPerMetre,drawing.centerY()-(p.y-centre.y)*pointsPerMetre)
    fun clip()=PlanClip(centre.x-drawing.width()/2/pointsPerMetre,centre.y-drawing.height()/2/pointsPerMetre,centre.x+drawing.width()/2/pointsPerMetre,centre.y+drawing.height()/2/pointsPerMetre)
}
object DrawingExport {
    const val POINTS_PER_MM=72.0/25.4
    fun sheet(options:DrawingExportOptions,bounds:Bounds3):DrawingSheet {
        val w=if(options.landscape)options.paper.width else options.paper.height;val h=if(options.landscape)options.paper.height else options.paper.width
        val width=(w*POINTS_PER_MM).roundToInt();val height=(h*POINTS_PER_MM).roundToInt();val margin=(10*POINTS_PER_MM).toFloat()
        val footer=if(options.titleBlock || options.northArrow || options.scaleBar)(35*POINTS_PER_MM).toFloat()else 0f
        val drawing=RectF(margin,margin,width-margin,height-margin-footer)
        val factor=if(options.scope==DrawingScope.SHEET)1000*POINTS_PER_MM/options.scale else min(drawing.width()/max(bounds.max.x-bounds.min.x,.01),drawing.height()/max(bounds.max.y-bounds.min.y,.01))*.95
        return DrawingSheet(width,height,drawing,Point2(bounds.min.x+(bounds.max.x-bounds.min.x)/2,bounds.min.y+(bounds.max.y-bounds.min.y)/2),factor)
    }
    fun write(request:ViewExportRequest,options:DrawingExportOptions,assetRoot:File,output:OutputStream) {
        val document=request.document.copy(project=request.document.project.copy(settings=request.document.project.settings.copy(showHiddenObjects=false)))
        val level=request.frame.levelId?.let(::LevelId)
        if(options.scope!=DrawingScope.VIEW)require(level!=null){"Choose a current level before exporting its plan"}
        val underlays=if(options.includeUnderlay)ExportUnderlays.load(document,request.frame.levelId,assetRoot,if(options.format==DrawingFormat.DXF)256 else 2048)else emptyList()
        try {
            val clean=request.copy(document=document,frame=request.frame.copy(selectionScene=null,selectionDelta=null))
            if(options.format==DrawingFormat.PNG) {
                require(options.scope==DrawingScope.VIEW){"PNG exports the current view"}
                val background=ExportUnderlays.background(clean,underlays)
                try {ViewExport.write(clean.copy(underlayBackground=background),ViewExportFormat.PNG,output)}finally {background?.recycle()};return
            }
            val segments=exportPlanSegments(document,level)
            require(segments.size<=200000){"The plan exceeds 200,000 edges. Export a smaller view or level."}
            val points=segments.flatMap {listOf(it.a,it.b)}+underlays.flatMap {it.corners}
            val bounds=if(points.isEmpty())Bounds3(Point3(),Point3(1.0,1.0))else Bounds3(Point3(points.minOf {it.x},points.minOf {it.y}),Point3(points.maxOf {it.x},points.maxOf {it.y}))
            val layout=sheet(options,bounds)
            if(options.format==DrawingFormat.DXF) {
                val clip=when(options.scope){DrawingScope.LEVEL->null;DrawingScope.SHEET->layout.clip();DrawingScope.VIEW->{val a=clean.frame.viewport.screenToWorld(0.0,request.height.toDouble(),request.width.toDouble(),request.height.toDouble());val b=clean.frame.viewport.screenToWorld(request.width.toDouble(),0.0,request.width.toDouble(),request.height.toDouble());PlanClip(a.x,a.y,b.x,b.y)}}
                val rasters=if(underlays.isEmpty())emptyList()else {
                    val corners=underlays.flatMap {it.corners};val rect=clip ?: PlanClip(corners.minOf {it.x},corners.minOf {it.y},corners.maxOf {it.x}.coerceAtLeast(corners.minOf {it.x}+.001),corners.maxOf {it.y}.coerceAtLeast(corners.minOf {it.y}+.001))
                    val bitmap=createBitmap(256,256);bitmap.eraseColor(Color.WHITE)
                    try {ExportUnderlays.paint(Canvas(bitmap),underlays){Point2((it.x-rect.minX)/(rect.maxX-rect.minX)*256,(rect.maxY-it.y)/(rect.maxY-rect.minY)*256)}
                        val pixels=IntArray(256*256);bitmap.getPixels(pixels,0,256,0,0,256,256)
                        listOf(DxfRaster(listOf(Point2(rect.minX,rect.maxY),Point2(rect.maxX,rect.maxY),Point2(rect.maxX,rect.minY),Point2(rect.minX,rect.minY)),256,256,pixels))
                    }finally {bitmap.recycle()}
                }
                DxfExport.write(document,level,options.units,output,clip,rasters);return
            }
            val pdf=DrawingPdf(layout.width,layout.height)
            var actualScale=layout.pointsPerMetre
            pdf.save();pdf.clip(layout.drawing)
            if(options.scope==DrawingScope.VIEW) {
                val paperRequest=clean.copy(frame=clean.frame.copy(dark=false));val background=ExportUnderlays.background(paperRequest,underlays)
                val bitmap=try {ViewExport.render(paperRequest.copy(underlayBackground=background))}finally {background?.recycle()}
                try {val fit=min(layout.drawing.width()/bitmap.width,layout.drawing.height()/bitmap.height);val width=bitmap.width*fit;val height=bitmap.height*fit
                    val left=layout.drawing.centerX()-width/2;val top=layout.drawing.centerY()-height/2
                    pdf.image(bitmap,listOf(Point2(left.toDouble(),top.toDouble()),Point2((left+width).toDouble(),top.toDouble()),Point2((left+width).toDouble(),(top+height).toDouble()),Point2(left.toDouble(),(top+height).toDouble())))
                    actualScale=if(!request.frame.is3D)request.frame.viewport.pixelsPerMetre*width/request.width else if(request.frame.orbit.projection==Projection.ORTHOGRAPHIC)height/request.frame.orbit.orthographicHeight else 0.0
                }finally {bitmap.recycle()}
            }else {
                for(underlay in underlays)pdf.image(underlay.bitmap,underlay.corners.map(layout::project),underlay.record.opacity)
                for(segment in segments)pdf.line(layout.project(segment.a),layout.project(segment.b),segment.colorArgb.toInt())
            }
            pdf.restore()
            decorations(pdf,layout,options,document,level,actualScale,!request.frame.is3D || options.scope!=DrawingScope.VIEW)
            pdf.write(output)
        }finally {underlays.forEach {it.close()}}
    }
    private fun decorations(pdf:DrawingPdf,layout:DrawingSheet,options:DrawingExportOptions,document:ProjectDocument,level:LevelId?,factor:Double,plan:Boolean) {
        val left=layout.drawing.left.toDouble();val bottom=layout.height-10*POINTS_PER_MM;val top=layout.drawing.bottom+8.0
        if(options.titleBlock){pdf.rect(left,top,layout.drawing.right.toDouble(),bottom)
            pdf.save();pdf.clip(RectF(left.toFloat(),top.toFloat(),layout.drawing.centerX(),bottom.toFloat()))
            pdf.text(document.project.name,left+8,top+20,13.0)
            pdf.text(document.levels.find {it.id==level}?.name ?: "Current view",left+8,top+36)
            val scale=if(factor>0)"1:%.2f".format(Locale.ROOT,1000*POINTS_PER_MM/factor)else "Perspective / no uniform scale"
            pdf.text("${options.paper.name} · $scale",left+8,top+50)
            pdf.text("${document.project.projectNumber} ${document.project.author} · ${java.time.LocalDate.now()}",left+8,top+64)
            pdf.restore()
        }
        if(options.northArrow && plan){val x=layout.drawing.right-28.0;val y=top+35
            pdf.line(Point2(x,y+15),Point2(x,y-10));pdf.line(Point2(x,y-10),Point2(x-5,y));pdf.line(Point2(x,y-10),Point2(x+5,y));pdf.text("N",x-4,y-16)
        }
        if(options.scaleBar && factor>0) {
            val maxLength=min(80*POINTS_PER_MM,layout.drawing.width()*.3)/factor
            val magnitude=10.0.pow(floor(log10(maxLength.coerceAtLeast(.000001))))
            val length=listOf(1.0,2.0,5.0,10.0).map {it*magnitude}.lastOrNull {it<=maxLength} ?: magnitude
            val width=length*factor;val x=layout.drawing.centerX()+12.0;val y=bottom-17
            pdf.rect(x,y,x+width/2,y+6,true);pdf.rect(x,y,x+width,y+6)
            pdf.text("0",x,y-3);pdf.text("%.3g m".format(Locale.ROOT,length),x+width-20,y-3)
        }
    }
}
