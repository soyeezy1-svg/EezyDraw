package com.eezydraw.core.geometry

import com.eezydraw.core.model.Bounds3
import com.eezydraw.core.model.Point2
import com.eezydraw.core.model.Point3
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Double-precision, Y-up plan camera. Pixels are measured from the top-left of the surface. */
data class Viewport2D(
    val center: Point2 = Point2(0.0, 0.0),
    val pixelsPerMetre: Double = 60.0,
) {
    init { require(pixelsPerMetre.isFinite() && pixelsPerMetre in MIN_SCALE..MAX_SCALE) }

    fun screenToWorld(x: Double, y: Double, width: Double, height: Double): Point2 {
        if (!validSurface(width, height) || !x.isFinite() || !y.isFinite()) return center
        return Point2(finite(center.x + (x - width / 2) / pixelsPerMetre),
            finite(center.y - (y - height / 2) / pixelsPerMetre))
    }

    fun worldToScreen(point: Point2, width: Double, height: Double): Point2 = Point2(
        finite((point.x - center.x) * pixelsPerMetre + finite(width) / 2),
        finite(-(point.y - center.y) * pixelsPerMetre + finite(height) / 2),
    )

    /** Positive touch deltas move the drawing with the finger. */
    fun pan(dxPixels: Double, dyPixels: Double): Viewport2D {
        if (!dxPixels.isFinite() || !dyPixels.isFinite()) return this
        return copy(center = Point2(finite(center.x - dxPixels / pixelsPerMetre),
            finite(center.y + dyPixels / pixelsPerMetre)))
    }

    fun zoom(factor: Double, anchorX: Double, anchorY: Double, width: Double, height: Double): Viewport2D {
        if (!factor.isFinite() || factor <= 0 || !validSurface(width, height) ||
            !anchorX.isFinite() || !anchorY.isFinite()) return this
        val scale = (pixelsPerMetre * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val anchor = screenToWorld(anchorX, anchorY, width, height)
        return copy(center = Point2(
            finite(anchor.x - (anchorX - width / 2) / scale),
            finite(anchor.y + (anchorY - height / 2) / scale)), pixelsPerMetre = scale)
    }

    fun fit(bounds: Bounds3, width: Double, height: Double, paddingPixels: Double = 48.0): Viewport2D {
        if (!validSurface(width, height) || !paddingPixels.isFinite()) return this
        val usableWidth = max(1.0, width - 2 * max(0.0, paddingPixels))
        val usableHeight = max(1.0, height - 2 * max(0.0, paddingPixels))
        val actualSpanX = finite(bounds.max.x - bounds.min.x)
        val actualSpanY = finite(bounds.max.y - bounds.min.y)
        // Only a point needs an arbitrary viewing area. A short edge or tiny window must
        // retain its actual size, and a zero-width axis must not constrain the other axis.
        val pointBounds = actualSpanX == 0.0 && actualSpanY == 0.0
        val spanX = if (pointBounds) 0.1 else max(1e-12, actualSpanX)
        val spanY = if (pointBounds) 0.1 else max(1e-12, actualSpanY)
        return Viewport2D(
            Point2(bounds.min.x / 2 + bounds.max.x / 2, bounds.min.y / 2 + bounds.max.y / 2),
            min(usableWidth / spanX, usableHeight / spanY).coerceIn(MIN_SCALE, MAX_SCALE),
        )
    }

    /** A tap or a thin accidental drag leaves the camera unchanged. Drag direction is immaterial. */
    fun zoomWindow(startX: Double, startY: Double, endX: Double, endY: Double,
        width: Double, height: Double): Viewport2D {
        if (!validSurface(width, height) || !listOf(startX, startY, endX, endY).all { it.isFinite() } ||
            abs(endX - startX) < 4 || abs(endY - startY) < 4) return this
        val first = screenToWorld(startX, startY, width, height)
        val last = screenToWorld(endX, endY, width, height)
        return fit(Bounds3(Point3(min(first.x, last.x), min(first.y, last.y)),
            Point3(max(first.x, last.x), max(first.y, last.y))), width, height, 0.0)
    }

    fun visibleBounds(width: Double, height: Double): Bounds3 {
        val topLeft = screenToWorld(0.0, 0.0, width, height)
        val bottomRight = screenToWorld(width, height, width, height)
        return Bounds3(Point3(min(topLeft.x, bottomRight.x), min(topLeft.y, bottomRight.y)),
            Point3(max(topLeft.x, bottomRight.x), max(topLeft.y, bottomRight.y)))
    }

    companion object {
        const val MIN_SCALE = 1e-6
        const val MAX_SCALE = 1e7
        private fun validSurface(width: Double, height: Double) =
            width.isFinite() && height.isFinite() && width > 0 && height > 0
        private fun finite(value: Double): Double = when {
            value.isNaN() -> 0.0
            value == Double.POSITIVE_INFINITY -> Double.MAX_VALUE
            value == Double.NEGATIVE_INFINITY -> -Double.MAX_VALUE
            else -> value
        }
    }
}

/** Density-limited drawing grid/ruler step; settings retain the original snapping spacing. */
fun adaptiveGridStep(baseSpacing: Double, pixelsPerMetre: Double, minPixels: Double = 20.0): Double {
    if (!baseSpacing.isFinite() || baseSpacing <= 0 || !pixelsPerMetre.isFinite() || pixelsPerMetre <= 0 ||
        !minPixels.isFinite() || minPixels <= 0) return 1.0
    val baseExponent = log10(baseSpacing)
    val exponent = ceil(log10(minPixels) - log10(pixelsPerMetre) - baseExponent)
    if (exponent <= 0) return baseSpacing
    // Sum logarithms before exponentiating to avoid overflow in the intermediate ratio/power.
    val value = 10.0.pow(baseExponent + exponent)
    return if (value.isFinite()) value else Double.MAX_VALUE
}
