package com.eezydraw.ui.canvas

import com.eezydraw.core.geometry.*
import com.eezydraw.core.model.*
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.eezydraw.core.editing.EditDelta
import com.eezydraw.core.geometry.DrawingScene
import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.SceneChunk
import com.eezydraw.core.geometry.SurfaceChunk
import com.eezydraw.core.geometry.SurfaceScene
import com.eezydraw.core.geometry.Viewport2D
import com.eezydraw.core.model.ObjectDisplay
import com.eezydraw.core.model.Bounds3
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.Projection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * GLES2 line/surface renderer. Immutable, spatially bounded batches upload once, while camera
 * gestures change only uniforms, culling and a bounded construction-grid buffer. All origin
 * subtraction happens in doubles before values reach the GPU.
 */
internal class DrawingGlRenderer(private val publishLoupe: (CapturedLoupe?) -> Unit = {}, private val publishDiagnostics:(RenderDiagnostics)->Unit={}, private val reportError: (String) -> Unit) : GLSurfaceView.Renderer {
    private data class RenderState(val scene: DrawingScene?, val frame: CanvasFrame)
    private val state = AtomicReference(RenderState(null, CanvasFrame(Viewport2D(), OrbitCamera())))
    private val disposed = AtomicBoolean(false)

    // The fields below belong exclusively to the GL thread.
    private val adaptive=AdaptiveGraphics()
    private val fxaa=FxaaPass()
    private val multisample=MultisampleTarget()
    private var samples=0
    private var supportsResolve=false
    private var surfaceInfoPending=true
    private var depthBits=0
    private var fragmentHighPrecision=true
    private var effectiveQuality=RenderQuality.QUALITY
    private var lodCount=0
    private var lodProjection=LodProjection(OrbitCamera(),1)
    private var uploadedShadowScene:SurfaceScene?=null
    private var shadowChunks=emptyList<GpuSurfaceChunk>()
    private var program = 0
    private var positionAttribute = -1
    private var colorAttribute = -1
    private var matrixUniform = -1
    private var modelUniform = -1
    private var offsetUniform = -1
    private var darkUniform = -1
    private var selectionUniform = -1
    private var lineOpacityUniform = -1
    private var surfacePassActive = false
    private var roomWallSources: List<Any?> = emptyList()
    private var roomWalls = RoomWallIndex(emptyList())
    private var width = 1
    private var height = 1
    private var drawingLineWidth = 1f
    private var uploadedScene: DrawingScene? = null
    private var chunks = emptyList<GpuChunk>()
    private var uploadedSelectionScene: DrawingScene? = null
    private var selectionChunks = emptyList<GpuChunk>()
    private val annotationCache = ExtraLineCache()
    private val measurementCache = ExtraLineCache()
    private val selectionMeasurementCache = ExtraLineCache()
    private var surfaceShader: SurfaceShader? = null
    private var uploadedSurfaceScene: SurfaceScene? = null
    private var surfaceChunks = emptyList<GpuSurfaceChunk>()
    private var uploadedSelectionSurfaceScene: SurfaceScene? = null
    private var selectionSurfaceChunks = emptyList<GpuSurfaceChunk>()
    private var gridBuffer = 0
    private var gridVertexCount = 0
    private var gridFrame: CanvasFrame? = null
    private var gridWidth = 0
    private var gridHeight = 0
    private var staging = nativeFloats(MAX_GRID_FLOATS)
    private val gridVertices = FloatArray(MAX_GRID_FLOATS)
    private val frustumPlanes = DoubleArray(24)
    private val visibleBounds = DoubleArray(6)

