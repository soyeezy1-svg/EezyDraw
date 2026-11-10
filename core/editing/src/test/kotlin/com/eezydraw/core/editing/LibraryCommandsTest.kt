package com.eezydraw.core.editing

import com.eezydraw.core.geometry.buildDrawingScene
import com.eezydraw.core.geometry.buildSurfaceScene
import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test

class LibraryCommandsTest {
    private val project = ProjectId("project")
    private val layer = LayerId("drawing")
    private val firstLevel = Level(LevelId("first"), project, "First", 0.0, 0.2, 3.5)
    private val secondLevel = firstLevel.copy(id = LevelId("second"), name = "Second", elevation = 4.0)
    private val item = LibraryItem(LibraryItemId("rack"), "Rack", Discipline.ICT, "ICT",
        Size3(0.6, 0.8, 2.0), geometry = listOf(Shape.Box(Size3(0.6, 0.8, 2.0))),
        defaultSystem = "ICT", defaultMaterial = "Steel", defaultColourArgb = 0xFF223344)
    private fun document() = ProjectDocument(Project(project, "Library edits", 0, 0),
        levels = listOf(firstLevel, secondLevel), layers = listOf(Layer(layer, project, "Drawing")))
    private fun insert(doc: ProjectDocument, id: String, point: Point3 = Point3(), definition: LibraryItem = item,
        level: Level = firstLevel): ProjectDocument {
        val candidate = previewLibraryPlacement(doc, definition, point, level.id, layer, ElementId(id))
        return LibraryCommands.place(doc, candidate).preview(doc)
    }

    @Test fun `placement carries the definition material and system as one reversible edit`() {
        val doc = document()
        val candidate = previewLibraryPlacement(doc, item, Point3(2.0, 3.0), firstLevel.id, layer, ElementId("rack"))
        assertTrue(doc.libraryItems.isEmpty())
        assertTrue(doc.elements.isEmpty())
        val history = EditHistory()
        val result = history.execute(doc, LibraryCommands.place(doc, candidate))
        assertEquals(listOf(item), result.libraryItems)
        assertEquals(1, result.elements.size)
        assertEquals("ICT", result.systems.single().name)
        assertEquals("Steel", result.materials.single().name)
        assertEquals(result.systems.single().id, result.elements.single().systemId)
        assertEquals(result.materials.single().id, result.elements.single().materialId)
        assertEquals(1, history.undoCount)
        assertEquals(doc, history.undo(result))
        assertEquals(result, history.redo(doc))
    }

    @Test fun `placement rejects a hidden target system in both preview and commit`() {
        val system = BuildingSystem(SystemId("ict"), project, "ict", Discipline.ICT, 0xFF223344)
        val doc = document().copy(systems = listOf(system))
        val hidden = doc.copy(systems = listOf(system.copy(visible = false)))
        val previewFailure = assertThrows(IllegalArgumentException::class.java) {
            previewLibraryPlacement(hidden, item, Point3(), firstLevel.id, layer, ElementId("rack"))
        }
        assertTrue(previewFailure.message.orEmpty().contains("system is hidden"))
        val candidate = previewLibraryPlacement(doc, item, Point3(), firstLevel.id, layer, ElementId("rack"))
        val commitFailure = assertThrows(IllegalArgumentException::class.java) { LibraryCommands.place(hidden, candidate) }
        assertTrue(commitFailure.message.orEmpty().contains("system is hidden"))
        assertTrue(hidden.elements.isEmpty())
        assertTrue(hidden.libraryItems.isEmpty())
    }

