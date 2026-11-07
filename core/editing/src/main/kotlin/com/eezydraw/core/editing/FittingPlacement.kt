package com.eezydraw.core.editing

import com.eezydraw.core.geometry.ScenePresentation
import com.eezydraw.core.model.*
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** Stable endpoint identity complements the route reference held by [HostAttachment]. */
object LibraryConnectionParameterKeys {
    const val ENDPOINT = "connectedRouteEndpoint"
    const val PORT = "connectedFittingPort"
}

/** Capture is in physical plan metres so elevated route ends can be picked in a plan view. */
const val FITTING_ENDPOINT_CAPTURE_METRES = 0.5

/** Resolve the visible input marker using the same size and signed transform as the scene. */
fun libraryFittingConnectionPoint(item: LibraryItem, element: Element): Point3? {
    val body = element.body as? ElementBody.Component ?: return null
    val fitting = item.fitting ?: return null
    val port = fitting.ports.find { it.id == "input" } ?: fitting.ports.firstOrNull() ?: return null
    return measurementToWorld(element.transform, Point3(
        port.position.x * body.size.width / (item.geometrySize ?: item.defaultSize).width,
        port.position.y * body.size.depth / (item.geometrySize ?: item.defaultSize).depth,
        port.position.z * body.size.height / (item.geometrySize ?: item.defaultSize).height,
    ))
}

private data class RouteEnd(
    val element: Element,
    val localPoint: Point3,
    val point: Point3,
    val outward: Point3,
    val up: Point3,
    val section: RouteSection,
    val endpoint: String,
    val planDistance: Double,
    val heightDistance: Double,
)

/** Validate the captured host without looking for a new endpoint or changing the candidate. */
internal fun validateFittingPlacement(document: ProjectDocument, placement: LibraryPlacement) {
    val routeId = placement.connectedRouteId ?: return
    val message = "The connected route changed or is hidden. Choose the fitting position again."
    val fitting = requireNotNull(placement.item.fitting) { message }
    val component = requireNotNull(placement.element.body as? ElementBody.Component) { message }
    val attachment = requireNotNull(component.host?.takeIf { it.hostElementId == routeId }) { message }
    val host = requireNotNull(document.elements.find { it.id == routeId }) { message }
    val route = requireNotNull(host.body as? ElementBody.Route) { message }
    require(route.kind == fitting.routeKind && sameProfile(route.section, fitting.section)) { message }
    require(host.levelId == null || host.levelId == placement.element.levelId) { message }
    require(ScenePresentation(document).display(host) != ObjectDisplay.HIDDEN) { message }
    val endpoint = (component.parameters[LibraryConnectionParameterKeys.ENDPOINT] as? ParameterValue.Text)?.value
    require(endpoint == "START" || endpoint == "END") { message }
    val anchor = requireNotNull(placement.connectionPoint) { message }
    val current = requireNotNull(routeEnd(host, route, endpoint == "START", anchor)) { message }
    val input = fitting.ports.first()
    val inward = requireNotNull(unit(negate(input.direction))) { message }
    val helper = if (abs(inward.z) < 0.9) Point3(z = 1.0) else Point3(y = 1.0)
    val inputUp = cross(inward, requireNotNull(unit(cross(helper, inward))))
    require(closePoint(current.localPoint, attachment.localPosition) && closePoint(current.point, anchor)
        && sameSection(current.section, fitting.section)
        && unit(linear(placement.element.transform, input.direction))?.let { closePoint(it, negate(current.outward)) } == true
        && unit(linear(placement.element.transform, inputUp))?.let { closePoint(it, current.up) } == true
        && libraryFittingConnectionPoint(placement.item, placement.element)?.let { closePoint(it, anchor) } == true) { message }
}

/**
 * Attach only at a compatible, visible route end inside the capture radius. Missing targets
 * return null so ordinary library placement still supports an independent fitting/accessory.
 */
