package com.eezydraw.core.editing

import com.eezydraw.core.geometry.*
import com.eezydraw.core.model.*
import java.util.Arrays
import java.util.PriorityQueue
import kotlin.math.*

/** One canonical pick feeds the crosshair, loupe, ghost, live readout and eventual confirmation. */
data class PrecisionPick(val point:Point3,val screen:Point2,val mode:SnapMode?=null,
    val elementId:ElementId?=null,val wallEdge:Boolean=false)

/** Prepared with a scene, never rebuilt for a finger movement. The world BVH also handles front/side. */
class PointPickContext(document:ProjectDocument,scene:DrawingScene) {
    private val index=SpatialIndex(scene.segments)
    private val plan=SnapEngine(index)
    private val world=ScreenSegmentIndex(scene.segments)
    private val wallIds=document.elements.filter {it.body is ElementBody.Wall}.mapTo(HashSet()) {it.id.value}

    /** Local world edges for the magnifier; no scene scan or screen-index rebuild on drag. */
    fun nearbyScreenSegments(screen:Point2,radiusPixels:Double,viewport:Viewport2D,orbit:OrbitCamera,
        is3D:Boolean,width:Double,height:Double,levelId:String?):List<SceneSegment> {
        require(radiusPixels.isFinite() && radiusPixels>=0)
        if(width<=0 || height<=0)return emptyList()
        return if(is3D)world.near(screen,orbit,width,height,radiusPixels,levelId)
        else index.queryNear2D(viewport.screenToWorld(screen.x,screen.y,width,height),
            radiusPixels/viewport.pixelsPerMetre,limit=256,levelId=levelId)
    }