    fun update(value: CanvasFrame) { state.updateAndGet { it.copy(frame = value) } }
    fun setScene(value: DrawingScene) { state.updateAndGet { it.copy(scene = value) } }
    fun submit(scene: DrawingScene, frame: CanvasFrame): Boolean {
        while (true) {
            val previous = state.get()
            if (previous.scene === scene && previous.frame == frame) return false
            if (state.compareAndSet(previous, RenderState(scene, frame))) return true
        }
    }
    fun dispose() {
        disposed.set(true)
        state.set(RenderState(null, CanvasFrame(Viewport2D(), OrbitCamera())))
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Handles from the old EGL context are invalid, even when the CPU scene is unchanged.
        fxaa.contextLost();adaptive.reset();surfaceInfoPending=true;shadowChunks=emptyList();uploadedShadowScene=null
        program = 0
        gridBuffer = 0
        chunks = emptyList()
        selectionChunks = emptyList()
        annotationCache.chunks = emptyList()
        measurementCache.chunks = emptyList()
        selectionMeasurementCache.chunks = emptyList()
        surfaceChunks = emptyList()
        selectionSurfaceChunks = emptyList()
        surfaceShader = null
        uploadedScene = null
        uploadedSelectionScene = null
        annotationCache.scene = null
        measurementCache.scene = null
        selectionMeasurementCache.scene = null
        uploadedSurfaceScene = null
        uploadedSelectionSurfaceScene = null
        gridFrame = null
        gridVertexCount = 0
        if (disposed.get()) return
        try {
            program = createProgram()
            surfaceShader = createSurfaceShader()
            positionAttribute = GLES20.glGetAttribLocation(program, "aPosition")
            colorAttribute = GLES20.glGetAttribLocation(program, "aColor")
            matrixUniform = GLES20.glGetUniformLocation(program, "uMatrix")
            modelUniform = GLES20.glGetUniformLocation(program, "uModel")
            offsetUniform = GLES20.glGetUniformLocation(program, "uOffset")
            darkUniform = GLES20.glGetUniformLocation(program, "uDark")
            selectionUniform = GLES20.glGetUniformLocation(program, "uSelection")
            lineOpacityUniform = GLES20.glGetUniformLocation(program, "uOpacity")
            check(positionAttribute >= 0 && colorAttribute >= 0 && matrixUniform >= 0 &&
                modelUniform >= 0 && offsetUniform >= 0 && darkUniform >= 0 && selectionUniform >= 0 && lineOpacityUniform >= 0) {
                "Required drawing shader inputs are unavailable"
            }
            val ids = IntArray(1)
            GLES20.glGenBuffers(1, ids, 0)
            gridBuffer = ids[0]
            checkGlUpload("allocating the construction grid")
            check(gridBuffer != 0) { "Cannot allocate the construction grid buffer" }
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, gridBuffer)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, MAX_GRID_FLOATS * FLOAT_BYTES, null, GLES20.GL_DYNAMIC_DRAW)
            checkGlUpload("uploading the construction grid")
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            val range = FloatArray(2)
            GLES20.glGetFloatv(GLES20.GL_ALIASED_LINE_WIDTH_RANGE, range, 0)
            drawingLineWidth = if (range[0] > 0 && range[1] >= range[0]) 1.25f.coerceIn(range[0], range[1]) else 1f
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glDepthFunc(GLES20.GL_LEQUAL)
            val bits=IntArray(1);GLES20.glGetIntegerv(GLES20.GL_DEPTH_BITS,bits,0);depthBits=bits[0]
            GLES20.glGetIntegerv(GLES20.GL_SAMPLES,bits,0);samples=bits[0]
            supportsResolve=Regex("OpenGL ES [3-9]").containsMatchIn(GLES20.glGetString(GLES20.GL_VERSION).orEmpty())
            multisample.contextCreated(supportsResolve)
            checkGlUpload("initializing drawing state")
        } catch (error: IllegalStateException) {
            releaseGlResources()
            reportError("The drawing renderer could not start: ${error.message.orEmpty()}")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = max(1, width)
        this.height = max(1, height)
        GLES20.glViewport(0, 0, this.width, this.height)
        gridFrame = null
    }

    override fun onDrawFrame(gl: GL10?) {
        val snapshot = state.get()
        val current = snapshot.frame.let {f->if(f.is3D && f.orbit.clip==null && snapshot.scene!=null)
            f.copy(orbit=f.orbit.withSceneClipping(snapshot.scene.boundsForLevel(f.levelId))) else f}
        val sample=adaptive.frame(System.nanoTime(),current.quality,current.graphics.automaticQuality && current.is3D,current.motionActive)
        effectiveQuality=adaptive.quality;lodCount=0;lodProjection=LodProjection(current.orbit,height)
        multisample.begin(width,height,requestedSamples(effectiveQuality,current.graphics.antiAliasing && current.is3D))
        samples=multisample.samples
        if (current.dark) GLES20.glClearColor(0.055f, 0.071f, 0.10f, 1f)
        else GLES20.glClearColor(0.97f, 0.98f, 0.99f, 1f)
        GLES20.glColorMask(true, true, true, true)
        GLES20.glDepthMask(true)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (disposed.get() || program == 0 || surfaceShader == null) {GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);return}

        val pendingScene = snapshot.scene
        if (pendingScene !== uploadedScene && !uploadScene(pendingScene)) return
        if (current.selectionScene !== uploadedSelectionScene &&
            !uploadScene(current.selectionScene, selection = true)) return
        val passes = viewRenderPlan(current.viewMode)
        surfacePassActive = passes.surfaces || current.autoXrayFrontWalls && current.is3D ||
            current.surfaceScene?.chunks?.any { it.display == ObjectDisplay.XRAY } == true ||
            current.selectionSurfaceScene?.chunks?.any { it.display == ObjectDisplay.XRAY } == true
        if (surfacePassActive) {
            if (current.surfaceScene !== uploadedSurfaceScene && !uploadSurface(current.surfaceScene)) return
            if (current.selectionSurfaceScene !== uploadedSelectionSurfaceScene &&
                !uploadSurface(current.selectionSurfaceScene, selection = true)) return
        } else {
            // Retain unchanged buffers for a quick mode switch, but never pin an obsolete
            // drawing's CPU/GPU mesh merely because the user returned to wireframe.
            if (uploadedSurfaceScene !== current.surfaceScene) {
                deleteSurfaceBuffers(); uploadedSurfaceScene = null
            }
            if (uploadedSelectionSurfaceScene !== current.selectionSurfaceScene) {
                deleteSurfaceBuffers(selection = true); uploadedSelectionSurfaceScene = null
            }
        }
        val sideSources = listOf(pendingScene, current.selectionScene, current.surfaceScene, current.selectionSurfaceScene)
        if (sideSources.indices.any { sideSources[it] !== roomWallSources.getOrNull(it) }) {
            roomWallSources = sideSources
            val parts = ArrayList<RoomWallPart>()
            for (scene in listOfNotNull(pendingScene, current.selectionScene)) for (chunk in scene.chunks)
                if (chunk.roomSide != null) parts.add(RoomWallPart(chunk.roomSide, chunk.bounds, chunk.roomCenter))
            for (scene in listOfNotNull(current.surfaceScene, current.selectionSurfaceScene)) for (chunk in scene.chunks)
                if (chunk.roomSide != null) parts.add(RoomWallPart(chunk.roomSide, chunk.bounds, chunk.roomCenter))
            roomWalls = RoomWallIndex(parts)
        }
        val annotations = current.annotationScene
        if ((annotations == null || annotations === pendingScene || passes.fullEdges) &&
            annotationCache.scene !== annotations) deleteExtraBuffers(annotationCache)
        if (!passes.fullEdges && annotations != null && annotations !== pendingScene &&
            annotations !== annotationCache.scene && !uploadExtraLines(annotations, annotationCache)) return
        if (current.measurementScene !== measurementCache.scene && !uploadExtraLines(current.measurementScene, measurementCache)) return
        if (current.selectionMeasurementScene !== selectionMeasurementCache.scene &&
            !uploadExtraLines(current.selectionMeasurementScene, selectionMeasurementCache)) return

        val shadows=current.contactShadowScene.takeIf {current.selectionDelta==null && current.is3D && current.graphics.contactShadows && effectiveQuality!=RenderQuality.PERFORMANCE && passes.lighting>0 && !passes.transparent}
        if(shadows!==uploadedShadowScene && !uploadShadows(shadows))return
        val planDepth = if (!current.is3D && surfacePassActive) planDepthRange(pendingScene, current, 0.0) else null
        val planOriginZ = planDepth?.let { it.first / 2 + it.second / 2 } ?: current.floorElevation
        val origin = if (current.is3D) current.orbit.target else
            Point3(current.viewport.center.x, current.viewport.center.y, planOriginZ)
        val aspect = width.toDouble() / height
        val matrix = if (current.is3D) current.orbit.viewProjectionMatrix(aspect) else {
            if (surfacePassActive) {
                val depth = requireNotNull(planDepth)
                filledPlanMatrix(current.viewport, width, height, depth.first - origin.z, depth.second - origin.z)
            } else planMatrix(current)
        }
        if (current.is3D) extractFrustumPlanes(matrix)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        val visiblePlan=current.viewport.visibleBounds(width.toDouble(),height.toDouble())
        val halfWidth=(visiblePlan.max.x-visiblePlan.min.x)/2
        val halfHeight=(visiblePlan.max.y-visiblePlan.min.y)/2

        useLineShader(matrix, current.dark)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        if (current.showGrid) {
            updateGrid(current)
            GLES20.glDepthMask(false)
            GLES20.glLineWidth(1f)
            GLES20.glUniform3f(offsetUniform, 0f, 0f, 0f)
            drawBuffer(gridBuffer, gridVertexCount)
            GLES20.glDepthMask(true)
        }
        disableLineAttributes()

        if (surfacePassActive) {
            // Opaque depth first, then all object/global transparent faces. The second pass
            // deliberately leaves depth untouched, exposing geometry behind a front wall.
            for (transparent in listOf(false, true)) {
                if (transparent) {
                    GLES20.glDisable(GLES20.GL_DEPTH_TEST)
                    GLES20.glDepthMask(false)
                    GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)
                } else {
                    GLES20.glEnable(GLES20.GL_DEPTH_TEST)
                    GLES20.glDepthMask(true)
                    GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL)
                    GLES20.glPolygonOffset(1f, 1f)
                }
                drawSurfaces(surfaceChunks, current, matrix, origin, halfWidth, halfHeight, transparent)
                drawSurfaces(selectionSurfaceChunks, current, matrix, origin, halfWidth, halfHeight, transparent,
                    current.selectionDelta, selected = true)
                if(!transparent && shadowChunks.isNotEmpty()) {
                    GLES20.glDepthMask(false);GLES20.glPolygonOffset(-1f,-1f)
                    drawSurfaces(shadowChunks,current,matrix,origin,halfWidth,halfHeight,false)
                    GLES20.glDepthMask(true);GLES20.glPolygonOffset(1f,1f)
                }
            }
            GLES20.glColorMask(true, true, true, true)
            GLES20.glDepthMask(true)
            GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)
        }

        useLineShader(matrix, current.dark)
        GLES20.glLineWidth(drawingLineWidth)
        if (passes.fullEdges || annotations == null || annotations === pendingScene) {
            drawChunks(chunks, current, origin, halfWidth, halfHeight)
        } else {
            // Normal annotation batches stay separate from filled edges. Overrides retain
            // their own edge policy even when the project is shaded without edges.
            drawChunks(annotationCache.chunks, current, origin, halfWidth, halfHeight, lineFilter = LineFilter.NORMAL)
            drawChunks(chunks, current, origin, halfWidth, halfHeight, lineFilter = LineFilter.OVERRIDES)
        }

        if (selectionChunks.isNotEmpty()) {
            val delta = current.selectionDelta
            val model = delta?.linearMatrix() ?: IDENTITY_MATRIX
            if (model.all { it.isFinite() }) {
                GLES20.glUniformMatrix4fv(modelUniform, 1, false, model, 0)
                GLES20.glUniform1f(selectionUniform, 1f)
                // The selected outline remains discoverable in filled modes and during a drag.
                drawChunks(selectionChunks, current, origin, halfWidth, halfHeight, delta, forceOverlay = true)
            }
        }
        if (measurementCache.chunks.isNotEmpty() || selectionMeasurementCache.chunks.isNotEmpty()) {
            useLineShader(matrix, current.dark)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthMask(false)
            drawChunks(measurementCache.chunks, current, origin, halfWidth, halfHeight, forceOverlay = true)
            val delta = current.selectionDelta
            val model = delta?.linearMatrix() ?: IDENTITY_MATRIX
            if (model.all(Float::isFinite)) {
                GLES20.glUniformMatrix4fv(modelUniform, 1, false, model, 0)
                GLES20.glUniform1f(selectionUniform, 1f)
                drawChunks(selectionMeasurementCache.chunks, current, origin, halfWidth, halfHeight, delta, forceOverlay = true)
            }
            GLES20.glDepthMask(true)
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        disableLineAttributes()
        val resolvedTexture=multisample.finish()
        val usedFxaa=current.graphics.antiAliasing && current.is3D && fxaa.apply(width,height,resolvedTexture)
        if(resolvedTexture!=0 && !usedFxaa)multisample.present()
        if(surfaceInfoPending || sample!=null && (current.graphics.showFrameRate || current.graphics.automaticQuality)) {
            publishDiagnostics(RenderDiagnostics(sample?.first,effectiveQuality,samples,if(samples>0)24 else depthBits,usedFxaa,lodCount,supportsResolve))
            surfaceInfoPending=false
        }
        publishLoupe(current.loupe?.let { captureLoupe(it, width, height)?.copy(renderFrame = current, scene = pendingScene) })
        if(pendingScene?.segments?.isNotEmpty()==true)current.performanceCounter?.frame(System.nanoTime())
    }

    private fun useLineShader(matrix: FloatArray, dark: Boolean) {
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(matrixUniform, 1, false, matrix, 0)
        GLES20.glUniformMatrix4fv(modelUniform, 1, false, IDENTITY_MATRIX, 0)
        GLES20.glUniform1f(darkUniform, if (dark) 1f else 0f)
        GLES20.glUniform1f(selectionUniform, 0f)
        GLES20.glUniform1f(lineOpacityUniform, 1f)
        GLES20.glEnableVertexAttribArray(positionAttribute)
        GLES20.glEnableVertexAttribArray(colorAttribute)
    }

    private fun disableLineAttributes() {
        GLES20.glDisableVertexAttribArray(positionAttribute)
        GLES20.glDisableVertexAttribArray(colorAttribute)
    }

    private fun planDepthRange(value: DrawingScene?, current: CanvasFrame, originZ: Double): Pair<Double, Double> {
        var low = value?.bounds?.min?.z ?: current.floorElevation
        var high = value?.bounds?.max?.z ?: current.floorElevation
        current.selectionScene?.bounds?.let { bounds ->
            val delta = current.selectionDelta
            for (corner in 0 until 8) {
                val point = Point3(if (corner and 1 == 0) bounds.min.x else bounds.max.x,
                    if (corner and 2 == 0) bounds.min.y else bounds.max.y,
                    if (corner and 4 == 0) bounds.min.z else bounds.max.z)
                val z = runCatching { delta?.apply(point)?.z ?: point.z }.getOrNull() ?: continue
                low = min(low, z); high = max(high, z)
            }
        }
        return (low - originZ) to (high - originZ)
    }

    /** The small selected batch changes uniforms during handle drags, never its vertex buffers. */
    private enum class LineFilter { ALL, NORMAL, OVERRIDES }

    private fun drawChunks(
        batches: List<GpuChunk>,
        current: CanvasFrame,
        origin: Point3,
        halfWidth: Double,
        halfHeight: Double,
        delta: EditDelta? = null,
        lineFilter: LineFilter = LineFilter.ALL,
        forceOverlay: Boolean = false,
    ) {
        val linear = delta?.linearMatrixDouble()
        for (gpu in batches) {
            val chunk = gpu.source
            val front = roomWalls.isFront(chunk.roomSide, current.orbit, current.autoXrayFrontWalls, current.is3D)
            val overridden = chunk.display != ObjectDisplay.NORMAL || front
            if (lineFilter == LineFilter.NORMAL && overridden || lineFilter == LineFilter.OVERRIDES && !overridden) continue
            val plan = objectRenderPlan(current.viewMode, chunk.display, front)
            val depth = !forceOverlay && chunk.display != ObjectDisplay.WIREFRAME && chunk.display != ObjectDisplay.HIDDEN &&
                !plan.transparent && (current.is3D || surfacePassActive)
            if (depth) GLES20.glEnable(GLES20.GL_DEPTH_TEST) else GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glUniform1f(lineOpacityUniform, objectEdgeOpacity(chunk.display, front)*if(forceOverlay)1f else edgeEmphasis(objectViewMode(current.viewMode,chunk.display,front)))
            if (current.levelId != null && chunk.levelId != null && chunk.levelId != current.levelId) continue
            boundsRelativeToCamera(chunk.bounds, origin, delta, linear)
            if (current.is3D) {
                if (!inFrustum()) continue
            } else {
                if (visibleBounds[3] < -halfWidth || visibleBounds[0] > halfWidth ||
                    visibleBounds[4] < -halfHeight || visibleBounds[1] > halfHeight) continue
            }
            val transformedOrigin = if (delta == null) chunk.origin else try {
                delta.apply(chunk.origin)
            } catch (_: IllegalArgumentException) {
                // Extremely large finite user input can overflow a transformed project coordinate.
                continue
            }
            val x = (transformedOrigin.x - origin.x).toFloat()
            val y = (transformedOrigin.y - origin.y).toFloat()
            val z = if (current.is3D || surfacePassActive)
                (transformedOrigin.z - origin.z).toFloat() else 0f
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
            GLES20.glUniform3f(offsetUniform, x, y, z)
            val lod=current.is3D && !forceOverlay && delta==null && chunk.lodAllowed && gpu.lodCount>0 && lodProjection.diameter(chunk.bounds)<lodPixelThreshold(effectiveQuality)
            if(lod)lodCount++
            drawBuffer(if(lod)gpu.lodBuffer else gpu.buffer,if(lod)gpu.lodCount else chunk.vertices.size / FLOATS_PER_VERTEX)
        }
    }

    private fun drawSurfaces(
        batches: List<GpuSurfaceChunk>, current: CanvasFrame, matrix: FloatArray,
        origin: Point3, halfWidth: Double, halfHeight: Double, transparentPass: Boolean,
        delta: EditDelta? = null, selected: Boolean = false,
    ) {
        if (batches.isEmpty()) return
        val shader = surfaceShader ?: return
        val model = delta?.linearMatrix() ?: IDENTITY_MATRIX
        val normal = runCatching { previewNormalMatrix(delta) }.getOrNull() ?: return
        if (model.any { !it.isFinite() } || normal.any { !it.isFinite() }) return
        val linear = delta?.linearMatrixDouble()
        GLES20.glUseProgram(shader.program)
        GLES20.glUniformMatrix4fv(shader.matrix, 1, false, matrix, 0)
        GLES20.glUniformMatrix4fv(shader.model, 1, false, model, 0)
        GLES20.glUniformMatrix3fv(shader.normalMatrix, 1, false, normal, 0)
        GLES20.glUniform1f(shader.selection, if (selected) .22f else 0f)
        GLES20.glUniform1f(shader.dark, if (current.dark) 1f else 0f)
        val outward = if (current.is3D) Point3(cos(current.orbit.pitch) * cos(current.orbit.yaw),
            cos(current.orbit.pitch) * sin(current.orbit.yaw), sin(current.orbit.pitch)) else Point3(z = 1.0)
        GLES20.glUniform3f(shader.viewDirection, outward.x.toFloat(), outward.y.toFloat(), outward.z.toFloat())
        val key=if(current.is3D)cameraKeyLight(current.orbit)else normalizeRender(Point3(.2,-.3,.9))
        val coefficients=lightCoefficients(current.graphics.lighting)
        GLES20.glUniform3f(shader.keyLight,key.x.toFloat(),key.y.toFloat(),key.z.toFloat())
        GLES20.glUniform2f(shader.lightStrength,coefficients.first.toFloat(),coefficients.second.toFloat())
        GLES20.glEnableVertexAttribArray(shader.position)
        GLES20.glEnableVertexAttribArray(shader.normal)
        GLES20.glEnableVertexAttribArray(shader.color)
        val ordered = if (transparentPass) batches.filter {gpu->
            (current.levelId==null || gpu.source.levelId==null || gpu.source.levelId==current.levelId) &&
                objectRenderPlan(current.viewMode,gpu.source.display,roomWalls.isFront(gpu.source.roomSide,current.orbit,current.autoXrayFrontWalls,current.is3D)).transparent
        }.sortedBy { gpu ->
            val bounds = gpu.source.bounds
            val center = Point3(bounds.min.x / 2 + bounds.max.x / 2,
                bounds.min.y / 2 + bounds.max.y / 2, bounds.min.z / 2 + bounds.max.z / 2)
            val point = runCatching { delta?.apply(center) ?: center }.getOrNull() ?: center
            (point.x - origin.x) * outward.x + (point.y - origin.y) * outward.y + (point.z - origin.z) * outward.z
        } else batches
        for (gpu in ordered) {
            val chunk = gpu.source
            val front = roomWalls.isFront(chunk.roomSide, current.orbit, current.autoXrayFrontWalls, current.is3D)
            val passes = objectRenderPlan(current.viewMode, chunk.display, front)
            if (!passes.surfaces || passes.transparent != transparentPass) continue
            if (current.levelId != null && chunk.levelId != null && chunk.levelId != current.levelId) continue
            boundsRelativeToCamera(chunk.bounds, origin, delta, linear)
            if (current.is3D) {
                if (!inFrustum()) continue
            } else if (visibleBounds[3] < -halfWidth || visibleBounds[0] > halfWidth ||
                visibleBounds[4] < -halfHeight || visibleBounds[1] > halfHeight) continue
            val position = runCatching { delta?.apply(chunk.origin) ?: chunk.origin }.getOrNull() ?: continue
            val x = (position.x - origin.x).toFloat()
            val y = (position.y - origin.y).toFloat()
            val z = (position.z - origin.z).toFloat()
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
            GLES20.glColorMask(!passes.depthOnly, !passes.depthOnly, !passes.depthOnly, !passes.depthOnly)
            val shadow=chunk.material==RenderMaterial.CONTACT_SHADOW
            if(!transparentPass)GLES20.glPolygonOffset(if(shadow)-1f else chunk.depthBias,if(shadow)-1f else chunk.depthBias)
            GLES20.glUniform1f(shader.lighting,if(shadow)0f else passes.lighting.toFloat())
            GLES20.glUniform1f(shader.dark,if(current.dark && !shadow)1f else 0f)
            val detail=when(effectiveQuality){RenderQuality.PERFORMANCE->TextureDetail.LOW;RenderQuality.BALANCED->if(current.graphics.textureDetail==TextureDetail.HIGH)TextureDetail.MEDIUM else current.graphics.textureDetail;else->current.graphics.textureDetail}
            val grain=if(detail==TextureDetail.HIGH).035f else if(detail==TextureDetail.MEDIUM).02f else 0f
            GLES20.glUniform1f(shader.grain,if(shadow || !current.is3D || !fragmentHighPrecision)0f else grain)
            GLES20.glUniform2f(shader.textureDensity,
                (if(current.orbit.projection==Projection.PERSPECTIVE)height/(2*tan(OrbitCamera.FOV_Y/2))else height/current.orbit.orthographicHeight).toFloat(),
                if(current.orbit.projection==Projection.PERSPECTIVE)1f else 0f)
            GLES20.glUniform1f(shader.frequency,textureFrequency(detail).toFloat())
            GLES20.glUniform1f(shader.opacity, if (passes.transparent) .28f else 1f)
            GLES20.glUniform3f(shader.offset, x, y, z)
            GLES20.glUniform3f(shader.textureOffset,(position.x%64).toFloat(),(position.y%64).toFloat(),(position.z%64).toFloat())
            val lod=current.is3D && !selected && delta==null && gpu.lodCount>0 && lodProjection.diameter(chunk.bounds)<lodPixelThreshold(effectiveQuality)
            if(lod)lodCount++
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, if(lod)gpu.lodBuffer else gpu.buffer)
            GLES20.glVertexAttribPointer(shader.position, 3, GLES20.GL_FLOAT, false, SURFACE_VERTEX_BYTES, 0)
            GLES20.glVertexAttribPointer(shader.normal, 3, GLES20.GL_FLOAT, false, SURFACE_VERTEX_BYTES, 3 * FLOAT_BYTES)
            GLES20.glVertexAttribPointer(shader.color, 4, GLES20.GL_FLOAT, false, SURFACE_VERTEX_BYTES, 6 * FLOAT_BYTES)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, if(lod)gpu.lodCount else chunk.vertices.size / SURFACE_FLOATS_PER_VERTEX)
        }
        GLES20.glDisableVertexAttribArray(shader.position)
        GLES20.glDisableVertexAttribArray(shader.normal)
        GLES20.glDisableVertexAttribArray(shader.color)
    }

    /** Called on the GL thread, or reclaimed by EGL itself when a disposed surface is detached. */
    fun releaseGlResources() {
        fxaa.release();multisample.release();deleteShadows()
        if (positionAttribute >= 0) GLES20.glDisableVertexAttribArray(positionAttribute)
        if (colorAttribute >= 0) GLES20.glDisableVertexAttribArray(colorAttribute)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glUseProgram(0)
        deleteChunkBuffers()
        deleteChunkBuffers(selection = true)
        for (cache in listOf(annotationCache, measurementCache, selectionMeasurementCache)) deleteExtraBuffers(cache)
        deleteSurfaceBuffers()
        deleteSurfaceBuffers(selection = true)
        if (gridBuffer != 0) GLES20.glDeleteBuffers(1, intArrayOf(gridBuffer), 0)
        if (program != 0) GLES20.glDeleteProgram(program)
        surfaceShader?.let { GLES20.glDeleteProgram(it.program) }
        gridBuffer = 0
        program = 0
        positionAttribute = -1
        colorAttribute = -1
        matrixUniform = -1
        modelUniform = -1
        offsetUniform = -1
        darkUniform = -1
        selectionUniform = -1
        lineOpacityUniform = -1
        roomWallSources = emptyList()
        roomWalls = RoomWallIndex(emptyList())
        uploadedScene = null
        uploadedSelectionScene = null
        annotationCache.scene = null
        measurementCache.scene = null
        selectionMeasurementCache.scene = null
        annotationCache.chunks = emptyList()
        measurementCache.chunks = emptyList()
        selectionMeasurementCache.chunks = emptyList()
        uploadedSurfaceScene = null
        uploadedSelectionSurfaceScene = null
        surfaceShader = null
        gridFrame = null
        gridVertexCount = 0
    }

    private fun deleteExtraBuffers(cache: ExtraLineCache) {
        if (cache.chunks.isNotEmpty()) GLES20.glDeleteBuffers(cache.chunks.size, cache.chunks.map { it.buffer }.toIntArray(), 0)
        cache.chunks = emptyList()
        cache.scene = null
    }

    private fun uploadExtraLines(value: DrawingScene?, cache: ExtraLineCache): Boolean {
        deleteExtraBuffers(cache)
        if (value == null || value.chunks.isEmpty()) { cache.scene = value; return true }
        val ids = IntArray(value.chunks.size)
        try {
            GLES20.glGenBuffers(ids.size, ids, 0)
            checkGlUpload("allocating annotation buffers")
            check(ids.all { it != 0 }) { "The GPU could not allocate annotation buffers" }
            val uploaded = value.chunks.mapIndexed { index, chunk ->
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[index])
                GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, chunk.vertices.size * FLOAT_BYTES,
                    stage(chunk.vertices, chunk.vertices.size), GLES20.GL_STATIC_DRAW)
                checkGlUpload("uploading annotations")
                GpuChunk(chunk, ids[index])
            }
            cache.chunks = uploaded
            cache.scene = value
            return true
        } catch (error: IllegalStateException) {
            GLES20.glDeleteBuffers(ids.size, ids, 0)
            releaseGlResources()
            reportError("Annotations could not be uploaded to the GPU: ${error.message.orEmpty()}")
            return false
        } finally { GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0) }
    }

    private fun deleteShadows() {
        if(shadowChunks.isNotEmpty())GLES20.glDeleteBuffers(shadowChunks.size,shadowChunks.map {it.buffer}.toIntArray(),0)
        shadowChunks=emptyList();uploadedShadowScene=null
    }
    private fun uploadShadows(scene:SurfaceScene?):Boolean {
        deleteShadows()
        if(scene==null){return true}
        val ids=IntArray(scene.chunks.size)
        try {
            GLES20.glGenBuffers(ids.size,ids,0)
            shadowChunks=scene.chunks.mapIndexed {i,chunk->
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,ids[i])
                GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,chunk.vertices.size*4,stage(chunk.vertices,chunk.vertices.size),GLES20.GL_STATIC_DRAW)
                checkGlUpload("uploading contact shadows")
                GpuSurfaceChunk(chunk,ids[i])
            }
            uploadedShadowScene=scene;return true
        } catch(error:IllegalStateException) {
            GLES20.glDeleteBuffers(ids.size,ids,0);shadowChunks=emptyList()
            reportError("Contact shadows could not be uploaded: ${error.message}");return false
        }
    }

    private fun uploadSurface(value: SurfaceScene?, selection: Boolean = false): Boolean {
        deleteSurfaceBuffers(selection)
        if (selection) uploadedSelectionSurfaceScene = null else uploadedSurfaceScene = null
        if (value == null || value.chunks.isEmpty()) {
            if (selection) uploadedSelectionSurfaceScene = value else uploadedSurfaceScene = value
            return true
        }
        val ids = IntArray(value.chunks.size*2)
        try {
            GLES20.glGenBuffers(ids.size, ids, 0)
            checkGlUpload("allocating surface buffers")
            check(ids.all { it != 0 }) { "The GPU could not allocate surface buffers" }
            val uploaded = value.chunks.mapIndexed { index, chunk ->
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[index])
                GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, chunk.vertices.size * FLOAT_BYTES,
                    stage(chunk.vertices, chunk.vertices.size), GLES20.GL_STATIC_DRAW)
                checkGlUpload("uploading filled surfaces")
                val coarse=if(selection)FloatArray(0)else coarseSurface(chunk)
                if(coarse.isNotEmpty()) {
                    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,ids[value.chunks.size+index])
                    GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,coarse.size*4,stage(coarse,coarse.size),GLES20.GL_STATIC_DRAW)
                    checkGlUpload("uploading distant surfaces")
                }
                GpuSurfaceChunk(chunk, ids[index],ids[value.chunks.size+index],coarse.size/10)
            }
            if (selection) { selectionSurfaceChunks = uploaded; uploadedSelectionSurfaceScene = value }
            else { surfaceChunks = uploaded; uploadedSurfaceScene = value }
            return true
        } catch (error: IllegalStateException) {
            GLES20.glDeleteBuffers(ids.size, ids, 0)
            releaseGlResources()
            reportError("Surfaces could not be uploaded to the GPU: ${error.message.orEmpty()}")
            return false
        } finally { GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0) }
    }

    private fun deleteSurfaceBuffers(selection: Boolean = false) {
        val batches = if (selection) selectionSurfaceChunks else surfaceChunks
        if (batches.isNotEmpty()) GLES20.glDeleteBuffers(batches.size*2, batches.flatMap {listOf(it.buffer,it.lodBuffer)}.toIntArray(), 0)
        if (selection) selectionSurfaceChunks = emptyList() else surfaceChunks = emptyList()
    }

    /** Publish the new GPU scene only after every batch has uploaded successfully. */
    private fun uploadScene(value: DrawingScene?, selection: Boolean = false): Boolean {
        deleteChunkBuffers(selection)
        if (selection) uploadedSelectionScene = null else uploadedScene = null
        if (value == null || value.chunks.isEmpty()) {
            if (selection) uploadedSelectionScene = value else uploadedScene = value
            return true
        }
        val ids = IntArray(value.chunks.size*2)
        try {
            checkGlUpload("preparing the drawing upload")
            GLES20.glGenBuffers(ids.size, ids, 0)
            checkGlUpload("allocating drawing buffers")
            check(ids.all { it != 0 }) { "The GPU could not allocate all drawing buffers" }
            val uploaded = ArrayList<GpuChunk>(ids.size)
            for ((index, chunk) in value.chunks.withIndex()) {
                val id = ids[index]
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id)
                val floats = stage(chunk.vertices, chunk.vertices.size)
                GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, chunk.vertices.size * FLOAT_BYTES, floats, GLES20.GL_STATIC_DRAW)
                checkGlUpload("uploading drawing batch ${index + 1} of ${ids.size}")
                val coarse=if(selection || !chunk.lodAllowed)FloatArray(0)else coarseWire(chunk.vertices)
                if(coarse.isNotEmpty()) {
                    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,ids[value.chunks.size+index])
                    GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,coarse.size*4,stage(coarse,coarse.size),GLES20.GL_STATIC_DRAW)
                    checkGlUpload("uploading distant edges")
                }
                uploaded += GpuChunk(chunk, id,ids[value.chunks.size+index],coarse.size/7)
            }
            if (selection) {
                selectionChunks = uploaded
                uploadedSelectionScene = value
            } else {
                chunks = uploaded
                uploadedScene = value
            }
            return true
        } catch (error: IllegalStateException) {
            // The transaction owns every generated name, including buffers not reached by the loop.
            GLES20.glDeleteBuffers(ids.size, ids, 0)
            releaseGlResources()
            reportError("The drawing could not be uploaded to the GPU: ${error.message.orEmpty()}")
            return false
        } finally {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        }
    }

    /** Allocation checks stay out of the camera/draw hot path. */
    private fun checkGlUpload(operation: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) {
            if (error == GLES20.GL_OUT_OF_MEMORY) "GPU memory is exhausted while $operation"
            else "OpenGL ES error 0x${error.toString(16)} while $operation"
        }
    }

    private fun deleteChunkBuffers(selection: Boolean = false) {
        val batches = if (selection) selectionChunks else chunks
        if (batches.isNotEmpty()) {
            val ids = batches.flatMap {listOf(it.buffer,it.lodBuffer)}.toIntArray()
            GLES20.glDeleteBuffers(ids.size, ids, 0)
            if (selection) selectionChunks = emptyList() else chunks = emptyList()
        }
    }

    private fun drawBuffer(buffer: Int, vertexCount: Int) {
        if (buffer == 0 || vertexCount == 0) return
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffer)
        GLES20.glVertexAttribPointer(positionAttribute, 3, GLES20.GL_FLOAT, false, VERTEX_BYTES, 0)
        GLES20.glVertexAttribPointer(colorAttribute, 4, GLES20.GL_FLOAT, false, VERTEX_BYTES, 3 * FLOAT_BYTES)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, vertexCount)
    }

    private fun planMatrix(current: CanvasFrame): FloatArray = FloatArray(16).also {
        val x=2*current.viewport.pixelsPerMetre/width;val y=2*current.viewport.pixelsPerMetre/height
        val c=kotlin.math.cos(current.viewport.rotation);val s=kotlin.math.sin(current.viewport.rotation)
        it[0]=(x*c).toFloat();it[4]=(-x*s).toFloat();it[1]=(y*s).toFloat();it[5]=(y*c).toFloat()
        // Plan view projects all elevations to XY. Depth testing is disabled.
        it[15] = 1f
    }

    /** Six homogeneous clip half-spaces, evaluated without allocating eight corners per chunk. */
    private fun extractFrustumPlanes(matrix: FloatArray) {
        for (plane in 0 until 6) {
            val row = plane / 2
            val sign = if (plane % 2 == 0) 1.0 else -1.0
            for (column in 0 until 4) {
                frustumPlanes[plane * 4 + column] = matrix[column * 4 + 3] + sign * matrix[column * 4 + row]
            }
        }
    }

    /**
     * Culling follows the preview too: a selected batch can move into view from outside the old
     * bounds. Eight corners are transformed in Double; the reusable result avoids per-batch arrays.
     */
    private fun boundsRelativeToCamera(
        bounds: Bounds3,
        origin: Point3,
        delta: EditDelta?,
        linear: DoubleArray?,
    ) {
        if (delta == null || linear == null) {
            visibleBounds[0] = bounds.min.x - origin.x
            visibleBounds[1] = bounds.min.y - origin.y
            visibleBounds[2] = bounds.min.z - origin.z
            visibleBounds[3] = bounds.max.x - origin.x
            visibleBounds[4] = bounds.max.y - origin.y
            visibleBounds[5] = bounds.max.z - origin.z
            return
        }
        for (axis in 0..2) {
            visibleBounds[axis] = Double.POSITIVE_INFINITY
            visibleBounds[axis + 3] = Double.NEGATIVE_INFINITY
        }
        for (corner in 0 until 8) {
            val x = (if (corner and 1 == 0) bounds.min.x else bounds.max.x) - delta.pivot.x
            val y = (if (corner and 2 == 0) bounds.min.y else bounds.max.y) - delta.pivot.y
            val z = (if (corner and 4 == 0) bounds.min.z else bounds.max.z) - delta.pivot.z
            val tx = delta.pivot.x + delta.translation.x + linear[0] * x + linear[4] * y + linear[8] * z - origin.x
            val ty = delta.pivot.y + delta.translation.y + linear[1] * x + linear[5] * y + linear[9] * z - origin.y
            val tz = delta.pivot.z + delta.translation.z + linear[2] * x + linear[6] * y + linear[10] * z - origin.z
            visibleBounds[0] = min(visibleBounds[0], tx)
            visibleBounds[1] = min(visibleBounds[1], ty)
            visibleBounds[2] = min(visibleBounds[2], tz)
            visibleBounds[3] = max(visibleBounds[3], tx)
            visibleBounds[4] = max(visibleBounds[4], ty)
            visibleBounds[5] = max(visibleBounds[5], tz)
        }
    }

    private fun inFrustum(): Boolean {
        val minX = visibleBounds[0]
        val minY = visibleBounds[1]
        val minZ = visibleBounds[2]
        val maxX = visibleBounds[3]
        val maxY = visibleBounds[4]
        val maxZ = visibleBounds[5]
        for (plane in 0 until 6) {
            val start = plane * 4
            val x = frustumPlanes[start]
            val y = frustumPlanes[start + 1]
            val z = frustumPlanes[start + 2]
            val maximum = x * (if (x >= 0) maxX else minX) + y * (if (y >= 0) maxY else minY) +
                z * (if (z >= 0) maxZ else minZ) + frustumPlanes[start + 3]
            if (maximum < 0) return false
        }
        return true
    }

    private fun updateGrid(current: CanvasFrame) {
        val previous = gridFrame
        // Selection and handle motion do not invalidate the construction grid.
        if (previous != null && previous.viewport == current.viewport && previous.orbit == current.orbit &&
            previous.is3D == current.is3D && previous.gridSpacing == current.gridSpacing &&
            previous.dark == current.dark && previous.floorElevation == current.floorElevation &&
            gridWidth == width && gridHeight == height) return
        gridFrame = current
        gridWidth = width
        gridHeight = height
        gridVertexCount = 0
        val origin = if (current.is3D) current.orbit.target else
            Point3(current.viewport.center.x, current.viewport.center.y)
        val visibleHeight = if (!current.is3D) height / current.viewport.pixelsPerMetre else
            if (current.orbit.projection == Projection.ORTHOGRAPHIC) current.orbit.orthographicHeight else
                2 * current.orbit.distance * tan(OrbitCamera.FOV_Y / 2)
        val halfY = visibleHeight * if (current.is3D) 2.0 else 0.5
        val halfX = halfY * width / height
        // At far coordinates the grid must also respect the local resolution of a Double.
        val minimumSpacing = max(visibleHeight * MIN_GRID_PIXELS / height,
            max(Math.ulp(origin.x), Math.ulp(origin.y)) * 8)
        val spacing = adaptiveSpacing(current.gridSpacing,
            max(minimumSpacing, (halfX + halfY) * 2 / (MAX_GRID_LINES - 8)))
        val minX = origin.x - halfX
        val maxX = origin.x + halfX
        val minY = origin.y - halfY
        val maxY = origin.y + halfY
        val z = if (current.is3D) current.floorElevation - origin.z else 0.0
        if (listOf(minX, maxX, minY, maxY, z, spacing).any { !it.isFinite() } || spacing <= 0) return
        val firstX = ceil(minX / spacing)
        val firstY = ceil(minY / spacing)
        val countX = min(MAX_GRID_LINES, max(0.0, floor(maxX / spacing) - firstX + 1).toInt())
        val countY = min(MAX_GRID_LINES - countX, max(0.0, floor(maxY / spacing) - firstY + 1).toInt())
        for (index in 0 until countX) {
            val gridIndex = firstX + index
            val x = gridIndex * spacing - origin.x
            gridLine(x, minY - origin.y, x, maxY - origin.y, z, gridIndex, true, current.dark)
        }
        for (index in 0 until countY) {
            val gridIndex = firstY + index
            val y = gridIndex * spacing - origin.y
            gridLine(minX - origin.x, y, maxX - origin.x, y, z, gridIndex, false, current.dark)
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, gridBuffer)
        val floatCount = gridVertexCount * FLOATS_PER_VERTEX
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, floatCount * FLOAT_BYTES, stage(gridVertices, floatCount))
    }

    private fun gridLine(x1: Double, y1: Double, x2: Double, y2: Double, z: Double,
        index: Double, vertical: Boolean, dark: Boolean) {
        if (gridVertexCount + 2 > MAX_GRID_LINES * 2) return
        val axis = index == 0.0
        val major = abs(index % 5) < 0.1
        val r: Float
        val g: Float
        val b: Float
        val alpha: Float
        when {
            axis && vertical -> { r = 0.20f; g = 0.56f; b = 0.40f; alpha = 0.75f }
            axis -> { r = 0.84f; g = 0.32f; b = 0.30f; alpha = 0.75f }
            dark -> { r = 0.37f; g = 0.43f; b = 0.52f; alpha = if (major) 0.48f else 0.23f }
            else -> { r = 0.36f; g = 0.43f; b = 0.52f; alpha = if (major) 0.40f else 0.17f }
        }
        gridVertex(x1, y1, z, r, g, b, alpha)
        gridVertex(x2, y2, z, r, g, b, alpha)
    }

    private fun gridVertex(x: Double, y: Double, z: Double, r: Float, g: Float, b: Float, alpha: Float) {
        val start = gridVertexCount * FLOATS_PER_VERTEX
        gridVertices[start] = x.toFloat()
        gridVertices[start + 1] = y.toFloat()
        gridVertices[start + 2] = z.toFloat()
        gridVertices[start + 3] = r
        gridVertices[start + 4] = g
        gridVertices[start + 5] = b
        gridVertices[start + 6] = alpha
        gridVertexCount++
    }

    /** Keep the configured grid as the base; coarser intervals follow 1/2/5 powers of ten. */
    private fun adaptiveSpacing(base: Double, minimum: Double): Double {
        if (minimum <= base) return base
        val power = 10.0.pow(floor(log10(minimum / base)))
        val ratio = minimum / (base * power)
        val multiplier = when { ratio <= 1 -> 1.0; ratio <= 2 -> 2.0; ratio <= 5 -> 5.0; else -> 10.0 }
        return base * power * multiplier
    }

    private fun stage(vertices: FloatArray, length: Int): FloatBuffer {
        if (staging.capacity() < length) staging = nativeFloats(length)
        staging.clear()
        staging.put(vertices, 0, length)
        staging.flip()
        return staging
    }

    private fun createSurfaceShader(): SurfaceShader {
        val precision=IntArray(1)
        GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER,GLES20.GL_HIGH_FLOAT,IntArray(2),0,precision,0)
        fragmentHighPrecision=precision[0]>0
        val vertex=if(fragmentHighPrecision)SURFACE_VERTEX_SHADER else SURFACE_VERTEX_SHADER.replace("varying highp","varying mediump")
        val fragment=if(fragmentHighPrecision)SURFACE_FRAGMENT_SHADER else SURFACE_FRAGMENT_SHADER.replace("highp","mediump")
        val id = createProgram(vertex,fragment)
        fun attribute(name: String) = GLES20.glGetAttribLocation(id, name)
        fun uniform(name: String) = GLES20.glGetUniformLocation(id, name)
        val shader = SurfaceShader(id, attribute("aPosition"), attribute("aNormal"), attribute("aColor"),
            uniform("uMatrix"), uniform("uModel"), uniform("uNormalMatrix"), uniform("uOffset"),
            uniform("uLighting"), uniform("uOpacity"), uniform("uSelection"), uniform("uDark"), uniform("uViewDirection"),uniform("uKeyLight"),uniform("uLightStrength"),uniform("uTextureOffset"),uniform("uGrain"),uniform("uFrequency"),uniform("uTextureDensity"))
        if (listOf(shader.position, shader.normal, shader.color, shader.matrix, shader.model,
                shader.normalMatrix, shader.offset, shader.lighting, shader.opacity, shader.selection,
                shader.dark, shader.viewDirection,shader.keyLight,shader.lightStrength,shader.textureOffset,shader.grain,shader.frequency,shader.textureDensity).any { it < 0 }) {
            GLES20.glDeleteProgram(id)
            error("Required surface shader inputs are unavailable")
        }
        return shader
    }

    private fun createProgram(vertexSource: String = VERTEX_SHADER, fragmentSource: String = FRAGMENT_SHADER): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = try { compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource) }
        catch (error: IllegalStateException) { GLES20.glDeleteShader(vertex); throw error }
        val result = GLES20.glCreateProgram()
        try {
            check(result != 0) { "Cannot create an OpenGL ES2 program" }
            GLES20.glAttachShader(result, vertex)
            GLES20.glAttachShader(result, fragment)
            GLES20.glLinkProgram(result)
            val status = IntArray(1)
            GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "Drawing shader link failed: ${GLES20.glGetProgramInfoLog(result)}" }
            return result
        } catch (error: IllegalStateException) {
            if (result != 0) GLES20.glDeleteProgram(result)
            throw error
        } finally {
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "Cannot create an OpenGL ES2 shader" }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("Drawing shader compilation failed: $log")
        }
        return shader
    }

    private data class GpuChunk(val source: SceneChunk, val buffer: Int,val lodBuffer:Int=0,val lodCount:Int=0)
    private class ExtraLineCache(var scene: DrawingScene? = null, var chunks: List<GpuChunk> = emptyList())
    private data class GpuSurfaceChunk(val source: SurfaceChunk, val buffer: Int,val lodBuffer:Int=0,val lodCount:Int=0)
    private data class SurfaceShader(
        val program: Int, val position: Int, val normal: Int, val color: Int,
        val matrix: Int, val model: Int, val normalMatrix: Int, val offset: Int,
        val lighting: Int, val opacity: Int, val selection: Int, val dark: Int, val viewDirection: Int,
        val keyLight:Int,val lightStrength:Int,val textureOffset:Int,val grain:Int,val frequency:Int,val textureDensity:Int,
    )

    private companion object {
        const val FLOAT_BYTES = 4
        const val FLOATS_PER_VERTEX = 7
        const val VERTEX_BYTES = FLOATS_PER_VERTEX * FLOAT_BYTES
        const val SURFACE_FLOATS_PER_VERTEX = 10
        const val SURFACE_VERTEX_BYTES = SURFACE_FLOATS_PER_VERTEX * FLOAT_BYTES
        const val MAX_GRID_LINES = 256
        const val MAX_GRID_FLOATS = MAX_GRID_LINES * 2 * FLOATS_PER_VERTEX
        const val MIN_GRID_PIXELS = 16.0

        val IDENTITY_MATRIX = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )

        fun nativeFloats(size: Int): FloatBuffer = ByteBuffer.allocateDirect(size * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()

        const val VERTEX_SHADER = """
            precision highp float;
            uniform mat4 uMatrix;
            uniform mat4 uModel;
            uniform vec3 uOffset;
            uniform float uDark;
            uniform float uSelection;
            uniform float uOpacity;
            attribute vec3 aPosition;
            attribute vec4 aColor;
            varying lowp vec4 vColor;
            void main() {
                vec3 position = (uModel * vec4(aPosition, 1.0)).xyz + uOffset;
                gl_Position = uMatrix * vec4(position, 1.0);
                vec3 color = aColor.rgb;
                float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));
                if (uDark > 0.5 && luminance < 0.12) {
                    color = clamp(color + vec3(0.74 - luminance), 0.0, 1.0);
                }
                color = mix(color, vec3(0.11, 0.48, 1.0), uSelection);
                vColor = vec4(color, aColor.a * uOpacity);
            }
        """
        const val FRAGMENT_SHADER = """
            precision mediump float;
            varying lowp vec4 vColor;
            void main() { gl_FragColor = vColor; }
        """
        const val SURFACE_VERTEX_SHADER = """
            precision highp float;
            uniform mat4 uMatrix;
            uniform mat4 uModel;
            uniform mat3 uNormalMatrix;
            uniform vec3 uOffset;
            uniform float uSelection;
            uniform float uDark;
            attribute vec3 aPosition;
            uniform vec3 uTextureOffset;
            attribute vec3 aNormal;
            attribute vec4 aColor;
            varying mediump vec3 vNormal;
            varying highp vec3 vTexturePosition;
            varying highp float vDepth;
            varying lowp vec4 vColor;
            void main() {
                vec3 position = (uModel * vec4(aPosition, 1.0)).xyz + uOffset;
                gl_Position = uMatrix * vec4(position, 1.0);
                vNormal = uNormalMatrix * aNormal;
                vTexturePosition = (uModel * vec4(aPosition,1.0)).xyz + uTextureOffset;
                vDepth=gl_Position.w;
                vec3 color = aColor.rgb;
                float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));
                if (uDark > 0.5 && luminance < 0.08) {
                    color = clamp(color + vec3(0.14 - luminance), 0.0, 1.0);
                }
                vColor = vec4(mix(color, vec3(0.11, 0.48, 1.0), uSelection), aColor.a);
            }
        """
        const val SURFACE_FRAGMENT_SHADER = """
            precision highp float;
            uniform float uLighting;
            uniform float uOpacity;
            uniform vec3 uViewDirection;
            uniform vec3 uKeyLight;
            uniform vec2 uLightStrength;
            uniform float uGrain;
            uniform float uFrequency;
            uniform vec2 uTextureDensity;
            varying mediump vec3 vNormal;
            varying highp vec3 vTexturePosition;
            varying highp float vDepth;
            varying lowp vec4 vColor;
            void main() {
                vec3 normal = normalize(vNormal);
                if (!gl_FrontFacing) normal = -normal;
                vec3 key = normalize(uKeyLight);
                float diffuse = max(dot(normal, key), 0.0);
                float illumination = uLighting > 0.5 ? uLightStrength.x + uLightStrength.y * diffuse : 1.0;
                float specular = 0.0;
                if (uLighting > 1.5) {
                    vec3 halfway = normalize(key + normalize(uViewDirection));
                    specular = 0.035 * pow(max(dot(normal, halfway), 0.0), 12.0);
                }
                float grain=1.0;
                if(uGrain>0.0){
                    float density=uTextureDensity.y>.5 ? uTextureDensity.x/max(.001,vDepth) : uTextureDensity.x;
                    density*=abs(dot(normal,uViewDirection));
                    float strength=uGrain*clamp((density/max(.01,uFrequency)-4.0)/8.0,0.0,1.0);
                    if(strength>0.0){
                    float f=uFrequency*6.2831853;
                    float noise=(sin(dot(vTexturePosition,vec3(.75,.5,1.0))*f)+sin(dot(vTexturePosition,vec3(1.0,.75,-.5))*f+1.7)+sin(dot(vTexturePosition,vec3(-.5,1.0,.75))*f+4.1))/3.0;
                    grain=1.0-strength*.5+strength*.5*noise;
                    }
                }
                gl_FragColor = vec4(clamp(vColor.rgb * illumination * grain + vec3(specular), 0.0, 1.0), vColor.a * uOpacity);
            }
        """
    }
}
