package com.eezydraw.core.editing

import com.eezydraw.core.model.*
import kotlin.math.*

/** Same canonical endpoint is used by the ghost, crosshair and final construction. */
fun endpointAtLength(start: Point3, aim: Point3, length: Double): Point3 {
    require(length.isFinite() && length > 1e-9) { "Enter a positive length." }
    val distance = pointDistance(start, aim)
    val direction = if (distance > 1e-9) Point3((aim.x-start.x)/distance,(aim.y-start.y)/distance,(aim.z-start.z)/distance) else Point3(1.0)
    return Point3(start.x+direction.x*length,start.y+direction.y*length,start.z+direction.z*length)
}

/** Walls retain their first base elevation; ordinary lines can follow an elevated 3D aim. */
fun drawingEndpoint(start:Point3,aim:Point3,length:Double?,wall:Boolean):Point3 {
    val target=if(wall)aim.copy(z=start.z)else aim
    return if(length==null)target else endpointAtLength(start,target,length)
}

fun fixedEndpoints(start: Point3, end: Point3, length: Double, fixed: FixedEnd): Pair<Point3, Point3> {
    val next = endpointAtLength(start,end,length)
    val delta = Point3(next.x-end.x,next.y-end.y,next.z-end.z)
    fun shift(p:Point3, factor:Double)=Point3(p.x+delta.x*factor,p.y+delta.y*factor,p.z+delta.z*factor)
    return when(fixed) { FixedEnd.START -> start to next; FixedEnd.END -> shift(start,-1.0) to end; FixedEnd.CENTER -> shift(start,-.5) to shift(end,.5) }
}

fun createBoundDimension(document:ProjectDocument,binding:DimensionBinding,label:Point3?=null):SketchGeometry {
    val (a,b)=requireNotNull(dimensionEndpoints(document,binding)) {"This edge is no longer available."}
    val middle=measurementMidpoint(a,b)
    val sketch=createMeasurement(document,a,b,label ?: Point3(middle.x,middle.y+.25,middle.z),binding.elementId,binding.elementId,
        name=when(binding.property){DimensionProperty.THICKNESS->"Thickness";DimensionProperty.HEIGHT->"Height";else->when(binding.wallFace){WallLengthFace.CENTERLINE->"Length";WallLengthFace.INSIDE->"Inside face";WallLengthFace.OUTSIDE->"Outside face"}},showCoordinates=false,showHeightAboveFloor=false)
    val dimension=sketch.shape as Shape.Dimension
    return sketch.copy(shape=dimension.copy(measurement=dimension.measurement!!.copy(binding=binding)))
}

/** Infer semantic edges when both anchors are on the same editable edge. */
fun measurementBinding(document:ProjectDocument,element:Element):DimensionBinding? {
    val shape=measurementShape(element) ?: return null
    val data=shape.measurement!!
    data.binding?.let {return it}
    val first=data.anchors[0];val last=data.anchors[1]
    if(first.elementId==null || first.elementId!=last.elementId)return null
    val host=document.elements.find {it.id==first.elementId} ?: return null
    val vertices=dimensionVertices(host)
    val i=first.vertexIndex;val j=last.vertexIndex
    return if(i!=null && j!=null && (i+1)%vertices.size==j)DimensionBinding(host.id,if(host.body is ElementBody.Room)DimensionProperty.EDGE else DimensionProperty.LENGTH,i)
        else if(i!=null && j!=null && (j+1)%vertices.size==i)DimensionBinding(host.id,if(host.body is ElementBody.Room)DimensionProperty.EDGE else DimensionProperty.LENGTH,j) else null
}

