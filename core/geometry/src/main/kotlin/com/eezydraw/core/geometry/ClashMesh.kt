package com.eezydraw.core.geometry

import com.eezydraw.core.model.Point3
import kotlin.math.*
import java.util.PriorityQueue

internal data class CV(val x:Double,val y:Double,val z:Double) {
    operator fun plus(b:CV)=CV(x+b.x,y+b.y,z+b.z)
    operator fun minus(b:CV)=CV(x-b.x,y-b.y,z-b.z)
    operator fun times(n:Double)=CV(x*n,y*n,z*n)
    fun dot(b:CV)=x*b.x+y*b.y+z*b.z
    fun cross(b:CV)=CV(y*b.z-z*b.y,z*b.x-x*b.z,x*b.y-y*b.x)
    fun length()=sqrt(dot(this))
    fun unit()=this*(1/length().coerceAtLeast(1e-30))
    fun point()=Point3(x,y,z)
    fun axis(i:Int)=when(i){0->x;1->y;else->z}
}
internal fun Point3.cv()=CV(x,y,z)
internal data class CB(val low:CV,val high:CV) {
    fun gap(b:CB):Double = sqrt((0..2).sumOf { i -> max(0.0,max(low.axis(i)-b.high.axis(i),b.low.axis(i)-high.axis(i))).pow(2) })
    fun expand(d:Double)=CB(low-CV(d,d,d),high+CV(d,d,d))
    fun overlaps(b:CB)=gap(b)==0.0
    fun centre()=(low+high)*.5
    fun union(b:CB)=CB(CV(min(low.x,b.low.x),min(low.y,b.low.y),min(low.z,b.low.z)),CV(max(high.x,b.high.x),max(high.y,b.high.y),max(high.z,b.high.z)))
}
internal fun bounds(points:List<CV>)=CB(CV(points.minOf {it.x},points.minOf {it.y},points.minOf {it.z}),CV(points.maxOf {it.x},points.maxOf {it.y},points.maxOf {it.z}))
internal data class CT(val a:CV,val b:CV,val c:CV) {
    val box=bounds(listOf(a,b,c));val normal=(b-a).cross(c-a).unit()
    val points get()=listOf(a,b,c)
    val edges get()=listOf(a to b,b to c,c to a)
}
/** Balanced AABB tree, shared by broad-phase solids and narrow-phase triangles. */
internal class ClashTree<T>(items:List<T>,private val box:(T)->CB) {
    internal class Node<T>(val bounds:CB,val items:List<T>,val left:Node<T>?=null,val right:Node<T>?=null)
    val root:Node<T> = build(items)
    private fun build(items:List<T>):Node<T> {
        val bounds=items.map(box).reduce(CB::union)
        if(items.size<=8)return Node(bounds,items)
        val axis=(0..2).maxBy {bounds.high.axis(it)-bounds.low.axis(it)}
        val sorted=items.sortedBy {box(it).centre().axis(axis)};val half=sorted.size/2
        return Node(bounds,emptyList(),build(sorted.take(half)),build(sorted.drop(half)))
    }
    fun query(bounds:CB,visit:(T)->Unit) {
        fun walk(n:Node<T>){if(!bounds.overlaps(n.bounds))return;if(n.left==null)n.items.forEach {if(bounds.overlaps(box(it)))visit(it)}else {walk(n.left);walk(n.right!!)}}
        walk(root)
    }
}
internal class ClashMesh(val triangles:List<CT>,private val cancelled:()->Unit={}) {
    val tree=ClashTree(triangles){it.box}
    val box=tree.root.bounds
    val vertices=triangles.flatMap {it.points}.distinct()
    private fun direction(v:CV):CV? {
        if(v.length()<1e-10)return null
        val n=v.unit();val sign=if(abs(n.x)>1e-8)n.x else if(abs(n.y)>1e-8)n.y else n.z
        return if(sign<0)n * -1.0 else n
    }
    private fun key(v:CV)=listOf(v.x,v.y,v.z).map {(it*1e7).roundToLong()}
    val normals=triangles.mapNotNull {direction(it.normal)}.distinctBy(::key)
    val convex = vertices.size<=256 && normals.size<=130 && triangles.all { t -> vertices.all {(it-t.a).dot(t.normal)<1e-6} }
    val edges by lazy {triangles.flatMap {t->t.edges.mapNotNull {direction(it.second-it.first)}}.distinctBy(::key)}
    /** Exact separating-axis penetration for convex tessellated bodies; null for nonconvex. */
    fun penetration(other:ClashMesh):Double? {
        if(!convex || !other.convex)return null
        val axes=(normals+other.normals+edges.flatMap {a->other.edges.mapNotNull {b->direction(a.cross(b))}}).distinctBy(::key)
        var depth=Double.POSITIVE_INFINITY
        val origin=box.centre()
        for(axis in axes) {
            val a=vertices.map {(it-origin).dot(axis)};val b=other.vertices.map {(it-origin).dot(axis)}
            val overlap=min(a.max()-b.min(),b.max()-a.min())
            if(overlap<0)return overlap
            depth=min(depth,overlap)
        }
        return depth
    }
    fun inside(p:CV):Boolean {
        if(!box.overlaps(CB(p,p)))return false
        if(convex)return triangles.all {(p-it.a).dot(it.normal)<1e-8}
        // Generalized winding includes overlapping component solids and subtracts cavity shells.
        // Unlike parity counting, two overlapping equipment primitives do not become empty.
        var angle=0.0
        for((i,t) in triangles.withIndex()) {
            if(i%256==0)cancelled()
            val a=t.a-p;val b=t.b-p;val c=t.c-p
            val denominator=a.length()*b.length()*c.length()+a.dot(b)*c.length()+b.dot(c)*a.length()+c.dot(a)*b.length()
            angle+=2*atan2(a.dot(b.cross(c)),denominator)
        }
        return abs(angle)>2*Math.PI
    }

