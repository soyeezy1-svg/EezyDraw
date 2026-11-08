package com.eezydraw.core.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Small parametric definitions, without bitmaps or file I/O. Shapes use the item's nominal SI size:
 * X=width, Y=depth (away from a wall), Z=height. Stable IDs survive project/archive round trips.
 * The catalogue is constructed once; a project carries only definitions it actually uses.
 */
object BuiltInLibrary {
    val items: List<LibraryItem> by lazy {
        listOf(
            electrical("single-switch", "Single switch", .086, .035, .086, MountingType.WALL, 1.2, LibrarySymbol.SWITCH),
            electrical("double-switch", "Double switch", .146, .035, .086, MountingType.WALL, 1.2, LibrarySymbol.DOUBLE_SWITCH),
            electrical("dimmer", "Dimmer", .086, .04, .086, MountingType.WALL, 1.2, LibrarySymbol.DIMMER),
            electrical("socket", "Socket", .086, .04, .086, MountingType.WALL, .3, LibrarySymbol.SOCKET),
            electrical("floor-box", "Floor box", .3, .3, .08, MountingType.FLOOR, 0.0, LibrarySymbol.FLOOR_BOX, "Steel"),
            electrical("isolator", "Isolator", .1, .08, .15, MountingType.WALL, 1.2, LibrarySymbol.ISOLATOR),
            electrical("distribution-board", "Distribution board", .6, .15, .8, MountingType.WALL, 1.2, LibrarySymbol.PANEL, "Powder-coated steel"),
            electrical("downlight", "Downlight", .15, .15, .08, MountingType.CEILING, 3.0, LibrarySymbol.LIGHT, "Aluminium", "Lighting", round = true),
            electrical("linear-light", "Linear light fitting", 1.2, .15, .08, MountingType.CEILING, 3.0, LibrarySymbol.LIGHT, "Aluminium", "Lighting"),
            electrical("emergency-light", "Emergency light", .3, .1, .08, MountingType.CEILING, 3.0, LibrarySymbol.EMERGENCY_LIGHT, system = "Emergency lighting"),
            electrical("exit-sign", "Exit sign", .3, .04, .15, MountingType.WALL, 2.1, LibrarySymbol.EXIT_SIGN, system = "Emergency lighting"),
            electrical("junction-box", "Junction box", .1, .05, .1, MountingType.WALL, 2.4, LibrarySymbol.JUNCTION_BOX),
            electrical("pull-box", "Pull box", .3, .15, .3, MountingType.WALL, 2.4, LibrarySymbol.PULL_BOX, "Steel"),
            electrical("cable-tray", "Cable tray", .3, 3.0, .05, MountingType.CEILING, 2.7, LibrarySymbol.TRAY, "Galvanized steel"),
            electrical("cable-ladder", "Cable ladder", .45, 3.0, .1, MountingType.CEILING, 2.7, LibrarySymbol.TRAY, "Galvanized steel"),
            electrical("trunking", "Trunking", .1, 3.0, .05, MountingType.CEILING, 2.7, LibrarySymbol.TRUNKING, "Galvanized steel"),
            electrical("conduit", "Conduit", .025, .025, 3.0, MountingType.WALL, 0.0, LibrarySymbol.CONDUIT, "PVC", round = true),
            electrical("earth-bar", "Earth bar", .5, .025, .05, MountingType.WALL, .3, LibrarySymbol.EARTH, "Copper", "Earthing"),
            electrical("earth-rod", "Earth rod", .016, .016, 1.2, MountingType.FLOOR, 0.0, LibrarySymbol.EARTH, "Copper", "Earthing", round = true),
            ict("rack-42u", "42U rack", .6, 1.0, 2.0, MountingType.FLOOR, 0.0, LibrarySymbol.RACK, "Powder-coated steel"),
            ict("rack-24u", "24U rack", .6, .8, 1.2, MountingType.FLOOR, 0.0, LibrarySymbol.RACK, "Powder-coated steel"),
            ict("rack-12u", "12U rack", .6, .6, .65, MountingType.FLOOR, 0.0, LibrarySymbol.RACK, "Powder-coated steel"),
            ict("patch-panel", "Patch panel", .483, .1, .04445, MountingType.WALL, 1.5, LibrarySymbol.PATCH_PANEL, "Steel"),
            ict("cable-basket", "Cable basket", .3, 3.0, .05, MountingType.CEILING, 2.7, LibrarySymbol.BASKET, "Galvanized steel"),
            ict("data-outlet", "Data outlet", .086, .035, .086, MountingType.WALL, .3, LibrarySymbol.DATA_OUTLET),
            ict("fibre-box", "Fibre box", .3, .08, .3, MountingType.WALL, 1.2, LibrarySymbol.FIBRE_BOX, "Steel"),
            ict("wifi-access-point", "Wi-Fi access point", .2, .2, .04, MountingType.CEILING, 3.0, LibrarySymbol.WIFI, round = true),
            ict("cctv-camera", "CCTV camera", .12, .12, .1, MountingType.CEILING, 3.0, LibrarySymbol.CAMERA, system = "Security", round = true),
            ict("access-control-reader", "Access control reader", .075, .025, .12, MountingType.WALL, 1.2, LibrarySymbol.READER, system = "Security"),
            ict("intercom", "Intercom", .1, .04, .2, MountingType.WALL, 1.4, LibrarySymbol.INTERCOM),
            ict("pa-speaker", "PA speaker", .2, .2, .08, MountingType.CEILING, 3.0, LibrarySymbol.SPEAKER, system = "Public address", round = true),
            ict("tv-outlet", "TV outlet", .086, .035, .086, MountingType.WALL, .3, LibrarySymbol.TV_OUTLET),
        ) + expandedLibraryItems() + fittingLibraryItems() + wallServiceLibraryItems() + floorServiceLibraryItems()
    }

