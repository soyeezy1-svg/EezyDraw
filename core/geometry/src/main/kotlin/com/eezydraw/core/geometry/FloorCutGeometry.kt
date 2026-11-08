package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import kotlin.math.abs

/** Reuses the wall depth-band boolean/triangulation engine in a floor XY / downward-Z basis. */
fun floorCutMesh(document:ProjectDocument,slab:Element):Shape.Mesh? {
    val source=slab.body as ElementBody.Slab
    val scale=slab.transform.scale
    fun physical(ring:List<Point2>)=ring.map {Point2(it.x*abs(scale.x),it.y*abs(scale.y))}
    val body=source.copy(boundary=Polygon2(physical(source.boundary.outer),source.boundary.holes.map(::physical)),thickness=source.thickness*abs(scale.z))
    val bounds=body.boundary.outer
    val virtual=slab.copy(transform=Transform3(),body=ElementBody.Wall(Point2(0.0,0.0),Point2(1.0,0.0),body.thickness,1.0))
    val frame=WallFrame(virtual,null,Point3(),Point3(x=1.0),Point3(y=1.0),bounds.maxOf {it.x}.coerceAtLeast(1.0),body.thickness,bounds.maxOf {it.y}.coerceAtLeast(1.0))
    val cuts=floorServiceCuts(document,slab).map {Floor->WallCut(slab.id,Floor.sourceId,null,WallServiceMode.CHASE,Floor.outline,0.0,Floor.depth.coerceAtMost(body.thickness),body.thickness,"Floor cut",Floor.depth)}
    return wallCutMesh(frame,cuts,boundary=body.boundary)?.let {it.copy(vertices=it.vertices.map {p->Point3(p.x/abs(scale.x),p.z/abs(scale.y),p.y/abs(scale.z))},triangles=it.triangles.chunked(3).flatMap {t->listOf(t[0],t[2],t[1])})}
}
fun floorRevealed(document:ProjectDocument,element:Element):Boolean = document.project.settings.viewMode in setOf(ViewMode.XRAY,ViewMode.SECTION) ||
    document.elements.filter {it.body is ElementBody.Slab && it.levelId==element.levelId}.any {it.objectDisplay in setOf(ObjectDisplay.XRAY,ObjectDisplay.HIDDEN) || document.layers.find {l->l.id==it.layerId}?.visible==false}
