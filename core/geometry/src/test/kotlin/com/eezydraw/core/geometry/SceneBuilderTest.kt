package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneBuilderTest {
    private val projectId = ProjectId("project")
    private val layerId = LayerId("drawing")
    private val project = Project(projectId, "Drawing", 0L, 0L)
    private val square = Polygon2(listOf(Point2(0.0, 0.0), Point2(4.0, 0.0), Point2(4.0, 3.0), Point2(0.0, 3.0)))

    private fun element(id: String, body: ElementBody, level: String? = null, transform: Transform3 = Transform3()) =
        Element(ElementId(id), projectId, layerId, body, level?.let(::LevelId), transform = transform)

    private fun drawing(shape: Shape, transform: Transform3 = Transform3()) =
        element("shape", ElementBody.DrawingShape(shape), transform = transform)

    private fun document(vararg elements: Element) = ProjectDocument(
        project,
        layers = listOf(Layer(layerId, projectId, "Drawing")),
        elements = elements.toList(),
    )

    @Test
    fun `millimetres at billion metre coordinates survive relative GPU encoding`() {
        val scene = buildDrawingScene(document(drawing(
            Shape.Line(Point3(), Point3(0.001, 0.002, 0.003)),
            Transform3(translation = Point3(1e9, -1e9, 1e9)),
        )))
        val edge = scene.segments.single()
        val chunk = scene.chunks.single()
        assertEquals(1e9, chunk.origin.x, 0.0)
        assertEquals(edge.b.x - edge.a.x, (chunk.vertices[7] - chunk.vertices[0]).toDouble(), 1e-9)
        assertEquals(edge.b.y - edge.a.y, (chunk.vertices[8] - chunk.vertices[1]).toDouble(), 1e-9)
        assertEquals(edge.b.z - edge.a.z, (chunk.vertices[9] - chunk.vertices[2]).toDouble(), 1e-9)
        assertTrue(chunk.vertices[7] > 0.0009f)
    }

    @Test
    fun `scale intrinsic XYZ rotations and translation compose in documented order`() {
        val scene = buildDrawingScene(document(drawing(
            Shape.Line(Point3(), Point3(1.0, 2.0, 3.0)),
            Transform3(Point3(10.0, 20.0, 30.0), Rotation3(PI / 2, PI / 2, PI / 2), Scale3(2.0, 3.0, 4.0)),
        )))
        assertPoint(Point3(10.0, 20.0, 30.0), scene.segments.single().a)
        assertPoint(Point3(22.0, 14.0, 32.0), scene.segments.single().b)
    }

    @Test
    fun `selected level includes global edges without adding level elevation`() {
        val global = element("global", ElementBody.DrawingShape(Shape.Line(Point3(-2.0), Point3(-1.0))))
        val first = element("first", ElementBody.DrawingShape(Shape.Line(Point3(), Point3(2.0, 1.0))), "first")
        val second = element("second", ElementBody.DrawingShape(Shape.Line(Point3(10.0), Point3(12.0))), "second")
        val scene = buildDrawingScene(document(global, first, second).copy(levels = listOf(
            Level(LevelId("first"), projectId, "First", 100.0, 0.2, 3.0),
            Level(LevelId("second"), projectId, "Second", 200.0, 0.2, 3.0),
        )))
        assertEquals(-2.0, scene.boundsForLevel("first").min.x, 0.0)
        assertEquals(2.0, scene.boundsForLevel("first").max.x, 0.0)
        assertEquals(12.0, scene.boundsForLevel(null).max.x, 0.0)
        assertEquals(0.0, scene.bounds.max.z, 0.0)
        assertEquals(-1.0, scene.boundsForLevel("missing").max.x, 0.0)
    }

    @Test
    fun `hidden layers and systems are excluded and system color wins`() {
        val hiddenLayer = LayerId("hidden")
        val hiddenSystem = SystemId("hidden")
        val visibleSystem = SystemId("visible")
        val line = Shape.Line(Point3(), Point3(1.0))
        val scene = buildDrawingScene(document(
            drawing(line).copy(id = ElementId("layer hidden"), layerId = hiddenLayer),
            drawing(line).copy(id = ElementId("system hidden"), systemId = hiddenSystem),
            drawing(line).copy(id = ElementId("visible"), systemId = visibleSystem),
        ).copy(
            layers = listOf(Layer(layerId, projectId, "Drawing", 0xFF00FF00), Layer(hiddenLayer, projectId, "Hidden", visible = false)),
            systems = listOf(
                BuildingSystem(hiddenSystem, projectId, "Hidden", Discipline.HVAC, 0xFFFF0000, visible = false),
                BuildingSystem(visibleSystem, projectId, "Visible", Discipline.HVAC, 0x80402010),
            ),
        ))
        assertEquals("visible", scene.segments.single().elementId)
        assertEquals(0x80402010L, scene.segments.single().colorArgb)
        assertEquals(64f / 255, scene.chunks.single().vertices[3], 1e-6f)
        assertEquals(128f / 255, scene.chunks.single().vertices[6], 1e-6f)
    }

    @Test
    fun `room boundary and holes extrude into true project space volume`() {
        val hole = listOf(Point2(1.0, 1.0), Point2(2.0, 1.0), Point2(2.0, 2.0), Point2(1.0, 2.0))
        val scene = buildDrawingScene(document(element(
            "room", ElementBody.Room(square.copy(holes = listOf(hole)), ceilingHeight = 3.2),
            transform = Transform3(translation = Point3(0.0, 0.0, 5.0)),
        )))
        assertEquals(24, scene.segments.size)
        assertEquals(5.0, scene.bounds.min.z, 0.0)
        assertEquals(8.2, scene.bounds.max.z, 1e-12)
        assertTrue(scene.segments.any { it.a.x == 1.0 && it.a.y == 1.0 && it.a.z != it.b.z })
    }

    @Test
    fun `wall finish reference and slab underside are real geometry`() {
        val wall = element("wall", ElementBody.Wall(Point2(0.0, 0.0), Point2(4.0, 0.0), 0.2, 3.0, WallReference.LEFT_FINISH_FACE))
        val wallScene = buildDrawingScene(document(wall))
        assertEquals(-0.2, wallScene.bounds.min.y, 1e-12)
        assertEquals(0.0, wallScene.bounds.max.y, 0.0)
        assertEquals(3.0, wallScene.bounds.max.z, 0.0)
        val slabScene = buildDrawingScene(document(element("slab", ElementBody.Slab(square, 0.25))))
        assertEquals(-0.25, slabScene.bounds.min.z, 0.0)
        assertEquals(0.0, slabScene.bounds.max.z, 0.0)
    }

    @Test
    fun `component uses scaled library curves and only falls back for missing geometry`() {
        val id = LibraryItemId("custom")
        val component = element("component", ElementBody.Component(id, Size3(4.0, 6.0, 8.0)))
        val library = LibraryItem(id, "Custom", Discipline.ARCHITECTURE, "Test", Size3(2.0, 2.0, 2.0),
            geometry = listOf(Shape.Line(Point3(), Point3(2.0, 2.0, 2.0))))
        val scene = buildDrawingScene(document(component).copy(libraryItems = listOf(library)))
        assertEquals(1, scene.segments.size)
        assertPoint(Point3(4.0, 6.0, 8.0), scene.segments.single().b)
        val fallback = buildDrawingScene(document(component))
        assertEquals(12, fallback.segments.size)
        assertPoint(Point3(4.0, 6.0, 8.0), fallback.bounds.max)
    }

    @Test
    fun `quadratic spline follows clamped curve rather than control polygon`() {
        val scene = buildDrawingScene(document(drawing(Shape.Spline(
            listOf(Point3(), Point3(1.0, 2.0), Point3(2.0)), degree = 2,
        ))))
        assertEquals(64, scene.segments.size)
        assertPoint(Point3(), scene.segments.first().a)
        assertPoint(Point3(2.0), scene.segments.last().b)
        assertEquals(1.0, scene.bounds.max.y, 1e-12)
    }

    @Test
    fun `all model shape variants produce finite bounded wireframes`() {
        val shapes = listOf(
            Shape.Line(Point3(), Point3(1.0)),
            Shape.Polyline(listOf(Point3(), Point3(1.0), Point3(1.0, 1.0)), true),
            Shape.Arc(2.0, 0.0, -PI), Shape.Circle(2.0), Shape.Ellipse(3.0, 2.0),
            Shape.Polygon(square), Shape.Spline(listOf(Point3(), Point3(1.0, 2.0), Point3(3.0)), 2),
            Shape.Text("EEZY DRAW 123\nΩ", 0.5),
            Shape.Dimension(DimensionKind.ALIGNED, listOf(Point3(), Point3(4.0)), Point3(0.0, 1.0)),
            Shape.Dimension(DimensionKind.ANGULAR, listOf(Point3(), Point3(4.0), Point3(0.0, 4.0)), Point3()),
            Shape.Dimension(DimensionKind.RADIAL, listOf(Point3(), Point3(4.0)), Point3(0.0, 1.0)),
            Shape.Leader(listOf(Point3(), Point3(1.0, 1.0)), "A"),
            Shape.Hatch(square, "diagonal", 0.1), Shape.RevisionCloud(square, 0.2),
            Shape.Box(Size3(1.0, 2.0, 3.0)), Shape.Sphere(2.0), Shape.Cylinder(2.0, 3.0),
            Shape.Cone(2.0, 0.0, 3.0), Shape.Pyramid(square, Point3(2.0, 1.5, 3.0)),
            Shape.Extrusion(square, -3.0), Shape.Revolution(square, PI),
        )
        for (shape in shapes) {
            val scene = buildDrawingScene(document(drawing(shape)))
            assertFalse("Empty ${shape::class.simpleName}", scene.segments.isEmpty())
            assertTrue(scene.chunks.all { it.vertices.size % 14 == 0 && it.vertices.all(Float::isFinite) })
            assertTrue(scene.segments.all { it.a != it.b })
        }
    }

    @Test
    fun `route centreline retains actual three dimensional elevations`() {
        val path = listOf(Point3(1.0, 2.0, 3.0), Point3(4.0, 2.0, 3.0), Point3(4.0, 2.0, 6.0))
        val route = ElementBody.Route(RouteKind.DUCT, path, RouteSection.Rectangular(0.5, 0.3),
            ElevationSetting(3.0, ElevationReference.ABOVE_FINISHED_FLOOR))
        val scene = buildDrawingScene(document(element("route", route)))
        assertEquals(2, scene.segments.size)
        assertPoint(path.last(), scene.segments.last().b)
    }

    @Test
    fun `hundred thousand edges use bounded chunks without whole drawing float coordinates`() {
        val points = List(100_001) { i -> Point3(1e9 + i % 1000 * 0.5, 1e9 + i / 1000 * 0.5) }
        val scene = buildDrawingScene(document(drawing(Shape.Polyline(points))))
        assertEquals(100_000, scene.segments.size)
        assertTrue(scene.chunks.size < 1_000)
        assertTrue(scene.chunks.all { it.vertices.size <= 512 * 14 })
        assertTrue(scene.chunks.all { chunk ->
            (chunk.vertices.indices step 7).all {
                absFloat(chunk.vertices[it]) <= 256f && absFloat(chunk.vertices[it + 1]) <= 256f && absFloat(chunk.vertices[it + 2]) <= 256f
            }
        })
        assertTrue(scene.chunks.sumOf { it.vertices.size / 14 } >= 100_000)
    }

    @Test
    fun `long segments split GPU spans while retaining one exact snap edge`() {
        val scene = buildDrawingScene(document(drawing(Shape.Line(Point3(1e9), Point3(1e9 + 1_000.0)))))
        assertEquals(1, scene.segments.size)
        assertEquals(8, scene.chunks.sumOf { it.vertices.size / 14 })
        assertTrue(scene.chunks.all { it.vertices[7] - it.vertices[0] <= 128f })
    }

    @Test
    fun `oversized and nonfinite transformed geometry fail clearly`() {
        val huge = document(drawing(Shape.Line(Point3(), Point3(1e9))))
        val failure = assertThrows(IllegalArgumentException::class.java) { buildDrawingScene(huge) }
        assertTrue(failure.message.orEmpty().contains("render span limit"))
        assertThrows(IllegalArgumentException::class.java) {
            buildDrawingScene(document(drawing(Shape.Line(Point3(), Point3(1e300)), Transform3(scale = Scale3(1e300, 1.0, 1.0)))))
        }
    }

    @Test
    fun `empty and entirely degenerate documents yield finite empty scenes`() {
        val empty = buildDrawingScene(document(drawing(Shape.Line(Point3(), Point3()))))
        assertTrue(empty.segments.isEmpty())
        assertTrue(empty.chunks.isEmpty())
        assertEquals(Bounds3(Point3(), Point3()), empty.bounds)
    }

    private fun assertPoint(expected: Point3, actual: Point3) {
        assertEquals(expected.x, actual.x, 1e-9)
        assertEquals(expected.y, actual.y, 1e-9)
        assertEquals(expected.z, actual.z, 1e-9)
    }

    private fun absFloat(value: Float) = if (value < 0) -value else value
}
