package com.eezydraw.core.editing
import com.eezydraw.core.model.*
import java.util.UUID
import kotlin.math.*
const val GENERATED_ROOM_WALL="roomGeneratedWall"
object RoomWalls {
 fun generate(document:ProjectDocument,ids:Set<ElementId>,options:RoomWallOptions):EditCommand {
  val next=document.copy(elements=document.elements.map {e->if(e.id !in ids)e else e.copy(body=(e.body as ElementBody.Room).copy(wallOptions=options))})
  return EditCommands.between(document,reconcile(document,next),"Generate walls")
 }
 fun reconcile(before:ProjectDocument,after:ProjectDocument):ProjectDocument {
  val document=followSharedCorners(before,after)
  data class Edge(val room:Element,val index:Int,val ring:Int?,val a:Point3,val b:Point3,val options:RoomWallOptions)
  val rooms=document.elements.filter {(it.body as? ElementBody.Room)?.wallOptions!=null}
  if(rooms.isEmpty() && document.elements.none {GENERATED_ROOM_WALL in it.properties})return if(document.elements.filter {it.body is ElementBody.Wall}!=before.elements.filter {it.body is ElementBody.Wall})joinedWallProfiles(document)else document
  val edges=rooms.flatMap {e->val r=e.body as ElementBody.Room;(listOf(r.boundary.outer)+r.boundary.holes).flatMapIndexed {ri,ring->ring.indices.map {i->Edge(e,i,if(ri==0)null else ri-1,measurementToWorld(e.transform,Point3(ring[i].x,ring[i].y)),measurementToWorld(e.transform,Point3(ring[(i+1)%ring.size].x,ring[(i+1)%ring.size].y)),r.wallOptions!!)}}}
  val generated=linkedMapOf<String,Element>();val links=mutableMapOf<ElementId,MutableList<RoomWallFace>>();val used=hashSetOf<ElementId>()
  val materials=document.materials.toMutableList()
  fun pointKey(p:Point3)="${round(p.x*1e7).toLong()},${round(p.y*1e7).toLong()},${round(p.z*1e7).toLong()}"
  for(edge in edges) {
   val dx=edge.b.x-edge.a.x;val dy=edge.b.y-edge.a.y;val length=hypot(dx,dy);if(length<1e-8)continue
   val breaks=mutableListOf(0.0,1.0)
   for(other in edges)if(other.room.levelId==edge.room.levelId)for(p in listOf(other.a,other.b)) {
    val t=((p.x-edge.a.x)*dx+(p.y-edge.a.y)*dy)/(length*length)
    if(t>1e-8 && t<1-1e-8 && abs((p.x-edge.a.x)*dy-(p.y-edge.a.y)*dx)<1e-7*length && abs(p.z-edge.a.z)<1e-7)breaks+=t
   }
   fun at(t:Double)=Point3(edge.a.x+dx*t,edge.a.y+dy*t,edge.a.z)
   for((t,u)in breaks.distinct().sorted().zipWithNext()) {
    var a=at(t);var b=at(u);val forward=pointKey(a)<=pointKey(b);if(!forward){val swap=a;a=b;b=swap}
    val key="${edge.room.levelId}:${pointKey(a)}:${pointKey(b)}"
    val wall=generated.getOrPut(key){
     val prior=(before.elements.find {it.id==edge.room.id}?.body as? ElementBody.Room)?.wallFaces?.firstOrNull {it.edgeIndex==edge.index && it.ringIndex==edge.ring}?.wallId
     var id=prior?.takeIf {it !in used && before.elements.any {e->e.id==it && GENERATED_ROOM_WALL in e.properties}} ?: ElementId(UUID.nameUUIDFromBytes(key.toByteArray()).toString())
     if(id in used)id=ElementId(UUID.nameUUIDFromBytes((key+edge.room.id.value).toByteArray()).toString())
     used+=id
     val material=materials.find {it.name.equals(edge.options.material,true)} ?: Material(MaterialId(UUID.nameUUIDFromBytes(edge.options.material.toByteArray()).toString()),edge.options.material,0xFFAAAAAA).also {materials+=it}
     val room=edge.room.body as ElementBody.Room;val ring=if(edge.ring==null)room.boundary.outer else room.boundary.holes[edge.ring]
     val ccw=ring.indices.sumOf {i->val p=ring[i];val q=ring[(i+1)%ring.size];p.x*q.y-q.x*p.y}>0
     val insideLeft=(ccw==(edge.ring==null))==forward
     val reference=when(edge.options.alignment){RoomWallAlignment.CENTERLINE->WallReference.CENTERLINE;RoomWallAlignment.INSIDE_FACE->if(insideLeft)WallReference.LEFT_FINISH_FACE else WallReference.RIGHT_FINISH_FACE;RoomWallAlignment.OUTSIDE_FACE->if(insideLeft)WallReference.RIGHT_FINISH_FACE else WallReference.LEFT_FINISH_FACE}
     val old=before.elements.find {it.id==id}
     val wallBody=ElementBody.Wall(Point2(a.x,a.y),Point2(b.x,b.y),edge.options.thickness,edge.options.height*abs(edge.room.transform.scale.z),reference)
     val base=old ?: Element(id,document.project.id,edge.room.layerId,wallBody,name="Room wall")
     base.copy(layerId=edge.room.layerId,body=wallBody,levelId=edge.room.levelId,transform=Transform3(Point3(z=a.z)),materialId=material.id,properties=base.properties+(GENERATED_ROOM_WALL to ParameterValue.Flag(true)))
    }
    val shape=wall.body as ElementBody.Wall
    val requestedHeight=edge.options.height*abs(edge.room.transform.scale.z)
    if(requestedHeight>shape.height || edge.options.thickness>shape.thickness)generated[key]=wall.copy(body=shape.copy(height=max(shape.height,requestedHeight),thickness=max(shape.thickness,edge.options.thickness)))
    links.getOrPut(edge.room.id){mutableListOf()}.add(RoomWallFace(edge.index,wall.id,if(forward)WallSide.LEFT else WallSide.RIGHT,edge.ring))
   }
  }
  // Keep survivor order so sparse history and existing wall-host references remain stable.
  val values=generated.values.associateBy {it.id}
  val elements=document.elements.mapNotNull {e->when {GENERATED_ROOM_WALL in e.properties->values[e.id];e in rooms->e.copy(body=(e.body as ElementBody.Room).copy(wallFaces=links[e.id].orEmpty()));else->e}}+values.values.filter {w->document.elements.none {it.id==w.id}}
  var result=document.copy(elements=elements,materials=materials)
  val removed=document.elements.filter {GENERATED_ROOM_WALL in it.properties && it.id !in values}.mapTo(hashSetOf()){it.id}
  if(removed.isNotEmpty()) {
   // Let the established delete path detach hosted devices and measurement references.
   val retained=result.elements+document.elements.filter {it.id in removed}
   result=EditCommands.delete(result.copy(elements=retained),removed).preview(result.copy(elements=retained))
  }
  return joinedWallProfiles(result)
 }
 fun create(document:ProjectDocument,kind:String,points:List<Point3>,level:LevelId?,options:RoomWallOptions,automatic:Boolean):EditCommand {
  val layer=document.layers.firstOrNull {it.visible && !it.locked} ?: error("Choose an unlocked visible layer")
  val id=ElementId(UUID.randomUUID().toString());val origin=points.first()
  val body=if(kind=="wall") {require(points.size==2 && points[0]!=points[1]);ElementBody.Wall(Point2(0.0,0.0),Point2(points[1].x-origin.x,points[1].y-origin.y),options.thickness,options.height)}else {
   val sketch=createSketch(if(kind=="room")DrawingTool.RECTANGLE else DrawingTool.POLYGON,points)
   ElementBody.Room((sketch.shape as Shape.Polygon).boundary,wallOptions=options.takeIf {automatic})
  }
  val e=Element(id,document.project.id,layer.id,body,level,name=if(kind=="wall")"Wall"else "Room",transform=Transform3(origin))
  return EditCommands.between(document,reconcile(document,document.copy(elements=document.elements+e)),"Create $kind")
 }
}

