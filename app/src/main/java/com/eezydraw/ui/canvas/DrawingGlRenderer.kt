package com.eezydraw.ui.canvas

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.eezydraw.core.geometry.DrawingScene
import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.SceneChunk
import com.eezydraw.core.geometry.Viewport2D
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
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/**
 * GLES2 wireframe renderer. A scene's immutable, spatially bounded batches upload once, while camera
 * gestures change only uniforms, culling and a bounded construction-grid buffer. All origin
 * subtraction happens in doubles before values reach the GPU.
 */
internal class DrawingGlRenderer(private val reportError: (String) -> Unit) : GLSurfaceView.Renderer {
    private val scene = AtomicReference<DrawingScene?>(null)
    private val frame = AtomicReference(CanvasFrame(Viewport2D(), OrbitCamera()))
    private val disposed = AtomicBoolean(false)

    // The fields below belong exclusively to the GL thread.
    private var program = 0
    private var positionAttribute = -1
    private var colorAttribute = -1
    private var matrixUniform = -1
    private var offsetUniform = -1
    private var darkUniform = -1
    private var width = 1
    private var height = 1
    private var drawingLineWidth = 1f
    private var uploadedScene: DrawingScene? = null
    private var chunks = emptyList<GpuChunk>()
    private var gridBuffer = 0
    private var gridVertexCount = 0
    private var gridFrame: CanvasFrame? = null
    private var gridWidth = 0
    private var gridHeight = 0
    private var staging = nativeFloats(MAX_GRID_FLOATS)
    private val gridVertices = FloatArray(MAX_GRID_FLOATS)
    private val frustumPlanes = DoubleArray(24)

