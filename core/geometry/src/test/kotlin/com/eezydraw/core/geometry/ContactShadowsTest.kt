package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ContactShadowsTest {
    private val p=ProjectId("p");private val l=LayerId("l")
    private val wall=Element(ElementId("wall"),p,l,ElementBody.Wall(Point2(0.0,0.0),Point2(6.0,0.0),.2,3.0))
    private val floor=Element(ElementId("floor"),p,l,ElementBody.Slab(Polygon2(listOf(Point2(0.0,0.0),Point2(6.0,0.0),Point2(6.0,4.0),Point2(0.0,4.0))),.2))
    private val doc=ProjectDocument(Project(p,"Contacts",0,0),layers=listOf(Layer(l,p,"Base")),elements=listOf(floor,wall))
    @Test fun contactsAreSoftAndClippedToTheFloorRatherThanCoveringBothSides() {
        val shadow=buildContactShadows(doc)
        assertTrue(shadow.chunks.isNotEmpty());assertTrue(shadow.triangleCount<20)
        val alphas=shadow.chunks.flatMap {c->c.vertices.indices.step(10).map {c.vertices[it+9]}}
        assertTrue(alphas.max()>.19);assertEquals(0.0,alphas.min().toDouble(),1e-7)
        assertTrue(shadow.chunks.all {it.material==RenderMaterial.CONTACT_SHADOW && !it.lodAllowed && it.bounds.min.y>=0})
    }
    @Test fun doorThresholdHasNoContactShadowAndDeletingTheDoorRestoresIt() {
        val door=BuiltInLibrary.find(LibraryItemId("eezy.architecture.single-door"))!!
        val e=Element(ElementId("door"),p,l,ElementBody.Component(door.id,door.defaultSize.copy(depth=.2),wallService=WallServiceAttachment(wall.id,WallFaceSide.LEFT,defaultWallDeviceOptions(door))),transform=Transform3(Point3(1.55,-.1,0.0)))
        val withDoor=doc.copy(elements=doc.elements+e,libraryItems=listOf(door))
        val shadows=buildContactShadows(withDoor)
        assertTrue(shadows.chunks.isNotEmpty())
        assertTrue(shadows.chunks.all {it.bounds.max.x<=1.55+1e-6 || it.bounds.min.x>=2.45-1e-6})
        assertTrue(buildContactShadows(doc).chunks.any {it.bounds.min.x<1.55 && it.bounds.max.x>2.45})
    }
    @Test fun hiddenOrXrayWallsAndHiddenFloorsDoNotLeaveFalseContacts() {
        for(mode in listOf(ObjectDisplay.HIDDEN,ObjectDisplay.XRAY,ObjectDisplay.WIREFRAME)) {
            val hidden=doc.copy(elements=listOf(floor,wall.withDisplay(mode)))
            assertTrue(buildContactShadows(hidden).chunks.isEmpty())
        }
        assertTrue(buildContactShadows(doc.copy(elements=listOf(floor.withDisplay(ObjectDisplay.HIDDEN),wall))).chunks.isEmpty())
    }
}