/** A dimension edit changes geometry and its annotation in one sparse, validated command. */
object DimensionEditing {
    fun edit(document:ProjectDocument,id:ElementId,value:Double,fixed:FixedEnd):EditCommand {
        require(value.isFinite() && value>1e-9) {"Enter a positive dimension."}
        val annotation=requireNotNull(document.elements.find {it.id==id}) {"The dimension no longer exists."}
        val shape=(annotation.body as? ElementBody.DrawingShape)?.shape as? Shape.Dimension ?: error("Select a dimension.")
        require(annotation.lockMode==LockMode.NONE && document.layers.none {it.id==annotation.layerId && it.locked}) {"Dimension is locked. Unlock to edit."}
        var after=document
        val binding=measurementBinding(document,annotation)
        if(binding!=null) {
            after=editBinding(document,binding,value,fixed)
            if(document.elements.any {it.id==binding.elementId && it.body is ElementBody.Wall}) {
                var resolved=false
                repeat(16) {
                    if(!resolved) {
                        after=RoomWalls.reconcile(document,after)
                        val ends=requireNotNull(dimensionEndpoints(after,binding)) {"The edited wall edge is no longer available."}
                        val error=value-pointDistance(ends.first,ends.second)
                        if(abs(error)<=max(1e-8,value*1e-9))resolved=true
                        else after=editBinding(after,binding,value,fixed)
                    }
                }
                require(resolved) {"This length would collapse a wall junction. Choose another dimension."}
            }
            // Store inferred binding so later edge changes continue to resolve semantically.
            val updated=annotation.copy(body=ElementBody.DrawingShape(shape.copy(measurement=shape.measurement!!.copy(binding=binding))))
            after=after.copy(elements=after.elements.map {if(it.id==id)updated else it})
        } else if(shape.measurement!=null) {
            val data=shape.measurement!!
            val summary=resolveMeasurement(document,annotation)!!
            val (a,b)=fixedEndpoints(summary.start,summary.end,value,fixed)
            val changes=linkedMapOf<ElementId,Element>()
            val desired=listOf(a,b)
            val original=listOf(summary.start,summary.end)
            data.anchors.forEachIndexed {index,anchor->
                val host=anchor.elementId?.let {key->document.elements.find {it.id==key}}
                if(host!=null && pointDistance(desired[index],original[index])>1e-9) {
                    require(data.anchors.count {it.elementId==host.id}==1) {"Pick the ends of an editable edge to resize this object."}
                    val delta=Point3(desired[index].x-original[index].x,desired[index].y-original[index].y,desired[index].z-original[index].z)
                    changes[host.id]=host.copy(transform=host.transform.copy(translation=Point3(host.transform.translation.x+delta.x,host.transform.translation.y+delta.y,host.transform.translation.z+delta.z)))
                }
            }
            after=document.copy(elements=document.elements.map {changes[it.id] ?: it})
            val geometry=createMeasurement(after,a,b,summary.labelPoint,data.anchors[0].elementId,data.anchors[1].elementId,
                data.name,data.showCoordinates,data.showHeightAboveFloor,data.floorLevelId,
                data.anchors.any {it.projectFixed})
            after=after.copy(elements=after.elements.map {if(it.id==id)it.copy(body=ElementBody.DrawingShape(geometry.shape),transform=geometry.transform)else it})
        } else {
            val world=shape.anchors.map {measurementToWorld(annotation.transform,it)}
            val updated=if(shape.kind==DimensionKind.ANGULAR) {
                require(world.size>=3) {"This angular dimension needs two complete arms."}
                require(value<PI) {"Use an angle between zero and 180 degrees."}
                val center=world[0];val arm=world[1];val other=world[2]
                val radius=pointDistance(center,other);val armLength=pointDistance(center,arm)
                require(radius>1e-9 && armLength>1e-9) {"The angle arms must have a length."}
                val u=Point3((arm.x-center.x)/armLength,(arm.y-center.y)/armLength,(arm.z-center.z)/armLength)
                val v=Point3((other.x-center.x)/radius,(other.y-center.y)/radius,(other.z-center.z)/radius)
                val dot=u.x*v.x+u.y*v.y+u.z*v.z
                val perpendicular=Point3(v.x-u.x*dot,v.y-u.y*dot,v.z-u.z*dot)
                val magnitude=pointDistance(Point3(),perpendicular)
                require(magnitude>1e-9) {"The angle arms must define a plane."}
                val c=cos(value)*radius;val t=sin(value)*radius/magnitude
                world.toMutableList().apply {this[2]=Point3(center.x+u.x*c+perpendicular.x*t,center.y+u.y*c+perpendicular.y*t,center.z+u.z*c+perpendicular.z*t)}
            } else fixedEndpoints(world[0],world[1],value,fixed).let {listOf(it.first,it.second)}
            after=document.copy(elements=document.elements.map {if(it.id==id)it.copy(body=ElementBody.DrawingShape(shape.copy(anchors=updated.map {p->measurementToLocal(it.transform,p)})))else it})
        }
        after=after.copy(project=after.project.copy(settings=after.project.settings.copy(dimensionFixedEnd=fixed)))
        return EditCommands.between(document,after,"Edit dimension")
    }