/** Miter two-wall corners; T-junctions retain intersecting full-thickness bodies. */
fun joinedWallProfiles(document:ProjectDocument):ProjectDocument {
 val walls=document.elements.filter {it.body is ElementBody.Wall}
 return document.copy(elements=document.elements.map {e->
  val b=e.body as? ElementBody.Wall ?: return@map e
  if(e.transform.rotation!=Rotation3() || e.transform.scale!=Scale3())return@map e
  val a=Point3(b.start.x+e.transform.translation.x,b.start.y+e.transform.translation.y,e.transform.translation.z)
  val z=Point3(b.end.x+e.transform.translation.x,b.end.y+e.transform.translation.y,e.transform.translation.z)
  val dx=z.x-a.x;val dy=z.y-a.y;val len=hypot(dx,dy);val nx=-dy/len;val ny=dx/len
  val left=when(b.reference){WallReference.CENTERLINE->b.thickness/2;WallReference.LEFT_FINISH_FACE->0.0;WallReference.RIGHT_FINISH_FACE->b.thickness}
  fun corner(p:Point3,offset:Double):Point2 {
   val original=Point2(p.x+nx*offset,p.y+ny*offset)
   val neighbors=walls.filter {other->if(other.id==e.id || other.levelId!=e.levelId || other.transform.rotation!=Rotation3() || other.transform.scale!=Scale3())false else {
    val w=other.body as ElementBody.Wall
    listOf(w.start,w.end).any {hypot(it.x+other.transform.translation.x-p.x,it.y+other.transform.translation.y-p.y)<1e-7 && abs(other.transform.translation.z-p.z)<1e-7}
   }}
   if(neighbors.size!=1)return Point2(original.x-e.transform.translation.x,original.y-e.transform.translation.y)
   val other=neighbors.single();val w=other.body as ElementBody.Wall
   val oa=Point2(w.start.x+other.transform.translation.x,w.start.y+other.transform.translation.y);val ob=Point2(w.end.x+other.transform.translation.x,w.end.y+other.transform.translation.y)
   val vx=ob.x-oa.x;val vy=ob.y-oa.y;val vl=hypot(vx,vy);val det=dx*vy-dy*vx
   if(abs(det)<1e-9)return Point2(original.x-e.transform.translation.x,original.y-e.transform.translation.y)
   val ol=when(w.reference){WallReference.CENTERLINE->w.thickness/2;WallReference.LEFT_FINISH_FACE->0.0;WallReference.RIGHT_FINISH_FACE->w.thickness}
   val choices=listOf(ol,ol-w.thickness).map {d->
    val x=oa.x-vy/vl*d;val y=oa.y+vx/vl*d;val t=((x-original.x)*vy-(y-original.y)*vx)/det
    Point2(original.x+dx*t,original.y+dy*t)
   }
   // Match the boundary on the same side of the corner bisector.
   val atStart=hypot(p.x-a.x,p.y-a.y)<1e-7
   val currentX=if(atStart)dx else -dx;val currentY=if(atStart)dy else -dy
   val otherEnd=if(hypot(oa.x-p.x,oa.y-p.y)<1e-7)ob else oa
   val turn=sign((otherEnd.x-p.x)*nx+(otherEnd.y-p.y)*ny)
   val selected=if(abs(offset)<1e-9)choices.minByOrNull {hypot(it.x-p.x,it.y-p.y)}!! else choices.maxByOrNull {q->((q.x-p.x)*currentX+(q.y-p.y)*currentY)*sign(offset)*turn} ?: original
   if(hypot(selected.x-p.x,selected.y-p.y)>max(b.thickness,w.thickness)*5)return Point2(original.x-e.transform.translation.x,original.y-e.transform.translation.y)
   return Point2(selected.x-e.transform.translation.x,selected.y-e.transform.translation.y)
  }
  e.copy(body=b.copy(joinProfile=Polygon2(listOf(corner(a,left),corner(z,left),corner(z,left-b.thickness),corner(a,left-b.thickness)))))
 })
}

