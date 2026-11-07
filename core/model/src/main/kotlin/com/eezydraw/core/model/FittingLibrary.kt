package com.eezydraw.core.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Lightweight, deterministic vector definitions; no thumbnail files or external meshes. */
fun fittingLibraryItems(): List<LibraryItem> = (RouteKind.entries.map { it to nominalSection(it) } +
    (RouteKind.DUCT to RouteSection.Circular(.315))).flatMap { (route, section) ->
    val roundDuct = route == RouteKind.DUCT && section is RouteSection.Circular
    val title = if (roundDuct) "Round duct" else routeTitle(route)
    val routeSlug = if (roundDuct) "duct-round" else route.name.lowercase().replace('_', '-')
    val variants = fittingVariants + if (route == RouteKind.PIPE) pipeVariants else emptyList()
    variants.map { variant ->
        val id = LibraryItemId("eezy.fittings.$routeSlug.${variant.slug}")
        val discipline = when (route) {
            RouteKind.DUCT -> Discipline.HVAC
            RouteKind.PIPE -> Discipline.PLUMBING
            RouteKind.CABLE_BASKET -> Discipline.ICT
            else -> Discipline.ELECTRICAL
        }
        val seed = LibraryItem(
            id = id, name = "$title ${variant.name}", discipline = discipline,
            category = "Fittings / $title", defaultSize = Size3(1.0, 1.0, 1.0),
            builtIn = true, mounting = MountingType.FLOOR,
            symbol = if (section is RouteSection.Circular) LibrarySymbol.CONDUIT else LibrarySymbol.TRAY,
            defaultMaterial = when (route) {
                RouteKind.PIPE -> "Steel"
                RouteKind.CONDUIT -> "PVC"
                else -> "Galvanized steel"
            },
            defaultSystem = when (route) {
                RouteKind.DUCT -> "HVAC"
                RouteKind.PIPE -> "Water supply"
                RouteKind.CABLE_BASKET -> "ICT"
                else -> "Power"
            },
            defaultColourArgb = when (route) {
                RouteKind.DUCT -> 0xFF459E95
                RouteKind.PIPE -> 0xFF377FBD
                else -> 0xFFAB8244
            },
            fitting = FittingDefinition(id, route, variant.kind, section,
                listOf(FittingPort("input", Point3(), Point3(y = -1.0), section)),
                listOf(Point3(), Point3(y = 1.0))),
        )
        buildFitting(seed, section, null)
    }
}

/**
 * Regenerates the complete fitting, including connection points, for an actual route section.
 * Profile families cannot be mixed. Sized variants have deterministic IDs, so a project may carry
 * several sizes of one catalogue type without replacing another instance's reusable definition.
 * Passing the catalogue section restores its original ID. Existing style metadata is retained.
 */
fun adaptedFittingItem(
    item: LibraryItem,
    section: RouteSection,
    outletSection: RouteSection? = null,
): LibraryItem? {
    val fitting = item.fitting ?: return null
    if (!sameProfile(fitting.section, section)) return null
    if (outletSection != null && (!sameProfile(section, outletSection) || fitting.kind != FittingKind.REDUCER)) return null
    return buildFitting(item, section, outletSection)
}

private data class FittingVariant(val kind: FittingKind, val slug: String, val name: String)