    fun editBinding(document:ProjectDocument,binding:DimensionBinding,value:Double,fixed:FixedEnd):ProjectDocument {
        require(value.isFinite() && value>1e-9) {"Enter a positive dimension."}
        val host=requireNotNull(document.elements.find {it.id==binding.elementId})
        // Generated walls are owned by room boundaries: edit the actual owning edge.
        val owner=document.elements.firstOrNull {e->(e.body as? ElementBody.Room)?.wallFaces?.any {it.wallId==host.id}==true}
        if(host.body is ElementBody.Wall && owner!=null) {
            val room=owner.body as ElementBody.Room
            val face=room.wallFaces.first {it.wallId==host.id}
            if(binding.property in setOf(DimensionProperty.LENGTH,DimensionProperty.EDGE)) {
                val endpoints=dimensionEndpoints(document,binding)!!
                val length=pointDistance(endpoints.first,endpoints.second)
                val edgeBinding=DimensionBinding(owner.id,DimensionProperty.EDGE,face.edgeIndex,face.ringIndex)
                val edge=dimensionEndpoints(document,edgeBinding)!!
                val target=pointDistance(edge.first,edge.second)+value-length
                val wall=host.body as ElementBody.Wall
                val roomDirection=Point3(edge.second.x-edge.first.x,edge.second.y-edge.first.y)
                val wallDirection=measurementToWorld(host.transform,Point3(wall.end.x,wall.end.y)).let {end->val start=measurementToWorld(host.transform,Point3(wall.start.x,wall.start.y));Point3(end.x-start.x,end.y-start.y)}
                val reversed=roomDirection.x*wallDirection.x+roomDirection.y*wallDirection.y<0
                val choice=if(!reversed || fixed==FixedEnd.CENTER)fixed else if(fixed==FixedEnd.START)FixedEnd.END else FixedEnd.START
                return editBinding(document,edgeBinding,target,choice)
            }
        }
        val wall=host.body as? ElementBody.Wall
        val changed=if(wall!=null && binding.property in setOf(DimensionProperty.THICKNESS,DimensionProperty.HEIGHT)) {
            val ends=dimensionEndpoints(document,binding)!!
            val shown=pointDistance(ends.first,ends.second)
            val body=if(binding.property==DimensionProperty.HEIGHT)wall.copy(height=wall.height*value/shown,joinProfile=null)
                else wall.copy(thickness=wall.thickness*value/shown,joinProfile=null)
            val overrides=if(GENERATED_ROOM_WALL !in host.properties)host.properties else host.properties+
                (if(binding.property==DimensionProperty.HEIGHT)WALL_HEIGHT_OVERRIDE to ParameterValue.Number(body.height,Quantity.LENGTH)
                 else WALL_THICKNESS_OVERRIDE to ParameterValue.Number(body.thickness,Quantity.LENGTH))
            host.copy(body=body,properties=overrides)
        } else {
            val vertices=dimensionVertices(host,binding.ringIndex).toMutableList()
            require(vertices.size>=2 && binding.edgeIndex in vertices.indices) {"The edge is no longer available."}
            val i=binding.edgeIndex;val j=(i+1)%vertices.size
            val start=measurementToWorld(host.transform,vertices[i]);val end=measurementToWorld(host.transform,vertices[j])
            val shown=dimensionEndpoints(document,binding)!!
            val target=pointDistance(start,end)+value-pointDistance(shown.first,shown.second)
            val (a,b)=fixedEndpoints(start,end,target,fixed)
            vertices[i]=measurementToLocal(host.transform,a);vertices[j]=measurementToLocal(host.transform,b)
            replaceVertices(host,vertices,binding.ringIndex)
        }
        return document.copy(elements=document.elements.map {if(it.id==host.id)changed else it})
    }
}

