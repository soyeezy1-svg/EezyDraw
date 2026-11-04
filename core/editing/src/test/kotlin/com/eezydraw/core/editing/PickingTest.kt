package com.eezydraw.core.editing

import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.ViewPreset
import com.eezydraw.core.geometry.buildDrawingScene
import com.eezydraw.core.model.*
import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PickingTest {
    private val projectId = ProjectId("project")
    private val layerId = LayerId("drawing")
    private fun item(id: String, shape: Shape, transform: Transform3 = Transform3(), level: String? = null) = Element(
        ElementId(id), projectId, layerId, ElementBody.DrawingShape(shape), level?.let(::LevelId), transform = transform,
    )
    private fun document(vararg elements: Element) = ProjectDocument(
        Project(projectId, "Picking", 0L, 0L),
        layers = listOf(Layer(layerId, projectId, "Drawing")), elements = elements.toList(),
    )
    private fun picker(document: ProjectDocument) = EditorPicker(document, buildDrawingScene(document))
    private fun square(low: Double = 0.0, high: Double = 4.0) = listOf(
        Point2(low, low), Point2(high, low), Point2(high, high), Point2(low, high),
    )

    @Test
    fun `nearest outline beats a surrounding filled body and respects capture tolerance`() {
        val circle = item("circle", Shape.Circle(10.0))
        val line = item("line", Shape.Line(Point3(-2.0), Point3(2.0)))
        val picker = picker(document(circle, line))
        assertEquals(line.id, picker.pickPlan(Point2(0.0, 0.05), 0.1))
        assertEquals(circle.id, picker.pickPlan(Point2(0.0, 3.0), 0.1))
        assertNull(picker.pickPlan(Point2(12.0, 0.0), 0.1))
    }

    @Test
    fun `polygon holes and concave voids are not selectable interiors`() {
        val hole = item("hole", Shape.Polygon(Polygon2(square(), listOf(square(1.0, 3.0)))))
        val holePicker = picker(document(hole))
        assertNull(holePicker.pickPlan(Point2(2.0, 2.0), 0.05))
        assertEquals(hole.id, holePicker.pickPlan(Point2(0.5, 2.0), 0.05))
        val concave = item("L", Shape.Polygon(Polygon2(listOf(Point2(0.0, 0.0), Point2(4.0, 0.0),
            Point2(4.0, 1.0), Point2(1.0, 1.0), Point2(1.0, 4.0), Point2(0.0, 4.0)))))
        assertNull(picker(document(concave)).pickPlan(Point2(3.0, 3.0), 0.05))
    }

    @Test
    fun `closed curves and box centers select through transformed mirrored geometry`() {
        val circle = item("ellipse", Shape.Circle(2.0), Transform3(
            Point3(1e9, -1e9, 5.0), Rotation3(x = PI / 3), Scale3(-2.0, 1.0, 1.0),
        ))
        assertEquals(circle.id, picker(document(circle)).pickPlan(Point2(1e9, -1e9), 0.01))
        val box = item("box", Shape.Box(Size3(4.0, 4.0, 3.0)), Transform3(translation = Point3(10.0, 20.0, 2.0)))
        assertEquals(box.id, picker(document(box)).pickPlan(Point2(12.0, 22.0), 0.01))
    }

    @Test
    fun `level filtering includes globals and hidden layers never select while locked layers do`() {
        val first = item("first", Shape.Circle(1.0), level = "first")
        val second = item("second", Shape.Circle(1.0), Transform3(translation = Point3(5.0)), "second")
        val global = item("global", Shape.Circle(1.0), Transform3(translation = Point3(10.0)))
        val source = document(first, second, global)
        val picker = picker(source)
        assertEquals(first.id, picker.pickPlan(Point2(0.0, 0.0), 0.01, "first"))
        assertNull(picker.pickPlan(Point2(5.0, 0.0), 0.01, "first"))
        assertEquals(global.id, picker.pickPlan(Point2(10.0, 0.0), 0.01, "first"))
        assertNull(picker(source.copy(layers = source.layers.map { it.copy(visible = false) })).pickPlan(Point2(0.0, 0.0), 0.1))
        assertEquals(first.id, picker(source.copy(layers = source.layers.map { it.copy(locked = true) })).pickPlan(Point2(0.0, 0.0), 0.1))
    }

    @Test
    fun `orbit interior selects all five primitive bodies in both projections`() {
        val shapes = listOf(Shape.Box(Size3(4.0, 4.0, 3.0)), Shape.Sphere(2.0), Shape.Cylinder(2.0, 3.0),
            Shape.Cone(2.0, height = 3.0), Shape.Pyramid(Polygon2(square(-2.0, 2.0)), Point3(z = 3.0)))
        for (shape in shapes) for (projection in Projection.entries) {
            val item = item("solid", shape)
            val camera = OrbitCamera(target = Point3(0.2, 0.3, 0.8), projection = projection).preset(ViewPreset.TOP)
            val x = if (shape is Shape.Box) 1.3 else 0.2
            val y = if (shape is Shape.Box) 1.7 else 0.3
            val screen = camera.project(Point3(x, y, 0.0), 1000.0, 800.0)!!
            assertEquals("${shape::class.simpleName} $projection", item.id,
                picker(document(item)).pick3D(camera, screen.x, screen.y, 1000.0, 800.0, 0.1))
        }
    }

    @Test
    fun `overlapping orbit outlines and interiors choose nearer depth`() {
        val far = item("far", Shape.Circle(2.0))
        val near = item("near", Shape.Circle(2.0), Transform3(translation = Point3(z = 5.0)))
        val picker = picker(document(far, near))
        val camera = OrbitCamera().preset(ViewPreset.TOP)
        val center = camera.project(Point3(), 1000.0, 800.0)!!
        assertEquals(near.id, picker.pick3D(camera, center.x, center.y, 1000.0, 800.0, 1.0))
        val edge = camera.project(Point3(2.0), 1000.0, 800.0)!!
        assertEquals(near.id, picker.pick3D(camera, edge.x, edge.y, 1000.0, 800.0, 1.0))
    }

    @Test
    fun `open lines do not gain filled rectangular selection areas`() {
        val line = item("line", Shape.Line(Point3(), Point3(10.0, 10.0)))
        assertNull(picker(document(line)).pickPlan(Point2(2.0, 8.0), 0.1))
        val camera = OrbitCamera().preset(ViewPreset.TOP)
        val point = camera.project(Point3(2.0, 8.0), 1000.0, 800.0)!!
        assertNull(picker(document(line)).pick3D(camera, point.x, point.y, 1000.0, 800.0, 2.0))
    }

    @Test
    fun `selection bounds reuse visible transformed records`() {
        val first = item("first", Shape.Circle(2.0), Transform3(translation = Point3(1e9, -1e9, 4.0)))
        val picker = picker(document(first))
        val bounds = picker.boundsFor(setOf(first.id))!!
        assertEquals(1e9 - 2, bounds.min.x, 0.0)
        assertEquals(-1e9 + 2, bounds.max.y, 0.0)
        assertNull(picker.boundsFor(emptySet()))
    }
}
