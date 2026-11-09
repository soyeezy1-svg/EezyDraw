package com.eezydraw.core.editing

import com.eezydraw.core.model.*
import kotlin.math.PI
import org.junit.Assert.*
import org.junit.Test

class PresentationCommandsTest {
    private val owner=EditFixtures.projectId
    private val ground=LevelId("ground")
    private val upper=LevelId("upper")
    private fun fixture()=EditFixtures.document().copy(levels=listOf(
        Level(ground,owner,"Ground",0.0,.2,3.4),Level(upper,owner,"Upper",3.4,.2,3.4)))
    private fun withLock(mode:LockMode)=fixture().let {doc->doc.copy(elements=doc.elements.map {it.withLock(mode)})}

    @Test fun everythingLockRejectsMoveRotateScaleMirrorDeleteArrayAndNumericEdits() {
        val doc=withLock(LockMode.ALL);val item=doc.elements.single();val ids=setOf(item.id)
        for(delta in listOf(EditDelta(translation=Point3(1.0)),EditDelta(rotationZ=PI/2),EditDelta(scale=2.0)))
            assertThrows(IllegalArgumentException::class.java){EditCommands.transform(doc,ids,delta)}
        assertThrows(IllegalArgumentException::class.java){EditCommands.mirror(doc,ids,MirrorAxis.X,Point3())}
        assertThrows(IllegalArgumentException::class.java){EditCommands.delete(doc,ids)}
        assertThrows(IllegalArgumentException::class.java){EditCommands.array(doc,ids,2,1,1.0,0.0)}
        assertThrows(IllegalArgumentException::class.java){EditCommands.replace(doc,listOf(item.copy(name="Changed")))}
        assertThrows(IllegalArgumentException::class.java){EditCommands.replace(doc,listOf(item.copy(properties=item.properties+
            (ELEMENT_COLOUR_KEY to ParameterValue.Colour(0xFFFF0000)))))}
        val props=readProperties(doc,item)
        assertThrows(IllegalArgumentException::class.java){EditCommands.replace(doc,listOf(applyProperties(doc,item,
            props.copy(size=Point3(6.0,8.0,0.0)))))}
        // Copying is non-destructive; a pasted clone retains its lock until explicitly unlocked.
        val pasted=EditCommands.paste(doc,listOf(item),Point3(2.0)).preview(doc)
        assertEquals(2,pasted.elements.size);assertTrue(pasted.elements.all {it.lockMode==LockMode.ALL})
    }

    @Test fun positionLockPreservesInsertionButAllowsRotationSizeAndDelete() {
        val doc=withLock(LockMode.POSITION_ONLY);val item=doc.elements.single();val ids=setOf(item.id)
        assertThrows(IllegalArgumentException::class.java){EditCommands.transform(doc,ids,EditDelta(translation=Point3(1.0)))}
        assertThrows(IllegalArgumentException::class.java){EditCommands.transform(doc,ids,EditDelta(pivot=Point3(1.0),rotationZ=PI/2))}
        val rotated=EditCommands.transform(doc,ids,EditDelta(pivot=item.transform.translation,rotationZ=PI/2)).preview(doc).elements.single()
        assertEquals(item.transform.translation,rotated.transform.translation)
        assertEquals(PI/2,rotated.transform.rotation.z,1e-12)
        val scaled=EditCommands.transform(doc,ids,EditDelta(pivot=item.transform.translation,scale=2.0)).preview(doc).elements.single()
        assertEquals(item.transform.translation,scaled.transform.translation);assertEquals(2.0,scaled.transform.scale.x,0.0)
        val props=readProperties(doc,item)
        val resized=EditCommands.replace(doc,listOf(applyProperties(doc,item,props.copy(size=Point3(6.0,8.0,0.0))))).preview(doc)
        assertEquals(item.transform.translation,resized.elements.single().transform.translation)
        assertThrows(IllegalArgumentException::class.java){EditCommands.replace(doc,listOf(applyProperties(doc,item,
            props.copy(position=Point3(.01)))))}
        assertEquals(item.transform.translation,EditCommands.mirror(doc,ids,MirrorAxis.X,Point3()).preview(doc).elements.single().transform.translation)
        assertThrows(IllegalArgumentException::class.java){EditCommands.mirror(doc,ids,MirrorAxis.X,Point3(1.0))}
        assertTrue(EditCommands.delete(doc,ids).preview(doc).elements.isEmpty())
    }