private fun followSharedCorners(before:ProjectDocument,after:ProjectDocument):ProjectDocument {
 fun key(level:LevelId?,p:Point3)="$level:${round(p.x*1e7)},${round(p.y*1e7)},${round(p.z*1e7)}"
 val changed=mutableMapOf<String,Point3>()
 for(e in after.elements) {
  val r=e.body as? ElementBody.Room ?: continue
  val old=before.elements.find {it.id==e.id} ?: continue;val b=old.body as? ElementBody.Room ?: continue
  if(e==old || r.boundary.outer.size!=b.boundary.outer.size)continue
  b.boundary.outer.zip(r.boundary.outer).forEach {(a,z)->val from=measurementToWorld(old.transform,Point3(a.x,a.y));val to=measurementToWorld(e.transform,Point3(z.x,z.y))
   if(from!=to)changed[key(e.levelId,from)]=to
  }
 }
 if(changed.isEmpty())return after
 return after.copy(elements=after.elements.map {e->val room=e.body as? ElementBody.Room
  if(room==null || before.elements.find {it.id==e.id}!=e)e else e.copy(body=room.copy(boundary=room.boundary.copy(outer=room.boundary.outer.map {p->
   val world=measurementToWorld(e.transform,Point3(p.x,p.y));val target=changed[key(e.levelId,world)]
   if(target==null)p else measurementToLocal(e.transform,target).let {Point2(it.x,it.y)}
  })))
 })
}
