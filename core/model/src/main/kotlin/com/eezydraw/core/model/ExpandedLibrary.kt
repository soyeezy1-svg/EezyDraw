package com.eezydraw.core.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lightweight, deterministic stock definitions. All dimensions are metres, with local X=width,
 * Y=depth and Z=height. The vector plans and physical parts scale with an instance's local size.
 * No bitmap, mesh file or project-specific state is retained by the built-in catalogue.
 */
fun expandedLibraryItems(): List<LibraryItem> = buildList {
    val fire = StockGroup("fire_protection", Discipline.FIRE_PROTECTION, "Fire alarm and safety", "Fire alarm", "ABS plastic", 0xFFD34B42)
    with(fire) {
        add(item("smoke-detector", "Smoke detector", .1, .1, .045, StockForm.DETECTOR, MountingType.CEILING))
        add(item("heat-detector", "Heat detector", .1, .1, .05, StockForm.HEAT_DETECTOR, MountingType.CEILING))
        add(item("manual-call-point", "Manual call point", .09, .04, .09, StockForm.CALL_POINT, MountingType.WALL, 1.4))
        add(item("sounder", "Fire alarm sounder", .1, .065, .1, StockForm.SOUNDER, MountingType.WALL, 2.1))
        add(item("fire-alarm-panel", "Fire alarm panel", .5, .15, .6, StockForm.PANEL, MountingType.WALL, 1.2, material = "Powder-coated steel"))
        add(item("sprinkler", "Sprinkler", .07, .07, .06, StockForm.SPRINKLER, MountingType.CEILING, system = "Fire suppression", material = "Brass"))
        add(item("hose-reel", "Hose reel", .6, .25, .65, StockForm.HOSE_REEL, MountingType.WALL, .7, system = "Fire suppression", material = "Steel"))
        add(item("fire-extinguisher", "Fire extinguisher", .18, .18, .55, StockForm.EXTINGUISHER, MountingType.WALL, .35, system = "Fire safety", material = "Steel"))
    }

    val hvac = StockGroup("hvac", Discipline.HVAC, "Mechanical / HVAC", "HVAC", "Galvanized steel", 0xFF4B9C7B)
    with(hvac) {
        add(item("air-handling-unit", "Air handling unit", 2.4, 1.2, 1.5, StockForm.AHU))
        add(item("fan-coil-unit", "Fan coil unit", 1.0, .6, .3, StockForm.FAN_COIL, MountingType.CEILING))
        add(item("split-ac-indoor", "Split AC indoor unit", .9, .22, .3, StockForm.INDOOR_AC, MountingType.WALL, 2.1, material = "ABS plastic"))
        add(item("split-ac-outdoor", "Split AC outdoor unit", .85, .35, .65, StockForm.OUTDOOR_AC))
        add(item("vav-box", "VAV box", .6, .9, .3, StockForm.VAV, MountingType.CEILING))
        add(item("exhaust-fan", "Exhaust fan", .3, .15, .3, StockForm.FAN, MountingType.WALL, 2.1))
        add(item("supply-diffuser", "Supply diffuser", .6, .6, .12, StockForm.SUPPLY_DIFFUSER, MountingType.CEILING, material = "Aluminium"))
        add(item("return-diffuser", "Return diffuser", .6, .6, .1, StockForm.RETURN_DIFFUSER, MountingType.CEILING, material = "Aluminium"))
        add(item("grille", "Grille", .6, .05, .3, StockForm.GRILLE, MountingType.WALL, 2.1, material = "Aluminium"))
        add(item("volume-damper", "Volume damper", .4, .2, .25, StockForm.VOLUME_DAMPER, MountingType.CEILING))
        add(item("fire-damper", "Fire damper", .4, .25, .25, StockForm.FIRE_DAMPER, MountingType.CEILING))
        add(item("rectangular-duct", "Rectangular duct", .4, 1.0, .25, StockForm.RECTANGULAR_DUCT, MountingType.CEILING))
        add(item("round-duct", "Round duct", .315, .315, 1.0, StockForm.TUBE, MountingType.CEILING))
        add(item("flexible-duct", "Flexible duct", .2, .2, 1.0, StockForm.FLEXIBLE_TUBE, MountingType.CEILING, material = "Insulated flexible aluminium"))
        add(item("chiller", "Chiller", 2.8, 1.2, 1.8, StockForm.CHILLER))
        add(item("pump", "HVAC pump", .6, .35, .45, StockForm.PUMP, material = "Cast iron"))
    }

    val plumbing = StockGroup("plumbing", Discipline.PLUMBING, "Plumbing and drainage", "Water supply", "Brass", 0xFF378AC0)
    with(plumbing) {
        // Outside diameters follow common steel pipe stock; the nominal bore remains searchable.
        listOf(15 to .0213, 20 to .0269, 25 to .0337, 32 to .0424, 40 to .0483,
            50 to .0603, 65 to .0761, 80 to .0889, 100 to .1143, 150 to .1683, 200 to .2191).forEach { (dn, diameter) ->
            add(item("pipe-dn$dn", "Pipe DN$dn", diameter, diameter, 1.0, StockForm.TUBE, material = "Steel",
                parameters = mapOf("nominalDiameter" to ParameterValue.Number(dn / 1000.0, Quantity.LENGTH),
                    "outsideDiameter" to ParameterValue.Number(diameter, Quantity.LENGTH))))
        }
        add(item("gate-valve", "Gate valve", .15, .1, .2, StockForm.GATE_VALVE))
        add(item("ball-valve", "Ball valve", .12, .08, .12, StockForm.BALL_VALVE))
        add(item("check-valve", "Check valve", .15, .1, .12, StockForm.CHECK_VALVE))
        add(item("pump", "Plumbing pump", .6, .35, .45, StockForm.PUMP, material = "Cast iron"))
        add(item("water-tank", "Water tank", 1.5, 1.5, 1.8, StockForm.TANK, material = "Polyethylene"))
        add(item("water-heater", "Water heater", .45, .45, .9, StockForm.HEATER, MountingType.WALL, 1.2, material = "Enamelled steel"))
        add(item("sink", "Sink", .8, .5, .25, StockForm.SINK, MountingType.WALL, .85, material = "Stainless steel"))
        add(item("basin", "Basin", .55, .45, .2, StockForm.BASIN, MountingType.WALL, .8, material = "Ceramic"))
        add(item("water-closet", "Water closet", .38, .7, .75, StockForm.WATER_CLOSET, material = "Ceramic", system = "Sanitary drainage"))
        add(item("urinal", "Urinal", .35, .3, .6, StockForm.URINAL, MountingType.WALL, .6, material = "Ceramic", system = "Sanitary drainage"))
        add(item("shower", "Shower", .9, .9, .08, StockForm.SHOWER, material = "Acrylic", system = "Sanitary drainage"))
        add(item("floor-drain", "Floor drain", .15, .15, .08, StockForm.FLOOR_DRAIN, material = "Stainless steel", system = "Sanitary drainage"))
        add(item("cleanout", "Cleanout", .1, .1, .08, StockForm.CLEANOUT, material = "Brass", system = "Sanitary drainage"))
        add(item("manhole", "Manhole", .9, .9, .9, StockForm.MANHOLE, material = "Concrete", system = "Underground drainage"))
    }

    val architecture = StockGroup("architecture", Discipline.ARCHITECTURE, "Architecture", "Architecture", "Timber", 0xFFAD936F)
    with(architecture) {
        add(item("single-door", "Single door", .9, .045, 2.1, StockForm.SINGLE_DOOR, MountingType.WALL))
        add(item("double-door", "Double door", 1.8, .045, 2.1, StockForm.DOUBLE_DOOR, MountingType.WALL))
        add(item("sliding-door", "Sliding door", 1.8, .09, 2.1, StockForm.SLIDING_DOOR, MountingType.WALL))
        add(item("single-window", "Single window", .9, .1, 1.2, StockForm.SINGLE_WINDOW, MountingType.WALL, .9, material = "Aluminium and glass"))
        add(item("double-window", "Double window", 1.5, .1, 1.2, StockForm.DOUBLE_WINDOW, MountingType.WALL, .9, material = "Aluminium and glass"))
        add(item("fixed-window", "Fixed window", 1.2, .1, 1.2, StockForm.FIXED_WINDOW, MountingType.WALL, .9, material = "Aluminium and glass"))
        add(item("stairs", "Stairs", 1.0, 3.0, 1.5, StockForm.STAIRS, material = "Concrete"))
        add(item("ramp", "Ramp", 1.2, 4.8, .4, StockForm.RAMP, material = "Concrete"))
        add(item("column", "Architectural column", .3, .3, 3.0, StockForm.RECTANGULAR_COLUMN, material = "Concrete"))
        add(item("ceiling", "Ceiling", 3.0, 3.0, .025, StockForm.CEILING, MountingType.CEILING, material = "Gypsum board"))
        add(item("floor", "Floor", 3.0, 3.0, .15, StockForm.SLAB, material = "Concrete"))
        add(item("desk", "Desk", 1.4, .7, .75, StockForm.DESK))
        add(item("chair", "Chair", .5, .5, .85, StockForm.CHAIR, material = "Upholstered steel"))
        add(item("table", "Table", 1.6, .9, .75, StockForm.TABLE))
        add(item("cabinet", "Cabinet", .8, .45, 1.8, StockForm.CABINET))
        add(item("counter", "Counter", 1.8, .6, .9, StockForm.COUNTER, material = "Laminate"))
    }

    val structure = StockGroup("structure", Discipline.STRUCTURE, "Civil and structure", "Structure", "Steel", 0xFF87929E)
    with(structure) {
        // Rolled stock is extruded along local Z. Rotate it using the common XYZ Properties controls.
        add(item("i-beam", "I-beam", .15, .3, 3.0, StockForm.I_BEAM))
        add(item("h-beam", "H-beam", .3, .3, 3.0, StockForm.H_BEAM))
        add(item("rectangular-beam", "Rectangular beam", .25, .4, 3.0, StockForm.RECTANGULAR_COLUMN, material = "Concrete"))
        add(item("square-column", "Square column", .4, .4, 3.0, StockForm.RECTANGULAR_COLUMN, material = "Reinforced concrete"))
        add(item("round-column", "Round column", .4, .4, 3.0, StockForm.ROUND_COLUMN, material = "Reinforced concrete"))
        add(item("slab", "Slab", 3.0, 3.0, .2, StockForm.SLAB, material = "Reinforced concrete"))
        add(item("footing", "Footing", 1.2, 1.2, .5, StockForm.FOOTING, material = "Reinforced concrete"))
        add(item("shear-wall", "Shear wall", 3.0, .25, 3.0, StockForm.SHEAR_WALL, material = "Reinforced concrete"))
        add(item("opening", "Opening", 1.0, .2, 2.1, StockForm.OPENING, material = "Void", system = "Coordination"))
        add(item("sleeve", "Sleeve", .15, .15, .3, StockForm.TUBE, material = "Steel", system = "Coordination"))
    }
}

