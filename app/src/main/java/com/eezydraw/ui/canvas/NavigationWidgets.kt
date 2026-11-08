package com.eezydraw.ui.canvas

import android.graphics.Paint
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import com.eezydraw.core.editing.projectPrecisionPoint
import com.eezydraw.core.geometry.*
import com.eezydraw.core.model.*
import kotlin.math.*

@Composable internal fun NavigationPreferences(settings:AppSettings,onSave:(AppSettings)->Unit,onDismiss:()->Unit) {
    var value by remember {mutableStateOf(settings)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Navigation settings")},text={Column {
        Text("Gesture sensitivity: %.2f".format(java.util.Locale.ROOT,value.gestureSensitivity))
        Slider(value.gestureSensitivity.toFloat(),{value=value.copy(gestureSensitivity=it.toDouble())},valueRange=.25f..4f)
        NavigationSwitch("Invert orbit",value.invertOrbit){value=value.copy(invertOrbit=it)}
        NavigationSwitch("Zoom toward finger (off: center)",value.zoomTowardFinger){value=value.copy(zoomTowardFinger=it)}
        NavigationSwitch("Show axis indicator",value.showAxisIndicator){value=value.copy(showAxisIndicator=it)}
    }},confirmButton={TextButton({onSave(value)},Modifier.testTag("navigation_settings_save")){Text("Save")}},dismissButton={TextButton(onDismiss){Text("Cancel")}})
}
@Composable private fun NavigationSwitch(label:String,checked:Boolean,onChange:(Boolean)->Unit){Row(verticalAlignment=Alignment.CenterVertically){Switch(checked,onChange);Text(label,Modifier.padding(start=8.dp))}}

@Composable internal fun CameraViews(document:ProjectDocument,onSave:(String)->Unit,onRestore:(SavedView)->Unit,onDelete:(ViewId)->Unit) {
    var name by remember {mutableStateOf("")}
    Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp)) {
        OutlinedTextField(name,{name=it.take(120)},label={Text("View name")},singleLine=true,modifier=Modifier.testTag("camera_view_name"))
        TextButton({onSave(name);name=""},enabled=name.isNotBlank() && document.views.size<200,modifier=Modifier.testTag("camera_view_save")){Text("Save view")}
        document.views.forEach {view->Row(verticalAlignment=Alignment.CenterVertically){TextButton({onRestore(view)},Modifier.weight(1f)){Text(view.name)};IconButton({onDelete(view.id)},Modifier.size(48.dp).testTag("camera_view_delete_${view.id.value}")){Icon(Icons.Outlined.Delete,"Delete ${view.name}")}}}
    }
}

internal fun sectionFace(box:Bounds3,face:Int,value:Double):Bounds3 {
    if(!value.isFinite())return box
    val axis=face/2;val low=face%2==0
    val min=doubleArrayOf(box.min.x,box.min.y,box.min.z);val max=doubleArrayOf(box.max.x,box.max.y,box.max.z)
    if(low)min[axis]=value.coerceAtMost(max[axis]-.0001)else max[axis]=value.coerceAtLeast(min[axis]+.0001)
    return Bounds3(Point3(min[0],min[1],min[2]),Point3(max[0],max[1],max[2]))
}
internal fun sectionValue(box:Bounds3,face:Int):Double=if(face%2==0)when(face/2){0->box.min.x;1->box.min.y;else->box.min.z}else when(face/2){0->box.max.x;1->box.max.y;else->box.max.z}
@Composable internal fun SectionBoxOptions(box:Bounds3,onChange:(Bounds3)->Unit,onDisable:()->Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp)) {
        Text("Section box · drag a face handle or enter a position in metres")
        for(face in 0..5){
            val value=sectionValue(box,face)
            var input by remember(value){mutableStateOf(value.toString())}
            OutlinedTextField(input,{text->input=text;text.toDoubleOrNull()?.takeIf {it.isFinite()}?.let {onChange(sectionFace(box,face,it))}},
                label={Text("${listOf("X","Y","Z")[face/2]} ${if(face%2==0)"minimum"else "maximum"} (m)")},singleLine=true,
                isError=input.toDoubleOrNull()?.isFinite()!=true,modifier=Modifier.fillMaxWidth().testTag("section_value_$face"))
        }
        TextButton(onDisable){Text("Disable section box")}
    }
}