    private val byId: Map<LibraryItemId, LibraryItem> by lazy { items.associateBy { it.id } }
    fun find(id: LibraryItemId): LibraryItem? = byId[id]

    /** Project definitions override matching built-ins, preserving custom versions and edits. */
    fun including(projectItems: List<LibraryItem>): List<LibraryItem> =
        (items.associateBy { it.id } + projectItems.associateBy { it.id }).values.toList()
}

private fun electrical(
    slug: String, name: String, w: Double, d: Double, h: Double, mounting: MountingType,
    mountingHeight: Double, symbol: LibrarySymbol, material: String = "ABS plastic",
    system: String = "Power", round: Boolean = false,
) = definition(slug, name, Discipline.ELECTRICAL, "Electrical", w, d, h, mounting, mountingHeight,
    symbol, material, system, 0xFFCF8024, round)

private fun ict(
    slug: String, name: String, w: Double, d: Double, h: Double, mounting: MountingType,
    mountingHeight: Double, symbol: LibrarySymbol, material: String = "ABS plastic",
    system: String = "ICT", round: Boolean = false,
) = definition(slug, name, Discipline.ICT, "ICT / low current", w, d, h, mounting, mountingHeight,
    symbol, material, system, 0xFF2878BC, round)

private fun definition(
    slug: String, name: String, discipline: Discipline, category: String, w: Double, d: Double,
    h: Double, mounting: MountingType, mountingHeight: Double, symbol: LibrarySymbol,
    material: String, system: String, colour: Long, round: Boolean,
): LibraryItem {
    val size = Size3(w, d, h)
    val solid = if (round) Shape.Extrusion(Polygon2(List(24) { i ->
        val angle = 2 * PI * i / 24
        Point2(w / 2 + w / 2 * cos(angle), d / 2 + d / 2 * sin(angle))
    }), h) else Shape.Box(size)
    val details = if (symbol == LibrarySymbol.RACK || symbol == LibrarySymbol.PANEL || symbol == LibrarySymbol.PATCH_PANEL) {
        val count = if (symbol == LibrarySymbol.RACK) 8 else 3
        List(count) { i ->
            val z = h * (i + 1) / (count + 1)
            Shape.Line(Point3(w * .08, d, z), Point3(w * .92, d, z))
        }
    } else emptyList()
    return LibraryItem(
        id = LibraryItemId("eezy.${discipline.name.lowercase()}.$slug"), name = name,
        discipline = discipline, category = category, defaultSize = size, geometry = listOf(solid) + details,
        builtIn = true, mounting = mounting, defaultMountingHeight = mountingHeight,
        symbol = symbol, symbolGeometry = planSymbol(symbol, size), defaultMaterial = material,
        defaultSystem = system, defaultColourArgb = colour,
    )
}

