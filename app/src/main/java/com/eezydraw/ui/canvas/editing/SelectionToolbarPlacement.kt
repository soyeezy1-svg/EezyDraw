package com.eezydraw.ui.canvas.editing

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import com.eezydraw.core.model.Point2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Context actions move around user-owned panels; panel positions themselves never change. */
internal fun selectionToolbarPosition(pivot:Point2,width:Int,height:Int,density:Float,occupied:List<Rect>):IntOffset {
    val w=240*density;val h=48*density;val margin=8*density
    fun bounded(x:Double,y:Double)=IntOffset(x.toFloat().coerceIn(0f,max(0f,width-w)).roundToInt(),
        y.toFloat().coerceIn(0f,max(0f,height-h)).roundToInt())
    val candidates=listOf(
        bounded(pivot.x-w/2,pivot.y+120*density),bounded(pivot.x-w/2,pivot.y-200*density),
        bounded(pivot.x-w-48*density,pivot.y-h/2),bounded(pivot.x+48*density,pivot.y-h/2),
        bounded(width/2.0-w/2,margin.toDouble()),bounded(width-w-margin.toDouble(),margin.toDouble()),
        bounded(margin.toDouble(),margin.toDouble()))
    fun overlap(at:IntOffset):Double {
        val r=Rect(at.x.toFloat()-4*density,at.y.toFloat()-4*density,at.x+w+4*density,at.y+h+4*density)
        return occupied.sumOf {other->(max(0f,min(r.right,other.right)-max(r.left,other.left))*
            max(0f,min(r.bottom,other.bottom)-max(r.top,other.top))).toDouble()}
    }
    return candidates.minBy(::overlap)
}
