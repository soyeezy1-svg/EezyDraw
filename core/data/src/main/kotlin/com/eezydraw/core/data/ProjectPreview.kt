package com.eezydraw.core.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.eezydraw.core.model.ElementBody
import com.eezydraw.core.model.Point2
import com.eezydraw.core.model.ProjectDocument
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** A preview of the stored room geometry; empty projects receive a labelled document monogram. */
internal fun renderProjectPreview(document: ProjectDocument): ByteArray {
    val bitmap = Bitmap.createBitmap(480, 300, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(244, 247, 252))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(225, 233, 245)
        paint.strokeWidth = 1f
        for (x in 0..480 step 24) canvas.drawLine(x.toFloat(), 0f, x.toFloat(), 246f, paint)
        for (y in 0..246 step 24) canvas.drawLine(0f, y.toFloat(), 480f, y.toFloat(), paint)
        val rooms = document.elements.mapNotNull { element ->
            val room = element.body as? ElementBody.Room ?: return@mapNotNull null
            val rotation = element.transform.rotation.z
            room.boundary.outer.map { point ->
                val x = point.x * element.transform.scale.x
                val y = point.y * element.transform.scale.y
                Point2(
                    x * cos(rotation) - y * sin(rotation) + element.transform.translation.x,
                    x * sin(rotation) + y * cos(rotation) + element.transform.translation.y,
                )
            }
        }
        if (rooms.isNotEmpty()) {
            val points = rooms.flatten()
            val minX = points.minOf { it.x }
            val minY = points.minOf { it.y }
            val width = (points.maxOf { it.x } - minX).coerceAtLeast(1.0)
            val height = (points.maxOf { it.y } - minY).coerceAtLeast(1.0)
            val scale = minOf(400.0 / width, 180.0 / height)
            val left = (480 - width * scale) / 2
            val top = (246 - height * scale) / 2
            rooms.forEach { polygon ->
                val path = Path()
                polygon.forEachIndexed { index, point ->
                    val x = (left + (point.x - minX) * scale).toFloat()
                    val y = (top + (point.y - minY) * scale).toFloat()
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(45, 36, 95, 193)
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 4f
                paint.color = Color.rgb(36, 95, 193)
                canvas.drawPath(path, paint)
            }
        } else {
            paint.style = Paint.Style.FILL
            paint.textSize = 64f
            paint.color = Color.rgb(36, 95, 193)
            paint.textAlign = Paint.Align.CENTER
            val initials = document.project.name.trim().split(Regex("\\s+")).take(2)
                .joinToString("") { it.take(1) }.uppercase(Locale.ROOT)
            canvas.drawText(initials, 240f, 140f, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawRect(0f, 246f, 480f, 300f, paint)
        paint.color = Color.rgb(16, 29, 50)
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 22f
        val title = document.project.name.let { name ->
            val count = paint.breakText(name, true, 436f, null)
            if (count < name.length) name.take((count - 1).coerceAtLeast(0)) + "…" else name
        }
        canvas.drawText(title, 22f, 280f, paint)
        return ByteArrayOutputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw ProjectStorageException("The project preview could not be saved.")
            }
            output.toByteArray()
        }
    } finally {
        bitmap.recycle()
    }
}
