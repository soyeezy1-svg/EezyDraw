package com.eezydraw.core.geometry

import com.eezydraw.core.model.Bounds3
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.Projection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

enum class ViewPreset { TOP, FRONT, SIDE, ISOMETRIC }

/** Z-up orbital camera. All geometry stays in doubles until the floating-origin GPU matrix. */
data class OrbitCamera(
    val target: Point3 = Point3(),
    val yaw: Double = PI / 4,
    val pitch: Double = 0.6154797086703874,
    val distance: Double = 30.0,
    val orthographicHeight: Double = 20.0,
    val projection: Projection = Projection.ORTHOGRAPHIC,
) {
    init {
        require(yaw.isFinite() && pitch.isFinite() && pitch in -PI / 2..PI / 2)
        require(distance.isFinite() && distance in MIN_SIZE..MAX_SIZE)
        require(orthographicHeight.isFinite() && orthographicHeight in MIN_SIZE..MAX_SIZE)
    }

    fun orbit(dxRadians: Double, dyRadians: Double): OrbitCamera {
        if (!dxRadians.isFinite() || !dyRadians.isFinite()) return this
        // Reduce increments first so a finite, very large gesture cannot overflow the yaw.
        val nextYaw = (yaw % (2 * PI) + dxRadians % (2 * PI)) % (2 * PI)
        return copy(yaw = nextYaw, pitch = (pitch + dyRadians).coerceIn(-PI / 2, PI / 2))
    }

    fun pan(dxPixels: Double, dyPixels: Double, width: Double, height: Double): OrbitCamera {
        if (!validSurface(width, height) || !dxPixels.isFinite() || !dyPixels.isFinite()) return this
        val basis = basis()
        val scale = visibleHeight() / height
        val shift = basis.right * (-dxPixels * scale) + basis.up * (dyPixels * scale)
        val moved = vector(target) + shift
        return if (moved.finite()) copy(target = moved.point()) else this
    }

    fun zoom(factor: Double): OrbitCamera {
        if (!factor.isFinite() || factor <= 0) return this
        return copy(distance = (distance / factor).coerceIn(MIN_SIZE, MAX_SIZE),
            orthographicHeight = (orthographicHeight / factor).coerceIn(MIN_SIZE, MAX_SIZE))
    }

    fun zoomAt(factor:Double,x:Double,y:Double,width:Double,height:Double):OrbitCamera =
        pan(width/2-x,height/2-y,width,height).zoom(factor).pan(x-width/2,y-height/2,width,height)

    fun eye():Point3 = (vector(target)+basis().outward*distance).point()

    /** Turn the viewing direction while retaining the eye, unlike orbit around a target. */
    fun lookAround(dxRadians:Double,dyRadians:Double):OrbitCamera {
        val eye=vector(eye());val next=orbit(dxRadians,dyRadians)
        return next.copy(target=(eye-next.basis().outward*distance).point())
    }

    /** Walk in the horizontal view direction; no collision or gravity is inferred. */
    fun walk(right:Double,forward:Double,up:Double=0.0):OrbitCamera {
        if(!listOf(right,forward,up).all {it.isFinite()})return this
        val moved=vector(target)+Vec(-sin(yaw)*right-cos(yaw)*forward,cos(yaw)*right-sin(yaw)*forward,up)
        return if(moved.finite())copy(target=moved.point())else this
    }

    /** Preserve the target plane's apparent height while switching between lens types. */
    fun withProjection(value: Projection): OrbitCamera = when {
        value == projection -> this
        value == Projection.PERSPECTIVE -> copy(projection = value,
            distance = (orthographicHeight / (2 * tan(FOV_Y / 2))).coerceIn(MIN_SIZE, MAX_SIZE))
        else -> copy(projection = value,
            orthographicHeight = (2 * distance * tan(FOV_Y / 2)).coerceIn(MIN_SIZE, MAX_SIZE))
    }

    fun preset(value: ViewPreset): OrbitCamera = when (value) {
        ViewPreset.TOP -> copy(yaw = -PI / 2, pitch = PI / 2)
        ViewPreset.FRONT -> copy(yaw = -PI / 2, pitch = 0.0)
        ViewPreset.SIDE -> copy(yaw = 0.0, pitch = 0.0)
        ViewPreset.ISOMETRIC -> copy(yaw = PI / 4, pitch = asin(1 / sqrt(3.0)))
    }

    fun fit(bounds: Bounds3, aspect: Double): OrbitCamera {
        if (!aspect.isFinite() || aspect <= 0) return this
        val middle = Point3(bounds.min.x / 2 + bounds.max.x / 2,
            bounds.min.y / 2 + bounds.max.y / 2, bounds.min.z / 2 + bounds.max.z / 2)
        val b = basis()
        var halfHeight = MIN_SIZE
        var fitDistance = MIN_SIZE
        var frontExtent = MIN_SIZE
        for (corner in corners(bounds)) {
            val relative = vector(corner) - vector(middle)
            val x = abs(relative dot b.right)
            val y = abs(relative dot b.up)
            val depth = relative dot b.outward
            halfHeight = max(halfHeight, max(y, x / aspect))
            fitDistance = max(fitDistance, depth + max(y / tan(FOV_Y / 2), x / (aspect * tan(FOV_Y / 2))))
            frontExtent = max(frontExtent, abs(depth))
        }
        return copy(target = middle,
            orthographicHeight = (halfHeight * 2.4).coerceIn(MIN_SIZE, MAX_SIZE),
            distance = max(fitDistance * 1.2, frontExtent * 1.2 + MIN_SIZE).coerceIn(MIN_SIZE, MAX_SIZE))
    }

    /** Column-major OpenGL ES matrix; the caller must subtract [target] in double precision. */
    fun viewProjectionMatrix(aspect: Double): FloatArray {
        val safeAspect = if (aspect.isFinite() && aspect > 0) aspect else 1.0
        val b = basis()
        val near = nearPlane()
        val far = farPlane()
        val a: Double
        val scaleY: Double
        val scaleX: Double
        val translationZ: Double
        val wZ: Double
        val wTranslation: Double
        if (projection == Projection.PERSPECTIVE) {
            scaleY = 1 / tan(FOV_Y / 2)
            scaleX = scaleY / safeAspect
            a = -(far + near) / (far - near)
            translationZ = -a * distance - 2 * far * near / (far - near)
            wZ = -1.0
            wTranslation = distance
        } else {
            scaleY = 2 / orthographicHeight
            scaleX = scaleY / safeAspect
            a = -2 / (far - near)
            translationZ = -a * distance - (far + near) / (far - near)
            wZ = 0.0
            wTranslation = 1.0
        }
        return floatArrayOf(
            (scaleX * b.right.x).toFloat(), (scaleY * b.up.x).toFloat(), (a * b.outward.x).toFloat(), (wZ * b.outward.x).toFloat(),
            (scaleX * b.right.y).toFloat(), (scaleY * b.up.y).toFloat(), (a * b.outward.y).toFloat(), (wZ * b.outward.y).toFloat(),
            (scaleX * b.right.z).toFloat(), (scaleY * b.up.z).toFloat(), (a * b.outward.z).toFloat(), (wZ * b.outward.z).toFloat(),
            0f, 0f, translationZ.toFloat(), wTranslation.toFloat(),
        )
    }

    /** Pixel x/y and OpenGL depth. Points behind the perspective eye are not projected. */
    fun project(point: Point3, width: Double, height: Double): Point3? {
        if (!validSurface(width, height)) return null
        val relative = vector(point) - vector(target)
        val b = basis()
        val cameraZ = (relative dot b.outward) - distance
        if (projection == Projection.PERSPECTIVE && cameraZ >= -nearPlane()) return null
        val halfHeight = if (projection == Projection.PERSPECTIVE) -cameraZ * tan(FOV_Y / 2) else orthographicHeight / 2
        val ndcX = (relative dot b.right) / (halfHeight * width / height)
        val ndcY = (relative dot b.up) / halfHeight
        val near = nearPlane()
        val far = farPlane()
        val ndcZ = if (projection == Projection.PERSPECTIVE)
            ((far + near) / (near - far) * cameraZ + 2 * far * near / (near - far)) / -cameraZ
        else -2 * cameraZ / (far - near) - (far + near) / (far - near)
        val x = (ndcX + 1) * width / 2
        val y = (1 - ndcY) * height / 2
        return if (x.isFinite() && y.isFinite() && ndcZ.isFinite()) Point3(x, y, ndcZ) else null
    }

    /** Returns the forward screen ray's intersection with the horizontal construction plane. */
    fun rayToPlaneZ(screenX: Double, screenY: Double, width: Double, height: Double, z: Double): Point3? {
        if (!validSurface(width, height) || !screenX.isFinite() || !screenY.isFinite() || !z.isFinite()) return null
        val b = basis()
        val ndcX = 2 * screenX / width - 1
        val ndcY = 1 - 2 * screenY / height
        val offset = b.right * (ndcX * visibleHeight() * width / height / 2) +
            b.up * (ndcY * visibleHeight() / 2)
        val eye = vector(target) + b.outward * distance
        val origin = if (projection == Projection.PERSPECTIVE) eye else eye + offset
        val direction = if (projection == Projection.PERSPECTIVE) offset - b.outward * distance else b.outward * -1.0
        if (abs(direction.z) <= 1e-12 * max(1.0, direction.length())) return null
        val t = (z - origin.z) / direction.z
        if (!t.isFinite() || t < 0) return null
        val result = origin + direction * t
        return if (result.finite()) Point3(result.x, result.y, z) else null
    }

    /** Conservative frustum test used for drawing chunks, including chunks crossing the eye plane. */
    fun visible(bounds: Bounds3, aspect: Double): Boolean {
        if (!aspect.isFinite() || aspect <= 0) return true
        val b = basis()
        val near = nearPlane()
        val far = farPlane()
        val coordinates = corners(bounds).map { point ->
            val p = vector(point) - vector(target)
            doubleArrayOf(p dot b.right, p dot b.up, distance - (p dot b.outward))
        }
        if (coordinates.all { it[2] < near } || coordinates.all { it[2] > far }) return false
        fun halfHeight(depth: Double) = if (projection == Projection.PERSPECTIVE)
            max(0.0, depth) * tan(FOV_Y / 2) else orthographicHeight / 2
        if (coordinates.all { it[0] < -halfHeight(it[2]) * aspect } ||
            coordinates.all { it[0] > halfHeight(it[2]) * aspect } ||
            coordinates.all { it[1] < -halfHeight(it[2]) } ||
            coordinates.all { it[1] > halfHeight(it[2]) }) return false
        return true
    }

    private fun visibleHeight() = if (projection == Projection.PERSPECTIVE) 2 * distance * tan(FOV_Y / 2) else orthographicHeight
    private fun nearPlane() = max(1e-5, distance * 0.0001)
    private fun farPlane() = max(distance * 100, orthographicHeight * 100)
    private fun basis(): Basis {
        // An analytic basis remains defined at both poles, unlike cross(forward, worldUp).
        val cy = cos(yaw)
        val sy = sin(yaw)
        val cp = cos(pitch)
        val sp = sin(pitch)
        return Basis(Vec(-sy, cy, 0.0), Vec(-sp * cy, -sp * sy, cp), Vec(cp * cy, cp * sy, sp))
    }

    private data class Basis(val right: Vec, val up: Vec, val outward: Vec)
    private data class Vec(val x: Double, val y: Double, val z: Double) {
        operator fun plus(other: Vec) = Vec(x + other.x, y + other.y, z + other.z)
        operator fun minus(other: Vec) = Vec(x - other.x, y - other.y, z - other.z)
        operator fun times(value: Double) = Vec(x * value, y * value, z * value)
        infix fun dot(other: Vec) = x * other.x + y * other.y + z * other.z
        fun length() = sqrt(this dot this)
        fun finite() = x.isFinite() && y.isFinite() && z.isFinite()
        fun point() = Point3(x, y, z)
    }

    companion object {
        const val FOV_Y = PI / 3
        private const val MIN_SIZE = 1e-5
        private const val MAX_SIZE = 1e12
        private fun vector(point: Point3) = Vec(point.x, point.y, point.z)
        private fun validSurface(width: Double, height: Double) = width.isFinite() && height.isFinite() && width > 0 && height > 0
        private fun corners(bounds: Bounds3): List<Point3> = listOf(bounds.min.x, bounds.max.x).flatMap { x ->
            listOf(bounds.min.y, bounds.max.y).flatMap { y -> listOf(bounds.min.z, bounds.max.z).map { z -> Point3(x, y, z) } }
        }
    }
}
