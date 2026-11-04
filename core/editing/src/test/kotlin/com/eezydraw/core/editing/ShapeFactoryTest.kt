package com.eezydraw.core.editing

import com.eezydraw.core.geometry.buildDrawingScene
import com.eezydraw.core.model.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeFactoryTest {
    private val origin = Point3(12.0, -7.0, 4.0)
    private val right = Point3(16.0, -7.0, 4.0)
    private val corner = Point3(16.0, -4.0, 4.0)
    private val up = Point3(12.0, -4.0, 4.0)

    @Test
    fun `all nineteen drawing tools produce renderable typed geometry on the picked level`() {
        val samples = mapOf(
            DrawingTool.LINE to (listOf(origin, right) to Shape.Line::class),
            DrawingTool.POLYLINE to (listOf(origin, right, corner) to Shape.Polyline::class),
            DrawingTool.ARC to (listOf(origin, right, corner) to Shape.Arc::class),
            DrawingTool.CIRCLE to (listOf(origin, right) to Shape.Circle::class),
            DrawingTool.ELLIPSE to (listOf(origin, corner) to Shape.Ellipse::class),
            DrawingTool.RECTANGLE to (listOf(origin, corner) to Shape.Polygon::class),
            DrawingTool.POLYGON to (listOf(origin, right, corner, up) to Shape.Polygon::class),
            DrawingTool.TRIANGLE to (listOf(origin, right, up) to Shape.Polygon::class),
            DrawingTool.SPLINE to (listOf(origin, right, corner) to Shape.Spline::class),
            DrawingTool.TEXT to (listOf(origin) to Shape.Text::class),
            DrawingTool.LEADER to (listOf(origin, right, corner) to Shape.Leader::class),
            DrawingTool.DIM_ALIGNED to (listOf(origin, right, up) to Shape.Dimension::class),
            DrawingTool.DIM_ANGULAR to (listOf(origin, right, up, corner) to Shape.Dimension::class),
            DrawingTool.DIM_RADIAL to (listOf(origin, right, corner) to Shape.Dimension::class),
            DrawingTool.BOX to (listOf(origin, corner) to Shape.Box::class),
            DrawingTool.SPHERE to (listOf(origin, right) to Shape.Sphere::class),
            DrawingTool.CYLINDER to (listOf(origin, right) to Shape.Cylinder::class),
            DrawingTool.CONE to (listOf(origin, right) to Shape.Cone::class),
            DrawingTool.PYRAMID to (listOf(origin, corner) to Shape.Pyramid::class),
        )
        assertEquals(DrawingTool.entries.size - 1, samples.size)
        for ((tool, input) in samples) {
            val sketch = createSketch(tool, input.first)
            assertEquals(tool.name, input.second, sketch.shape::class)
            val scene = buildDrawingScene(document(sketch))
            assertFalse(tool.name, scene.segments.isEmpty())
            assertTrue(tool.name, scene.chunks.all { chunk -> chunk.vertices.all(Float::isFinite) })
            assertEquals(tool.name, origin.z, sketch.transform.translation.z, 0.0)
        }
    }

    @Test
    fun `point metadata and explicit finish retain every vertex and spline degree`() {
        assertEquals(0, DrawingTool.SELECT.minimumPoints)
        assertEquals(null, DrawingTool.SELECT.autoFinishPoints)
        assertEquals(3, DrawingTool.TRIANGLE.autoFinishPoints)
        assertEquals(null, DrawingTool.POLYGON.autoFinishPoints)
        val points = listOf(origin, right, corner, up)
        val polyline = createSketch(DrawingTool.POLYLINE, points, ToolOptions(closed = true)).shape as Shape.Polyline
        assertTrue(polyline.closed)
        assertEquals(4, polyline.points.size)
        val spline = createSketch(DrawingTool.SPLINE, points).shape as Shape.Spline
        assertEquals(3, spline.degree)
        assertEquals(4, spline.controlPoints.size)
        assertEquals(2, (createSketch(DrawingTool.SPLINE, points.take(3)).shape as Shape.Spline).degree)
    }

    @Test
    fun `millimetre details at billion metre survey coordinates remain local doubles`() {
        val a = Point3(1e9, -1e9, 200.0)
        val b = Point3(1e9 + 0.001, -1e9 + 0.002, 200.0)
        val sketch = createSketch(DrawingTool.LINE, listOf(a, b))
        assertEquals(a, sketch.transform.translation)
        val line = sketch.shape as Shape.Line
        assertEquals(Point3(), line.start)
        assertEquals(b.x - a.x, line.end.x, 0.0)
        assertEquals(b.y - a.y, line.end.y, 0.0)
        assertEquals(line.end.x, (buildDrawingScene(document(sketch)).chunks.single().vertices[7]).toDouble(), 1e-10)
    }

    @Test
    fun `three point arcs choose the directed minor and major sweeps through the middle`() {
        val cases = listOf(
            listOf(Point3(1.0), Point3(sqrt(0.5), sqrt(0.5)), Point3(0.0, 1.0)) to PI / 2,
            listOf(Point3(1.0), Point3(-1.0), Point3(0.0, -1.0)) to PI * 1.5,
            listOf(Point3(1.0), Point3(0.0, -1.0), Point3(0.0, 1.0)) to -PI * 1.5,
        )
        for ((points, expectedSweep) in cases) {
            val sketch = createSketch(DrawingTool.ARC, points)
            val arc = sketch.shape as Shape.Arc
            assertEquals(1.0, arc.radius, 1e-12)
            assertEquals(expectedSweep, arc.sweepRadians, 1e-12)
            assertEquals(0.0, sketch.transform.translation.x, 1e-12)
            assertEquals(0.0, sketch.transform.translation.y, 1e-12)
            val end = arc.startRadians + arc.sweepRadians
            assertEquals(points.last().x, cos(end), 1e-12)
            assertEquals(points.last().y, sin(end), 1e-12)
        }
    }

    @Test
    fun `arc circumcentre remains accurate for millimetres near a billion metre origin`() {
        val a = Point3(1e9 + 0.001, 1e9, 20.0)
        val b = Point3(1e9, 1e9 + 0.001, 20.0)
        val c = Point3(1e9 - 0.001, 1e9, 20.0)
        val sketch = createSketch(DrawingTool.ARC, listOf(a, b, c))
        assertEquals(1e9, sketch.transform.translation.x, 1e-7)
        assertEquals(1e9, sketch.transform.translation.y, 1e-7)
        assertEquals(PI, (sketch.shape as Shape.Arc).sweepRadians, 1e-10)
    }

    @Test
    fun `box reverse base picks normalize origin while pyramid apex stays centred`() {
        val box = createSketch(DrawingTool.BOX, listOf(corner, origin), ToolOptions(height = 5.0))
        assertEquals(origin, box.transform.translation)
        assertEquals(Size3(4.0, 3.0, 5.0), (box.shape as Shape.Box).size)
        val pyramid = createSketch(DrawingTool.PYRAMID, listOf(corner, origin), ToolOptions(height = 2.5)).shape as Shape.Pyramid
        assertEquals(Point3(-2.0, -1.5, 2.5), pyramid.apex)
        assertEquals(Point2(-4.0, -3.0), pyramid.base.outer[2])
    }

    @Test
    fun `dimension label picks become the documented local offsets`() {
        val aligned = createSketch(DrawingTool.DIM_ALIGNED, listOf(origin, right, corner)).shape as Shape.Dimension
        assertEquals(listOf(Point3(), Point3(4.0)), aligned.anchors)
        // A perpendicular to a horizontal segment can contain IEEE -0.0; compare coordinates.
        assertEquals(0.0, aligned.labelOffset.x, 0.0)
        assertEquals(3.0, aligned.labelOffset.y, 0.0)
        assertEquals(0.0, aligned.labelOffset.z, 0.0)
        val angular = createSketch(DrawingTool.DIM_ANGULAR, listOf(origin, right, up, corner)).shape as Shape.Dimension
        assertEquals(listOf(Point3(), Point3(4.0), Point3(0.0, 3.0)), angular.anchors)
        assertEquals(Point3(4.0, 3.0), angular.labelOffset)
        val radial = createSketch(DrawingTool.DIM_RADIAL, listOf(origin, right, corner)).shape as Shape.Dimension
        assertEquals(Point3(0.0, 3.0), radial.labelOffset)
    }

    @Test
    fun `text and leader preserve user text and text has an explicit physical height`() {
        val options = ToolOptions(text = "Supply\nA-01", textHeight = 0.12)
        assertEquals(Shape.Text(options.text, 0.12), createSketch(DrawingTool.TEXT, listOf(origin), options).shape)
        assertEquals(options.text, (createSketch(DrawingTool.LEADER, listOf(origin, right), options).shape as Shape.Leader).text)
    }

    @Test
    fun `incomplete extra duplicate collinear and off-plane picks are rejected atomically`() {
        fun invalid(tool: DrawingTool, points: List<Point3>, options: ToolOptions = ToolOptions()) {
            assertThrows("$tool $points", IllegalArgumentException::class.java) { createSketch(tool, points, options) }
        }
        invalid(DrawingTool.SELECT, emptyList())
        invalid(DrawingTool.LINE, listOf(origin))
        invalid(DrawingTool.LINE, listOf(origin, right, corner))
        invalid(DrawingTool.LINE, listOf(origin, origin))
        invalid(DrawingTool.LINE, listOf(origin, right.copy(z = 5.0)))
        invalid(DrawingTool.ARC, listOf(Point3(), Point3(1.0), Point3(2.0)))
        invalid(DrawingTool.CIRCLE, listOf(origin, origin))
        invalid(DrawingTool.ELLIPSE, listOf(origin, right))
        invalid(DrawingTool.RECTANGLE, listOf(origin, right))
        invalid(DrawingTool.POLYLINE, listOf(origin, right), ToolOptions(closed = true))
        invalid(DrawingTool.POLYGON, listOf(Point3(), Point3(1.0), Point3(2.0)))
        invalid(DrawingTool.TRIANGLE, listOf(origin, right, right))
        invalid(DrawingTool.SPLINE, listOf(origin, right))
        invalid(DrawingTool.DIM_ALIGNED, listOf(origin, right, right))
        invalid(DrawingTool.DIM_ANGULAR, listOf(Point3(), Point3(1.0), Point3(2.0), Point3(1.0, 1.0)))
        invalid(DrawingTool.DIM_RADIAL, listOf(origin, right, right))
        invalid(DrawingTool.BOX, listOf(origin, corner), ToolOptions(height = -1.0))
        invalid(DrawingTool.CYLINDER, listOf(origin, right), ToolOptions(height = Double.NaN))
        invalid(DrawingTool.CONE, listOf(origin, right), ToolOptions(height = 0.0))
        invalid(DrawingTool.TEXT, listOf(origin), ToolOptions(text = "  "))
        invalid(DrawingTool.TEXT, listOf(origin), ToolOptions(textHeight = Double.POSITIVE_INFINITY))
    }

    private fun document(sketch: SketchGeometry): ProjectDocument {
        val project = Project(ProjectId("p"), "Drawing", 0, 0)
        val layer = Layer(LayerId("l"), project.id, "Drawing")
        return ProjectDocument(project, layers = listOf(layer), elements = listOf(
            Element(ElementId("e"), project.id, layer.id, ElementBody.DrawingShape(sketch.shape), transform = sketch.transform),
        ))
    }
}
