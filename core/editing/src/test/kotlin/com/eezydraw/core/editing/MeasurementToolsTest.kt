package com.eezydraw.core.editing

import com.eezydraw.core.geometry.buildDrawingScene
import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test

class MeasurementToolsTest {
    private val owner = ProjectId("project")
    private val layer = LayerId("drawing")
    private val floor = LevelId("level")
    private val libraryId = LibraryItemId("socket-definition")
    private val socket = Element(ElementId("socket"), owner, layer,
        ElementBody.Component(libraryId, Size3(.1, .05, .1)), floor,
        transform = Transform3(Point3(.3, 0.0, 1.2)))
    private val wall = Element(ElementId("wall"), owner, layer,
        ElementBody.Wall(Point2(0.0, 0.0), Point2(2.0, 0.0), .2, 3.0), floor)
    private fun fixture(): ProjectDocument = ProjectDocument(Project(owner, "Position sharing", 0, 0),
        levels = listOf(Level(floor, owner, "Ground", 0.0, .2, 3.0)),
        layers = listOf(Layer(layer, owner, "Drawing")), elements = listOf(socket, wall),
        libraryItems = listOf(LibraryItem(libraryId, "Socket", Discipline.ELECTRICAL, "Sockets", Size3(.1, .05, .1))))
    private fun addMeasurement(document: ProjectDocument = fixture()): Pair<ProjectDocument, Element> {
        val geometry = createMeasurement(document, socket.transform.translation, Point3(), Point3(.15, .4, 1.2),
            socket.id, wall.id, "Socket to wall", showHeightAboveFloor = true)
        val element = Element(ElementId("measurement"), owner, layer, ElementBody.DrawingShape(geometry.shape),
            floor, transform = geometry.transform)
        return document.copy(elements = document.elements + element) to element
    }

    @Test fun freeMeasurementsPreserveThreeDimensionalDistanceAndPickedLabel() {
        val doc = fixture()
        val geometry = createMeasurement(doc, Point3(2.0, 3.0, 4.0), Point3(5.0, 7.0, 16.0), Point3(3.0, 6.0, 10.0))
        val element = Element(ElementId("free"), owner, layer, ElementBody.DrawingShape(geometry.shape), transform = geometry.transform)
        val summary = requireNotNull(resolveMeasurement(doc, element))
        assertEquals(13.0, summary.distance, 1e-12)
        assertEquals(5.0, summary.horizontalDistance, 1e-12)
        assertEquals(Point3(3.0, 6.0, 10.0), summary.labelPoint)
        assertNull(summary.heightAboveFloorMetres)
    }

    @Test fun referenceMeasurementReportsSocketOffsetAndCurrentMountingHeight() {
        val (document, element) = addMeasurement()
        val summary = requireNotNull(resolveMeasurement(document, element))
        assertEquals(.3, summary.horizontalDistance, 1e-12)
        assertEquals(1.2, requireNotNull(summary.heightAboveFloorMetres), 1e-12)
        assertEquals(socket.id, summary.sourceElementId)
        assertEquals(wall.id, summary.referenceElementId)
        assertEquals(socket.transform.translation, measurementPointForElement(document, socket.id, Point3(5.0, 5.0)))
        val corner = measurementPointForElement(document, wall.id, Point3(.01, .08))
        assertTrue(buildDrawingScene(document, setOf(wall.id)).segments.any { it.a == corner || it.b == corner })
    }

    @Test fun numericEditRetainsLinksAndBecomesOneUndoTransaction() {
        val (document, element) = addMeasurement()
        val changed = updateMeasurement(document, element, Point3(.4, 0.0, 1.3), Point3(.1, 0.0), Point3(.2, .6, 1.3),
            "Adjusted socket", false, true)
        val history = EditHistory()
        val edited = history.execute(document, EditCommands.replace(document, listOf(changed)))
        val summary = requireNotNull(resolveMeasurement(edited, changed))
        assertEquals("Adjusted socket", summary.name)
        assertEquals(.3, summary.horizontalDistance, 1e-12)
        assertEquals(1.3, requireNotNull(summary.heightAboveFloorMetres), 1e-12)
        assertEquals(socket.id, summary.sourceElementId)
        assertFalse(summary.showCoordinates)
        assertEquals(document, history.undo(edited))
    }

