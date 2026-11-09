package com.eezydraw.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.eezydraw.R
import com.eezydraw.core.editing.PrecisionPick
import com.eezydraw.core.editing.PointerRect
import com.eezydraw.core.model.*
import kotlin.math.*

internal val PrecisionScreenX = SemanticsPropertyKey<Double>("PrecisionScreenX")
internal val PrecisionScreenY = SemanticsPropertyKey<Double>("PrecisionScreenY")
internal val PrecisionWorldX = SemanticsPropertyKey<Double>("PrecisionWorldX")
internal val PrecisionWorldY = SemanticsPropertyKey<Double>("PrecisionWorldY")
internal val PrecisionWorldZ = SemanticsPropertyKey<Double>("PrecisionWorldZ")
internal val PrecisionLoupeZoom = SemanticsPropertyKey<Double>("PrecisionLoupeZoom")

/**
 * A render-only cursor and explicit point controls. The parent owns input, snapping, haptic
 * transitions and history. This overlay never dispatches a point because a finger was lifted.
 */
@Composable
internal fun PrecisionPointerOverlay(
    pick: PrecisionPick?, finger: Point2?, settings: PrecisionPointerSettings,
    reference: Point3?, unit: LengthUnit, precision: Int,
    onPlace: () -> Unit, onCancel: () -> Unit, onNudge: (Int, Int) -> Unit, enabled: Boolean,
    modifier: Modifier = Modifier, loupeBounds: PointerRect? = null, capture: CapturedLoupe? = null,
    canPlace: Boolean = true,
    controlsBottomInset: androidx.compose.ui.unit.Dp = 0.dp, controlsVisible: Boolean = true,
    controlsLeftInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val density = LocalDensity.current.density
    val accent = MaterialTheme.colorScheme.primary
    val foreground = MaterialTheme.colorScheme.onSurface
    val background = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outline
    val crosshairLabel = stringResource(R.string.precision_crosshair)
    val loupeLabel = stringResource(R.string.precision_loupe)
    val snapMode=pick?.mode
    val snapText = when {
        pick?.wallEdge == true -> stringResource(R.string.precision_wall_edge)
        snapMode != null -> stringResource(snapLabel(snapMode))
        pick?.elementId != null -> stringResource(R.string.precision_object_reference)
        else -> null
    }
    val distance = if (pick != null && reference != null) sqrt(
        (pick.point.x-reference.x).pow(2) + (pick.point.y-reference.y).pow(2) + (pick.point.z-reference.z).pow(2)) else null
    val distanceText = distance?.let { stringResource(R.string.precision_distance,coordinate(it,unit,precision),unitSuffix(unit)) }
    BoxWithConstraints(modifier.fillMaxSize().testTag("precision_pointer")) {
        val width = constraints.maxWidth.toDouble()
        val height = constraints.maxHeight.toDouble()
        if (pick != null && width > 0 && height > 0) {
            val at = pick.screen
            Canvas(Modifier.fillMaxSize()) {
                finger?.let { source ->
                    val from=Offset(source.x.toFloat(),source.y.toFloat())
                    val to=Offset(at.x.toFloat(),at.y.toFloat())
                    drawLine(background.copy(alpha=.9f),from,to,3*density)
                    drawLine(foreground.copy(alpha=.7f),from,to,density)
                    drawCircle(foreground.copy(alpha=.45f),8*density,from,style=Stroke(density))
                }
            }
            Canvas(Modifier.offset {IntOffset((at.x-16*density).roundToInt(),(at.y-16*density).roundToInt())}
                .size(32.dp).testTag("precision_crosshair").semantics {
                    contentDescription=crosshairLabel
                    set(PrecisionScreenX,at.x);set(PrecisionScreenY,at.y)
                    set(PrecisionWorldX,pick.point.x);set(PrecisionWorldY,pick.point.y);set(PrecisionWorldZ,pick.point.z)
                }) {
                drawPointerMark(center,if(snapText!=null)accent else foreground,background,snapText!=null)
            }
            val textX=(at.x+20*density).coerceIn(4.0,max(4.0,width-184*density))
            val textY=(if(finger!=null && finger.y<at.y)at.y+20*density else at.y-56*density).coerceIn(4.0,max(4.0,height-72*density))
            if(snapText!=null || distanceText!=null) Surface(
                Modifier.offset {IntOffset(textX.roundToInt(),textY.roundToInt())}.widthIn(max=180.dp).loupeObstacle("precision_readout"),
                color=background.copy(alpha=.94f),shape=RoundedCornerShape(8.dp),tonalElevation=3.dp,
            ) {
                Column(Modifier.padding(horizontal=8.dp,vertical=4.dp)) {
                    snapText?.let {Text(it,Modifier.testTag("precision_snap"),color=accent,style=MaterialTheme.typography.labelMedium)}
                    distanceText?.let {Text(it,Modifier.testTag("precision_distance"),style=MaterialTheme.typography.labelMedium)}
                }
            }
            loupeBounds?.let { bounds ->
                val diameter = bounds.width
                Canvas(Modifier.offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                    .size((diameter/density).dp).testTag("precision_loupe").semantics {
                        contentDescription=loupeLabel;set(PrecisionLoupeZoom,settings.magnifierZoom)
                        set(PrecisionScreenX,at.x);set(PrecisionScreenY,at.y)
                    }) {
                    val clip=Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset.Zero,size)) }
                    clipPath(clip) {
                        // Geometry, depth, lighting, grid, level and display overrides are already
                        // resolved in this exact native framebuffer crop. Only scale pixels here.
                        capture?.let { image ->
                            val zoom=settings.magnifierZoom
                            // Fill source portions outside the physical framebuffer with its clear colour.
                            drawRect(if(image.renderFrame?.dark==true)Color(.055f,.071f,.10f) else Color(.97f,.98f,.99f))
                            withTransform({
                                translate((size.width/2+(image.region.left-at.x)*zoom).toFloat(),
                                    (size.height/2+(image.region.top-at.y)*zoom).toFloat())
                                scale(zoom.toFloat(),zoom.toFloat(),Offset.Zero)
                            }) { drawImage(image.bitmap.asImageBitmap(),srcSize=androidx.compose.ui.unit.IntSize(image.bitmap.width,image.bitmap.height),dstSize=androidx.compose.ui.unit.IntSize(image.bitmap.width,image.bitmap.height),filterQuality=FilterQuality.Low) }
                        }
                        drawPointerMark(center,if(snapText!=null)accent else foreground,background,snapText!=null)
                    }
                    drawCircle(outline,radius=size.minDimension/2-1.dp.toPx(),style=Stroke(2*density))
                }
            }
        }

        if(controlsVisible)Surface(Modifier.align(if(settings.side==PrecisionOffsetSide.ABOVE_LEFT)Alignment.BottomEnd else Alignment.BottomStart)
            .padding(start=controlsLeftInset,bottom=controlsBottomInset).padding(12.dp).testTag("precision_controls").loupeObstacle("precision_controls").pointerInput(Unit) {detectTapGestures(onTap={})},
            shape=RoundedCornerShape(18.dp),tonalElevation=5.dp,shadowElevation=3.dp) {
            Column(Modifier.padding(4.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Row {
                    IconButton({onNudge(-1,0)},Modifier.size(48.dp).testTag("precision_nudge_left"),enabled=enabled && pick!=null){Icon(Icons.AutoMirrored.Outlined.ArrowBack,stringResource(R.string.precision_nudge_left))}
                    IconButton({onNudge(0,-1)},Modifier.size(48.dp).testTag("precision_nudge_up"),enabled=enabled && pick!=null){Icon(Icons.Outlined.ArrowUpward,stringResource(R.string.precision_nudge_up))}
                    IconButton({onNudge(0,1)},Modifier.size(48.dp).testTag("precision_nudge_down"),enabled=enabled && pick!=null){Icon(Icons.Outlined.ArrowDownward,stringResource(R.string.precision_nudge_down))}
                    IconButton({onNudge(1,0)},Modifier.size(48.dp).testTag("precision_nudge_right"),enabled=enabled && pick!=null){Icon(Icons.AutoMirrored.Outlined.ArrowForward,stringResource(R.string.precision_nudge_right))}
                }
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onCancel,Modifier.defaultMinSize(minWidth=64.dp,minHeight=56.dp).testTag("precision_cancel")) {
                        Text(stringResource(R.string.precision_cancel))
                    }
                    Button(onPlace,Modifier.defaultMinSize(minWidth=64.dp,minHeight=56.dp).testTag("precision_place"),enabled=enabled && pick!=null && canPlace) {
                        Text(stringResource(R.string.precision_place))
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawPointerMark(at:Offset,colour:Color,background:Color,snapped:Boolean) {
    val arm=12*density;val gap=4*density
    for(stroke in listOf(4f*density,1.5f*density)) {
        val c=if(stroke>2*density)background else colour
        drawLine(c,at-Offset(arm,0f),at-Offset(gap,0f),stroke)
        drawLine(c,at+Offset(gap,0f),at+Offset(arm,0f),stroke)
        drawLine(c,at-Offset(0f,arm),at-Offset(0f,gap),stroke)
        drawLine(c,at+Offset(0f,gap),at+Offset(0f,arm),stroke)
    }
    if(snapped)drawRect(colour,at-Offset(4*density,4*density),Size(8*density,8*density),style=Stroke(1.5f*density))
    else drawCircle(colour,1.5f*density,at)
}

internal fun unitSuffix(unit:LengthUnit)=when(unit) {
    LengthUnit.MILLIMETRES->"mm";LengthUnit.CENTIMETRES->"cm";LengthUnit.METRES->"m";LengthUnit.INCHES->"in";LengthUnit.FEET->"ft"
}
