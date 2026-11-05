package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ObjectPresentationTest {
    private val project = ProjectId("display-project")
    private val layer = LayerId("base")
    private val level = LevelId("floor")
    private val system = SystemId("service")
    private val rectangle = Polygon2(listOf(Point2(0.0,0.0),Point2(4.0,0.0),Point2(4.0,3.0),Point2(0.0,3.0)))
    private fun element(id:String,body:ElementBody)=Element(ElementId(id),project,layer,body,level,system)
    private fun document(vararg elements:Element)=ProjectDocument(Project(project,"Display",0,0),
        layers=listOf(Layer(layer,project,"Base")),
        levels=listOf(Level(level,project,"Floor",0.0,.2,3.4)),
        systems=listOf(BuildingSystem(system,project,"Services",Discipline.HVAC,0xFF6688AA)),elements=elements.toList())

    @Test fun hidingEverySupportedBodyRemovesItsRenderAndSnapGeometryWithoutChangingTheBody() {
        val bodies=listOf<ElementBody>(
            ElementBody.DrawingShape(Shape.Box(Size3(1.0,2.0,3.0))),
            ElementBody.DrawingShape(Shape.Sphere(.5)),
            ElementBody.DrawingShape(Shape.Circle(.5)),
            ElementBody.Room(rectangle),ElementBody.Wall(Point2(0.0,0.0),Point2(4.0,0.0),.2,3.0),
            ElementBody.Slab(rectangle,.2),ElementBody.Component(LibraryItemId("socket"),Size3(.3,.2,.3)),
            ElementBody.Route(RouteKind.DUCT,listOf(Point3(),Point3(2.0,0.0,1.0)),RouteSection.Rectangular(.3,.2),
                ElevationSetting(1.0,ElevationReference.PROJECT_DATUM)))
        for((index,body)in bodies.withIndex()) {
            val original=element("$index",body)
            val hidden=original.withDisplay(ObjectDisplay.HIDDEN)
            val doc=document(hidden)
            assertEquals(body,hidden.body)
            assertTrue(buildDrawingScene(doc).segments.isEmpty())
            assertEquals(0,buildSurfaceScene(doc).triangleCount)
            assertFalse(elementShown(doc,hidden))
            val visible=buildDrawingScene(document(original))
            val anchor=visible.segments.first().a
            val settings=SnapSettings(modes=setOf(SnapMode.ENDPOINT))
            assertNull(SnapEngine(SpatialIndex(buildDrawingScene(doc).segments)).snap(Point2(anchor.x,anchor.y),settings,.1).mode)
        }
    }

    @Test fun hiddenObjectsAndHiddenHierarchyAppearOnlyAsOptionalFaintInteractiveEdges() {
        val box=element("box",ElementBody.DrawingShape(Shape.Box(Size3(1.0,1.0,1.0))))
        val base=document(box)
        val variants=listOf(base.copy(elements=listOf(box.withDisplay(ObjectDisplay.HIDDEN))),
            base.copy(layers=base.layers.map {it.copy(visible=false)}),
            base.copy(systems=base.systems.map {it.copy(visible=false)}),
            base.copy(project=base.project.copy(settings=base.project.settings.copy(hiddenLevelIds=setOf(level)))))
        for(doc in variants) {
            assertTrue(buildDrawingScene(doc).segments.isEmpty())
            val shown=doc.copy(project=doc.project.copy(settings=doc.project.settings.copy(showHiddenObjects=true)))
            val scene=buildDrawingScene(shown)
            assertTrue(scene.segments.isNotEmpty())
            assertTrue(scene.segments.all {it.display==ObjectDisplay.HIDDEN})
            assertTrue(scene.chunks.all {it.display==ObjectDisplay.HIDDEN})
            assertEquals(0,buildSurfaceScene(shown).triangleCount)
            assertTrue(elementShown(shown,shown.elements.single()))
            val anchor=scene.segments.first().a
            assertEquals(SnapMode.ENDPOINT,SnapEngine(SpatialIndex(scene.segments)).snap(Point2(anchor.x,anchor.y),
                SnapSettings(modes=setOf(SnapMode.ENDPOINT)),.1).mode)
        }
    }

    @Test fun xrayAndWireframeKeepActualPositionsAndPublishIndependentRenderModes() {
        val original=element("box",ElementBody.DrawingShape(Shape.Box(Size3(1.0,2.0,3.0))))
        val normal=buildDrawingScene(document(original))
        val xray=buildDrawingScene(document(original.withDisplay(ObjectDisplay.XRAY)))
        assertEquals(normal.bounds,xray.bounds)
        assertEquals(normal.segments.map {it.a to it.b},xray.segments.map {it.a to it.b})
        assertTrue(xray.chunks.all {it.display==ObjectDisplay.XRAY})
        assertTrue(buildSurfaceScene(document(original.withDisplay(ObjectDisplay.XRAY))).chunks.all {it.display==ObjectDisplay.XRAY})
        val wire=document(original.withDisplay(ObjectDisplay.WIREFRAME))
        assertEquals(normal.bounds,buildDrawingScene(wire).bounds)
        assertTrue(buildDrawingScene(wire).chunks.all {it.display==ObjectDisplay.WIREFRAME})
        assertEquals(0,buildSurfaceScene(wire).triangleCount)
    }

    @Test fun oneRoomWallCanBeHiddenWhileEveryOtherWallAndItsCapsRemain() {
        val room=element("room",ElementBody.Room(rectangle,ceilingHeight=3.0))
        val side=RoomSideRef(room.id,0)
        val normal=buildSurfaceScene(document(room))
        assertEquals(4,normal.chunks.mapNotNull {it.roomSide}.distinct().size)
        val hiddenRoom=room.withRoomSideDisplay(side,ObjectDisplay.HIDDEN)
        val doc=document(hiddenRoom)
        val surfaces=buildSurfaceScene(doc)
        assertFalse(surfaces.chunks.any {it.roomSide==side})
        assertEquals(3,surfaces.chunks.mapNotNull {it.roomSide}.distinct().size)
        assertTrue(surfaces.chunks.any {it.roomSide==null})
        assertFalse(buildDrawingScene(doc).segments.any {it.roomSide==side})
        val shown=doc.copy(project=doc.project.copy(settings=doc.project.settings.copy(showHiddenObjects=true)))
        assertTrue(buildDrawingScene(shown).segments.any {it.roomSide==side && it.display==ObjectDisplay.HIDDEN})
        assertFalse(buildSurfaceScene(shown).chunks.any {it.roomSide==side})
        assertEquals(room.body,hiddenRoom.body)
    }

    @Test fun outerAndHoleWallSidesHaveStableIndependentIdentities() {
        val hole=listOf(Point2(1.0,1.0),Point2(2.0,1.0),Point2(2.0,2.0),Point2(1.0,2.0))
        val room=element("room",ElementBody.Room(rectangle.copy(holes=listOf(hole))))
        val selected=RoomSideRef(room.id,2,0)
        val doc=document(room.withRoomSideDisplay(selected,ObjectDisplay.XRAY))
        val chunks=buildSurfaceScene(doc).chunks
        assertEquals(8,chunks.mapNotNull {it.roomSide}.distinct().size)
        assertTrue(chunks.filter {it.roomSide==selected}.all {it.display==ObjectDisplay.XRAY})
        assertTrue(chunks.filter {it.roomSide!=selected}.all {it.display==ObjectDisplay.NORMAL})
    }

    @Test fun aLinkedPhysicalWallUsesItsRoomOverrideWithoutASecondVirtualWallFace() {
        val wall=element("wall",ElementBody.Wall(Point2(0.0,0.0),Point2(4.0,0.0),.2,3.0))
        val room=element("room",ElementBody.Room(rectangle,wallFaces=listOf(RoomWallFace(0,wall.id,WallSide.LEFT))))
        val side=RoomSideRef(room.id,0)
        val chunks=buildSurfaceScene(document(room,wall)).chunks
        assertTrue(chunks.any {it.elementId==wall.id.value && it.roomSide==side})
        assertFalse(chunks.any {it.elementId==room.id.value && it.roomSide==side})
        val hidden=document(room.withRoomSideDisplay(side,ObjectDisplay.HIDDEN),wall)
        assertFalse(elementShown(hidden,wall))
        assertFalse(buildSurfaceScene(hidden).chunks.any {it.elementId==wall.id.value})
        assertFalse(buildDrawingScene(hidden).segments.any {it.elementId==wall.id.value})
        assertEquals(wall,hidden.elements.last())
    }

    @Test fun aNormalWallOverrideCanBeOpaqueInsideAXrayOrWireframeRoom() {
        val side=RoomSideRef(ElementId("room"),0)
        for(mode in listOf(ObjectDisplay.XRAY,ObjectDisplay.WIREFRAME)) {
            val room=element("room",ElementBody.Room(rectangle)).withDisplay(mode).withRoomSideDisplay(side,ObjectDisplay.NORMAL)
            val chunks=buildSurfaceScene(document(room)).chunks
            assertTrue(chunks.any {it.roomSide==side && it.display==ObjectDisplay.NORMAL})
            assertTrue(chunks.filter {it.roomSide!=side}.all {it.display==mode})
            if(mode==ObjectDisplay.WIREFRAME)assertEquals(2,chunks.sumOf {it.vertices.size/30})
        }
    }

    @Test fun automaticFrontWallMetadataKeepsTheTransformedRoomCentreAtHalfHeight() {
        val room=element("room",ElementBody.Room(rectangle,ceilingHeight=3.0)).copy(
            transform=Transform3(Point3(1000.0,2000.0,2.4),Rotation3(.2,.3,.4),Scale3(2.0,1.0,1.5)))
        val expected=measurementToWorld(room.transform,Point3(2.0,1.5,1.5))
        assertTrue(buildDrawingScene(document(room)).chunks.all {it.roomCenter==expected})
        assertTrue(buildSurfaceScene(document(room)).chunks.all {it.roomCenter==expected})
    }
}