    @Test fun deletingMovedHostFreezesCurrentAnchorAndUndoRestoresAssociation() {
        val (beforeMove, element) = addMeasurement()
        val movedSocket = socket.copy(transform = socket.transform.copy(translation = Point3(.6, 0.0, 1.5)))
        val document = beforeMove.copy(elements = listOf(movedSocket, wall, element))
        val before = requireNotNull(resolveMeasurement(document, element))
        val history = EditHistory()
        val deleted = history.execute(document, EditCommands.delete(document, setOf(socket.id)))
        val frozen = deleted.elements.single { it.id == element.id }
        val after = requireNotNull(resolveMeasurement(deleted, frozen))
        assertEquals(before.start, after.start)
        assertEquals(before.end, after.end)
        assertEquals(before.labelPoint, after.labelPoint)
        assertNull(after.sourceElementId)
        val movedLabel = frozen.copy(transform = frozen.transform.copy(translation = Point3(3.0, 2.0, 1.5)))
        assertEquals(after.start, requireNotNull(resolveMeasurement(deleted, movedLabel)).start)
        assertEquals(after.end, requireNotNull(resolveMeasurement(deleted, movedLabel)).end)
        assertEquals(document, history.undo(deleted))
    }

    @Test fun gridReferenceRemainsFixedWhenLabelMovesAndNumericEditingRetainsThatRule() {
        val document = fixture()
        val geometry = createMeasurement(document, socket.transform.translation, Point3(0.0, 0.0, 0.0),
            Point3(.15, .4, 1.2), sourceElementId = socket.id)
        val element = Element(ElementId("grid-measurement"), owner, layer, ElementBody.DrawingShape(geometry.shape), floor,
            transform = geometry.transform)
        val moved = element.copy(transform = element.transform.copy(translation = Point3(3.3, 2.0, 1.2)))
        val before = requireNotNull(resolveMeasurement(document, element))
        val after = requireNotNull(resolveMeasurement(document, moved))
        assertEquals(before.start, after.start)
        assertEquals(before.end, after.end)
        assertEquals(before.distance, after.distance, 0.0)
        assertNotEquals(before.labelPoint, after.labelPoint)
        val edited = updateMeasurement(document, moved, after.start, after.end, after.labelPoint, "Renamed grid", true, true)
        assertTrue(requireNotNull(measurementShape(edited)?.measurement).anchors[1].projectFixed)
        assertEquals(after.end, requireNotNull(resolveMeasurement(document, edited)).end)
        val pasted = EditHistory().execute(document.copy(elements = document.elements + element),
            EditCommands.paste(document.copy(elements = document.elements + element), listOf(socket, element), Point3(5.0)))
        val copied = measurementElements(pasted).single { it.id != element.id }
        val copiedSummary = requireNotNull(resolveMeasurement(pasted, copied))
        assertEquals(5.3, copiedSummary.start.x, 1e-12)
        assertEquals(before.end, copiedSummary.end)
    }

    @Test fun pasteAndArrayRemapMeasurementReferencesWithinTheCopiedSet() {
        val (document, element) = addMeasurement()
        val history = EditHistory()
        val offset = Point3(5.0, 2.0, 3.0)
        val pasted = history.execute(document, EditCommands.paste(document, listOf(socket, wall, element), offset))
        val clones = pasted.elements.filter { it.id !in document.elements.map { original -> original.id } }
        val clonedMeasurement = clones.single(::isMeasurement)
        val summary = requireNotNull(resolveMeasurement(pasted, clonedMeasurement))
        assertTrue(summary.sourceElementId in clones.map { it.id })
        assertTrue(summary.referenceElementId in clones.map { it.id })
        assertEquals(.3 + offset.x, summary.start.x, 1e-12)
        assertEquals(offset.z + 1.2, summary.start.z, 1e-12)
        val array = history.execute(document, EditCommands.array(document, setOf(socket.id, wall.id, element.id), 2, 1, 2.0, 0.0))
        assertEquals(2, measurementElements(array).size)
        assertEquals(.3, requireNotNull(resolveMeasurement(array, measurementElements(array).last())).horizontalDistance, 1e-12)
    }

    @Test fun invalidCoincidentPointsDanglingHostsAndLockedDependentCleanupAreRejected() {
        val doc = fixture()
        assertThrows(IllegalArgumentException::class.java) { createMeasurement(doc, Point3(), Point3(), Point3()) }
        assertThrows(IllegalArgumentException::class.java) { createMeasurement(doc, Point3(), Point3(1.0), Point3(), ElementId("missing")) }
        val (measured, element) = addMeasurement()
        val missing = element.copy(body = ElementBody.DrawingShape((element.body as ElementBody.DrawingShape).shape.let {
            val shape = it as Shape.Dimension
            shape.copy(measurement = shape.measurement!!.copy(anchors = listOf(MeasurementAnchor(Point3(), ElementId("missing")), MeasurementAnchor(Point3()))))
        }))
        assertThrows(IllegalArgumentException::class.java) { EditCommands.replace(measured, listOf(missing)) }
        val lockedLayer = Layer(LayerId("locked"), owner, "Locked annotations", locked = true)
        val locked = measured.copy(layers = measured.layers + lockedLayer,
            elements = measured.elements.map { if (it.id == element.id) it.copy(layerId = lockedLayer.id) else it })
        assertThrows(IllegalArgumentException::class.java) { EditCommands.delete(locked, setOf(socket.id)) }
    }
}