private fun replaceVertices(element:Element,points:List<Point3>,ringIndex:Int?):Element {
    fun polygon(old:Polygon2):Polygon2 {
        val ring=points.map {Point2(it.x,it.y)}
        require(ring.indices.all {pointDistance(points[it],points[(it+1)%points.size])>1e-9}) {"The edit would collapse an edge."}
        return if(ringIndex==null)old.copy(outer=ring)else old.copy(holes=old.holes.mapIndexed {i,p->if(i==ringIndex)ring else p})
    }
    return element.copy(body=when(val body=element.body) {
        is ElementBody.Wall->body.copy(start=Point2(points[0].x,points[0].y),end=Point2(points[1].x,points[1].y),joinProfile=null)
        is ElementBody.Room->body.copy(boundary=polygon(body.boundary))
        is ElementBody.DrawingShape->ElementBody.DrawingShape(when(val shape=body.shape){
            is Shape.Line->shape.copy(start=points[0],end=points[1])
            is Shape.Polyline->shape.copy(points=points)
            is Shape.Polygon->shape.copy(boundary=polygon(shape.boundary))
            else->error("This geometry has no editable edge.")})
        else->error("This geometry has no editable edge.")
    })
}

fun dimensionWall(document:ProjectDocument,id:ElementId,faces:Set<WallLengthFace> = setOf(WallLengthFace.CENTERLINE),
    thickness:Boolean=true,height:Boolean=true):EditCommand {
    val host=requireNotNull(document.elements.find {it.id==id && it.body is ElementBody.Wall}) {"Pick a wall."}
    val layer=document.layers.firstOrNull {it.id==host.layerId && !it.locked && it.visible}
        ?: document.layers.firstOrNull {it.visible && !it.locked} ?: error("Choose an unlocked visible dimension layer.")
    val bindings=faces.map {DimensionBinding(id,wallFace=it)}+
        listOfNotNull(DimensionBinding(id,DimensionProperty.THICKNESS).takeIf {thickness},DimensionBinding(id,DimensionProperty.HEIGHT).takeIf {height})
    val existing=document.elements.mapNotNull {measurementShape(it)?.measurement?.binding}.toSet()
    val added=bindings.filter {it !in existing}.mapIndexed {index,binding->
        val ends=dimensionEndpoints(document,binding)!!;val middle=measurementMidpoint(ends.first,ends.second)
        val dx=ends.second.x-ends.first.x;val dy=ends.second.y-ends.first.y;val length=hypot(dx,dy)
        val offset=.25+index*.22
        val label=if(length>1e-9)Point3(middle.x-dy/length*offset,middle.y+dx/length*offset,middle.z)else Point3(middle.x+offset,middle.y,middle.z)
        val geometry=createBoundDimension(document,binding,label)
        Element(ElementId(java.util.UUID.randomUUID().toString()),document.project.id,layer.id,ElementBody.DrawingShape(geometry.shape),host.levelId,
            name=(geometry.shape as Shape.Dimension).measurement!!.name,transform=geometry.transform)
    }
    val keep=document.elements.filter {e->val binding=measurementShape(e)?.measurement?.binding;binding?.elementId!=id || binding in bindings}
    return EditCommands.between(document,document.copy(elements=keep+added),"Dimension this wall")
}
