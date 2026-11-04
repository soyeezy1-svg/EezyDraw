package com.eezydraw.core.editing

import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.ViewPreset
import com.eezydraw.core.geometry.Viewport2D
import com.eezydraw.core.model.*
import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ElementPropertiesTest {
    private val projectId = ProjectId("project")
    private val layerId = LayerId("drawing")
    private fun element(shape: Shape, transform: Transform3 = Transform3()) = Element(
        ElementId("object"), projectId, layerId, ElementBody.DrawingShape(shape), transform = transform,
    )
    private fun document(element: Element) = ProjectDocument(
        Project(projectId, "Properties", 0L, 0L),
        layers = listOf(Layer(layerId, projectId, "Drawing", visible = false, locked = true)),
        elements = listOf(element),
    )

    @Test
    fun `rotation does not swap intrinsic dimensions and mirrored sizes remain positive`() {
        val item = element(Shape.Box(Size3(2.0, 3.0, 4.0)), Transform3(
            Point3(1e9, -1e9, 5.0), Rotation3(0.3, 0.4, PI / 2), Scale3(-2.0, 3.0, 4.0),
        ))
        val properties = readProperties(document(item), item)
        assertEquals(item.transform.translation, properties.position)
        assertEquals(item.transform.rotation, properties.rotation)
        assertEquals(Point3(4.0, 9.0, 16.0), properties.size)
        val changed = applyProperties(document(item), item, properties.copy(
            position = Point3(1e9 + 0.001, -1e9, 5.0), size = Point3(6.0, 12.0, 20.0),
        ))
        assertEquals(Scale3(-3.0, 4.0, 5.0), changed.transform.scale)
        assertEquals(1e9 + 0.001, changed.transform.translation.x, 0.0)
        assertEquals(item.transform.rotation, changed.transform.rotation)
    }

    @Test
    fun `planar axes retain zero thickness and original signed scale`() {
        val item = element(Shape.Line(Point3(), Point3(2.0)), Transform3(scale = Scale3(1.0, -2.0, 3.0)))
        val document = document(item)
        val properties = readProperties(document, item)
        assertEquals(Point3(2.0, 0.0, 0.0), properties.size)
        val changed = applyProperties(document, item, properties.copy(size = Point3(10.0, 0.0, 0.0)))
        assertEquals(Scale3(5.0, -2.0, 3.0), changed.transform.scale)
        assertThrows(IllegalArgumentException::class.java) {
            applyProperties(document, item, properties.copy(size = Point3(2.0, 1.0, 0.0)))
        }
    }

    @Test
    fun `zero and negative dimensions cannot collapse a solid`() {
        val item = element(Shape.Box(Size3(1.0, 2.0, 3.0)))
        val document = document(item)
        val properties = readProperties(document, item)
        for (size in listOf(Point3(0.0, 2.0, 3.0), Point3(1.0, -2.0, 3.0))) {
            assertThrows(IllegalArgumentException::class.java) { applyProperties(document, item, properties.copy(size = size)) }
        }
    }

    @Test
    fun `microscopic nonzero dimensions remain editable`() {
        val item = element(Shape.Line(Point3(), Point3(1e-12)))
        val changed = applyProperties(document(item), item, ElementProperties(Point3(), Rotation3(), Point3(2e-12, 0.0, 0.0)))
        assertEquals(2.0, changed.transform.scale.x, 0.0)
    }

    @Test
    fun `world bounds union uses transformed geometry rather than transformed origin`() {
        val first = element(Shape.Box(Size3(2.0, 3.0, 4.0)), Transform3(Point3(10.0, 20.0, 30.0), Rotation3(z = PI / 2)))
        val second = element(Shape.Circle(2.0), Transform3(Point3(-10.0, 0.0, 1.0))).copy(id = ElementId("second"))
        val document = document(first).copy(elements = listOf(first, second))
        val bounds = elementWorldBounds(document, setOf(first.id, second.id))
        assertEquals(-12.0, bounds.min.x, 1e-9)
        assertEquals(10.0, bounds.max.x, 1e-9)
        assertEquals(22.0, bounds.max.y, 1e-9)
        assertEquals(34.0, bounds.max.z, 1e-9)
    }

    @Test
    fun `axis drag has metre scale in plan and no perpendicular drift`() {
        val moved = screenAxisDrag(Point3(1.0), Point3(), Point2(100.0, 100.0), Point2(140.0, 20.0),
            Viewport2D(pixelsPerMetre = 20.0), OrbitCamera(), false, 1000.0, 800.0)
        assertEquals(Point3(2.0), moved)
    }

    @Test
    fun `orbit axis drags follow projected world XYZ in perspective and orthographic at survey coordinates`() {
        val pivot = Point3(1e9, -1e9, 1e9)
        for (projection in Projection.entries) {
            val camera = OrbitCamera(target = pivot, projection = projection)
            for (axis in listOf(Point3(1.0), Point3(y = 1.0), Point3(z = 1.0))) {
                val first = camera.project(pivot, 1200.0, 800.0)!!
                val last = camera.project(Point3(pivot.x + axis.x * 2, pivot.y + axis.y * 2, pivot.z + axis.z * 2), 1200.0, 800.0)!!
                val moved = screenAxisDrag(axis, pivot, Point2(first.x, first.y), Point2(last.x, last.y),
                    Viewport2D(), camera, true, 1200.0, 800.0)
                assertEquals(axis.x * 2, moved.x, 5e-7)
                assertEquals(axis.y * 2, moved.y, 5e-7)
                assertEquals(axis.z * 2, moved.z, 5e-7)
            }
        }
    }

    @Test
    fun `view-parallel axis has no unstable displacement`() {
        val moved = screenAxisDrag(Point3(z = 1.0), Point3(), Point2(500.0, 400.0), Point2(500.0, 450.0),
            Viewport2D(), OrbitCamera().preset(ViewPreset.TOP), true, 1000.0, 800.0)
        assertEquals(Point3(), moved)
    }
}
