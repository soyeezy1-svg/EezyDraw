package com.eezydraw.core.model

import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable enum class WallServiceMode { SURFACE, CHASE, RECESS, OPENING }
@Serializable enum class WallFaceSide { LEFT, RIGHT }
@Serializable enum class WallDrop { NONE, CEILING, FLOOR }
@Serializable enum class OpeningKind { DOOR, DOUBLE_DOOR, SLIDING_DOOR, WINDOW, DOUBLE_WINDOW }
@Serializable enum class DoorHinge { LEFT, RIGHT }
@Serializable enum class DoorDirection { IN, OUT }
@Serializable data class DoorSwing(val hinge:DoorHinge=DoorHinge.LEFT,val direction:DoorDirection=DoorDirection.IN) {
    fun flipped()=copy(direction=if(direction==DoorDirection.IN)DoorDirection.OUT else DoorDirection.IN)
}
@Serializable enum class WallPositionReference { LEFT_END, RIGHT_END, NEAREST_CORNER, NEAREST_DOOR }
@Serializable data class AlongWallPosition(val reference:WallPositionReference,val distance:Double) {
    init {require(distance.isFinite() && distance>=0)}
}
@Serializable enum class OpeningProfile { RECTANGULAR, CIRCULAR }
@Serializable
data class WallServiceOptions(
    val mode:WallServiceMode = WallServiceMode.SURFACE,
    val clearance:Double = .005,
    val depth:Double? = null,
    val drop:WallDrop = WallDrop.NONE,
    val profile:OpeningProfile = OpeningProfile.RECTANGULAR,
    /** Placement/edit intent; resolved into the attachment side/transform, then cleared. */
    val side:WallFaceSide?=null,
    val position:AlongWallPosition?=null,
    val swing:DoorSwing?=null,
) {
    init {require(clearance.isFinite() && clearance in 0.0..1.0);require(depth==null || depth.isFinite() && depth>0 && depth<=10)}
}
@Serializable
data class WallServiceAttachment(val wallId:ElementId,val side:WallFaceSide,val options:WallServiceOptions,
    val roomSide:RoomSideRef? = null)

@Serializable
data class WallServiceSettings(val showCuts:Boolean=true,val maxDepthFraction:Double=.5,val sectionHeight:Double=1.2) {
    init {require(maxDepthFraction.isFinite() && maxDepthFraction in .01..1.0);require(sectionHeight.isFinite() && sectionHeight>0)}
}

