package com.eezydraw.ui.canvas

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.eezydraw.R
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.eezydraw.core.model.*

internal data class CadAction(val id:String,val label:String,val icon:ImageVector,val selected:Boolean=false,val enabled:Boolean=true,val run:()->Unit)
internal fun CanvasGroup.label()=name.lowercase().replaceFirstChar {it.uppercase()}
internal fun CanvasGroup.icon():ImageVector=when(this) {
    CanvasGroup.DRAW->Icons.Outlined.Draw;CanvasGroup.EDIT->Icons.Outlined.Tune;CanvasGroup.LIBRARY->Icons.Outlined.Inventory2
    CanvasGroup.ROUTE->Icons.Outlined.Route;CanvasGroup.LEVEL->Icons.Outlined.Layers;CanvasGroup.MEASURE->Icons.Outlined.Straighten
    CanvasGroup.VIEW->Icons.Outlined.ViewInAr;CanvasGroup.NAVIGATE->Icons.Outlined.OpenWith;CanvasGroup.DISPLAY->Icons.Outlined.Visibility
    CanvasGroup.LAYERS->Icons.Outlined.Layers;CanvasGroup.ISSUES->Icons.Outlined.PushPin;CanvasGroup.SCHEDULE->Icons.Outlined.TableChart
    CanvasGroup.EXPORT->Icons.Outlined.Share;CanvasGroup.SETTINGS->Icons.Outlined.Settings
}
private val barColour=Color(0xFF17202B)
private val selectedColour=Color(0xFF65D7FF)

/** The canvas is never inset. All chrome is an overlay and changes only on explicit callbacks. */
@Composable internal fun MobileCanvasChrome(
    layout:CanvasLayout,onLayout:(CanvasLayout)->Unit,actions:List<CadAction>,
    onClose:()->Unit,onUndo:()->Unit,onRedo:()->Unit,onFit:()->Unit,onToggle3D:()->Unit,
    canUndo:Boolean,canRedo:Boolean,is3D:Boolean,modifier:Modifier=Modifier,
    canFit:Boolean=true,onBottomHeight:(Int)->Unit={},
    cube:@Composable ()->Unit,options:(@Composable ()->Unit)?=null,detail:(@Composable ()->Unit)?=null,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        if(layout.hideAll) {
            RoundControl("Show controls",Icons.Outlined.FullscreenExit,{onLayout(layout.copy(hideAll=false))},Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp).testTag("canvas_show_panels"))
        } else {
            Row(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),verticalAlignment=Alignment.Top) {
                RoundControl(stringResource(R.string.canvas_back),Icons.Outlined.Close,onClose,Modifier.testTag("canvas_close"))
                Spacer(Modifier.width(8.dp));cube()
            }
            val mini=listOf(CadAction("view_undo","Undo",Icons.AutoMirrored.Outlined.Undo,enabled=canUndo,run=onUndo),
                CadAction("view_redo","Redo",Icons.AutoMirrored.Outlined.Redo,enabled=canRedo,run=onRedo),
                CadAction("canvas_extents",stringResource(R.string.canvas_extents),Icons.Outlined.FitScreen,enabled=canFit,run=onFit),
                CadAction("canvas_2d_3d",if(is3D)"2D"else "3D",Icons.Outlined.ViewInAr,selected=is3D,run=onToggle3D),
                CadAction("canvas_hide_panels","Full screen",Icons.Outlined.Fullscreen,run={onLayout(layout.copy(hideAll=true))}))
            Surface(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),shape=RoundedCornerShape(24.dp),color=barColour,shadowElevation=5.dp) {
                if(maxWidth<520.dp)Column {mini.forEach {MiniControl(it)}}else Row {mini.forEach {MiniControl(it)}}
            }
            if(layout.bottomBarVisible)BoxWithConstraints(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged {onBottomHeight(it.height)}.navigationBarsPadding().imePadding().testTag("cad_bottom_controls")) {
                val optionsLimit=if(options==null)0.dp else minOf(170.dp,maxHeight*.20f)
                val reserve=if(layout.openGroup==null)72.dp else 136.dp
                val detailLimit=minOf(maxHeight*.38f,(maxHeight-optionsLimit-reserve-32.dp).coerceAtLeast(48.dp))
                Column {
                detail?.let {Surface(Modifier.fillMaxWidth().heightIn(max=detailLimit),tonalElevation=5.dp,shadowElevation=5.dp){Box(Modifier.pointerInput(Unit){detectTapGestures(onTap={})}){it()}}}
                options?.let {Surface(Modifier.fillMaxWidth().heightIn(max=optionsLimit).testTag("cad_options"),tonalElevation=4.dp){Column(Modifier.verticalScroll(rememberScrollState()).pointerInput(Unit){detectTapGestures(onTap={})}){it()}}}
                if(layout.openGroup!=null)Surface(color=barColour,contentColor=Color.White,modifier=Modifier.fillMaxWidth().testTag("cad_substrip_${layout.openGroup?.name?.lowercase()}")) {
                    FadingStrip("cad_substrip_scroll") {actions.forEach {action->CadTool(action)}}
                }
                Surface(color=barColour,contentColor=Color.White,modifier=Modifier.fillMaxWidth().testTag("cad_bottom_bar")) {
                    FadingStrip("cad_group_scroll") {CanvasGroup.entries.forEach {group->
                        val active=layout.openGroup==group
                        Column(Modifier.width(76.dp).heightIn(min=72.dp).selectable(active,role=Role.Tab,onClick={onLayout(layout.toggleGroup(group))}).testTag("cad_group_${group.name.lowercase()}"),horizontalAlignment=Alignment.CenterHorizontally) {
                            Canvas(Modifier.fillMaxWidth().height(6.dp)){if(active){val path=Path().apply {moveTo(size.width/2-5.dp.toPx(),size.height);lineTo(size.width/2,0f);lineTo(size.width/2+5.dp.toPx(),size.height);close()};drawPath(path,selectedColour)}}
                            Icon(group.icon(),null,Modifier.size(26.dp),tint=if(active)selectedColour else Color.White)
                            Text(group.label(),style=MaterialTheme.typography.labelSmall,color=if(active)selectedColour else Color.White,modifier=Modifier.padding(vertical=5.dp))
                            Box(Modifier.fillMaxWidth().height(3.dp).background(if(active)selectedColour else Color.Transparent))
                        }
                    }}
                }
                }
            } else RoundControl("Show bottom bar",Icons.Outlined.ExpandLess,{onLayout(layout.copy(bottomBarVisible=true))},Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(8.dp).testTag("cad_show_bottom"))
        }
    }
}