    @Test fun `update all includes hidden matching instances while object locks still guard edits`() {
        val placed = insert(insert(document(), "first"), "second", Point3(5.0))
        val hidden = placed.copy(systems = placed.systems.map { it.copy(visible = false) })
        val properties = readLibraryProperties(hidden, hidden.elements.first()).copy(size = Size3(1.0, 1.0, 1.0))
        val result = LibraryCommands.edit(hidden, hidden.elements.first().id, properties, updateAll = true).preview(hidden)
        assertTrue(result.elements.all { readLibraryProperties(result, it).size == properties.size })
        assertFalse(result.systems.single().visible)
        val locked = hidden.copy(elements = hidden.elements.mapIndexed { index, element ->
            if (index == 1) element.withLock(LockMode.ALL) else element
        })
        assertThrows(IllegalArgumentException::class.java) {
            LibraryCommands.edit(locked, locked.elements.first().id, properties, updateAll = true)
        }
    }

    @Test fun `favorites browsing after placement survives undo and redo`() {
        val doc = document()
        val candidate = previewLibraryPlacement(doc, item, Point3(), firstLevel.id, layer, ElementId("rack"))
        val history = EditHistory()
        val result = history.execute(doc, LibraryCommands.place(doc, candidate))
        val browsing = LibraryState(setOf(item.id), listOf(item.id), LibraryViewMode.LIST)
        val browsed = result.copy(libraryState = browsing)
        val undone = history.undo(browsed)
        assertEquals(browsing, undone.libraryState)
        assertTrue(undone.elements.isEmpty())
        assertTrue(undone.libraryItems.isEmpty())
        val redone = history.redo(undone)
        assertEquals(browsing, redone.libraryState)
        assertEquals(result.elements, redone.elements)
    }

    @Test fun `instance parameters resize real lines and surfaces without modifying definition or sibling`() {
        val doc = insert(insert(document(), "first"), "second", Point3(5.0))
        val source = doc.elements.first()
        val properties = readLibraryProperties(doc, source).copy(size = Size3(1.2, 1.6, 4.0), mountingHeight = 0.5,
            manufacturer = "Acme", model = "R-42", nameTag = "ICT-01", material = "Aluminium", system = "Power", colourArgb = 0xFFAA5522)
        val result = LibraryCommands.edit(doc, source.id, properties).preview(doc)
        val edited = result.elements.first()
        assertEquals(properties, readLibraryProperties(result, edited))
        assertEquals(doc.elements.last(), result.elements.last())
        assertEquals(doc.libraryItems, result.libraryItems)
        assertEquals("ICT-01", edited.name)
        assertEquals(0xFFAA5522, edited.colourOverride)
        val isolated = result.copy(elements = listOf(edited))
        val lines = buildDrawingScene(isolated).bounds
        val faces = buildSurfaceScene(isolated).bounds
        assertEquals(1.2, lines.max.x - lines.min.x, 1e-9)
        assertEquals(1.6, lines.max.y - lines.min.y, 1e-9)
        assertEquals(4.0, lines.max.z - lines.min.z, 1e-9)
        assertEquals(lines, faces)
    }

