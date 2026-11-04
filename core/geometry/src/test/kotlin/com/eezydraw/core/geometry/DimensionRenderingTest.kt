package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DimensionRenderingTest {
    private val aligned = Shape.Dimension(DimensionKind.ALIGNED, listOf(Point3(), Point3(1.0)), Point3(0.0, 0.5))

    @Test
    fun `measured labels use every display unit and configured decimal precision`() {
        val inch = aligned.copy(anchors = listOf(Point3(), Point3(0.0254)))
        val expected = mapOf(
            LengthUnit.MILLIMETRES to "25.400 MM",
            LengthUnit.CENTIMETRES to "2.540 CM",
            LengthUnit.METRES to "0.025 M",
            LengthUnit.INCHES to "1.000 IN",
            LengthUnit.FEET to "0.083 FT",
        )
        for ((unit, text) in expected) {
            assertEquals(text, formatDimensionLabel(inch, ProjectSettings(lengthUnit = unit, decimalPlaces = 3)))
        }
        assertEquals("1000 MM", formatDimensionLabel(aligned, ProjectSettings(decimalPlaces = 0)))
    }

    @Test
    fun `dimensions measure transformed world anchors including XYZ rotation and nonuniform size`() {
        val diagonal = aligned.copy(anchors = listOf(Point3(), Point3(3.0, 4.0)))
        val transform = Transform3(Point3(1e9, -1e9, 10.0), Rotation3(PI / 2, PI / 3, PI / 4), Scale3(2.0, 3.0, 4.0))
        assertEquals("13.416 M", formatDimensionLabel(diagonal, ProjectSettings(lengthUnit = LengthUnit.METRES, decimalPlaces = 3), transform))
        val radial = diagonal.copy(kind = DimensionKind.RADIAL)
        assertEquals("R 13.416 M", formatDimensionLabel(radial, ProjectSettings(lengthUnit = LengthUnit.METRES, decimalPlaces = 3), transform))
    }

    @Test
    fun `angular measurements update after nonuniform scaling and format degrees or radians`() {
        val angular = Shape.Dimension(DimensionKind.ANGULAR,
            listOf(Point3(), Point3(1.0), Point3(1.0, 1.0)), Point3(1.0, 0.5))
        assertEquals("45.00 DEG", formatDimensionLabel(angular, ProjectSettings()))
        val scale = Transform3(scale = Scale3(2.0, 1.0, 1.0))
        assertEquals("26.57 DEG", formatDimensionLabel(angular, ProjectSettings(), scale))
        assertEquals("0.46 RAD", formatDimensionLabel(angular, ProjectSettings(angleFormat = AngleFormat.RADIANS), scale))
        val rotated = scale.copy(rotation = Rotation3(1.1, 0.7, 0.0))
        assertEquals("26.57 DEG", formatDimensionLabel(angular, ProjectSettings(), rotated))
    }

    @Test
    fun `aligned dimensions render extensions line inward arrows and numeric lettering`() {
        val scene = scene(aligned)
        assertTrue(scene.segments.any { it.a == Point3() && it.b == Point3(0.0, 0.5) })
        assertTrue(scene.segments.any { it.a == Point3(1.0) && it.b == Point3(1.0, 0.5) })
        assertTrue(scene.segments.any { it.a == Point3(0.0, 0.5) && it.b == Point3(1.0, 0.5) })
        assertTrue(scene.segments.count { it.a == Point3(0.0, 0.5) && it.b.x > 0.0 } >= 3)
        assertTrue(scene.segments.count { it.a == Point3(1.0, 0.5) && it.b.x < 1.0 } >= 2)
        assertTrue(scene.segments.size > 20)
        assertTrue(scene.bounds.max.y > 0.5)
    }

    @Test
    fun `angular dimensions use picked radius with arrows and readable lettering`() {
        val shape = Shape.Dimension(DimensionKind.ANGULAR,
            listOf(Point3(), Point3(2.0), Point3(0.0, 2.0)), Point3(1.2, 1.6))
        val scene = scene(shape)
        assertTrue(scene.segments.any { it.a == Point3(2.0) && it.b.y > 0.0 && it.b.x < 2.0 })
        assertTrue(scene.segments.size > 40)
        assertTrue(scene.segments.any { it.a.x > 1.0 && it.a.y > 1.6 && it.a.z == 0.0 })
    }

    @Test
    fun `radial dimensions render centre radius elbow extension and R lettering`() {
        val shape = Shape.Dimension(DimensionKind.RADIAL, listOf(Point3(), Point3(1.0)), Point3(0.5, 0.5))
        val scene = scene(shape)
        assertTrue(scene.segments.any { it.a == Point3() && it.b == Point3(1.0) })
        assertTrue(scene.segments.any { it.a == Point3(1.0) && it.b == Point3(1.5, 0.5) })
        assertTrue(scene.segments.count { it.a == Point3(1.0) && it.b.x < 1.0 } >= 2)
        assertTrue(scene.bounds.max.x > 1.5)
        assertTrue(scene.segments.size > 20)
    }

    @Test
    fun `leader arrow tip is the first picked point and text starts at the last`() {
        val leader = Shape.Leader(listOf(Point3(), Point3(1.0, 1.0), Point3(2.0, 1.0)), "A")
        val scene = scene(leader)
        assertEquals(3, scene.segments.count { it.a == Point3() })
        assertTrue(scene.bounds.max.x > 2.0)
        assertTrue(scene.bounds.max.y > 1.0)
    }

    private fun scene(shape: Shape): DrawingScene {
        val project = Project(ProjectId("p"), "Drawing", 0, 0)
        val layer = Layer(LayerId("l"), project.id, "Drawing")
        return buildDrawingScene(ProjectDocument(project, layers = listOf(layer), elements = listOf(
            Element(ElementId("e"), project.id, layer.id, ElementBody.DrawingShape(shape)),
        )))
    }
}