@Composable internal fun SectionBoxOverlay(box:Bounds3,viewport:Viewport2D,orbit:OrbitCamera,is3D:Boolean,width:Int,height:Int,onChange:(Bounds3)->Unit) {
    val colour=MaterialTheme.colorScheme.tertiary;val density=LocalDensity.current.density
    val latestBox by rememberUpdatedState(box);val update by rememberUpdatedState(onChange)
    fun project(p:Point3)=projectPrecisionPoint(p,viewport,orbit,is3D,width.toDouble(),height.toDouble())?.let {Offset(it.x.toFloat(),it.y.toFloat())}
    val corners=listOf(box.min.x,box.max.x).flatMap {x->listOf(box.min.y,box.max.y).flatMap {y->listOf(box.min.z,box.max.z).map {z->Point3(x,y,z)}}}
    Canvas(Modifier.fillMaxSize()) {for(i in corners.indices)for(bit in listOf(1,2,4)){val j=i xor bit;if(j>i){val a=project(corners[i]);val b=project(corners[j]);if(a!=null&&b!=null)drawLine(colour,a,b,1.5.dp.toPx())}}}
    for(face in 0..5){val axis=face/2;val centre=Point3((box.min.x+box.max.x)/2,(box.min.y+box.max.y)/2,(box.min.z+box.max.z)/2)
        val point=when(axis){0->centre.copy(x=sectionValue(box,face));1->centre.copy(y=sectionValue(box,face));else->centre.copy(z=sectionValue(box,face))}
        val at=project(point) ?: continue
        val toward=project(when(axis){0->point.copy(x=point.x+1);1->point.copy(y=point.y+1);else->point.copy(z=point.z+1)}) ?: continue
        val vector=toward-at;val squared=vector.x*vector.x+vector.y*vector.y
        if(squared<1e-6)continue
        Box(Modifier.offset {IntOffset((at.x-24*density).roundToInt(),(at.y-24*density).roundToInt())}.size(48.dp).testTag("section_face_$face")
            .semantics {contentDescription="Section ${listOf("X","Y","Z")[axis]} ${if(face%2==0)"minimum"else "maximum"}"}
            .pointerInput(viewport,orbit,face){awaitEachGesture {val down=awaitFirstDown(requireUnconsumed=false);down.consume();var previous=down.position;while(true){val event=awaitPointerEvent();val change=event.changes.firstOrNull {it.id==down.id} ?: break;val drag=change.position-previous;previous=change.position;val delta=(drag.x*vector.x+drag.y*vector.y)/squared;update(sectionFace(latestBox,face,sectionValue(latestBox,face)+delta));change.consume();if(!change.pressed)break}}},contentAlignment=Alignment.Center){Canvas(Modifier.size(16.dp)){drawCircle(colour);drawCircle(Color.White,style=Stroke(2.dp.toPx()))}}
    }
}

@Composable internal fun WalkPad(onMove:(Double,Double)->Unit,modifier:Modifier=Modifier) {
    val move by rememberUpdatedState(onMove)
    Surface(modifier.size(112.dp).testTag("walk_joystick"),shape=CircleShape,color=MaterialTheme.colorScheme.surface.copy(alpha=.85f),tonalElevation=5.dp) {
        Box(Modifier.pointerInput(Unit){awaitEachGesture {val down=awaitFirstDown(requireUnconsumed=false);down.consume();var previous=down.position;while(true){val event=awaitPointerEvent();val change=event.changes.firstOrNull {it.id==down.id} ?: break;val drag=change.position-previous;previous=change.position;move(drag.x.toDouble(),-drag.y.toDouble());change.consume();if(!change.pressed)break}}},contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Outlined.OpenWith,"Drag to walk",Modifier.size(32.dp));Text("Walk / Roam",style=MaterialTheme.typography.labelSmall)}}
    }
}

@Composable internal fun AxisIndicator(viewport:Viewport2D,orbit:OrbitCamera,is3D:Boolean,modifier:Modifier=Modifier) {
    val ink=MaterialTheme.colorScheme.onSurface
    Canvas(modifier.size(76.dp).testTag("canvas_axes")) {
        val centre=Offset(size.width/2,size.height/2);val scale=23.dp.toPx()
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {textSize=12.dp.toPx();color=android.graphics.Color.BLACK}
        val vectors=listOf(Point3(x=1.0),Point3(y=1.0),Point3(z=1.0))
        vectors.forEachIndexed {i,v->
            val x:Double;val y:Double
            if(is3D){x=-sin(orbit.yaw)*v.x+cos(orbit.yaw)*v.y;y=sin(orbit.pitch)*cos(orbit.yaw)*v.x+sin(orbit.pitch)*sin(orbit.yaw)*v.y-cos(orbit.pitch)*v.z}
            else {x=cos(viewport.rotation)*v.x-sin(viewport.rotation)*v.y;y=-(sin(viewport.rotation)*v.x+cos(viewport.rotation)*v.y)}
            val end=centre+Offset((x*scale).toFloat(),(y*scale).toFloat());val c=listOf(Color(0xFFE54B4B),Color(0xFF39A85B),Color(0xFF3788E5))[i]
            drawLine(c,centre,end,2.dp.toPx());paint.color=android.graphics.Color.argb(255,(c.red*255).toInt(),(c.green*255).toInt(),(c.blue*255).toInt())
            drawContext.canvas.nativeCanvas.drawText(listOf("X","Y","Z")[i],end.x+3.dp.toPx(),end.y-3.dp.toPx(),paint)
        }
        drawCircle(ink,2.dp.toPx(),centre)
    }
}

@Composable internal fun PlanRotationDialog(rotation:Double,onApply:(Double)->Unit,onDismiss:()->Unit) {
    var text by remember {mutableStateOf((rotation*180/PI).toString())}
    val angle=text.toDoubleOrNull();val valid=angle!=null && angle.isFinite()
    AlertDialog(onDismissRequest=onDismiss,title={Text("Rotate view in 2D")},text={Column {
        OutlinedTextField(text,{text=it},label={Text("Plan angle (degrees)")},modifier=Modifier.testTag("plan_rotation_angle"))
        Row {listOf(-15.0,15.0,90.0).forEach {step->TextButton({text=((text.toDoubleOrNull() ?: 0.0)+step).toString()}){Text("${if(step>0)"+"else ""}${step.toInt()}°")}}}
    }},confirmButton={TextButton({onApply((angle!!%360)*PI/180)},enabled=valid,modifier=Modifier.testTag("plan_rotation_apply")){Text("Rotate")}},dismissButton={TextButton(onDismiss){Text("Cancel")}})
}
