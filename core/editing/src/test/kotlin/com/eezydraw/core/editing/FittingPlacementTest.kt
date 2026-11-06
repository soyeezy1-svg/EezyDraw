package com.eezydraw.core.editing

import com.eezydraw.core.model.*
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class FittingPlacementTest {
    private val project = ProjectId("fitting-project")
    private val layer = LayerId("components")
    private val routeLayer = LayerId("routes")
    private val level = Level(LevelId("ground"), project, "Ground", 0.0, 0.2, 4.0)
    private val otherLevel = level.copy(id = LevelId("upper"), name = "Upper", elevation = 4.0)

    private fun fitting(kind: RouteKind = RouteKind.CABLE_TRAY, circular: Boolean = false,
        fittingKind: FittingKind = FittingKind.COUPLER): LibraryItem = BuiltInLibrary.items.first { item ->
        item.fitting?.let { it.routeKind == kind && it.kind == fittingKind &&
            (it.section is RouteSection.Circular) == circular } == true
    }

    private fun route(id: String = "route", path: List<Point3> = listOf(Point3(1.0, 2.0, 2.6), Point3(6.0, 2.0, 2.6)),
        kind: RouteKind = RouteKind.CABLE_TRAY, section: RouteSection = RouteSection.Rectangular(0.42, 0.14),
        transform: Transform3 = Transform3()): Element = Element(ElementId(id), project, routeLayer,
        ElementBody.Route(kind, path, section, ElevationSetting(2.6, ElevationReference.ABOVE_FINISHED_FLOOR)),
        levelId = level.id, transform = transform)

    private fun document(vararg routes: Element) = ProjectDocument(Project(project, "Fitting placement", 0, 0),
        levels = listOf(level, otherLevel),
        layers = listOf(Layer(layer, project, "Components"), Layer(routeLayer, project, "Routes")), elements = routes.toList())

    private fun preview(document: ProjectDocument, point: Point3, item: LibraryItem = fitting(), id: String = "fitting") =
        previewLibraryPlacement(document, item, point, level.id, layer, ElementId(id))

    private fun assertPoint(expected: Point3, actual: Point3, tolerance: Double = 1e-8) {
        assertEquals(expected.x, actual.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
        assertEquals(expected.z, actual.z, tolerance)
    }

    private fun assertSection(expected: RouteSection, actual: RouteSection?) {
        when (expected) {
            is RouteSection.Rectangular -> {
                assertTrue(actual is RouteSection.Rectangular)
                actual as RouteSection.Rectangular
                assertEquals(expected.width, actual.width, 1e-9)
                assertEquals(expected.height, actual.height, 1e-9)
            }
            is RouteSection.Circular -> {
                assertTrue(actual is RouteSection.Circular)
                assertEquals(expected.diameter, (actual as RouteSection.Circular).diameter, 1e-9)
            }
        }
    }

    private fun direction(placement: LibraryPlacement): Point3 {
        val port = placement.item.fitting!!.ports.first()
        val origin = measurementToWorld(placement.element.transform, Point3())
        val vector = measurementToWorld(placement.element.transform, port.direction)
        return Point3(vector.x - origin.x, vector.y - origin.y, vector.z - origin.z)
    }

    private fun normalized(a: Point3, b: Point3): Point3 {
        val x = a.x - b.x; val y = a.y - b.y; val z = a.z - b.z
        val length = sqrt(x * x + y * y + z * z)
        return Point3(x / length, y / length, z / length)
    }

    @Test fun `both endpoints mate the input centreline and face away from the incoming route`() {
        val host = route()
        val doc = document(host)
        val body = host.body as ElementBody.Route
        for ((point, endpoint, outwardInput) in listOf(
            Triple(Point3(1.1, 2.05), "START", Point3(x = 1.0)),
            Triple(Point3(6.1, 2.05), "END", Point3(x = -1.0)),
        )) {
            val placement = preview(doc, point)
            val component = placement.element.body as ElementBody.Component
            val expected = if (endpoint == "START") body.path.first() else body.path.last()
            assertEquals(host.id, placement.connectedRouteId)
            assertEquals(host.id, component.host!!.hostElementId)
            assertEquals(ParameterValue.Text(endpoint), component.parameters[LibraryConnectionParameterKeys.ENDPOINT])
            assertEquals(ParameterValue.Text("input"), component.parameters[LibraryConnectionParameterKeys.PORT])
            assertPoint(expected, placement.connectionPoint!!)
            assertPoint(expected, component.host!!.localPosition)
            assertPoint(expected, libraryFittingConnectionPoint(placement.item, placement.element)!!)
            assertPoint(outwardInput, direction(placement))
            assertEquals(body.section, placement.connectionSection)
            assertEquals(body.section, placement.item.fitting!!.ports.first().section)
            assertEquals(2.6, placement.mountingHeight, 1e-9)
            assertFalse(placement.wallSnapped)
        }
    }

    @Test fun `rotated mirrored nonuniform route inherits its exact physical rectangular section`() {
        val transform = Transform3(Point3(1e9, -1e9, 12.0), Rotation3(0.31, -0.42, 0.67), Scale3(-2.0, 3.0, 4.0))
        val host = route(path = listOf(Point3(), Point3(y = 5.0)), section = RouteSection.Rectangular(0.4, 0.2), transform = transform)
        val start = measurementToWorld(transform, Point3())
        val end = measurementToWorld(transform, Point3(y = 5.0))
        val placement = preview(document(host), end)
        assertEquals(host.id, placement.connectedRouteId)
        assertSection(RouteSection.Rectangular(0.8, 0.8), placement.connectionSection)
        assertPoint(end, libraryFittingConnectionPoint(placement.item, placement.element)!!, 3e-7)
        assertPoint(normalized(start, end), direction(placement), 3e-7)
        assertPoint(Point3(y = 5.0), (placement.element.body as ElementBody.Component).host!!.localPosition)
    }

    @Test fun `vertical route uses its rendered cross section plane and exact endpoint tangent`() {
        val transform = Transform3(Point3(4.0, 8.0, 0.0), Rotation3(0.2, -0.3, 0.7), Scale3(-2.0, 3.0, 4.0))
        val host = route(path = listOf(Point3(z = 1.0), Point3(z = 5.0)), section = RouteSection.Rectangular(0.4, 0.2), transform = transform)
        val start = measurementToWorld(transform, Point3(z = 1.0))
        val end = measurementToWorld(transform, Point3(z = 5.0))
        val placement = preview(document(host), end)
        assertEquals(host.id, placement.connectedRouteId)
        val section = placement.connectionSection as RouteSection.Rectangular
        assertEquals(0.8, section.width, 1e-9)
        assertEquals(0.6, section.height, 1e-9)
        assertPoint(end, libraryFittingConnectionPoint(placement.item, placement.element)!!)
        assertPoint(normalized(start, end), direction(placement))
    }

    @Test fun `bent route uses the final nondegenerate segment rather than its first segment`() {
        val host = route(path = listOf(Point3(z = 2.0), Point3(3.0, 0.0, 2.0), Point3(3.0, 3.0, 2.0), Point3(3.0, 3.0, 2.0)))
        val placement = preview(document(host), Point3(3.1, 3.1))
        assertEquals(host.id, placement.connectedRouteId)
        assertPoint(Point3(y = -1.0), direction(placement))
        assertPoint(Point3(3.0, 3.0, 2.0), placement.connectionPoint!!)
    }

    @Test fun `circular routes inherit transverse diameter but oval and skew sections do not connect`() {
        val item = fitting(RouteKind.PIPE, circular = true)
        val host = route(kind = RouteKind.PIPE, section = RouteSection.Circular(0.05),
            transform = Transform3(scale = Scale3(-2.0, 3.0, 3.0)))
        val point = measurementToWorld(host.transform, (host.body as ElementBody.Route).path.last())
        val connected = preview(document(host), point, item)
        assertEquals(host.id, connected.connectedRouteId)
        assertSection(RouteSection.Circular(0.15), connected.connectionSection)
        val oval = host.copy(transform = host.transform.copy(scale = Scale3(2.0, 3.0, 4.0)))
        val ovalPoint = measurementToWorld(oval.transform, (oval.body as ElementBody.Route).path.last())
        assertNull(preview(document(oval), ovalPoint, item).connectedRouteId)
        val skew = route(path = listOf(Point3(1.0, 1.0, 2.0), Point3(5.0, 5.0, 2.0)),
            transform = Transform3(scale = Scale3(2.0, 1.0, 1.0)))
        val skewPoint = measurementToWorld(skew.transform, (skew.body as ElementBody.Route).path.last())
        assertNull(preview(document(skew), skewPoint).connectedRouteId)
    }

    @Test fun `route kind section profile and plan capture distance must all match`() {
        val host = route(kind = RouteKind.CONDUIT, section = RouteSection.Circular(0.05))
        assertNull(preview(document(host), Point3(6.0, 2.0), fitting(RouteKind.PIPE, circular = true)).connectedRouteId)
        val circularDuct = host.copy(body = (host.body as ElementBody.Route).copy(kind = RouteKind.DUCT))
        assertNull(preview(document(circularDuct), Point3(6.0, 2.0), fitting(RouteKind.DUCT)).connectedRouteId)
        assertNull(preview(document(route()), Point3(6.0 + FITTING_ENDPOINT_CAPTURE_METRES + 0.01, 2.0)).connectedRouteId)
        val captured = preview(document(route()), Point3(6.0 + FITTING_ENDPOINT_CAPTURE_METRES, 2.0))
        assertNotNull(captured.connectedRouteId)
        assertEquals(2.6, captured.connectionPoint!!.z, 0.0)
    }

    @Test fun `nearest visible endpoint wins with height breaking equal plan distances`() {
        val first = route("first")
        val closer = route("closer", path = listOf(Point3(1.0, 3.0, 3.0), Point3(6.0, 2.2, 3.0)))
        assertEquals(closer.id, preview(document(first, closer), Point3(6.0, 2.15)).connectedRouteId)
        val above = route("above", path = listOf(Point3(1.0, 2.0, 3.6), Point3(6.0, 2.0, 3.6)))
        assertEquals(above.id, preview(document(first, above), Point3(6.0, 2.0, 3.5)).connectedRouteId)
    }

    @Test fun `hidden objects layers systems levels and other storeys are ineligible even when revealed`() {
        val host = route()
        val visible = document(host)
        val system = BuildingSystem(SystemId("route-system"), project, "Hidden route service", Discipline.ELECTRICAL, 0xFF334455, visible = false)
        val cases = listOf(
            visible.copy(elements = listOf(host.withDisplay(ObjectDisplay.HIDDEN))),
            visible.copy(layers = visible.layers.map { if (it.id == routeLayer) it.copy(visible = false) else it }),
            visible.copy(systems = listOf(system), elements = listOf(host.copy(systemId = system.id))),
            visible.copy(elements = listOf(host.copy(levelId = otherLevel.id))),
        )
        for (candidate in cases) {
            val revealed = candidate.copy(project = candidate.project.copy(settings = candidate.project.settings.copy(showHiddenObjects = true)))
            assertNull(preview(revealed, Point3(6.0, 2.0)).connectedRouteId)
        }
        assertEquals(host.id, preview(document(host.withLock(LockMode.ALL)), Point3(6.0, 2.0)).connectedRouteId)
    }

    @Test fun `hidden selected levels reject every library item before a ghost is created`() {
        val doc = document(route()).let { it.copy(project = it.project.copy(settings =
            it.project.settings.copy(hiddenLevelIds = setOf(level.id)))) }
        val ordinary = LibraryItem(LibraryItemId("floor-item"), "Floor item", Discipline.ARCHITECTURE,
            "Architecture", Size3(0.5, 0.5, 0.5))
        for (item in listOf(fitting(), ordinary)) {
            val failure = assertThrows(IllegalArgumentException::class.java) { preview(doc, Point3(6.0, 2.0), item) }
            assertTrue(failure.message.orEmpty().contains("selected level is hidden"))
        }
    }

    @Test fun `unconnected fitting still has a visible physical input marker and sensible defaults`() {
        val placement = preview(document(), Point3(10.0, 20.0))
        assertNull(placement.connectedRouteId)
        assertNotNull(placement.connectionPoint)
        assertEquals(placement.item.fitting!!.section, placement.connectionSection)
        assertPoint(placement.connectionPoint!!, libraryFittingConnectionPoint(placement.item, placement.element)!!)
        assertEquals(placement.connectionPoint!!.z, placement.mountingHeight, 1e-9)
        assertNull((placement.element.body as ElementBody.Component).host)
    }

    @Test fun `route sized definitions coexist and each connection is one reversible placement`() {
        val first = route("first")
        val second = route("second", path = listOf(Point3(1.0, 5.0, 2.8), Point3(6.0, 5.0, 2.8)),
            section = RouteSection.Rectangular(0.6, 0.22))
        val original = document(first, second)
        val one = preview(original, Point3(6.0, 2.0), id = "one")
        val history = EditHistory()
        val placedOne = history.execute(original, LibraryCommands.place(original, one))
        val two = preview(placedOne, Point3(6.0, 5.0), id = "two")
        val placedBoth = history.execute(placedOne, LibraryCommands.place(placedOne, two))
        assertNotEquals(one.item.id, two.item.id)
        assertEquals(one.item.fitting!!.baseTypeId, two.item.fitting!!.baseTypeId)
        assertEquals(setOf(one.item.id, two.item.id), placedBoth.libraryItems.map { it.id }.toSet())
        assertEquals(2, history.undoCount)
        assertEquals(placedOne, history.undo(placedBoth))
        val undone = history.undo(placedOne)
        assertEquals(original, undone)
        assertEquals(placedOne, history.redo(undone))
        assertEquals(placedBoth, history.redo(placedOne))
    }

    @Test fun `commit consumes resolved connection without retargeting to a new nearer route`() {
        val original = document(route())
        val placement = preview(original, Point3(6.1, 2.0))
        val later = original.copy(elements = original.elements + route("new", path = listOf(Point3(1.0, 2.1, 2.6), Point3(6.1, 2.0, 2.6))))
        val result = LibraryCommands.place(later, placement).preview(later)
        val committed = result.elements.last()
        assertEquals(ElementId("route"), (committed.body as ElementBody.Component).host!!.hostElementId)
        assertEquals(placement.element.transform, committed.transform)
        assertPoint(placement.connectionPoint!!, libraryFittingConnectionPoint(placement.item, committed)!!)
    }

    @Test fun `commit rejects a moved hidden resized or reoriented captured endpoint without resnapping`() {
        val host = route()
        val doc = document(host)
        val placement = preview(doc, Point3(6.0, 2.0))
        val body = host.body as ElementBody.Route
        val replacements = listOf(
            host.copy(transform = Transform3(Point3(x = 1.0))),
            host.withDisplay(ObjectDisplay.HIDDEN),
            host.copy(body = body.copy(kind = RouteKind.TRUNKING)),
            host.copy(body = body.copy(section = RouteSection.Circular(0.1))),
            host.copy(body = body.copy(section = RouteSection.Rectangular(0.5, 0.14))),
            host.copy(body = body.copy(path = listOf(Point3(6.0, 0.0, 2.6), body.path.last()))),
        )
        for (replacement in replacements) {
            val changed = doc.copy(elements = listOf(replacement))
            val failure = assertThrows(IllegalArgumentException::class.java) { LibraryCommands.place(changed, placement) }
            assertTrue(failure.message.orEmpty().contains("connected route changed or is hidden"))
            assertTrue(changed.libraryItems.isEmpty())
        }
    }

    @Test fun `below floor piping preserves its signed centreline height when committed`() {
        val host = route(kind = RouteKind.PIPE, section = RouteSection.Circular(0.1),
            path = listOf(Point3(1.0, 2.0, -0.6), Point3(6.0, 2.0, -0.6)))
        val doc = document(host)
        val placement = preview(doc, Point3(6.0, 2.0), fitting(RouteKind.PIPE, circular = true))
        val result = LibraryCommands.place(doc, placement).preview(doc)
        assertEquals(-0.6, placement.mountingHeight, 1e-9)
        assertEquals(-0.6, readLibraryProperties(result, result.elements.last()).mountingHeight, 1e-9)
        assertPoint(Point3(6.0, 2.0, -0.6), libraryFittingConnectionPoint(placement.item, result.elements.last())!!)
    }
}