private val fittingVariants = listOf(
    FittingVariant(FittingKind.ELBOW_45, "bend-45", "45° bend"),
    FittingVariant(FittingKind.ELBOW_90, "bend-90-horizontal", "90° horizontal bend"),
    FittingVariant(FittingKind.BEND_90_HORIZONTAL_INSIDE, "bend-90-horizontal-inside", "90° horizontal inside bend"),
    FittingVariant(FittingKind.BEND_90_HORIZONTAL_OUTSIDE, "bend-90-horizontal-outside", "90° horizontal outside bend"),
    FittingVariant(FittingKind.BEND_90_VERTICAL_INSIDE, "bend-90-vertical-inside", "90° vertical inside bend"),
    FittingVariant(FittingKind.BEND_90_VERTICAL_OUTSIDE, "bend-90-vertical-outside", "90° vertical outside bend"),
    FittingVariant(FittingKind.TEE, "tee", "tee"),
    FittingVariant(FittingKind.CROSS, "cross", "cross"),
    FittingVariant(FittingKind.REDUCER, "reducer", "reducer"),
    FittingVariant(FittingKind.RISER_UP, "riser-up", "riser up"),
    FittingVariant(FittingKind.RISER_DOWN, "riser-down", "riser down"),
    FittingVariant(FittingKind.OFFSET, "offset", "offset"),
    FittingVariant(FittingKind.COUPLER, "coupler", "coupler"),
    FittingVariant(FittingKind.END_CAP, "end-cap", "end cap"),
    FittingVariant(FittingKind.FLEXIBLE_CONNECTOR, "flexible-connector", "flexible connector"),
    FittingVariant(FittingKind.EXPANSION_JOINT, "expansion-joint", "expansion joint"),
    FittingVariant(FittingKind.SADDLE, "saddle", "saddle"),
    FittingVariant(FittingKind.SUPPORT, "support", "support"),
    FittingVariant(FittingKind.HANGER, "hanger", "hanger"),
    FittingVariant(FittingKind.BRACKET, "bracket", "bracket"),
    FittingVariant(FittingKind.TRAPEZE, "trapeze", "trapeze"),
)

private val pipeVariants = listOf(
    FittingVariant(FittingKind.ELBOW, "elbow", "elbow"),
    FittingVariant(FittingKind.UNION, "union", "union"),
    FittingVariant(FittingKind.FLANGE, "flange", "flange"),
)

private fun nominalSection(route: RouteKind): RouteSection = when (route) {
    RouteKind.TRUNKING -> RouteSection.Rectangular(.1, .05)
    RouteKind.CABLE_TRAY, RouteKind.CABLE_BASKET -> RouteSection.Rectangular(.3, .05)
    RouteKind.CABLE_LADDER -> RouteSection.Rectangular(.45, .1)
    RouteKind.CONDUIT -> RouteSection.Circular(.025)
    RouteKind.DUCT -> RouteSection.Rectangular(.5, .3)
    RouteKind.PIPE -> RouteSection.Circular(.05)
}

private fun routeTitle(route: RouteKind): String = when (route) {
    RouteKind.TRUNKING -> "Trunking"
    RouteKind.CABLE_TRAY -> "Cable tray"
    RouteKind.CABLE_LADDER -> "Cable ladder"
    RouteKind.CABLE_BASKET -> "Cable basket"
    RouteKind.CONDUIT -> "Conduit"
    RouteKind.DUCT -> "Duct"
    RouteKind.PIPE -> "Pipe"
}

private fun sameProfile(first: RouteSection, second: RouteSection): Boolean =
    first is RouteSection.Circular && second is RouteSection.Circular ||
        first is RouteSection.Rectangular && second is RouteSection.Rectangular

private val sectionKeys = setOf(LibraryParameterKeys.SECTION_WIDTH, LibraryParameterKeys.SECTION_HEIGHT,
    LibraryParameterKeys.DIAMETER, LibraryParameterKeys.OUTLET_WIDTH, LibraryParameterKeys.OUTLET_HEIGHT,
    LibraryParameterKeys.OUTLET_DIAMETER)

private fun RouteSection.width(): Double = when (this) {
    is RouteSection.Circular -> diameter
    is RouteSection.Rectangular -> width
}

private fun RouteSection.height(): Double = when (this) {
    is RouteSection.Circular -> diameter
    is RouteSection.Rectangular -> height
}

private fun RouteSection.half(): RouteSection = when (this) {
    is RouteSection.Circular -> RouteSection.Circular(diameter / 2)
    is RouteSection.Rectangular -> RouteSection.Rectangular(width / 2, height / 2)
}

private fun RouteSection.signature(): String = when (this) {
    is RouteSection.Circular -> "c-${diameter.toBits().toULong().toString(16)}"
    is RouteSection.Rectangular -> "r-${width.toBits().toULong().toString(16)}-${height.toBits().toULong().toString(16)}"
}

