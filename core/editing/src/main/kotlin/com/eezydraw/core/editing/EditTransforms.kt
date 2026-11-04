package com.eezydraw.core.editing

import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.Rotation3
import com.eezydraw.core.model.Scale3
import com.eezydraw.core.model.Transform3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.abs

/** A project-space transform around [pivot]. Lengths are metres, angles radians. */
data class EditDelta(
    val pivot: Point3 = Point3(),
    val translation: Point3 = Point3(),
    val rotationZ: Double = 0.0,
    val scale: Double = 1.0,
    /** Positive dimension ratios along the orthonormal [axes] frame, before global Z rotation. */
    val axisScale: Point3 = Point3(1.0, 1.0, 1.0),
    val axes: Rotation3 = Rotation3(),
) {
    init {
        require(rotationZ.isFinite()) { "Rotation must be finite" }
        require(scale.isFinite() && scale > 0.0) { "Scale must be positive and finite" }
        require(axisScale.x > 0.0 && axisScale.y > 0.0 && axisScale.z > 0.0) {
            "Dimension scales must be positive and finite"
        }
        require(listOf(axisScale.x, axisScale.y, axisScale.z).all { (it * scale).let { factor -> factor.isFinite() && factor > 0.0 } }) {
            "Combined dimension scales must be positive and finite"
        }
    }

    fun apply(point: Point3): Point3 {
        if (axisScale.x != axisScale.y || axisScale.y != axisScale.z) {
            val x = point.x - pivot.x
            val y = point.y - pivot.y
            val z = point.z - pivot.z
            return Point3(
                pivot.x + translation.x + linear[0] * x + linear[1] * y + linear[2] * z,
                pivot.y + translation.y + linear[3] * x + linear[4] * y + linear[5] * z,
                pivot.z + translation.z + linear[6] * x + linear[7] * y + linear[8] * z,
            )
        }
        val factor = scale * axisScale.x
        val x = (point.x - pivot.x) * factor
        val y = (point.y - pivot.y) * factor
        val c = cos(rotationZ)
        val s = sin(rotationZ)
        return Point3(
            pivot.x + translation.x + c * x - s * y,
            pivot.y + translation.y + s * x + c * y,
            pivot.z + translation.z + (point.z - pivot.z) * factor,
        )
    }

    private val linear: DoubleArray = run {
        val values = doubleArrayOf(axisScale.x * scale, axisScale.y * scale, axisScale.z * scale)
        val scaled = DoubleArray(9)
        if (axisScale.x == axisScale.y && axisScale.y == axisScale.z) {
            for (axis in 0..2) scaled[axis * 4] = values[axis]
        } else {
            val basis = editRotationMatrix(axes)
            for (row in 0..2) for (column in 0..2) {
                scaled[row * 3 + column] = (0..2).sumOf { index ->
                    basis[row * 3 + index] * values[index] * basis[column * 3 + index]
                }
            }
        }
        val c = cos(rotationZ); val s = sin(rotationZ)
        scaled.copyOf().also { result ->
            for (column in 0..2) {
                result[column] = c * scaled[column] - s * scaled[3 + column]
                result[3 + column] = s * scaled[column] + c * scaled[3 + column]
            }
        }
    }

    /** Column-major coefficients, copied once for Double-precision CPU bounds/chunk work. */
    fun linearMatrixDouble(): DoubleArray = doubleArrayOf(
        linear[0], linear[3], linear[6], 0.0,
        linear[1], linear[4], linear[7], 0.0,
        linear[2], linear[5], linear[8], 0.0,
        0.0, 0.0, 0.0, 1.0,
    )

    /** Column-major GLES matrix; translation is deliberately applied in Double separately. */
    fun linearMatrix(): FloatArray {
        return floatArrayOf(
            linear[0].toFloat(), linear[3].toFloat(), linear[6].toFloat(), 0f,
            linear[1].toFloat(), linear[4].toFloat(), linear[7].toFloat(), 0f,
            linear[2].toFloat(), linear[5].toFloat(), linear[8].toFloat(), 0f,
            0f, 0f, 0f, 1f,
        )
    }

    val isIdentity: Boolean
        get() = translation == Point3() && rotationZ == 0.0 && scale == 1.0 && axisScale == Point3(1.0, 1.0, 1.0)
}

