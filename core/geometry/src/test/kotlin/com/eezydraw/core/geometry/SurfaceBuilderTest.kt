package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceBuilderTest {
    private val projectId = ProjectId("project")
    private val layerId = LayerId("drawing")
    private val project = Project(projectId, "Surfaces", 0, 0)
    private val square = Polygon2(listOf(Point2(0.0, 0.0), Point2(4.0, 0.0), Point2(4.0, 4.0), Point2(0.0, 4.0)))
    private val hole = listOf(Point2(1.0, 1.0), Point2(2.0, 1.0), Point2(2.0, 2.0), Point2(1.0, 2.0))

    private fun element(shape: Shape, id: String = "shape", transform: Transform3 = Transform3()) =
        Element(ElementId(id), projectId, layerId, ElementBody.DrawingShape(shape), transform = transform)

    private fun document(vararg elements: Element) = ProjectDocument(project,
        layers = listOf(Layer(layerId, projectId, "Drawing")), elements = elements.toList())

    @Test fun `all placed primitives provide real triangle volumes and expected proportions`() {
        val base = Polygon2(listOf(Point2(0.0, 0.0), Point2(1.0, 0.0), Point2(1.0, 1.0), Point2(0.0, 1.0)))
        val shapes = listOf(Shape.Box(Size3(1.0, 1.0, 1.0)), Shape.Sphere(.5), Shape.Cylinder(.5, 1.0),
            Shape.Cone(.5, height = 1.0), Shape.Pyramid(base, Point3(.5, .5, 1.0)))
        for (shape in shapes) {
            val scene = buildSurfaceScene(document(element(shape)))
            assertTrue("$shape has triangles", scene.triangleCount >= 6)
            assertEquals(1.0, scene.bounds.max.x - scene.bounds.min.x, 1e-12)
            assertEquals(1.0, scene.bounds.max.y - scene.bounds.min.y, 1e-12)
            assertEquals(1.0, scene.bounds.max.z - scene.bounds.min.z, 1e-12)
            scene.chunks.forEach { chunk ->
                assertEquals("shape", chunk.elementId)
                assertEquals(0, chunk.vertices.size % 30)
                for (i in chunk.vertices.indices step 10) {
                    assertEquals(1.0, hypot(hypot(chunk.vertices[i + 3].toDouble(), chunk.vertices[i + 4].toDouble()),
                        chunk.vertices[i + 5].toDouble()), 1e-6)
                }
            }
        }
    }

    @Test fun `concave polygon caps preserve holes and ignore ring winding`() {
        val outer = listOf(Point2(0.0, 0.0), Point2(4.0, 0.0), Point2(4.0, 4.0),
            Point2(2.0, 4.0), Point2(2.0, 2.0), Point2(0.0, 2.0))
        val opening = hole.map { Point2(it.x + 1.5, it.y - .5) }
        for (reverseOuter in listOf(false, true)) for (reverseHole in listOf(false, true)) {
            val polygon = Polygon2(if (reverseOuter) outer.reversed() else outer,
                listOf(if (reverseHole) opening.reversed() else opening))
            val scene = buildSurfaceScene(document(element(Shape.Polygon(polygon))))
            val triangles = triangles(scene)
            assertEquals(11.0, triangles.sumOf(::area), 1e-6)
            triangles.forEach { triangle ->
                val midpoint = Point3(triangle.sumOf { it.x } / 3, triangle.sumOf { it.y } / 3)
                assertFalse("Hole may not be capped", midpoint.x in 2.5..3.5 && midpoint.y in .5..1.5)
                assertFalse("Concavity may not be capped", midpoint.x < 2.0 && midpoint.y > 2.0)
            }
        }
    }

    @Test fun `room and slab extrusions preserve floor openings and correct cap elevations`() {
        val boundary = square.copy(holes = listOf(hole))
        val room = element(Shape.Box(Size3(1.0, 1.0, 1.0))).copy(body = ElementBody.Room(boundary, ceilingHeight = 2.0))
        val scene = buildSurfaceScene(document(room))
        val caps = triangles(scene).filter { t -> t.all { it.z == t[0].z } }
        assertEquals(15.0, caps.filter { it[0].z == 0.0 }.sumOf(::area), 1e-6)
        assertEquals(0.0, caps.filter { it[0].z == 2.0 }.sumOf(::area), 1e-6)
        assertEquals(55.0, triangles(scene).sumOf(::area), 1e-6)
        val slab = room.copy(body = ElementBody.Slab(boundary, .25))
        val slabScene = buildSurfaceScene(document(slab))
        assertEquals(-.25, slabScene.bounds.min.z, 0.0)
        assertEquals(0.0, slabScene.bounds.max.z, 0.0)
    }

    @Test fun `flat circles ellipses hatches and closed outlines have faces but annotations stay edges`() {
        val closed = Shape.Polyline(listOf(Point3(), Point3(2.0), Point3(2.0, 3.0), Point3(0.0, 3.0)), true)
        for (shape in listOf(Shape.Circle(1.0), Shape.Ellipse(1.0, 2.0), Shape.Hatch(square, "solid", .1), closed)) {
            val scene = buildSurfaceScene(document(element(shape)))
            assertTrue(scene.triangleCount >= 2)
            assertEquals(0.0, scene.bounds.max.z - scene.bounds.min.z, 0.0)
        }
        val open = listOf(Shape.Line(Point3(), Point3(1.0)), Shape.Arc(1.0, 0.0, PI),
            Shape.Text("Text", .25), Shape.Dimension(DimensionKind.ALIGNED, listOf(Point3(), Point3(1.0)), Point3(y = .2)),
            Shape.Leader(listOf(Point3(), Point3(1.0)), "Leader"), Shape.Polyline(listOf(Point3(), Point3(1.0))))
        open.forEach { assertEquals(0, buildSurfaceScene(document(element(it))).triangleCount) }
    }

    @Test fun `system material and layer colours have stable precedence with alpha and visibility`() {
        val materialId = MaterialId("material")
        val systemId = SystemId("service")
        val hiddenLayer = LayerId("hidden")
        val box = Shape.Box(Size3(1.0, 1.0, 1.0))
        val doc = document(element(box, "layer"), element(box, "material").copy(materialId = materialId),
            element(box, "system").copy(materialId = materialId, systemId = systemId),
            element(box, "hidden").copy(layerId = hiddenLayer)).copy(
            layers = listOf(Layer(layerId, projectId, "Layer", 0xFF00FF00), Layer(hiddenLayer, projectId, "Hidden", visible = false)),
            materials = listOf(Material(materialId, "Red", 0xFFFF0000)),
            systems = listOf(BuildingSystem(systemId, projectId, "Blue", Discipline.HVAC, 0x800000FF)),
        )
        val scene = buildSurfaceScene(doc)
        assertEquals(setOf("layer", "material", "system"), scene.chunks.map { it.elementId }.toSet())
        fun colour(id: String) = scene.chunks.first { it.elementId == id }.vertices.sliceArray(6..9)
        assertEquals(listOf(0f, 1f, 0f, 1f), colour("layer").toList())
        assertEquals(listOf(1f, 0f, 0f, 1f), colour("material").toList())
        assertEquals(128f / 255, colour("system")[3], 1e-6f)
        val filtered = buildSurfaceScene(doc, setOf(ElementId("system")))
        assertTrue(filtered.chunks.all { it.elementId == "system" })
        assertTrue(buildSurfaceScene(doc.copy(systems = doc.systems.map { it.copy(visible = false) }), setOf(ElementId("system"))).chunks.isEmpty())
    }

    @Test fun `XYZ rotations signed nonuniform scales and normals match committed geometry`() {
        val transform = Transform3(Point3(4.0, -5.0, 6.0), Rotation3(.4, -.7, .9), Scale3(-2.0, 3.0, .5))
        val scene = buildSurfaceScene(document(element(Shape.Box(Size3(1.0, 1.0, 1.0)), transform = transform)))
        val wire = buildDrawingScene(document(element(Shape.Box(Size3(1.0, 1.0, 1.0)), transform = transform)))
        assertPoint(wire.bounds.min, scene.bounds.min)
        assertPoint(wire.bounds.max, scene.bounds.max)
        scene.chunks.forEach { chunk ->
            for (i in chunk.vertices.indices step 30) {
                val a = vertex(chunk, i); val b = vertex(chunk, i + 10); val c = vertex(chunk, i + 20)
                val normal = cross(subtract(b, a), subtract(c, a))
                val length = hypot(hypot(normal.x, normal.y), normal.z)
                val dot = (normal.x * chunk.vertices[i + 3] + normal.y * chunk.vertices[i + 4] + normal.z * chunk.vertices[i + 5]) / length
                assertTrue("Reflected faces keep outward normal and winding", dot > .999)
            }
        }
    }

    @Test fun `millimetre detail survives surface packing at distant survey coordinates`() {
        val origin = Point3(1e9 + 96.0, -1e9 + 96.0, 1e9)
        val scene = buildSurfaceScene(document(element(Shape.Box(Size3(.001, .002, .003)),
            transform = Transform3(translation = origin))))
        val positions = scene.chunks.flatMap { chunk -> chunk.vertices.indices.step(10).map { vertex(chunk, it) } }
        assertEquals(.001, positions.maxOf { it.x } - positions.minOf { it.x }, 1e-7)
        assertEquals(.002, positions.maxOf { it.y } - positions.minOf { it.y }, 1e-7)
        assertEquals(.003, positions.maxOf { it.z } - positions.minOf { it.z }, 1e-7)
        scene.chunks.forEach { chunk -> for (i in chunk.vertices.indices step 10) {
            assertTrue(abs(chunk.vertices[i]) < .01f)
            assertTrue(abs(chunk.vertices[i + 1]) < .01f)
            assertTrue(abs(chunk.vertices[i + 2]) < .01f)
        } }
    }

    @Test fun `large faces subdivide into bounded relative triangles before Float conversion`() {
        val scene = buildSurfaceScene(document(element(Shape.Box(Size3(1024.0, 512.0, 2.0)))))
        assertTrue(scene.chunks.size > 1)
        assertTrue(scene.triangleCount > 12)
        scene.chunks.forEach { chunk -> for (offset in chunk.vertices.indices step 30) {
            for (axis in 0..2) {
                val points = listOf(chunk.vertices[offset + axis], chunk.vertices[offset + 10 + axis], chunk.vertices[offset + 20 + axis])
                assertTrue(points.max() - points.min() <= 128.0001f)
            }
        } }
    }

    @Test fun `walls use finish references and routes use actual section dimensions`() {
        val wall = element(Shape.Circle(1.0)).copy(body = ElementBody.Wall(Point2(0.0, 0.0), Point2(4.0, 0.0), .2, 3.0,
            WallReference.LEFT_FINISH_FACE))
        val wallScene = buildSurfaceScene(document(wall))
        assertEquals(-.2, wallScene.bounds.min.y, 1e-12)
        assertEquals(0.0, wallScene.bounds.max.y, 1e-12)
        val route = wall.copy(body = ElementBody.Route(RouteKind.PIPE, listOf(Point3(), Point3(2.0)),
            RouteSection.Circular(.2), ElevationSetting(0.0, ElevationReference.PROJECT_DATUM)))
        val scene = buildSurfaceScene(document(route))
        assertEquals(2.0, scene.bounds.max.x - scene.bounds.min.x, 1e-12)
        assertEquals(.2, scene.bounds.max.y - scene.bounds.min.y, 1e-12)
        assertEquals(.2, scene.bounds.max.z - scene.bounds.min.z, 1e-12)
    }

    @Test fun `library surfaces scale instance dimensions and inherit definition material`() {
        val materialId = MaterialId("material")
        val item = LibraryItem(LibraryItemId("library"), "Socket", Discipline.ELECTRICAL, "Electrical",
            Size3(1.0, 1.0, 1.0), geometry = listOf(Shape.Box(Size3(1.0, 1.0, 1.0))), materialId = materialId)
        val component = element(Shape.Circle(1.0)).copy(body = ElementBody.Component(item.id, Size3(.1, .2, .3)))
        val scene = buildSurfaceScene(document(component).copy(libraryItems = listOf(item), materials = listOf(Material(materialId, "Blue", 0xFF0000FF))))
        assertEquals(.1, scene.bounds.max.x, 1e-12)
        assertEquals(.2, scene.bounds.max.y, 1e-12)
        assertEquals(.3, scene.bounds.max.z, 1e-12)
        assertEquals(1f, scene.chunks.first().vertices[8], 0f)
    }

    @Test fun `surface budgets reject oversized face spans and pathological polygon work`() {
        assertThrows(IllegalArgumentException::class.java) {
            buildSurfaceScene(document(element(Shape.Box(Size3(64_000_001.0, 1.0, 1.0)))))
        }
        val outline = List(5000) { i ->
            val a = 2 * PI * i / 5000
            Point2(cos(a), sin(a))
        }
        assertThrows(IllegalArgumentException::class.java) { buildSurfaceScene(document(element(Shape.Polygon(Polygon2(outline))))) }
    }

    @Test fun `level bounds include global faces and render filtering keeps full document definitions`() {
        val first = element(Shape.Box(Size3(1.0, 1.0, 1.0)), "first").copy(levelId = LevelId("first"))
        val global = element(Shape.Box(Size3(1.0, 1.0, 1.0)), "global", Transform3(translation = Point3(-2.0)))
        val other = first.copy(id = ElementId("other"), levelId = LevelId("other"), transform = Transform3(translation = Point3(10.0)))
        val scene = buildSurfaceScene(document(first, global, other))
        assertEquals(-2.0, scene.boundsForLevel("first").min.x, 0.0)
        assertEquals(1.0, scene.boundsForLevel("first").max.x, 0.0)
        assertEquals(11.0, scene.boundsForLevel(null).max.x, 0.0)
        assertEquals(setOf("first"), buildSurfaceScene(document(first, global, other), setOf(first.id)).chunks.map { it.elementId }.toSet())
    }

    private fun triangles(scene: SurfaceScene): List<List<Point3>> = scene.chunks.flatMap { chunk ->
        chunk.vertices.indices.step(30).map { offset -> listOf(vertex(chunk, offset), vertex(chunk, offset + 10), vertex(chunk, offset + 20)) }
    }
    private fun vertex(chunk: SurfaceChunk, offset: Int) = Point3(chunk.origin.x + chunk.vertices[offset],
        chunk.origin.y + chunk.vertices[offset + 1], chunk.origin.z + chunk.vertices[offset + 2])
    private fun subtract(a: Point3, b: Point3) = Point3(a.x - b.x, a.y - b.y, a.z - b.z)
    private fun cross(a: Point3, b: Point3) = Point3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
    private fun area(triangle: List<Point3>): Double {
        val normal = cross(subtract(triangle[1], triangle[0]), subtract(triangle[2], triangle[0]))
        return hypot(hypot(normal.x, normal.y), normal.z) / 2
    }
    private fun assertPoint(expected: Point3, actual: Point3) {
        assertEquals(expected.x, actual.x, 1e-12); assertEquals(expected.y, actual.y, 1e-12); assertEquals(expected.z, actual.z, 1e-12)
    }
}