    fun distance(p:CV):Double {
        var best=Double.POSITIVE_INFINITY
        fun walk(node:ClashTree.Node<CT>){if(node.bounds.gap(CB(p,p))>best)return
            if(node.left==null)node.items.forEach {best=min(best,(p-closestPoint(p,it)).length())}
            else {walk(node.left);walk(node.right!!)}}
        walk(tree.root);return best
    }
    data class Contact(val distance:Double,val position:CV,val intersections:List<Pair<CV,CV>>)
    fun contact(other:ClashMesh,limit:Double,cancel:()->Unit):Contact {
        data class PairNode(val a:ClashTree.Node<CT>,val b:ClashTree.Node<CT>,val gap:Double)
        val queue=PriorityQueue<PairNode>(compareBy {it.gap});queue+=PairNode(tree.root,other.tree.root,box.gap(other.box))
        var best=limit;var point=(box.centre()+other.box.centre())*.5
        val hits=mutableListOf<Pair<CV,CV>>();var work=0
        fun add(a:ClashTree.Node<CT>,b:ClashTree.Node<CT>){val d=a.bounds.gap(b.bounds);if(d<=best+1e-8)queue+=PairNode(a,b,d)}
        while(queue.isNotEmpty()) {
            if(work++%128==0)cancel()
            val (a,b,gap)=queue.poll();if(gap>best+1e-8)continue
            if(a.left!=null){add(a.left,b);add(a.right!!,b);continue}
            if(b.left!=null){add(a,b.left);add(a,b.right!!);continue}
            for(t in a.items)for(u in b.items) {
                if(t.box.gap(u.box)>best+1e-8)continue
                for((p,q) in t.edges)rayHit(p,q-p,u)?.takeIf {it in -1e-9..1.000000001}?.let {f->if(hits.size<512)hits+=(p+(q-p)*f) to (t.normal+u.normal)}
                for((p,q) in u.edges)rayHit(p,q-p,t)?.takeIf {it in -1e-9..1.000000001}?.let {f->if(hits.size<512)hits+=(p+(q-p)*f) to (t.normal+u.normal)}
                val candidates=t.points.map {it to closestPoint(it,u)}+u.points.map {closestPoint(it,t) to it}+
                    t.edges.flatMap {(p,q)->u.edges.map {(r,s)->closestEdges(p,q,r,s)}}
                for((p,q) in candidates){val distance=(p-q).length();if(distance<best){best=distance;point=(p+q)*.5}}
                if(hits.isNotEmpty()){best=0.0;point=hits.last().first}
            }
        }
        return Contact(best,point,hits)
    }
    /** Interior witnesses distinguish real nonconvex intersections from contact and empty cuts. */
    fun interiorOverlap(other:ClashMesh,hits:List<Pair<CV,CV>>,tolerance:Double,cancel:()->Unit):Pair<Double,CV>? {
        val candidates=mutableListOf(box.centre(),other.box.centre())
        if(hits.isNotEmpty())candidates+=hits.map {it.first}.reduce(CV::plus)*(1.0/hits.size)
        fun samples(points:List<CV>)=(0 until min(64,points.size)).map {points[it*points.size/min(64,points.size)]}
        candidates+=samples(vertices).filter {other.inside(it)};candidates+=samples(other.vertices).filter {inside(it)}
        val span=min((box.high-box.low).length(),(other.box.high-other.box.low).length())
        for((p,n) in hits.take(64))for(d in listOf(max(tolerance*.25,.0000001),max(tolerance,.000001),span*.005,span*.05))if(n.length()>1e-6)candidates+=p-n.unit()*d
        var result:Pair<Double,CV>?=null
        // A boundary vertex strictly inside the other solid proves material overlap, even
        // when a hollow fitting's own wall is thinner than the configured tolerance.
        for((points,host) in listOf(samples(vertices) to other,samples(other.vertices) to this))for(p in points) {
            cancelled();if(!host.inside(p))continue
            val depth=host.distance(p)
            if(depth>1e-8 && (result==null || depth>result.first))result=depth to p
        }
        for(p in candidates){cancel();if(!inside(p)||!other.inside(p))continue
            val depth=2*min(distance(p),other.distance(p))
            if(depth>1e-8 && (result==null || depth>result.first))result=depth to p}
        return result
    }
}
private fun rayHit(p:CV,d:CV,t:CT):Double? {
    val e=t.b-t.a;val f=t.c-t.a;val h=d.cross(f);val det=e.dot(h)
    if(abs(det)<1e-14)return null
    val s=p-t.a;val u=s.dot(h)/det;if(u < -1e-9 || u>1.000000001)return null
    val q=s.cross(e);val v=d.dot(q)/det;if(v < -1e-9 || u+v>1.000000001)return null
    return f.dot(q)/det
}
private fun closestPoint(p:CV,t:CT):CV {
    val ab=t.b-t.a;val ac=t.c-t.a;val ap=p-t.a;val d1=ab.dot(ap);val d2=ac.dot(ap)
    if(d1<=0 && d2<=0)return t.a
    val bp=p-t.b;val d3=ab.dot(bp);val d4=ac.dot(bp);if(d3>=0 && d4<=d3)return t.b
    val vc=d1*d4-d3*d2;if(vc<=0 && d1>=0 && d3<=0)return t.a+ab*(d1/(d1-d3))
    val cp=p-t.c;val d5=ab.dot(cp);val d6=ac.dot(cp);if(d6>=0 && d5<=d6)return t.c
    val vb=d5*d2-d1*d6;if(vb<=0 && d2>=0 && d6<=0)return t.a+ac*(d2/(d2-d6))
    val va=d3*d6-d5*d4;if(va<=0 && d4-d3>=0 && d5-d6>=0)return t.b+(t.c-t.b)*((d4-d3)/((d4-d3)+(d5-d6)))
    val inverse=1/(va+vb+vc);return t.a+ab*(vb*inverse)+ac*(vc*inverse)
}
private fun closestEdges(p:CV,q:CV,r:CV,s:CV):Pair<CV,CV> {
    val d=q-p;val e=s-r;val v=p-r;val a=d.dot(d);val b=d.dot(e);val c=e.dot(e);val f=e.dot(v)
    if(a<1e-25 || c<1e-25)return p to r
    val den=a*c-b*b
    var t=if(abs(den)>1e-25)((b*f-d.dot(v)*c)/den).coerceIn(0.0,1.0)else 0.0
    var u=(b*t+f)/c
    if(u<0){u=0.0;t=(-d.dot(v)/a).coerceIn(0.0,1.0)}else if(u>1){u=1.0;t=((b-d.dot(v))/a).coerceIn(0.0,1.0)}
    return (p+d*t) to (r+e*u)
}