    @Test fun lockModesUnlockAndDisplayEachHaveSparseExactUndoRedo() {
        val doc=fixture();val id=doc.elements.single().id;val history=EditHistory()
        val locked=history.execute(doc,PresentationCommands.setLock(doc,setOf(id),LockMode.ALL))
        val hidden=history.execute(locked,PresentationCommands.setDisplay(locked,setOf(id),ObjectDisplay.HIDDEN))
        val xray=history.execute(hidden,PresentationCommands.setDisplay(hidden,setOf(id),ObjectDisplay.XRAY))
        val unlocked=history.execute(xray,PresentationCommands.setLock(xray,setOf(id),LockMode.NONE))
        assertEquals(LockMode.NONE,unlocked.elements.single().lockMode)
        assertEquals(xray,history.undo(unlocked));assertEquals(hidden,history.undo(xray));assertEquals(locked,history.undo(hidden));assertEquals(doc,history.undo(locked))
        assertEquals(locked,history.redo(doc));assertEquals(hidden,history.redo(locked));assertEquals(xray,history.redo(hidden));assertEquals(unlocked,history.redo(xray))
        assertEquals(1,PresentationCommands.setDisplay(doc,setOf(id),ObjectDisplay.WIREFRAME).changedElementCount)
    }

    @Test fun trustedPresentationCanChangeLockedLayersButCannotSmuggleGeometryOrColour() {
        val doc=withLock(LockMode.ALL).let {it.copy(layers=it.layers.map {layer->layer.copy(locked=true)})}
        val item=doc.elements.single()
        val shown=PresentationCommands.setDisplay(doc,setOf(item.id),ObjectDisplay.WIREFRAME).preview(doc)
        assertEquals(ObjectDisplay.WIREFRAME,shown.elements.single().objectDisplay)
        val unlocked=PresentationCommands.setLock(doc,setOf(item.id),LockMode.NONE).preview(doc)
        assertEquals(LockMode.NONE,unlocked.elements.single().lockMode)
        assertThrows(IllegalArgumentException::class.java){EditCommands.replace(doc,listOf(item.withLock(LockMode.NONE)))}
        assertThrows(IllegalArgumentException::class.java){EditCommands.presentation(doc,listOf(item.withDisplay(ObjectDisplay.HIDDEN)
            .copy(transform=Transform3(translation=Point3(1.0)))))}
        assertThrows(IllegalArgumentException::class.java){EditCommands.presentation(doc,listOf(item.withLock(LockMode.NONE)
            .copy(properties=item.properties+(ELEMENT_COLOUR_KEY to ParameterValue.Colour(0xFFFF0000)))))}
        assertThrows(IllegalArgumentException::class.java){EditCommands.presentationBetween(doc,doc.copy(layers=doc.layers.map {it.copy(name="Renamed")}))}
    }

    @Test fun lockAllTargetsCurrentLevelAndUndoRestoresEachPriorMode() {
        val doc=fixture().let {it.copy(elements=listOf(it.elements.single().copy(levelId=ground),
            EditFixtures.line("up").copy(levelId=upper).withLock(LockMode.POSITION_ONLY),EditFixtures.line("global")))}
        val history=EditHistory()
        val locked=history.execute(doc,PresentationCommands.lockLevel(doc,ground,LockMode.ALL))
        assertEquals(listOf(LockMode.ALL,LockMode.POSITION_ONLY,LockMode.NONE),locked.elements.map {it.lockMode})
        val all=history.execute(locked,PresentationCommands.lockLevel(locked,null,LockMode.ALL))
        assertTrue(all.elements.all {it.lockMode==LockMode.ALL})
        val unlocked=history.execute(all,PresentationCommands.lockLevel(all,ground,LockMode.NONE))
        assertEquals(listOf(LockMode.NONE,LockMode.ALL,LockMode.ALL),unlocked.elements.map {it.lockMode})
        assertEquals(all,history.undo(unlocked));assertEquals(locked,history.undo(all));assertEquals(doc,history.undo(locked))
    }