private fun sectionParameters(section: RouteSection, outlet: RouteSection?): Map<String, ParameterValue> {
    fun length(value: Double) = ParameterValue.Number(value, Quantity.LENGTH)
    val input = when (section) {
        is RouteSection.Circular -> mapOf(LibraryParameterKeys.DIAMETER to length(section.diameter))
        is RouteSection.Rectangular -> mapOf(LibraryParameterKeys.SECTION_WIDTH to length(section.width),
            LibraryParameterKeys.SECTION_HEIGHT to length(section.height))
    }
    return input + when (outlet) {
        is RouteSection.Circular -> mapOf(LibraryParameterKeys.OUTLET_DIAMETER to length(outlet.diameter))
        is RouteSection.Rectangular -> mapOf(LibraryParameterKeys.OUTLET_WIDTH to length(outlet.width),
            LibraryParameterKeys.OUTLET_HEIGHT to length(outlet.height))
        null -> emptyMap()
    }
}

private data class FittingPath(val points: List<Point3>, val sections: List<RouteSection>)
private data class RawPort(val id: String, val position: Point3, val direction: Point3, val section: RouteSection)

private fun buildFitting(item: LibraryItem, section: RouteSection, explicitOutlet: RouteSection?): LibraryItem {
    val definition = requireNotNull(item.fitting)
    val kind = definition.kind
    val outlet = if (kind == FittingKind.REDUCER) explicitOutlet ?: section.half() else null
    val outputSection = outlet ?: section
    val w = section.width()
    val h = section.height()
    val radius = max(w, h) * 2
    val length = w * when (kind) {
        FittingKind.END_CAP -> .3
        FittingKind.COUPLER, FittingKind.FLANGE -> .6
        FittingKind.UNION -> 1.2
        else -> 3.0
    }
    val z = h / 2
    val paths = mutableListOf<FittingPath>()
    val ports = mutableListOf<RawPort>()
    fun path(points: List<Point3>, sections: List<RouteSection> = List(points.size) { section }) {
        paths += FittingPath(points, sections)
    }
    fun port(id: String, point: Point3, direction: Point3, profile: RouteSection = section) {
        ports += RawPort(id, point, direction, profile)
    }
    when (kind) {
        FittingKind.ELBOW_45, FittingKind.ELBOW_90, FittingKind.ELBOW,
        FittingKind.BEND_90_HORIZONTAL_INSIDE, FittingKind.BEND_90_HORIZONTAL_OUTSIDE -> {
            val angle = (item.parameters["routingAngleRadians"] as? ParameterValue.Number)?.value ?: if (kind == FittingKind.ELBOW_45) PI / 4 else PI / 2
            val side = if (kind == FittingKind.BEND_90_HORIZONTAL_OUTSIDE) -1 else 1
            val points = List(9) { i ->
                val a = angle * i / 8
                Point3(side * radius * (1 - cos(a)), radius * sin(a), z)
            }
            path(points)
            port("input", points.first(), Point3(y = -1.0))
            port("output", points.last(), Point3(side * sin(angle), cos(angle)))
        }
        FittingKind.BEND_90_VERTICAL_INSIDE, FittingKind.BEND_90_VERTICAL_OUTSIDE -> {
            val up = kind == FittingKind.BEND_90_VERTICAL_INSIDE
            val angle=(item.parameters["routingAngleRadians"] as? ParameterValue.Number)?.value ?: PI/2
            val points = List(9) { i ->
                val a = angle * i / 8
                Point3(0.0, radius * sin(a), if (up) z + radius * (1 - cos(a)) else z + radius * cos(a))
            }
            path(points)
            port("input", points.first(), Point3(y = -1.0))
            port("output", points.last(), Point3(y=cos(angle),z=if(up)sin(angle)else -sin(angle)))
        }
        FittingKind.TEE, FittingKind.CROSS -> {
            val centre = Point3(y = length / 2, z = z)
            val start = Point3(z = z)
            val end = Point3(y = length, z = z)
            path(listOf(start, centre, end))
            path(listOf(centre, Point3(length / 2, length / 2, z)))
            port("input", start, Point3(y = -1.0))
            port("output", end, Point3(y = 1.0))
            port("branch-right", paths.last().points.last(), Point3(x = 1.0))
            if (kind == FittingKind.CROSS) {
                path(listOf(centre, Point3(-length / 2, length / 2, z)))
                port("branch-left", paths.last().points.last(), Point3(x = -1.0))
            }
        }
        FittingKind.RISER_UP, FittingKind.RISER_DOWN -> {
            val up = kind == FittingKind.RISER_UP
            val startHeight = if (up) z else z + radius
            val endHeight = if (up) z + radius else z
            val points = listOf(Point3(z = startHeight), Point3(y = length / 3, z = startHeight),
                Point3(y = length * 2 / 3, z = endHeight), Point3(y = length, z = endHeight))
            path(points)
            port("input", points.first(), Point3(y = -1.0))
            port("output", points.last(), Point3(y = 1.0))
        }
        FittingKind.OFFSET -> {
            val offsetX=(item.parameters["routingOffsetX"] as? ParameterValue.Number)?.value ?: w
            val offsetLength=(item.parameters["routingOffsetLength"] as? ParameterValue.Number)?.value ?: length
            val points = listOf(Point3(z = z), Point3(y = offsetLength / 3, z = z),
                Point3(offsetX, offsetLength * 2 / 3, z), Point3(offsetX, offsetLength, z))
            path(points)
            port("input", points.first(), Point3(y = -1.0))
            port("output", points.last(), Point3(y = 1.0))
        }
        else -> {
            val start = Point3(z = z)
            val end = Point3(y = length, z = outputSection.height() / 2)
            path(listOf(start, end), listOf(section, outputSection))
            port("input", start, Point3(y = -1.0))
            if (kind != FittingKind.END_CAP) port("output", end, Point3(y = 1.0), outputSection)
        }
    }

    // Mesh vertices are the same true section rings used by the connection metadata. This keeps
    // circular/vertical fittings and their shaded surfaces consistent with the route end planes.
    val rings = paths.map { fittingPath ->
        if (kind in connectorKinds) connectorRings(kind, fittingPath, section)
        else sweptRings(fittingPath, ports)
    }
    val accessory = accessoryOutline(kind, w, length, h)
    val rod = RouteSection.Rectangular(min(w, h) * .12, min(w, h) * .12)
    val accessoryRings = accessory.map { points -> sweptRings(FittingPath(points, List(points.size) { rod }), emptyList()) }
    val allPoints = (rings + accessoryRings).flatten().flatten()
    val minX = allPoints.minOf { it.x }
    val minY = allPoints.minOf { it.y }
    val minZ = allPoints.minOf { it.z }
    val size = Size3(allPoints.maxOf { it.x } - minX, allPoints.maxOf { it.y } - minY,
        allPoints.maxOf { it.z } - minZ)
    fun shift(point: Point3) = Point3(point.x - minX, point.y - minY, point.z - minZ)
    val geometry = mutableListOf<Shape>()
    rings.forEach { pathRings ->
        pathRings.forEach { geometry += Shape.Polyline(it.map(::shift), true) }
        geometry += sweptMesh(pathRings.map { it.map(::shift) })
    }
    accessoryRings.forEach { geometry += sweptMesh(it.map { ring -> ring.map(::shift) }) }
    val shiftedPorts = ports.map { FittingPort(it.id, shift(it.position), it.direction, it.section) }
    val symbols = mutableListOf<Shape>()
    paths.forEach { fittingPath ->
        val footprint = sweepFootprint(fittingPath)
        if (footprint.size >= 3) symbols += Shape.Polyline(footprint.map {
            val p = shift(it)
            Point3(p.x.coerceIn(0.0, size.width), p.y.coerceIn(0.0, size.depth))
        }, true)
        val projected = fittingPath.points.map { val p = shift(it); Point3(p.x, p.y) }.distinct()
        if (projected.size >= 2) symbols += Shape.Polyline(projected)
    }
    // Add the actual projected connector/support silhouette to the compact plan symbol.
    (rings + accessoryRings).forEach { pathRings ->
        pathRings.forEach { ring ->
            val projected = ring.map { val p = shift(it); Point3(p.x, p.y) }.distinct()
            if (projected.size >= 2) symbols += Shape.Polyline(projected, projected.size > 2)
        }
    }
    // The small cross marks the exact, reusable connection point in catalogue/plan symbols.
    shiftedPorts.forEach { port ->
        val d = min(w, h) * .12
        symbols += Shape.Line(Point3(max(0.0, port.position.x - d), port.position.y),
            Point3(min(size.width, port.position.x + d), port.position.y))
        symbols += Shape.Line(Point3(port.position.x, max(0.0, port.position.y - d)),
            Point3(port.position.x, min(size.depth, port.position.y + d)))
    }
    val nominal = if (definition.routeKind == RouteKind.DUCT && section is RouteSection.Circular)
        RouteSection.Circular(.315) else nominalSection(definition.routeKind)
    val nominalOutlet = if (kind == FittingKind.REDUCER) nominal.half() else null
    val angleSignature=listOf("routingAngleRadians","routingOffsetX","routingOffsetLength").mapNotNull {key->(item.parameters[key] as? ParameterValue.Number)?.value?.let {"@$key-${it.toBits().toULong().toString(16)}"}}.joinToString("")
    val id = if (section == nominal && outlet == nominalOutlet && angleSignature.isEmpty()) definition.baseTypeId else
        LibraryItemId("${definition.baseTypeId.value}@${section.signature()}${outlet?.let { "~${it.signature()}" } ?: ""}$angleSignature")
    return item.copy(
        id = id, defaultSize = size, geometrySize = null, geometry = geometry, symbolGeometry = symbols,
        parameters = item.parameters.filterKeys { it !in sectionKeys } + sectionParameters(section, outlet),
        fitting = FittingDefinition(definition.baseTypeId, definition.routeKind, kind, section, shiftedPorts,
            paths.first().points.map(::shift), outlet),
    )
}

