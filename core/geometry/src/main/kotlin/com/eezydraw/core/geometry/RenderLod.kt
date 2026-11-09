package com.eezydraw.core.geometry

import com.eezydraw.core.model.*

fun renderLodEligible(e:Element)= (e.body as? ElementBody.Component)?.let {it.wallService==null}==true && !e.isCeiling
/** A stable subset of actual distant wire edges, rather than invented connecting segments. */
fun coarseWire(vertices:FloatArray):FloatArray {
    if(vertices.size<=14*24)return FloatArray(0)
    val indices=(0 until vertices.size/14 step 4).toMutableList()
    if(indices.last()!=vertices.size/14-1)indices+=vertices.size/14-1
    return FloatArray(indices.size*14){i->vertices[indices[i/14]*14+i%14]}
}
/** Twelve-triangle distant equipment proxy. Openings/walls/ceilings never opt into it. */
fun coarseSurface(source:SurfaceChunk):FloatArray {
    if(!source.lodAllowed || source.vertices.size<=360)return FloatArray(0)
    val b=source.bounds;val o=source.origin
    val corners=(0..7).map {i->Point3((if(i and 1==0)b.min.x else b.max.x)-o.x,(if(i and 2==0)b.min.y else b.max.y)-o.y,(if(i and 4==0)b.min.z else b.max.z)-o.z)}
    val faces=listOf(listOf(0,4,6,2),listOf(1,3,7,5),listOf(0,1,5,4),listOf(2,6,7,3),listOf(0,2,3,1),listOf(4,5,7,6))
    val normals=listOf(Point3(-1.0,0.0),Point3(1.0,0.0),Point3(0.0,-1.0),Point3(0.0,1.0),Point3(z=-1.0),Point3(z=1.0))
    val values=ArrayList<Float>(360)
    for((index,face)in faces.withIndex())for(i in listOf(0,1,2,0,2,3)) {
        val p=corners[face[i]];val n=normals[index]
        values+=listOf(p.x.toFloat(),p.y.toFloat(),p.z.toFloat(),n.x.toFloat(),n.y.toFloat(),n.z.toFloat())
        values+=source.vertices.slice(6..9)
    }
    return values.toFloatArray()
}
