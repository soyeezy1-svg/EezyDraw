package com.eezydraw.ui.canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import com.eezydraw.core.model.*
import com.eezydraw.core.geometry.*
import com.eezydraw.core.editing.projectPrecisionPoint
import kotlin.math.roundToInt
@Composable internal fun BoxScope.RoomCornerHandles(e:Element,viewport:Viewport2D,orbit:OrbitCamera,is3D:Boolean,width:Int,height:Int,onMove:(Int,Point3)->Unit) {
 if(e.lockMode!=LockMode.NONE)return
 val room=e.body as ElementBody.Room;val density=LocalDensity.current.density
 room.boundary.outer.forEachIndexed {index,p->
  val world=measurementToWorld(e.transform,Point3(p.x,p.y))
  val screen=projectPrecisionPoint(world,viewport,orbit,is3D,width.toDouble(),height.toDouble()) ?: return@forEachIndexed
  var delta by remember(e,index){mutableStateOf(Offset.Zero)}
  Surface(Modifier.offset {IntOffset((screen.x-14*density+delta.x).roundToInt(),(screen.y-14*density+delta.y).roundToInt())}.size(28.dp).testTag("room_corner_$index").pointerInput(e,viewport,orbit){
   detectDragGestures(onDragStart={delta=Offset.Zero},onDragCancel={delta=Offset.Zero},onDragEnd={
    val x=screen.x+delta.x;val y=screen.y+delta.y
    val at=if(is3D)orbit.rayToPlaneZ(x,y,width.toDouble(),height.toDouble(),world.z)else viewport.screenToWorld(x,y,width.toDouble(),height.toDouble()).let {Point3(it.x,it.y,world.z)}
    if(at!=null)onMove(index,at);delta=Offset.Zero
   }){change,amount->change.consume();delta+=amount}
  },color=MaterialTheme.colorScheme.primary,shape=MaterialTheme.shapes.small){Text("${index+1}")}
 }
}