private val connectorKinds = setOf(FittingKind.FLEXIBLE_CONNECTOR, FittingKind.EXPANSION_JOINT,
    FittingKind.UNION, FittingKind.FLANGE, FittingKind.COUPLER)

private fun connectorRings(kind: FittingKind, path: FittingPath, section: RouteSection): List<List<Point3>> {
    val count = if (kind == FittingKind.FLEXIBLE_CONNECTOR || kind == FittingKind.EXPANSION_JOINT) 14 else 4
    val start = path.points.first()
    val end = path.points.last()
    return List(count) { index ->
        val t = index.toDouble() / (count - 1)
        val factor = when {
            index == 0 || index == count - 1 -> 1.0
            kind == FittingKind.FLANGE -> 1.5
            kind == FittingKind.UNION -> 1.3
            kind == FittingKind.COUPLER -> 1.12
            index % 2 == 0 -> 1.0
            else -> .88
        }
        sectionRing(Point3(start.x + (end.x - start.x) * t, start.y + (end.y - start.y) * t,
            start.z + (end.z - start.z) * t), Point3(y = 1.0), section, factor)
    }
}

/** Adjacent ring quads and fan caps form one closed, low polygon count solid. */
private fun sweptMesh(rings: List<List<Point3>>): Shape.Mesh {
    val ringSize = rings.first().size
    val vertices = rings.flatten().toMutableList()
    val triangles = mutableListOf<Int>()
    fun triangle(a: Int, b: Int, c: Int) { triangles += listOf(a, b, c) }
    for (ring in 0 until rings.lastIndex) {
        for (side in 0 until ringSize) {
            val next = (side + 1) % ringSize
            val a = ring * ringSize + side
            val b = (ring + 1) * ringSize + side
            val c = (ring + 1) * ringSize + next
            val d = ring * ringSize + next
            triangle(a, b, c)
            triangle(a, c, d)
        }
    }
    fun centre(ring: List<Point3>) = Point3(ring.sumOf { it.x } / ring.size,
        ring.sumOf { it.y } / ring.size, ring.sumOf { it.z } / ring.size)
    val inputCentre = vertices.size
    vertices += centre(rings.first())
    val outputCentre = vertices.size
    vertices += centre(rings.last())
    for (side in 0 until ringSize) {
        val next = (side + 1) % ringSize
        triangle(inputCentre, side, next)
        triangle(outputCentre, rings.lastIndex * ringSize + next, rings.lastIndex * ringSize + side)
    }
    return Shape.Mesh(vertices, triangles)
}

