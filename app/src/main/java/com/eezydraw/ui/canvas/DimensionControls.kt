package com.eezydraw.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.eezydraw.core.model.*
import com.eezydraw.core.editing.*
import com.eezydraw.core.geometry.*
import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DimensionValueRequest(val value:Double,val annotationId:ElementId?=null,
    val binding:DimensionBinding?=null,val angular:Boolean=false)

@Composable
internal fun DimensionValueDialog(request:DimensionValueRequest,settings:ProjectSettings,
    onApply:(Double,FixedEnd)->Boolean,onDismiss:()->Unit) {
    val factor=if(request.angular)if(settings.angleFormat==AngleFormat.DEGREES)180/PI else 1.0 else unitFactor(settings.lengthUnit)
    var text by rememberSaveable(request) {mutableStateOf("${request.value*factor}")}
    var fixed by rememberSaveable {mutableStateOf(settings.dimensionFixedEnd)}
    var error by remember {mutableStateOf(false)}
    val value=text.toDoubleOrNull()?.div(factor)?.takeIf {it.isFinite() && it>1e-9 && (!request.angular || it<PI)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Edit dimension")},text={Column {
        OutlinedTextField(text,{text=it;error=false},Modifier.fillMaxWidth().testTag("dimension_value"),singleLine=true,
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),label={Text(if(request.angular)if(settings.angleFormat==AngleFormat.DEGREES)"Angle (degrees)"else "Angle (radians)"else "Distance (${settings.lengthUnit.name.lowercase()})")},isError=value==null)
        if(!request.angular)FixedEnd.entries.forEach {choice->Row {
            RadioButton(fixed==choice,{fixed=choice},Modifier.testTag("dimension_fixed_${choice.name}"))
            TextButton({fixed=choice}){Text(when(choice){FixedEnd.START->"Start fixed";FixedEnd.END->"End fixed";FixedEnd.CENTER->"Center fixed"})}
        }}
        if(error)Text("The change was refused. Check the lock or reference message.",color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton({if(value!=null){if(onApply(value,fixed))onDismiss()else error=true}},enabled=value!=null,modifier=Modifier.testTag("dimension_apply")){Text("Apply")}},
        dismissButton={TextButton(onDismiss){Text("Cancel")}})
}

@Composable
internal fun WallDimensionOptionsDialog(onApply:(Set<WallLengthFace>,Boolean,Boolean)->Unit,onDismiss:()->Unit,
    current:List<DimensionBinding> = emptyList()) {
    var faces by remember {mutableStateOf(if(current.isEmpty())setOf(WallLengthFace.CENTERLINE)else current.filter {it.property==DimensionProperty.LENGTH}.map {it.wallFace}.toSet())}
    var thickness by remember {mutableStateOf(current.isEmpty() || current.any {it.property==DimensionProperty.THICKNESS})}
    var height by remember {mutableStateOf(current.isEmpty() || current.any {it.property==DimensionProperty.HEIGHT})}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Dimension this wall")},text={Column {
        WallLengthFace.entries.forEach {face->Row {Checkbox(face in faces,{faces=if(it)faces+face else faces-face},Modifier.testTag("wall_dimension_${face.name}"));Text(when(face){WallLengthFace.CENTERLINE->"Centerline length";WallLengthFace.INSIDE->"Inside face length";WallLengthFace.OUTSIDE->"Outside face length"})}}
        Row {Checkbox(thickness,{thickness=it});Text("Thickness")};Row {Checkbox(height,{height=it});Text("Height")}
    }},confirmButton={TextButton({onApply(faces,thickness,height);onDismiss()},Modifier.testTag("wall_dimension_apply")){Text("Show dimensions")}},dismissButton={TextButton(onDismiss){Text("Close")}})
}