    @Test fun `legacy metadata edits retain rendered colour with normal override precedence`() {
        val layerColour = 0xFF2255CCL
        val definitionMaterial = Material(MaterialId("definition"), "Definition material", 0xFFCC7722)
        val instanceMaterial = Material(MaterialId("instance"), "Instance material", 0xFF22AA55)
        val system = BuildingSystem(SystemId("system"), project, "System", Discipline.ICT, 0xFFCC2244)
        val legacy = item.copy(defaultMaterial = "", defaultSystem = "")
        val component = Element(ElementId("legacy"), project, layer,
            ElementBody.Component(legacy.id, legacy.defaultSize), levelId = firstLevel.id)
        val base = document().copy(libraryItems = listOf(legacy), elements = listOf(component),
            layers = listOf(Layer(layer, project, "Blue", colourArgb = layerColour)),
            materials = listOf(definitionMaterial, instanceMaterial), systems = listOf(system))
        val cases = listOf(
            base to layerColour,
            base.copy(libraryItems = listOf(legacy.copy(materialId = definitionMaterial.id))) to definitionMaterial.colourArgb,
            base.copy(libraryItems = listOf(legacy.copy(materialId = definitionMaterial.id)),
                elements = listOf(component.copy(materialId = instanceMaterial.id))) to instanceMaterial.colourArgb,
            base.copy(elements = listOf(component.copy(materialId = instanceMaterial.id, systemId = system.id))) to instanceMaterial.colourArgb,
            base.copy(elements = listOf(component.copy(systemId = system.id))) to system.colourArgb,
            base.copy(elements = listOf(component.copy(systemId = system.id,
                properties = mapOf(ELEMENT_COLOUR_KEY to ParameterValue.Colour(0xFF8833AA))))) to 0xFF8833AAL,
        )
        for ((doc, expectedColour) in cases) {
            val source = doc.elements.single()
            assertEquals(setOf(expectedColour), buildDrawingScene(doc).segments.map { it.colorArgb }.toSet())
            val properties = readLibraryProperties(doc, source)
            assertEquals(expectedColour, properties.colourArgb)
            if (doc.libraryItems.single().materialId == definitionMaterial.id && source.materialId == null)
                assertEquals(definitionMaterial.name, properties.material)
            val edited = LibraryCommands.edit(doc, source.id, properties.copy(manufacturer = "Updated maker")).preview(doc)
            assertEquals(setOf(expectedColour), buildDrawingScene(edited).segments.map { it.colorArgb }.toSet())
            assertEquals("Updated maker", readLibraryProperties(edited, edited.elements.single()).manufacturer)
        }
    }

    @Test fun `instance physical dimensions include generic handle scales and preserve mirror handedness when edited`() {
        val doc = insert(document(), "rack")
        val element = doc.elements.single().copy(transform = Transform3(Point3(10.0, 20.0, 1.2), Rotation3(z = 0.5), Scale3(-2.0, 3.0, 4.0)))
        val scaled = doc.copy(elements = listOf(element))
        val properties = readLibraryProperties(scaled, element)
        assertEquals(1.2, properties.size.width, 1e-9)
        assertEquals(2.4, properties.size.depth, 1e-9)
        assertEquals(8.0, properties.size.height, 1e-9)
        assertEquals(1.2, properties.mountingHeight, 0.0)
        val changed = LibraryCommands.edit(scaled, element.id, properties.copy(size = Size3(1.0, 2.0, 3.0))).preview(scaled)
        assertEquals(Scale3(-1.0, 1.0, 1.0), changed.elements.single().transform.scale)
        assertEquals(element.transform.rotation, changed.elements.single().transform.rotation)
        assertEquals(Size3(1.0, 2.0, 3.0), readLibraryProperties(changed, changed.elements.single()).size)
    }

    @Test fun `update all targets this definition across levels and keeps sibling XY rotation and unrelated types`() {
        var doc = insert(insert(document(), "first", Point3(1.0, 2.0)), "second", Point3(5.0, 6.0), level = secondLevel)
        doc = insert(doc, "other", Point3(10.0), item.copy(id = LibraryItemId("other"), name = "Other"))
        val sibling = doc.elements[1].copy(transform = doc.elements[1].transform.copy(rotation = Rotation3(z = 0.8)))
        doc = doc.copy(elements = doc.elements.map { if (it.id == sibling.id) sibling else it })
        val source = doc.elements.first()
        val properties = readLibraryProperties(doc, source).copy(size = Size3(0.7, 0.9, 1.5), mountingHeight = 1.2, nameTag = "Equipment")
        val history = EditHistory()
        val result = history.execute(doc, LibraryCommands.edit(doc, source.id, properties,
            ElementProperties(Point3(3.0, 4.0), Rotation3(z = 0.4), Point3(0.7, 0.9, 1.5)), updateAll = true))
        assertEquals(Point3(3.0, 4.0, 1.2), result.elements[0].transform.translation)
        assertEquals(Point3(5.0, 6.0, 5.2), result.elements[1].transform.translation)
        assertEquals(sibling.transform.rotation, result.elements[1].transform.rotation)
        assertEquals(properties.size, readLibraryProperties(result, result.elements[1]).size)
        assertEquals(doc.elements.last(), result.elements.last())
        assertEquals(1, history.undoCount)
        assertEquals(doc, history.undo(result))
        assertEquals(result, history.redo(doc))
    }