    @Test fun roomSidesAreIndependentAndDisplayNeverChangesGeometryOrMeasurements() {
        val initial=EditFixtures.linkedDocument()
        val roomId=ElementId("room");val wallId=EditFixtures.wallId
        val geometry=createMeasurement(initial,Point3(.3,0.0,1.2),Point3(),Point3(.15,.4,1.2),referenceElementId=wallId)
        val measurement=Element(ElementId("dimension"),owner,EditFixtures.layerId,ElementBody.DrawingShape(geometry.shape),transform=geometry.transform)
        val doc=initial.copy(elements=initial.elements+measurement)
        val summary=resolveMeasurement(doc,measurement)
        val history=EditHistory()
        val hidden=history.execute(doc,PresentationCommands.setRoomSideDisplay(doc,RoomSideRef(roomId,0),ObjectDisplay.HIDDEN))
        val xray=history.execute(hidden,PresentationCommands.setRoomSideDisplay(hidden,RoomSideRef(roomId,1),ObjectDisplay.XRAY))
        val room=xray.elements.single {it.id==roomId}
        assertEquals(ObjectDisplay.HIDDEN,xray.elements.single {it.id==wallId}.objectDisplay)
        assertEquals("Linked sides edit their physical wall, not a room override",ObjectDisplay.NORMAL,room.roomSideDisplay(RoomSideRef(roomId,0)))
        assertEquals(ObjectDisplay.XRAY,room.roomSideDisplay(RoomSideRef(roomId,1)))
        assertEquals(ObjectDisplay.NORMAL,room.roomSideDisplay(RoomSideRef(roomId,2)))
        assertEquals(doc.elements.map {it.body},xray.elements.map {it.body})
        assertEquals(doc.elements.map {it.transform},xray.elements.map {it.transform})
        assertEquals(summary,resolveMeasurement(xray,measurement))
        val shown=history.execute(xray,PresentationCommands.showRoomWalls(xray,roomId))
        assertTrue((0..2).all {shown.elements.single {it.id==roomId}.roomSideDisplay(RoomSideRef(roomId,it))==ObjectDisplay.NORMAL})
        assertEquals(xray,history.undo(shown));assertEquals(hidden,history.undo(xray));assertEquals(doc,history.undo(hidden))
    }

    @Test fun showAllRestoresHiddenLevelsLayersSystemsAndSidesWithoutDestroyingXrayChoices() {
        val doc=EditFixtures.linkedDocument().let {it.copy(levels=fixture().levels,
            layers=it.layers.map {layer->layer.copy(visible=false,locked=true)},
            systems=listOf(BuildingSystem(SystemId("power"),owner,"Power",Discipline.ELECTRICAL,0xFF008800,visible=false)),
            elements=it.elements.map {element->when(element.id.value) {
                "room"->element.withRoomSideDisplay(RoomSideRef(element.id,0),ObjectDisplay.HIDDEN)
                    .withRoomSideDisplay(RoomSideRef(element.id,1),ObjectDisplay.XRAY).withLock(LockMode.ALL)
                "wall"->element.withDisplay(ObjectDisplay.HIDDEN).withLock(LockMode.ALL)
                "component"->element.withDisplay(ObjectDisplay.XRAY)
                else->element
            }},project=it.project.copy(settings=it.project.settings.copy(hiddenLevelIds=setOf(ground),showMeasurements=false)))}
        val shown=PresentationCommands.showAll(doc).preview(doc)
        assertTrue(shown.layers.all {it.visible});assertTrue(shown.layers.all {it.locked});assertTrue(shown.systems.all {it.visible})
        assertTrue(shown.project.settings.hiddenLevelIds.isEmpty());assertTrue(shown.project.settings.showMeasurements)
        assertEquals(ObjectDisplay.NORMAL,shown.elements.single {it.id.value=="wall"}.objectDisplay)
        assertEquals(ObjectDisplay.XRAY,shown.elements.single {it.id.value=="component"}.objectDisplay)
        val room=shown.elements.single {it.id.value=="room"}
        assertEquals(ObjectDisplay.NORMAL,room.roomSideDisplay(RoomSideRef(room.id,0)))
        assertEquals(ObjectDisplay.XRAY,room.roomSideDisplay(RoomSideRef(room.id,1)))
        assertEquals(LockMode.ALL,room.lockMode)
        val history=EditHistory();val changed=history.execute(doc,PresentationCommands.setLevelVisibility(doc,upper,false))
        assertEquals(setOf(ground,upper),changed.project.settings.hiddenLevelIds)
        assertEquals(doc,history.undo(changed))
    }

