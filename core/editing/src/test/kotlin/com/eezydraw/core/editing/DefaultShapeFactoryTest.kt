package com.eezydraw.core.editing

import com.eezydraw.core.geometry.buildDrawingScene
import com.eezydraw.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Physical geometry stays in metres regardless of the project's display unit. */
@RunWith(Parameterized::class)
class DefaultShapeFactoryTest(private val unit: LengthUnit) {
    private val sizedTools = setOf(
        DrawingTool.CIRCLE, DrawingTool.ELLIPSE, DrawingTool.RECTANGLE, DrawingTool.POLYGON,
        DrawingTool.TRIANGLE, DrawingTool.BOX, DrawingTool.SPHERE, DrawingTool.CYLINDER,
        DrawingTool.CONE, DrawingTool.PYRAMID,
    )
    private val solids = setOf(DrawingTool.BOX, DrawingTool.SPHERE, DrawingTool.CYLINDER,
        DrawingTool.CONE, DrawingTool.PYRAMID)

    @Test
    fun `every default shape has a one metre footprint with equal solid proportions`() {
        assertEquals(1.0, ToolOptions().height, 0.0)
        assertTrue(ToolOptions().useDefaultDimensions)
        for (tool in sizedTools) {
            val sketch = createDefaultSketch(tool, Point3(12.0, -7.0, 4.0))
            val document = document(sketch)
            val element = document.elements.single()
            val properties = readProperties(document, element)
            val expectedHeight = if (tool in solids) 1.0 else 0.0
            assertSize(tool.name, Point3(1.0, 1.0, expectedHeight), properties.size)
            val scene = buildDrawingScene(document)
            assertFalse(tool.name, scene.segments.isEmpty())
            assertSize(tool.name, properties.size, extent(scene.bounds))
            assertTrue(tool.name, scene.chunks.all { chunk -> chunk.vertices.all(Float::isFinite) })
            when (val shape = sketch.shape) {
                is Shape.Sphere -> assertEquals(.5, shape.radius, 0.0)
                is Shape.Circle -> assertEquals(.5, shape.radius, 0.0)
                is Shape.Ellipse -> {
                    assertEquals(.5, shape.radiusX, 0.0)
                    assertEquals(.5, shape.radiusY, 0.0)
                }
                is Shape.Box -> assertEquals(Size3(1.0, 1.0, 1.0), shape.size)
                is Shape.Cylinder -> { assertEquals(.5, shape.radius, 0.0); assertEquals(1.0, shape.height, 0.0) }
                is Shape.Cone -> { assertEquals(.5, shape.baseRadius, 0.0); assertEquals(1.0, shape.height, 0.0) }
                else -> Unit
            }
        }
    }

    @Test
    fun `round tools use a physical diameter and sphere ignores unrelated depth and height`() {
        val options = ToolOptions(width = 2.0, depth = .25, height = 7.0)
        for (tool in listOf(DrawingTool.CIRCLE, DrawingTool.SPHERE, DrawingTool.CYLINDER, DrawingTool.CONE)) {
            val sketch = createDefaultSketch(tool, Point3(), options)
            val document = document(sketch)
            val height = when (tool) {
                DrawingTool.SPHERE -> 2.0
                DrawingTool.CYLINDER, DrawingTool.CONE -> 7.0
                else -> 0.0
            }
            assertSize(tool.name, Point3(2.0, 2.0, height), readProperties(document, document.elements.single()).size)
        }
        val ellipse = createDefaultSketch(DrawingTool.ELLIPSE, Point3(), options)
        val document = document(ellipse)
        assertSize("Ellipse", Point3(2.0, .25, 0.0), readProperties(document, document.elements.single()).size)
    }

    @Test
    fun `numeric width depth and height agree with properties and metre-valued scene bounds`() {
        val options = ToolOptions(width = 2.4, depth = 1.8, height = .75)
        for (tool in listOf(DrawingTool.RECTANGLE, DrawingTool.POLYGON, DrawingTool.TRIANGLE,
            DrawingTool.BOX, DrawingTool.PYRAMID)) {
            val document = document(createDefaultSketch(tool, Point3(), options))
            val size = readProperties(document, document.elements.single()).size
            val expected = Point3(options.width, options.depth, if (tool in solids) options.height else 0.0)
            assertSize(tool.name, expected, size)
            // Both cameras consume this same metre-valued scene; no display-unit scaling is baked in.
            assertSize(tool.name, expected, extent(buildDrawingScene(document).bounds))
        }
    }

