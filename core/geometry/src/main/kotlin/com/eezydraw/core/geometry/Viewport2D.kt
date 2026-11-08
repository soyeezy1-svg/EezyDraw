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
import kotlin.math.cos
import kotlin.math.sin

/** Double-precision, Y-up plan camera. Pixels are measured from the top-left of the surface. */
data class Viewport2D(
    val center: Point2 = Point2(0.0, 0.0),
    val pixelsPerMetre: Double = 60.0,
    val rotation:Double=0.0,
) {
    init { require(pixelsPerMetre.isFinite() && pixelsPerMetre in MIN_SCALE..MAX_SCALE && rotation.isFinite()) }

    fun screenToWorld(x: Double, y: Double, width: Double, height: Double): Point2 {
        if (!validSurface(width, height) || !x.isFinite() || !y.isFinite()) return center
        val dx=(x-width/2)/pixelsPerMetre;val dy=-(y-height/2)/pixelsPerMetre
        return Point2(finite(center.x+cos(rotation)*dx+sin(rotation)*dy),finite(center.y-sin(rotation)*dx+cos(rotation)*dy))
    }

    fun worldToScreen(point: Point2, width: Double, height: Double): Point2 = Point2(
        finite((cos(rotation)*(point.x-center.x)-sin(rotation)*(point.y-center.y))*pixelsPerMetre+finite(width)/2),
        finite(-(sin(rotation)*(point.x-center.x)+cos(rotation)*(point.y-center.y))*pixelsPerMetre+finite(height)/2),
    )

    /** Positive touch deltas move the drawing with the finger. */
    fun pan(dxPixels: Double, dyPixels: Double): Viewport2D {
        if (!dxPixels.isFinite() || !dyPixels.isFinite()) return this
        val dx=-dxPixels/pixelsPerMetre;val dy=dyPixels/pixelsPerMetre
        return copy(center=Point2(finite(center.x+cos(rotation)*dx+sin(rotation)*dy),finite(center.y-sin(rotation)*dx+cos(rotation)*dy)))
    }

    fun zoom(factor: Double, anchorX: Double, anchorY: Double, width: Double, height: Double): Viewport2D {
        if (!factor.isFinite() || factor <= 0 || !validSurface(width, height) ||
            !anchorX.isFinite() || !anchorY.isFinite()) return this
        val scale = (pixelsPerMetre * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val anchor = screenToWorld(anchorX, anchorY, width, height)
        val after=copy(pixelsPerMetre=scale);val shifted=after.screenToWorld(anchorX,anchorY,width,height)
        return after.copy(center=Point2(finite(center.x+anchor.x-shifted.x),finite(center.y+anchor.y-shifted.y)))
    }

    fun rotate(angle:Double,anchorX:Double,anchorY:Double,width:Double,height:Double):Viewport2D {
        if(!angle.isFinite() || !validSurface(width,height))return this
        val before=screenToWorld(anchorX,anchorY,width,height)
        val next=copy(rotation=(rotation+angle)%(2*Math.PI));val after=next.screenToWorld(anchorX,anchorY,width,height)
        return next.copy(center=Point2(center.x+before.x-after.x,center.y+before.y-after.y))
    }

    fun fit(bounds: Bounds3, width: Double, height: Double, paddingPixels: Double = 48.0): Viewport2D {
        if (!validSurface(width, height) || !paddingPixels.isFinite()) return this
        val usableWidth = max(1.0, width - 2 * max(0.0, paddingPixels))
        val usableHeight = max(1.0, height - 2 * max(0.0, paddingPixels))
        val actualSpanX = abs(cos(rotation))*finite(bounds.max.x-bounds.min.x)+abs(sin(rotation))*finite(bounds.max.y-bounds.min.y)
        val actualSpanY = abs(sin(rotation))*finite(bounds.max.x-bounds.min.x)+abs(cos(rotation))*finite(bounds.max.y-bounds.min.y)
        // Only a point needs an arbitrary viewing area. A short edge or tiny window must
        // retain its actual size, and a zero-width axis must not constrain the other axis.
        val pointBounds = actualSpanX == 0.0 && actualSpanY == 0.0
        val spanX = if (pointBounds) 0.1 else max(1e-12, actualSpanX)
        val spanY = if (pointBounds) 0.1 else max(1e-12, actualSpanY)
        return Viewport2D(
            Point2(bounds.min.x / 2 + bounds.max.x / 2, bounds.min.y / 2 + bounds.max.y / 2),
            min(usableWidth / spanX, usableHeight / spanY).coerceIn(MIN_SCALE, MAX_SCALE),
            rotation,
        )
    }

    /** A tap or a thin accidental drag leaves the camera unchanged. Drag direction is immaterial. */
    fun zoomWindow(startX: Double, startY: Double, endX: Double, endY: Double,
        width: Double, height: Double): Viewport2D {
        if (!validSurface(width, height) || !listOf(startX, startY, endX, endY).all { it.isFinite() } ||
            abs(endX - startX) < 4 || abs(endY - startY) < 4) return this
        return copy(center=screenToWorld((startX+endX)/2,(startY+endY)/2,width,height),
            pixelsPerMetre=(pixelsPerMetre*min(width/abs(endX-startX),height/abs(endY-startY))).coerceIn(MIN_SCALE,MAX_SCALE))
    }

    fun visibleBounds(width: Double, height: Double): Bounds3 {
        val points=listOf(screenToWorld(0.0,0.0,width,height),screenToWorld(width,0.0,width,height),screenToWorld(0.0,height,width,height),screenToWorld(width,height,width,height))
        return Bounds3(Point3(points.minOf {it.x},points.minOf {it.y}),Point3(points.maxOf {it.x},points.maxOf {it.y}))
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
