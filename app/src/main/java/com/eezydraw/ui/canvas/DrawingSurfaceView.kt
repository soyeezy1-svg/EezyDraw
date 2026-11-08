package com.eezydraw.ui.canvas

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import com.eezydraw.core.editing.EditDelta
import com.eezydraw.core.geometry.DrawingScene
import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.SurfaceScene
import com.eezydraw.core.geometry.Viewport2D
import com.eezydraw.core.model.ViewMode
import java.util.concurrent.atomic.AtomicBoolean

/** Immutable camera/preview state; the static document is supplied independently through [setScene]. */
data class CanvasFrame(
    val viewport: Viewport2D,
    val orbit: OrbitCamera,
    val is3D: Boolean = false,
    val levelId: String? = null,
    val gridSpacing: Double = 0.1,
    val showGrid: Boolean = true,
    val dark: Boolean = false,
    val floorElevation: Double = 0.0,
    /** Selected geometry stays immutable during a drag; only [selectionDelta] changes. */
    val selectionScene: DrawingScene? = null,
    val selectionDelta: EditDelta? = null,
    val surfaceScene: SurfaceScene? = null,
    val selectionSurfaceScene: SurfaceScene? = null,
    val viewMode: ViewMode = ViewMode.WIREFRAME,
    /** Open curves and annotations remain visible when closed-surface edges are hidden. */
    val annotationScene: DrawingScene? = null,
    val selectionAnnotationScene: DrawingScene? = null,
    /** Dimension labels/extension lines are overlaid for readability in filled views. */
    val measurementScene: DrawingScene? = null,
    val selectionMeasurementScene: DrawingScene? = null,
    /** Presentation-only camera classification of the front sides of rooms. */
    val autoXrayFrontWalls: Boolean = false,
    val sectionBox:com.eezydraw.core.model.Bounds3?=null,
) {
    init {
        require(gridSpacing.isFinite() && gridSpacing > 0)
        require(floorElevation.isFinite())
    }
}

/**
 * Render-only GLES surface. Compose owns input, accessibility, controls and lifecycle observation.
 * Call [onResume]/[onPause] with the host lifecycle, and [release] when its owner is disposed.
 */
class DrawingSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {
    private val released = AtomicBoolean(false)

    /** Delivered on the UI thread if GLES initialization or a drawing upload fails. */
    @Volatile
    var onRenderError: ((String) -> Unit)? = null

    private val drawingRenderer = DrawingGlRenderer { message ->
        post { if (!released.get()) onRenderError?.invoke(message) }
    }

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = true
        setRenderer(drawingRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun update(frame: CanvasFrame) {
        if (released.get()) return
        drawingRenderer.update(frame)
        requestRender()
    }

    /** Publish the wireframe, surfaces, annotations and camera as one render snapshot. */
    fun render(scene: DrawingScene, frame: CanvasFrame) {
        if (released.get()) return
        if (drawingRenderer.submit(scene, frame)) requestRender()
    }

    fun setScene(scene: DrawingScene) {
        if (released.get()) return
        drawingRenderer.setScene(scene)
        requestRender()
    }

    /** Idempotent. EGL also reclaims resources if an already-paused surface has lost its context. */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        drawingRenderer.dispose()
        queueEvent { drawingRenderer.releaseGlResources() }
        requestRender()
    }

}