internal fun previewFittingRoutePlacement(
    document: ProjectDocument,
    item: LibraryItem,
    point: Point3,
    level: Level?,
    layer: Layer,
    id: ElementId,
): LibraryPlacement? {
    val fitting = item.fitting ?: return null
    val target = nearestRouteEnd(document, item, point, level?.id) ?: return null
    val adapted = adaptedFittingItem(item, target.section) ?: return null
    val adaptedFitting = requireNotNull(adapted.fitting)
    val input = adaptedFitting.ports.find { it.id == "input" } ?: adaptedFitting.ports.first()
    val inward = unit(negate(input.direction)) ?: return null
    val helper = if (abs(inward.z) < 0.9) Point3(z = 1.0) else Point3(y = 1.0)
    val localRight = requireNotNull(unit(cross(helper, inward)))
    val localUp = cross(inward, localRight)
    val worldRight = requireNotNull(unit(cross(target.up, target.outward)))
    // These bases are orthonormal and right handed, including when the host is mirrored.
    val rotationMatrix = basisRotation(inward, localRight, localUp, target.outward, worldRight, target.up)
    val rotatedInput = matrixPoint(rotationMatrix, input.position)
    val transform = Transform3(subtract(target.point, rotatedInput), matrixRotation(rotationMatrix))
    val routeSystem = target.element.systemId?.let { routeId -> document.systems.find { it.id == routeId } }
    val defaults = adapted.defaultParameters()
    val floor = (target.element.levelId?.let { routeLevel -> document.levels.find { it.id == routeLevel } }
        ?: level)?.elevation ?: 0.0
    val height = target.point.z - floor
    val params = defaults + mapOf(
        LibraryParameterKeys.MOUNTING_HEIGHT to ParameterValue.Number(height, Quantity.LENGTH),
        LibraryConnectionParameterKeys.ENDPOINT to ParameterValue.Text(target.endpoint),
        LibraryConnectionParameterKeys.PORT to ParameterValue.Text(input.id),
    ) + if (routeSystem == null) emptyMap() else mapOf(LibraryParameterKeys.SYSTEM to ParameterValue.Text(routeSystem.name))
    val systemName = (params[LibraryParameterKeys.SYSTEM] as? ParameterValue.Text)?.value.orEmpty()
    val system = requireVisiblePlacementSystem(document, systemName)
    val materialName = (params[LibraryParameterKeys.MATERIAL] as? ParameterValue.Text)?.value.orEmpty()
    val colour = (params[LibraryParameterKeys.COLOUR] as? ParameterValue.Colour)?.argb ?: adapted.defaultColourArgb
    val element = Element(
        id = id,
        projectId = document.project.id,
        layerId = layer.id,
        levelId = target.element.levelId ?: level?.id,
        systemId = system?.id,
        materialId = target.element.materialId
            ?: adapted.materialId?.takeIf { materialId -> document.materials.any { it.id == materialId } }
            ?: document.materials.find { it.name.equals(materialName, ignoreCase = true) }?.id,
        name = (params[LibraryParameterKeys.NAME_TAG] as? ParameterValue.Text)?.value ?: adapted.name,
        body = ElementBody.Component(adapted.id, adapted.defaultSize, params,
            HostAttachment(target.element.id, HostMode.SURFACE, target.localPoint)),
        transform = transform,
        properties = mapOf(ELEMENT_COLOUR_KEY to ParameterValue.Colour(colour)),
    )
    check(fitting.routeKind == adaptedFitting.routeKind)
    return LibraryPlacement(element, adapted, height, wallSnapped = false,
        connectionPoint = target.point, connectedRouteId = target.element.id, connectionSection = target.section)
}

private fun nearestRouteEnd(document: ProjectDocument, item: LibraryItem, point: Point3, levelId: LevelId?): RouteEnd? {
    val fitting = requireNotNull(item.fitting)
    val presentation = ScenePresentation(document)
    var best: RouteEnd? = null
    for (element in document.elements) {
        val route = element.body as? ElementBody.Route ?: continue
        if (route.kind != fitting.routeKind || !sameProfile(route.section, fitting.section)) continue
        if (levelId != null && element.levelId != null && element.levelId != levelId) continue
        if (presentation.display(element) == ObjectDisplay.HIDDEN) continue
        for (start in listOf(true, false)) {
            val candidate = routeEnd(element, route, start, point) ?: continue
            if (candidate.planDistance > FITTING_ENDPOINT_CAPTURE_METRES + 1e-10) continue
            val previous = best
            if (previous == null || candidate.planDistance < previous.planDistance - 1e-10
                || abs(candidate.planDistance - previous.planDistance) <= 1e-10 && candidate.heightDistance < previous.heightDistance - 1e-10) {
                best = candidate
            }
        }
    }
    return best
}

