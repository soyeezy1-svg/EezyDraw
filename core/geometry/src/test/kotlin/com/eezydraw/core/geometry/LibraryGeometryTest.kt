package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class LibraryGeometryTest {
    private val projectId = ProjectId("library-geometry")
    private val layerId = LayerId("equipment")
    private val levelId = LevelId("upper")
    private val definition = LibraryItem(
        LibraryItemId("marked-panel"), "Marked panel", Discipline.ELECTRICAL, "Electrical",
        Size3(2.0, 1.0, 3.0), geometry = listOf(Shape.Box(Size3(2.0, 1.0, 3.0))),
        symbolGeometry = listOf(Shape.Line(Point3(.25, .25), Point3(1.75, .75))),
    )

    private fun component(
        size: Size3 = definition.defaultSize,
        transform: Transform3 = Transform3(Point3(10.0, 20.0, 4.2)),
    ) = Element(ElementId("panel"), projectId, layerId,
        ElementBody.Component(definition.id, size), levelId, transform = transform)

    private fun document(vararg elements: Element) = ProjectDocument(
        Project(projectId, "Equipment", 0, 0),
        levels = listOf(Level(levelId, projectId, "Upper", 3.0, .2, 3.4)),
        layers = listOf(Layer(layerId, projectId, "Equipment")),
        libraryItems = listOf(definition), elements = elements.toList(),
    )

    @Test fun planSymbolsAreRaisedOntoTheBodyAndRetainPhysicalSelectionBounds() {
        val doc = document(component())
        val physical = buildDrawingScene(doc)
        val plan = buildDrawingScene(doc, planSymbols = true)
        assertEquals(12, physical.segments.size)
        assertEquals(13, plan.segments.size)
        assertEquals(physical.bounds, plan.bounds)
        assertEquals(physical.levelBounds, plan.levelBounds)
        assertEquals(physical.segments, plan.segments.take(physical.segments.size))
        assertPoint(Point3(10.25, 20.25, 7.2), plan.segments.last().a)
        assertPoint(Point3(11.75, 20.75, 7.2), plan.segments.last().b)
        assertEquals(levelId.value, plan.segments.last().levelId)
        assertEquals("panel", plan.segments.last().elementId)
    }

    @Test fun instanceDimensionsAndWallFacingRotationScaleSymbolAndSolidTogether() {
        val item = component(Size3(4.0, 2.0, 6.0),
            Transform3(Point3(20.0, 30.0, 4.0), Rotation3(z = PI / 2), Scale3(.5, 2.0, 1.5)))
        val doc = document(item)
        val physical = buildDrawingScene(doc)
        val plan = buildDrawingScene(doc, planSymbols = true)
        val surfaces = buildSurfaceScene(doc)
        assertBounds(physical.bounds, surfaces.bounds)
        assertEquals(physical.bounds, plan.bounds)
        assertPoint(Point3(16.0, 30.0, 4.0), plan.bounds.min)
        assertPoint(Point3(20.0, 32.0, 13.0), plan.bounds.max)
        assertPoint(Point3(19.0, 30.25, 13.0), plan.segments.last().a)
        assertPoint(Point3(17.0, 31.75, 13.0), plan.segments.last().b)
        assertEquals(12, surfaces.triangleCount)
    }

    @Test fun mirroredAndTiltedParametersPreservePhysicalBoundsInBothRepresentations() {
        val item = component(Size3(.8, .4, 1.2),
            Transform3(Point3(1e8, -1e8, 3.0), Rotation3(.3, -.2, .6), Scale3(-2.0, 1.5, .75)))
        val doc = document(item)
        val physical = buildDrawingScene(doc)
        val plan = buildDrawingScene(doc, planSymbols = true)
        assertBounds(physical.bounds, plan.bounds)
        assertBounds(physical.bounds, buildSurfaceScene(doc).bounds)
        val symbol = plan.segments.last()
        val expected = measurementToWorld(item.transform, Point3(.1, .1, 1.2))
        assertPoint(expected, symbol.a)
        assertTrue(plan.chunks.flatMap { it.vertices.toList() }.all(Float::isFinite))
    }

    @Test fun ceilingItemsKeepPlanSymbolsOnTheVisibleFaceWhenHangingBelowTheirMount() {
        val item = component(Size3(.2, .2, .04), Transform3(Point3(0.0, 0.0, 6.0), scale = Scale3(z = -1.0)))
        val doc = document(item)
        val plan = buildDrawingScene(doc, planSymbols = true)
        assertEquals(6.0, plan.bounds.max.z, 0.0)
        assertEquals(5.96, plan.bounds.min.z, 1e-12)
        assertEquals(6.0, plan.segments.last().a.z, 0.0)
        assertEquals(6.0, plan.segments.last().b.z, 0.0)
        assertEquals(buildDrawingScene(doc).bounds, plan.bounds)
    }

    @Test fun planSymbolsShareInstanceColourVisibilityAndLockPresentation() {
        val colour = 0xFF72A123L
        val original = component()
        val item = original.copy(properties = original.properties + (ELEMENT_COLOUR_KEY to ParameterValue.Colour(colour)))
            .withLock(LockMode.ALL)
        val visible = document(item)
        val plan = buildDrawingScene(visible, planSymbols = true)
        assertTrue(plan.segments.all { it.colorArgb == colour })
        assertTrue(buildSurfaceScene(visible).chunks.all { chunk ->
            // Surface vertices pack rgba after position and normal.
            kotlin.math.abs(chunk.vertices[6] - 0x72 / 255f) < 1e-6
        })
        val hidden = document(item.withDisplay(ObjectDisplay.HIDDEN))
        assertTrue(buildDrawingScene(hidden, planSymbols = true).segments.isEmpty())
        assertEquals(0, buildSurfaceScene(hidden).triangleCount)
        val shown = hidden.copy(project = hidden.project.copy(settings = hidden.project.settings.copy(showHiddenObjects = true)))
        val faint = buildDrawingScene(shown, planSymbols = true)
        assertEquals(plan.segments.size, faint.segments.size)
        assertTrue(faint.segments.all { it.display == ObjectDisplay.HIDDEN && it.colorArgb == colour })
        assertEquals(LockMode.ALL, shown.elements.single().lockMode)
    }

    @Test fun customDefinitionWithoutPhysicalGeometryStillKeepsItsFallbackBoxAndPlanMark() {
        val doc = document(component()).copy(libraryItems = listOf(definition.copy(geometry = emptyList())))
        assertEquals(12, buildDrawingScene(doc).segments.size)
        val plan = buildDrawingScene(doc, planSymbols = true)
        assertEquals(13, plan.segments.size)
        assertEquals(12, buildSurfaceScene(doc).triangleCount)
        assertEquals(buildDrawingScene(doc).bounds, plan.bounds)
        assertTrue(buildDrawingScene(doc, setOf(ElementId("other")), planSymbols = true).segments.isEmpty())
    }

    @Test fun everyBuiltInTypeKeepsItsNominalSizeWhenParametersChange() {
        for (item in BuiltInLibrary.items) {
            val resized = Size3(item.defaultSize.width * 1.7, item.defaultSize.depth * .8, item.defaultSize.height * 1.3)
            val element = component(resized, Transform3()).copy(body = ElementBody.Component(item.id, resized))
            val doc = document(element).copy(libraryItems = listOf(item))
            val physical = buildDrawingScene(doc)
            val plan = buildDrawingScene(doc, planSymbols = true)
            assertPoint(Point3(), physical.bounds.min, item.name)
            val kind=item.openingKind()
            val frame=minOf(.05,resized.width/10,resized.height/10)
            val sweep=when(kind) {
                OpeningKind.DOOR->resized.width-2*frame
                OpeningKind.DOUBLE_DOOR->(resized.width-2*frame)/2
                else->0.0
            }
            // An open leaf projects outside the nominal opening depth; the frame keeps W/H.
            assertPoint(Point3(resized.width, maxOf(resized.depth,resized.depth/2+sweep), resized.height), physical.bounds.max, item.name)
            assertBounds(physical.bounds, plan.bounds)
            assertBounds(physical.bounds, buildSurfaceScene(doc).bounds)
            if(item.openingKind()==null)assertTrue("${item.name} needs a plan symbol", plan.segments.size > physical.segments.size)
        }
    }

    @Test fun hundredsOfInstancesCompileWithoutDuplicatingDefinitionsOrLosingLateSymbols() {
        val types = BuiltInLibrary.items
        val instanceCount = maxOf(384, types.size * 2)
        val elements = List(instanceCount) { index ->
            val item = types[index % types.size]
            component(item.defaultSize, Transform3(Point3(index * 4.0, 0.0, 3.0))).copy(
                id = ElementId("equipment-$index"), body = ElementBody.Component(item.id, item.defaultSize))
        }
        val doc = document(*elements.toTypedArray()).copy(libraryItems = types)
        val scene = buildDrawingScene(doc, planSymbols = true)
        assertEquals(elements.map { it.id.value }.toSet(), scene.segments.map { it.elementId }.toSet())
        assertEquals(types.size, doc.libraryItems.size)
        assertEquals(types.map { it.id }.toSet(), elements.map { (it.body as ElementBody.Component).libraryItemId }.toSet())
        assertTrue(scene.chunks.all { chunk -> chunk.vertices.all(Float::isFinite) })
        assertTrue(scene.segments.any { it.elementId == "equipment-${instanceCount - 1}" })
        assertTrue(scene.bounds.max.x > (instanceCount - 1) * 4.0)
    }

    private fun assertPoint(expected: Point3, actual: Point3, message: String = "") {
        assertEquals(message, expected.x, actual.x, 1e-7)
        assertEquals(message, expected.y, actual.y, 1e-7)
        assertEquals(message, expected.z, actual.z, 1e-7)
    }

    private fun assertBounds(expected: Bounds3, actual: Bounds3) {
        assertPoint(expected.min, actual.min)
        assertPoint(expected.max, actual.max)
    }
}
