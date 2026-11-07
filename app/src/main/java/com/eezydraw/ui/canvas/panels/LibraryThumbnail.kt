package com.eezydraw.ui.canvas.panels

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.eezydraw.R
import com.eezydraw.core.model.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Only visible lazy-list entries compile a tiny vector thumbnail; no bitmap/GL/I/O work is needed. */
@Composable
internal fun LibraryThumbnail(item: LibraryItem, modifier: Modifier = Modifier) {
    val photo by produceState<ImageBitmap?>(null,item.thumbnailBase64) {
        value=withContext(Dispatchers.Default) {runCatching {
            item.thumbnailBase64?.let {encoded->
                val bytes=Base64.decode(encoded,Base64.DEFAULT)
                val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                var sample=1
                while(maxOf(bounds.outWidth,bounds.outHeight)/sample>256)sample*=2
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {inSampleSize=sample})?.asImageBitmap()
            }
        }.getOrNull()}
    }
    if(photo!=null) {Image(photo!!,"Preview of ${item.name}",modifier);return}
    val lines = remember(item.symbolGeometry, item.geometry, item.defaultSize, item.geometrySize) {
        val envelope = item.geometrySize ?: item.defaultSize
        thumbnailLines(item.symbolGeometry.ifEmpty { item.geometry }).map { (a,b) ->
            fun scaled(p:Point2)=Point2(p.x*item.defaultSize.width/envelope.width,p.y*item.defaultSize.depth/envelope.depth)
            scaled(a) to scaled(b)
        }.ifEmpty { rectangle(0.0, 0.0, item.defaultSize.width, item.defaultSize.depth) }
    }
    val bounds = remember(lines) {
        val points = lines.flatMap { listOf(it.first, it.second) }
        listOf(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    }
    val color = MaterialTheme.colorScheme.primary
    val label = stringResource(R.string.library_item_preview, item.name)
    Canvas(modifier.semantics { contentDescription = label }) {
        val width = (bounds[2] - bounds[0]).coerceAtLeast(1e-9)
        val depth = (bounds[3] - bounds[1]).coerceAtLeast(1e-9)
        val scale = min((size.width - 12f).coerceAtLeast(1f) / width, (size.height - 12f).coerceAtLeast(1f) / depth)
        fun screen(point: Point2): Offset = Offset(
            (size.width / 2 + (point.x - (bounds[0] + bounds[2]) / 2) * scale).toFloat(),
            (size.height / 2 - (point.y - (bounds[1] + bounds[3]) / 2) * scale).toFloat(),
        )
        lines.forEach { (a, b) -> drawLine(color, screen(a), screen(b), strokeWidth = 1.6f * density, cap = StrokeCap.Round) }
    }
}

private typealias ThumbnailLine = Pair<Point2, Point2>

private fun rectangle(x: Double, y: Double, width: Double, depth: Double): List<ThumbnailLine> =
    outline(listOf(Point2(x, y), Point2(x + width, y), Point2(x + width, y + depth), Point2(x, y + depth)), true)

private fun outline(points: List<Point2>, closed: Boolean): List<ThumbnailLine> =
    (if (closed) points + points.first() else points).zipWithNext()

private fun thumbnailLines(shapes: List<Shape>): List<ThumbnailLine> = shapes.flatMap { shape ->
    fun arc(rx: Double, ry: Double, start: Double = 0.0, sweep: Double = PI * 2): List<ThumbnailLine> =
        outline((0..32).map { index ->
            val angle = start + sweep * index / 32
            Point2(cos(angle) * rx, sin(angle) * ry)
        }, false)
    when (shape) {
        is Shape.Line -> listOf(Point2(shape.start.x, shape.start.y) to Point2(shape.end.x, shape.end.y))
        is Shape.Polyline -> outline(shape.points.map { Point2(it.x, it.y) }, shape.closed)
        is Shape.Polygon -> outline(shape.boundary.outer, true) + shape.boundary.holes.flatMap { outline(it, true) }
        is Shape.Circle -> arc(shape.radius, shape.radius)
        is Shape.Ellipse -> arc(shape.radiusX, shape.radiusY)
        is Shape.Arc -> arc(shape.radius, shape.radius, shape.startRadians, shape.sweepRadians)
        is Shape.Box -> rectangle(0.0, 0.0, shape.size.width, shape.size.depth)
        is Shape.Cylinder -> arc(shape.radius, shape.radius)
        is Shape.Sphere -> arc(shape.radius, shape.radius)
        else -> emptyList()
    }
}
