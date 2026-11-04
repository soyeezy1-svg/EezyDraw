package com.eezydraw.core.editing

import com.eezydraw.core.geometry.buildDrawingScene
import com.eezydraw.core.model.*
import kotlin.math.hypot

/** Points and labels arrive in project metres; IDs make the picked object anchors associative. */
fun createMeasurement(
    document: ProjectDocument,
    start: Point3,
    end: Point3,
    label: Point3,
    sourceElementId: ElementId? = null,
    referenceElementId: ElementId? = null,
    name: String = "Measurement",
    showCoordinates: Boolean = true,
    showHeightAboveFloor: Boolean = sourceElementId != null,
    floorLevelId: LevelId? = null,
    fixedFreeAnchors: Boolean = sourceElementId != null || referenceElementId != null,
): SketchGeometry {
    require(name.trim().isNotBlank() && name.trim().length <= 120 && name.none(Char::isISOControl)) {
        "Use a measurement name of 1 to 120 printable characters."
    }
    require(hypot(hypot(end.x - start.x, end.y - start.y), end.z - start.z) >= 1e-9) { "Pick two different measurement points." }
    require(floorLevelId == null || document.levels.any { it.id == floorLevelId }) { "The reference floor no longer exists." }
    val transform = Transform3(translation = start)
    val a = Point3()
    val b = measurementToLocal(transform, end)
    val midpoint = measurementMidpoint(a, b)
    val labelLocal = measurementToLocal(transform, label)
    fun anchor(point: Point3, id: ElementId?): MeasurementAnchor {
        val host = id?.let { key -> requireNotNull(document.elements.find { it.id == key }) { "The reference object no longer exists." } }
        val fixed = host == null && fixedFreeAnchors
        return MeasurementAnchor(if (fixed) point else measurementToLocal(host?.transform ?: transform, point), id, point, fixed)
    }
    return SketchGeometry(Shape.Dimension(DimensionKind.ALIGNED, listOf(a, b),
        Point3(labelLocal.x - midpoint.x, labelLocal.y - midpoint.y, labelLocal.z - midpoint.z),
        MeasurementData(name.trim(), listOf(anchor(start, sourceElementId), anchor(end, referenceElementId)),
            showCoordinates, showHeightAboveFloor, floorLevelId)), transform)
}

/** Numeric editing retains references and changes the same project-space points shown in the label. */
fun updateMeasurement(
    document: ProjectDocument,
    element: Element,
    start: Point3,
    end: Point3,
    label: Point3,
    name: String,
    showCoordinates: Boolean,
    showHeightAboveFloor: Boolean,
): Element {
    val data = requireNotNull(measurementShape(element)?.measurement) { "Select a saved measurement." }
    val geometry = createMeasurement(document, start, end, label, data.anchors[0].elementId,
        data.anchors[1].elementId, name, showCoordinates, showHeightAboveFloor, data.floorLevelId,
        fixedFreeAnchors = data.anchors.any { it.projectFixed } || data.anchors.any { it.elementId != null })
    return element.copy(body = ElementBody.DrawingShape(geometry.shape), transform = geometry.transform, name = name.trim())
}

/** Components use their insertion/mounting point. Other references use the nearest displayed corner. */
fun measurementPointForElement(document: ProjectDocument, id: ElementId, pickedPoint: Point3): Point3 {
    val element = requireNotNull(document.elements.find { it.id == id }) { "The reference object no longer exists." }
    if (element.body is ElementBody.Component) return element.transform.translation
    val scene = buildDrawingScene(document.copy(
        layers = document.layers.map { it.copy(visible = true) }, systems = document.systems.map { it.copy(visible = true) }), setOf(id))
    fun distance(point: Point3) = hypot(hypot(point.x - pickedPoint.x, point.y - pickedPoint.y), point.z - pickedPoint.z)
    return scene.segments.asSequence().flatMap { sequenceOf(it.a, it.b) }.minByOrNull(::distance)
        ?: element.transform.translation
}

/** Freeze current project positions when a host is removed; labels and remaining links stay put. */
internal fun detachMeasurementReferences(document: ProjectDocument, element: Element, deleted: Set<ElementId>): Element {
    val shape = measurementShape(element) ?: return element
    val data = shape.measurement ?: return element
    if (data.anchors.none { it.elementId in deleted }) return element
    val summary = resolveMeasurement(document, element) ?: return element
    val points = listOf(summary.start, summary.end)
    val local = points.map { measurementToLocal(element.transform, it) }
    val midpoint = measurementMidpoint(local[0], local[1])
    val label = measurementToLocal(element.transform, summary.labelPoint)
    val anchors = data.anchors.mapIndexed { index, anchor ->
        if (anchor.elementId in deleted) MeasurementAnchor(points[index], fallbackPoint = points[index], projectFixed = true) else anchor
    }
    return element.copy(body = ElementBody.DrawingShape(shape.copy(anchors = local,
        labelOffset = Point3(label.x - midpoint.x, label.y - midpoint.y, label.z - midpoint.z),
        measurement = data.copy(anchors = anchors))))
}
