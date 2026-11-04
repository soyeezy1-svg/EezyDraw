package com.eezydraw.core.model

import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlinx.serialization.Serializable

/** Referenced points are host-local; free points are local to the annotation element. */
@Serializable
data class MeasurementAnchor(
    val point: Point3,
    val elementId: ElementId? = null,
    /** Last known project point permits a readable fallback for incomplete external data. */
    val fallbackPoint: Point3 = point,
    /** Fixed grid/free references stay in project coordinates when only their label is moved. */
    val projectFixed: Boolean = false,
)

/** Optional metadata keeps older, unassociated dimensions backward compatible. */
@Serializable
data class MeasurementData(
    val name: String = "Measurement",
    val anchors: List<MeasurementAnchor>,
    val showCoordinates: Boolean = false,
    val showHeightAboveFloor: Boolean = false,
    /** Null follows the source object's current level, then the nearest floor below it. */
    val floorLevelId: LevelId? = null,
) {
    init {
        require(name.isNotBlank() && name.length <= 120 && name.none { it.isISOControl() })
        require(anchors.size == 2)
    }
}

data class MeasurementSummary(
    val elementId: ElementId,
    val name: String,
    val start: Point3,
    val end: Point3,
    val labelPoint: Point3,
    val distance: Double,
    val horizontalDistance: Double,
    val azimuthRadians: Double,
    val elevationRadians: Double,
    val heightAboveFloorMetres: Double?,
    val showCoordinates: Boolean,
    val sourceElementId: ElementId?,
    val referenceElementId: ElementId?,
)

fun measurementShape(element: Element): Shape.Dimension? =
    ((element.body as? ElementBody.DrawingShape)?.shape as? Shape.Dimension)?.takeIf { it.measurement != null }

fun isMeasurement(element: Element): Boolean = measurementShape(element) != null

fun measurementElements(document: ProjectDocument): List<Element> = document.elements.filter(::isMeasurement)

fun resolveMeasurement(document: ProjectDocument, element: Element): MeasurementSummary? =
    resolveMeasurement(document, element, document.elements.associateBy { it.id })

/** The caller compiling a large drawing can reuse its immutable ID map. */
fun resolveMeasurement(
    document: ProjectDocument,
    element: Element,
    elements: Map<ElementId, Element>,
): MeasurementSummary? {
    val shape = measurementShape(element) ?: return null
    val data = shape.measurement ?: return null
    fun resolve(anchor: MeasurementAnchor): Point3 = anchor.elementId?.let { id ->
        elements[id]?.let { measurementToWorld(it.transform, anchor.point) } ?: anchor.fallbackPoint
    } ?: if (anchor.projectFixed) anchor.point else measurementToWorld(element.transform, anchor.point)
    val start = resolve(data.anchors[0])
    val end = resolve(data.anchors[1])
    val dx = end.x - start.x; val dy = end.y - start.y; val dz = end.z - start.z
    val horizontal = hypot(dx, dy)
    val distance = hypot(horizontal, dz)
    val midpoint = measurementMidpoint(shape.anchors[0], shape.anchors[1])
    val label = measurementToWorld(element.transform, Point3(midpoint.x + shape.labelOffset.x,
        midpoint.y + shape.labelOffset.y, midpoint.z + shape.labelOffset.z))
    val source = data.anchors[0].elementId?.let(elements::get)
    val level = (data.floorLevelId ?: source?.levelId ?: element.levelId)?.let { id -> document.levels.find { it.id == id } }
        ?: document.levels.filter { it.elevation <= start.z + 1e-9 }.maxByOrNull { it.elevation }
    return MeasurementSummary(
        element.id, data.name, start, end, label, distance, horizontal,
        if (horizontal == 0.0) 0.0 else atan2(dy, dx),
        if (distance == 0.0) 0.0 else atan2(dz, horizontal),
        if (data.showHeightAboveFloor) start.z - (level?.elevation ?: 0.0) else null,
        data.showCoordinates, data.anchors[0].elementId, data.anchors[1].elementId,
    )
}

