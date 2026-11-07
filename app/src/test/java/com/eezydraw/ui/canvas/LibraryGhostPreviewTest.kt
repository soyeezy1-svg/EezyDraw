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
        // A floor item resolves the pointer's elevation to the construction floor.
        assertEquals(Point3(10.0, -3.0, 0.0), scene.segments.single().a)
        assertEquals(Point3(10.2, -2.9, .3), scene.segments.single().b)
        val moved = requireNotNull(buildLibraryPreview(document, item.id, Point3(11.0, -1.0, 2.2)))
        assertEquals(1.0, moved.bounds.min.x - scene.bounds.min.x, 1e-9)
        assertEquals(.2, moved.bounds.max.x - moved.bounds.min.x, 1e-9)
        assertEquals(before, document)
        assertEquals(listOf(existing), document.elements)
    }

    @Test fun builtInWallGhostUsesTheFinishFaceAndDefaultMountingHeight() {
        val wall=Element(ElementId("wall"),projectId,layerId,
            ElementBody.Wall(Point2(0.0,0.0),Point2(5.0,0.0),.2,3.0))
        val source=document.copy(elements=listOf(wall),layers=listOf(Layer(layerId,projectId,"Drawing")))
        val switch=LibraryItemId("eezy.electrical.single-switch")
        val scene=requireNotNull(buildLibraryPreview(source,switch,Point3(2.0,1.0,9.0)))
        assertEquals(.065,scene.bounds.min.y,1e-9)
        assertEquals(.1,scene.bounds.max.y,1e-9)
        assertEquals(1.2,scene.bounds.min.z,1e-9)
        assertEquals(1.286,scene.bounds.max.z,1e-9)
        assertNull(buildLibraryPreview(source.copy(elements=emptyList()),switch,Point3()))
        assertEquals(listOf(wall),source.elements)
        assertTrue(source.libraryItems.none {it.id==switch})
    }

    @Test fun ceilingGhostHangsBelowTheChosenLevelsCeiling() {
        val level=Level(LevelId("upper"),projectId,"Upper",4.0,.2,3.2)
        val source=document.copy(levels=listOf(level),layers=listOf(Layer(layerId,projectId,"Drawing")))
        val scene=requireNotNull(buildLibraryPreview(source,LibraryItemId("eezy.ict.wifi-access-point"),Point3(2.0,2.0),level.id))
        assertEquals(7.0,scene.bounds.max.z,1e-9)
        assertEquals(6.96,scene.bounds.min.z,1e-9)
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