@Composable
internal fun MoveFromWallDialog(document:ProjectDocument,item:Element,distances:List<WallDistance>,
    onApply:(List<WallDistance>,Boolean)->Boolean,onSecondWall:(List<WallDistance>)->Unit,onDismiss:()->Unit) {
    val settings=document.project.settings;val factor=unitFactor(settings.lengthUnit)
    var values by remember(distances) {mutableStateOf(distances.map {"${it.distance*factor}"})}
    var references by remember(distances) {mutableStateOf(distances.map {it.reference})}
    var sides by remember(distances) {mutableStateOf(distances.map {it.side})}
    var lock by remember {mutableStateOf(distances.any {c->item.wallDistances.any {it.wallId==c.wallId && it.roomSide==c.roomSide}})}
    var failed by remember {mutableStateOf(false)}
    val parsed=values.map {it.toDoubleOrNull()?.div(factor)?.takeIf {v->v.isFinite() && v>=0}}
    fun constraints()=distances.mapIndexed {i,c->c.copy(distance=parsed[i] ?: c.distance,reference=references[i],side=sides[i])}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Move from wall")},text={Column {
        distances.forEachIndexed {i,c->
            Text(document.elements.find {it.id==c.wallId}?.name?.ifBlank {"Wall ${i+1}"} ?: "Wall ${i+1}")
            Row {WallFaceSide.entries.forEach {side->FilterChip(sides[i]==side,{sides=sides.toMutableList().apply {this[i]=side}},label={Text(if(side==WallFaceSide.LEFT)"Left face"else "Right face")})}}
            Row(Modifier.horizontalScroll(rememberScrollState())) {DistanceReference.entries.forEach {reference->FilterChip(references[i]==reference,{references=references.toMutableList().apply {this[i]=reference}},label={Text(referenceLabel(reference))},modifier=Modifier.testTag("wall_reference_${i}_${reference.name}"))}}
            OutlinedTextField(values[i],{text->values=values.toMutableList().apply {this[i]=text}},Modifier.fillMaxWidth().testTag("wall_distance_$i"),singleLine=true,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),label={Text("Distance (${settings.lengthUnit.name.lowercase()})")},isError=parsed[i]==null)
        }
        Row {Checkbox(lock,{lock=it},Modifier.testTag("wall_distance_lock"));Text("Lock distance to wall")}
        if(distances.size==1)TextButton({onSecondWall(constraints())},Modifier.testTag("pick_second_wall"),enabled=parsed.all {it!=null}){Text("Pick a second wall")}
        if(item.wallDistances.isNotEmpty() && !lock)Text("Apply without the lock to release these wall distances.")
        if(failed)Text("The move was refused. Check the lock or wall message.",color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton({if(onApply(constraints(),lock))onDismiss()else failed=true},Modifier.testTag("move_from_wall_apply"),enabled=parsed.all {it!=null}){Text("Apply")}},
        dismissButton={TextButton(onDismiss){Text("Cancel")}})
}

internal fun referenceLabel(reference:DistanceReference)=when(reference){DistanceReference.NEAR_EDGE->"Near edge";DistanceReference.CENTER->"Center";DistanceReference.FAR_EDGE->"Far edge"}