/** Geometry, editing and exports use the same metre/radian values and display conversion. */
fun measurementText(
    summary: MeasurementSummary,
    settings: ProjectSettings,
    detailed: Boolean = settings.showDetailedMeasurements,
): List<String> {
    val (factor, suffix) = when (settings.lengthUnit) {
        LengthUnit.MILLIMETRES -> 1000.0 to "MM"
        LengthUnit.CENTIMETRES -> 100.0 to "CM"
        LengthUnit.METRES -> 1.0 to "M"
        LengthUnit.INCHES -> 1.0 / 0.0254 to "IN"
        LengthUnit.FEET -> 1.0 / 0.3048 to "FT"
    }
    fun number(value: Double) = String.format(Locale.ROOT, "%.${settings.decimalPlaces}f", value)
    fun length(value: Double) = "${number(value * factor)} $suffix"
    fun angle(value: Double) = if (settings.angleFormat == AngleFormat.RADIANS) "${number(value)} RAD"
        else "${number(value * 180.0 / PI)} DEG"
    return buildList {
        add("${summary.name}: DIST ${length(summary.distance)}")
        if (!detailed) return@buildList
        add("PLAN ${length(summary.horizontalDistance)}  AZ ${angle(summary.azimuthRadians)}  EL ${angle(summary.elevationRadians)}")
        summary.heightAboveFloorMetres?.let { add("AFFL ${length(it)}") }
        // The global detail switch also applies to measurements saved while it was off.
        // Keep the legacy metadata flag readable, without requiring every annotation to be edited.
        add("A X ${length(summary.start.x)} Y ${length(summary.start.y)} Z ${length(summary.start.z)}")
        add("B X ${length(summary.end.x)} Y ${length(summary.end.y)} Z ${length(summary.end.z)}")
    }
}

fun measurementMidpoint(a: Point3, b: Point3) = Point3(a.x + (b.x - a.x) / 2,
    a.y + (b.y - a.y) / 2, a.z + (b.z - a.z) / 2)

/** Intrinsic XYZ (Rx * Ry * Rz), with scale before rotation. */
fun measurementToWorld(transform: Transform3, point: Point3): Point3 {
    val matrix = measurementRotation(transform.rotation)
    val x = point.x * transform.scale.x; val y = point.y * transform.scale.y; val z = point.z * transform.scale.z
    return Point3(transform.translation.x + matrix[0] * x + matrix[1] * y + matrix[2] * z,
        transform.translation.y + matrix[3] * x + matrix[4] * y + matrix[5] * z,
        transform.translation.z + matrix[6] * x + matrix[7] * y + matrix[8] * z)
}

fun measurementToLocal(transform: Transform3, point: Point3): Point3 {
    val matrix = measurementRotation(transform.rotation)
    val x = point.x - transform.translation.x; val y = point.y - transform.translation.y; val z = point.z - transform.translation.z
    return Point3((matrix[0] * x + matrix[3] * y + matrix[6] * z) / transform.scale.x,
        (matrix[1] * x + matrix[4] * y + matrix[7] * z) / transform.scale.y,
        (matrix[2] * x + matrix[5] * y + matrix[8] * z) / transform.scale.z)
}

private fun measurementRotation(rotation: Rotation3): DoubleArray {
    val cx = cos(rotation.x); val sx = sin(rotation.x); val cy = cos(rotation.y); val sy = sin(rotation.y)
    val cz = cos(rotation.z); val sz = sin(rotation.z)
    return doubleArrayOf(cy * cz, -cy * sz, sy,
        cx * sz + sx * sy * cz, cx * cz - sx * sy * sz, -sx * cy,
        sx * sz - cx * sy * cz, sx * cz + cx * sy * sz, cx * cy)
}
