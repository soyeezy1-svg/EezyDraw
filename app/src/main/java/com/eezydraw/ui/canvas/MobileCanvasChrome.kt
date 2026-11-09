package com.eezydraw.ui.canvas

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import com.eezydraw.ui.components.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
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
private val barColour=CadStyle.chrome
private val selectedColour=CadStyle.accent

/** The canvas is never inset. All chrome is an overlay and changes only on explicit callbacks. */
@Composable internal fun MobileCanvasChrome(
    layout:CanvasLayout,onLayout:(CanvasLayout)->Unit,actions:List<CadAction>,
    onClose:()->Unit,onUndo:()->Unit,onRedo:()->Unit,onFit:()->Unit,onToggle3D:()->Unit,
    canUndo:Boolean,canRedo:Boolean,is3D:Boolean,modifier:Modifier=Modifier,
    canFit:Boolean=true,onBottomHeight:(Int)->Unit={},
    safeInsets:WindowInsets=WindowInsets.safeDrawing,
    showLabels:Boolean=true,barPosition:CanvasBarPosition=CanvasBarPosition.BOTTOM,
    onGroup:((CanvasGroup)->Unit)?=null,detailKey:String="panel",detailTitle:String="Tools",onCloseDetail:()->Unit={},
    cube:@Composable ()->Unit,options:(@Composable ()->Unit)?=null,detail:(@Composable ()->Unit)?=null,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val left=barPosition==CanvasBarPosition.LEFT
        val leftInset=if(left)76.dp else 0.dp
        if(layout.hideAll) {
            RoundControl("Show controls",Icons.Outlined.FullscreenExit,{onLayout(layout.copy(hideAll=false))},Modifier.align(Alignment.TopEnd).windowInsetsPadding(safeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(8.dp).testTag("canvas_show_panels").loupeObstacle("show_controls"))
        } else {
            Row(Modifier.align(Alignment.TopStart).windowInsetsPadding(safeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(8.dp).loupeObstacle("close_cube"),verticalAlignment=Alignment.Top) {
                RoundControl(stringResource(R.string.canvas_back),Icons.Outlined.Close,onClose,Modifier.testTag("canvas_close"))
                Spacer(Modifier.width(8.dp));cube()
            }
            val mini=listOf(CadAction("view_undo","Undo",Icons.AutoMirrored.Outlined.Undo,enabled=canUndo,run=onUndo),
                CadAction("view_redo","Redo",Icons.AutoMirrored.Outlined.Redo,enabled=canRedo,run=onRedo),
                CadAction("canvas_extents",stringResource(R.string.canvas_extents),Icons.Outlined.FitScreen,enabled=canFit,run=onFit),
                CadAction("canvas_2d_3d",if(is3D)"2D"else "3D",Icons.Outlined.ViewInAr,selected=is3D,run=onToggle3D),
                CadAction("canvas_hide_panels","Full screen",Icons.Outlined.Fullscreen,run={onLayout(layout.copy(hideAll=true))}))
            Surface(Modifier.align(Alignment.TopEnd).windowInsetsPadding(safeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(8.dp).testTag("cad_top_toolbar").loupeObstacle("top_toolbar"),shape=RoundedCornerShape(24.dp),color=barColour,shadowElevation=5.dp) {
                if(maxWidth<520.dp)Column {mini.forEach {MiniControl(it)}}else Row {mini.forEach {MiniControl(it)}}
            }
            if(layout.bottomBarVisible) {
                if(left)Surface(Modifier.align(Alignment.TopStart).padding(top=144.dp).statusBarsPadding().background(barColour).windowInsetsPadding(safeInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)).width(76.dp).fillMaxHeight().testTag("cad_left_bar").loupeObstacle("left_bar"),color=barColour) {
                    val scroll=rememberScrollState()
                    Box {
                        Column(Modifier.verticalScroll(scroll).testTag("cad_group_scroll")){CanvasGroup.entries.forEach {group->GroupButton(group,layout.openGroup==group,showLabels){onGroup?.invoke(group) ?: onLayout(layout.toggleGroup(group))}}}
                        Canvas(Modifier.matchParentSize()) {
                            if(scroll.canScrollBackward)drawRect(Brush.verticalGradient(listOf(barColour,Color.Transparent),0f,16.dp.toPx()),size=androidx.compose.ui.geometry.Size(size.width,16.dp.toPx()))
                            if(scroll.canScrollForward)drawRect(Brush.verticalGradient(listOf(Color.Transparent,barColour),size.height-16.dp.toPx(),size.height),topLeft=Offset(0f,size.height-16.dp.toPx()),size=androidx.compose.ui.geometry.Size(size.width,16.dp.toPx()))
                        }
                    }
                }
                BoxWithConstraints(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start=leftInset).background(barColour).onSizeChanged {onBottomHeight(it.height)}.testTag("cad_bottom_controls").loupeObstacle("bottom_controls").windowInsetsPadding(safeInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
                    val optionsLimit=if(options==null)0.dp else minOf(170.dp,maxHeight*.20f)
                    val reserve=(if(left)0.dp else if(showLabels)72.dp else 64.dp)+(if(layout.openGroup==null)0.dp else if(showLabels)72.dp else 64.dp)
                    val detailLimit=(maxHeight-optionsLimit-reserve-16.dp).coerceAtLeast(96.dp)
                    Column {
                        detail?.let {ResizableCanvasPanel(detailKey,detailTitle,onCloseDetail,detailLimit,it)}
                        options?.let {Surface(Modifier.fillMaxWidth().heightIn(max=optionsLimit).testTag("cad_options"),tonalElevation=4.dp){Column(Modifier.verticalScroll(rememberScrollState()).pointerInput(Unit){detectTapGestures(onTap={})}){it()}}}
                        if(layout.openGroup!=null)Surface(color=barColour,contentColor=Color.White,modifier=Modifier.fillMaxWidth().testTag("cad_substrip_${layout.openGroup?.name?.lowercase()}")) {
                            FadingStrip("cad_substrip_scroll") {actions.forEach {action->CadTool(action,showLabel=showLabels)}}
                        }
                        if(!left)Surface(color=barColour,contentColor=Color.White,modifier=Modifier.fillMaxWidth().testTag("cad_bottom_bar")) {
                            FadingStrip("cad_group_scroll") {CanvasGroup.entries.forEach {group->GroupButton(group,layout.openGroup==group,showLabels){onGroup?.invoke(group) ?: onLayout(layout.toggleGroup(group))}}}
                        }
                    }
                }
            } else RoundControl("Show bottom bar",Icons.Outlined.ExpandLess,{onLayout(layout.copy(bottomBarVisible=true))},Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(8.dp).testTag("cad_show_bottom").loupeObstacle("show_bottom"))
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
@Composable private fun GroupButton(group:CanvasGroup,active:Boolean,labels:Boolean,onClick:()->Unit) {
    CadTooltip(group.label()) {
        Column(Modifier.width(76.dp).heightIn(min=if(labels)72.dp else 56.dp).selectable(active,role=Role.Tab,onClick=onClick).semantics {contentDescription=group.label()}.testTag("cad_group_${group.name.lowercase()}"),horizontalAlignment=Alignment.CenterHorizontally) {
            Canvas(Modifier.fillMaxWidth().height(6.dp)){if(active){val path=Path().apply {moveTo(size.width/2-5.dp.toPx(),size.height);lineTo(size.width/2,0f);lineTo(size.width/2+5.dp.toPx(),size.height);close()};drawPath(path,selectedColour)}}
            Icon(group.icon(),null,Modifier.size(24.dp),tint=if(active)selectedColour else Color.White)
            if(labels)Text(group.label(),style=MaterialTheme.typography.labelSmall,color=if(active)selectedColour else Color.White,modifier=Modifier.padding(vertical=5.dp))else Spacer(Modifier.height(17.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).background(if(active)selectedColour else Color.Transparent))
        }
    }
}
@Composable internal fun CadTool(action:CadAction,modifier:Modifier=Modifier,showLabel:Boolean=true) {
    CadTooltip(action.label) {
        Column(modifier.widthIn(min=if(showLabel)72.dp else 56.dp).height(if(showLabel)72.dp else 56.dp).selectable(action.selected,enabled=action.enabled,role=Role.Button,onClick=action.run).semantics {contentDescription=action.label}.padding(horizontal=8.dp,vertical=6.dp).then(if(action.id.isEmpty())Modifier else Modifier.testTag(action.id)),horizontalAlignment=Alignment.CenterHorizontally) {
            val tint=if(!action.enabled)Color.Gray else if(action.selected)selectedColour else Color.White
            Icon(action.icon,null,Modifier.size(24.dp),tint=tint)
            if(showLabel)Text(action.label,style=MaterialTheme.typography.labelSmall,color=tint,maxLines=2,overflow=TextOverflow.Ellipsis,textAlign=TextAlign.Center,modifier=Modifier.padding(top=4.dp))
        }
    }
}
@Composable private fun MiniControl(action:CadAction){CadTooltip(action.label){IconButton(action.run,enabled=action.enabled,modifier=Modifier.size(48.dp).testTag(action.id)){Icon(action.icon,action.label,Modifier.size(24.dp),tint=if(!action.enabled)Color.Gray else if(action.selected)selectedColour else Color.White)}}}
@Composable internal fun RoundControl(label:String,icon:ImageVector,onClick:()->Unit,modifier:Modifier=Modifier) {
    CadTooltip(label,modifier){Surface(shape=CircleShape,color=barColour,contentColor=Color.White,shadowElevation=4.dp){IconButton(onClick,Modifier.size(48.dp)){Icon(icon,label,Modifier.size(24.dp))}}}
}

/** A non-modal bottom sheet: only explicit drag, resize buttons or Close changes its size/state. */
@Composable internal fun ResizableCanvasPanel(key:String,title:String,onClose:()->Unit,maxHeight:androidx.compose.ui.unit.Dp,content:@Composable ()->Unit) {
    val density=LocalDensity.current
    var heightDp by rememberSaveable(key){mutableFloatStateOf(320f)}
    val limit=maxHeight.value.coerceAtLeast(96f)
    val visibleHeight=heightDp.coerceIn(96f,limit)
    Surface(Modifier.fillMaxWidth().height(visibleHeight.dp).testTag("cad_detail_sheet"),shape=CadStyle.shape,tonalElevation=5.dp,shadowElevation=8.dp) {
        Column {
            Row(Modifier.fillMaxWidth().height(48.dp),verticalAlignment=Alignment.CenterVertically) {
                Row(Modifier.weight(1f).fillMaxHeight().testTag("cad_detail_drag")
                .pointerInput(key,limit){awaitEachGesture {
                    val down=awaitFirstDown(requireUnconsumed=true);down.consume()
                    while(true){val event=awaitPointerEvent();val change=event.changes.firstOrNull {it.id==down.id} ?: break
                        val delta=change.positionChange().y
                        heightDp=(heightDp.coerceIn(64f,limit)-delta/density.density).coerceIn(64f,limit)
                        change.consume();if(!change.pressed){if(heightDp<96f){heightDp=320f;onClose()};break}
                    }
                }},verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Outlined.DragHandle,"Drag to resize or close",Modifier.padding(horizontal=12.dp).size(24.dp))
                Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,maxLines=1)
                }
                CadTooltip("Resize panel"){IconButton({heightDp=if(visibleHeight<limit*.8f)limit else minOf(320f,limit)},Modifier.size(48.dp).testTag("cad_detail_resize")){Icon(Icons.Outlined.UnfoldMore,"Resize panel",Modifier.size(24.dp))}}
                CadTooltip("Close panel"){IconButton(onClose,Modifier.size(48.dp).testTag("cad_detail_close")){Icon(Icons.Outlined.Close,"Close panel",Modifier.size(24.dp))}}
            }
            Box(Modifier.fillMaxWidth().weight(1f).pointerInput(Unit){detectTapGestures(onTap={})}) {content()}
        }
    }
}
