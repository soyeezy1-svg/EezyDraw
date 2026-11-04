package com.eezydraw.ui.canvas

import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test

class LibraryGhostPreviewTest {
    private val projectId = ProjectId("preview")
    private val layerId = LayerId("drawing")
    private val item = LibraryItem(LibraryItemId("socket"), "Socket", Discipline.ELECTRICAL, "Sockets",
        Size3(.2, .1, .3), geometry = listOf(Shape.Line(Point3(), Point3(.2, .1, .3))))
    private val existing = Element(ElementId("existing"), projectId, layerId,
        ElementBody.DrawingShape(Shape.Box(Size3(1.0, 1.0, 1.0))))
    private val document = ProjectDocument(Project(projectId, "Preview", 0, 0),
        layers = listOf(Layer(layerId, projectId, "Drawing", visible = false)),
        libraryItems = listOf(item), elements = listOf(existing))

    @Test fun actualDefinitionFollowsFingerAtItsPhysicalSizeWithoutInsertion() {
        val before = document.copy()
        val at = Point3(10.0, -3.0, 1.2)
        val scene = requireNotNull(buildLibraryPreview(document, item.id, at))
        // Custom geometry has one diagonal, unlike the fallback twelve-edge box.
        assertEquals(1, scene.segments.size)
        assertEquals(at, scene.segments.single().a)
        assertEquals(Point3(10.2, -2.9, 1.5), scene.segments.single().b)
        val moved = requireNotNull(buildLibraryPreview(document, item.id, Point3(11.0, -1.0, 2.2)))
        assertEquals(1.0, moved.bounds.min.x - scene.bounds.min.x, 1e-9)
        assertEquals(.2, moved.bounds.max.x - moved.bounds.min.x, 1e-9)
        assertEquals(before, document)
        assertEquals(listOf(existing), document.elements)
    }

    @Test fun emptyDefinitionUsesTheSameDefaultSizeAsInsertionAndAbsentCursorHidesGhost() {
        val fallback = item.copy(geometry = emptyList())
        val scene = requireNotNull(buildLibraryPreview(document.copy(libraryItems = listOf(fallback)), fallback.id, Point3()))
        assertEquals(12, scene.segments.size)
        assertEquals(.2, scene.bounds.max.x - scene.bounds.min.x, 1e-9)
        assertEquals(.1, scene.bounds.max.y - scene.bounds.min.y, 1e-9)
        assertEquals(.3, scene.bounds.max.z - scene.bounds.min.z, 1e-9)
        assertNull(buildLibraryPreview(document, item.id, null))
        assertNull(buildLibraryPreview(document, LibraryItemId("missing"), Point3()))
    }
}
