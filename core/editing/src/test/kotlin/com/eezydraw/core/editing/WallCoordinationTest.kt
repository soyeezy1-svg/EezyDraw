package com.eezydraw.core.editing

import com.eezydraw.core.model.*
import com.eezydraw.core.geometry.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class WallCoordinationTest {
    private val p=ProjectId("coordination");private val l=LayerId("base")
    private val level=Level(LevelId("L1"),p,"Level 1",0.0,.2,3.0)
    private val wall=Element(ElementId("wall"),p,l,ElementBody.Wall(Point2(0.0,0.0),Point2(6.0,0.0),.2,3.0),level.id,name="Wall")
    private val base=ProjectDocument(Project(p,"Coordination",0,0),levels=listOf(level),layers=listOf(Layer(l,p,"Base")),elements=listOf(wall))
    private fun stock(slug:String)=BuiltInLibrary.find(LibraryItemId("eezy.architecture.$slug"))!!
    private val socket=BuiltInLibrary.items.first {it.symbol==LibrarySymbol.SOCKET}
    private fun place(doc:ProjectDocument,item:LibraryItem,at:Point3=Point3(2.0,.2),id:String="item",options:WallServiceOptions=defaultWallDeviceOptions(item)):ProjectDocument {
        val preview=previewLibraryPlacement(doc,item,at,level.id,id=ElementId(id),wallOptions=options)
        return LibraryCommands.place(doc,preview).preview(doc)
    }
    private fun selected(doc:ProjectDocument,id:String="item")=doc.elements.first {it.id.value==id}
    private fun room(id:String="room",x:Double=0.0)=Element(ElementId(id),p,l,ElementBody.Room(Polygon2(listOf(Point2(0.0,0.0),Point2(4.0,0.0),Point2(4.0,4.0),Point2(0.0,4.0)))),level.id,name=id,transform=Transform3(Point3(x,0.0)))
    private fun onFace(mesh:Shape.Mesh,frame:WallFrame,s:Double,z:Double,depth:Double):Boolean {
        for(i in mesh.triangles.indices step 3) {
            val v=(0..2).map {frame.coordinates(measurementToWorld(frame.host.transform,mesh.vertices[mesh.triangles[i+it]]))}
            if(v.any {abs(it.y-depth)>1e-7})continue
            fun area(a:Point3,b:Point3,c:Point3)=abs((b.x-a.x)*(c.z-a.z)-(b.z-a.z)*(c.x-a.x))
            val area=area(v[0],v[1],v[2]);if(area<1e-10)continue
            val q=Point3(s,depth,z)
            if(abs(area(v[0],v[1],q)+area(v[1],v[2],q)+area(v[2],v[0],q)-area)<1e-8)return true
        };return false
    }
    @Test fun doorsAndWindowsCutBothFinishesAtTheirExactWidthHeightAndSill() {
        for(slug in listOf("single-door","double-door","sliding-door","single-window","double-window","fixed-window")) {
            val doc=place(base,stock(slug));val item=selected(doc);val cut=wallServiceCuts(doc).single();val f=wallFrames(doc).single()
            assertEquals(WallServiceMode.OPENING,cut.mode)
            assertEquals(0.0,cut.fromDepth,0.0);assertEquals(f.thickness,cut.toDepth,1e-9)
            assertEquals(stock(slug).defaultSize.width,cut.width,1e-8);assertEquals(stock(slug).defaultSize.height,cut.height,1e-8)
            assertEquals(stock(slug).defaultMountingHeight,cut.outline.minOf {it.y},1e-8)
            assertEquals(f.thickness,(item.body as ElementBody.Component).size.depth,1e-8)
            val mesh=wallCutMesh(f,listOf(cut))!!
            for(depth in listOf(0.0,f.thickness)) {
                assertFalse("$slug opening at $depth",onFace(mesh,f,cut.position.x,cut.position.y,depth))
                assertTrue("Wall beside $slug stays solid",onFace(mesh,f,.25,1.0,depth))
            }
        }
    }
    @Test fun moveResizeDeleteAndHistoryRebuildTheOpeningWithoutTouchingTheNominalWall() {
        val doc=place(base,stock("single-window"));val e=selected(doc)
        val moved=EditCommands.transform(doc,setOf(e.id),EditDelta(translation=Point3(1.0,0.0,.2))).preview(doc)
        val attached=WallServices.reconcile(doc,moved)
        assertEquals(3.0,wallServiceCuts(attached).single().position.x,1e-8)
        val props=readLibraryProperties(attached,selected(attached)).copy(size=Size3(1.4,.2,1.0),mountingHeight=1.1)
        val resized=LibraryCommands.edit(attached,e.id,props).preview(attached)
        val result=WallServices.reconcile(attached,resized)
        val cut=wallServiceCuts(result).single()
        assertEquals(1.4,cut.width,1e-8);assertEquals(1.0,cut.height,1e-8);assertEquals(1.1,cut.outline.minOf {it.y},1e-8)
        assertEquals(wall,result.elements.first())
        val history=EditHistory();val changed=history.execute(doc,EditCommands.between(doc,result,"Resize opening"))
        assertEquals(doc,history.undo(changed));assertEquals(result,history.redo(doc))
        val deleted=EditCommands.delete(result,setOf(e.id)).preview(result)
        assertTrue(wallServiceCuts(deleted).isEmpty());assertEquals(wall,deleted.elements.single())
    }
    @Test fun openingIsVisibleInPlanFilledWireframeXrayAndSectionEvenWithServiceCutsHidden() {
        val doc=place(base,stock("single-door"))
        for(mode in listOf(ViewMode.WIREFRAME,ViewMode.SHADED,ViewMode.XRAY,ViewMode.SECTION)) {
            val view=doc.copy(project=doc.project.copy(settings=doc.project.settings.copy(viewMode=mode,wallServices=WallServiceSettings(showCuts=false))))
            val surfaces=buildSurfaceScene(view,planView=true)
            val gapTriangles=surfaces.chunks.filter {it.elementId==wall.id.value}.flatMap {c->c.vertices.indices.step(30).map {o->
                (0..2).map {i->Point2(c.origin.x+c.vertices[o+i*10],c.origin.y+c.vertices[o+i*10+1])}
            }}
            fun area(a:Point2,b:Point2,c:Point2)=abs((b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x))
            assertFalse("$mode plan must leave the opening empty",gapTriangles.any {t->val a=area(t[0],t[1],t[2]);val q=Point2(2.0,0.0);a>1e-10 && abs(area(t[0],t[1],q)+area(t[1],t[2],q)+area(t[2],t[0],q)-a)<1e-9})
            assertTrue(buildDrawingScene(view,planSymbols=true).segments.any {it.elementId=="item"})
            assertTrue(buildSurfaceScene(view).chunks.any {it.elementId==wall.id.value})
        }
    }
    @Test fun nominalMeasurementsAndExplicitUncutModelExportsRetainTheUncutWall() {
        val doc=place(base,stock("single-door"))
        fun wallVertices(options:ModelExportOptions)=ModelExport.meshes(doc,options).single {it.element.id==wall.id}.vertices
        val normal=wallVertices(ModelExportOptions())
        val uncut=wallVertices(ModelExportOptions(cuts=ModelCuts.NONE))
        val frame=wallFrames(doc).single()
        // The door's centre lies in the wall finish only in an explicitly uncut export.
        assertFalse(onFace(Shape.Mesh(normal,normal.indices.toList()),frame,2.0,1.0,0.0))
        assertTrue(onFace(Shape.Mesh(uncut,uncut.indices.toList()),frame,2.0,1.0,0.0))
        assertEquals(readProperties(base,wall),readProperties(doc,wall))
    }
    @Test fun swingHandAndDirectionChangeTheLeafAndTrueWidthPlanArc() {
        val item=stock("single-door");val size=item.defaultSize.copy(depth=.2)
        for(hand in DoorHinge.entries)for(direction in DoorDirection.entries) {
            val swing=DoorSwing(hand,direction);val arc=openingPlanSymbol(OpeningKind.DOOR,size,swing).single() as Shape.Polyline
            val radius=size.width-.1
            assertEquals(radius,abs(arc.points.last().y-size.depth/2),1e-8)
            assertEquals(if(hand==DoorHinge.LEFT).05 else size.width-.05,arc.points.last().x,1e-8)
            assertEquals(if(direction==DoorDirection.IN)1.0 else -1.0,sign(arc.points.last().y-size.depth/2),0.0)
            assertEquals(swing,swing.flipped().flipped())
        }
        val doc=place(base,item);val e=selected(doc);val old=e.wallService()!!.options
        val after=WallServices.edit(doc,e.id,old.copy(swing=old.swing!!.flipped())).preview(doc)
        assertEquals(wallServiceCuts(doc),wallServiceCuts(after))
        assertNotEquals(buildDrawingScene(doc),buildDrawingScene(after))
    }
    @Test fun closestFaceAndFlipAreFlushAndFaceOutwardIncludingConcealedItems() {
        for(y in listOf(-.2,.2))for(mode in listOf(WallServiceMode.SURFACE,WallServiceMode.RECESS)) {
            val preview=previewLibraryPlacement(base,socket,Point3(2.0,y),level.id,wallOptions=WallServiceOptions(mode))
            val mount=preview.element.wallService()!!;val f=wallFrames(base).single()
            assertEquals(if(y>0)WallFaceSide.LEFT else WallFaceSide.RIGHT,mount.side)
            val b=preview.element.body as ElementBody.Component
            val contact=measurementToWorld(preview.element.transform,Point3(b.size.width/2,if(mode==WallServiceMode.RECESS)b.size.depth else 0.0))
            assertEquals(f.faceDepth(mount.side),f.coordinates(contact).y,1e-8)
            val origin=preview.element.transform.translation
            val front=measurementToWorld(preview.element.transform,Point3(y=1.0));val n=f.outward(mount.side)
            assertTrue((front.x-origin.x)*n.x+(front.y-origin.y)*n.y>.99)
            val flipped=previewLibraryPlacement(base,socket,Point3(2.0,y),level.id,wallOptions=WallServiceOptions(mode,side=if(mount.side==WallFaceSide.LEFT)WallFaceSide.RIGHT else WallFaceSide.LEFT))
            assertNotEquals(mount.side,flipped.element.wallService()!!.side)
        }
    }
    @Test fun wallFaceNamesUseTheItemsPositionAndIdentifyTheOutside() {
        val a=room("Room A");val b=room("Room B",4.0)
        val doc=base.copy(elements=base.elements+listOf(a,b))
        val frame=wallFrames(doc).first {it.host.id==wall.id}
        assertTrue(wallFaceName(doc,frame,WallFaceSide.LEFT,2.0).startsWith("Room A side"))
        assertTrue(wallFaceName(doc,frame,WallFaceSide.LEFT,5.0).startsWith("Room B side"))
        assertTrue(wallFaceName(doc,frame,WallFaceSide.RIGHT,2.0).startsWith("Outside"))
    }
    @Test fun wallPositionReferencesAndNumericPropertiesResolveToTheSameCentre() {
        var doc=place(base,stock("single-door"),Point3(2.0,.2),id="door")
        val f=wallFrames(doc).single()
        assertEquals(1.0,resolveAlongWallPosition(doc,f,AlongWallPosition(WallPositionReference.LEFT_END,1.0),4.0),0.0)
        assertEquals(5.0,resolveAlongWallPosition(doc,f,AlongWallPosition(WallPositionReference.RIGHT_END,1.0),2.0),0.0)
        assertEquals(5.0,resolveAlongWallPosition(doc,f,AlongWallPosition(WallPositionReference.NEAREST_CORNER,1.0),4.0),0.0)
        val doorEdge=wallServiceCuts(doc).single().outline.maxOf {it.x}
        assertEquals(doorEdge+.3,resolveAlongWallPosition(doc,f,AlongWallPosition(WallPositionReference.NEAREST_DOOR,.3),4.0),1e-8)
        doc=place(doc,socket,Point3(4.0,-.2),options=defaultWallDeviceOptions(socket).copy(position=AlongWallPosition(WallPositionReference.RIGHT_END,1.0)))
        val e=selected(doc);val initial=readLibraryProperties(doc,e)
        assertEquals(5.0,initial.wallMount!!.fromLeft,1e-8);assertEquals(1.0,initial.wallMount.fromRight,1e-8)
        val changed=LibraryCommands.edit(doc,e.id,initial.copy(mountingHeight=1.4,wallMount=initial.wallMount.copy(side=WallFaceSide.LEFT,position=AlongWallPosition(WallPositionReference.LEFT_END,3.0)))).preview(doc)
        val next=readLibraryProperties(changed,selected(changed))
        assertEquals(3.0,next.wallMount!!.fromLeft,1e-8);assertEquals(3.0,next.wallMount.fromRight,1e-8)
        assertEquals(WallFaceSide.LEFT,next.wallMount.side);assertEquals(1.4,next.mountingHeight,1e-8)
    }
    @Test fun sharedRoomWallDisplayChangesOnlyOnePhysicalWallAndNormalRestoresIt() {
        val rooms=base.copy(elements=listOf(room("Room A"),room("Room B",4.0)))
        val generated=RoomWalls.generate(rooms,rooms.elements.mapTo(hashSetOf()){it.id},RoomWallOptions()).preview(rooms)
        val a=generated.elements.first {it.id.value=="Room A"};val b=generated.elements.first {it.id.value=="Room B"}
        val shared=(a.body as ElementBody.Room).wallFaces.map {it.wallId}.intersect((b.body as ElementBody.Room).wallFaces.map {it.wallId}.toSet()).single()
        var current=generated;val history=EditHistory()
        for(mode in listOf(ObjectDisplay.HIDDEN,ObjectDisplay.XRAY,ObjectDisplay.WIREFRAME,ObjectDisplay.NORMAL)) {
            val preview=PresentationCommands.setDisplay(current,setOf(shared),mode).preview(current)
            val reconciled=RoomWalls.reconcile(current,preview)
            val changed=history.execute(current,EditCommands.between(current,reconciled,"Wall display"))
            assertEquals(mode,ScenePresentation(changed).display(changed.elements.first {it.id==shared}))
            assertEquals(current.elements.filter {it.id!=shared},changed.elements.filter {it.id!=shared})
            assertEquals(current,history.undo(changed));assertEquals(changed,history.redo(current));current=changed
        }
        val reset=PresentationCommands.resetAllDisplay(current).preview(current)
        assertTrue(reset.elements.all {it.objectDisplay==ObjectDisplay.NORMAL})
    }
    @Test fun selectingARoomBoundarySelectsItsPhysicalWallAndCutInteriorStaysEmpty() {
        val r=room();val seed=base.copy(elements=listOf(r))
        val generated=RoomWalls.generate(seed,setOf(r.id),RoomWallOptions()).preview(seed)
        val linked=generated.elements.first {it.id==r.id}.body as ElementBody.Room
        val bottom=linked.wallFaces.first {it.edgeIndex==0}.wallId
        val picker=EditorPicker(generated,buildDrawingScene(generated,planSymbols=true),planView=true)
        assertEquals(bottom,picker.pickPlan(Point2(2.0,0.0),.2))
        val open=place(generated,stock("single-door"),Point3(2.0,.1))
        val openedPicker=EditorPicker(open,buildDrawingScene(open,planSymbols=true),planView=true)
        assertNotEquals(bottom,openedPicker.pickPlan(Point2(2.0,0.0),.001))
    }
    @Test fun explicitWallNormalOverridesLegacyRoomSideAndAutoXrayWithoutChangingOtherSides() {
        val room=room();val generated=RoomWalls.generate(base.copy(elements=listOf(room)),setOf(room.id),RoomWallOptions()).preview(base.copy(elements=listOf(room)))
        val r=generated.elements.first {it.id==room.id};val side=RoomSideRef(r.id,0);val w=(r.body as ElementBody.Room).wallFaces.first {it.edgeIndex==0}.wallId
        val legacy=generated.copy(elements=generated.elements.map {if(it.id==r.id)it.withDisplay(ObjectDisplay.XRAY).withRoomSideDisplay(side,ObjectDisplay.HIDDEN)else it})
        val restored=PresentationCommands.resetDisplay(legacy,setOf(w)).preview(legacy)
        assertEquals(ObjectDisplay.NORMAL,ScenePresentation(restored).display(restored.elements.first {it.id==w}))
        assertTrue(buildDrawingScene(restored).segments.filter {it.elementId==w.value}.all {it.roomSide==null})
        assertEquals(legacy.elements.first {it.id==r.id},restored.elements.first {it.id==r.id})
    }
    @Test fun noDefaultRoomRoofAndFlatCeilingIsASeparateSelectableDisplayableObject() {
        val doc=base.copy(elements=listOf(room()))
        assertEquals(CeilingType.NONE,level.ceiling.type)
        val surfaces=buildSurfaceScene(doc)
        assertFalse(surfaces.chunks.flatMap {c->c.vertices.indices.step(30).map {o->(0..2).map {i->c.origin.z+c.vertices[o+i*10+2]}}}.any {t->t.all {abs(it-3.0)<1e-8}})
        val flat=Ceilings.setLevel(doc,level.id,CeilingOptions(CeilingType.FLAT,2.6,.15)).preview(doc)
        val ceiling=flat.elements.single {it.isCeiling}
        assertEquals(2.75,ceiling.transform.translation.z,1e-8)
        assertTrue(ceiling.body is ElementBody.Slab)
        assertEquals(ceiling.id,EditorPicker(flat,buildDrawingScene(flat)).pickPlan(Point2(2.0,2.0),.01))
        val xray=PresentationCommands.setDisplay(flat,setOf(ceiling.id),ObjectDisplay.XRAY).preview(flat)
        assertTrue(buildSurfaceScene(xray).chunks.filter {it.elementId==ceiling.id.value}.all {it.display==ObjectDisplay.XRAY})
        val hidden=PresentationCommands.setDisplay(flat,setOf(ceiling.id),ObjectDisplay.HIDDEN).preview(flat)
        assertFalse(buildSurfaceScene(hidden).chunks.any {it.elementId==ceiling.id.value})
        val toggled=flat.copy(project=flat.project.copy(settings=flat.project.settings.copy(showCeilings=false)))
        assertFalse(buildSurfaceScene(toggled).chunks.any {it.elementId==ceiling.id.value})
        assertNull(floorSlab(flat,Point3(1.0,1.0),level.id))
    }
    @Test fun ceilingDeleteAndLevelHeightChangesAreUndoableAndMountingWorksWithoutARoof() {
        val doc=base.copy(elements=listOf(room()))
        val light=BuiltInLibrary.items.first {it.mounting==MountingType.CEILING && !it.ceiling}
        val lit=place(doc,light,Point3(1.0,1.0),id="light")
        assertEquals(2.8,selected(lit,"light").transform.translation.z,1e-8)
        val command=Ceilings.setLevel(lit,level.id,CeilingOptions(CeilingType.FLAT,2.6,.1))
        val history=EditHistory();val flat=history.execute(lit,command)
        assertEquals(2.6,selected(flat,"light").transform.translation.z,1e-8)
        assertEquals(lit,history.undo(flat));assertEquals(flat,history.redo(lit))
        val c=flat.elements.single {it.isCeiling}
        val removed=Ceilings.reconcile(flat,EditCommands.delete(flat,setOf(c.id)).preview(flat))
        assertFalse(removed.elements.any {it.isCeiling});assertEquals(CeilingType.NONE,roomCeiling(removed,selected(removed,"room")).type)
        assertEquals(2.6,previewLibraryPlacement(removed,light,Point3(1.0,1.0),level.id).element.transform.translation.z,1e-8)
        assertFalse(EditCommands.delete(flat,setOf(ElementId("room"))).preview(flat).elements.any {it.isCeiling})
    }
}
