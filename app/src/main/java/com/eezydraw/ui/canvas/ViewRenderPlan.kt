package com.eezydraw.ui.canvas

import com.eezydraw.core.editing.EditDelta
import com.eezydraw.core.geometry.Viewport2D
import com.eezydraw.core.model.ViewMode

/** Pass choices are shared with the view exporter and can be checked without an EGL device. */
internal data class ViewRenderPlan(
    val surfaces: Boolean,
    val fullEdges: Boolean,
    val depthOnly: Boolean = false,
    val transparent: Boolean = false,
    val lighting: Int = 0,
)

internal fun viewRenderPlan(mode: ViewMode): ViewRenderPlan = when (mode) {
    ViewMode.WIREFRAME -> ViewRenderPlan(surfaces = false, fullEdges = true)
    ViewMode.HIDDEN_LINE, ViewMode.SECTION -> ViewRenderPlan(surfaces = true, fullEdges = true, depthOnly = true)
    ViewMode.SHADED -> ViewRenderPlan(surfaces = true, fullEdges = false, lighting = 1)
    ViewMode.SHADED_WITH_EDGES -> ViewRenderPlan(surfaces = true, fullEdges = true, lighting = 1)
    ViewMode.REALISTIC -> ViewRenderPlan(surfaces = true, fullEdges = false, lighting = 2)
    ViewMode.TRANSPARENT, ViewMode.XRAY -> ViewRenderPlan(surfaces = true, fullEdges = true, transparent = true, lighting = 1)
}

/** Inverse transpose of a preview's linear transform, in GLES column-major order. */
internal fun previewNormalMatrix(delta: EditDelta?): FloatArray {
    if (delta == null) return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    val m = delta.linearMatrixDouble()
    val a = m[0]; val b = m[4]; val c = m[8]
    val d = m[1]; val e = m[5]; val f = m[9]
    val g = m[2]; val h = m[6]; val i = m[10]
    val determinant = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
    require(determinant.isFinite() && determinant != 0.0) { "A surface preview needs an invertible transform." }
    return floatArrayOf(
        ((e * i - f * h) / determinant).toFloat(),
        ((c * h - b * i) / determinant).toFloat(),
        ((b * f - c * e) / determinant).toFloat(),
        ((f * g - d * i) / determinant).toFloat(),
        ((a * i - c * g) / determinant).toFloat(),
        ((c * d - a * f) / determinant).toFloat(),
        ((d * h - e * g) / determinant).toFloat(),
        ((b * g - a * h) / determinant).toFloat(),
        ((a * e - b * d) / determinant).toFloat(),
    )
}

/** Z-up top view: larger project Z has a nearer depth, while XY uses the same metre scale. */
internal fun filledPlanMatrix(
    viewport: Viewport2D, width: Int, height: Int, minRelativeZ: Double, maxRelativeZ: Double,
): FloatArray {
    val span = maxOf(1.0, maxRelativeZ - minRelativeZ)
    val middle = minRelativeZ / 2 + maxRelativeZ / 2
    return FloatArray(16).also {
        val x=2*viewport.pixelsPerMetre/width;val y=2*viewport.pixelsPerMetre/height
        val c=kotlin.math.cos(viewport.rotation);val s=kotlin.math.sin(viewport.rotation)
        it[0]=(x*c).toFloat();it[4]=(-x*s).toFloat();it[1]=(y*s).toFloat();it[5]=(y*c).toFloat()
        it[10] = (-2 / (span * 1.02)).toFloat()
        it[14] = (2 * middle / (span * 1.02)).toFloat()
        it[15] = 1f
    }
}

/** Filled solids retain unobtrusive outlines; technical hidden-line/wire passes stay full strength. */
internal fun edgeEmphasis(mode:ViewMode):Float=if(mode==ViewMode.SHADED_WITH_EDGES).55f else 1f