@Composable private fun FadingStrip(tag:String,content:@Composable RowScope.()->Unit) {
    val scroll=rememberScrollState()
    Box(Modifier.pointerInput(Unit){detectTapGestures(onTap={})}) {
        Row(Modifier.fillMaxWidth().heightIn(min=64.dp).horizontalScroll(scroll).testTag(tag),verticalAlignment=Alignment.CenterVertically,content=content)
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            val edge=16.dp.toPx()
            if(scroll.canScrollBackward)drawRect(Brush.horizontalGradient(listOf(barColour,Color.Transparent),0f,edge),size=androidx.compose.ui.geometry.Size(edge,size.height))
            if(scroll.canScrollForward)drawRect(Brush.horizontalGradient(listOf(Color.Transparent,barColour),size.width-edge,size.width),topLeft=Offset(size.width-edge,0f),size=androidx.compose.ui.geometry.Size(edge,size.height))
        }
    }
}
@Composable internal fun CadTool(action:CadAction) {
    Column(Modifier.widthIn(min=72.dp).heightIn(min=64.dp).selectable(action.selected,enabled=action.enabled,role=Role.Button,onClick=action.run).padding(horizontal=8.dp,vertical=6.dp).testTag(action.id),horizontalAlignment=Alignment.CenterHorizontally) {
        val tint=if(!action.enabled)Color.Gray else if(action.selected)selectedColour else Color.White
        Icon(action.icon,null,Modifier.size(24.dp),tint=tint);Text(action.label,style=MaterialTheme.typography.labelSmall,color=tint,modifier=Modifier.padding(top=4.dp))
    }
}
@Composable private fun MiniControl(action:CadAction){IconButton(action.run,enabled=action.enabled,modifier=Modifier.size(48.dp).testTag(action.id)){Icon(action.icon,action.label,tint=if(!action.enabled)Color.Gray else if(action.selected)selectedColour else Color.White)}}
@Composable internal fun RoundControl(label:String,icon:ImageVector,onClick:()->Unit,modifier:Modifier=Modifier) {
    Surface(modifier,shape=CircleShape,color=barColour,contentColor=Color.White,shadowElevation=4.dp){IconButton(onClick,Modifier.size(48.dp)){Icon(icon,label)}}
}