/** Orthonormal physical metres: along wall, depth inward from left finish, height above base. */
data class WallFrame(val host:Element,val roomSide:RoomSideRef?,val origin:Point3,val along:Point3,val normal:Point3,
    val length:Double,val thickness:Double,val height:Double,val skew:Double=0.0) {
    fun world(s:Double,d:Double,z:Double)=Point3(origin.x+along.x*(s+skew*d/thickness)-normal.x*d,origin.y+along.y*(s+skew*d/thickness)-normal.y*d,origin.z+z)
    fun coordinates(p:Point3):Point3 {
        val depth=-((p.x-origin.x)*normal.x+(p.y-origin.y)*normal.y)
        return Point3((p.x-origin.x)*along.x+(p.y-origin.y)*along.y-skew*depth/thickness,depth,p.z-origin.z)
    }
    fun faceDepth(side:WallFaceSide)=if(side==WallFaceSide.LEFT)0.0 else thickness
    fun outward(side:WallFaceSide)=if(side==WallFaceSide.LEFT)normal else Point3(-normal.x,-normal.y)
}
fun wallFrames(document:ProjectDocument):List<WallFrame> = document.elements.flatMap {host->
    if(abs(host.transform.rotation.x)>1e-8 || abs(host.transform.rotation.y)>1e-8)return@flatMap emptyList()
    fun frame(a:Point2,b:Point2,thickness:Double,height:Double,reference:WallReference,side:RoomSideRef?):WallFrame? {
        val dx=b.x-a.x;val dy=b.y-a.y;val length=hypot(dx,dy);if(length<1e-9)return null
        val nx=-dy/length;val ny=dx/length
        val offset=when(reference){WallReference.CENTERLINE->thickness/2;WallReference.LEFT_FINISH_FACE->0.0;WallReference.RIGHT_FINISH_FACE->thickness}
        val origin=measurementToWorld(host.transform,Point3(a.x+nx*offset,a.y+ny*offset))
        val end=measurementToWorld(host.transform,Point3(b.x+nx*offset,b.y+ny*offset))
        val worldLength=hypot(end.x-origin.x,end.y-origin.y)
        if(worldLength<1e-9)return null
        val along=Point3((end.x-origin.x)/worldLength,(end.y-origin.y)/worldLength)
        var normal=Point3(-along.y,along.x)
        val inner=measurementToWorld(host.transform,Point3(a.x+nx*(offset-thickness),a.y+ny*(offset-thickness)))
        if((origin.x-inner.x)*normal.x+(origin.y-inner.y)*normal.y<0)normal=Point3(-normal.x,-normal.y)
        val depth=abs((origin.x-inner.x)*normal.x+(origin.y-inner.y)*normal.y)
        val h=abs(host.transform.scale.z)*height
        return WallFrame(host,side,origin.copy(z=minOf(origin.z,measurementToWorld(host.transform,Point3(z=height)).z)),along,normal,worldLength,depth,h,(inner.x-origin.x)*along.x+(inner.y-origin.y)*along.y)
    }
    when(val body=host.body) {
        is ElementBody.Wall->listOfNotNull(frame(body.start,body.end,body.thickness,body.height,body.reference,null))
        is ElementBody.Room->(listOf(body.boundary.outer)+body.boundary.holes).flatMapIndexed {ringIndex,ring->ring.indices.mapNotNull {i->
            val side=RoomSideRef(host.id,i,if(ringIndex==0)null else ringIndex-1)
            if(body.wallFaces.any {it.edgeIndex==i && it.ringIndex==side.ringIndex})null else frame(ring[i],ring[(i+1)%ring.size],document.project.settings.defaultWallThickness,body.ceilingHeight,WallReference.LEFT_FINISH_FACE,side)
        }}
        else->emptyList()
    }
}
fun Element.wallService():WallServiceAttachment?=when(val b=body){is ElementBody.Route->b.wallService;is ElementBody.Component->b.wallService;else->null}
fun Element.withWallService(value:WallServiceAttachment?):Element=copy(body=when(val b=body){is ElementBody.Route->b.copy(wallService=value);is ElementBody.Component->b.copy(wallService=value);else->b})

data class WallCut(val wallId:ElementId,val sourceId:ElementId,val roomSide:RoomSideRef?,val mode:WallServiceMode,
    val outline:List<Point2>,val fromDepth:Double,val toDepth:Double,val wallThickness:Double,val description:String,val requestedDepth:Double=toDepth-fromDepth,val side:WallFaceSide=WallFaceSide.LEFT) {
    val width get()=outline.maxOf {it.x}-outline.minOf {it.x}
    val height get()=outline.maxOf {it.y}-outline.minOf {it.y}
    val depth get()=requestedDepth
    val position get()=Point2((outline.maxOf {it.x}+outline.minOf {it.x})/2,(outline.maxOf {it.y}+outline.minOf {it.y})/2)
    fun exceeds(fraction:Double)=mode!=WallServiceMode.OPENING && depth>wallThickness*fraction+1e-9
}

