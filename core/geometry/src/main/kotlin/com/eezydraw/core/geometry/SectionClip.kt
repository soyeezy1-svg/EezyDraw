package com.eezydraw.core.geometry

import com.eezydraw.core.model.*

/** Presentation clipping in chunk-local doubles. Source elements, topology and dimensions stay intact. */
object SectionClip {
    fun contains(box:Bounds3,p:Point3)=p.x in box.min.x..box.max.x && p.y in box.min.y..box.max.y && p.z in box.min.z..box.max.z
    fun segment(a:Point3,b:Point3,box:Bounds3):Pair<Point3,Point3>? {
        var lo=0.0;var hi=1.0
        val av=doubleArrayOf(a.x,a.y,a.z);val bv=doubleArrayOf(b.x,b.y,b.z)
        val min=doubleArrayOf(box.min.x,box.min.y,box.min.z);val max=doubleArrayOf(box.max.x,box.max.y,box.max.z)
        for(i in 0..2){val d=bv[i]-av[i];if(kotlin.math.abs(d)<1e-15){if(av[i]<min[i] || av[i]>max[i])return null}else {
            val x=(min[i]-av[i])/d;val y=(max[i]-av[i])/d;lo=maxOf(lo,minOf(x,y));hi=minOf(hi,maxOf(x,y));if(lo>hi)return null
        }}
        fun point(t:Double)=Point3(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t,a.z+(b.z-a.z)*t)
        return point(lo) to point(hi)
    }
    fun drawing(scene:DrawingScene,box:Bounds3?):DrawingScene {
        if(box==null)return scene
        val edges=scene.segments.mapNotNull {s->segment(s.a,s.b,box)?.let {(a,b)->s.copy(a=a,b=b)}}
        val chunks=scene.chunks.mapNotNull {chunk->
            val values=ArrayList<Float>();val v=chunk.vertices
            for(i in v.indices step 14){val a=Point3(chunk.origin.x+v[i],chunk.origin.y+v[i+1],chunk.origin.z+v[i+2]);val b=Point3(chunk.origin.x+v[i+7],chunk.origin.y+v[i+8],chunk.origin.z+v[i+9])
                val pair=segment(a,b,box) ?: continue
                for(p in listOf(pair.first,pair.second)){values+=(p.x-chunk.origin.x).toFloat();values+=(p.y-chunk.origin.y).toFloat();values+=(p.z-chunk.origin.z).toFloat();for(c in 3..6)values+=v[i+c]}
            }
            if(values.isEmpty())null else chunk.copy(vertices=values.toFloatArray(),bounds=intersection(chunk.bounds,box)!!)
        }
        return scene.copy(segments=edges,chunks=chunks,bounds=intersection(scene.bounds,box) ?: Bounds3(box.min,box.min),levelBounds=emptyMap())
    }
    fun surfaces(scene:SurfaceScene,box:Bounds3?):SurfaceScene {
        if(box==null)return scene
        val chunks=scene.chunks.mapNotNull {chunk->
            if(intersection(chunk.bounds,box)==null)return@mapNotNull null
            val values=ArrayList<Float>();val v=chunk.vertices
            val min=doubleArrayOf(box.min.x-chunk.origin.x,box.min.y-chunk.origin.y,box.min.z-chunk.origin.z)
            val max=doubleArrayOf(box.max.x-chunk.origin.x,box.max.y-chunk.origin.y,box.max.z-chunk.origin.z)
            for(i in v.indices step 30){var polygon=List(3){n->DoubleArray(10){c->v[i+n*10+c].toDouble()}}
                for(axis in 0..2)for(side in 0..1){if(polygon.isEmpty())break;val limit=if(side==0)min[axis]else max[axis]
                    fun inside(p:DoubleArray)=if(side==0)p[axis]>=limit else p[axis]<=limit
                    val next=ArrayList<DoubleArray>();var a=polygon.last()
                    for(b in polygon){val ai=inside(a);val bi=inside(b);if(ai!=bi){val t=(limit-a[axis])/(b[axis]-a[axis]);next+=DoubleArray(10){c->a[c]+(b[c]-a[c])*t}};if(bi)next+=b;a=b};polygon=next
                }
                for(n in 1 until polygon.lastIndex)for(p in listOf(polygon.first(),polygon[n],polygon[n+1]))for(c in p)values+=c.toFloat()
            }
            if(values.isEmpty())null else chunk.copy(vertices=values.toFloatArray(),bounds=intersection(chunk.bounds,box)!!)
        }
        return SurfaceScene(chunks,intersection(scene.bounds,box) ?: Bounds3(box.min,box.min))
    }
    private fun intersection(a:Bounds3,b:Bounds3):Bounds3? {
        val min=Point3(maxOf(a.min.x,b.min.x),maxOf(a.min.y,b.min.y),maxOf(a.min.z,b.min.z));val max=Point3(minOf(a.max.x,b.max.x),minOf(a.max.y,b.max.y),minOf(a.max.z,b.max.z))
        return if(min.x>max.x || min.y>max.y || min.z>max.z)null else Bounds3(min,max)
    }
}
