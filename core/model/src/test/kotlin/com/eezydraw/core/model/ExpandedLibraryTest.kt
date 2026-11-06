package com.eezydraw.core.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ExpandedLibraryTest {
    private val items = expandedLibraryItems()

    @Test fun `expanded catalogue contains every requested device plant fixture and architectural type`() {
        val expected = mapOf(
            Discipline.FIRE_PROTECTION to setOf("Smoke detector", "Heat detector", "Manual call point",
                "Fire alarm sounder", "Fire alarm panel", "Sprinkler", "Hose reel", "Fire extinguisher"),
            Discipline.HVAC to setOf("Air handling unit", "Fan coil unit", "Split AC indoor unit", "Split AC outdoor unit",
                "VAV box", "Exhaust fan", "Supply diffuser", "Return diffuser", "Grille", "Volume damper",
                "Fire damper", "Rectangular duct", "Round duct", "Flexible duct", "Chiller", "HVAC pump"),
            Discipline.PLUMBING to setOf("Gate valve", "Ball valve", "Check valve", "Plumbing pump", "Water tank",
                "Water heater", "Sink", "Basin", "Water closet", "Urinal", "Shower", "Floor drain", "Cleanout", "Manhole") +
                setOf(15, 20, 25, 32, 40, 50, 65, 80, 100, 150, 200).map { "Pipe DN$it" },
            Discipline.ARCHITECTURE to setOf("Single door", "Double door", "Sliding door", "Single window",
                "Double window", "Fixed window", "Stairs", "Ramp", "Architectural column", "Ceiling", "Floor",
                "Desk", "Chair", "Table", "Cabinet", "Counter"),
            Discipline.STRUCTURE to setOf("I-beam", "H-beam", "Rectangular beam", "Square column", "Round column",
                "Slab", "Footing", "Shear wall", "Opening", "Sleeve"),
        )
        assertEquals(expected.keys, items.map { it.discipline }.toSet())
        expected.forEach { (discipline, names) ->
            assertEquals(discipline.name, names, items.filter { it.discipline == discipline }.map { it.name }.toSet())
        }
        assertEquals(75, items.size)
    }

    @Test fun `stock items have stable unique ids SI sizes metadata and both representations`() {
        assertEquals(items, expandedLibraryItems())
        assertEquals(items.size, items.map { it.id }.toSet().size)
        items.forEach { item ->
            assertTrue(item.id.value, item.id.value.matches(Regex("eezy\\.[a-z_]+\\.[a-z0-9-]+")))
            assertTrue(item.builtIn)
            assertTrue(item.name, item.defaultSize.width > 0 && item.defaultSize.depth > 0 && item.defaultSize.height > 0)
            assertTrue(item.name, item.geometry.any { it is Shape.Extrusion || it is Shape.Mesh })
            assertTrue(item.name, item.symbolGeometry.isNotEmpty())
            assertTrue(item.name, item.defaultMaterial.isNotBlank())
            assertTrue(item.name, item.defaultSystem.isNotBlank())
            assertEquals(item.defaultMountingHeight,
                (item.defaultParameters().getValue(LibraryParameterKeys.MOUNTING_HEIGHT) as ParameterValue.Number).value, 0.0)
            assertEquals(item.name,
                (item.defaultParameters().getValue(LibraryParameterKeys.NAME_TAG) as ParameterValue.Text).value)
        }
    }

    @Test fun `mounting defaults distinguish ceiling devices wall devices and floor equipment`() {
        listOf("Smoke detector", "Heat detector", "Sprinkler", "Fan coil unit", "Supply diffuser",
            "Return diffuser", "VAV box", "Round duct", "Rectangular duct", "Flexible duct", "Ceiling").forEach { name ->
            assertEquals(name, MountingType.CEILING, item(name).mounting)
            assertEquals(name, 3.0, item(name).defaultMountingHeight, 0.0)
        }
        assertEquals(MountingType.WALL, item("Manual call point").mounting)
        assertEquals(1.4, item("Manual call point").defaultMountingHeight, 0.0)
        assertEquals(2.1, item("Split AC indoor unit").defaultMountingHeight, 0.0)
        assertEquals(.9, item("Fixed window").defaultMountingHeight, 0.0)
        listOf("Single door", "Double door", "Sliding door").forEach { name ->
            assertEquals(name, MountingType.WALL, item(name).mounting)
            assertEquals(name, 0.0, item(name).defaultMountingHeight, 0.0)
        }
        listOf("Air handling unit", "Chiller", "Plumbing pump", "Water closet", "Desk", "I-beam", "Manhole").forEach { name ->
            assertEquals(name, MountingType.FLOOR, item(name).mounting)
            assertEquals(name, 0.0, item(name).defaultMountingHeight, 0.0)
        }
    }

    @Test fun `pipe stock supplies distinct nominal bores outside diameters and open sections`() {
        val pipes = items.filter { it.name.startsWith("Pipe DN") }
        assertEquals(11, pipes.size)
        pipes.zipWithNext().forEach { (smaller, larger) ->
            assertTrue(smaller.defaultSize.width < larger.defaultSize.width)
        }
        pipes.forEach { pipe ->
            val dn = (pipe.parameters.getValue("nominalDiameter") as ParameterValue.Number)
            val outside = pipe.parameters.getValue("outsideDiameter") as ParameterValue.Number
            assertEquals(Quantity.LENGTH, dn.quantity)
            assertEquals(Quantity.LENGTH, outside.quantity)
            assertTrue(outside.value > dn.value)
            assertEquals(outside.value, pipe.defaultSize.width, 0.0)
            assertEquals(outside.value, pipe.defaultSize.depth, 0.0)
            assertEquals(1.0, pipe.defaultSize.height, 0.0)
            val extrusion = pipe.geometry.single() as Shape.Extrusion
            assertEquals(1, extrusion.profile.holes.size)
            assertEquals(24, extrusion.profile.outer.size)
        }
    }

    @Test fun `profiles and detailed symbols distinguish stock families instead of using generic boxes`() {
        listOf("I-beam", "H-beam").forEach { name ->
            val extrusion = item(name).geometry.single() as Shape.Extrusion
            assertEquals(12, extrusion.profile.outer.size)
        }
        assertNotEquals(item("I-beam").geometry, item("H-beam").geometry)
        assertEquals(10, item("Stairs").geometry.filterIsInstance<Shape.Extrusion>().size)
        val ramp = item("Ramp")
        val rampMesh = ramp.geometry.single() as Shape.Mesh
        assertEquals(6, rampMesh.vertices.size)
        assertEquals(24, rampMesh.triangles.size)
        assertEquals(Point3(ramp.defaultSize.width, ramp.defaultSize.depth, ramp.defaultSize.height), rampMesh.vertices[4])
        assertEquals(Point3(0.0, ramp.defaultSize.depth, ramp.defaultSize.height), rampMesh.vertices[5])
        assertTrue((item("Basin").geometry.first() as Shape.Extrusion).profile.holes.isNotEmpty())
        assertTrue((item("Manhole").geometry.first() as Shape.Extrusion).profile.holes.isNotEmpty())
        assertNotEquals(item("Supply diffuser").symbolGeometry, item("Return diffuser").symbolGeometry)
        assertNotEquals(item("Single door").symbolGeometry, item("Sliding door").symbolGeometry)
        assertNotEquals(item("Gate valve").symbolGeometry, item("Check valve").symbolGeometry)
        assertTrue(item("Flexible duct").geometry.filterIsInstance<Shape.Polyline>().size >= 10)
    }

    @Test fun `ramp and elevated furniture parts are closed filled solids with outward triangles`() {
        val ramp = item("Ramp")
        val mesh = ramp.geometry.single() as Shape.Mesh
        val size = ramp.defaultSize
        assertEquals(size.width * size.depth * size.height / 2, meshVolume(mesh), 1e-12)
        listOf("Ramp", "Table", "Desk", "Chair", "Opening", "Rectangular duct").forEach { name ->
            val meshes = item(name).geometry.filterIsInstance<Shape.Mesh>()
            assertTrue(name, meshes.isNotEmpty())
            meshes.forEach { solid ->
                assertTrue(name, meshVolume(solid) > 0)
                val edges = solid.triangles.chunked(3).flatMap { triangle ->
                    listOf(triangle[0] to triangle[1], triangle[1] to triangle[2], triangle[2] to triangle[0])
                }
                edges.forEach { edge ->
                    assertEquals("$name must have two faces at each edge", 1, edges.count { it.first == edge.second && it.second == edge.first })
                }
            }
        }
        val table = item("Table")
        val top = table.geometry.filterIsInstance<Shape.Mesh>().single()
        assertTrue(top.vertices.all { it.z >= table.defaultSize.height * .92 })
        assertEquals(table.defaultSize.height, top.vertices.maxOf { it.z }, 0.0)
        assertEquals(2, item("Chair").geometry.filterIsInstance<Shape.Mesh>().size)
    }

    @Test fun `new catalogue is searchable across disciplines categories and service metadata`() {
        assertEquals(listOf("Smoke detector"), librarySearch(items, "SMOKE safety").map { it.name })
        assertEquals(listOf("Pipe DN100"), librarySearch(items, "plumbing DN100").map { it.name })
        assertEquals(listOf("Split AC indoor unit", "Split AC outdoor unit"), librarySearch(items, "split ac").map { it.name })
        assertEquals(listOf("Opening", "Sleeve"), librarySearch(items, "coordination").map { it.name })
        assertEquals(listOf("Floor drain"), librarySearch(items, "floor sanitary drain").map { it.name })
        assertTrue(librarySearch(items, "detector structure").isEmpty())
    }

    @Test fun `expanded definitions parameters and browsing ids survive serialization`() {
        val selected = listOf(item("Smoke detector"), item("Pipe DN100"), item("Single door"), item("I-beam"), item("Ramp"), item("Table"))
        selected.forEach { original ->
            assertEquals(original, Json.decodeFromString<LibraryItem>(Json.encodeToString(original)))
            val changed = original.copy(defaultSize = Size3(.8, .6, .4), defaultMaterial = "Project material")
            assertEquals(changed, Json.decodeFromString<LibraryItem>(Json.encodeToString(changed)))
            assertNotEquals(changed, item(original.name))
        }
        val ids = selected.map { it.id }
        val state = LibraryState(favoriteIds = ids.take(2).toSet(), recentIds = ids, viewMode = LibraryViewMode.LIST)
        assertEquals(state, Json.decodeFromString<LibraryState>(Json.encodeToString(state)))
    }

    private fun item(name: String) = items.single { it.name == name }

    /** Signed tetrahedral volume also detects accidental inward triangle winding. */
    private fun meshVolume(mesh: Shape.Mesh): Double = mesh.triangles.chunked(3).sumOf { triangle ->
        val a = mesh.vertices[triangle[0]]
        val b = mesh.vertices[triangle[1]]
        val c = mesh.vertices[triangle[2]]
        (a.x * (b.y * c.z - b.z * c.y) - a.y * (b.x * c.z - b.z * c.x) +
            a.z * (b.x * c.y - b.y * c.x)) / 6
    }
}
