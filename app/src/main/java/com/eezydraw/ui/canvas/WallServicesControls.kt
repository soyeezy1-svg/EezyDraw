package com.eezydraw.ui.canvas

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.eezydraw.core.model.*
import com.eezydraw.core.editing.WallMountProperties
import java.util.Locale

internal fun wallModeLabel(mode:WallServiceMode)=when(mode){WallServiceMode.SURFACE->"Surface mounted";WallServiceMode.CHASE->"Concealed (chase)";WallServiceMode.RECESS->"Recess / back box";WallServiceMode.OPENING->"Through opening"}
@Composable
internal fun WallDeviceBar(item:LibraryItem,options:WallServiceOptions,onChange:(WallServiceOptions)->Unit,mount:WallMountProperties?=null,step:Double=.01) {
    Surface(tonalElevation=2.dp) {Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(4.dp).testTag("wall_device_settings"),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        mount?.let {Text("Face: ${it.face}",Modifier.testTag("wall_face_label"),style=MaterialTheme.typography.labelMedium)}
        RouteChoice("Wall face",options.side,listOf(null,WallFaceSide.LEFT,WallFaceSide.RIGHT),{onChange(options.copy(side=it))}) {side->
            when(side){null->"Nearest to crosshair";WallFaceSide.LEFT->mount?.leftFace ?: "Left face";WallFaceSide.RIGHT->mount?.rightFace ?: "Right face"}
        }
        TextButton({val side=options.side ?: mount?.side ?: WallFaceSide.LEFT;onChange(options.copy(side=if(side==WallFaceSide.LEFT)WallFaceSide.RIGHT else WallFaceSide.LEFT))},Modifier.testTag("wall_flip_side")){Text("Flip side")}
        val position=options.position ?: AlongWallPosition(WallPositionReference.LEFT_END,mount?.fromLeft ?: item.defaultSize.width/2)
        RouteChoice("Position along wall",position.reference,WallPositionReference.entries,{onChange(options.copy(position=position.copy(reference=it)))}){wallPositionLabel(it)}
        RouteNumber("Distance to item centre",position.distance,{onChange(options.copy(position=position.copy(distance=it.coerceAtLeast(0.0))))},false)
        TextButton({onChange(options.copy(position=position.copy(distance=(position.distance-step).coerceAtLeast(0.0))))},Modifier.testTag("wall_position_decrease")){Text("−")}
        TextButton({onChange(options.copy(position=position.copy(distance=position.distance+step)))},Modifier.testTag("wall_position_increase")){Text("+")}
        if(item.openingKind() in setOf(OpeningKind.DOOR,OpeningKind.DOUBLE_DOOR)) {
            val swing=options.swing ?: DoorSwing()
            RouteChoice("Hinge",swing.hinge,DoorHinge.entries,{onChange(options.copy(swing=swing.copy(hinge=it)))}){it.name.lowercase().replaceFirstChar(Char::uppercase)}
            RouteChoice("Swing",swing.direction,DoorDirection.entries,{onChange(options.copy(swing=swing.copy(direction=it)))}){it.name.lowercase().replaceFirstChar(Char::uppercase)}
            TextButton({onChange(options.copy(swing=swing.flipped()))},Modifier.testTag("door_flip")){Text("Flip")}
        }
        if(item.openingKind()==null)RouteChoice("Wall mounting",options.mode,listOf(WallServiceMode.SURFACE,WallServiceMode.RECESS,WallServiceMode.OPENING),{onChange(options.copy(mode=it))},::wallModeLabel)
        if(item.openingKind()==null)RouteNumber("Clearance",options.clearance,{onChange(options.copy(clearance=it.coerceIn(0.0,1.0)))},false)
        if(options.mode==WallServiceMode.RECESS)RouteNumber("Recess depth",options.depth ?: item.defaultSize.depth,{onChange(options.copy(depth=it.coerceAtMost(10.0)))})
        if(options.mode!=WallServiceMode.OPENING)RouteChoice("Conduit drop",options.drop,WallDrop.entries,{onChange(options.copy(drop=it))}) {when(it){WallDrop.NONE->"Nearby concealed conduit";WallDrop.CEILING->"From ceiling";WallDrop.FLOOR->"From floor"}}
        if(options.mode==WallServiceMode.OPENING && item.openingKind()==null)RouteChoice("Opening",options.profile,OpeningProfile.entries,{onChange(options.copy(profile=it))}){it.name.lowercase().replaceFirstChar(Char::uppercase)}
    }}
}
@Composable
internal fun WallServiceSelection(element:Element,document:ProjectDocument,onApply:(ElementId,WallServiceOptions?)->Unit,onSchedule:()->Unit) {
    val mount=element.wallService()
    val component=element.body as? ElementBody.Component
    val item=component?.let {c->document.libraryItems.find {it.id==c.libraryItemId}}
    if(mount==null && element.body !is ElementBody.Wall && element.body !is ElementBody.Room)return
    var edit by remember(element.id) {mutableStateOf(false)}
    Row {
        if(mount!=null)TextButton({edit=true},Modifier.testTag("wall_service_properties")){Text("Wall service")}
        TextButton(onSchedule,Modifier.testTag("wall_schedule_open")){Text("Wall schedule")}
    }
    if(edit && mount!=null) {
        var value by remember(element) {mutableStateOf(mount.options)}
        AlertDialog(onDismissRequest={edit=false},title={Text("Wall mounting")},text={Column {
            if(item!=null)WallDeviceBar(item,value,{value=it},com.eezydraw.core.editing.readWallMount(document,element))else {
                RouteChoice("Mounting",value.mode,listOf(WallServiceMode.SURFACE,WallServiceMode.CHASE),{value=value.copy(mode=it)},::wallModeLabel)
                RouteNumber("Clearance",value.clearance,{value=value.copy(clearance=it.coerceIn(0.0,1.0))},false)
                RouteNumber("Chase depth",value.depth ?: .035,{value=value.copy(depth=it.coerceAtMost(10.0))})
            }
        }},confirmButton={TextButton({onApply(element.id,value);edit=false},Modifier.testTag("wall_service_apply")){Text("Apply")}},dismissButton={TextButton({edit=false}){Text("Cancel")}})
    }
}
@Composable
internal fun WallScheduleDialog(document:ProjectDocument,onSettings:(WallServiceSettings)->Unit,onSection:()->Unit,onDismiss:()->Unit) {
    val cuts=remember(document){wallServiceCuts(document)}
    var fraction by remember(document.project.id) {mutableStateOf((document.project.settings.wallServices.maxDepthFraction*100).toInt().toString())}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Wall services schedule")},text={Column(Modifier.fillMaxWidth().heightIn(max=480.dp).verticalScroll(rememberScrollState()).testTag("wall_schedule")) {
        Text("Sizes and positions are millimetres from the wall's left finish-face start and base.",style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(fraction,{fraction=it;it.toDoubleOrNull()?.takeIf {n->n.isFinite() && n in 1.0..100.0}?.let {n->onSettings(document.project.settings.wallServices.copy(maxDepthFraction=n/100))}},label={Text("Depth warning (% of thickness)")},singleLine=true,modifier=Modifier.testTag("wall_depth_threshold"))
        RouteNumber("Section height",document.project.settings.wallServices.sectionHeight,{onSettings(document.project.settings.wallServices.copy(sectionHeight=it))})
        TextButton(onSection,Modifier.testTag("wall_section_view")){Text("Wall section view")}
        if(cuts.isEmpty())Text("No wall cuts.")
        cuts.groupBy {it.wallId}.forEach {(id,rows)->
            HorizontalDivider(Modifier.padding(vertical=6.dp));Text(document.elements.find {it.id==id}?.name?.ifBlank {"Wall ${id.value}"} ?: id.value,style=MaterialTheme.typography.titleMedium)
            rows.groupBy {it.sourceId}.values.forEachIndexed {i,parts->
                val cut=parts.first()
                val outline=parts.flatMap {it.outline}
                val width=outline.maxOf {it.x}-outline.minOf {it.x};val height=outline.maxOf {it.y}-outline.minOf {it.y}
                val position=Point2((outline.maxOf {it.x}+outline.minOf {it.x})/2,(outline.maxOf {it.y}+outline.minOf {it.y})/2)
                val depth=parts.maxOf {it.depth}
                val warning=cut.exceeds(document.project.settings.wallServices.maxDepthFraction)
                Text("${cut.description.ifBlank {cut.sourceId.value}} · ${wallModeLabel(cut.mode)} · ${cut.side.name.lowercase()} face${cut.roomSide?.let {" · edge ${it.edgeIndex+1}"} ?: ""}\n%.0f × %.0f × %.0f mm · position %.0f / %.0f mm%s".format(Locale.ROOT,width*1000,height*1000,depth*1000,position.x*1000,position.y*1000,if(warning)" · DEPTH WARNING"else ""),Modifier.padding(vertical=4.dp).testTag("wall_schedule_${id.value}_$i"),color=if(warning)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        }
    }},confirmButton={TextButton(onDismiss){Text("Close")}})
}

internal fun wallPositionLabel(reference:WallPositionReference)=when(reference) {
    WallPositionReference.LEFT_END->"Left end";WallPositionReference.RIGHT_END->"Right end"
    WallPositionReference.NEAREST_CORNER->"Nearest corner";WallPositionReference.NEAREST_DOOR->"Nearest door"
}