private class StockGroup(
    private val namespace: String,
    private val discipline: Discipline,
    private val category: String,
    private val system: String,
    private val material: String,
    private val colour: Long,
) {
    fun item(
        slug: String, name: String, w: Double, d: Double, h: Double, form: StockForm,
        mounting: MountingType = MountingType.FLOOR, height: Double = 0.0,
        material: String = this.material, system: String = this.system,
        parameters: Map<String, ParameterValue> = emptyMap(),
    ): LibraryItem {
        val size = Size3(w, d, h)
        return LibraryItem(
            id = LibraryItemId("eezy.$namespace.$slug"), name = name, discipline = discipline,
            category = category, defaultSize = size, builtIn = true, mounting = mounting,
            defaultMountingHeight = if (mounting == MountingType.CEILING) 3.0 else height,
            geometry = stockGeometry(form, size), symbolGeometry = stockSymbol(form, size),
            defaultMaterial = material, defaultSystem = system, defaultColourArgb = colour,
            parameters = parameters,
            opening=when(form) {
                StockForm.SINGLE_DOOR->OpeningKind.DOOR;StockForm.DOUBLE_DOOR->OpeningKind.DOUBLE_DOOR
                StockForm.SLIDING_DOOR->OpeningKind.SLIDING_DOOR;StockForm.DOUBLE_WINDOW->OpeningKind.DOUBLE_WINDOW
                StockForm.SINGLE_WINDOW,StockForm.FIXED_WINDOW->OpeningKind.WINDOW;else->null
            },ceiling=form==StockForm.CEILING,
        )
    }
}