/** Cross-section frame uses world/local Z for horizontal runs and local Y at a vertical port. */
private fun sectionRing(centre: Point3, direction: Point3, section: RouteSection, factor: Double = 1.0): List<Point3> {
    val horizontal = sqrt(direction.x * direction.x + direction.y * direction.y)
    val side = if (horizontal < 1e-10) Point3(x = 1.0) else Point3(direction.y / horizontal, -direction.x / horizontal)
    val up = Point3(direction.z * side.y, -direction.z * side.x,
        direction.x * -side.y + direction.y * side.x)
    fun point(x: Double, y: Double) = Point3(centre.x + side.x * x + up.x * y,
        centre.y + side.y * x + up.y * y, centre.z + side.z * x + up.z * y)
    return when (section) {
        is RouteSection.Rectangular -> listOf(point(-section.width / 2 * factor, -section.height / 2 * factor),
            point(section.width / 2 * factor, -section.height / 2 * factor),
            point(section.width / 2 * factor, section.height / 2 * factor),
            point(-section.width / 2 * factor, section.height / 2 * factor))
        is RouteSection.Circular -> List(12) { index ->
            val angle = 2 * PI * index / 12
            point(section.diameter / 2 * cos(angle) * factor, section.diameter / 2 * sin(angle) * factor)
        }
    }
}