    @Test
    fun `default physical dimensions do not depend on insertion coordinate or pixel distance`() {
        val options = ToolOptions(width = .001, depth = .002, height = .003)
        val distant = Point3(1e9, -2e9, 300.0)
        for (tool in sizedTools) {
            val local = createDefaultSketch(tool, Point3(), options)
            val survey = createDefaultSketch(tool, distant, options)
            assertEquals(tool.name, local.shape, survey.shape)
            assertEquals(tool.name, distant.x + local.transform.translation.x, survey.transform.translation.x, 0.0)
            assertEquals(tool.name, distant.y + local.transform.translation.y, survey.transform.translation.y, 0.0)
            assertEquals(tool.name, distant.z + local.transform.translation.z, survey.transform.translation.z, 0.0)
            val originDocument = document(local)
            val document = document(survey)
            assertSize(tool.name, readProperties(originDocument, originDocument.elements.single()).size,
                readProperties(document, document.elements.single()).size)
        }
    }

    @Test
    fun `point construction still honors explicit picks instead of substituting defaults`() {
        val box = createSketch(DrawingTool.BOX, listOf(Point3(), Point3(.02, .03)))
        assertEquals(Size3(.02, .03, 1.0), (box.shape as Shape.Box).size)
        val sphere = createSketch(DrawingTool.SPHERE, listOf(Point3(), Point3(.2)))
        assertEquals(.2, (sphere.shape as Shape.Sphere).radius, 0.0)
        val rectangle = createSketch(DrawingTool.RECTANGLE, listOf(Point3(), Point3(.04, .09)))
        val document = document(rectangle)
        assertSize("Rectangle", Point3(.04, .09, 0.0), readProperties(document, document.elements.single()).size)
    }

    @Test
    fun `unsupported and invalid default placements are rejected before creating a shape`() {
        assertEquals(sizedTools, DrawingTool.entries.filterTo(linkedSetOf(), ::supportsDefaultPlacement))
        for (tool in DrawingTool.entries.filterNot(::supportsDefaultPlacement)) {
            assertThrows(tool.name, IllegalArgumentException::class.java) { createDefaultSketch(tool, Point3()) }
        }
        for (tool in sizedTools) for (width in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows("$tool width=$width", IllegalArgumentException::class.java) {
                createDefaultSketch(tool, Point3(), ToolOptions(width = width))
            }
        }
        for (tool in listOf(DrawingTool.RECTANGLE, DrawingTool.ELLIPSE, DrawingTool.POLYGON,
            DrawingTool.TRIANGLE, DrawingTool.BOX, DrawingTool.PYRAMID)) {
            assertThrows("$tool depth", IllegalArgumentException::class.java) {
                createDefaultSketch(tool, Point3(), ToolOptions(depth = 0.0))
            }
        }
        for (tool in listOf(DrawingTool.BOX, DrawingTool.CYLINDER, DrawingTool.CONE, DrawingTool.PYRAMID)) {
            assertThrows("$tool height", IllegalArgumentException::class.java) {
                createDefaultSketch(tool, Point3(), ToolOptions(height = 0.0))
            }
        }
    }

    private fun assertSize(message: String, expected: Point3, actual: Point3) {
        assertEquals("$message width", expected.x, actual.x, 1e-12)
        assertEquals("$message depth", expected.y, actual.y, 1e-12)
        assertEquals("$message height", expected.z, actual.z, 1e-12)
    }

    private fun extent(bounds: Bounds3) = Point3(bounds.max.x - bounds.min.x,
        bounds.max.y - bounds.min.y, bounds.max.z - bounds.min.z)

    private fun document(sketch: SketchGeometry): ProjectDocument {
        val project = Project(ProjectId("p"), "Drawing", 0, 0, settings = ProjectSettings(lengthUnit = unit))
        val layer = Layer(LayerId("l"), project.id, "Drawing")
        return ProjectDocument(project, layers = listOf(layer), elements = listOf(
            Element(ElementId("e"), project.id, layer.id, ElementBody.DrawingShape(sketch.shape), transform = sketch.transform),
        ))
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun units(): List<Array<LengthUnit>> = LengthUnit.entries.map { arrayOf(it) }
    }
}