    @Test fun `mounting edit wins over unchanged transform Z and direct Z edits update AFFL metadata`() {
        val doc = insert(document(), "rack")
        val source = doc.elements.single()
        val oldProperties = readProperties(doc, source)
        val properties = readLibraryProperties(doc, source)
        val mounted = LibraryCommands.edit(doc, source.id, properties.copy(mountingHeight = 1.2), oldProperties).preview(doc)
        assertEquals(1.2, mounted.elements.single().transform.translation.z, 0.0)
        val moved = LibraryCommands.edit(doc, source.id, properties, oldProperties.copy(position = Point3(z = 2.0))).preview(doc)
        assertEquals(2.0, readLibraryProperties(moved, moved.elements.single()).mountingHeight, 0.0)
        assertEquals(2.0, ((moved.elements.single().body as ElementBody.Component).parameters[LibraryParameterKeys.MOUNTING_HEIGHT] as ParameterValue.Number).value, 0.0)
    }

    @Test fun `reset restores instance defaults and preserves other copies and orientation`() {
        val doc = insert(insert(document(), "first", Point3(2.0, 3.0)), "second", Point3(5.0))
        val source = doc.elements.first()
        val properties = readLibraryProperties(doc, source)
        val edited = LibraryCommands.edit(doc, source.id, properties.copy(size = Size3(2.0, 2.0, 2.0), mountingHeight = 1.5,
            manufacturer = "Changed", model = "Changed", material = "Wood", system = "Power", nameTag = "Changed"),
            readProperties(doc, source).copy(rotation = Rotation3(z = 0.7))).preview(doc)
        val reset = LibraryCommands.reset(edited, source.id).preview(edited)
        assertEquals(properties, readLibraryProperties(reset, reset.elements.first()))
        assertEquals(Point3(2.0, 3.0), reset.elements.first().transform.translation)
        assertEquals(0.7, reset.elements.first().transform.rotation.z, 0.0)
        assertEquals(doc.elements.last(), reset.elements.last())
        assertEquals(doc.libraryItems, reset.libraryItems)
    }

    @Test fun `reset also removes instance custom values and restores library custom defaults`() {
        val custom = item.copy(parameters = mapOf("rating" to ParameterValue.Number(20.0)))
        val placed = insert(document(), "first", definition = custom)
        val source = placed.elements.single()
        val body = source.body as ElementBody.Component
        val overridden = placed.copy(elements = listOf(source.copy(body = body.copy(parameters = body.parameters + mapOf(
            "rating" to ParameterValue.Number(32.0), "instanceOnly" to ParameterValue.Text("custom"),
        )))))
        val reset = LibraryCommands.reset(overridden, source.id).preview(overridden)
        val params = (reset.elements.single().body as ElementBody.Component).parameters
        assertEquals(ParameterValue.Number(20.0), params["rating"])
        assertFalse("instanceOnly" in params)
    }

    @Test fun `reset resolves legacy default material IDs when material text is empty`() {
        val material = Material(MaterialId("default"), "Library steel", 0xFF223344)
        val legacy = item.copy(materialId = material.id, defaultMaterial = "")
        val original = document().copy(materials = listOf(material))
        val placed = insert(original, "first", definition = legacy)
        val source = placed.elements.single()
        assertEquals(material.id, source.materialId)
        val edited = LibraryCommands.edit(placed, source.id,
            readLibraryProperties(placed, source).copy(material = "Aluminium")).preview(placed)
        val reset = LibraryCommands.reset(edited, source.id).preview(edited)
        assertEquals(material.id, reset.elements.single().materialId)
        assertEquals(material.name, readLibraryProperties(reset, reset.elements.single()).material)
    }

