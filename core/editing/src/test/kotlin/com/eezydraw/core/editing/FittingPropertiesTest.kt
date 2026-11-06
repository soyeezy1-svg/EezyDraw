package com.eezydraw.core.editing

import com.eezydraw.core.geometry.buildDrawingScene
import com.eezydraw.core.geometry.buildSurfaceScene
import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test

class FittingPropertiesTest {
    private val project = ProjectId("fitting-project")
    private val layer = LayerId("fittings")
    private val level = Level(LevelId("floor"), project, "Floor", 0.0, .2, 3.0)
    private fun fitting(kind: FittingKind, circular: Boolean) = fittingLibraryItems().first {
        it.fitting?.kind == kind && (it.fitting?.section is RouteSection.Circular) == circular
    }
    private fun element(item: LibraryItem, id: String, x: Double = 1.0) = Element(
        ElementId(id), project, layer, ElementBody.Component(item.id, item.defaultSize, item.defaultParameters()),
        transform = Transform3(Point3(x, 2.0, 1.0), Rotation3(z = .4)), levelId = level.id,
    )
    private fun document(items: List<LibraryItem>, elements: List<Element>) = ProjectDocument(
        Project(project, "Fitting properties", 0, 0), levels = listOf(level),
        layers = listOf(Layer(layer, project, "Fittings")), libraryItems = items, elements = elements,
    )
    private fun point(expected: Point3, actual: Point3) {
        assertEquals(expected.x, actual.x, 1e-9)
        assertEquals(expected.y, actual.y, 1e-9)
        assertEquals(expected.z, actual.z, 1e-9)
    }

    @Test fun `reducer connection edits regenerate geometry and both ports for one instance with atomic undo`() {
        val item = fitting(FittingKind.REDUCER, true)
        val first = element(item, "first")
        val sibling = element(item, "sibling", 5.0)
        val doc = document(listOf(item), listOf(first, sibling))
        val anchor = requireNotNull(libraryFittingConnectionPoint(item, first))
        val properties = readLibraryProperties(doc, first).copy(section = RouteSection.Circular(.2),
            outletSection = RouteSection.Circular(.1), manufacturer = "Acme")
        val history = EditHistory()
        val edited = history.execute(doc, LibraryCommands.edit(doc, first.id, properties, readProperties(doc, first)))
        val changed = edited.elements.first()
        val body = changed.body as ElementBody.Component
        val definition = edited.libraryItems.single { it.id == body.libraryItemId }
        assertNotEquals(item.id, definition.id)
        assertNotEquals(item.geometry, definition.geometry)
        assertEquals(RouteSection.Circular(.2), definition.fitting!!.ports.first().section)
        assertEquals(RouteSection.Circular(.1), definition.fitting!!.ports.last().section)
        assertEquals(definition.defaultSize, body.size)
        assertEquals(RouteSection.Circular(.2), readLibraryProperties(edited, changed).section)
        assertEquals("Acme", readLibraryProperties(edited, changed).manufacturer)
        point(anchor, requireNotNull(libraryFittingConnectionPoint(definition, changed)))
        assertEquals(sibling, edited.elements.last())
        assertEquals(doc, history.undo(edited))
        assertEquals(edited, history.redo(doc))
    }

    @Test fun `update all matches fitting base type across section variants and keeps each connection anchor`() {
        val item = fitting(FittingKind.ELBOW_45, false)
        val variant = requireNotNull(adaptedFittingItem(item, RouteSection.Rectangular(.4, .2)))
        val other = fitting(FittingKind.TEE, false)
        val first = element(item, "first")
        val second = element(variant, "second", 5.0)
        val unrelated = element(other, "other", 9.0)
        val doc = document(listOf(item, variant, other).distinctBy { it.id }, listOf(first, second, unrelated))
        val anchors = listOf(item to first, variant to second).map { (definition, component) ->
            requireNotNull(libraryFittingConnectionPoint(definition, component))
        }
        val properties = readLibraryProperties(doc, first).copy(section = RouteSection.Rectangular(.6, .3))
        val edited = LibraryCommands.edit(doc, first.id, properties, updateAll = true).preview(doc)
        edited.elements.take(2).forEachIndexed { index, component ->
            val definition = edited.libraryItems.single { it.id == (component.body as ElementBody.Component).libraryItemId }
            assertEquals(properties.section, definition.fitting!!.section)
            val anchor = requireNotNull(libraryFittingConnectionPoint(definition, component))
            assertEquals(anchors[index].x, anchor.x, 1e-9)
            assertEquals(anchors[index].y, anchor.y, 1e-9)
            assertEquals(properties.mountingHeight, anchor.z, 1e-9)
        }
        assertEquals(unrelated, edited.elements.last())
        val locked = doc.copy(elements = listOf(first, second.withLock(LockMode.ALL), unrelated))
        assertThrows(IllegalArgumentException::class.java) { LibraryCommands.edit(locked, first.id, properties, updateAll = true) }
    }