    fun pick(screen:Point2,viewport:Viewport2D,orbit:OrbitCamera,is3D:Boolean,width:Double,height:Double,
        floorZ:Double,levelId:String?,snapSettings:SnapSettings,anchor:Point3?=null,
        tolerancePixels:Double=14.0,surfacePoint:Point3?=null):PrecisionPick {
        require(width.isFinite() && height.isFinite() && width>0 && height>0 && floorZ.isFinite())
        require(tolerancePixels.isFinite() && tolerancePixels>=0)
        val tolerance=if(is3D)max(snapSettings.tolerance,tolerancePixels*metresPerPixel(orbit,height))
            else max(snapSettings.tolerance,tolerancePixels/viewport.pixelsPerMetre)
        fun result(point:Point3,mode:SnapMode?=null,id:String?=null,wall:Boolean=false)=PrecisionPick(point,
            projectPrecisionPoint(point,viewport,orbit,is3D,width,height) ?: screen,mode,id?.let(::ElementId),wall)
        if(!is3D) {
            val raw=viewport.screenToWorld(screen.x,screen.y,width,height)
            val snapped=plan.snap(raw,snapSettings,tolerance,anchor?.let {Point2(it.x,it.y)},levelId)
            val named=result(Point3(snapped.point.x,snapped.point.y,floorZ),snapped.mode,snapped.elementId)
            val known=if(named.screen.x in 0.0..width && named.screen.y in 0.0..height)named else result(Point3(raw.x,raw.y,floorZ))
            if(!snapSettings.enabled || snapSettings.modes.isEmpty())return known
            var best=known.takeIf {it.mode!=null}
            var distance=best?.let {hypot(it.point.x-raw.x,it.point.y-raw.y)} ?: tolerance
            for(edge in index.queryNear2D(raw,tolerance,limit=256,levelId=levelId)) {
                if(edge.elementId !in wallIds)continue
                val t=closest2D(raw,edge.a,edge.b)
                val point=mix(edge.a,edge.b,t).copy(z=floorZ)
                val projected=projectPrecisionPoint(point,viewport,orbit,false,width,height) ?: continue
                if(projected.x !in 0.0..width || projected.y !in 0.0..height)continue
                if(SnapMode.ORTHO in snapSettings.modes && anchor!=null) {
                    val horizontal=abs(raw.y-anchor.y)<=abs(raw.x-anchor.x)
                    if(if(horizontal)abs(point.y-anchor.y)>1e-7 else abs(point.x-anchor.x)>1e-7)continue
                }
                val d=hypot(point.x-raw.x,point.y-raw.y)
                // Exact endpoint/grid/midpoint ties retain their more useful named snap.
                if(d<distance-1e-9) {best=result(point,id=edge.elementId,wall=true);distance=d}
            }
            return best ?: known
        }
        val raw=surfacePoint ?: floorPoint(orbit,screen,width,height,floorZ)
        if(!snapSettings.enabled || snapSettings.modes.isEmpty())return result(raw)
        val pixelTolerance=max(tolerancePixels,snapSettings.tolerance/metresPerPixel(orbit,height))
        val edges=world.near(screen,orbit,width,height,pixelTolerance,levelId)
        var constrained=raw
        var orthoAxis:Int?=null
        if(SnapMode.ORTHO in snapSettings.modes && anchor!=null) {
            val deltas=listOf(abs(raw.x-anchor.x),abs(raw.y-anchor.y),abs(raw.z-anchor.z))
            orthoAxis=deltas.indices.maxBy {deltas[it]}
            constrained=when(orthoAxis) {0->Point3(raw.x,anchor.y,anchor.z);1->Point3(anchor.x,raw.y,anchor.z);else->Point3(anchor.x,anchor.y,raw.z)}
        }
        val targetScreen=projectPrecisionPoint(constrained,viewport,orbit,true,width,height) ?: screen
        var best:PrecisionPick?=null
        var bestDistance=Double.POSITIVE_INFINITY
        var bestDepth=Double.POSITIVE_INFINITY
        var bestPriority=Int.MAX_VALUE
        val epsilon=max(1e-7,max(Math.ulp(raw.x),max(Math.ulp(raw.y),Math.ulp(raw.z)))*8)
        fun consider(p:Point3,mode:SnapMode?=null,id:String?=null,wall:Boolean=false,priority:Int=6) {
            if(orthoAxis!=null && anchor!=null) {
                val delta=p-anchor
                if((orthoAxis!=0 && abs(delta.x)>epsilon)||(orthoAxis!=1 && abs(delta.y)>epsilon)||(orthoAxis!=2 && abs(delta.z)>epsilon))return
            }
            val projected=orbit.project(p,width,height) ?: return
            if(projected.x !in 0.0..width || projected.y !in 0.0..height || projected.z !in -1.0..1.0)return
            val distance=hypot(projected.x-targetScreen.x,projected.y-targetScreen.y)
            if(distance>pixelTolerance)return
            val depth=projected.z
            if(distance<bestDistance-1e-7 || abs(distance-bestDistance)<=1e-7 &&
                (priority<bestPriority || priority==bestPriority && depth<bestDepth)) {
                best=PrecisionPick(p,Point2(projected.x,projected.y),mode,id?.let(::ElementId),wall)
                bestDistance=distance;bestPriority=priority;bestDepth=depth
            }
        }
        for(edge in edges) {
            if(SnapMode.ENDPOINT in snapSettings.modes) {consider(edge.a,SnapMode.ENDPOINT,edge.elementId,priority=0);consider(edge.b,SnapMode.ENDPOINT,edge.elementId,priority=0)}
            if(SnapMode.MIDPOINT in snapSettings.modes)consider(mix(edge.a,edge.b,.5),SnapMode.MIDPOINT,edge.elementId,priority=2)
            if(SnapMode.PERPENDICULAR in snapSettings.modes && anchor!=null) {
                val direction=edge.b-edge.a;val length2=direction dot direction
                if(length2>1e-20) {
                    val t=((anchor-edge.a) dot direction)/length2
                    if(t in 0.0..1.0)consider(mix(edge.a,edge.b,t),SnapMode.PERPENDICULAR,edge.elementId,priority=3)
                }
            }
            if(edge.elementId in wallIds) {
                closestRayPoint(edge,orbit,screen,width,height)?.let {consider(it,id=edge.elementId,wall=true)}
            }
        }
        if(SnapMode.INTERSECTION in snapSettings.modes) {
            val nearby=edges.take(48)
            for(i in nearby.indices)for(j in i+1 until nearby.size)intersection3D(nearby[i],nearby[j],epsilon)
                ?.let {consider(it,SnapMode.INTERSECTION,nearby[i].elementId,priority=1)}
        }
        if(SnapMode.GRID in snapSettings.modes) {
            fun grid(v:Double)=round(v/snapSettings.gridSpacing)*snapSettings.gridSpacing
            // The ordinary construction grid belongs to the active level. Ortho retains its
            // untouched coordinates and can snap the raised Z coordinate independently.
            val p=if(orthoAxis!=null && anchor!=null)when(orthoAxis) {
                0->Point3(grid(constrained.x),anchor.y,anchor.z)
                1->Point3(anchor.x,grid(constrained.y),anchor.z)
                else->Point3(anchor.x,anchor.y,grid(constrained.z))
            } else Point3(grid(constrained.x),grid(constrained.y),if(surfacePoint==null)floorZ else grid(constrained.z))
            consider(p,SnapMode.GRID,priority=5)
        }
        return best ?: result(constrained,if(orthoAxis!=null)SnapMode.ORTHO else null)
    }
}