/** X mirrors across the project YZ plane through the pivot; Y across its XZ plane. */
enum class MirrorAxis { X, Y }

object EditTransforms {
    /** Compose a global Z rotation with the model's intrinsic local XYZ Euler convention. */
    fun apply(transform: Transform3, delta: EditDelta): Transform3 {
        if (delta.isIdentity) return transform
        if (delta.axisScale.x != delta.axisScale.y || delta.axisScale.y != delta.axisScale.z) {
            val originalAxes = editRotationMatrix(transform.rotation)
            val editAxes = editRotationMatrix(delta.axes)
            require(originalAxes.indices.all { abs(originalAxes[it] - editAxes[it]) < 1e-10 }) {
                "Resize one object along its own dimension axes."
            }
        }
        val rotation = if (delta.rotationZ == 0.0) transform.rotation else {
            val old = editRotationMatrix(transform.rotation)
            val c = cos(delta.rotationZ)
            val s = sin(delta.rotationZ)
            val composed = old.copyOf()
            for (column in 0..2) {
                composed[column] = c * old[column] - s * old[3 + column]
                composed[3 + column] = s * old[column] + c * old[3 + column]
            }
            rotationFromMatrix(composed)
        }
        return Transform3(
            translation = delta.apply(transform.translation),
            rotation = rotation,
            scale = Scale3(
                transform.scale.x * (delta.scale * delta.axisScale.x),
                transform.scale.y * (delta.scale * delta.axisScale.y),
                transform.scale.z * (delta.scale * delta.axisScale.z),
            ),
        )
    }

    /** Reflect the full geometry, including orientation and handedness. */
    fun mirror(transform: Transform3, axis: MirrorAxis, pivot: Point3): Transform3 {
        val t = transform.translation
        val r = transform.rotation
        val s = transform.scale
        return when (axis) {
            MirrorAxis.X -> transform.copy(
                translation = t.copy(x = pivot.x - (t.x - pivot.x)),
                rotation = Rotation3(r.x, -r.y, -r.z),
                scale = s.copy(x = -s.x),
            )
            MirrorAxis.Y -> transform.copy(
                translation = t.copy(y = pivot.y - (t.y - pivot.y)),
                rotation = Rotation3(-r.x, r.y, -r.z),
                scale = s.copy(y = -s.y),
            )
        }
    }

    private fun rotationFromMatrix(m: DoubleArray): Rotation3 {
        val sy = m[2].coerceIn(-1.0, 1.0)
        val cy = hypot(m[0], m[1])
        val y = atan2(sy, cy)
        return if (cy > 1e-12) {
            Rotation3(atan2(-m[5], m[8]), y, atan2(-m[1], m[0]))
        } else {
            // At gimbal lock X and Z are coupled. Choose Z=0 without changing orientation.
            Rotation3(atan2(if (sy >= 0.0) m[3] else -m[3], m[4]), y, 0.0)
        }
    }
}

/** Row-major intrinsic XYZ orientation, shared by dimension frames and edit previews. */
internal fun editRotationMatrix(r: Rotation3): DoubleArray {
    val cx = cos(r.x); val sx = sin(r.x)
    val cy = cos(r.y); val sy = sin(r.y)
    val cz = cos(r.z); val sz = sin(r.z)
    return doubleArrayOf(
        cy * cz, -cy * sz, sy,
        cx * sz + sx * sy * cz, cx * cz - sx * sy * sz, -sx * cy,
        sx * sz - cx * sy * cz, sx * cz + cx * sy * sz, cx * cy,
    )
}