    @Test fun `reset restores canonical connection sizes and preserves a below floor input anchor`() {
        val item = fitting(FittingKind.REDUCER, true)
        val original = element(item, "first")
        val anchor = requireNotNull(libraryFittingConnectionPoint(item, original))
        val route = Element(ElementId("route"), project, layer, ElementBody.Route(item.fitting!!.routeKind,
            listOf(anchor.copy(y = anchor.y - 2.0), anchor), item.fitting!!.section,
            ElevationSetting(anchor.z, ElevationReference.PROJECT_DATUM)), levelId = level.id)
        val body = original.body as ElementBody.Component
        val bindings = mapOf(LibraryConnectionParameterKeys.ENDPOINT to ParameterValue.Text("END"),
            LibraryConnectionParameterKeys.PORT to ParameterValue.Text("input"))
        val first = original.copy(body = body.copy(host = HostAttachment(route.id, HostMode.SURFACE, anchor),
            parameters = body.parameters + bindings + ("customExtra" to ParameterValue.Text("remove on reset"))))
        val doc = document(listOf(item), listOf(route, first))
        val edited = LibraryCommands.edit(doc, first.id, readLibraryProperties(doc, first).copy(
            section = RouteSection.Circular(.25), outletSection = RouteSection.Circular(.15), mountingHeight = -.4,
        )).preview(doc)
        assertEquals(-.4, readLibraryProperties(edited, edited.elements.last()).mountingHeight, 1e-9)
        val attachment = requireNotNull((edited.elements.last().body as ElementBody.Component).host)
        val reset = LibraryCommands.reset(edited, first.id).preview(edited)
        val resetBody = reset.elements.last().body as ElementBody.Component
        val properties = readLibraryProperties(reset, reset.elements.last())
        assertEquals(item.fitting!!.section, properties.section)
        assertEquals(item.fitting!!.outletSection, properties.outletSection)
        assertEquals(-.4, properties.mountingHeight, 1e-9)
        val definition = reset.libraryItems.single { it.id == resetBody.libraryItemId }
        assertEquals(definition.defaultSize, properties.size)
        bindings.forEach { (key, value) -> assertEquals(value, resetBody.parameters[key]) }
        assertEquals(attachment.hostElementId, resetBody.host!!.hostElementId)
        point(attachment.localPosition, resetBody.host!!.localPosition)
        assertFalse("customExtra" in resetBody.parameters)
    }