/** Balanced immutable XYZ tree. A camera query projects only a bounded set of nearby nodes. */
private class ScreenSegmentIndex(segments:List<SceneSegment>) {
    private val edges=segments.toTypedArray()
    private val root=if(edges.isEmpty())null else build(0,edges.size)
    private data class Node(val bounds:Bounds3,val start:Int,val end:Int,val left:Node?=null,val right:Node?=null)
    private data class Visit(val node:Node,val distance:Double)
    private fun build(start:Int,end:Int):Node {
        var lo=edges[start].a;var hi=lo
        for(i in start until end)for(p in listOf(edges[i].a,edges[i].b)) {
            lo=Point3(min(lo.x,p.x),min(lo.y,p.y),min(lo.z,p.z));hi=Point3(max(hi.x,p.x),max(hi.y,p.y),max(hi.z,p.z))
        }
        val bounds=Bounds3(lo,hi)
        if(end-start<=8)return Node(bounds,start,end)
        val extents=listOf(hi.x-lo.x,hi.y-lo.y,hi.z-lo.z)
        val axis=extents.indices.maxBy {extents[it]}
        fun center(e:SceneSegment)=when(axis) {0->e.a.x/2+e.b.x/2;1->e.a.y/2+e.b.y/2;else->e.a.z/2+e.b.z/2}
        Arrays.sort(edges,start,end,compareBy {center(it)})
        val mid=(start+end)/2
        return Node(bounds,start,end,build(start,mid),build(mid,end))
    }
    fun near(point:Point2,camera:OrbitCamera,width:Double,height:Double,tolerance:Double,level:String?):List<SceneSegment> {
        val root=root ?: return emptyList()
        fun distance(bounds:Bounds3):Double {
            if(!camera.visible(bounds,width/height))return Double.POSITIVE_INFINITY
            var minX=Double.POSITIVE_INFINITY;var minY=minX;var maxX=Double.NEGATIVE_INFINITY;var maxY=maxX
            for(x in listOf(bounds.min.x,bounds.max.x))for(y in listOf(bounds.min.y,bounds.max.y))for(z in listOf(bounds.min.z,bounds.max.z)) {
                val projected=camera.project(Point3(x,y,z),width,height) ?: return 0.0
                minX=min(minX,projected.x);maxX=max(maxX,projected.x);minY=min(minY,projected.y);maxY=max(maxY,projected.y)
            }
            return hypot(max(0.0,max(minX-point.x,point.x-maxX)),max(0.0,max(minY-point.y,point.y-maxY)))
        }
        val queue=PriorityQueue<Visit>(compareBy<Visit> {it.distance}.thenBy {it.node.end-it.node.start})
        queue.add(Visit(root,distance(root.bounds)))
        val result=ArrayList<Pair<SceneSegment,Double>>()
        var visited=0;var tested=0
        while(queue.isNotEmpty() && visited++<1024 && tested<2048) {
            val visit=queue.remove();if(visit.distance>tolerance)continue
            val n=visit.node
            if(n.left!=null) {queue.add(Visit(n.left,distance(n.left.bounds)));queue.add(Visit(n.right!!,distance(n.right.bounds)))}
            else for(i in n.start until n.end) {
                val e=edges[i];if(level!=null && e.levelId!=null && e.levelId!=level)continue
                tested++
                val projected=projectVisibleSegment(e,camera,width,height) ?: continue
                val a=projected.first;val b=projected.second
                val t=closest2D(point,a,b)
                val d=hypot(point.x-(a.x+(b.x-a.x)*t),point.y-(a.y+(b.y-a.y)*t))
                if(d<=tolerance)result.add(e to d)
            }
        }
        return result.sortedBy {it.second}.take(256).map {it.first}
    }
}

