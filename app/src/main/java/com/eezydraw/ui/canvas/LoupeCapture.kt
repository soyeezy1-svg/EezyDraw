package com.eezydraw.ui.canvas

import android.graphics.Bitmap
import android.opengl.GLES20
import com.eezydraw.core.editing.PointerRect
import com.eezydraw.core.model.Point2
import com.eezydraw.core.geometry.DrawingScene
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor

/** Physical canvas pixels, with the same top-left origin as pointer input. */
data class LoupeSource(val crosshair: Point2, val diameter: Double, val zoom: Double) {
    init {
        require(crosshair.x.isFinite() && crosshair.y.isFinite())
        require(diameter.isFinite() && diameter > 0 && zoom in 2.0..4.0)
    }
}

internal data class CapturedLoupe(val source: LoupeSource, val region: PointerRect, val bitmap: Bitmap,
    val renderFrame: CanvasFrame? = null, val scene: DrawingScene? = null,
) {
    fun matches(scene: DrawingScene?, frame: CanvasFrame) = this.scene === scene &&
        renderFrame?.copy(loupe = null) == frame.copy(loupe = null)
}

/**
 * Read only the small source crop AFTER all native drawing passes, before swapping buffers.
 * No second scene/camera, surface approximation, toolbar pixels or asynchronous PixelCopy.
 * A half-field motion margin lets Compose follow the next input before GL delivery,
 * using the same source pixels. The extra pixel preserves fractional filtered sampling.
 */
internal fun captureLoupe(source: LoupeSource, width: Int, height: Int,
    read: (Int, Int, Int, Int, ByteBuffer) -> Unit = { x, y, w, h, buffer ->
        GLES20.glReadPixels(x, y, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
    },
): CapturedLoupe? {
    if (width <= 0 || height <= 0) return null
    val radius = source.diameter / source.zoom / 2
    val left = floor(source.crosshair.x - 2 * radius - 1).toInt().coerceIn(0, width)
    val top = floor(source.crosshair.y - 2 * radius - 1).toInt().coerceIn(0, height)
    val right = ceil(source.crosshair.x + 2 * radius + 1).toInt().coerceIn(0, width)
    val bottom = ceil(source.crosshair.y + 2 * radius + 1).toInt().coerceIn(0, height)
    val w = right - left; val h = bottom - top
    if (w <= 0 || h <= 0) return null
    val rgba = ByteBuffer.allocateDirect(w * h * 4)
    read(left, height - bottom, w, h, rgba)
    val argb = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        val offset = ((h - 1 - y) * w + x) * 4
        // GL is bottom-up RGBA; Android Bitmap is top-down ARGB, independently of byte order.
        argb[y * w + x] = (rgba.get(offset + 3).toInt() and 255 shl 24) or
            (rgba.get(offset).toInt() and 255 shl 16) or
            (rgba.get(offset + 1).toInt() and 255 shl 8) or (rgba.get(offset + 2).toInt() and 255)
    }
    return CapturedLoupe(source, PointerRect(left.toDouble(), top.toDouble(), right.toDouble(), bottom.toDouble()),
        Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888))
}
