package com.eezydraw.core.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class FittingLibraryTest {
    private val items = fittingLibraryItems()

    @Test fun `every service has complete fittings and distinct reusable connection definitions`() {
        assertEquals(171, items.size)
        assertEquals(items.size, items.map { it.id }.distinct().size)
        val required = setOf(FittingKind.ELBOW_45, FittingKind.ELBOW_90, FittingKind.BEND_90_HORIZONTAL_INSIDE,
            FittingKind.BEND_90_HORIZONTAL_OUTSIDE, FittingKind.BEND_90_VERTICAL_INSIDE,
            FittingKind.BEND_90_VERTICAL_OUTSIDE, FittingKind.TEE, FittingKind.CROSS, FittingKind.REDUCER,
            FittingKind.RISER_UP, FittingKind.RISER_DOWN, FittingKind.OFFSET, FittingKind.COUPLER,
            FittingKind.END_CAP, FittingKind.FLEXIBLE_CONNECTOR, FittingKind.EXPANSION_JOINT,
            FittingKind.SADDLE, FittingKind.SUPPORT, FittingKind.HANGER, FittingKind.BRACKET, FittingKind.TRAPEZE)
        RouteKind.entries.forEach { route ->
            assertTrue(items.filter { it.fitting?.routeKind == route }.map { it.fitting!!.kind }.containsAll(required))
        }
        val pipe = items.filter { it.fitting?.routeKind == RouteKind.PIPE }
        assertTrue(pipe.map { it.fitting!!.kind }.containsAll(listOf(FittingKind.ELBOW, FittingKind.UNION, FittingKind.FLANGE)))
        assertEquals(21, items.count { it.fitting?.routeKind == RouteKind.DUCT && it.fitting?.section is RouteSection.Circular })
        assertEquals(21, items.count { it.fitting?.routeKind == RouteKind.DUCT && it.fitting?.section is RouteSection.Rectangular })
        items.forEach { item ->
            val fitting = item.fitting!!
            assertEquals(item.id, fitting.baseTypeId)
            assertEquals("input", fitting.ports.first().id)
            assertEquals(Point3(y = -1.0), fitting.ports.first().direction)
            assertTrue(item.builtIn)
            assertTrue(item.geometry.any { it is Shape.Mesh })
            assertTrue(item.symbolGeometry.isNotEmpty())
            assertEquals(if (fitting.kind == FittingKind.END_CAP) 1 else when (fitting.kind) {
                FittingKind.TEE -> 3
                FittingKind.CROSS -> 4
                else -> 2
            }, fitting.ports.size)
        }
    }

    @Test fun `adapting fitting regenerates section geometry ports and stable ids without changing catalogue`() {
        val original = items.first { it.fitting?.routeKind == RouteKind.CABLE_TRAY && it.fitting?.kind == FittingKind.ELBOW_90 }
        val section = RouteSection.Rectangular(.6, .15)
        val adapted = adaptedFittingItem(original, section)!!
        assertNotEquals(original.id, adapted.id)
        assertEquals(original.id, adapted.fitting!!.baseTypeId)
        assertEquals(section, adapted.fitting!!.section)
        assertTrue(adapted.fitting!!.ports.all { it.section == section })
        assertEquals(.6, number(adapted, LibraryParameterKeys.SECTION_WIDTH), 0.0)
        assertEquals(.15, number(adapted, LibraryParameterKeys.SECTION_HEIGHT), 0.0)
        assertEquals(adapted, adaptedFittingItem(adapted, section))
        assertEquals(original, adaptedFittingItem(adapted, original.fitting!!.section))
        assertEquals(RouteSection.Rectangular(.3, .05), original.fitting!!.section)
        assertNull(adaptedFittingItem(original, RouteSection.Circular(.1)))
        assertNull(adaptedFittingItem(original.copy(fitting = null), section))
    }

    @Test fun `reducer preserves explicit inlet and outlet profile and parameter dimensions`() {
        val original = items.first { it.fitting?.routeKind == RouteKind.PIPE && it.fitting?.kind == FittingKind.REDUCER }
        val fitted = adaptedFittingItem(original, RouteSection.Circular(.08), RouteSection.Circular(.05))!!
        assertEquals(RouteSection.Circular(.08), fitted.fitting!!.ports.first().section)
        assertEquals(RouteSection.Circular(.05), fitted.fitting!!.ports.last().section)
        assertEquals(.08, number(fitted, LibraryParameterKeys.DIAMETER), 0.0)
        assertEquals(.05, number(fitted, LibraryParameterKeys.OUTLET_DIAMETER), 0.0)
        assertNotEquals(fitted.id, adaptedFittingItem(original, RouteSection.Circular(.08), RouteSection.Circular(.04))!!.id)
        assertNull(adaptedFittingItem(original, RouteSection.Circular(.08), RouteSection.Rectangular(.05, .05)))
        val rectangle = items.first { it.fitting?.routeKind == RouteKind.DUCT && it.fitting?.kind == FittingKind.REDUCER &&
            it.fitting?.section is RouteSection.Rectangular }
        val resized = adaptedFittingItem(rectangle, RouteSection.Rectangular(.8, .4), RouteSection.Rectangular(.5, .25))!!
        assertEquals(.5, number(resized, LibraryParameterKeys.OUTLET_WIDTH), 0.0)
        assertEquals(.25, number(resized, LibraryParameterKeys.OUTLET_HEIGHT), 0.0)
    }

    @Test fun `every inlet ring lies flush in the declared port plane with the exact route cross section`() {
        items.forEach { item ->
            val port = item.fitting!!.ports.first()
            val ring = (item.geometry.first() as Shape.Polyline).points
            ring.forEach { point ->
                val dot = (point.x - port.position.x) * port.direction.x +
                    (point.y - port.position.y) * port.direction.y + (point.z - port.position.z) * port.direction.z
                assertEquals("${item.name}: inlet plane", 0.0, dot, 1e-10)
            }
            val width = ring.maxOf { it.x } - ring.minOf { it.x }
            val height = ring.maxOf { it.z } - ring.minOf { it.z }
            when (val section = port.section) {
                is RouteSection.Rectangular -> {
                    assertEquals(item.name, section.width, width, 1e-10)
                    assertEquals(item.name, section.height, height, 1e-10)
                }
                is RouteSection.Circular -> {
                    assertEquals(item.name, section.diameter, width, 1e-10)
                    assertEquals(item.name, section.diameter, height, 1e-10)
                }
            }
        }
    }

    @Test fun `fitting physical geometry and symbols stay inside their exact editable size`() {
        items.forEach { item ->
            val points = item.geometry.flatMap(::points)
            assertEquals(item.name, 0.0, points.minOf { it.x }, 1e-10)
            assertEquals(item.name, 0.0, points.minOf { it.y }, 1e-10)
            assertEquals(item.name, 0.0, points.minOf { it.z }, 1e-10)
            assertEquals(item.name, item.defaultSize.width, points.maxOf { it.x }, 1e-10)
            assertEquals(item.name, item.defaultSize.depth, points.maxOf { it.y }, 1e-10)
            assertEquals(item.name, item.defaultSize.height, points.maxOf { it.z }, 1e-10)
            item.symbolGeometry.flatMap(::points).forEach { point ->
                assertTrue(item.name, point.x >= -1e-10 && point.x <= item.defaultSize.width + 1e-10)
                assertTrue(item.name, point.y >= -1e-10 && point.y <= item.defaultSize.depth + 1e-10)
            }
            val fitting = item.fitting!!
            fitting.ports.forEach { port ->
                assertTrue(port.position.x >= -1e-10 && port.position.x <= item.defaultSize.width + 1e-10)
                assertTrue(port.position.y >= -1e-10 && port.position.y <= item.defaultSize.depth + 1e-10)
                assertTrue(port.position.z >= -1e-10 && port.position.z <= item.defaultSize.height + 1e-10)
            }
        }
    }

    @Test fun `vertical fittings distinguish rise and drop and branches expose correct connection direction`() {
        val rise = items.first { it.fitting?.routeKind == RouteKind.TRUNKING && it.fitting?.kind == FittingKind.RISER_UP }.fitting!!
        val drop = items.first { it.fitting?.routeKind == RouteKind.TRUNKING && it.fitting?.kind == FittingKind.RISER_DOWN }.fitting!!
        assertTrue(rise.ports.last().position.z > rise.ports.first().position.z)
        assertTrue(drop.ports.last().position.z < drop.ports.first().position.z)
        val cross = items.first { it.fitting?.routeKind == RouteKind.PIPE && it.fitting?.kind == FittingKind.CROSS }.fitting!!
        assertEquals(setOf(Point3(y = -1.0), Point3(y = 1.0), Point3(x = -1.0), Point3(x = 1.0)), cross.ports.map { it.direction }.toSet())
    }

    @Test fun `sized fittings serialize losslessly and old library definitions keep null fitting metadata`() {
        val round = items.first { it.fitting?.routeKind == RouteKind.DUCT && it.fitting?.section is RouteSection.Circular }
        val fitted = adaptedFittingItem(round, RouteSection.Circular(.4))!!
        assertEquals(fitted, Json.decodeFromString<LibraryItem>(Json.encodeToString(fitted)))
        assertEquals(round, adaptedFittingItem(fitted, round.fitting!!.section))
        val legacy = Json.decodeFromString<LibraryItem>("""{"id":"old","name":"Old box","discipline":"ELECTRICAL",
            "category":"Custom","defaultSize":{"width":1,"depth":1,"height":1}}""")
        assertNull(legacy.fitting)
    }

    private fun number(item: LibraryItem, key: String) = (item.parameters.getValue(key) as ParameterValue.Number).value

    private fun points(shape: Shape): List<Point3> = when (shape) {
        is Shape.Line -> listOf(shape.start, shape.end)
        is Shape.Polyline -> shape.points
        is Shape.Extrusion -> shape.profile.outer.flatMap { listOf(Point3(it.x, it.y), Point3(it.x, it.y, shape.distance)) }
        is Shape.Mesh -> shape.vertices
        else -> error("Unexpected fitting shape ${shape::class}")
    }
}
