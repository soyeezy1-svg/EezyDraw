package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshGeometryTest {
    private val projectId = ProjectId("mesh-project")
    private val layerId = LayerId("drawing")
    private val mesh = Shape.Mesh(listOf(Point3(), Point3(2.0), Point3(2.0, 3.0, 1.0), Point3(0.0, 3.0, 1.0)),
        listOf(0, 1, 2, 0, 2, 3))

    private fun element(transform: Transform3 = Transform3()) = Element(ElementId("mesh"), projectId, layerId,
        ElementBody.DrawingShape(mesh), transform = transform)

    private fun document(element: Element) = ProjectDocument(Project(projectId, "Mesh", 0, 0),
        layers = listOf(Layer(layerId, projectId, "Drawing")), elements = listOf(element))

    @Test fun `indexed mesh draws shared edges once and fills its actual inclined surface`() {
        val document = document(element())
        val wire = buildDrawingScene(document)
        val surface = buildSurfaceScene(document)
        assertEquals(5, wire.segments.size)
        assertEquals(2, surface.triangleCount)
        assertEquals(wire.bounds, surface.bounds)
        assertEquals(Point3(), surface.bounds.min)
        assertEquals(Point3(2.0, 3.0, 1.0), surface.bounds.max)
        surface.chunks.forEach { chunk ->
            for (offset in chunk.vertices.indices step 10) {
                val point = vertex(chunk, offset)
                assertEquals("Faces follow the sloping mesh instead of a flat extrusion", point.y / 3, point.z, 1e-7)
            }
        }
    }

    @Test fun `mesh normals and face winding survive signed nonuniform scale and XYZ rotation`() {
        val transform = Transform3(Point3(7.0, -4.0, 5.0), Rotation3(.3, -.5, .7), Scale3(-2.0, 3.0, .4))
        val document = document(element(transform))
        val wire = buildDrawingScene(document)
        val surface = buildSurfaceScene(document)
        assertPoint(wire.bounds.min, surface.bounds.min)
        assertPoint(wire.bounds.max, surface.bounds.max)
        surface.chunks.forEach { chunk ->
            for (offset in chunk.vertices.indices step 30) {
                val a = vertex(chunk, offset); val b = vertex(chunk, offset + 10); val c = vertex(chunk, offset + 20)
                val cross = cross(minus(b, a), minus(c, a))
                val length = hypot(hypot(cross.x, cross.y), cross.z)
                val dot = (cross.x * chunk.vertices[offset + 3] + cross.y * chunk.vertices[offset + 4] +
                    cross.z * chunk.vertices[offset + 5]) / length
                assertTrue("Reflected mesh normals agree with its rendered winding", dot > .999)
            }
        }
    }

    @Test fun `library meshes use instance dimensions and preserve detail at distant coordinates`() {
        val item = LibraryItem(LibraryItemId("fitting"), "Inclined fitting", Discipline.HVAC, "Fittings",
            Size3(2.0, 3.0, 1.0), geometry = listOf(mesh))
        val origin = Point3(1e9, -1e9, 1e9)
        val component = element(Transform3(translation = origin)).copy(
            body = ElementBody.Component(item.id, Size3(.002, .003, .001)))
        val document = document(component).copy(libraryItems = listOf(item))
        val wire = buildDrawingScene(document)
        val surface = buildSurfaceScene(document)
        assertEquals(2, surface.triangleCount)
        assertPoint(wire.bounds.min, surface.bounds.min)
        assertPoint(wire.bounds.max, surface.bounds.max)
        assertEquals(.002, surface.bounds.max.x - surface.bounds.min.x, 1e-7)
        assertEquals(.003, surface.bounds.max.y - surface.bounds.min.y, 1e-7)
        assertEquals(.001, surface.bounds.max.z - surface.bounds.min.z, 1e-7)
        surface.chunks.forEach { chunk ->
            for (offset in chunk.vertices.indices step 10) {
                assertTrue(kotlin.math.abs(chunk.vertices[offset]) < .01f)
                assertTrue(kotlin.math.abs(chunk.vertices[offset + 1]) < .01f)
                assertTrue(kotlin.math.abs(chunk.vertices[offset + 2]) < .01f)
            }
        }
    }

    private fun vertex(chunk: SurfaceChunk, offset: Int) = Point3(chunk.origin.x + chunk.vertices[offset],
        chunk.origin.y + chunk.vertices[offset + 1], chunk.origin.z + chunk.vertices[offset + 2])
    private fun minus(a: Point3, b: Point3) = Point3(a.x - b.x, a.y - b.y, a.z - b.z)
    private fun cross(a: Point3, b: Point3) = Point3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
    private fun assertPoint(expected: Point3, actual: Point3) {
        assertEquals(expected.x, actual.x, 1e-12)
        assertEquals(expected.y, actual.y, 1e-12)
        assertEquals(expected.z, actual.z, 1e-12)
    }
}