    fun update(value: CanvasFrame) { frame.set(value) }
    fun setScene(value: DrawingScene) { scene.set(value) }
    fun dispose() {
        disposed.set(true)
        scene.set(null)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Handles from the old EGL context are invalid, even when the CPU scene is unchanged.
        program = 0
        gridBuffer = 0
        chunks = emptyList()
        uploadedScene = null
        gridFrame = null
        gridVertexCount = 0
        if (disposed.get()) return
        try {
            program = createProgram()
            positionAttribute = GLES20.glGetAttribLocation(program, "aPosition")
            colorAttribute = GLES20.glGetAttribLocation(program, "aColor")
            matrixUniform = GLES20.glGetUniformLocation(program, "uMatrix")
            offsetUniform = GLES20.glGetUniformLocation(program, "uOffset")
            darkUniform = GLES20.glGetUniformLocation(program, "uDark")
            check(positionAttribute >= 0 && colorAttribute >= 0 && matrixUniform >= 0 &&
                offsetUniform >= 0 && darkUniform >= 0) { "Required drawing shader inputs are unavailable" }
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
        val current = frame.get()
        if (current.dark) GLES20.glClearColor(0.055f, 0.071f, 0.10f, 1f)
        else GLES20.glClearColor(0.97f, 0.98f, 0.99f, 1f)
        GLES20.glDepthMask(true)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (disposed.get() || program == 0) return

        val pendingScene = scene.get()
        if (pendingScene !== uploadedScene && !uploadScene(pendingScene)) return

        val origin = if (current.is3D) current.orbit.target else
            Point3(current.viewport.center.x, current.viewport.center.y)
        val aspect = width.toDouble() / height
        val matrix = if (current.is3D) current.orbit.viewProjectionMatrix(aspect) else planMatrix(current)
        if (current.is3D) {
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            extractFrustumPlanes(matrix)
        } else GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(matrixUniform, 1, false, matrix, 0)
        GLES20.glUniform1f(darkUniform, if (current.dark) 1f else 0f)
        GLES20.glEnableVertexAttribArray(positionAttribute)
        GLES20.glEnableVertexAttribArray(colorAttribute)

        if (current.showGrid) {
            updateGrid(current)
            GLES20.glDepthMask(false)
            GLES20.glLineWidth(1f)
            GLES20.glUniform3f(offsetUniform, 0f, 0f, 0f)
            drawBuffer(gridBuffer, gridVertexCount)
            GLES20.glDepthMask(true)
        }

        GLES20.glLineWidth(drawingLineWidth)
        val halfWidth = width / (2 * current.viewport.pixelsPerMetre)
        val halfHeight = height / (2 * current.viewport.pixelsPerMetre)
        for (gpu in chunks) {
            val chunk = gpu.source
            if (current.levelId != null && chunk.levelId != null && chunk.levelId != current.levelId) continue
            if (current.is3D) {
                if (!inFrustum(chunk, origin)) continue
            } else {
                val bounds = chunk.bounds
                if (bounds.max.x - origin.x < -halfWidth || bounds.min.x - origin.x > halfWidth ||
                    bounds.max.y - origin.y < -halfHeight || bounds.min.y - origin.y > halfHeight) continue
            }
            val x = (chunk.origin.x - origin.x).toFloat()
            val y = (chunk.origin.y - origin.y).toFloat()
            val z = if (current.is3D) (chunk.origin.z - origin.z).toFloat() else 0f
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
            GLES20.glUniform3f(offsetUniform, x, y, z)
            drawBuffer(gpu.buffer, chunk.vertices.size / FLOATS_PER_VERTEX)
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisableVertexAttribArray(positionAttribute)
        GLES20.glDisableVertexAttribArray(colorAttribute)
    }

    /** Called on the GL thread, or reclaimed by EGL itself when a disposed surface is detached. */
    fun releaseGlResources() {
        if (positionAttribute >= 0) GLES20.glDisableVertexAttribArray(positionAttribute)
        if (colorAttribute >= 0) GLES20.glDisableVertexAttribArray(colorAttribute)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glUseProgram(0)
        deleteChunkBuffers()
        if (gridBuffer != 0) GLES20.glDeleteBuffers(1, intArrayOf(gridBuffer), 0)
        if (program != 0) GLES20.glDeleteProgram(program)
        gridBuffer = 0
        program = 0
        positionAttribute = -1
        colorAttribute = -1
        matrixUniform = -1
        offsetUniform = -1
        darkUniform = -1
        uploadedScene = null
        gridFrame = null
        gridVertexCount = 0
    }

    /** Publish the new GPU scene only after every batch has uploaded successfully. */
    private fun uploadScene(value: DrawingScene?): Boolean {
        deleteChunkBuffers()
        uploadedScene = null
        if (value == null || value.chunks.isEmpty()) {
            uploadedScene = value
            return true
        }
        val ids = IntArray(value.chunks.size)
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
                uploaded += GpuChunk(chunk, id)
            }
            chunks = uploaded
            uploadedScene = value
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

    private fun deleteChunkBuffers() {
        if (chunks.isNotEmpty()) {
            val ids = IntArray(chunks.size) { chunks[it].buffer }
            GLES20.glDeleteBuffers(ids.size, ids, 0)
            chunks = emptyList()
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
        it[0] = (2 * current.viewport.pixelsPerMetre / width).toFloat()
        it[5] = (2 * current.viewport.pixelsPerMetre / height).toFloat()
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

    private fun inFrustum(chunk: SceneChunk, origin: Point3): Boolean {
        val bounds = chunk.bounds
        val minX = bounds.min.x - origin.x
        val minY = bounds.min.y - origin.y
        val minZ = bounds.min.z - origin.z
        val maxX = bounds.max.x - origin.x
        val maxY = bounds.max.y - origin.y
        val maxZ = bounds.max.z - origin.z
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
        if (gridFrame == current && gridWidth == width && gridHeight == height) return
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

    private fun createProgram(): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = try { compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER) }
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

    private data class GpuChunk(val source: SceneChunk, val buffer: Int)

    private companion object {
        const val FLOAT_BYTES = 4
        const val FLOATS_PER_VERTEX = 7
        const val VERTEX_BYTES = FLOATS_PER_VERTEX * FLOAT_BYTES
        const val MAX_GRID_LINES = 256
        const val MAX_GRID_FLOATS = MAX_GRID_LINES * 2 * FLOATS_PER_VERTEX
        const val MIN_GRID_PIXELS = 16.0

        fun nativeFloats(size: Int): FloatBuffer = ByteBuffer.allocateDirect(size * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()

        const val VERTEX_SHADER = """
            precision highp float;
            uniform mat4 uMatrix;
            uniform vec3 uOffset;
            uniform float uDark;
            attribute vec3 aPosition;
            attribute vec4 aColor;
            varying lowp vec4 vColor;
            void main() {
                gl_Position = uMatrix * vec4(aPosition + uOffset, 1.0);
                vec3 color = aColor.rgb;
                float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));
                if (uDark > 0.5 && luminance < 0.12) {
                    color = clamp(color + vec3(0.74 - luminance), 0.0, 1.0);
                }
                vColor = vec4(color, aColor.a);
            }
        """
        const val FRAGMENT_SHADER = """
            precision mediump float;
            varying lowp vec4 vColor;
            void main() { gl_FragColor = vColor; }
        """
    }
}
