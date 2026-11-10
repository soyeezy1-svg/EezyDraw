package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test

class MeasurementRenderingTest {
    private val owner = ProjectId("project")
    private val layer = LayerId("drawing")
    private val source = Element(ElementId("socket"), owner, layer, ElementBody.DrawingShape(Shape.Circle(.05)),
        transform = Transform3(Point3(.3, 0.0, 1.2)))
    private val measurement = Element(ElementId("measurement"), owner, layer,
        ElementBody.DrawingShape(Shape.Dimension(DimensionKind.ALIGNED, listOf(Point3(), Point3(-.3, 0.0, -1.2)),
            Point3(0.0, .4), MeasurementData("Socket offset", listOf(
                MeasurementAnchor(Point3(), source.id, source.transform.translation),
                MeasurementAnchor(Point3(-.3, 0.0, -1.2))), showHeightAboveFloor = true))),
        transform = Transform3(source.transform.translation))
    private fun document() = ProjectDocument(Project(owner, "Measurements", 0, 0),
        layers = listOf(Layer(layer, owner, "Drawing")), elements = listOf(source, measurement))

    @Test fun subsetRenderingKeepsTheFullDocumentForReferenceResolution() {
        val moved = source.copy(transform = source.transform.copy(translation = Point3(.6, 0.0, 1.2)))
        val document = document().copy(elements = listOf(moved, measurement))
        val scene = buildDrawingScene(document, setOf(measurement.id))
        assertTrue(scene.segments.isNotEmpty())
        assertTrue(scene.segments.all { it.elementId == measurement.id.value })
        assertTrue("The measurement must begin at the live host, not its old fallback", scene.segments.any { it.a == moved.transform.translation })
        assertFalse(scene.segments.any { it.a == source.transform.translation })
    }

    @Test fun measurementVisibilityDoesNotHideOrdinaryGeometryOrDiscardSavedAnnotations() {
        val document = document().let { it.copy(project = it.project.copy(settings = it.project.settings.copy(showMeasurements = false))) }
        val scene = buildDrawingScene(document)
        assertTrue(scene.segments.any { it.elementId == source.id.value })
        assertFalse(scene.segments.any { it.elementId == measurement.id.value })
        assertEquals(1, measurementElements(document).size)
    }

    @Test fun aVerticalDistanceHasVisibleThreeDimensionalArrowsAndLegibleText() {
        val vertical = measurement.copy(body = ElementBody.DrawingShape(Shape.Dimension(DimensionKind.ALIGNED,
            listOf(Point3(), Point3(0.0, 0.0, 1.2)), Point3(.3, .3),
            MeasurementData(anchors = listOf(MeasurementAnchor(Point3()), MeasurementAnchor(Point3(0.0, 0.0, 1.2)))))))
        val scene = buildDrawingScene(document().copy(elements = listOf(vertical)))
        assertTrue(scene.segments.size > 20)
        assertTrue(scene.segments.any { it.a.z != it.b.z })
        assertTrue(scene.bounds.max.x - scene.bounds.min.x > .3)
        assertTrue(scene.chunks.all { it.vertices.all(Float::isFinite) })
    }

    @Test fun linesUseTheSameMaterialThenSystemThenLayerColourOrderAsFaces() {
        val material = Material(MaterialId("metal"), "Metal", 0xFF00FF00)
        val system = BuildingSystem(SystemId("power"), owner, "Power", Discipline.ELECTRICAL, 0xFFFF0000)
        val materialObject = source.copy(materialId = material.id)
        val document = document().copy(materials = listOf(material), systems = listOf(system), elements = listOf(materialObject))
        assertEquals(material.colourArgb, buildDrawingScene(document).segments.first().colorArgb)
        assertEquals(material.colourArgb, buildDrawingScene(document.copy(elements = listOf(materialObject.copy(systemId = system.id))))
            .segments.first().colorArgb)
        assertEquals(system.colourArgb, buildDrawingScene(document.copy(elements = listOf(source.copy(systemId = system.id))))
            .segments.first().colorArgb)
    }

    @Test fun defaultDistanceOnlyRenderingKeepsItsAnchorsWhileDetailedReadoutsAreOptional() {
        val document = document()
        val plain = buildDrawingScene(document, setOf(measurement.id))
        val detailedDocument = document.copy(project = document.project.copy(
            settings = document.project.settings.copy(showDetailedMeasurements = true),
        ))
        val detailed = buildDrawingScene(detailedDocument, setOf(measurement.id))
        assertTrue(plain.segments.isNotEmpty())
        assertTrue("Angles and coordinates must add strokes only when explicitly enabled", detailed.segments.size > plain.segments.size)
        assertEquals(resolveMeasurement(document, measurement), resolveMeasurement(detailedDocument, measurement))
        assertTrue(plain.segments.any { it.a == source.transform.translation })
        assertEquals(document.elements, detailedDocument.elements)
    }
}
