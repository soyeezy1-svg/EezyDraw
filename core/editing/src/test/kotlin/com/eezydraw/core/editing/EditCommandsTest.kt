package com.eezydraw.core.editing

import com.eezydraw.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EditCommandsTest {
    private val fixture = EditFixtures

    @Test
    fun `paste assigns new IDs offsets geometry and remaps internal room and host references`() {
        val document = fixture.linkedDocument()
        val command = EditCommands.paste(document, document.elements, Point3(10.0, 20.0, 30.0))
        val pasted = EditHistory().execute(document, command)
        val clones = pasted.elements.drop(document.elements.size)
        assertEquals(document.elements.size, command.addedElementIds.size)
        assertTrue(clones.map { it.id }.none { id -> document.elements.any { it.id == id } })
        val wall = clones.single { it.body is ElementBody.Wall }
        val room = clones.single { it.body is ElementBody.Room }.body as ElementBody.Room
        val component = clones.single { it.body is ElementBody.Component }.body as ElementBody.Component
        val route = clones.single { it.body is ElementBody.Route }.body as ElementBody.Route
        assertEquals(wall.id, room.wallFaces.single().wallId)
        assertEquals(wall.id, component.host?.hostElementId)
        assertEquals(wall.id, route.host?.hostElementId)
        assertTrue(clones.all { it.transform.translation == Point3(10.0, 20.0, 30.0) })
        assertEquals(document.project.id, wall.projectId)
    }

    @Test
    fun `paste subset preserves valid external host and refuses a deleted external host`() {
        val document = fixture.linkedDocument()
        val component = document.elements.single { it.body is ElementBody.Component }
        val pasted = EditHistory().execute(document, EditCommands.paste(document, listOf(component)))
        assertEquals((component.body as ElementBody.Component).host, (pasted.elements.last().body as ElementBody.Component).host)
        val deleted = EditHistory().execute(document, EditCommands.delete(document, setOf(fixture.wallId)))
        assertThrows(IllegalArgumentException::class.java) { EditCommands.paste(deleted, listOf(component)) }
        assertThrows(IllegalArgumentException::class.java) {
            EditCommands.paste(document, listOf(component.copy(projectId = ProjectId("foreign"))))
        }
    }

    @Test
    fun `paste may place copies on a chosen unlocked layer`() {
        val original = fixture.document()
        val destination = Layer(LayerId("destination"), fixture.projectId, "Destination")
        val document = original.copy(layers = original.layers + destination)
        val result = EditHistory().execute(document, EditCommands.paste(document, document.elements, layerId = destination.id))
        assertEquals(destination.id, result.elements.last().layerId)
    }

    @Test
    fun `array creates complete cells with independent links and is undone as one operation`() {
        val document = fixture.linkedDocument()
        val history = EditHistory()
        val command = EditCommands.array(document, document.elements.mapTo(HashSet()) { it.id }, 3, 2, 5.0, 8.0)
        val result = history.execute(document, command)
        assertEquals(document.elements.size * 6, result.elements.size)
        assertEquals(1, history.undoCount)
        val cells = result.elements.chunked(document.elements.size)
        assertEquals(listOf(Point3(), Point3(5.0), Point3(10.0), Point3(y = 8.0), Point3(5.0, 8.0), Point3(10.0, 8.0)),
            cells.map { it.first().transform.translation })
        for (cell in cells) {
            val wall = cell.single { it.body is ElementBody.Wall }
            assertEquals(wall.id, (cell.single { it.body is ElementBody.Component }.body as ElementBody.Component).host?.hostElementId)
        }
        assertEquals(document, history.undo(result))
        assertEquals(result, history.redo(document))
    }

    @Test
    fun `array rejects excessive count including overflow-sized inputs`() {
        val document = fixture.document()
        val ids = document.elements.mapTo(HashSet()) { it.id }
        assertThrows(IllegalArgumentException::class.java) { EditCommands.array(document, ids, 1002, 1, 1.0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { EditCommands.array(document, ids, Int.MAX_VALUE, Int.MAX_VALUE, 1.0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { EditCommands.array(document, ids, 0, 1, 1.0, 1.0) }
        assertTrue(EditCommands.array(document, ids, 1, 1, 1.0, 1.0).isEmpty)
    }

    @Test
    fun `delete repairs room host group and issue links and undo restores everything`() {
        val document = fixture.linkedDocument()
        val history = EditHistory()
        val result = history.execute(document, EditCommands.delete(document, setOf(fixture.wallId)))
        assertEquals(document.elements.size - 1, result.elements.size)
        assertTrue((result.elements.single { it.body is ElementBody.Room }.body as ElementBody.Room).wallFaces.isEmpty())
        assertNull((result.elements.single { it.body is ElementBody.Component }.body as ElementBody.Component).host)
        assertNull((result.elements.single { it.body is ElementBody.Route }.body as ElementBody.Route).host)
        assertTrue(result.groups.single().elementIds.none { it == fixture.wallId })
        assertEquals(setOf(ElementId("room")), result.issues.single().elementIds)
        assertNull(result.issues.single().clash)
        assertEquals(document, history.undo(result))
        assertEquals(result, history.redo(document))
    }

    @Test
    fun `locked layers reject edits and dependent cleanup`() {
        val document = fixture.document().let { it.copy(layers = it.layers.map { layer -> layer.copy(locked = true) }) }
        val selected = document.elements.mapTo(HashSet()) { it.id }
        assertThrows(IllegalArgumentException::class.java) { EditCommands.delete(document, selected) }
        assertThrows(IllegalArgumentException::class.java) { EditCommands.transform(document, selected, EditDelta(translation = Point3(1.0))) }
        assertThrows(IllegalArgumentException::class.java) { EditCommands.replace(document, listOf(document.elements.first().copy(name = "Changed"))) }
        assertThrows(IllegalArgumentException::class.java) { EditCommands.paste(document, document.elements) }
        val linked = fixture.linkedDocument()
        val locked = Layer(LayerId("locked"), fixture.projectId, "Locked", locked = true)
        val dependent = linked.copy(layers = linked.layers + locked, elements = linked.elements.map {
            if (it.body is ElementBody.Component) it.copy(layerId = locked.id) else it
        })
        assertThrows(IllegalArgumentException::class.java) { EditCommands.delete(dependent, setOf(fixture.wallId)) }
    }

    @Test
    fun `replacement enforces object ownership and reference validity`() {
        val document = fixture.document()
        val original = document.elements.first()
        assertThrows(IllegalArgumentException::class.java) { EditCommands.add(document, original) }
        assertThrows(IllegalArgumentException::class.java) { EditCommands.replace(document, listOf(original.copy(projectId = ProjectId("other")))) }
        assertThrows(IllegalArgumentException::class.java) { EditCommands.replace(document, listOf(original.copy(levelId = LevelId("missing")))) }
        assertThrows(IllegalArgumentException::class.java) { EditCommands.delete(document, setOf(ElementId("missing"))) }
        assertNotEquals(original.id, EditCommands.paste(document, listOf(original)).addedElementIds.single())
    }

    @Test
    fun `between includes automatic drawing layer and undo restores an empty document`() {
        val empty = fixture.document().copy(layers = emptyList(), elements = emptyList())
        val layer = fixture.document().layers.single()
        val after = empty.copy(layers = listOf(layer), elements = listOf(fixture.line()))
        val command = EditCommands.between(empty, after, "Create line")
        val history = EditHistory()
        val result = history.execute(empty, command)
        assertEquals(after, result)
        assertEquals(empty, history.undo(result))
        assertEquals(after, history.redo(empty))
    }

    @Test
    fun `between rejects unsupported metadata edits reordering and dangling delete links`() {
        val document = fixture.linkedDocument()
        assertThrows(IllegalArgumentException::class.java) {
            EditCommands.between(document, document.copy(project = document.project.copy(name = "Renamed")))
        }
        assertThrows(IllegalArgumentException::class.java) { EditCommands.between(document, document.copy(elements = document.elements.reversed())) }
        assertThrows(IllegalArgumentException::class.java) {
            EditCommands.between(document, document.copy(elements = document.elements.filterNot { it.id == fixture.wallId }))
        }
    }
}

internal object EditFixtures {
    val projectId = ProjectId("project")
    val layerId = LayerId("drawing")
    val wallId = ElementId("wall")

    fun line(id: String = "line") = Element(
        ElementId(id), projectId, layerId,
        ElementBody.DrawingShape(Shape.Line(Point3(), Point3(3.0, 4.0))),
    )

    fun document() = ProjectDocument(
        Project(projectId, "Drawing", 1L, 1L),
        layers = listOf(Layer(layerId, projectId, "Drawing")),
        elements = listOf(line()),
    )

    fun linkedDocument(): ProjectDocument {
        val host = HostAttachment(wallId, HostMode.SURFACE, Point3())
        val boundary = Polygon2(listOf(Point2(0.0, 0.0), Point2(3.0, 0.0), Point2(3.0, 3.0)))
        val library = LibraryItem(LibraryItemId("socket"), "Socket", Discipline.ELECTRICAL, "Power", Size3(0.1, 0.1, 0.1))
        val elements = listOf(
            line("wall").copy(body = ElementBody.Wall(Point2(0.0, 0.0), Point2(3.0, 0.0), 0.2, 3.0)),
            line("room").copy(body = ElementBody.Room(boundary, wallFaces = listOf(RoomWallFace(0, wallId, WallSide.LEFT)))),
            line("component").copy(body = ElementBody.Component(library.id, library.defaultSize, host = host)),
            line("route").copy(body = ElementBody.Route(
                RouteKind.CONDUIT, listOf(Point3(), Point3(1.0)), RouteSection.Circular(0.1),
                ElevationSetting(1.0, ElevationReference.PROJECT_DATUM), host = host,
            )),
        )
        return document().copy(
            elements = elements, libraryItems = listOf(library),
            groups = listOf(ElementGroup(GroupId("group"), projectId, "Group", elements.mapTo(linkedSetOf()) { it.id })),
            issues = listOf(Issue(
                IssueId("issue"), projectId, "Check", 1L,
                elementIds = setOf(wallId, ElementId("room")),
                clash = Clash(wallId, ElementId("component"), ClashKind.HARD, Point3()),
            )),
        )
    }
}