private enum class StockForm {
    DETECTOR, HEAT_DETECTOR, CALL_POINT, SOUNDER, PANEL, SPRINKLER, HOSE_REEL, EXTINGUISHER,
    AHU, FAN_COIL, INDOOR_AC, OUTDOOR_AC, VAV, FAN, SUPPLY_DIFFUSER, RETURN_DIFFUSER, GRILLE,
    VOLUME_DAMPER, FIRE_DAMPER, RECTANGULAR_DUCT, TUBE, FLEXIBLE_TUBE, CHILLER, PUMP,
    GATE_VALVE, BALL_VALVE, CHECK_VALVE, TANK, HEATER, SINK, BASIN, WATER_CLOSET, URINAL,
    SHOWER, FLOOR_DRAIN, CLEANOUT, MANHOLE, SINGLE_DOOR, DOUBLE_DOOR, SLIDING_DOOR,
    SINGLE_WINDOW, DOUBLE_WINDOW, FIXED_WINDOW, STAIRS, RAMP, RECTANGULAR_COLUMN,
    ROUND_COLUMN, CEILING, SLAB, DESK, CHAIR, TABLE, CABINET, COUNTER, I_BEAM, H_BEAM,
    FOOTING, SHEAR_WALL, OPENING,
}

/** Normalized vector construction keeps all symbols inside the nominal footprint. */
private class StockVectors(private val size: Size3) {
    fun p(x: Double, y: Double, z: Double = 0.0) = Point3(x * size.width, y * size.depth, z * size.height)
    fun line(x: Double, y: Double, x2: Double, y2: Double) = Shape.Line(p(x, y), p(x2, y2))
    fun line3(x: Double, y: Double, z: Double, x2: Double, y2: Double, z2: Double) = Shape.Line(p(x, y, z), p(x2, y2, z2))
    fun outline(vararg xy: Pair<Double, Double>) = Shape.Polyline(xy.map { p(it.first, it.second) }, true)
    fun rect(x: Double = 0.0, y: Double = 0.0, w: Double = 1.0, d: Double = 1.0) =
        outline(x to y, x + w to y, x + w to y + d, x to y + d)
    fun ellipse(cx: Double = .5, cy: Double = .5, rx: Double = .5, ry: Double = .5) =
        Shape.Polyline(ellipsePoints(cx, cy, rx, ry).map { p(it.x, it.y) }, true)
    fun arc(cx: Double, cy: Double, rx: Double, ry: Double, start: Double, sweep: Double) =
        Shape.Polyline(List(17) { i ->
            val angle = start + sweep * i / 16
            p(cx + rx * cos(angle), cy + ry * sin(angle))
        })
    fun profile(points: List<Point2>, holes: List<List<Point2>> = emptyList()) = Polygon2(
        points.map { Point2(it.x * size.width, it.y * size.depth) },
        holes.map { hole -> hole.map { Point2(it.x * size.width, it.y * size.depth) } },
    )
    fun prism(x: Double = 0.0, y: Double = 0.0, w: Double = 1.0, d: Double = 1.0, h: Double = 1.0) =
        Shape.Extrusion(profile(listOf(Point2(x, y), Point2(x + w, y), Point2(x + w, y + d), Point2(x, y + d))), size.height * h)
    fun ellipsePrism(cx: Double = .5, cy: Double = .5, rx: Double = .5, ry: Double = .5, h: Double = 1.0) =
        Shape.Extrusion(profile(ellipsePoints(cx, cy, rx, ry)), size.height * h)
    fun tube(inner: Double = .4) = Shape.Extrusion(profile(ellipsePoints(.5, .5, .5, .5),
        listOf(ellipsePoints(.5, .5, inner, inner).reversed())), size.height)
    fun box(x: Double, y: Double, z: Double, w: Double, d: Double, h: Double) = Shape.Mesh(
        vertices = listOf(p(x, y, z), p(x + w, y, z), p(x + w, y + d, z), p(x, y + d, z),
            p(x, y, z + h), p(x + w, y, z + h), p(x + w, y + d, z + h), p(x, y + d, z + h)),
        triangles = listOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4,
            1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7),
    )
}

