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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
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
import com.eezydraw.core.editing.PointPickContext
import com.eezydraw.core.editing.PrecisionPick
import com.eezydraw.core.editing.precisionLoupeBounds
import com.eezydraw.core.geometry.*
import com.eezydraw.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    pick: PrecisionPick?, finger: Point2?, dragging: Boolean, settings: PrecisionPointerSettings,
    viewport: Viewport2D, orbit: OrbitCamera, is3D: Boolean,
    scene: DrawingScene?, surfaces: SurfaceScene?, gridSpacing: Double, showGrid: Boolean,
    floorZ: Double, reference: Point3?, unit: LengthUnit, precision: Int,
    onPlace: () -> Unit, onCancel: () -> Unit, onNudge: (Int, Int) -> Unit, enabled: Boolean,
    modifier: Modifier = Modifier, pickContext: PointPickContext? = null,
    viewMode: ViewMode = ViewMode.WIREFRAME, levelId: String? = null,
) {
    val density = LocalDensity.current.density
    val accent = MaterialTheme.colorScheme.primary
    val foreground = MaterialTheme.colorScheme.onSurface
    val background = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outline
    val gridColour = MaterialTheme.colorScheme.outlineVariant
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
            val prepared by produceState<LoupeSurfaces?>(null,surfaces,viewport,orbit,is3D,width,height,levelId,viewMode,settings.magnifier) {
                value=if(!settings.magnifier || surfaces==null || viewMode==ViewMode.WIREFRAME)null else withContext(Dispatchers.Default) {
                    LoupeSurfaces(surfaces,viewport,orbit,is3D,width,height,levelId)
                }
            }
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
                Modifier.offset {IntOffset(textX.roundToInt(),textY.roundToInt())}.widthIn(max=180.dp),
                color=background.copy(alpha=.94f),shape=RoundedCornerShape(8.dp),tonalElevation=3.dp,
            ) {
                Column(Modifier.padding(horizontal=8.dp,vertical=4.dp)) {
                    snapText?.let {Text(it,Modifier.testTag("precision_snap"),color=accent,style=MaterialTheme.typography.labelMedium)}
                    distanceText?.let {Text(it,Modifier.testTag("precision_distance"),style=MaterialTheme.typography.labelMedium)}
                }
            }
            if(dragging && settings.magnifier) {
                // Keep the loupe above the thumb controls even when the safest corner is low.
                val loupeHeight=(height-184*density).coerceAtLeast(min(height,132.0*density))
                val bounds=precisionLoupeBounds(finger,at,width,loupeHeight,density.toDouble())
                val diameter=min(bounds.width,bounds.height)
                val nearby=remember(scene,pickContext,at,settings.magnifierZoom,width,height,viewport,orbit,is3D,levelId) {
                    pickContext?.nearbyScreenSegments(at,diameter/settings.magnifierZoom/2+3*density,
                        viewport,orbit,is3D,width,height,levelId).orEmpty()
                }
                Canvas(Modifier.offset {IntOffset(bounds.left.roundToInt(),bounds.top.roundToInt())}
                    .size((diameter/density).dp).testTag("precision_loupe").semantics {
                        contentDescription=loupeLabel;set(PrecisionLoupeZoom,settings.magnifierZoom)
                    }) {
                    val clip=Path().apply {addOval(androidx.compose.ui.geometry.Rect(Offset.Zero,size))}
                    clipPath(clip) {
                        drawRect(background)
                        if(showGrid)drawLoupeGrid(at,viewport,orbit,is3D,width,height,floorZ,gridSpacing,settings.magnifierZoom,gridColour)
                        val faces=prepared?.near(at,diameter/settings.magnifierZoom/2+3*density).orEmpty()
                        drawLoupeFaces(faces,at,viewport,orbit,is3D,width,height,settings.magnifierZoom,viewMode,background)
                        if(viewMode!=ViewMode.SHADED && viewMode!=ViewMode.REALISTIC || faces.isEmpty()) nearby.forEach {edge->
                            val a=precisionLoupePoint(edge.a,at,viewport,orbit,is3D,width,height,size.width.toDouble(),settings.magnifierZoom)
                            val b=precisionLoupePoint(edge.b,at,viewport,orbit,is3D,width,height,size.width.toDouble(),settings.magnifierZoom)
                            if(a!=null && b!=null)drawLine(precisionLoupeColour(Color(edge.colorArgb),background.luminance()<.5f),Offset(a.x.toFloat(),a.y.toFloat()),Offset(b.x.toFloat(),b.y.toFloat()),1.25f*settings.magnifierZoom.toFloat())
                        }
                        drawPointerMark(center,if(snapText!=null)accent else foreground,background,snapText!=null)
                    }
                    drawCircle(outline,radius=size.minDimension/2-1.dp.toPx(),style=Stroke(2*density))
                }
            }
        }
        Surface(Modifier.align(if(settings.side==PrecisionOffsetSide.ABOVE_LEFT)Alignment.BottomEnd else Alignment.BottomStart)
            .padding(12.dp).testTag("precision_controls").pointerInput(Unit) {detectTapGestures(onTap={})},
            shape=RoundedCornerShape(18.dp),tonalElevation=5.dp,shadowElevation=3.dp) {
            Column(Modifier.padding(4.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                IconButton({onNudge(0,-1)},Modifier.size(48.dp).testTag("precision_nudge_up"),enabled=enabled && pick!=null) {
                    Icon(Icons.Outlined.ArrowUpward,stringResource(R.string.precision_nudge_up))
                }
                Row {
                    IconButton({onNudge(-1,0)},Modifier.size(48.dp).testTag("precision_nudge_left"),enabled=enabled && pick!=null) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack,stringResource(R.string.precision_nudge_left))
                    }
                    IconButton({onNudge(0,1)},Modifier.size(48.dp).testTag("precision_nudge_down"),enabled=enabled && pick!=null) {
                        Icon(Icons.Outlined.ArrowDownward,stringResource(R.string.precision_nudge_down))
                    }
                    IconButton({onNudge(1,0)},Modifier.size(48.dp).testTag("precision_nudge_right"),enabled=enabled && pick!=null) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward,stringResource(R.string.precision_nudge_right))
                    }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onCancel,Modifier.defaultMinSize(minWidth=64.dp,minHeight=56.dp).testTag("precision_cancel")) {
                        Text(stringResource(R.string.precision_cancel))
                    }
                    Button(onPlace,Modifier.defaultMinSize(minWidth=64.dp,minHeight=56.dp).testTag("precision_place"),enabled=enabled && pick!=null) {
                        Text(stringResource(R.string.precision_place))
                    }
                }
            }
        }
    }
}

