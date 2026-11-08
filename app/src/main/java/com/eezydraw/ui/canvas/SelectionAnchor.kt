package com.eezydraw.ui.canvas

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import com.eezydraw.core.geometry.*
import com.eezydraw.core.editing.projectPrecisionPoint
import com.eezydraw.core.model.*
import kotlin.math.roundToInt

/** Positions only the contextual toolbar; the model and gizmo remain in project coordinates. */
@Composable internal fun SelectionAnchor(bounds:Bounds3,viewport:Viewport2D,orbit:OrbitCamera,is3D:Boolean,width:Int,height:Int,bottomInset:Int,leftBar:Boolean,content:@Composable ()->Unit) {
    val density=LocalDensity.current.density
    var measured by remember {mutableStateOf(IntSize(320,64))}
    val points=listOf(bounds.min.x,bounds.max.x).flatMap {x->listOf(bounds.min.y,bounds.max.y).flatMap {y->listOf(bounds.min.z,bounds.max.z).mapNotNull {z->projectPrecisionPoint(Point3(x,y,z),viewport,orbit,is3D,width.toDouble(),height.toDouble())}}}
    if(points.isEmpty())return
    val left=if(leftBar)84*density else 8*density
    val x=((points.minOf {it.x}+points.maxOf {it.x})/2-measured.width/2).coerceIn(left.toDouble(),maxOf(left.toDouble(),width-measured.width-8*density.toDouble()))
    val intersectsTopControls=x<184*density || x+measured.width>width-(if(width/density<520)64 else 256)*density
    val top=(if(intersectsTopControls)136 else 8)*density
    val y=(points.minOf {it.y}-measured.height-28*density).coerceIn(top.toDouble(),maxOf(top.toDouble(),height-bottomInset-measured.height-8*density.toDouble()))
    Box(Modifier.offset {IntOffset(x.roundToInt(),y.roundToInt())}.widthIn(max=with(LocalDensity.current){(width-left-8*density).coerceAtLeast(48*density).toDp()}).onSizeChanged {measured=it}) {content()}
}
