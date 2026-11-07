package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import org.locationtech.jts.algorithm.Orientation
import org.locationtech.jts.geom.*
import org.locationtech.jts.operation.union.UnaryUnionOp
import org.locationtech.jts.triangulate.polygon.PolygonTriangulator
import kotlin.math.abs

/** Exact planar polygon unions in small wall coordinates, extruded through distinct depth bands. */
fun wallCutMesh(frame:WallFrame,cuts:List<WallCut>,sectionHeight:Double?=null):Shape.Mesh? {
    require(cuts.size<=512){"A wall exceeds the 512-cut geometry limit"}
    val height=minOf(frame.height,sectionHeight ?: frame.height).coerceAtLeast(1e-8)
    val factory=GeometryFactory()
    fun polygon(points:List<Point2>):Polygon=factory.createPolygon((points+points.first()).map {Coordinate(it.x,it.y)}.toTypedArray())
    val extent=polygon(listOf(Point2(0.0,0.0),Point2(frame.length,0.0),Point2(frame.length,height),Point2(0.0,height)))
    val bounded=cuts.map {it to polygon(it.outline).intersection(extent)}.filterNot {it.second.isEmpty}
    val depths=(listOf(0.0,frame.thickness)+bounded.flatMap {listOf(it.first.fromDepth,it.first.toDepth)}).distinct().sorted()
    val bands=depths.zipWithNext().map {(a,b)->
        val active=bounded.filter {it.first.fromDepth<(a+b)/2 && it.first.toDepth>(a+b)/2}.map {it.second}
        if(active.isEmpty())extent else extent.difference(UnaryUnionOp.union(active))
    }
    val vertices=mutableListOf<Point3>();val indices=mutableListOf<Int>()
    fun point(s:Double,d:Double,z:Double)=measurementToLocal(frame.host.transform,frame.world(s,d,z))
    fun triangle(a:Point3,b:Point3,c:Point3,normal:Point3) {
        val aw=measurementToWorld(frame.host.transform,a);val bw=measurementToWorld(frame.host.transform,b);val cw=measurementToWorld(frame.host.transform,c)
        val ab=Point3(bw.x-aw.x,bw.y-aw.y,bw.z-aw.z);val ac=Point3(cw.x-aw.x,cw.y-aw.y,cw.z-aw.z)
        val dot=(ab.y*ac.z-ab.z*ac.y)*normal.x+(ab.z*ac.x-ab.x*ac.z)*normal.y+(ab.x*ac.y-ab.y*ac.x)*normal.z
        if(abs(dot)<1e-15)return
        val start=vertices.size;vertices+=a
        val handedness=if(frame.host.transform.scale.x*frame.host.transform.scale.y*frame.host.transform.scale.z<0)-1 else 1
        if(dot*handedness>0){vertices+=b;vertices+=c}else {vertices+=c;vertices+=b}
        indices+=listOf(start,start+1,start+2)
        require(indices.size/3<=100000){"Wall cut mesh exceeds the triangle limit"}
    }
    fun cap(solid:Geometry,depth:Double,outward:Double) {
        if(solid.isEmpty)return
        val triangles=PolygonTriangulator.triangulate(solid)
        val normal=Point3(frame.normal.x*outward,frame.normal.y*outward)
        for(i in 0 until triangles.numGeometries) {
            val v=triangles.getGeometryN(i).coordinates
            if(v.size>=3)triangle(point(v[0].x,depth,v[0].y),point(v[1].x,depth,v[1].y),point(v[2].x,depth,v[2].y),normal)
        }
    }
    val empty=factory.createPolygon()
    for(i in depths.indices) {
        val front=bands.getOrNull(i-1) ?: empty
        val back=bands.getOrNull(i) ?: empty
        cap(front.difference(back),depths[i],-1.0)
        cap(back.difference(front),depths[i],1.0)
    }
    fun sides(geometry:Geometry,from:Double,to:Double) {
        if(geometry.isEmpty)return
        for(i in 0 until geometry.numGeometries) {
            val p=geometry.getGeometryN(i) as? Polygon ?: continue
            val rings=listOf(p.exteriorRing)+List(p.numInteriorRing){p.getInteriorRingN(it)}
            rings.forEachIndexed {ringIndex,ring->
                val c=ring.coordinates
                val right=if(Orientation.isCCW(c)==(ringIndex==0))1.0 else -1.0
                for(j in 0 until c.lastIndex) {
                    val a=c[j];val b=c[j+1]
                    val ns=(b.y-a.y)*right;val nz=-(b.x-a.x)*right
                    val normal=Point3(frame.along.x*ns,frame.along.y*ns,nz)
                    val q=listOf(point(a.x,from,a.y),point(b.x,from,b.y),point(b.x,to,b.y),point(a.x,to,a.y))
                    triangle(q[0],q[1],q[2],normal);triangle(q[0],q[2],q[3],normal)
                }
            }
        }
    }
    bands.forEachIndexed {i,solid->sides(solid,depths[i],depths[i+1])}
    // A fully cut wall has no faces; Mesh requires at least one valid triangle. Callers omit it.
    return if(indices.isEmpty())null else Shape.Mesh(vertices,indices)
}

/** Only silhouette/boundary and crease edges, rather than triangulation diagonals. */
fun wallMeshEdges(mesh:Shape.Mesh):List<Pair<Point3,Point3>> {
    data class Edge(val a:Point3,val b:Point3)
    val normals=linkedMapOf<Edge,MutableList<Point3>>()
    fun compare(a:Point3,b:Point3):Int=compareValuesBy(a,b,{it.x},{it.y},{it.z})
    for(i in mesh.triangles.indices step 3) {
        val v=(0..2).map {mesh.vertices[mesh.triangles[i+it]]}
        val a=Point3(v[1].x-v[0].x,v[1].y-v[0].y,v[1].z-v[0].z);val b=Point3(v[2].x-v[0].x,v[2].y-v[0].y,v[2].z-v[0].z)
        val n=Point3(a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x)
        for(j in 0..2) {val p=v[j];val q=v[(j+1)%3];val key=if(compare(p,q)<0)Edge(p,q)else Edge(q,p);normals.getOrPut(key){mutableListOf()}.add(n)}
    }
    return normals.filter {(_,values)->values.size==1 || values.zipWithNext().any {(a,b)->
        val cross=Point3(a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x)
        cross.x*cross.x+cross.y*cross.y+cross.z*cross.z>1e-20
    }}.keys.map {it.a to it.b}
}
