package com.eezydraw.core.editing
import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test
class RoomWallsTest {
 val p=ProjectId("p");val level=LevelId("l")
 val doc=ProjectDocument(Project(p,"Rooms",0,0),levels=listOf(Level(level,p,"Ground",0.0,.2,3.2)),layers=listOf(Layer(LayerId("base"),p,"Base")))
 fun room(x:Double=0.0,automatic:Boolean=true,document:ProjectDocument=doc)=RoomWalls.create(document,"room",listOf(Point3(x,0.0),Point3(x+4,4.0)),level,RoomWallOptions(),automatic).preview(document)
 @Test fun automaticAndManualGenerationAreUndoableAndLevelOwned() {
  val off=room(automatic=false);assertEquals(1,off.elements.size)
  val command=RoomWalls.generate(off,setOf(off.elements.single().id),RoomWallOptions(.15,2.8,"Brick",RoomWallAlignment.INSIDE_FACE))
  val h=EditHistory();val generated=h.execute(off,command)
  assertEquals(4,generated.elements.count {it.body is ElementBody.Wall})
  assertTrue(generated.elements.all {it.levelId==level})
  assertEquals(off,h.undo(generated));assertEquals(generated,h.redo(off))
 }
 @Test fun adjoiningRoomsShareOneWallAndUpdatesFollowRoomGeometry() {
  val first=room();val both=room(4.0,document=first)
  assertEquals(7,both.elements.count {it.body is ElementBody.Wall})
  val rooms=both.elements.filter {it.body is ElementBody.Room}
  val links=rooms.map {(it.body as ElementBody.Room).wallFaces.map {it.wallId}.toSet()}
  assertEquals(1,links[0].intersect(links[1]).size)
  val high=rooms[1].copy(transform=rooms[1].transform.copy(scale=Scale3(z=2.0)))
  val tallerShared=RoomWalls.reconcile(both,both.copy(elements=both.elements.map {if(it.id==high.id)high else it}))
  val sharedId=links[0].intersect(links[1]).single()
  assertEquals(6.0,(tallerShared.elements.first {it.id==sharedId}.body as ElementBody.Wall).height,0.0)
  val edited=rooms.first().copy(transform=Transform3(Point3(1.0,0.0)))
  val followed=RoomWalls.reconcile(both,both.copy(elements=both.elements.map {if(it.id==edited.id)edited else it}))
  assertEquals(followed.elements.size,followed.elements.map {it.id}.toSet().size)
  assertEquals(7,followed.elements.count {it.body is ElementBody.Wall})
  val shared=followed.elements.filter {it.body is ElementBody.Room}.map {(it.body as ElementBody.Room).wallFaces.map {it.wallId}.toSet()}
  assertEquals(1,shared[0].intersect(shared[1]).size)
  assertTrue(followed.elements.filter {it.body is ElementBody.Wall}.all {(it.body as ElementBody.Wall).joinProfile!=null})
  val e=first.elements.first {it.body is ElementBody.Room};val moved=e.copy(transform=Transform3(Point3(2.0,0.0)))
  val after=RoomWalls.reconcile(first,first.copy(elements=first.elements.map {if(it.id==e.id)moved else it}))
  assertEquals(4,after.elements.count {it.body is ElementBody.Wall})
  assertTrue(after.elements.filter {it.body is ElementBody.Wall}.all {(it.body as ElementBody.Wall).start.x>=2})
 }
 @Test fun standaloneWallsAndMaterialDefaultsAreCreatedWithoutRooms() {
  val result=RoomWalls.create(doc,"wall",listOf(Point3(),Point3(5.0,0.0)),level,RoomWallOptions(.25,3.0),false).preview(doc)
  assertEquals(.25,(result.elements.single().body as ElementBody.Wall).thickness,0.0)
  assertEquals(level,result.elements.single().levelId)
  val chained=RoomWalls.create(result,"wall",listOf(Point3(5.0,0.0),Point3(5.0,5.0)),level,RoomWallOptions(.25,3.0),false).preview(result)
  val survivor=result.elements.single()
  val restored=RoomWalls.reconcile(chained,chained.copy(elements=chained.elements.filter {it.id==survivor.id}))
  assertEquals((result.elements.single().body as ElementBody.Wall).joinProfile,(restored.elements.single().body as ElementBody.Wall).joinProfile)
  assertTrue(ProjectSettings().autoCreateWalls)
  val original=room();val source=original.elements.first {it.body is ElementBody.Room}
  val taller=source.copy(transform=source.transform.copy(scale=Scale3(z=2.0)))
  val updated=RoomWalls.reconcile(original,original.copy(elements=original.elements.map {if(it.id==source.id)taller else it}))
  assertTrue(updated.elements.filter {it.body is ElementBody.Wall}.all {(it.body as ElementBody.Wall).height==6.0})
 }
}
