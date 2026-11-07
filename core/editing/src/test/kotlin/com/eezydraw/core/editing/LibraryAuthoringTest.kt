package com.eezydraw.core.editing

import com.eezydraw.core.geometry.*
import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test

class LibraryAuthoringTest {
    private val project=ProjectId("p")
    private val layer=LayerId("l")
    private fun document()=ProjectDocument(Project(project,"Custom",0,0),layers=listOf(Layer(layer,project,"Drawing")))
    @Test fun `base shapes have a plan symbol and surfaces and sit at their local floor`() {
        for(shape in LibraryBaseShape.entries) {
            val item=createLibraryItem(LibraryItemId(shape.name),shape)
            val doc=document()
            val candidate=previewLibraryPlacement(doc,item,Point3(2.0,3.0,5.0))
            val placed=LibraryCommands.place(doc,candidate).preview(doc)
            val surface=buildSurfaceScene(placed)
            assertTrue(shape.name,surface.triangleCount>0)
            assertTrue(item.symbolGeometry.isNotEmpty())
            assertEquals(0.0,surface.bounds.min.z,1e-7)
            assertEquals(item.defaultSize.height,surface.bounds.max.z,1e-7)
        }
    }
    @Test fun `drawn geometry bakes rotation scale and position with a portable local origin`() {
        val shape=Element(ElementId("box"),project,layer,ElementBody.DrawingShape(Shape.Box(Size3(1.0,2.0,3.0))),
            transform=Transform3(Point3(10.0,20.0,30.0),Rotation3(z=Math.PI/2),Scale3(x=2.0)))
        val doc=document().copy(elements=listOf(shape))
        val item=libraryItemFromDrawing(doc,setOf(shape.id),LibraryItemId("drawn"))
        assertEquals(Size3(2.0,2.0,3.0),item.defaultSize)
        assertTrue(item.geometry.any {it is Shape.Mesh})
        assertTrue(item.symbolGeometry.all {it is Shape.Line && it.start.z==0.0 && it.end.z==0.0})
        assertEquals(doc.elements,listOf(shape))
    }
    @Test fun `planar drawing remains a custom symbol and rejects empty and non drawing selections`() {
        val line=Element(ElementId("line"),project,layer,ElementBody.DrawingShape(Shape.Line(Point3(5.0,6.0),Point3(7.0,9.0))))
        val doc=document().copy(elements=listOf(line))
        val item=libraryItemFromDrawing(doc,setOf(line.id),LibraryItemId("line-type"))
        assertEquals(Size3(2.0,3.0,.001),item.defaultSize)
        assertEquals(Shape.Line(Point3(),Point3(2.0,3.0)),item.geometry.single())
        assertThrows(IllegalArgumentException::class.java) {libraryItemFromDrawing(doc,emptySet(),item.id)}
    }
    @Test fun `closed 2D drawings become extruded 3D items with editable height`() {
        val polygon=Shape.Polygon(Polygon2(listOf(Point2(0.0,0.0),Point2(2.0,0.0),Point2(2.0,1.0),Point2(0.0,1.0))))
        val shape=Element(ElementId("profile"),project,layer,ElementBody.DrawingShape(polygon))
        val item=libraryItemFromDrawing(document().copy(elements=listOf(shape)),setOf(shape.id),LibraryItemId("profile-type"))
        assertEquals(Size3(2.0,1.0,.1),item.defaultSize)
        assertTrue(item.geometry.single() is Shape.Extrusion)
        val taller=item.copy(defaultSize=item.defaultSize.copy(height=1.0),geometrySize=item.defaultSize)
        val doc=document()
        val placed=LibraryCommands.place(doc,previewLibraryPlacement(doc,taller,Point3())).preview(doc)
        assertEquals(1.0,buildSurfaceScene(placed).bounds.max.z,1e-7)
    }
    @Test fun `edited fitting defaults retain accurate free connection marker and snap without replacing old types`() {
        val stock=BuiltInLibrary.items.first {it.fitting?.routeKind==RouteKind.CABLE_TRAY && it.fitting?.kind==FittingKind.COUPLER}
        val doc=document().copy(libraryItems=listOf(stock))
        val changed=stock.copy(name="Local coupler",defaultSize=stock.defaultSize.let {Size3(it.width*2,it.depth*2,it.height*2)},geometrySize=stock.defaultSize)
        val free=previewLibraryPlacement(doc,libraryPlacementDefinition(doc,changed),Point3(5.0))
        assertEquals(stock.fitting!!.ports.first().position.x*2+5.0,free.connectionPoint!!.x,1e-8)
        val route=Element(ElementId("route"),project,layer,ElementBody.Route(RouteKind.CABLE_TRAY,listOf(Point3(z=2.0),Point3(3.0,z=2.0)),stock.fitting!!.section,
            ElevationSetting(2.0,ElevationReference.ABOVE_FINISHED_FLOOR)))
        val priorVariant=adaptedFittingItem(stock,RouteSection.Rectangular(.6,.2))!!
        val routed=doc.copy(elements=listOf(route),libraryItems=doc.libraryItems+priorVariant)
        val preview=previewLibraryPlacement(routed,changed,Point3(3.0))
        val placed=LibraryCommands.place(routed,preview).preview(routed)
        assertEquals(stock,placed.libraryItems.first())
        assertNotEquals(stock.id,preview.item.id)
        assertNull(preview.item.geometrySize)
        assertEquals(Point3(3.0,z=2.0),preview.connectionPoint)
        val properties=readLibraryProperties(placed,placed.elements.last()).copy(section=priorVariant.fitting!!.section)
        val edited=LibraryCommands.edit(placed,placed.elements.last().id,properties).preview(placed)
        assertEquals(priorVariant,edited.libraryItems.single {it.id==priorVariant.id})
        assertEquals(properties.section,readLibraryProperties(edited,edited.elements.last()).section)
    }
    @Test fun `free placement preserves pointer elevation including below floor and undo redo`() {
        val doc=document()
        val item=createLibraryItem(LibraryItemId("free"),LibraryBaseShape.BOX).copy(mounting=MountingType.FREE)
        val preview=previewLibraryPlacement(doc,item,Point3(1.0,2.0,-.5))
        assertEquals(-.5,preview.mountingHeight,0.0)
        val history=EditHistory()
        val placed=history.execute(doc,LibraryCommands.place(doc,preview))
        assertEquals(-.5,placed.elements.single().transform.translation.z,0.0)
        assertEquals(-.5,readLibraryProperties(placed,placed.elements.single()).mountingHeight,0.0)
        assertEquals(doc,history.undo(placed))
        assertEquals(placed,history.redo(doc))
    }
    @Test fun `photo changes do not bloat project definitions or create duplicate geometry types`() {
        val source=createLibraryItem(LibraryItemId("photo"),LibraryBaseShape.BOX).copy(thumbnailBase64="small-photo")
        val definition=libraryPlacementDefinition(document(),source)
        assertNull(definition.thumbnailBase64)
        val doc=document().copy(libraryItems=listOf(definition))
        assertEquals(definition,libraryPlacementDefinition(doc,source.copy(thumbnailBase64="new-photo")))
        assertEquals("small-photo",source.thumbnailBase64)
    }
    @Test fun `edited default resizes wireframe and solid while existing project instances retain snapshots`() {
        val item=createLibraryItem(LibraryItemId("custom"),LibraryBaseShape.BOX)
        val doc=document()
        val first=LibraryCommands.place(doc,previewLibraryPlacement(doc,item,Point3(),id=ElementId("first"))).preview(doc)
        val changed=item.copy(name="Large",defaultSize=Size3(2.0,3.0,4.0),geometrySize=item.defaultSize)
        val definition=libraryPlacementDefinition(first,changed)
        assertNotEquals(item.id,definition.id)
        assertEquals(definition,libraryPlacementDefinition(first,changed))
        val next=LibraryCommands.place(first,previewLibraryPlacement(first,definition,Point3(5.0),id=ElementId("second"))).preview(first)
        assertEquals(item,next.libraryItems.single {it.id==item.id})
        assertEquals(item.defaultSize,(next.elements.first().body as ElementBody.Component).size)
        val wire=buildDrawingScene(next,setOf(ElementId("second")))
        val solid=buildSurfaceScene(next,setOf(ElementId("second")))
        assertEquals(7.0,wire.bounds.max.x,1e-7)
        assertEquals(3.0,solid.bounds.max.y,1e-7)
        assertEquals(4.0,solid.bounds.max.z,1e-7)
    }
}