private fun ellipsePoints(cx: Double, cy: Double, rx: Double, ry: Double) = List(24) { i ->
    val angle = 2 * PI * i / 24
    Point2(cx + rx * cos(angle), cy + ry * sin(angle))
}

private fun beamProfile(flange: Double, web: Double) = listOf(
    Point2(0.0, 0.0), Point2(1.0, 0.0), Point2(1.0, flange), Point2(.5 + web / 2, flange),
    Point2(.5 + web / 2, 1 - flange), Point2(1.0, 1 - flange), Point2(1.0, 1.0), Point2(0.0, 1.0),
    Point2(0.0, 1 - flange), Point2(.5 - web / 2, 1 - flange), Point2(.5 - web / 2, flange), Point2(0.0, flange),
)

private fun stockSymbol(form: StockForm, size: Size3): List<Shape> = with(StockVectors(size)) {
    val frame = rect()
    val inset = rect(.1, .1, .8, .8)
    val cross = listOf(line(.15, .15, .85, .85), line(.15, .85, .85, .15))
    val vanes = List(5) { i -> line(.15, (i + 1) / 6.0, .85, (i + 1) / 6.0) }
    val fan = listOf(ellipse(), ellipse(rx = .1, ry = .1)) + List(4) { i ->
        val angle = i * PI / 2
        line(.5 + .12 * cos(angle), .5 + .12 * sin(angle), .5 + .4 * cos(angle + .5), .5 + .4 * sin(angle + .5))
    }
    val valve = listOf(outline(.1 to .2, .5 to .5, .1 to .8), outline(.9 to .2, .5 to .5, .9 to .8),
        line(0.0, .5, .1, .5), line(.9, .5, 1.0, .5))
    when (form) {
        StockForm.DETECTOR -> listOf(ellipse(), ellipse(rx = .35, ry = .35),
            arc(.5, .5, .18, .18, PI / 2, PI), line(.5, .32, .65, .32), line(.35, .68, .5, .68))
        StockForm.HEAT_DETECTOR -> listOf(ellipse(), line(.35, .25, .35, .75), line(.65, .25, .65, .75), line(.35, .5, .65, .5))
        StockForm.CALL_POINT -> listOf(frame, inset, line(.25, .5, .75, .5), line(.4, .3, .6, .7))
        StockForm.SOUNDER -> listOf(ellipse(), ellipse(rx = .2, ry = .2),
            arc(.5, .5, .35, .35, -PI / 4, PI / 2), line(.1, .5, .3, .5))
        StockForm.PANEL -> listOf(frame, inset, rect(.2, .5, .6, .3), line(.2, .3, .8, .3))
        StockForm.SPRINKLER -> listOf(ellipse(), ellipse(rx = .1, ry = .1)) + cross
        StockForm.HOSE_REEL -> listOf(frame, ellipse(rx = .4, ry = .4), ellipse(rx = .25, ry = .25), ellipse(rx = .1, ry = .1))
        StockForm.EXTINGUISHER -> listOf(ellipse(), rect(.3, .3, .4, .4), line(.5, .3, .85, .1))
        StockForm.AHU -> listOf(frame, line(.3, 0.0, .3, 1.0), line(.65, 0.0, .65, 1.0),
            ellipse(.8, .5, .13, .35), line(.05, .1, .25, .9), line(.05, .9, .25, .1))
        StockForm.FAN_COIL, StockForm.INDOOR_AC -> listOf(frame, line(.15, .2, .85, .2), line(.15, .8, .85, .8),
            line(.2, .3, .8, .7), line(.2, .7, .8, .3))
        StockForm.OUTDOOR_AC, StockForm.FAN -> listOf(frame) + fan
        StockForm.VAV -> listOf(frame, rect(.15, .1, .7, .8), line(.2, .2, .8, .8), line(.2, .8, .8, .2))
        StockForm.SUPPLY_DIFFUSER -> listOf(frame, inset, rect(.25, .25, .5, .5), rect(.4, .4, .2, .2)) + cross
        StockForm.RETURN_DIFFUSER, StockForm.GRILLE -> listOf(frame, inset) + vanes
        StockForm.VOLUME_DAMPER -> listOf(frame, line(0.0, .5, 1.0, .5), line(.15, .2, .85, .8))
        StockForm.FIRE_DAMPER -> listOf(frame, line(.15, .2, .85, .8), line(.15, .8, .85, .2),
            line(.35, .4, .35, .65), line(.35, .65, .65, .65), line(.35, .52, .57, .52))
        StockForm.RECTANGULAR_DUCT -> listOf(frame, line(0.0, .1, 1.0, .1), line(0.0, .9, 1.0, .9), line(.5, 0.0, .5, 1.0))
        StockForm.TUBE -> listOf(ellipse(), ellipse(rx = .4, ry = .4))
        StockForm.FLEXIBLE_TUBE -> listOf(ellipse(), ellipse(rx = .42, ry = .42), ellipse(rx = .3, ry = .3))
        StockForm.CHILLER -> listOf(frame, ellipse(.25, .5, .2, .4), ellipse(.75, .5, .2, .4), line(.5, 0.0, .5, 1.0))
        StockForm.PUMP -> listOf(frame, ellipse(.3, .5, .25, .35), rect(.55, .25, .4, .5),
            outline(.22 to .3, .22 to .7, .45 to .5))
        StockForm.GATE_VALVE -> valve + listOf(line(.5, .1, .5, .4), line(.35, .1, .65, .1))
        StockForm.BALL_VALVE -> valve + listOf(ellipse(rx = .13, ry = .13), line(.5, .5, .75, .1))
        StockForm.CHECK_VALVE -> valve + listOf(line(.5, .5, .7, .2), line(.7, .2, .7, .8))
        StockForm.TANK -> listOf(ellipse(), ellipse(rx = .4, ry = .4), rect(.4, .4, .2, .2))
        StockForm.HEATER -> listOf(ellipse(), line(.25, .35, .75, .35), line(.35, .5, .65, .5), line(.25, .65, .75, .65))
        StockForm.SINK -> listOf(frame, rect(.08, .12, .58, .76), ellipse(.37, .5, .05, .07), line(.78, .15, .78, .85), line(.88, .15, .88, .85))
        StockForm.BASIN -> listOf(ellipse(), ellipse(.5, .55, .35, .3), ellipse(.5, .55, .04, .04), line(.5, .05, .5, .3))
        StockForm.WATER_CLOSET -> listOf(rect(.05, 0.0, .9, .25), ellipse(.5, .6, .4, .4), ellipse(.5, .6, .25, .28))
        StockForm.URINAL -> listOf(outline(0.0 to 0.0, 1.0 to 0.0, .8 to .85, .5 to 1.0, .2 to .85), ellipse(.5, .6, .2, .25))
        StockForm.SHOWER -> listOf(frame, inset, ellipse(.5, .5, .06, .06)) + cross
        StockForm.FLOOR_DRAIN -> listOf(frame, inset) + vanes + List(5) { i -> line((i + 1) / 6.0, .15, (i + 1) / 6.0, .85) }
        StockForm.CLEANOUT -> listOf(ellipse(), rect(.35, .35, .3, .3))
        StockForm.MANHOLE -> listOf(frame, ellipse(rx = .4, ry = .4)) + cross
        // The opening arcs are compressed into the item's actual plan depth, so the library's
        // thumbnail and selection bounds agree with the physical leaf rather than a swept volume.
        StockForm.SINGLE_DOOR -> listOf(frame, line(0.0, 0.0, 0.0, 1.0), arc(0.0, 0.0, 1.0, 1.0, 0.0, PI / 2))
        StockForm.DOUBLE_DOOR -> listOf(frame, line(.5, 0.0, .5, 1.0), arc(0.0, 0.0, .5, 1.0, 0.0, PI / 2), arc(1.0, 0.0, .5, 1.0, PI / 2, PI / 2))
        StockForm.SLIDING_DOOR -> listOf(frame, rect(0.0, 0.0, .55, .4), rect(.45, .6, .55, .4),
            line(.25, .5, .75, .5), line(.65, .4, .75, .5), line(.65, .6, .75, .5))
        StockForm.SINGLE_WINDOW -> listOf(frame, line(0.0, .25, 1.0, .25), line(0.0, .75, 1.0, .75), line(.1, .25, .9, .75))
        StockForm.DOUBLE_WINDOW -> listOf(frame, line(0.0, .25, 1.0, .25), line(0.0, .75, 1.0, .75), line(.5, 0.0, .5, 1.0))
        StockForm.FIXED_WINDOW -> listOf(frame, line(0.0, .25, 1.0, .25), line(0.0, .75, 1.0, .75))
        StockForm.STAIRS -> listOf(frame, line(.5, .1, .5, .9), line(.4, .8, .5, .9), line(.6, .8, .5, .9)) +
            List(10) { i -> line(0.0, (i + 1) / 11.0, 1.0, (i + 1) / 11.0) }
        StockForm.RAMP -> listOf(frame, line(.5, .1, .5, .9), line(.35, .75, .5, .9), line(.65, .75, .5, .9))
        StockForm.RECTANGULAR_COLUMN -> listOf(frame) + cross
        StockForm.ROUND_COLUMN -> listOf(ellipse()) + cross
        StockForm.CEILING -> listOf(frame) + List(4) { i -> line((i + 1) / 5.0, 0.0, (i + 1) / 5.0, 1.0) } +
            List(4) { i -> line(0.0, (i + 1) / 5.0, 1.0, (i + 1) / 5.0) }
        StockForm.SLAB -> listOf(frame, inset, line(.1, .1, .9, .9))
        StockForm.DESK -> listOf(frame, rect(.65, .1, .25, .8), line(.68, .35, .87, .35), line(.68, .65, .87, .65))
        StockForm.CHAIR -> listOf(frame, rect(.1, .2, .8, .7), line(.1, .1, .9, .1))
        StockForm.TABLE -> listOf(frame, rect(.1, .1, .1, .1), rect(.8, .1, .1, .1), rect(.1, .8, .1, .1), rect(.8, .8, .1, .1))
        StockForm.CABINET, StockForm.COUNTER -> listOf(frame, inset, line(.5, .1, .5, .9))
        StockForm.I_BEAM -> listOf(Shape.Polyline(beamProfile(.08, .08).map { p(it.x, it.y) }, true))
        StockForm.H_BEAM -> listOf(Shape.Polyline(beamProfile(.12, .1).map { p(it.x, it.y) }, true))
        StockForm.FOOTING -> listOf(frame, rect(.3, .3, .4, .4)) + cross
        StockForm.SHEAR_WALL -> listOf(frame) + List(8) { i -> line(i / 8.0, 0.0, (i + 1) / 8.0, 1.0) }
        StockForm.OPENING -> listOf(frame) + cross
    }
}