/** Derived cuts are never serialized as stale geometry or substituted for nominal wall dimensions. */
fun wallServiceCuts(document:ProjectDocument):List<WallCut> {
    val frames=wallFrames(document).associateBy {it.host.id to it.roomSide}
    val direct=document.elements.flatMap {e->
        val attachment=e.wallService() ?: return@flatMap emptyList()
        val frame=frames[attachment.wallId to attachment.roomSide] ?: return@flatMap emptyList()
        val definition=(e.body as? ElementBody.Component)?.let {b->document.libraryItems.find {it.id==b.libraryItemId} ?: BuiltInLibrary.find(b.libraryItemId)}
        val architectural=definition?.openingKind()!=null
        val options=if(architectural)attachment.options.copy(mode=WallServiceMode.OPENING,clearance=0.0,depth=null,profile=OpeningProfile.RECTANGULAR)else attachment.options
        if(options.mode==WallServiceMode.SURFACE)return@flatMap emptyList()
        fun cut(outline:List<Point2>,depth:Double):WallCut {
            val from=if(options.mode==WallServiceMode.OPENING || attachment.side==WallFaceSide.LEFT)0.0 else (frame.thickness-depth).coerceAtLeast(0.0)
            val to=if(options.mode==WallServiceMode.OPENING || attachment.side==WallFaceSide.RIGHT)frame.thickness else depth.coerceAtMost(frame.thickness)
            // Keep requested depth for warnings, even when the rendered cut reaches through the wall.
            return WallCut(frame.host.id,e.id,frame.roomSide,options.mode,outline,from,to,frame.thickness,e.name,if(options.mode==WallServiceMode.OPENING)frame.thickness else depth,attachment.side)
        }
        when(val body=e.body) {
            is ElementBody.Route -> {
                val width=when(val s=body.section){is RouteSection.Circular->s.diameter;is RouteSection.Rectangular->s.width}+2*options.clearance
                val depth=options.depth ?: (when(val s=body.section){is RouteSection.Circular->s.diameter;is RouteSection.Rectangular->s.height}+2*options.clearance)
                (if(options.mode==WallServiceMode.CHASE)body.path else body.fabricationPath ?: body.path).map {frame.coordinates(measurementToWorld(e.transform,it))}.zipWithNext().mapNotNull {(a,b)->
                    val length=hypot(b.x-a.x,b.z-a.z);if(length<1e-9)null else {
                        val x=-(b.z-a.z)/length*width/2;val z=(b.x-a.x)/length*width/2
                        cut(listOf(Point2(a.x+x,a.z+z),Point2(b.x+x,b.z+z),Point2(b.x-x,b.z-z),Point2(a.x-x,a.z-z)),depth)
                    }
                }
            }
            is ElementBody.Component -> {
                val vertices=listOf(Point3(),Point3(body.size.width),Point3(body.size.width,z=body.size.height),Point3(z=body.size.height)).map {frame.coordinates(measurementToWorld(e.transform,it))}
                val minS=vertices.minOf {it.x}-options.clearance;val maxS=vertices.maxOf {it.x}+options.clearance
                val minZ=vertices.minOf {it.z}-options.clearance;val maxZ=vertices.maxOf {it.z}+options.clearance
                val outline=if(options.profile==OpeningProfile.CIRCULAR)List(48){i->val angle=2*PI*i/48;Point2((minS+maxS)/2+(maxS-minS)/2*cos(angle),(minZ+maxZ)/2+(maxZ-minZ)/2*sin(angle))}
                    else listOf(Point2(minS,minZ),Point2(maxS,minZ),Point2(maxS,maxZ),Point2(minS,maxZ))
                listOf(cut(outline,options.depth ?: (body.size.depth*abs(e.transform.scale.y)+options.clearance)))
            }
            else->emptyList()
        }
    }
    return direct+wallChaseMitres(document,frames)
}
fun wallDepthWarnings(document:ProjectDocument):List<String> = wallServiceCuts(document).filter {it.exceeds(document.project.settings.wallServices.maxDepthFraction)}.map {cut->
    "${cut.description.ifBlank {cut.sourceId.value}}: cut depth ${"%.0f".format(java.util.Locale.ROOT,cut.depth*1000)} mm exceeds ${"%.0f".format(java.util.Locale.ROOT,document.project.settings.wallServices.maxDepthFraction*100)}% of wall thickness."
}.distinct()

fun defaultWallDeviceOptions(item:LibraryItem):WallServiceOptions = if(item.openingKind()!=null)
    WallServiceOptions(WallServiceMode.OPENING,clearance=0.0,swing=DoorSwing().takeIf {item.openingKind() in setOf(OpeningKind.DOOR,OpeningKind.DOUBLE_DOOR)})
    else item.wallService ?: if(item.symbol in setOf(LibrarySymbol.SWITCH,LibrarySymbol.DOUBLE_SWITCH,LibrarySymbol.DIMMER,LibrarySymbol.SOCKET,LibrarySymbol.DATA_OUTLET,LibrarySymbol.TV_OUTLET))
    WallServiceOptions(WallServiceMode.RECESS)else WallServiceOptions()

/** Defaults for project definitions saved before architectural opening metadata existed. */
fun LibraryItem.openingKind():OpeningKind? = opening ?: when((catalogSourceId ?: id).value) {
    "eezy.architecture.single-door"->OpeningKind.DOOR
    "eezy.architecture.double-door"->OpeningKind.DOUBLE_DOOR
    "eezy.architecture.sliding-door"->OpeningKind.SLIDING_DOOR
    "eezy.architecture.single-window","eezy.architecture.fixed-window"->OpeningKind.WINDOW
    "eezy.architecture.double-window"->OpeningKind.DOUBLE_WINDOW
    else->null
}
