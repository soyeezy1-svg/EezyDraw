package com.eezydraw.core.editing

import com.eezydraw.core.geometry.buildDrawingScene
import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ObjectPresentationBoundsTest {
    private val project=ProjectId("physical")
    private val layer=LayerId("base")
    private val level=LevelId("floor")
    private val system=SystemId("services")
    private val box=Element(ElementId("host"),project,layer,ElementBody.DrawingShape(Shape.Box(Size3(1.0,2.0,3.0))),
        level,system,transform=Transform3(Point3(10.0,20.0,2.4),Rotation3(.2,.3,.4),Scale3(-2.0,1.5,.5)))
    private fun document(vararg elements:Element)=ProjectDocument(Project(project,"Physical",0,0),
        layers=listOf(Layer(layer,project,"Base")),levels=listOf(Level(level,project,"Floor",2.4,.2,3.4)),
        systems=listOf(BuildingSystem(system,project,"Service",Discipline.HVAC,0xFF6688AA)),elements=elements.toList())

    @Test fun hiddenHierarchyAndDisplayDoNotChangePropertiesOrReferencePositions() {
        val original=document(box)
        val properties=readProperties(original,box)
        val bounds=elementWorldBounds(original,setOf(box.id))
        val picked=Point3(10.1,20.2,2.5)
        val reference=measurementPointForElement(original,box.id,picked)
        for(mode in ObjectDisplay.entries) {
            val changed=box.withDisplay(mode)
            val visible=document(changed)
            val hidden=visible.copy(layers=visible.layers.map {it.copy(visible=false)},systems=visible.systems.map {it.copy(visible=false)},
                project=visible.project.copy(settings=visible.project.settings.copy(hiddenLevelIds=setOf(level))))
            assertEquals(properties,readProperties(hidden,changed))
            assertEquals(bounds,elementWorldBounds(hidden,setOf(box.id)))
            assertEquals(reference,measurementPointForElement(hidden,box.id,picked))
        }
    }

    @Test fun aHiddenHostRetainsExactlyTheSameAssociativeMeasurement() {
        val original=document(box)
        val start=measurementToWorld(box.transform,Point3(0.0,0.0,0.0))
        val end=start.copy(x=start.x+2.0)
        val created=createMeasurement(original,start,end,start.copy(y=start.y+1.0),sourceElementId=box.id,
            showHeightAboveFloor=true,floorLevelId=level)
        val annotation=Element(ElementId("measurement"),project,layer,ElementBody.DrawingShape(created.shape),
            level,transform=created.transform)
        val normal=document(box,annotation)
        val hidden=normal.copy(elements=listOf(box.withDisplay(ObjectDisplay.HIDDEN),annotation))
        assertEquals(resolveMeasurement(normal,annotation),resolveMeasurement(hidden,annotation))
        assertFalse(buildDrawingScene(hidden).segments.any {it.elementId==box.id.value})
        assertTrue(buildDrawingScene(hidden).segments.any {it.elementId==annotation.id.value})
    }

    @Test fun hidingRoomSidesDoesNotShrinkItsNumericDimensions() {
        val body=ElementBody.Room(Polygon2(listOf(Point2(0.0,0.0),Point2(4.0,0.0),Point2(4.0,3.0),Point2(0.0,3.0))),ceilingHeight=3.0)
        val room=box.copy(body=body,transform=Transform3())
        val before=readProperties(document(room),room)
        var hidden=room
        for(index in body.boundary.outer.indices)hidden=hidden.withRoomSideDisplay(RoomSideRef(room.id,index),ObjectDisplay.HIDDEN)
        assertTrue(buildDrawingScene(document(hidden)).segments.isEmpty())
        assertEquals(before,readProperties(document(hidden),hidden))
        assertEquals(elementWorldBounds(document(room),setOf(room.id)),elementWorldBounds(document(hidden),setOf(room.id)))
    }
}
