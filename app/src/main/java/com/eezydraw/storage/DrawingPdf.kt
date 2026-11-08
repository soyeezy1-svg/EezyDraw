package com.eezydraw.storage

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.eezydraw.core.model.Point2
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.DeflaterOutputStream

/** Single-page PDF: vector plans/decorations plus embedded, alpha-masked underlay/view images.
 * Coordinates supplied by callers are paper points with a top-left origin.
 */
internal class DrawingPdf(private val width: Int, private val height: Int) {
    private val content = StringBuilder()
    private data class Image(val width: Int, val height: Int, val rgb: ByteArray, val alpha: ByteArray)
    private val images = mutableListOf<Image>()
    private fun n(value: Double) = String.format(Locale.ROOT, "%.6f", value)
    fun save() { content.append("q\n") }
    fun restore() { content.append("Q\n") }
    fun clip(rect: RectF) {
        content.append("${n(rect.left.toDouble())} ${n(height-rect.bottom.toDouble())} ${n(rect.width().toDouble())} ${n(rect.height().toDouble())} re W n\n")
    }
    private fun colour(argb: Int) {
        val rgb = "${n(Color.red(argb)/255.0)} ${n(Color.green(argb)/255.0)} ${n(Color.blue(argb)/255.0)}"
        content.append("$rgb RG $rgb rg\n")
    }
    fun line(a: Point2, b: Point2, argb: Int = Color.BLACK, weight: Double = .5) {
        colour(argb)
        content.append("${n(weight)} w ${n(a.x)} ${n(height-a.y)} m ${n(b.x)} ${n(height-b.y)} l S\n")
    }
    fun rect(left: Double, top: Double, right: Double, bottom: Double, fill: Boolean = false) {
        colour(Color.BLACK)
        content.append("0.6 w ${n(left)} ${n(height-bottom)} ${n(right-left)} ${n(bottom-top)} re ${if(fill) "f" else "S"}\n")
    }
    fun text(value: String, x: Double, y: Double, size: Double = 9.0) {
        // Base-14 Helvetica with an explicit WinAnsi encoding. Escape bytes, not Kotlin chars.
        val encoded = value.replace('\n', ' ').replace('\r', ' ').toByteArray(charset("windows-1252"))
        val literal = encoded.joinToString("") { byte ->
            val c = byte.toInt() and 255
            when { c == 40 || c == 41 || c == 92 -> "\\${c.toChar()}"; c !in 32..126 -> "\\"+c.toString(8).padStart(3, '0'); else -> c.toChar().toString() }
        }
        colour(Color.BLACK)
        content.append("BT /F1 ${n(size)} Tf 1 0 0 1 ${n(x)} ${n(height-y)} Tm ($literal) Tj ET\n")
    }
    fun image(bitmap: Bitmap, corners: List<Point2>, opacity: Double = 1.0) {
        require(corners.size == 4 && opacity in 0.0..1.0)
        val rgb = ByteArrayOutputStream(); val alpha = ByteArrayOutputStream()
        DeflaterOutputStream(rgb).use { colourStream -> DeflaterOutputStream(alpha).use { alphaStream ->
            val pixels = IntArray(bitmap.width); val colours = ByteArray(bitmap.width*3); val mask = ByteArray(bitmap.width)
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
                for (x in pixels.indices) {
                    colours[x*3] = Color.red(pixels[x]).toByte(); colours[x*3+1] = Color.green(pixels[x]).toByte(); colours[x*3+2] = Color.blue(pixels[x]).toByte()
                    mask[x] = (Color.alpha(pixels[x])*opacity).toInt().toByte()
                }
                colourStream.write(colours); alphaStream.write(mask)
            }
        } }
        val index = images.size
        images += Image(bitmap.width, bitmap.height, rgb.toByteArray(), alpha.toByteArray())
        val topLeft = corners[0]; val topRight = corners[1]; val bottomLeft = corners[3]
        content.append("q ${n(topRight.x-topLeft.x)} ${n(topLeft.y-topRight.y)} ${n(topLeft.x-bottomLeft.x)} ${n(bottomLeft.y-topLeft.y)} ${n(bottomLeft.x)} ${n(height-bottomLeft.y)} cm /Im$index Do Q\n")
    }
    fun write(output:OutputStream)=writePages(listOf(this),output)
    companion object {
     fun writePages(pages:List<DrawingPdf>,output:OutputStream) {
      var next=4
      data class PageIds(val page:Int,val content:Int,val images:List<Pair<Int,Int>>)
      val ids=pages.map {p->PageIds(next++,next++,p.images.map {next++ to next++})}
      val offsets=LongArray(next);var count=0L
      fun bytes(b:ByteArray){output.write(b);count+=b.size}
      fun raw(s:String)=bytes(s.toByteArray(Charsets.US_ASCII))
      fun obj(id:Int,s:String){offsets[id]=count;raw("$id 0 obj\n$s\nendobj\n")}
      fun stream(id:Int,dict:String,b:ByteArray){offsets[id]=count;raw("$id 0 obj\n<< $dict /Length ${b.size} >>\nstream\n");bytes(b);raw("\nendstream\nendobj\n")}
      raw("%PDF-1.4\n% EEZY DRAW\n");obj(1,"<< /Type /Catalog /Pages 2 0 R >>");obj(2,"<< /Type /Pages /Kids [${ids.joinToString(" ") {"${it.page} 0 R"}}] /Count ${pages.size} >>");obj(3,"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
      pages.zip(ids).forEach {(p,ids)->
       val resources=ids.images.mapIndexed {i,pair->"/Im$i ${pair.first} 0 R"}.joinToString(" ")
       obj(ids.page,"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${p.width} ${p.height}] /Resources << /Font << /F1 3 0 R >> /XObject << $resources >> >> /Contents ${ids.content} 0 R >>")
       stream(ids.content,"",p.content.toString().toByteArray(Charsets.US_ASCII))
       p.images.zip(ids.images).forEach {(image,pair)->
        stream(pair.first,"/Type /XObject /Subtype /Image /Width ${image.width} /Height ${image.height} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /SMask ${pair.second} 0 R",image.rgb)
        stream(pair.second,"/Type /XObject /Subtype /Image /Width ${image.width} /Height ${image.height} /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode",image.alpha)
       }
      }
      val xref=count;raw("xref\n0 ${offsets.size}\n0000000000 65535 f \n");for(i in 1 until offsets.size)raw(String.format(Locale.ROOT,"%010d 00000 n \n",offsets[i]));raw("trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
     }
    }
}
