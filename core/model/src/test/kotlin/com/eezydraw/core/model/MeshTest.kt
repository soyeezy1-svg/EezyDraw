package com.eezydraw.core.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshTest {
    private val vertices = listOf(Point3(), Point3(1.0), Point3(0.0, 1.0, 1.0))

    @Test fun `mesh faces round trip through the existing polymorphic shape format`() {
        val shape: Shape = Shape.Mesh(vertices, listOf(0, 1, 2))
        val encoded = Json.encodeToString(shape)
        assertTrue(encoded.contains("\"type\":\"mesh\""))
        assertEquals(shape, Json.decodeFromString<Shape>(encoded))
        assertEquals(Shape.Line(Point3(), Point3(1.0)), Json.decodeFromString<Shape>(
            """{"type":"line","start":{},"end":{"x":1.0}}""",
        ))
    }

    @Test fun `incomplete out of range and repeated triangle indices are rejected`() {
        for (indices in listOf(emptyList(), listOf(0, 1), listOf(-1, 1, 2), listOf(0, 1, 3), listOf(0, 1, 1))) {
            assertThrows(IllegalArgumentException::class.java) { Shape.Mesh(vertices, indices) }
        }
        assertThrows(IllegalArgumentException::class.java) { Shape.Mesh(vertices.take(2), listOf(0, 1, 2)) }
        assertThrows(IllegalArgumentException::class.java) {
            Json.decodeFromString<Shape>(
                """{"type":"mesh","vertices":[{},{"x":1.0},{"y":1.0}],"triangles":[0,1,3]}""",
            )
        }
    }

    @Test fun `mesh size budgets reject oversized indexed geometry before traversal`() {
        val excessiveVertices = object : AbstractList<Point3>() {
            override val size = 500_003
            override fun get(index: Int): Point3 = error("Oversized vertices must not be visited")
        }
        val excessiveTriangles = object : AbstractList<Int>() {
            override val size = 1_500_003
            override fun get(index: Int): Int = error("Oversized indices must not be visited")
        }
        assertThrows(IllegalArgumentException::class.java) { Shape.Mesh(excessiveVertices, listOf(0, 1, 2)) }
        assertThrows(IllegalArgumentException::class.java) { Shape.Mesh(vertices, excessiveTriangles) }
    }
}
