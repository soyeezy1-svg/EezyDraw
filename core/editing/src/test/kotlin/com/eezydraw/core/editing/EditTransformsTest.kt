package com.eezydraw.core.editing

import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.Rotation3
import com.eezydraw.core.model.Scale3
import com.eezydraw.core.model.Transform3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class EditTransformsTest {
    @Test
    fun `global delta composes full mixed XYZ orientation and signed scale`() {
        val original = Transform3(Point3(12.5, -4.0, 8.0), Rotation3(0.51, -0.83, 1.19), Scale3(-2.0, 3.0, 0.7))
        val delta = EditDelta(Point3(3.0, 4.0, 1.0), Point3(2.0, -3.0, 7.0), 0.74, 1.6)
        val transformed = EditTransforms.apply(original, delta)
        for (point in listOf(Point3(), Point3(1.0, 2.0, 3.0), Point3(-4.0, 6.0, -8.0))) {
            assertPoint(delta.apply(world(original, point)), world(transformed, point))
        }
    }

    @Test
    fun `mirrors reflect geometry around pivot and a second reflection restores it`() {
        val original = Transform3(Point3(12.0, -4.0, 8.0), Rotation3(0.61, 0.83, -1.23), Scale3(-2.0, 3.0, -0.7))
        val pivot = Point3(7.0, -5.0, 99.0)
        for (axis in MirrorAxis.entries) {
            val mirrored = EditTransforms.mirror(original, axis, pivot)
            for (point in listOf(Point3(), Point3(1.0, 2.0, 3.0), Point3(-4.0, 6.0, -8.0))) {
                val old = world(original, point)
                val expected = when (axis) {
                    MirrorAxis.X -> old.copy(x = 2.0 * pivot.x - old.x)
                    MirrorAxis.Y -> old.copy(y = 2.0 * pivot.y - old.y)
                }
                assertPoint(expected, world(mirrored, point))
            }
            assertEquals(original, EditTransforms.mirror(mirrored, axis, pivot))
        }
    }

    @Test
    fun `Euler decomposition preserves orientations at and near gimbal lock`() {
        for (sign in listOf(-1.0, 1.0)) {
            for (epsilon in listOf(0.0, 1e-10, 1e-7, 1e-4)) {
                val original = Transform3(rotation = Rotation3(0.0, sign * (PI / 2 - epsilon), 0.0))
                val delta = EditDelta(rotationZ = PI)
                val result = EditTransforms.apply(original, delta)
                for (point in listOf(Point3(1.0), Point3(y = 1.0), Point3(z = 1.0))) {
                    assertPoint(delta.apply(world(original, point)), world(result, point), 2e-8)
                }
            }
        }
    }

    @Test
    fun `GPU matrix matches delta linear component without a float world translation`() {
        val delta = EditDelta(Point3(1e12, -1e12, 8.0), Point3(0.25, 0.5, 1.0), PI / 2, 2.0)
        assertArrayEquals(
            floatArrayOf(0f, 2f, 0f, 0f, -2f, 0f, 0f, 0f, 0f, 0f, 2f, 0f, 0f, 0f, 0f, 1f),
            delta.linearMatrix(), 1e-6f,
        )
        assertPoint(Point3(1e12 + 0.25, -1e12 + 2.5, 9.0), delta.apply(Point3(1e12 + 1.0, -1e12, 8.0)))
    }

    @Test
    fun `identity is exact and invalid scales are rejected`() {
        val original = Transform3(rotation = Rotation3(12.1, -8.3, 7.4))
        assertSame(original, EditTransforms.apply(original, EditDelta(pivot = Point3(100.0))))
        for (scale in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { EditDelta(scale = scale) }
        }
    }

    private fun world(t: Transform3, point: Point3): Point3 {
        val x = point.x * t.scale.x
        val y = point.y * t.scale.y
        val z = point.z * t.scale.z
        val zx = cos(t.rotation.z) * x - sin(t.rotation.z) * y
        val zy = sin(t.rotation.z) * x + cos(t.rotation.z) * y
        val yx = cos(t.rotation.y) * zx + sin(t.rotation.y) * z
        val yz = -sin(t.rotation.y) * zx + cos(t.rotation.y) * z
        return Point3(
            t.translation.x + yx,
            t.translation.y + cos(t.rotation.x) * zy - sin(t.rotation.x) * yz,
            t.translation.z + sin(t.rotation.x) * zy + cos(t.rotation.x) * yz,
        )
    }

    private fun assertPoint(expected: Point3, actual: Point3, tolerance: Double = 1e-9) {
        assertEquals(expected.x, actual.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
        assertEquals(expected.z, actual.z, tolerance)
    }
}
