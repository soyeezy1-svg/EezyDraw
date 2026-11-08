package com.eezydraw.storage

import android.graphics.*
import androidx.core.graphics.createBitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.eezydraw.core.editing.projectPrecisionPoint
import com.eezydraw.core.model.*
import com.eezydraw.ui.canvas.CanvasFrame
import java.io.File
import java.io.Closeable
import kotlin.math.max

internal data class ExportUnderlay(val record:Underlay,val bitmap:Bitmap,val sourceWidth:Double,val sourceHeight:Double):Closeable {
    val corners:List<Point3> get()=listOf(Point2(0.0,sourceHeight),Point2(sourceWidth,sourceHeight),Point2(sourceWidth,0.0),Point2(0.0,0.0)).map {p->measurementToWorld(record.transform,Point3(p.x*record.metresPerSourceUnit,p.y*record.metresPerSourceUnit))}
    override fun close(){bitmap.recycle()}
}
internal object ExportUnderlays {
    fun load(document:ProjectDocument,level:String?,root:File,maxSide:Int):List<ExportUnderlay> {
        val result=mutableListOf<ExportUnderlay>()
        try {
            for(u in document.underlays) {
                if(u.opacity<=0 || document.layers.find {it.id==u.layerId}?.visible==false || u.levelId in document.project.settings.hiddenLevelIds || level!=null && u.levelId!=null && u.levelId?.value!=level)continue
                val asset=requireNotNull(document.assets.find {it.id==u.assetId}){"The underlay asset is missing"}
                val file=File(root,asset.relativePath).canonicalFile
                require(file.path.startsWith(root.canonicalPath+File.separator) && file.isFile){"Underlay file is unavailable: ${asset.relativePath}"}
                val decoded=when(u.format) {
                    UnderlayFormat.IMAGE->{val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,bounds)
                        require(bounds.outWidth>0 && bounds.outHeight>0){"The underlay image cannot be decoded"}
                        var sample=1;while(max(bounds.outWidth,bounds.outHeight)/sample>maxSide)sample*=2
                        val bitmap=requireNotNull(BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply {inSampleSize=sample})){"The underlay image cannot be decoded"}
                        ExportUnderlay(u,bitmap,bounds.outWidth.toDouble(),bounds.outHeight.toDouble())}
                    UnderlayFormat.PDF->ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use {fd->PdfRenderer(fd).use {pdf->
                        require(u.pageIndex in 0 until pdf.pageCount){"The stored underlay PDF page is unavailable"}
                        pdf.openPage(u.pageIndex).use {page->val factor=maxSide.toDouble()/max(page.width,page.height)
                            val bitmap=createBitmap((page.width*factor).toInt().coerceAtLeast(1),(page.height*factor).toInt().coerceAtLeast(1))
                            bitmap.eraseColor(Color.WHITE)
                            try {page.render(bitmap,null,Matrix().apply {setScale(factor.toFloat(),factor.toFloat())},PdfRenderer.Page.RENDER_MODE_FOR_PRINT)}catch(e:Exception){bitmap.recycle();throw e}
                            ExportUnderlay(u,bitmap,page.width.toDouble(),page.height.toDouble())
                        }
                    }}
                    else->error("${u.format} underlays cannot be rendered. Disable Include underlay or use a PDF/image underlay.")
                }
                if(result.sumOf {it.bitmap.width.toLong()*it.bitmap.height}+decoded.bitmap.width.toLong()*decoded.bitmap.height>8_388_608L){decoded.close();error("Underlays exceed the 32 MB decoded-image budget. Hide some underlays or disable Include underlay.")}
                result+=decoded
            }
            return result
        }catch(e:Exception){result.forEach {it.close()};throw e}
    }
    fun paint(canvas:Canvas,underlays:List<ExportUnderlay>,project:(Point3)->Point2?) {
        for(u in underlays){val corners=u.corners.mapNotNull(project);if(corners.size!=4)continue
            val matrix=Matrix();val src=floatArrayOf(0f,0f,u.bitmap.width.toFloat(),0f,u.bitmap.width.toFloat(),u.bitmap.height.toFloat(),0f,u.bitmap.height.toFloat())
            val dest=corners.flatMap {listOf(it.x.toFloat(),it.y.toFloat())}.toFloatArray()
            if(matrix.setPolyToPoly(src,0,dest,0,4))canvas.drawBitmap(u.bitmap,matrix,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {alpha=(u.record.opacity*255).toInt()})
        }
    }
    fun background(request:ViewExportRequest,underlays:List<ExportUnderlay>):Bitmap? {
        if(underlays.isEmpty())return null
        val factor=minOf(1.0,ViewExport.MAX_SIDE.toDouble()/max(request.width,request.height))
        val width=(request.width*factor).toInt().coerceAtLeast(1);val height=(request.height*factor).toInt().coerceAtLeast(1)
        val bitmap=createBitmap(width,height)
        bitmap.eraseColor(if(request.frame.dark)Color.rgb(14,18,26)else Color.rgb(247,250,252))
        paint(Canvas(bitmap),underlays){p->projectPrecisionPoint(p,request.frame.viewport,request.frame.orbit,request.frame.is3D,request.width.toDouble(),request.height.toDouble())?.let {Point2(it.x*factor,it.y*factor)}}
        return bitmap
    }
}