/** Keep the visible portion of an edge crossing the perspective near/eye plane queryable. */
private fun projectVisibleSegment(edge:SceneSegment,camera:OrbitCamera,width:Double,height:Double):Pair<Point3,Point3>? {
    var a=edge.a;var b=edge.b
    if(camera.projection==Projection.PERSPECTIVE) {
        val normal=Point3(cos(camera.pitch)*cos(camera.yaw),cos(camera.pitch)*sin(camera.yaw),sin(camera.pitch))
        val da=camera.distance-((a-camera.target) dot normal)
        val db=camera.distance-((b-camera.target) dot normal)
        val near=max(1e-5,camera.distance*.0001)*1.001
        if(da<near && db<near)return null
        if(da<near)a=mix(edge.a,edge.b,((near-da)/(db-da)).coerceIn(0.0,1.0))
        if(db<near)b=mix(edge.a,edge.b,((near-da)/(db-da)).coerceIn(0.0,1.0))
    }
    val first=camera.project(a,width,height) ?: return null
    val last=camera.project(b,width,height) ?: return null
    return first to last
}

private fun floorPoint(camera:OrbitCamera,screen:Point2,width:Double,height:Double,floorZ:Double):Point3 =
    camera.rayToPlaneZ(screen.x,screen.y,width,height,floorZ) ?: run {
        val ray=cameraPickRay(camera,screen.x,screen.y,width,height)
        val normal=Point3(cos(camera.pitch)*cos(camera.yaw),cos(camera.pitch)*sin(camera.yaw),sin(camera.pitch))
        val origin=Point3(ray.origin.x,ray.origin.y,ray.origin.z)
        val direction=Point3(ray.direction.x,ray.direction.y,ray.direction.z)
        val target=camera.target.copy(z=floorZ)
        val t=((target-origin) dot normal)/(direction dot normal)
        origin+direction*max(0.0,t)
    }
private fun metresPerPixel(camera:OrbitCamera,height:Double)=
    (if(camera.projection==Projection.PERSPECTIVE)2*camera.distance*tan(OrbitCamera.FOV_Y/2) else camera.orthographicHeight)/height
private fun closest2D(point:Point2,a:Point3,b:Point3):Double {
    val dx=b.x-a.x;val dy=b.y-a.y;val length=dx*dx+dy*dy
    return if(length<1e-20)0.0 else (((point.x-a.x)*dx+(point.y-a.y)*dy)/length).coerceIn(0.0,1.0)
}
private fun closestRayPoint(edge:SceneSegment,camera:OrbitCamera,screen:Point2,width:Double,height:Double):Point3? {
    val ray=cameraPickRay(camera,screen.x,screen.y,width,height)
    val origin=Point3(ray.origin.x,ray.origin.y,ray.origin.z);val view=Point3(ray.direction.x,ray.direction.y,ray.direction.z)
    val direction=edge.b-edge.a;val offset=edge.a-origin
    val aa=direction dot direction;val ab=direction dot view;val denominator=aa-ab*ab
    val t=if(abs(denominator)<1e-20).5 else ((ab*(offset dot view)-(direction dot offset))/denominator).coerceIn(0.0,1.0)
    return mix(edge.a,edge.b,t)
}
private fun intersection3D(a:SceneSegment,b:SceneSegment,epsilon:Double):Point3? {
    val u=a.b-a.a;val v=b.b-b.a;val w=a.a-b.a
    val aa=u dot u;val bb=u dot v;val cc=v dot v;val dd=u dot w;val ee=v dot w
    val denominator=aa*cc-bb*bb
    if(aa<1e-20 || cc<1e-20 || abs(denominator)<aa*cc*1e-12)return null
    val s=(bb*ee-cc*dd)/denominator;val t=(aa*ee-bb*dd)/denominator
    if(s !in -1e-9..1.000000001 || t !in -1e-9..1.000000001)return null
    val p=a.a+u*s;val q=b.a+v*t
    return if(length(p-q)<=epsilon)p else null
}
private fun mix(a:Point3,b:Point3,t:Double)=a+(b-a)*t
private fun length(p:Point3)=hypot(hypot(p.x,p.y),p.z)
private operator fun Point3.plus(other:Point3)=Point3(x+other.x,y+other.y,z+other.z)
private operator fun Point3.minus(other:Point3)=Point3(x-other.x,y-other.y,z-other.z)
private operator fun Point3.times(value:Double)=Point3(x*value,y*value,z*value)
private infix fun Point3.dot(other:Point3)=x*other.x+y*other.y+z*other.z
