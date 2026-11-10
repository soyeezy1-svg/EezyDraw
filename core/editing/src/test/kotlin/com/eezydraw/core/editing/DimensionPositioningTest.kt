package com.eezydraw.core.editing

import com.eezydraw.core.model.*
import com.eezydraw.core.geometry.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class DimensionPositioningTest {
    private val project=ProjectId("dimensions");private val layer=LayerId("drawing");private val level=LevelId("ground")
    private val wall=Element(ElementId("wall"),project,layer,ElementBody.Wall(Point2(-5.0,0.0),Point2(5.0,0.0),.2,3.0),level)
    private val perpendicular=wall.copy(id=ElementId("perpendicular"),body=ElementBody.Wall(Point2(0.0,-5.0),Point2(0.0,5.0),.2,3.0))
    private val item=Element(ElementId("item"),project,layer,ElementBody.DrawingShape(Shape.Box(Size3(1.0,2.0,1.0))),level,transform=Transform3(Point3(2.0,3.0)))
    private val line=Element(ElementId("line"),project,layer,ElementBody.DrawingShape(Shape.Line(Point3(),Point3(4.0))),level)
    private val room=Element(ElementId("room"),project,layer,ElementBody.Room(Polygon2(listOf(Point2(0.0,0.0),Point2(4.0,0.0),Point2(4.0,3.0),Point2(0.0,3.0)))),level)
    private fun doc(vararg elements:Element)=ProjectDocument(Project(project,"Dimension QA",0,0),levels=listOf(Level(level,project,"Ground",0.0,.2,3.0)),layers=listOf(Layer(layer,project,"Drawing")),elements=elements.toList())
    private fun annotation(document:ProjectDocument,binding:DimensionBinding):Element {
        val geometry=createBoundDimension(document,binding)
        return Element(ElementId("dimension"),project,layer,ElementBody.DrawingShape(geometry.shape),level,transform=geometry.transform)
    }
    private fun point(expected:Point3,actual:Point3){assertEquals(expected.x,actual.x,1e-7);assertEquals(expected.y,actual.y,1e-7);assertEquals(expected.z,actual.z,1e-7)}
    private fun rejects(action:()->Unit){try {action();fail("Expected a rejected edit")}catch(e:IllegalArgumentException){assertTrue(e.message.orEmpty().isNotBlank())}}

    @Test fun everyFixedEndResizesWallLineAndRoomEdgeAndUndoRestoresBothGeometryAndDefault() {
        for(host in listOf(wall,line,room))for(fixed in FixedEnd.entries) {
            val base=doc(host);val binding=DimensionBinding(host.id,if(host==room)DimensionProperty.EDGE else DimensionProperty.LENGTH)
            val dimension=annotation(base,binding);val before=base.copy(elements=base.elements+dimension)
            val endpoints=dimensionEndpoints(before,binding)!!
            val length=pointDistance(endpoints.first,endpoints.second)
            val expected=fixedEndpoints(endpoints.first,endpoints.second,length+2,fixed)
            val history=EditHistory();val after=history.execute(before,DimensionEditing.edit(before,dimension.id,length+2,fixed))
            val actual=dimensionEndpoints(after,binding)!!
            point(expected.first,actual.first);point(expected.second,actual.second)
            assertEquals(length+2,resolveMeasurement(after,after.elements.last())!!.distance,1e-7)
            assertEquals(fixed,after.project.settings.dimensionFixedEnd)
            assertEquals(before,history.undo(after));assertEquals(after,history.redo(before))
        }
    }
    @Test fun exactLengthsFollowTheLiveAimAndKeepSurveyPrecisionAndElevation() {
        val start=Point3(1e9, -1e9,4.2)
        val first=endpointAtLength(start,Point3(start.x+3,start.y+4,start.z),2.5)
        point(Point3(start.x+1.5,start.y+2,start.z),first)
        val dragged=endpointAtLength(start,Point3(start.x-4,start.y+3,start.z),2.5)
        assertEquals(2.5,pointDistance(start,dragged),1e-7)
        point(Point3(start.x+2,start.y,start.z),endpointAtLength(start,start,2.0))
        val raisedAim=Point3(start.x+3,start.y+4,start.z+8)
        val wallEnd=drawingEndpoint(start,raisedAim,2.5,true)
        assertEquals(start.z,wallEnd.z,0.0);assertEquals(2.5,pointDistance(start,wallEnd),1e-7)
        val lineEnd=drawingEndpoint(start,raisedAim,2.5,false)
        assertEquals(2.5,pointDistance(start,lineEnd),1e-7);assertTrue(lineEnd.z>start.z)
        point(raisedAim.copy(z=start.z),drawingEndpoint(start,raisedAim,null,true))
        rejects {endpointAtLength(start,first,0.0)};rejects {endpointAtLength(start,first,Double.NaN)}
    }
    @Test fun twoPointMeasurementTracksBothPickedVerticesAfterResizeAndMove() {
        var document=doc(line)
        val geometry=createMeasurement(document,Point3(),Point3(4.0),Point3(2.0,1.0),line.id,line.id)
        val dimension=Element(ElementId("measurement"),project,layer,ElementBody.DrawingShape(geometry.shape),transform=geometry.transform)
        document=document.copy(elements=document.elements+dimension)
        assertEquals(4.0,resolveMeasurement(document,dimension)!!.distance,1e-8)
        val edited=DimensionEditing.edit(document,dimension.id,6.0,FixedEnd.CENTER).preview(document)
        val moved=EditCommands.transform(edited,setOf(line.id),EditDelta(translation=Point3(2.0,3.0))).preview(edited)
        val summary=resolveMeasurement(moved,moved.elements.last())!!
        point(Point3(1.0,3.0),summary.start);point(Point3(7.0,3.0),summary.end)
    }
    @Test fun freeAndCrossObjectMeasurementsDriveTheirPointsOrHosts() {
        val base=doc(line,item)
        val geometry=createMeasurement(base,Point3(4.0),Point3(2.0,3.0),Point3(3.0,2.0),line.id,item.id)
        val d=Element(ElementId("measurement"),project,layer,ElementBody.DrawingShape(geometry.shape),transform=geometry.transform)
        val before=base.copy(elements=base.elements+d)
        val after=DimensionEditing.edit(before,d.id,10.0,FixedEnd.START).preview(before)
        assertEquals(10.0,resolveMeasurement(after,after.elements.last())!!.distance,1e-7)
        assertEquals(line,after.elements.first())
        val free=createMeasurement(base,Point3(),Point3(3.0,4.0),Point3(0.0,1.0))
        val f=d.copy(body=ElementBody.DrawingShape(free.shape),transform=free.transform)
        val document=base.copy(elements=base.elements+f)
        val resized=DimensionEditing.edit(document,f.id,2.0,FixedEnd.END).preview(document)
        point(Point3(3.0,4.0),resolveMeasurement(resized,resized.elements.last())!!.end)
        assertEquals(2.0,resolveMeasurement(resized,resized.elements.last())!!.distance,1e-7)
    }
    @Test fun wallDimensionsContainOnlyThatWallsPropertiesAndFaceChoicesAreReplaceable() {
        val base=doc(wall,perpendicular,item)
        val full=dimensionWall(base,wall.id,WallLengthFace.entries.toSet()).preview(base)
        val dimensions=full.elements.filter(::isMeasurement)
        assertEquals(5,dimensions.size)
        assertTrue(dimensions.all {measurementShape(it)!!.measurement!!.binding!!.elementId==wall.id})
        val values=dimensions.map {resolveMeasurement(full,it)!!.distance}
        assertEquals(listOf(10.0,10.0,10.0,.2,3.0),values)
        val next=dimensionWall(full,wall.id,setOf(WallLengthFace.INSIDE),false,false).preview(full)
        assertEquals(1,next.elements.count(::isMeasurement))
        assertEquals(WallLengthFace.INSIDE,measurementShape(next.elements.last())!!.measurement!!.binding!!.wallFace)
        assertTrue(dimensionWall(next,wall.id,setOf(WallLengthFace.INSIDE),false,false).isEmpty)
    }
    @Test fun thicknessAndHeightEditsUpdatePhysicalWallAndReadout() {
        for(property in listOf(DimensionProperty.THICKNESS,DimensionProperty.HEIGHT)) {
            val base=doc(wall);val d=annotation(base,DimensionBinding(wall.id,property));val before=base.copy(elements=base.elements+d)
            val after=DimensionEditing.edit(before,d.id,1.5,FixedEnd.START).preview(before)
            assertEquals(1.5,resolveMeasurement(after,after.elements.last())!!.distance,1e-7)
        }
    }
    @Test fun wallOnlySnapsNeverSeeANeighboursFarEndpointInPlanOrOrbit() {
        val distant=line.copy(id=ElementId("far"),body=ElementBody.DrawingShape(Shape.Line(Point3(25.0),Point3(26.0))))
        val document=doc(wall,distant);val scene=buildDrawingScene(document)
        val scoped=PointPickContext(document,scene,setOf(wall.id));val viewport=Viewport2D(pixelsPerMetre=10.0)
        val camera=OrbitCamera(target=Point3(10.0,0.0),orthographicHeight=80.0,projection=Projection.ORTHOGRAPHIC)
        val settings=SnapSettings(modes=setOf(SnapMode.ENDPOINT),tolerance=100.0)
        for(three in listOf(false,true)) {
            val screen=projectPrecisionPoint(Point3(25.0),viewport,camera,three,1000.0,800.0)!!
            val pick=scoped.pick(screen,viewport,camera,three,1000.0,800.0,0.0,null,settings,tolerancePixels=500.0)
            assertNotEquals(distant.id,pick.elementId)
            if(pick.elementId!=null)assertEquals(wall.id,pick.elementId)
            if(pick.elementId==wall.id)assertTrue(pick.point.x<=5.00001)
        }
    }
    @Test fun moveFromWallUsesNearCenterAndFarEdgesWithOneUndoStep() {
        for(reference in DistanceReference.entries) {
            val before=doc(wall,item)
            val constraint=WallDistance(wall.id,WallFaceSide.LEFT,1.5,reference)
            val history=EditHistory();val after=history.execute(before,WallPositioning.move(before,item.id,listOf(constraint),false))
            val moved=after.elements.last()
            assertEquals(1.5,WallPositioning.clearance(after,moved,constraint).distance,1e-7)
            assertEquals(item.transform.translation.x,moved.transform.translation.x,1e-8)
            assertEquals(before,history.undo(after));assertEquals(after,history.redo(before))
        }
    }
    @Test fun twoWallSolveWorksForEachReferenceAndForObliqueWalls() {
        for(reference in DistanceReference.entries) {
            val before=doc(wall,perpendicular,item)
            val constraints=listOf(WallDistance(wall.id,WallFaceSide.LEFT,.6,reference),WallDistance(perpendicular.id,WallFaceSide.RIGHT,.8,reference))
            val after=WallPositioning.move(before,item.id,constraints,false).preview(before)
            constraints.forEach {c->assertEquals(c.distance,WallPositioning.clearance(after,after.elements.last(),c).distance,1e-7)}
        }
        val diagonal=perpendicular.copy(transform=Transform3(rotation=Rotation3(z=PI/6)))
        val before=doc(wall,diagonal,item.copy(transform=Transform3(Point3(2.0,3.0),Rotation3(z=.4),Scale3(-1.0,2.0,1.0))))
        val constraints=listOf(WallDistance(wall.id,WallFaceSide.LEFT,1.0),WallDistance(diagonal.id,WallFaceSide.RIGHT,2.0))
        val after=WallPositioning.move(before,item.id,constraints,false).preview(before)
        constraints.forEach {c->assertEquals(c.distance,WallPositioning.clearance(after,after.elements.last(),c).distance,1e-7)}
    }
    @Test fun lockedDistanceFollowsWallChangesAndUndoRestoresTheWholeOperation() {
        val base=doc(wall,perpendicular,item)
        val constraints=listOf(WallDistance(wall.id,WallFaceSide.LEFT,.7),WallDistance(perpendicular.id,WallFaceSide.RIGHT,.9,DistanceReference.CENTER))
        val before=WallPositioning.move(base,item.id,constraints,true).preview(base)
        val history=EditHistory();val after=history.execute(before,EditCommands.transform(before,setOf(wall.id,perpendicular.id),EditDelta(translation=Point3(1.0,2.0))))
        constraints.forEach {c->assertEquals(c.distance,WallPositioning.clearance(after,after.elements.last(),c).distance,1e-7)}
        assertEquals(before,history.undo(after));assertEquals(after,history.redo(before))
        val unlocked=WallPositioning.move(after,item.id,constraints,false).preview(after)
        assertTrue(unlocked.elements.last().wallDistances.isEmpty())
    }
    @Test fun locksRejectDimensionEditsMovesAndWallChangesThatWouldMoveALockedFollower() {
        val base=doc(wall,line,item)
        val d=annotation(base,DimensionBinding(line.id));val before=base.copy(elements=base.elements+d)
        val locked=before.copy(elements=before.elements.map {if(it.id==line.id)it.withLock(LockMode.ALL)else it})
        rejects {DimensionEditing.edit(locked,d.id,8.0,FixedEnd.START)}
        val constraint=WallDistance(wall.id,WallFaceSide.LEFT,.7)
        val following=WallPositioning.move(base,item.id,listOf(constraint),true).preview(base)
        val lockedFollower=following.copy(elements=following.elements.map {if(it.id==item.id)it.withLock(LockMode.ALL)else it})
        rejects {EditCommands.transform(lockedFollower,setOf(wall.id),EditDelta(translation=Point3(y=1.0)))}
        rejects {WallPositioning.move(lockedFollower,item.id,listOf(constraint.copy(distance=2.0)),false)}
        val lockedLayer=following.copy(layers=following.layers.map {it.copy(locked=true)})
        rejects {WallPositioning.move(lockedLayer,item.id,listOf(constraint.copy(distance=2.0)),false)}
    }
    @Test fun deletionDetachesConstraintsAndAnnotationsAndUndoRestoresThem() {
        val base=doc(wall,item);val dimension=annotation(base,DimensionBinding(wall.id));val before=WallPositioning.move(base.copy(elements=base.elements+dimension),item.id,listOf(WallDistance(wall.id,WallFaceSide.LEFT,1.0)),true).preview(base.copy(elements=base.elements+dimension))
        val history=EditHistory();val after=history.execute(before,EditCommands.delete(before,setOf(wall.id)))
        assertTrue(after.elements.first {it.id==item.id}.wallDistances.isEmpty())
        assertNull(measurementShape(after.elements.last())!!.measurement!!.binding)
        assertEquals(before,history.undo(after))
    }
    @Test fun nearestWallsRespectHiddenGeometryAndLevelAndReturnIndependentDirections() {
        val hidden=wall.copy(id=ElementId("hidden"),transform=Transform3(Point3(y=2.5))).withDisplay(ObjectDisplay.HIDDEN)
        val upper=wall.copy(id=ElementId("upper"),levelId=LevelId("upper"),transform=Transform3(Point3(y=2.7)))
        val document=doc(wall,perpendicular,item,hidden,upper)
        val nearest=WallPositioning.nearest(document,item)
        assertEquals(setOf(wall.id,perpendicular.id),nearest.map {it.constraint.wallId}.toSet())
    }
    @Test fun parallelOrCrossLevelSecondWallsAreRefused() {
        val other=wall.copy(id=ElementId("other"),transform=Transform3(Point3(y=5.0)))
        val before=doc(wall,other,item)
        rejects {WallPositioning.move(before,item.id,listOf(WallDistance(wall.id,WallFaceSide.LEFT,1.0),WallDistance(other.id,WallFaceSide.RIGHT,1.0)),false)}
        val upper=before.copy(elements=before.elements.map {if(it.id==other.id)it.copy(levelId=LevelId("upper"))else it})
        rejects {WallPositioning.move(upper,item.id,listOf(WallDistance(other.id,WallFaceSide.RIGHT,1.0)),false)}
    }
    @Test fun generatedWallLengthEditsOwningRoomBoundaryAndReconcilesBackToTheRequestedValue() {
        val base=doc(room)
        val generated=RoomWalls.generate(base,setOf(room.id),RoomWallOptions()).preview(base)
        val generatedWall=generated.elements.first {it.body is ElementBody.Wall}
        val dimension=annotation(generated,DimensionBinding(generatedWall.id))
        val before=generated.copy(elements=generated.elements+dimension)
        val original=dimensionEndpoints(before,DimensionBinding(generatedWall.id))!!
        val requested=pointDistance(original.first,original.second)+1.0
        val prepared=DimensionEditing.edit(before,dimension.id,requested,FixedEnd.END).preview(before)
        val reconciled=RoomWalls.reconcile(before,prepared)
        val command=EditCommands.between(before,reconciled)
        val after=command.preview(before)
        assertEquals(requested,resolveMeasurement(after,after.elements.first {it.id==dimension.id})!!.distance,1e-7)
        val ends=dimensionEndpoints(after,DimensionBinding(generatedWall.id))!!
        point(original.second,ends.second)
    }
    @Test fun copiedWallConstraintsAndSemanticDimensionsFollowTheirCopiedHosts() {
        val base=doc(wall,item)
        val following=WallPositioning.move(base,item.id,listOf(WallDistance(wall.id,WallFaceSide.LEFT,1.0)),true).preview(base)
        val d=annotation(following,DimensionBinding(wall.id))
        val before=following.copy(elements=following.elements+d)
        val copied=EditCommands.paste(before,before.elements,Point3(10.0,10.0)).preview(before)
        val clones=copied.elements.drop(before.elements.size)
        val clonedWall=clones.first {it.body is ElementBody.Wall}
        val clonedItem=clones.first {it.wallDistances.isNotEmpty()}
        val clonedDimension=clones.first(::isMeasurement)
        assertEquals(clonedWall.id,clonedItem.wallDistances.single().wallId)
        assertEquals(clonedWall.id,measurementShape(clonedDimension)!!.measurement!!.binding!!.elementId)
        assertEquals(1.0,WallPositioning.clearance(copied,clonedItem,clonedItem.wallDistances.single()).distance,1e-7)
    }

    @Test fun generatedThicknessAndHeightEditsAffectOnlyThePickedWallAndSurviveRegeneration() {
        val base=doc(room)
        val generated=RoomWalls.generate(base,setOf(room.id),RoomWallOptions()).preview(base)
        for(property in listOf(DimensionProperty.THICKNESS,DimensionProperty.HEIGHT)) {
            val host=generated.elements.first {it.body is ElementBody.Wall}
            val binding=DimensionBinding(host.id,property)
            val value=if(property==DimensionProperty.THICKNESS).4 else 4.2
            val prepared=DimensionEditing.editBinding(generated,binding,value,FixedEnd.START)
            val reconciled=RoomWalls.reconcile(generated,prepared)
            val ends=dimensionEndpoints(reconciled,binding)!!
            assertEquals(value,pointDistance(ends.first,ends.second),1e-7)
            generated.elements.filter {it.body is ElementBody.Wall && it.id!=host.id}.forEach {old->
                val next=reconciled.elements.first {it.id==old.id}.body as ElementBody.Wall
                val previous=old.body as ElementBody.Wall
                assertEquals(previous.thickness,next.thickness,1e-8);assertEquals(previous.height,next.height,1e-8)
            }
            val repeated=RoomWalls.reconcile(reconciled,reconciled)
            assertEquals(reconciled,repeated)
        }
    }
    @Test fun obliqueNonuniformWallThicknessIsPerpendicularToItsFaceAndTiltedHeightsRemainPhysical() {
        val transformed=wall.copy(body=ElementBody.Wall(Point2(0.0,0.0),Point2(3.0,4.0),.2,3.0),transform=Transform3(rotation=Rotation3(z=.3),scale=Scale3(2.0,1.0,1.5)))
        val document=doc(transformed)
        val frame=wallFrames(document).single()
        val ends=dimensionEndpoints(document,DimensionBinding(wall.id,DimensionProperty.THICKNESS))!!
        assertEquals(frame.thickness,pointDistance(ends.first,ends.second),1e-8)
        assertEquals(0.0,(ends.second.x-ends.first.x)*frame.along.x+(ends.second.y-ends.first.y)*frame.along.y,1e-8)
        val tilted=transformed.copy(transform=transformed.transform.copy(rotation=Rotation3(.6,.4,.3)))
        val base=doc(tilted)
        val binding=DimensionBinding(wall.id,DimensionProperty.HEIGHT)
        val height=dimensionEndpoints(base,binding)!!
        assertEquals(4.5,pointDistance(height.first,height.second),1e-7)
        val next=DimensionEditing.editBinding(base,binding,6.0,FixedEnd.START)
        val updated=dimensionEndpoints(next,binding)!!
        assertEquals(6.0,pointDistance(updated.first,updated.second),1e-7)
    }
    @Test fun angularValueEditingPreservesTheOriginalThreeDimensionalPlane() {
        val shape=Shape.Dimension(DimensionKind.ANGULAR,listOf(Point3(),Point3(2.0),Point3(y=3.0)),Point3(1.0,1.0))
        val angle=line.copy(body=ElementBody.DrawingShape(shape),transform=Transform3(rotation=Rotation3(.5,.7,.3),scale=Scale3(2.0,1.0,1.0)))
        val before=doc(angle)
        val after=DimensionEditing.edit(before,angle.id,PI/3,FixedEnd.START).preview(before)
        val edited=after.elements.single();val points=((edited.body as ElementBody.DrawingShape).shape as Shape.Dimension).anchors.map {measurementToWorld(edited.transform,it)}
        val a=points[0];val b=points[1];val c=points[2]
        val dot=((b.x-a.x)*(c.x-a.x)+(b.y-a.y)*(c.y-a.y)+(b.z-a.z)*(c.z-a.z))/(pointDistance(a,b)*pointDistance(a,c))
        assertEquals(PI/3,acos(dot),1e-7)
        rejects {DimensionEditing.edit(before,angle.id,PI*1.5,FixedEnd.START)}
        val incomplete=angle.copy(body=ElementBody.DrawingShape(shape.copy(anchors=shape.anchors.take(2))))
        rejects {DimensionEditing.edit(doc(incomplete),incomplete.id,PI/3,FixedEnd.START)}
    }

    @Test fun mitredFaceDimensionsResolveTheRequestedLengthAfterRoomJunctionChanges() {
        val base=doc(room)
        val generated=RoomWalls.generate(base,setOf(room.id),RoomWallOptions()).preview(base)
        val host=generated.elements.first {it.body is ElementBody.Wall}
        for(face in listOf(WallLengthFace.INSIDE,WallLengthFace.OUTSIDE))for(fixed in FixedEnd.entries) {
            val binding=DimensionBinding(host.id,wallFace=face)
            val dimension=annotation(generated,binding)
            val before=generated.copy(elements=generated.elements+dimension)
            val ends=dimensionEndpoints(before,binding)!!
            val requested=pointDistance(ends.first,ends.second)+.6
            val after=DimensionEditing.edit(before,dimension.id,requested,fixed).preview(before)
            assertEquals(requested,resolveMeasurement(after,after.elements.first {it.id==dimension.id})!!.distance,1e-7)
        }
    }

}
