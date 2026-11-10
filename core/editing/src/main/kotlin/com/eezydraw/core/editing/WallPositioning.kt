package com.eezydraw.core.editing

import com.eezydraw.core.model.*
import com.eezydraw.core.geometry.*
import kotlin.math.*

data class WallClearance(val constraint:WallDistance,val start:Point3,val end:Point3) {
    val distance get()=pointDistance(start,end)
}

object WallPositioning {
    private fun vertices(document:ProjectDocument,item:Element):List<Point3> {
        val scene=buildDrawingScene(unfilteredGeometryDocument(document),setOf(item.id),architecturalCuts=false)
        return scene.segments.flatMap {listOf(it.a,it.b)}.ifEmpty {listOf(item.transform.translation)}
    }
    fun clearance(document:ProjectDocument,item:Element,constraint:WallDistance):WallClearance {
        val frame=requireNotNull(wallFrames(document).find {it.host.id==constraint.wallId && it.roomSide==constraint.roomSide}) {"The reference wall no longer exists."}
        return clearance(frame,vertices(document,item),constraint)
    }
    private fun clearance(frame:WallFrame,points:List<Point3>,constraint:WallDistance):WallClearance {
        val normal=frame.outward(constraint.side)
        val origin=frame.world(0.0,frame.faceDepth(constraint.side),0.0)
        fun projection(p:Point3)=(p.x-origin.x)*normal.x+(p.y-origin.y)*normal.y
        val near=points.minBy(::projection);val far=points.maxBy(::projection)
        val point=when(constraint.reference){DistanceReference.NEAR_EDGE->near;DistanceReference.FAR_EDGE->far;DistanceReference.CENTER->measurementMidpoint(near,far)}
        val signed=projection(point)
        val start=Point3(point.x-normal.x*signed,point.y-normal.y*signed,point.z)
        return WallClearance(constraint.copy(distance=signed.coerceAtLeast(0.0)),start,point)
    }
    fun nearest(document:ProjectDocument,item:Element):List<WallClearance> {
        val presentation=ScenePresentation(document)
        val points=vertices(document,item);val center=points.reduce {a,b->Point3(a.x+b.x,a.y+b.y,a.z+b.z)}.let {Point3(it.x/points.size,it.y/points.size,it.z/points.size)}
        val frames=wallFrames(document)
        val candidates=frames.filter {f->f.host.id!=item.id && (f.host.levelId==null || item.levelId==null || f.host.levelId==item.levelId) && presentation.display(f.host)!=ObjectDisplay.HIDDEN && (f.host.levelId==null || f.host.levelId !in document.project.settings.hiddenLevelIds)}.mapNotNull {f->
            val coordinates=f.coordinates(center)
            if(coordinates.x < -1e-7 || coordinates.x>f.length+1e-7)return@mapNotNull null
            val side=if(coordinates.y<f.thickness/2)WallFaceSide.LEFT else WallFaceSide.RIGHT
            val constraint=WallDistance(f.host.id,side,0.0,roomSide=f.roomSide)
            val clearance=clearance(f,points,constraint)
            // Do not call overlap a positive clearance.
            val n=f.outward(side);if((clearance.end.x-clearance.start.x)*n.x+(clearance.end.y-clearance.start.y)*n.y < -1e-7)null else clearance
        }.sortedBy {it.distance}
        val first=candidates.firstOrNull() ?: return emptyList()
        val a=frames.first {it.host.id==first.constraint.wallId && it.roomSide==first.constraint.roomSide}.outward(first.constraint.side)
        val second=candidates.drop(1).firstOrNull {c->val b=frames.first {it.host.id==c.constraint.wallId && it.roomSide==c.constraint.roomSide}.outward(c.constraint.side);abs(a.x*b.y-a.y*b.x)>1e-5}
        return listOfNotNull(first,second)
    }
    fun move(document:ProjectDocument,id:ElementId,distances:List<WallDistance>,lock:Boolean):EditCommand {
        require(distances.size in 1..2) {"Choose one wall, or two walls for both directions."}
        val item=requireNotNull(document.elements.find {it.id==id})
        require(item.body !is ElementBody.Wall && item.body !is ElementBody.Room && !isMeasurement(item)) {"Select a drawing item or component to move from a wall."}
        require(distances.none {it.wallId==id}) {"An item cannot measure from itself."}
        val moved=position(document,item,distances)
        val walls=distances.map {it.wallId to it.roomSide}.toSet()
        val kept=item.wallDistances.filter {it.wallId to it.roomSide !in walls}
        val freeBody=when(val body=moved.body) {
            is ElementBody.Component->body.copy(host=null,wallService=null)
            is ElementBody.Route->body.copy(host=null,wallService=null)
            else->body
        }
        val updated=moved.copy(body=freeBody,wallDistances=if(lock)(kept+distances).takeLast(2)else kept)
        return EditCommands.replace(document,listOf(updated),"Move from wall")
    }
    private fun position(document:ProjectDocument,item:Element,distances:List<WallDistance>):Element {
        val frames=wallFrames(document)
        val points=vertices(document,item)
        val equations=distances.map {c->
            val f=requireNotNull(frames.find {it.host.id==c.wallId && it.roomSide==c.roomSide}) {"The reference wall no longer exists."}
            require(f.host.levelId==null || item.levelId==null || f.host.levelId==item.levelId) {"Choose a wall on the item's level."}
            val n=f.outward(c.side);val clear=clearance(f,points,c)
            val signed=(clear.end.x-clear.start.x)*n.x+(clear.end.y-clear.start.y)*n.y
            Triple(n.x,n.y,c.distance-signed)
        }
        val a=equations[0]
        val delta=if(equations.size==1)Point3(a.first*a.third,a.second*a.third)else {
            val b=equations[1];val det=a.first*b.second-a.second*b.first
            require(abs(det)>1e-5) {"Choose a second wall in a different direction."}
            Point3((a.third*b.second-a.second*b.third)/det,(a.first*b.third-a.third*b.first)/det)
        }
        if(hypot(delta.x,delta.y)<1e-8)return item
        require(item.lockMode==LockMode.NONE && document.layers.none {it.id==item.layerId && it.locked}) {"Object is locked. Unlock to move."}
        return item.copy(transform=item.transform.copy(translation=Point3(item.transform.translation.x+delta.x,item.transform.translation.y+delta.y,item.transform.translation.z)))
    }
    /** Called by the common command boundary; undo stores the resulting followers atomically. */
    fun reconcile(before:ProjectDocument,after:ProjectDocument):ProjectDocument {
        if(after.elements.none {it.wallDistances.isNotEmpty()})return after
        val faces=wallFrames(after).mapTo(hashSetOf()){it.host.id to it.roomSide}
        val old=before.elements.associateBy {it.id}
        val changedWalls=after.elements.filter {it.body is ElementBody.Wall || it.body is ElementBody.Room}.filter {it!=old[it.id]}.mapTo(hashSetOf()){it.id}
        val next=after.elements.map {item->
            if(item.wallDistances.isEmpty())return@map item
            if(item==old[item.id] && item.wallDistances.none {it.wallId in changedWalls || it.wallId to it.roomSide !in faces})return@map item
            val constraints=item.wallDistances.filter {it.wallId to it.roomSide in faces}
            if(constraints.isEmpty())item.copy(wallDistances=emptyList())else position(after,item,constraints).copy(wallDistances=constraints)
        }
        return if(next==after.elements)after else after.copy(elements=next)
    }
}