    @Test fun settingsUndoAndRedoPreserveLayoutChangedIndependentlyAfterTheEdit() {
        val doc=fixture();val history=EditHistory()
        val settings=doc.project.settings.copy(showHiddenObjects=true,autoXrayFrontWalls=true,lengthUnit=LengthUnit.FEET)
        val edited=history.execute(doc,EditCommands.between(doc,doc.copy(project=doc.project.copy(settings=settings))))
        val layout=edited.project.settings.canvasLayout.withPanel(CanvasPanelId.DRAW,PanelPlacement(PanelVisibility.OPEN,.8,.6)).copy(hideAll=true)
        val moved=edited.copy(project=edited.project.copy(updatedAtEpochMillis=99,settings=edited.project.settings.copy(canvasLayout=layout)))
        val undone=history.undo(moved)
        assertEquals(doc.project.settings.copy(canvasLayout=layout),undone.project.settings)
        assertEquals(99L,undone.project.updatedAtEpochMillis)
        assertEquals(moved,history.redo(undone))
        val layoutOnly=doc.copy(project=doc.project.copy(settings=doc.project.settings.copy(canvasLayout=layout)))
        assertTrue("Panel layout never enters document undo",EditCommands.between(doc,layoutOnly).isEmpty)
    }

    @Test fun generationPreferenceLocksOnlyNewUnlockedWallsAndIsOneCreationUndo() {
        val before=fixture();val oldWall=EditFixtures.line("existing-wall").copy(body=ElementBody.Wall(Point2(0.0,0.0),Point2(2.0,0.0),.2,3.0))
        val doc=before.copy(elements=before.elements+oldWall)
        val newWall=oldWall.copy(id=ElementId("generated-wall"))
        val after=doc.copy(elements=doc.elements+newWall+EditFixtures.line("new-line"))
        assertSame(after,lockGeneratedWalls(doc,after,false))
        val generated=lockGeneratedWalls(doc,after,true)
        assertEquals(LockMode.NONE,generated.elements.single {it.id==oldWall.id}.lockMode)
        assertEquals(LockMode.ALL,generated.elements.single {it.id==newWall.id}.lockMode)
        assertEquals(LockMode.NONE,generated.elements.single {it.id.value=="new-line"}.lockMode)
        assertEquals(after.elements.map {it.body},generated.elements.map {it.body})
        val history=EditHistory();val created=history.execute(doc,EditCommands.between(doc,generated))
        assertEquals(doc,history.undo(created));assertEquals(created,history.redo(doc))
    }
    @Test fun showRoomWallsRestoresASharedPhysicalWallAndUndoRestoresBothRoomOverrides() {
        val initial=EditFixtures.linkedDocument()
        val room=initial.elements.first {it.body is ElementBody.Room}
        val face=(room.body as ElementBody.Room).wallFaces.first()
        val neighbour=room.copy(id=ElementId("neighbour-room")).withRoomSideDisplay(
            RoomSideRef(ElementId("neighbour-room"),face.edgeIndex,face.ringIndex),ObjectDisplay.HIDDEN)
        val doc=initial.copy(elements=initial.elements+neighbour)
        val wall=doc.elements.single {it.id==face.wallId}
        assertEquals(ObjectDisplay.HIDDEN,com.eezydraw.core.geometry.ScenePresentation(doc).display(wall))
        val history=EditHistory()
        val shown=history.execute(doc,PresentationCommands.showRoomWalls(doc,room.id))
        assertEquals(ObjectDisplay.NORMAL,com.eezydraw.core.geometry.ScenePresentation(shown).display(shown.elements.single {it.id==wall.id}))
        assertEquals(doc.elements.map {it.body},shown.elements.map {it.body})
        assertEquals(doc,history.undo(shown))
        val hiddenOwner=shown.copy(elements=shown.elements.map {if(it.id==neighbour.id)it.withDisplay(ObjectDisplay.HIDDEN)else it})
        assertEquals("A hidden neighbouring room cannot hide the visible room's shared wall",ObjectDisplay.NORMAL,
            com.eezydraw.core.geometry.ScenePresentation(hiddenOwner).display(wall))
        val firstOwnerHidden=shown.copy(elements=shown.elements.map {if(it.id==room.id)it.withDisplay(ObjectDisplay.HIDDEN)else it})
        assertEquals("Camera-side metadata follows a visible owner of the shared wall",neighbour.id,
            com.eezydraw.core.geometry.ScenePresentation(firstOwnerHidden).wallSide(wall)?.roomId)
    }

}
