package com.eezydraw.core.editing

import com.eezydraw.core.geometry.*
import com.eezydraw.core.model.*
import kotlin.math.*

private const val WALL_EPS=1e-7
const val WALL_DEVICE_FEED="wallDeviceFeed"
data class WallFacePick(val frame:WallFrame,val side:WallFaceSide,val point:Point3)

object WallServices {
    private fun eligible(document:ProjectDocument,frame:WallFrame,level:LevelId?):Boolean {
        val e=document.elements.firstOrNull {it.id==frame.host.id} ?: frame.host
        return (level==null || e.levelId==null || e.levelId==level) && e.lockMode==LockMode.NONE &&
            document.layers.none {it.id==e.layerId && it.locked} && ScenePresentation(document).display(e)!=ObjectDisplay.HIDDEN &&
            (frame.roomSide?.let {ScenePresentation(document).sideDisplay(e,it)!=ObjectDisplay.HIDDEN} ?: true)
    }
    fun nearest(document:ProjectDocument,point:Point3,level:LevelId?=null,preferred:WallServiceAttachment?=null,tolerance:Double=.75,allowLocked:Boolean=false):WallFacePick? {
        val frames=wallFrames(document)
        val unlocked=if(allowLocked)document.copy(elements=document.elements.map {it.withLock(LockMode.NONE)},layers=document.layers.map {it.copy(locked=false)})else document
        val candidates=frames.filter {eligible(unlocked,it,level) && (preferred==null || it.host.id==preferred.wallId && it.roomSide==preferred.roomSide)}.flatMap {frame->
            val q=frame.coordinates(point)
            if(q.z < -1e-6 || q.z>frame.height+1e-6)emptyList()else WallFaceSide.entries.filter {preferred==null || it==preferred.side}.map {side->
                val at=frame.world(q.x.coerceIn(0.0,frame.length),frame.faceDepth(side),q.z.coerceIn(0.0,frame.height))
                WallFacePick(frame,side,at)
            }
        }
        return candidates.filter {distance(it.point,point)<=tolerance}.minByOrNull {distance(it.point,point)}
    }
    /** Real wall-plane ray intersection works in orbit and orthographic elevation views. */
    fun pick(document:ProjectDocument,screen:Point2,orbit:OrbitCamera,width:Double,height:Double,level:LevelId?,preferred:WallServiceAttachment?=null):WallFacePick? {
        val ray=cameraPickRay(orbit,screen.x,screen.y,width,height)
        return wallFrames(document).filter {eligible(document,it,level) && (preferred==null || it.host.id==preferred.wallId && it.roomSide==preferred.roomSide)}.flatMap {frame->
            WallFaceSide.entries.filter {preferred==null || preferred.side==it}.mapNotNull {side->
                val plane=frame.world(0.0,frame.faceDepth(side),0.0);val n=frame.outward(side)
                val denominator=ray.direction.x*n.x+ray.direction.y*n.y
                if(abs(denominator)<1e-10)return@mapNotNull null
                val t=((plane.x-ray.origin.x)*n.x+(plane.y-ray.origin.y)*n.y)/denominator
                if(t<0)return@mapNotNull null
                val point=Point3(ray.origin.x+ray.direction.x*t,ray.origin.y+ray.direction.y*t,ray.origin.z+ray.direction.z*t)
                val q=frame.coordinates(point)
                if(q.x !in 0.0..frame.length || q.z !in 0.0..frame.height)null else t to WallFacePick(frame,side,point)
            }
        }.minByOrNull {it.first}?.second
    }
    fun append(document:ProjectDocument,kind:RouteKind,picks:List<RoutePick>,point:Point3,settings:RoutingSettings,level:Level?):List<RoutePick> {
        require(kind==RouteKind.CONDUIT || kind==RouteKind.TRUNKING){"Wall routing supports conduit and trunking"}
        val options=requireNotNull(settings.wallOptions)
        val face=requireNotNull(nearest(document,point,level?.id)){"Aim at a visible, unlocked wall face"}
        val frame=face.frame;var q=frame.coordinates(point)
        val last=picks.lastOrNull()?.point?.let(frame::coordinates)
        if(last!=null) {
            val dx=q.x-last.x;val dz=q.z-last.z
            val direction=when(settings.effectiveWallSnapAngles()) {
                WallSnapAngles.FREE->Point2(dx,dz)
                WallSnapAngles.HORIZONTAL->Point2(dx,0.0)
                WallSnapAngles.NINETY->if(abs(dx)>=abs(dz))Point2(dx,0.0)else Point2(0.0,dz)
                WallSnapAngles.FORTY_FIVE->{
                    val angle=round(atan2(dz,dx)/(PI/4))*(PI/4)
                    val x=cos(angle);val z=sin(angle);val length=dx*x+dz*z
                    Point2(x*length,z*length)
                }
            }
            // Clip along the snapped ray, so a wall edge cannot turn a diagonal into a free angle.
            var fraction=1.0
            if(direction.x>WALL_EPS)fraction=min(fraction,(frame.length-last.x)/direction.x)
            if(direction.x < -WALL_EPS)fraction=min(fraction,-last.x/direction.x)
            if(direction.y>WALL_EPS)fraction=min(fraction,(frame.height-last.z)/direction.y)
            if(direction.y < -WALL_EPS)fraction=min(fraction,-last.z/direction.y)
            q=q.copy(x=last.x+direction.x*fraction.coerceIn(0.0,1.0),z=last.z+direction.y*fraction.coerceIn(0.0,1.0))
        }
        val section=settings.section(kind)
        val depth=when(section){is RouteSection.Circular->section.diameter;is RouteSection.Rectangular->section.height}
        val into=if(options.mode==WallServiceMode.SURFACE)-depth/2 else options.clearance+depth/2
        val d=if(face.side==WallFaceSide.LEFT)into else frame.thickness-into
        val at=frame.world(q.x.coerceIn(0.0,frame.length),d,q.z.coerceIn(0.0,frame.height))
        if(picks.lastOrNull()?.point?.let {distance(it,at)<WALL_EPS}==true)return picks
        require(picks.size<512){"Finish the route before adding more points"}
        return picks+RoutePick(at,settings,level?.id)
    }
    fun attach(document:ProjectDocument,element:Element,options:WallServiceOptions,preferred:WallServiceAttachment?=element.wallService()):Element {
        val body=element.body
        val target=if(body is ElementBody.Component)measurementToWorld(element.transform,Point3(body.size.width/2,0.0,body.size.height/2))else {
            val r=body as? ElementBody.Route ?: error("Select a wall service")
            val a=measurementToWorld(element.transform,r.path.first());val b=measurementToWorld(element.transform,r.path.last());Point3((a.x+b.x)/2,(a.y+b.y)/2,(a.z+b.z)/2)
        }
        val face=requireNotNull(nearest(document,target,element.levelId,preferred,allowLocked=options.mode==WallServiceMode.SURFACE)){"The service must remain on its visible, unlocked wall face"}
        val f=face.frame;val attachment=WallServiceAttachment(f.host.id,face.side,options,f.roomSide)
        val normal=f.outward(face.side)
        val transform=when(body) {
            is ElementBody.Component -> {
                val width=body.size.width*abs(element.transform.scale.x);val height=body.size.height*abs(element.transform.scale.z)
                require(width<=f.length+1e-8 && height<=f.height+1e-8){"The device does not fit on this wall"}
                val centre=f.coordinates(target);val z=(element.transform.translation.z-f.origin.z).coerceIn(0.0,f.height-height)
                val x=Point3(normal.y,-normal.x)
                val sign=x.x*f.along.x+x.y*f.along.y
                val s=centre.x.coerceIn(width/2,f.length-width/2)-sign*width/2
                val inward=if(options.mode==WallServiceMode.SURFACE)0.0 else body.size.depth*abs(element.transform.scale.y)
                val depth=f.faceDepth(face.side)+if(face.side==WallFaceSide.LEFT)inward else -inward
                element.transform.copy(translation=f.world(s,depth,z),rotation=Rotation3(z=atan2(-normal.x,normal.y)))
            }
            else -> element.transform
        }
        var changed=element.copy(transform=transform).withWallService(attachment)
        if(body is ElementBody.Route) {
            val physicalDepth=when(val s=body.section){is RouteSection.Circular->s.diameter;is RouteSection.Rectangular->s.height}
            val inward=if(options.mode==WallServiceMode.SURFACE)-physicalDepth/2 else options.clearance+physicalDepth/2
            val d=f.faceDepth(face.side)+if(face.side==WallFaceSide.LEFT)inward else -inward
            val points=body.path.map {p->val q=f.coordinates(measurementToWorld(element.transform,p));
                require(q.x in -1e-6..f.length+1e-6 && q.z in -1e-6..f.height+1e-6){"The route extends outside its wall face"}
                measurementToLocal(transform,f.world(q.x,d,q.z))
            }
            changed=changed.copy(body=(changed.body as ElementBody.Route).copy(path=points,routing=body.routing?.let {it.copy(settings=it.settings.copy(wallOptions=options))},host=HostAttachment(f.host.id,if(options.mode==WallServiceMode.CHASE)HostMode.CHASE else HostMode.SURFACE,measurementToLocal(f.host.transform,measurementToWorld(transform,points.first())),options.depth ?: physicalDepth+options.clearance)))
        } else if(changed.body is ElementBody.Component) {
            val component=changed.body as ElementBody.Component
            changed=changed.copy(body=component.copy(host=HostAttachment(f.host.id,when(options.mode){WallServiceMode.SURFACE->HostMode.SURFACE;WallServiceMode.OPENING->HostMode.THROUGH_OPENING;else->HostMode.RECESSED},measurementToLocal(f.host.transform,changed.transform.translation),options.depth ?: component.size.depth)))
        }
        return changed
    }
    fun reconcile(before:ProjectDocument,after:ProjectDocument):ProjectDocument {
        val old=before.elements.associateBy {it.id};val hosts=after.elements.associateBy {it.id}
        val orphan=after.elements.filter {e->(e.properties[WALL_DEVICE_FEED] as? ParameterValue.Text)?.value?.let {id->ElementId(id) !in hosts}==true}.mapTo(hashSetOf()){it.id}
        if(orphan.isNotEmpty())return reconcile(before,EditCommands.delete(after,orphan).preview(after))
        val elements=after.elements.map {e->
            val mount=e.wallService() ?: return@map e
            val host=hosts[mount.wallId] ?: return@map e.withWallService(null)
            val previous=old[e.id];val oldHost=old[host.id]
            if(previous==e && oldHost==host)return@map e
            // An existing locked host can keep attachments; new attachment commands require an unlocked face.
            val unlocked=after.copy(elements=after.elements.map {if(it.id==host.id)it.withLock(LockMode.NONE)else it},layers=after.layers.map {if(it.id==host.layerId)it.copy(locked=false)else it})
            var candidate=e
            if(oldHost!=null && oldHost.transform!=host.transform && previous==e) {
                val p=measurementToWorld(host.transform,measurementToLocal(oldHost.transform,e.transform.translation))
                candidate=e.copy(transform=e.transform.copy(translation=p,rotation=e.transform.rotation.copy(z=e.transform.rotation.z+host.transform.rotation.z-oldHost.transform.rotation.z)))
            }
            // Visibility never invalidates an existing attachment or its derived physical cut.
            val visible=unlocked.copy(elements=unlocked.elements.map {if(it.id==host.id)it.withDisplay(ObjectDisplay.NORMAL).withAllRoomSidesNormal()else it},
                layers=unlocked.layers.map {if(it.id==host.layerId)it.copy(visible=true)else it},project=unlocked.project.copy(settings=unlocked.project.settings.copy(hiddenLevelIds=emptySet())),systems=unlocked.systems.map {it.copy(visible=true)})
            attach(visible,candidate,mount.options,mount)
        }
        return after.copy(elements=elements)
    }
    fun edit(document:ProjectDocument,id:ElementId,options:WallServiceOptions?):EditCommand {
        val e=document.elements.first {it.id==id}
        val next=if(options==null)e.withWallService(null).let {value->val b=value.body
            if(b is ElementBody.Route)value.copy(body=b.copy(host=null,routing=b.routing?.let {it.copy(settings=it.settings.copy(wallOptions=null))}))else value
        }else attach(document,e,options)
        return EditCommands.replace(document,listOf(next),"Change wall service mounting")
    }
    /** A separate concealed leg uses normal routing anchors, so moving/deleting devices updates it. */
    fun connectDevice(document:ProjectDocument,id:ElementId):ProjectDocument {
        val e=document.elements.first {it.id==id};val mount=e.wallService() ?: return document
        if(e.body !is ElementBody.Component || mount.options.mode==WallServiceMode.OPENING || document.elements.any {(it.properties[WALL_DEVICE_FEED] as? ParameterValue.Text)?.value==id.value})return document
        val body=e.body as ElementBody.Component
        val f=wallFrames(document).first {it.host.id==mount.wallId && it.roomSide==mount.roomSide}
        val front=measurementToWorld(e.transform,Point3(body.size.width/2,body.size.depth,body.size.height/2))
        val q=f.coordinates(front)
        val inward=.025/2+mount.options.clearance
        val port=f.world(q.x,f.faceDepth(mount.side)+if(mount.side==WallFaceSide.LEFT)inward else -inward,q.z)
        val near=document.elements.mapNotNull {r->val route=r.body as? ElementBody.Route ?: return@mapNotNull null
            val attached=route.wallService ?: return@mapNotNull null
            if(r.lockMode!=LockMode.NONE || ScenePresentation(document).display(r)==ObjectDisplay.HIDDEN || route.kind!=RouteKind.CONDUIT || attached.wallId!=mount.wallId || attached.options.mode!=WallServiceMode.CHASE)return@mapNotNull null
            val topology=route.routing ?: return@mapNotNull null
            listOf(route.path.first(),route.path.last()).map {measurementToWorld(r.transform,it)}.minByOrNull {distance(it,port)}?.let {Triple(r,it,topology.settings)}
        }.filter {distance(it.second,port)<.6}.minByOrNull {distance(it.second,port)}
        val start=near?.second ?: when(mount.options.drop) {
            WallDrop.NONE -> return document
            WallDrop.FLOOR -> port.copy(z=f.origin.z)
            WallDrop.CEILING -> port.copy(z=f.origin.z+f.height)
        }
        if(distance(start,port)<.001)return document
        val settings=(near?.third ?: defaultRoutingSettings(RouteKind.CONDUIT)).copy(wallOptions=WallServiceOptions(WallServiceMode.CHASE,clearance=mount.options.clearance))
        val points=mutableListOf(start)
        val a=f.coordinates(start);val b=f.coordinates(port)
        if(abs(a.x-b.x)>.001 && abs(a.z-b.z)>.001)points+=f.world(b.x,a.y,a.z)
        points+=port
        val command=ServiceRouting.create(document,RouteKind.CONDUIT,points.map {RoutePick(it,settings,e.levelId)})
        var result=command.preview(document)
        val newIds=command.addedElementIds
        result=result.copy(elements=result.elements.map {route->val b=route.body as? ElementBody.Route
            if(route.id !in newIds || b==null)route else {
                val t=b.routing!!;val local=measurementToLocal(e.transform,port)
                val end=measurementToWorld(route.transform,b.path.last());val endCoordinate=f.coordinates(end)
                val anchor=if(abs(endCoordinate.x-q.x)<.001 && abs(end.z-port.z)<.001)RouteAnchor(e.id,local)else t.endAnchor
                route.copy(body=b.copy(routing=t.copy(endAnchor=anchor)),properties=route.properties+(WALL_DEVICE_FEED to ParameterValue.Text(e.id.value)))
            }
        })
        return result
    }
    private fun distance(a:Point3,b:Point3)=sqrt((a.x-b.x).pow(2)+(a.y-b.y).pow(2)+(a.z-b.z).pow(2))
}