private fun unit(from: Point3, to: Point3): Point3 {
    val x = to.x - from.x
    val y = to.y - from.y
    val z = to.z - from.z
    val length = sqrt(x * x + y * y + z * z)
    return Point3(x / length, y / length, z / length)
}

private fun sweptRings(path: FittingPath, ports: List<RawPort>): List<List<Point3>> = path.points.mapIndexed { index, centre ->
    val from = path.points[max(0, index - 1)]
    val to = path.points[min(path.points.lastIndex, index + 1)]
    val port = if (index == 0 || index == path.points.lastIndex) ports.firstOrNull { it.position == centre } else null
    val direction = if (port?.id == "input") Point3(-port.direction.x, -port.direction.y, -port.direction.z)
        else port?.direction ?: unit(from, to)
    sectionRing(centre, direction, path.sections[index])
}

private fun sweepFootprint(path: FittingPath): List<Point3> {
    val left = mutableListOf<Point3>()
    val right = mutableListOf<Point3>()
    path.points.forEachIndexed { index, centre ->
        val before = path.points[max(0, index - 1)]
        val after = path.points[min(path.points.lastIndex, index + 1)]
        val dx = after.x - before.x
        val dy = after.y - before.y
        val length = sqrt(dx * dx + dy * dy)
        if (length > 1e-10) {
            val half = path.sections[index].width() / 2
            left += Point3(centre.x - dy / length * half, centre.y + dx / length * half)
            right += Point3(centre.x + dy / length * half, centre.y - dx / length * half)
        }
    }
    return (left + right.asReversed()).distinct()
}

private fun accessoryOutline(kind: FittingKind, width: Double, length: Double, height: Double): List<List<Point3>> {
    val x = width * .7
    val y = length / 2
    val top = height * 2.5
    return when (kind) {
        FittingKind.SADDLE -> listOf(listOf(Point3(-x, y), Point3(-x, y, height * 1.3),
            Point3(x, y, height * 1.3), Point3(x, y)))
        FittingKind.SUPPORT -> listOf(listOf(Point3(-x, y), Point3(x, y)),
            listOf(Point3(0.0, y), Point3(0.0, y, height * 1.5)))
        FittingKind.HANGER -> listOf(listOf(Point3(0.0, y, height), Point3(0.0, y, top)),
            listOf(Point3(-x, y, height), Point3(x, y, height)))
        FittingKind.BRACKET -> listOf(listOf(Point3(-x, y, top), Point3(-x, y), Point3(x, y)),
            listOf(Point3(-x, y, height * 1.5), Point3(x, y)))
        FittingKind.TRAPEZE -> listOf(listOf(Point3(-x, y, top), Point3(-x, y), Point3(x, y), Point3(x, y, top)))
        else -> emptyList()
    }
}