/**
 * Solids use extrusions, hollow profiles and small closed meshes for inclined/elevated parts.
 * Surface details remain ordinary 3D strokes.
 * Repeated parts are deliberately small so hundreds of definitions stay cheap to instantiate.
 */
private fun stockGeometry(form: StockForm, size: Size3): List<Shape> = with(StockVectors(size)) {
    fun frontSlats(count: Int = 6) = List(count) { i -> line3(.1, 1.0, (i + 1) / (count + 1.0), .9, 1.0, (i + 1) / (count + 1.0)) }
    fun faceRect(x: Double, z: Double, w: Double, h: Double) = Shape.Polyline(listOf(
        p(x, 1.0, z), p(x + w, 1.0, z), p(x + w, 1.0, z + h), p(x, 1.0, z + h),
    ), true)
    fun frontEllipse(cx: Double, cz: Double, rx: Double, rz: Double) = Shape.Polyline(List(24) { i ->
        val a = i * 2 * PI / 24
        p(cx + rx * cos(a), 1.0, cz + rz * sin(a))
    }, true)
    fun table(legHeight: Double = .92): List<Shape> = listOf(
        prism(.07, .08, .07, .1, legHeight), prism(.86, .08, .07, .1, legHeight),
        prism(.07, .82, .07, .1, legHeight), prism(.86, .82, .07, .1, legHeight),
        box(0.0, 0.0, legHeight, 1.0, 1.0, 1 - legHeight),
    )
    when (form) {
        StockForm.DETECTOR, StockForm.HEAT_DETECTOR -> listOf(ellipsePrism(h = .25), ellipsePrism(rx = .42, ry = .42)) +
            List(8) { i ->
                val a = i * PI / 4
                line3(.5 + .42 * cos(a), .5 + .42 * sin(a), .4, .5 + .42 * cos(a), .5 + .42 * sin(a), .8)
            }
        StockForm.CALL_POINT -> listOf(prism(), faceRect(.15, .2, .7, .6), line3(.25, 1.0, .5, .75, 1.0, .5))
        StockForm.SOUNDER -> listOf(prism(), frontEllipse(.5, .5, .4, .4), frontEllipse(.5, .5, .25, .25)) + frontSlats(3)
        StockForm.PANEL -> listOf(prism(), faceRect(.1, .45, .8, .4), faceRect(.15, .2, .7, .15), line3(.85, 1.0, .1, .85, 1.0, .4))
        StockForm.SPRINKLER -> listOf(ellipsePrism(h = .2), ellipsePrism(rx = .12, ry = .12),
            line3(.2, .5, .2, .5, .5, 1.0), line3(.8, .5, .2, .5, .5, 1.0))
        StockForm.HOSE_REEL -> listOf(prism(0.0, 0.0, 1.0, .2), ellipsePrism(h = .94),
            frontEllipse(.5, .5, .5, .46), frontEllipse(.5, .5, .36, .33), frontEllipse(.5, .5, .2, .18),
            line3(.9, 1.0, .5, 1.0, 1.0, 0.0))
        StockForm.EXTINGUISHER -> listOf(ellipsePrism(h = .88), prism(.35, .35, .3, .3),
            line3(.5, .5, .95, 1.0, .5, .75), line3(1.0, .5, .75, 1.0, .5, .3))
        StockForm.AHU -> listOf(prism(), line3(.3, 1.0, 0.0, .3, 1.0, 1.0), line3(.65, 1.0, 0.0, .65, 1.0, 1.0),
            faceRect(.05, .2, .2, .6), faceRect(.35, .2, .25, .6), frontEllipse(.82, .5, .13, .3))
        StockForm.FAN_COIL, StockForm.INDOOR_AC -> listOf(prism(), faceRect(.05, .1, .9, .3)) + frontSlats()
        StockForm.OUTDOOR_AC, StockForm.FAN -> listOf(prism(), frontEllipse(.5, .5, .43, .43), frontEllipse(.5, .5, .1, .1)) +
            List(4) { i ->
                val a = i * PI / 2
                line3(.5 + .1 * cos(a), 1.0, .5 + .1 * sin(a), .5 + .38 * cos(a + .4), 1.0, .5 + .38 * sin(a + .4))
            }
        StockForm.VAV -> listOf(prism(.12, .1, .76, .8), prism(0.0, 0.0, 1.0, 1.0, .6), line3(.12, .1, 1.0, .88, .9, 1.0))
        StockForm.SUPPLY_DIFFUSER -> listOf(prism(h = .2), prism(.12, .12, .76, .76, .45),
            prism(.25, .25, .5, .5, .7), prism(.4, .4, .2, .2))
        StockForm.RETURN_DIFFUSER -> listOf(prism(h = .2)) + List(7) { i -> prism(.1, .08 + i * .12, .8, .025) }
        StockForm.GRILLE -> listOf(prism(), faceRect(.05, .05, .9, .9)) + frontSlats(8)
        StockForm.VOLUME_DAMPER, StockForm.FIRE_DAMPER -> listOf(
            Shape.Extrusion(profile(listOf(Point2(0.0, 0.0), Point2(1.0, 0.0), Point2(1.0, 1.0), Point2(0.0, 1.0)),
                listOf(listOf(Point2(.08, .08), Point2(.08, .92), Point2(.92, .92), Point2(.92, .08)))), size.height),
            line3(.1, .1, .2, .9, .9, .8), line3(.1, .9, .2, .9, .1, .8),
        )
        StockForm.RECTANGULAR_DUCT -> listOf(
            // An open-ended box section runs along Y, with side walls and a lower sheet.
            prism(0.0, 0.0, .025, 1.0), prism(.975, 0.0, .025, 1.0), prism(h = .025),
        ) + box(0.0, 0.0, .975, 1.0, 1.0, .025)
        StockForm.TUBE -> listOf(tube())
        StockForm.FLEXIBLE_TUBE -> listOf(tube()) + List(12) { i ->
            Shape.Polyline(ellipsePoints(.5, .5, .5, .5).map { p(it.x, it.y, i / 11.0) }, true)
        }
        StockForm.CHILLER -> listOf(prism(h = .65), prism(0.0, 0.0, .08, 1.0), prism(.92, 0.0, .08, 1.0),
            ellipsePrism(.27, .5, .2, .4), ellipsePrism(.73, .5, .2, .4)) + frontSlats(4)
        StockForm.PUMP -> listOf(prism(h = .12), ellipsePrism(.28, .5, .28, .5, .8), prism(.53, .16, .47, .68),
            line3(.55, .2, .8, .95, .2, .8), line3(.55, .8, .8, .95, .8, .8))
        StockForm.GATE_VALVE -> listOf(ellipsePrism(h = .55), prism(.4, .4, .2, .2),
            Shape.Polyline(ellipsePoints(.5, .5, .4, .4).map { p(it.x, it.y, 1.0) }, true))
        StockForm.BALL_VALVE -> listOf(ellipsePrism(h = .6), prism(.45, .45, .1, .1), line3(.5, .5, 1.0, 1.0, .5, 1.0))
        StockForm.CHECK_VALVE -> listOf(ellipsePrism(h = .8), prism(.3, .3, .4, .4), line3(.2, 1.0, .2, .8, 1.0, .7))
        StockForm.TANK -> listOf(ellipsePrism(h = .94), ellipsePrism(rx = .12, ry = .12)) +
            List(3) { i -> Shape.Polyline(ellipsePoints(.5, .5, .5, .5).map { p(it.x, it.y, (i + 1) / 4.0) }, true) }
        StockForm.HEATER -> listOf(ellipsePrism(), frontEllipse(.5, .6, .16, .08), line3(.25, .5, 0.0, .25, .5, .15), line3(.75, .5, 0.0, .75, .5, .15))
        StockForm.SINK -> listOf(Shape.Extrusion(profile(listOf(Point2(0.0, 0.0), Point2(1.0, 0.0), Point2(1.0, 1.0), Point2(0.0, 1.0)),
            listOf(listOf(Point2(.1, .15), Point2(.1, .85), Point2(.65, .85), Point2(.65, .15)))), size.height),
            prism(.1, .15, .55, .7, .08))
        StockForm.BASIN -> listOf(Shape.Extrusion(profile(ellipsePoints(.5, .5, .5, .5),
            listOf(ellipsePoints(.5, .55, .35, .3).reversed())), size.height), ellipsePrism(.5, .55, .35, .3, .08))
        StockForm.WATER_CLOSET -> listOf(prism(0.0, 0.0, 1.0, .25), ellipsePrism(.5, .62, .42, .38, .6),
            Shape.Polyline(ellipsePoints(.5, .62, .27, .27).map { p(it.x, it.y, .6) }, true))
        StockForm.URINAL -> listOf(Shape.Extrusion(profile(listOf(Point2(0.0, 0.0), Point2(1.0, 0.0), Point2(.8, .85), Point2(.5, 1.0), Point2(.2, .85)),
            listOf(ellipsePoints(.5, .55, .25, .25).reversed())), size.height), ellipsePrism(.5, .55, .25, .25, .05))
        StockForm.SHOWER -> listOf(prism(h = .2), prism(0.0, 0.0, 1.0, .05), prism(0.0, .95, 1.0, .05),
            prism(0.0, .05, .05, .9), prism(.95, .05, .05, .9))
        StockForm.FLOOR_DRAIN -> listOf(prism(h = .9)) + List(5) { i -> prism(.08 + i * .2, 0.0, .035, 1.0) }
        StockForm.CLEANOUT -> listOf(ellipsePrism(h = .85), prism(.35, .35, .3, .3))
        StockForm.MANHOLE -> listOf(Shape.Extrusion(profile(listOf(Point2(0.0, 0.0), Point2(1.0, 0.0), Point2(1.0, 1.0), Point2(0.0, 1.0)),
            listOf(ellipsePoints(.5, .5, .4, .4).reversed())), size.height), prism(.05, .05, .9, .9, .05))
        StockForm.SINGLE_DOOR -> listOf(prism(), faceRect(.1, .1, .8, .8), line3(.8, 1.0, .5, .9, 1.0, .5))
        StockForm.DOUBLE_DOOR -> listOf(prism(), faceRect(.05, .05, .4, .9), faceRect(.55, .05, .4, .9),
            line3(.5, 1.0, 0.0, .5, 1.0, 1.0))
        StockForm.SLIDING_DOOR -> listOf(prism(0.0, 0.0, .55, .4), prism(.45, .6, .55, .4),
            faceRect(.5, .05, .45, .9))
        StockForm.SINGLE_WINDOW, StockForm.DOUBLE_WINDOW, StockForm.FIXED_WINDOW -> listOf(prism(),
            faceRect(.05, .05, .9, .9), line3(.05, 1.0, .05, .95, 1.0, .95)) +
            if (form == StockForm.DOUBLE_WINDOW) listOf(line3(.5, 1.0, 0.0, .5, 1.0, 1.0)) else emptyList()
        StockForm.STAIRS -> List(10) { i -> prism(0.0, i / 10.0, 1.0, .1, (i + 1) / 10.0) }
        StockForm.RAMP -> listOf(Shape.Mesh(
            vertices = listOf(p(0.0, 0.0), p(1.0, 0.0), p(1.0, 1.0), p(0.0, 1.0),
                p(1.0, 1.0, 1.0), p(0.0, 1.0, 1.0)),
            // Bottom, inclined top, back, and the two triangular sides; outward winding.
            triangles = listOf(0, 3, 2, 0, 2, 1, 0, 1, 4, 0, 4, 5, 2, 3, 5, 2, 5, 4, 0, 5, 3, 1, 2, 4),
        ))
        StockForm.RECTANGULAR_COLUMN, StockForm.SLAB, StockForm.SHEAR_WALL -> listOf(prism())
        StockForm.ROUND_COLUMN -> listOf(ellipsePrism())
        StockForm.CEILING -> listOf(prism()) + List(4) { i -> line3((i + 1) / 5.0, 0.0, 1.0, (i + 1) / 5.0, 1.0, 1.0) } +
            List(4) { i -> line3(0.0, (i + 1) / 5.0, 1.0, 1.0, (i + 1) / 5.0, 1.0) }
        StockForm.DESK -> table() + listOf(prism(.7, .1, .2, .8, .9), faceRect(.7, .3, .2, .05), faceRect(.7, .6, .2, .05))
        StockForm.TABLE -> table()
        StockForm.CHAIR -> listOf(prism(.07, .08, .08, .1, .48), prism(.85, .08, .08, .1, .55),
            prism(.07, .82, .08, .1, .48), prism(.85, .82, .08, .1, .55),
            box(0.0, 0.0, .48, 1.0, 1.0, .07), box(.85, 0.0, .55, .15, 1.0, .45))
        StockForm.CABINET, StockForm.COUNTER -> listOf(prism(), faceRect(.05, .05, .9, .9),
            line3(.5, 1.0, .05, .5, 1.0, .95), line3(.44, 1.0, .4, .44, 1.0, .6), line3(.56, 1.0, .4, .56, 1.0, .6))
        StockForm.I_BEAM -> listOf(Shape.Extrusion(profile(beamProfile(.08, .08)), size.height))
        StockForm.H_BEAM -> listOf(Shape.Extrusion(profile(beamProfile(.12, .1)), size.height))
        StockForm.FOOTING -> listOf(prism(h = .6), prism(.25, .25, .5, .5))
        StockForm.OPENING -> listOf(prism(0.0, 0.0, .025, 1.0), prism(.975, 0.0, .025, 1.0)) +
            box(0.0, 0.0, .975, 1.0, 1.0, .025) + listOf(line3(0.0, 0.0, 0.0, 1.0, 0.0, 1.0), line3(1.0, 0.0, 0.0, 0.0, 0.0, 1.0))
    }
}