/** Source pixels are scaled about the *snapped* crosshair, not the finger or camera target. */
internal fun precisionLoupePoint(point:Point3,crosshair:Point2,viewport:Viewport2D,orbit:OrbitCamera,is3D:Boolean,
    width:Double,height:Double,diameter:Double,zoom:Double):Point2? {
    val p=if(is3D)orbit.project(point,width,height)?.let {Point2(it.x,it.y)}
        else viewport.worldToScreen(Point2(point.x,point.y),width,height)
    return p?.let {Point2(diameter/2+(it.x-crosshair.x)*zoom,diameter/2+(it.y-crosshair.y)*zoom)}
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

/** Match the production line/surface shader's dark-theme contrast before lighting. */
internal fun precisionLoupeColour(colour:Color,dark:Boolean,surface:Boolean=false):Color {
    val luminance=.2126f*colour.red+.7152f*colour.green+.0722f*colour.blue
    val addition=if(dark && luminance<(if(surface).08f else .12f))(if(surface).14f else .74f)-luminance else 0f
    return Color((colour.red+addition).coerceIn(0f,1f),(colour.green+addition).coerceIn(0f,1f),
        (colour.blue+addition).coerceIn(0f,1f),colour.alpha)
}

private fun unitSuffix(unit:LengthUnit)=when(unit) {
    LengthUnit.MILLIMETRES->"mm";LengthUnit.CENTIMETRES->"cm";LengthUnit.METRES->"m";LengthUnit.INCHES->"in";LengthUnit.FEET->"ft"
}

private fun DrawScope.drawLoupeGrid(at:Point2,viewport:Viewport2D,orbit:OrbitCamera,is3D:Boolean,width:Double,height:Double,
    floorZ:Double,spacing:Double,zoom:Double,colour:Color) {
    if(!spacing.isFinite() || spacing<=0)return
    val origin=if(is3D)orbit.rayToPlaneZ(at.x,at.y,width,height,floorZ)?.let {Point2(it.x,it.y)} else viewport.screenToWorld(at.x,at.y,width,height)
    origin ?: return
    val perMetre=if(is3D)height/orbit.orthographicHeight else viewport.pixelsPerMetre
    val step=adaptiveGridStep(spacing,perMetre*zoom,14.0*density)
    val span=size.width/(perMetre*zoom).coerceAtLeast(1e-12)*3
    if(!step.isFinite() || !span.isFinite())return
    fun p(point:Point3)=precisionLoupePoint(point,at,viewport,orbit,is3D,width,height,size.width.toDouble(),zoom)
    val x0=floor((origin.x-span)/step)*step;val y0=floor((origin.y-span)/step)*step
    val count=ceil(span*2/step).toInt().coerceIn(0,128)
    for(i in 0..count) {
        val x=x0+i*step;val y=y0+i*step
        listOf(Point3(x,origin.y-span,floorZ) to Point3(x,origin.y+span,floorZ),
            Point3(origin.x-span,y,floorZ) to Point3(origin.x+span,y,floorZ)).forEach {(a,b)->
            val pa=p(a);val pb=p(b)
            if(pa!=null && pb!=null)drawLine(colour,Offset(pa.x.toFloat(),pa.y.toFloat()),Offset(pb.x.toFloat(),pb.y.toFloat()),density)
        }
    }
}

/** Small projected chunk BVH: built once per scene/camera off UI, never per finger update. */
private class LoupeSurfaces(scene:SurfaceScene,viewport:Viewport2D,orbit:OrbitCamera,is3D:Boolean,width:Double,height:Double,levelId:String?) {
    private data class Entry(val chunk:SurfaceChunk,val left:Double,val top:Double,val right:Double,val bottom:Double)
    private class Node(val entries:List<Entry>,val left:Double,val top:Double,val right:Double,val bottom:Double,val first:Node?=null,val second:Node?=null)
    private val root:Node?
    init {
        val entries=scene.chunks.mapNotNull {chunk->
            if(levelId!=null && chunk.levelId!=null && chunk.levelId!=levelId)return@mapNotNull null
            val points=buildList {
                for(x in listOf(chunk.bounds.min.x,chunk.bounds.max.x))for(y in listOf(chunk.bounds.min.y,chunk.bounds.max.y))
                    for(z in listOf(chunk.bounds.min.z,chunk.bounds.max.z)) {
                        val p=if(is3D)orbit.project(Point3(x,y,z),width,height)?.let {Point2(it.x,it.y)} else viewport.worldToScreen(Point2(x,y),width,height)
                        if(p!=null)add(p)
                    }
            }
            if(points.isEmpty())null else Entry(chunk,points.minOf {it.x},points.minOf {it.y},points.maxOf {it.x},points.maxOf {it.y})
        }
        fun build(items:List<Entry>):Node? {
            if(items.isEmpty())return null
            val l=items.minOf {it.left};val t=items.minOf {it.top};val r=items.maxOf {it.right};val b=items.maxOf {it.bottom}
            if(items.size<=8)return Node(items,l,t,r,b)
            val ordered=if(r-l>=b-t)items.sortedBy {it.left/2+it.right/2} else items.sortedBy {it.top/2+it.bottom/2}
            return Node(emptyList(),l,t,r,b,build(ordered.subList(0,ordered.size/2)),build(ordered.subList(ordered.size/2,ordered.size)))
        }
        root=build(entries)
    }
    fun near(point:Point2,radius:Double):List<SurfaceChunk> {
        val result=ArrayList<SurfaceChunk>();var work=0
        fun visit(node:Node?) {
            if(node==null || result.size>=16 || ++work>512 || node.right<point.x-radius || node.left>point.x+radius || node.bottom<point.y-radius || node.top>point.y+radius)return
            if(node.first!=null) {visit(node.first);visit(node.second)} else for(e in node.entries) {
                if(result.size>=16)break
                if(e.right>=point.x-radius && e.left<=point.x+radius && e.bottom>=point.y-radius && e.top<=point.y+radius)result.add(e.chunk)
            }
        }
        visit(root);return result
    }
}

private fun DrawScope.drawLoupeFaces(chunks:List<SurfaceChunk>,at:Point2,viewport:Viewport2D,orbit:OrbitCamera,is3D:Boolean,
    width:Double,height:Double,zoom:Double,mode:ViewMode,background:Color) {
    data class Face(val points:List<Point2>,val depth:Double,val colour:Color)
    val faces=ArrayList<Face>()
    for(chunk in chunks) {
        val values=chunk.vertices
        for(offset in values.indices step 30) {
            if(faces.size>=2048 || offset+29>=values.size)break
            val points=ArrayList<Point2>(3);var depth=0.0
            repeat(3) {i->
                val a=offset+i*10;val world=Point3(chunk.origin.x+values[a],chunk.origin.y+values[a+1],chunk.origin.z+values[a+2])
                val p=precisionLoupePoint(world,at,viewport,orbit,is3D,width,height,size.width.toDouble(),zoom)
                if(p!=null) {points.add(p);depth+=if(is3D)orbit.project(world,width,height)?.z ?: 0.0 else -world.z}
            }
            if(points.size!=3)continue
            val hidden=mode==ViewMode.HIDDEN_LINE || mode==ViewMode.SECTION
            val nx=values[offset+3];val ny=values[offset+4];val nz=values[offset+5]
            val lit=mode==ViewMode.SHADED || mode==ViewMode.SHADED_WITH_EDGES || mode==ViewMode.REALISTIC
            val shade=if(lit)(.36+.64*abs(nx*.45-ny*.4+nz*.8)).coerceIn(.2,1.0).toFloat() else 1f
            val base=precisionLoupeColour(Color(values[offset+6],values[offset+7],values[offset+8],values[offset+9]),background.luminance()<.5f,surface=true)
            val colour=if(hidden)background else Color((base.red*shade).coerceIn(0f,1f),(base.green*shade).coerceIn(0f,1f),
                (base.blue*shade).coerceIn(0f,1f),if(mode==ViewMode.XRAY || mode==ViewMode.TRANSPARENT).28f else base.alpha)
            faces.add(Face(points,depth/3,colour))
        }
    }
    faces.sortedByDescending {it.depth}.forEach {face->
        val path=Path().apply {moveTo(face.points[0].x.toFloat(),face.points[0].y.toFloat());face.points.drop(1).forEach {lineTo(it.x.toFloat(),it.y.toFloat())};close()}
        drawPath(path,face.colour)
    }
}