/** Vector-only symbols stay sharp at every zoom and require no thumbnail decoding/cache. */
private fun planSymbol(symbol: LibrarySymbol, size: Size3): List<Shape> {
    fun p(x: Double, y: Double) = Point3(x * size.width, y * size.depth)
    fun line(x: Double, y: Double, x2: Double, y2: Double) = Shape.Line(p(x, y), p(x2, y2))
    fun outline(vararg points: Pair<Double, Double>) = Shape.Polyline(points.map { p(it.first, it.second) }, true)
    fun circle(cx: Double = .5, cy: Double = .5, radius: Double = .35) = Shape.Polyline(List(24) { i ->
        val a = 2 * PI * i / 24
        p(cx + radius * cos(a), cy + radius * sin(a))
    }, true)
    val rect = outline(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0)
    val cross = listOf(line(.15, .15, .85, .85), line(.15, .85, .85, .15))
    return when (symbol) {
        LibrarySymbol.SWITCH -> listOf(rect, circle(), line(.5, .5, .85, .9))
        LibrarySymbol.DOUBLE_SWITCH -> listOf(rect, circle(.3, .5, .2), circle(.7, .5, .2),
            line(.3, .5, .48, .85), line(.7, .5, .88, .85))
        LibrarySymbol.DIMMER -> listOf(rect, circle(), line(.5, .5, .8, .8), line(.4, .1, .6, .1))
        LibrarySymbol.SOCKET -> listOf(rect, circle(), line(.4, .35, .4, .65), line(.6, .35, .6, .65))
        LibrarySymbol.FLOOR_BOX -> listOf(rect, outline(.15 to .15, .85 to .15, .85 to .85, .15 to .85)) + cross
        LibrarySymbol.ISOLATOR -> listOf(rect, line(.3, .2, .3, .8), line(.3, .5, .75, .8), circle(.75, .5, .07))
        LibrarySymbol.PANEL, LibrarySymbol.PATCH_PANEL -> listOf(rect, line(.1, .35, .9, .35), line(.1, .65, .9, .65), line(.8, .1, .8, .9))
        LibrarySymbol.LIGHT -> listOf(rect) + cross
        LibrarySymbol.EMERGENCY_LIGHT -> listOf(rect) + cross + listOf(line(.35, .5, .65, .5))
        LibrarySymbol.EXIT_SIGN -> listOf(rect, line(.2, .5, .8, .5), line(.6, .3, .8, .5), line(.6, .7, .8, .5))
        LibrarySymbol.JUNCTION_BOX -> listOf(rect) + cross
        LibrarySymbol.PULL_BOX -> listOf(rect, outline(.2 to .2, .8 to .2, .8 to .8, .2 to .8))
        LibrarySymbol.TRAY, LibrarySymbol.BASKET -> listOf(rect, line(.12, 0.0, .12, 1.0), line(.88, 0.0, .88, 1.0)) +
            List(6) { i -> line(.12, (i + 1) / 7.0, .88, (i + 1) / 7.0) }
        LibrarySymbol.TRUNKING -> listOf(rect, line(.5, 0.0, .5, 1.0))
        LibrarySymbol.CONDUIT -> listOf(circle(), circle(radius = .15))
        LibrarySymbol.EARTH -> listOf(rect, line(.5, .1, .5, .45), line(.15, .45, .85, .45), line(.25, .65, .75, .65), line(.35, .85, .65, .85))
        LibrarySymbol.RACK -> listOf(rect, outline(.1 to .1, .9 to .1, .9 to .9, .1 to .9)) +
            List(6) { i -> line(.1, (i + 1) / 7.0, .9, (i + 1) / 7.0) }
        LibrarySymbol.DATA_OUTLET -> listOf(rect, outline(.2 to .2, .8 to .2, .5 to .8), line(.5, .35, .5, .6))
        LibrarySymbol.FIBRE_BOX -> listOf(rect, circle(.35, .5, .2), circle(.65, .5, .2))
        LibrarySymbol.WIFI -> listOf(circle(), circle(radius = .2), circle(radius = .05))
        LibrarySymbol.CAMERA -> listOf(circle(.4, .5, .25), outline(.55 to .35, .9 to .15, .9 to .85, .55 to .65))
        LibrarySymbol.READER -> listOf(rect, outline(.2 to .55, .8 to .55, .8 to .85, .2 to .85), line(.3, .25, .7, .25))
        LibrarySymbol.INTERCOM -> listOf(rect, outline(.2 to .15, .8 to .15, .8 to .5, .2 to .5), circle(.5, .75, .1))
        LibrarySymbol.SPEAKER -> listOf(circle(), circle(radius = .15)) + cross
        LibrarySymbol.TV_OUTLET -> listOf(rect, circle(), line(.25, .3, .75, .3), line(.5, .3, .5, .75))
        LibrarySymbol.BOX -> listOf(rect)
    }
}