    @Test fun `mounting height edits update host local position along with rendered insertion`() {
        val host = Element(ElementId("wall"), project, layer,
            ElementBody.Wall(Point2(0.0, 0.0), Point2(10.0, 0.0), 0.2, 3.5), levelId = firstLevel.id)
        val original = document().copy(elements = listOf(host))
        val doc = insert(original, "mounted", Point3(3.0, 2.0), item.copy(mounting = MountingType.WALL, defaultMountingHeight = 1.2))
        val source = doc.elements.last()
        val result = LibraryCommands.edit(doc, source.id, readLibraryProperties(doc, source).copy(mountingHeight = 1.3)).preview(doc)
        val edited = result.elements.last()
        assertEquals(1.3, edited.transform.translation.z, 1e-9)
        assertEquals(1.3, (edited.body as ElementBody.Component).host!!.localPosition.z, 1e-9)
    }

    @Test fun `locked instance rejects update all atomically and position lock permits size metadata reset`() {
        val doc = insert(insert(document(), "first"), "second", Point3(5.0))
        val source = doc.elements.first()
        val properties = readLibraryProperties(doc, source).copy(size = Size3(1.0, 1.0, 1.0))
        val locked = doc.copy(elements = listOf(source, doc.elements.last().withLock(LockMode.ALL)))
        assertThrows(IllegalArgumentException::class.java) { LibraryCommands.edit(locked, source.id, properties, updateAll = true) }
        val positionLocked = source.copy(transform = source.transform.copy(translation = Point3(2.0, 3.0, 1.5))).withLock(LockMode.POSITION_ONLY)
        val posDoc = doc.copy(elements = listOf(positionLocked, doc.elements.last()))
        val posProperties = readLibraryProperties(posDoc, positionLocked).copy(size = Size3(1.0, 1.0, 1.0))
        val resized = LibraryCommands.edit(posDoc, source.id, posProperties).preview(posDoc)
        val reset = LibraryCommands.reset(resized, source.id).preview(resized)
        assertEquals(positionLocked.transform.translation, reset.elements.first().transform.translation)
        assertEquals(item.defaultSize, readLibraryProperties(reset, reset.elements.first()).size)
        assertThrows(IllegalArgumentException::class.java) {
            LibraryCommands.edit(posDoc, source.id, posProperties.copy(mountingHeight = 1.6))
        }
    }

    @Test fun `ceiling resize and reset preserve hanging orientation and mount plane`() {
        val ceilingItem = item.copy(mounting = MountingType.CEILING, defaultSize = Size3(0.3, 0.3, 0.05))
        val doc = insert(document(), "light", definition = ceilingItem)
        val source = doc.elements.single()
        val changed = LibraryCommands.edit(doc, source.id, readLibraryProperties(doc, source).copy(size = Size3(0.6, 0.6, 0.1))).preview(doc)
        assertEquals(-1.0, changed.elements.single().transform.scale.z, 0.0)
        assertEquals(3.3, changed.elements.single().transform.translation.z, 1e-9)
        val reset = LibraryCommands.reset(changed, source.id).preview(changed)
        assertEquals(ceilingItem.defaultSize, readLibraryProperties(reset, reset.elements.single()).size)
        assertEquals(-1.0, reset.elements.single().transform.scale.z, 0.0)
    }

    @Test fun `invalid heights sizes and colours reject before creating a command`() {
        assertThrows(IllegalArgumentException::class.java) { Size3(0.0, 1.0, 1.0) }
        val doc = insert(document(), "rack")
        val properties = readLibraryProperties(doc, doc.elements.single())
        assertThrows(IllegalArgumentException::class.java) { properties.copy(mountingHeight = -1.0) }
        assertThrows(IllegalArgumentException::class.java) { properties.copy(colourArgb = 0x100000000) }
    }
}
