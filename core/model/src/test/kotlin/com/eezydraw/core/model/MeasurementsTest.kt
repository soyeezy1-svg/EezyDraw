package com.eezydraw.core.model

import kotlin.math.PI
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MeasurementsTest {
    private val owner = ProjectId("project")
    private val layer = LayerId("drawing")
    private val floor = LevelId("level")
    private val source = Element(ElementId("socket"), owner, layer,
        ElementBody.DrawingShape(Shape.Circle(.05)), floor, transform = Transform3(Point3(.3, 0.0, 1.2)))
    private val dimension = Element(ElementId("dimension"), owner, layer, ElementBody.DrawingShape(
        Shape.Dimension(DimensionKind.ALIGNED, listOf(Point3(), Point3(-.3, 0.0, -1.2)), Point3(0.0, .5),
            MeasurementData("Socket offset", listOf(MeasurementAnchor(Point3(), source.id, source.transform.translation),
                MeasurementAnchor(Point3(-.3, 0.0, -1.2))), showHeightAboveFloor = true))), floor,
        transform = Transform3(source.transform.translation))
    private fun document() = ProjectDocument(Project(owner, "Measurements", 0, 0),
        levels = listOf(Level(floor, owner, "Ground", 0.0, .2, 3.0)),
        layers = listOf(Layer(layer, owner, "Drawing")), elements = listOf(source, dimension))

    @Test fun legacyDimensionsDecodeWithoutMeasurementMetadata() {
        val old = """{"type":"dimension","kind":"ALIGNED","anchors":[{}, {"x":1.0}],"labelOffset":{}}"""
        val shape = Json.decodeFromString<Shape>(old) as Shape.Dimension
        assertNull(shape.measurement)
        assertEquals(1.0, shape.anchors[1].x, 0.0)
    }

    @Test fun oldProjectRecordsDecodeWithNoDimensionOrPositioningConstraints() {
        val oldElement="""{"id":"old","projectId":"project","layerId":"drawing","body":{"type":"shape","shape":{"type":"line","start":{},"end":{"x":1.0}}}}"""
        val element=Json.decodeFromString<Element>(oldElement)
        assertTrue(element.wallDistances.isEmpty())
        assertEquals(FixedEnd.START,Json.decodeFromString<ProjectSettings>("{}").dimensionFixedEnd)
        val anchor=Json.decodeFromString<MeasurementAnchor>("""{"point":{}}""")
        assertNull(anchor.vertexIndex)
        val oldData=Json.decodeFromString<MeasurementData>("""{"anchors":[{"point":{}},{"point":{"x":1.0}}]}""")
        assertNull(oldData.binding)
    }

    @Test fun measurementsAndReferencesRoundTripInTheNativeDocument() {
        val document = document()
        val decoded = Json.decodeFromString<ProjectDocument>(Json.encodeToString(document))
        assertEquals(document, decoded)
        assertEquals(listOf(dimension), measurementElements(decoded))
        assertTrue(isMeasurement(dimension))
        assertFalse(isMeasurement(source))
    }

    @Test fun referenceSummarySeparatesPlanDistanceTrueDistanceAndAffl() {
        val summary = requireNotNull(resolveMeasurement(document(), dimension))
        assertEquals(.3, summary.horizontalDistance, 1e-12)
        assertEquals(1.236931687685298, summary.distance, 1e-12)
        assertEquals(1.2, requireNotNull(summary.heightAboveFloorMetres), 1e-12)
        val text = measurementText(summary, ProjectSettings(showDetailedMeasurements = true))
        assertTrue(text[0].contains("DIST 1236.93 MM"))
        assertTrue(text[1].contains("PLAN 300.00 MM"))
        assertTrue(text.any { it == "AFFL 1200.00 MM" })
        assertTrue(text.any { it.startsWith("A X 300.00 MM") })
    }

    @Test fun anglesAndLengthsUseCurrentSettingsWithoutRescalingPoints() {
        val changed = dimension.copy(body = ElementBody.DrawingShape(Shape.Dimension(DimensionKind.ALIGNED,
            listOf(Point3(), Point3(1.0, 1.0, 1.0)), Point3(),
            MeasurementData(anchors = listOf(MeasurementAnchor(Point3()), MeasurementAnchor(Point3(1.0, 1.0, 1.0)))))))
        val summary = requireNotNull(resolveMeasurement(document(), changed))
        assertEquals(PI / 4, summary.azimuthRadians, 1e-12)
        assertEquals(.6154797086703874, summary.elevationRadians, 1e-12)
        for (unit in LengthUnit.entries) {
            val text = measurementText(summary, ProjectSettings(lengthUnit = unit, angleFormat = AngleFormat.RADIANS,
                showDetailedMeasurements = true))
            assertTrue(text[1].contains("AZ 0.79 RAD"))
            assertEquals(Point3(.3, 0.0, 1.2), summary.start)
        }
    }

    @Test fun referenceTransformRoundTripsSignedXyzAtSurveyCoordinates() {
        val transform = Transform3(Point3(1e9, -1e9, 30.0), Rotation3(.3, .4, .8), Scale3(-2.0, 3.0, -.5))
        val local = Point3(.3, .4, 1.2)
        val restored = measurementToLocal(transform, measurementToWorld(transform, local))
        assertEquals(local.x, restored.x, 1e-7)
        assertEquals(local.y, restored.y, 1e-7)
        assertEquals(local.z, restored.z, 1e-7)
    }

    @Test fun referencedAnchorFollowsHostWhileAnnotationDragMovesOnlyLabelAndFreeAnchor() {
        val first = requireNotNull(resolveMeasurement(document(), dimension))
        val movedSource = source.copy(transform = source.transform.copy(translation = Point3(.5, 0.0, 1.2)))
        val movedDimension = dimension.copy(transform = dimension.transform.copy(translation = Point3(1.3, 1.0, 1.2)))
        val after = requireNotNull(resolveMeasurement(document().copy(elements = listOf(movedSource, movedDimension)), movedDimension))
        assertEquals(movedSource.transform.translation, after.start)
        assertEquals(first.end.x + 1, after.end.x, 1e-12)
        assertEquals(first.labelPoint.x + 1, after.labelPoint.x, 1e-12)
        assertEquals(first.labelPoint.y + 1, after.labelPoint.y, 1e-12)
    }

    @Test fun distanceIsTheOnlyDefaultReadoutEvenForOlderDetailedAnnotations() {
        val shape = measurementShape(dimension)!!
        val legacy = dimension.copy(body = ElementBody.DrawingShape(shape.copy(
            measurement = shape.measurement!!.copy(showCoordinates = true, showHeightAboveFloor = true),
        )))
        val summary = requireNotNull(resolveMeasurement(document(), legacy))
        for (unit in LengthUnit.entries) {
            val text = measurementText(summary, ProjectSettings(lengthUnit = unit))
            assertEquals(1, text.size)
            assertTrue(text.single().contains("DIST"))
            assertFalse(text.single().contains("AZ"))
            assertFalse(text.single().contains("PLAN"))
            assertFalse(text.single().contains("AFFL"))
            assertFalse(text.single().contains("A X"))
        }
    }

    @Test fun enablingDetailsRetroactivelyShowsAnglesAndCoordinatesWithoutEditingSavedMetadata() {
        val summary = requireNotNull(resolveMeasurement(document(), dimension))
        assertFalse(summary.showCoordinates)
        assertEquals(1, measurementText(summary, ProjectSettings()).size)
        val text = measurementText(summary, ProjectSettings(showDetailedMeasurements = true))
        assertTrue(text.any { it.contains("AZ") && it.contains("EL") })
        assertTrue(text.any { it.startsWith("A X") && it.contains(" Y ") && it.contains(" Z ") })
        assertTrue(text.any { it.startsWith("B X") })
        assertTrue(text.any { it.startsWith("AFFL") })
        assertFalse(measurementShape(dimension)!!.measurement!!.showCoordinates)
        assertEquals(1, measurementText(summary, ProjectSettings(showDetailedMeasurements = true), detailed = false).size)
    }
}
