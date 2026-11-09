package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import org.locationtech.jts.geom.*
import org.locationtech.jts.operation.union.UnaryUnionOp
import org.locationtech.jts.triangulate.polygon.PolygonTriangulator
import kotlin.math.*

/** Soft, short wall/floor contact strips, clipped to real visible floor footprints and door
 * thresholds. Presentation only: never used by physical meshes, selections or quantities. */
fun buildContactShadows(document:ProjectDocument):SurfaceScene {
    val factory=GeometryFactory();val presentation=ScenePresentation(document)
    fun polygon(boundary:Polygon2,transform:Transform3):Polygon {
        fun ring(points:List<Point2>)=factory.createLinearRing((points+points.first()).map {p->
            val w=measurementToWorld(transform,Point3(p.x,p.y));Coordinate(w.x,w.y)
        }.toTypedArray())
        return factory.createPolygon(ring(boundary.outer),boundary.holes.map(::ring).toTypedArray())
    }
    fun key(level:LevelId?,z:Double)=level to (z*1000).roundToLong()
    val floors=document.elements.mapNotNull {e->
        if(e.isCeiling || presentation.display(e)!=ObjectDisplay.NORMAL)return@mapNotNull null
        val boundary=when(val b=e.body){is ElementBody.Room->b.boundary;is ElementBody.Slab->b.boundary;else->return@mapNotNull null}
        if(abs(e.transform.rotation.x)>1e-8 || abs(e.transform.rotation.y)>1e-8)return@mapNotNull null
        key(e.levelId,e.transform.translation.z) to polygon(boundary,e.transform)
    }.groupBy({it.first},{it.second}).mapValues {UnaryUnionOp.union(it.value)}
    val cuts=wallServiceCuts(document).groupBy {it.wallId to it.roomSide}
    val chunks=mutableListOf<SurfaceChunk>();var triangleCount=0
    for(frame in wallFrames(document)) {
        val host=frame.host
        if(presentation.display(host)!=ObjectDisplay.NORMAL || frame.roomSide?.let {presentation.sideDisplay(host,it)!=ObjectDisplay.NORMAL}==true)continue
        val floor=floors[key(host.levelId,frame.origin.z)] ?: continue
        val gaps=cuts[host.id to frame.roomSide].orEmpty().filter {it.mode==WallServiceMode.OPENING && it.outline.minOf {p->p.y}<=.005}
            .map {it.outline.minOf {p->p.x}.coerceIn(0.0,frame.length) to it.outline.maxOf {p->p.x}.coerceIn(0.0,frame.length)}.sortedBy {it.first}
        val intervals=mutableListOf<Pair<Double,Double>>();var at=0.0
        for((a,b)in gaps){if(a>at)intervals+=at to a;at=max(at,b)}
        if(at<frame.length)intervals+=at to frame.length
        for(side in WallFaceSide.entries)for((a,b)in intervals) {
            if(b-a<1e-7)continue
            val depth=frame.faceDepth(side);val direction=if(side==WallFaceSide.LEFT)-1 else 1
            val points=listOf(frame.world(a,depth,0.0),frame.world(b,depth,0.0),frame.world(b,depth+direction*.12,0.0),frame.world(a,depth+direction*.12,0.0))
            val strip=factory.createPolygon((points+points.first()).map {Coordinate(it.x,it.y)}.toTypedArray())
            val clipped=strip.intersection(floor)
            if(clipped.isEmpty)continue
            val triangles=PolygonTriangulator.triangulate(clipped)
            val values=ArrayList<Float>();var low:Point3?=null;var high:Point3?=null
            val origin=frame.world(a,depth,.001)
            for(i in 0 until triangles.numGeometries) {
                check(triangleCount++<100_000){"Contact shadows exceed the display detail limit"}
                val ring=triangles.getGeometryN(i).coordinates
                for(j in 0..2) {
                    val p=Point3(ring[j].x,ring[j].y,origin.z)
                    val distance=abs(frame.coordinates(p).y-depth)
                    val alpha=(1-distance/.12).coerceIn(0.0,1.0)*.20
                    values+=listOf((p.x-origin.x).toFloat(),(p.y-origin.y).toFloat(),0f,0f,0f,1f,0f,0f,0f,alpha.toFloat())
                    low=low?.let {Point3(min(it.x,p.x),min(it.y,p.y),p.z)} ?: p
                    high=high?.let {Point3(max(it.x,p.x),max(it.y,p.y),p.z)} ?: p
                }
            }
            if(values.isNotEmpty())chunks+=SurfaceChunk(origin,Bounds3(low!!,high!!),host.levelId?.value,values.toFloatArray(),host.id.value,material=RenderMaterial.CONTACT_SHADOW)
        }
    }
    val bounds=chunks.map {it.bounds}.reduceOrNull {a,b->Bounds3(Point3(min(a.min.x,b.min.x),min(a.min.y,b.min.y),min(a.min.z,b.min.z)),Point3(max(a.max.x,b.max.x),max(a.max.y,b.max.y),max(a.max.z,b.max.z)))} ?: Bounds3(Point3(),Point3())
    return SurfaceScene(chunks,bounds)
}