private fun routeEnd(element: Element, route: ElementBody.Route, start: Boolean, point: Point3): RouteEnd? {
    val endpointIndex = if (start) 0 else route.path.lastIndex
    val endpoint = route.path[endpointIndex]
    var adjacentIndex = endpointIndex + if (start) 1 else -1
    while (adjacentIndex in route.path.indices && route.path[adjacentIndex] == endpoint) adjacentIndex += if (start) 1 else -1
    if (adjacentIndex !in route.path.indices) return null
    val adjacent = route.path[adjacentIndex]
    // Match SurfaceBuilder's section frame, which uses the path's forward tangent.
    val forward = unit(if (start) subtract(adjacent, endpoint) else subtract(endpoint, adjacent)) ?: return null
    val helper = if (abs(forward.z) < 0.9) Point3(z = 1.0) else Point3(y = 1.0)
    val right = requireNotNull(unit(cross(helper, forward)))
    val up = cross(forward, right)
    val transformedAxis = linear(element.transform, forward)
    val transformedRight = linear(element.transform, right)
    val transformedUp = linear(element.transform, up)
    val axisLength = magnitude(transformedAxis)
    val rightLength = magnitude(transformedRight)
    val upLength = magnitude(transformedUp)
    if (minOf(axisLength, rightLength, upLength) < 1e-12) return null
    // A diagonal route under nonuniform scale can become a skew section. A rigid
    // fitting cannot match that plane, so retain independent-placement behavior.
    if (!orthogonal(transformedAxis, transformedRight) || !orthogonal(transformedAxis, transformedUp)
        || !orthogonal(transformedRight, transformedUp)) return null
    val section = when (val source = route.section) {
        is RouteSection.Rectangular -> RouteSection.Rectangular(source.width * rightLength, source.height * upLength)
        is RouteSection.Circular -> {
            if (abs(rightLength - upLength) > maxOf(rightLength, upLength) * 1e-8) return null
            RouteSection.Circular(source.diameter * rightLength)
        }
    }
    val worldPoint = measurementToWorld(element.transform, endpoint)
    return RouteEnd(element, endpoint, worldPoint,
        times(transformedAxis, (if (start) -1.0 else 1.0) / axisLength),
        times(transformedUp, 1.0 / upLength), section, if (start) "START" else "END",
        hypot(point.x - worldPoint.x, point.y - worldPoint.y), abs(point.z - worldPoint.z))
}

private fun closePoint(a: Point3, b: Point3): Boolean =
    abs(a.x - b.x) <= 1e-7 && abs(a.y - b.y) <= 1e-7 && abs(a.z - b.z) <= 1e-7

private fun sameSection(a: RouteSection, b: RouteSection): Boolean = when (a) {
    is RouteSection.Circular -> b is RouteSection.Circular && closeLength(a.diameter, b.diameter)
    is RouteSection.Rectangular -> b is RouteSection.Rectangular && closeLength(a.width, b.width) && closeLength(a.height, b.height)
}

private fun closeLength(a: Double, b: Double): Boolean = abs(a - b) <= maxOf(a, b) * 1e-8

private fun sameProfile(a: RouteSection, b: RouteSection): Boolean = when (a) {
    is RouteSection.Circular -> b is RouteSection.Circular
    is RouteSection.Rectangular -> b is RouteSection.Rectangular
}

private fun linear(transform: Transform3, vector: Point3): Point3 =
    measurementToWorld(transform.copy(translation = Point3()), vector)

private fun orthogonal(a: Point3, b: Point3): Boolean = abs(dot(a, b)) <= magnitude(a) * magnitude(b) * 1e-8
private fun dot(a: Point3, b: Point3): Double = a.x * b.x + a.y * b.y + a.z * b.z
private fun magnitude(a: Point3): Double = sqrt(dot(a, a))
private fun unit(a: Point3): Point3? = magnitude(a).takeIf { it > 1e-12 }?.let { times(a, 1.0 / it) }
private fun times(a: Point3, k: Double) = Point3(a.x * k, a.y * k, a.z * k)
private fun negate(a: Point3) = times(a, -1.0)
private fun subtract(a: Point3, b: Point3) = Point3(a.x - b.x, a.y - b.y, a.z - b.z)
private fun cross(a: Point3, b: Point3) = Point3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)

internal fun basisRotation(a: Point3, b: Point3, c: Point3, x: Point3, y: Point3, z: Point3): DoubleArray = doubleArrayOf(
    x.x * a.x + y.x * b.x + z.x * c.x, x.x * a.y + y.x * b.y + z.x * c.y, x.x * a.z + y.x * b.z + z.x * c.z,
    x.y * a.x + y.y * b.x + z.y * c.x, x.y * a.y + y.y * b.y + z.y * c.y, x.y * a.z + y.y * b.z + z.y * c.z,
    x.z * a.x + y.z * b.x + z.z * c.x, x.z * a.y + y.z * b.y + z.z * c.y, x.z * a.z + y.z * b.z + z.z * c.z,
)

private fun matrixPoint(m: DoubleArray, p: Point3) = Point3(
    m[0] * p.x + m[1] * p.y + m[2] * p.z,
    m[3] * p.x + m[4] * p.y + m[5] * p.z,
    m[6] * p.x + m[7] * p.y + m[8] * p.z,
)

/** Same intrinsic XYZ decomposition as editing transforms, including the vertical case. */
internal fun matrixRotation(m: DoubleArray): Rotation3 {
    val sy = m[2].coerceIn(-1.0, 1.0)
    val cy = hypot(m[0], m[1])
    val y = atan2(sy, cy)
    return if (cy > 1e-10) Rotation3(atan2(-m[5], m[8]), y, atan2(-m[1], m[0]))
        else Rotation3(atan2(if (sy >= 0.0) m[3] else -m[3], m[4]), y, 0.0)
}