    @Test fun `fitting rejects envelope distortion and incompatible connection profiles`() {
        val item = fitting(FittingKind.ELBOW_45, true)
        val first = element(item, "first")
        val doc = document(listOf(item), listOf(first))
        val properties = readLibraryProperties(doc, first)
        assertThrows(IllegalArgumentException::class.java) {
            LibraryCommands.edit(doc, first.id, properties.copy(size = Size3(2.0, 3.0, 4.0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LibraryCommands.edit(doc, first.id, properties.copy(section = RouteSection.Rectangular(.2, .1)))
        }
    }

    @Test fun `uniformly scaled circular reducer reads actual diameters and metadata apply preserves them`() {
        val item = fitting(FittingKind.REDUCER, true)
        val source = element(item, "scaled").copy(transform = Transform3(Point3(1.0, 2.0, 1.0),
            Rotation3(z = .4), Scale3(-2.0, 2.0, 2.0)))
        val doc = document(listOf(item), listOf(source))
        val anchor = requireNotNull(libraryFittingConnectionPoint(item, source))
        val properties = readLibraryProperties(doc, source)
        assertEquals(RouteSection.Circular((item.fitting!!.section as RouteSection.Circular).diameter * 2), properties.section)
        assertEquals(RouteSection.Circular((item.fitting!!.outletSection as RouteSection.Circular).diameter * 2), properties.outletSection)
        assertTrue(properties.connectionProfileRepresentable)
        val edited = LibraryCommands.edit(doc, source.id, properties.copy(manufacturer = "Scaled maker"),
            readProperties(doc, source)).preview(doc)
        val changed = edited.elements.single()
        val definition = edited.libraryItems.single { it.id == (changed.body as ElementBody.Component).libraryItemId }
        assertEquals(doc.libraryItems, edited.libraryItems)
        assertEquals(properties.size, (changed.body as ElementBody.Component).size)
        assertEquals(properties.section, readLibraryProperties(edited, changed).section)
        assertEquals(properties.outletSection, readLibraryProperties(edited, changed).outletSection)
        assertEquals(Scale3(-1.0, 1.0, 1.0), changed.transform.scale)
        point(anchor, requireNotNull(libraryFittingConnectionPoint(definition, changed)))
    }

    @Test fun `rectangular body and transform scales read effective inlet and outlet sizes during metadata apply`() {
        val item = fitting(FittingKind.REDUCER, false)
        val body = ElementBody.Component(item.id, Size3(item.defaultSize.width * 1.25,
            item.defaultSize.depth, item.defaultSize.height * .5), item.defaultParameters())
        val source = element(item, "scaled").copy(body = body, transform = Transform3(Point3(1.0, 2.0, 1.0),
            Rotation3(z = .4), Scale3(-2.0, 1.5, 3.0)))
        val doc = document(listOf(item), listOf(source))
        val anchor = requireNotNull(libraryFittingConnectionPoint(item, source))
        val properties = readLibraryProperties(doc, source)
        val input = item.fitting!!.section as RouteSection.Rectangular
        val outlet = item.fitting!!.outletSection as RouteSection.Rectangular
        assertEquals(input.width * 2.5, (properties.section as RouteSection.Rectangular).width, 1e-12)
        assertEquals(input.height * 1.5, (properties.section as RouteSection.Rectangular).height, 1e-12)
        assertEquals(outlet.width * 2.5, (properties.outletSection as RouteSection.Rectangular).width, 1e-12)
        assertEquals(outlet.height * 1.5, (properties.outletSection as RouteSection.Rectangular).height, 1e-12)
        val edited = LibraryCommands.edit(doc, source.id, properties.copy(model = "Scaled model"),
            readProperties(doc, source)).preview(doc)
        val changed = edited.elements.single()
        val definition = edited.libraryItems.single { it.id == (changed.body as ElementBody.Component).libraryItemId }
        assertEquals(doc.libraryItems, edited.libraryItems)
        assertEquals(properties.size, (changed.body as ElementBody.Component).size)
        assertEquals(properties.section, readLibraryProperties(edited, changed).section)
        assertEquals(properties.outletSection, readLibraryProperties(edited, changed).outletSection)
        point(anchor, requireNotNull(libraryFittingConnectionPoint(definition, changed)))
    }

    @Test fun `elliptical circular scaling requires an explicit profile edit before metadata apply`() {
        val item = fitting(FittingKind.ELBOW_45, true)
        val source = element(item, "elliptical").copy(transform = Transform3(Point3(1.0, 2.0, 1.0),
            scale = Scale3(2.0, 1.0, 3.0)))
        val doc = document(listOf(item), listOf(source))
        val properties = readLibraryProperties(doc, source)
        assertFalse(properties.connectionProfileRepresentable)
        assertThrows(IllegalArgumentException::class.java) {
            LibraryCommands.edit(doc, source.id, properties.copy(manufacturer = "Changed"))
        }
        val edited = LibraryCommands.edit(doc, source.id, properties.copy(section = RouteSection.Circular(.12))).preview(doc)
        val changed = edited.elements.single()
        assertTrue(readLibraryProperties(edited, changed).connectionProfileRepresentable)
        assertEquals(RouteSection.Circular(.12), readLibraryProperties(edited, changed).section)
    }

    @Test fun `manufacturer only edit preserves rectangular XYZ scale and all wire solid and port geometry`() {
        val item = fitting(FittingKind.TRAPEZE, false)
        val source = element(item, "scaled-accessory").copy(transform = Transform3(Point3(1.0, 2.0, 1.0),
            Rotation3(z = .4), Scale3(2.0, 3.0, 2.0)))
        val doc = document(listOf(item), listOf(source))
        val properties = readLibraryProperties(doc, source)
        val edited = LibraryCommands.edit(doc, source.id, properties.copy(manufacturer = "New maker"),
            readProperties(doc, source)).preview(doc)
        val changed = edited.elements.single()
        assertEquals(doc.libraryItems, edited.libraryItems)
        assertEquals(properties.size, readLibraryProperties(edited, changed).size)
        assertEquals(properties.section, readLibraryProperties(edited, changed).section)
        assertEquals("New maker", readLibraryProperties(edited, changed).manufacturer)
        val beforeLines = buildDrawingScene(doc)
        val afterLines = buildDrawingScene(edited)
        assertEquals(beforeLines.segments.size, afterLines.segments.size)
        beforeLines.segments.zip(afterLines.segments).forEach { (before, after) ->
            point(before.a, after.a)
            point(before.b, after.b)
        }
        val beforeSurface = buildSurfaceScene(doc)
        val afterSurface = buildSurfaceScene(edited)
        assertEquals(beforeSurface.triangleCount, afterSurface.triangleCount)
        point(beforeSurface.bounds.min, afterSurface.bounds.min)
        point(beforeSurface.bounds.max, afterSurface.bounds.max)
        fun ports(element: Element): List<Point3> {
            val body = element.body as ElementBody.Component
            return item.fitting!!.ports.map { port -> measurementToWorld(element.transform, Point3(
                port.position.x * body.size.width / item.defaultSize.width,
                port.position.y * body.size.depth / item.defaultSize.depth,
                port.position.z * body.size.height / item.defaultSize.height,
            )) }
        }
        ports(source).zip(ports(changed)).forEach { (before, after) -> point(before, after) }
    }
}
