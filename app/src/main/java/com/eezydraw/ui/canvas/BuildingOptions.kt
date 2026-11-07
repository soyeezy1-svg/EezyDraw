package com.eezydraw.ui.canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.eezydraw.core.model.*
@Composable internal fun BuildingOptions(value:RoomWallOptions,onChange:(RoomWallOptions)->Unit,automatic:Boolean,onAutomatic:(Boolean)->Unit,room:Boolean,wallOnly:Boolean=false) {
 Row(Modifier.horizontalScroll(rememberScrollState()).padding(4.dp)) {
  RouteNumber("Wall thickness",value.thickness,{onChange(value.copy(thickness=it))})
  RouteNumber("Wall height",value.height,{onChange(value.copy(height=it))})
  if(!wallOnly) {
  OutlinedTextField(value.material,{if(it.isNotBlank())onChange(value.copy(material=it))},label={Text("Material")},modifier=Modifier.width(150.dp))
  RouteChoice("Alignment",value.alignment,RoomWallAlignment.entries,{onChange(value.copy(alignment=it))}){when(it){RoomWallAlignment.CENTERLINE->"Centerline";RoomWallAlignment.INSIDE_FACE->"Inside face";RoomWallAlignment.OUTSIDE_FACE->"Outside face"}}
  }
  if(room)FilterChip(automatic,{onAutomatic(!automatic)},{Text("Create walls automatically")},Modifier.testTag("room_auto_walls"))
 }
}
@Composable internal fun GenerateWallsDialog(value:RoomWallOptions,onChange:(RoomWallOptions)->Unit,onCreate:()->Unit,onDismiss:()->Unit) {
 AlertDialog(onDismissRequest=onDismiss,title={Text("Generate walls")},text={BuildingOptions(value,onChange,true,{},false)},confirmButton={TextButton(onCreate,Modifier.testTag("generate_walls_create")){Text("Create")}},dismissButton={TextButton(onDismiss){Text("Cancel")}})
}