/** Touch targets use the same canonical values and label positions as committed scene annotations. */
@Composable
internal fun DimensionLabels(document:ProjectDocument,selected:Element?,clearances:List<WallClearance>,
    viewport:Viewport2D,orbit:OrbitCamera,is3D:Boolean,width:Int,height:Int,level:String?,preview:EditDelta?,
    onEdit:(DimensionValueRequest)->Unit,onClearance:(WallDistance)->Unit) {
    val density=LocalDensity.current.density
    val presentation=remember(document){ScenePresentation(document)}
    val settings=document.project.settings
    val measurements by produceState<List<Triple<Element,Shape.Dimension,MeasurementSummary?>>>(emptyList(),document) {
        value=withContext(Dispatchers.Default) {
            val elements=document.elements.associateBy {it.id}
            document.elements.mapNotNull {element->
                val shape=(element.body as? ElementBody.DrawingShape)?.shape as? Shape.Dimension ?: return@mapNotNull null
                Triple(element,shape,resolveMeasurement(document,element,elements))
            }
        }
    }
    val frames=remember(document){wallFrames(document).associateBy {it.host.id to it.roomSide}}
    fun screen(point:Point3)=projectPrecisionPoint(point,viewport,orbit,is3D,width.toDouble(),height.toDouble())
    @Composable fun label(at:Point3,text:String,tag:String,click:()->Unit) {
        val p=screen(at)
        if(p!=null && p.x in 0.0..width.toDouble() && p.y in 0.0..height.toDouble())Surface(Modifier.offset {IntOffset((p.x-45*density).roundToInt(),(p.y-24*density).roundToInt())},tonalElevation=2.dp) {
            TextButton(click,Modifier.heightIn(min=48.dp).testTag(tag),contentPadding=PaddingValues(horizontal=6.dp)){Text(text,style=MaterialTheme.typography.labelMedium)}
        }
    }
    Box(Modifier.fillMaxSize()) {
        if(settings.showMeasurements)measurements.forEach {(element,shape,summary)->
            if(presentation.display(element)!=ObjectDisplay.HIDDEN && (level==null || element.levelId==null || element.levelId?.value==level) && element.levelId !in settings.hiddenLevelIds && document.layers.any {it.id==element.layerId && it.visible} && document.systems.none {it.id==element.systemId && !it.visible}) {
                val a=measurementToWorld(element.transform,shape.anchors[0]);val b=measurementToWorld(element.transform,shape.anchors[1])
                val angular=shape.kind==DimensionKind.ANGULAR
                if(angular && shape.anchors.size<3)return@forEach
                val value=summary?.distance ?: if(angular) {
                    val c=measurementToWorld(element.transform,shape.anchors[2])
                    val ab=pointDistance(a,b);val ac=pointDistance(a,c)
                    if(ab<1e-9 || ac<1e-9)0.0 else acos((((b.x-a.x)/ab)*((c.x-a.x)/ac)+((b.y-a.y)/ab)*((c.y-a.y)/ac)+((b.z-a.z)/ab)*((c.z-a.z)/ac)).coerceIn(-1.0,1.0))
                }else pointDistance(a,b)
                val base=when(shape.kind){DimensionKind.ALIGNED->measurementMidpoint(shape.anchors[0],shape.anchors[1]);DimensionKind.RADIAL->shape.anchors[1];DimensionKind.ANGULAR->shape.anchors[0]}
                val at=summary?.labelPoint ?: measurementToWorld(element.transform,Point3(base.x+shape.labelOffset.x,base.y+shape.labelOffset.y,base.z+shape.labelOffset.z))
                val text=if(angular)"${coordinate(if(settings.angleFormat==AngleFormat.DEGREES)value*180/PI else value,LengthUnit.METRES,settings.decimalPlaces)} ${if(settings.angleFormat==AngleFormat.DEGREES)"°"else "rad"}"else coordinate(value,settings.lengthUnit,settings.decimalPlaces)
                label(at,text,"dimension_label_${element.id.value}"){onEdit(DimensionValueRequest(value,element.id,angular=angular))}
            }
        }
        if(selected?.body is ElementBody.Room) {
            val room=selected.body as ElementBody.Room
            (listOf(room.boundary.outer)+room.boundary.holes).forEachIndexed {ring,points->points.indices.forEach {i->
                val binding=DimensionBinding(selected.id,DimensionProperty.EDGE,i,if(ring==0)null else ring-1)
                dimensionEndpoints(document,binding)?.let {(a,b)->val value=pointDistance(a,b)
                    label(measurementMidpoint(a,b),coordinate(value,settings.lengthUnit,settings.decimalPlaces),"room_edge_${ring}_$i"){onEdit(DimensionValueRequest(value,binding=binding))}
                }
            }}
        }
        val colour=MaterialTheme.colorScheme.primary
        clearances.forEachIndexed {index,clearance->
            val n=frames[clearance.constraint.wallId to clearance.constraint.roomSide]?.outward(clearance.constraint.side) ?: return@forEachIndexed
            val end=preview?.apply(clearance.end) ?: clearance.end
            val signed=(end.x-clearance.start.x)*n.x+(end.y-clearance.start.y)*n.y
            val start=Point3(end.x-n.x*signed,end.y-n.y*signed,end.z)
            val a=screen(start);val b=screen(end)
            if(a!=null && b!=null)Canvas(Modifier.fillMaxSize()){drawLine(colour,Offset(a.x.toFloat(),a.y.toFloat()),Offset(b.x.toFloat(),b.y.toFloat()),2*density)}
            label(measurementMidpoint(start,end),"${coordinate(abs(signed),settings.lengthUnit,settings.decimalPlaces)} · ${referenceLabel(clearance.constraint.reference)}","item_wall_distance_$index"){onClearance(clearance.constraint.copy(distance=abs(signed)))}
        }
    }
}

@Composable
internal fun SelectedItemNudges(settings:ProjectSettings,onMove:(Point3)->Unit,onGrid:()->Unit,modifier:Modifier=Modifier) {
    var step by rememberSaveable {mutableStateOf("${settings.snap.gridSpacing*unitFactor(settings.lengthUnit)}")}
    val metres=step.toDoubleOrNull()?.div(unitFactor(settings.lengthUnit))?.takeIf {it.isFinite() && it>0}
    Surface(modifier,tonalElevation=3.dp){Row(Modifier.horizontalScroll(rememberScrollState())) {
        OutlinedTextField(step,{step=it},Modifier.width(100.dp).testTag("item_nudge_step"),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),label={Text("Step")})
        listOf("←" to Point3(-1.0),"→" to Point3(1.0),"↑" to Point3(y=1.0),"↓" to Point3(y= -1.0)).forEachIndexed {i,(label,direction)->TextButton({metres?.let {onMove(Point3(direction.x*it,direction.y*it))}},Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).testTag("item_nudge_$i"),enabled=metres!=null){Text(label)}}
        TextButton(onGrid,Modifier.heightIn(min=48.dp).testTag("item_snap_grid")){Text("Snap to grid")}
    }}
}
