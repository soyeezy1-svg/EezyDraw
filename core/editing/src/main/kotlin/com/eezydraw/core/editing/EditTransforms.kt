package com.eezydraw.core.editing

import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.Rotation3
import com.eezydraw.core.model.Scale3
import com.eezydraw.core.model.Transform3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A project-space similarity transform around [pivot]. Lengths are metres, angles radians. */
data class EditDelta(
    val pivot: Point3 = Point3(),
    val translation: Point3 = Point3(),
    val rotationZ: Double = 0.0,
    val scale: Double = 1.0,
) {
    init {
        require(rotationZ.isFinite()) { "Rotation must be finite" }
        require(scale.isFinite() && scale > 0.0) { "Scale must be positive and finite" }
    }

    fun apply(point: Point3): Point3 {
        val x = (point.x - pivot.x) * scale
        val y = (point.y - pivot.y) * scale
        val c = cos(rotationZ)
        val s = sin(rotationZ)
        return Point3(
            pivot.x + translation.x + c * x - s * y,
            pivot.y + translation.y + s * x + c * y,
            pivot.z + translation.z + (point.z - pivot.z) * scale,
        )
    }

    /** Column-major GLES matrix; translation is deliberately applied in Double separately. */
    fun linearMatrix(): FloatArray {
        val c = (cos(rotationZ) * scale).toFloat()
        val s = (sin(rotationZ) * scale).toFloat()
        return floatArrayOf(
            c, s, 0f, 0f,
            -s, c, 0f, 0f,
            0f, 0f, scale.toFloat(), 0f,
            0f, 0f, 0f, 1f,
        )
    }

    internal val isIdentity: Boolean
        get() = translation == Point3() && rotationZ == 0.0 && scale == 1.0
}

/** X mirrors across the project YZ plane through the pivot; Y across its XZ plane. */
enum class MirrorAxis { X, Y }

object EditTransforms {
    /** Compose a global Z rotation with the model's intrinsic local XYZ Euler convention. */
    fun apply(transform: Transform3, delta: EditDelta): Transform3 {
        if (delta.isIdentity) return transform
        val rotation = if (delta.rotationZ == 0.0) transform.rotation else {
            val old = rotationMatrix(transform.rotation)
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
                transform.scale.x * delta.scale,
                transform.scale.y * delta.scale,
                transform.scale.z * delta.scale,
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

    private fun rotationMatrix(r: Rotation3): DoubleArray {
        val cx = cos(r.x)
        val sx = sin(r.x)
        val cy = cos(r.y)
        val sy = sin(r.y)
        val cz = cos(r.z)
        val sz = sin(r.z)
        return doubleArrayOf(
            cy * cz, -cy * sz, sy,
            cx * sz + sx * sy * cz, cx * cz - sx * sy * sz, -sx * cy,
            sx * sz - cx * sy * cz, sx * cz + cx * sy * sz, cx * cy,
        )
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
