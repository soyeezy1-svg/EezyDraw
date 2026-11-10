package com.eezydraw.core.model

import kotlinx.serialization.Serializable
import kotlin.math.*

const val WALL_THICKNESS_OVERRIDE = "eezydraw.dimensionWallThickness"
const val WALL_HEIGHT_OVERRIDE = "eezydraw.dimensionWallHeight"

@Serializable enum class FixedEnd { START, END, CENTER }
@Serializable enum class DimensionProperty { LENGTH, THICKNESS, HEIGHT, EDGE }
@Serializable enum class WallLengthFace { CENTERLINE, INSIDE, OUTSIDE }
@Serializable enum class DistanceReference { NEAR_EDGE, CENTER, FAR_EDGE }

/** A semantic edge survives resizing; picked local points alone cannot express that intent. */
@Serializable
data class DimensionBinding(val elementId: ElementId, val property: DimensionProperty = DimensionProperty.LENGTH,
    val edgeIndex: Int = 0, val ringIndex: Int? = null, val wallFace: WallLengthFace = WallLengthFace.CENTERLINE) {
    init { require(edgeIndex >= 0 && (ringIndex == null || ringIndex >= 0)) }
}

@Serializable
data class WallDistance(val wallId: ElementId, val side: WallFaceSide, val distance: Double,
    val reference: DistanceReference = DistanceReference.NEAR_EDGE, val roomSide: RoomSideRef? = null) {
    init { require(distance.isFinite() && distance >= 0) }
}

/** Editable vertices, in host-local coordinates. */
fun dimensionVertices(element: Element, ringIndex: Int? = null): List<Point3> = when (val body = element.body) {
    is ElementBody.Wall -> listOf(body.start, body.end).map { Point3(it.x, it.y) }
    is ElementBody.Room -> (if (ringIndex == null) body.boundary.outer else body.boundary.holes.getOrNull(ringIndex).orEmpty()).map { Point3(it.x, it.y) }
    is ElementBody.DrawingShape -> when (val shape = body.shape) {
        is Shape.Line -> listOf(shape.start, shape.end)
        is Shape.Polyline -> shape.points
        is Shape.Polygon -> (if (ringIndex == null) shape.boundary.outer else shape.boundary.holes.getOrNull(ringIndex).orEmpty()).map { Point3(it.x, it.y) }
        else -> emptyList()
    }
    else -> emptyList()
}

fun dimensionEndpoints(document: ProjectDocument, binding: DimensionBinding): Pair<Point3, Point3>? {
    val host = document.elements.find { it.id == binding.elementId } ?: return null
    val wall = host.body as? ElementBody.Wall
    if (wall != null) {
        val f = wallFrames(document.copy(elements = listOf(host))).firstOrNull()
        if (f != null) {
            if (binding.property == DimensionProperty.THICKNESS) {
                val a=f.world(f.length/2,0.0,0.0)
                return a to Point3(a.x-f.normal.x*f.thickness,a.y-f.normal.y*f.thickness,a.z)
            }
            if (binding.property == DimensionProperty.HEIGHT) return f.world(f.length / 2, 0.0, 0.0) to f.world(f.length / 2, 0.0, f.height)
            val insideSide = document.elements.asSequence().mapNotNull { it.body as? ElementBody.Room }
                .flatMap { it.wallFaces.asSequence() }.firstOrNull { it.wallId == host.id }?.side ?: WallSide.LEFT
            val insideLeft = insideSide == WallSide.LEFT
            val depth = when (binding.wallFace) {
                WallLengthFace.CENTERLINE -> f.thickness / 2
                WallLengthFace.INSIDE -> if (insideLeft) 0.0 else f.thickness
                WallLengthFace.OUTSIDE -> if (insideLeft) f.thickness else 0.0
            }
            // Mitred finish faces use their own finite end points, never a neighbour's edges.
            val profile = wall.joinProfile?.outer.orEmpty().map { f.coordinates(measurementToWorld(host.transform, Point3(it.x, it.y))) }
            val face = profile.filter { abs(it.y - depth) < 1e-7 }
            val from = if (binding.wallFace != WallLengthFace.CENTERLINE && face.size >= 2) face.minOf { it.x } else 0.0
            val to = if (binding.wallFace != WallLengthFace.CENTERLINE && face.size >= 2) face.maxOf { it.x } else f.length
            return f.world(from, depth, 0.0) to f.world(to, depth, 0.0)
        }
        // Tilted walls retain physical dimensions even though XY wall-distance placement
        // only offers upright finish planes.
        val dx=wall.end.x-wall.start.x;val dy=wall.end.y-wall.start.y;val length=hypot(dx,dy)
        val nx= -dy/length;val ny=dx/length
        val offset=when(wall.reference){WallReference.CENTERLINE->wall.thickness/2;WallReference.LEFT_FINISH_FACE->0.0;WallReference.RIGHT_FINISH_FACE->wall.thickness}
        val a=measurementToWorld(host.transform,Point3((wall.start.x+wall.end.x)/2+nx*offset,(wall.start.y+wall.end.y)/2+ny*offset))
        if(binding.property==DimensionProperty.HEIGHT) {
            val b=measurementToWorld(host.transform,Point3((wall.start.x+wall.end.x)/2+nx*offset,(wall.start.y+wall.end.y)/2+ny*offset,wall.height))
            return a to b
        }
        if(binding.property==DimensionProperty.THICKNESS) {
            val inner=measurementToWorld(host.transform,Point3((wall.start.x+wall.end.x)/2+nx*(offset-wall.thickness),(wall.start.y+wall.end.y)/2+ny*(offset-wall.thickness)))
            val start=measurementToWorld(host.transform,Point3(wall.start.x,wall.start.y));val end=measurementToWorld(host.transform,Point3(wall.end.x,wall.end.y))
            val span=pointDistance(start,end);val u=Point3((end.x-start.x)/span,(end.y-start.y)/span,(end.z-start.z)/span)
            val dot=(inner.x-a.x)*u.x+(inner.y-a.y)*u.y+(inner.z-a.z)*u.z
            return a to Point3(inner.x-u.x*dot,inner.y-u.y*dot,inner.z-u.z*dot)
        }
        val insideLeft=document.elements.asSequence().mapNotNull {it.body as? ElementBody.Room}.flatMap {it.wallFaces.asSequence()}
            .firstOrNull {it.wallId==host.id}?.side!=WallSide.RIGHT
        val faceOffset=when(binding.wallFace) {
            WallLengthFace.CENTERLINE->offset-wall.thickness/2
            WallLengthFace.INSIDE->if(insideLeft)offset else offset-wall.thickness
            WallLengthFace.OUTSIDE->if(insideLeft)offset-wall.thickness else offset
        }
        val profile=wall.joinProfile?.outer.orEmpty().filter {abs((it.x-wall.start.x)*nx+(it.y-wall.start.y)*ny-faceOffset)<1e-7}
        val facePositions=profile.map {((it.x-wall.start.x)*dx+(it.y-wall.start.y)*dy)/length}
        val from=if(binding.wallFace!=WallLengthFace.CENTERLINE && facePositions.size>=2)facePositions.min()else 0.0
        val to=if(binding.wallFace!=WallLengthFace.CENTERLINE && facePositions.size>=2)facePositions.max()else length
        fun point(s:Double)=measurementToWorld(host.transform,Point3(wall.start.x+dx/length*s+nx*faceOffset,wall.start.y+dy/length*s+ny*faceOffset))
        return point(from) to point(to)
    } else if(binding.property in setOf(DimensionProperty.THICKNESS,DimensionProperty.HEIGHT))return null
    val vertices = dimensionVertices(host, binding.ringIndex)
    if (vertices.size < 2 || binding.edgeIndex !in vertices.indices) return null
    val a = vertices[binding.edgeIndex]
    val b = vertices[(binding.edgeIndex + 1) % vertices.size]
    return measurementToWorld(host.transform, a) to measurementToWorld(host.transform, b)
}

fun pointDistance(a: Point3, b: Point3): Double = hypot(hypot(b.x - a.x, b.y - a.y), b.z - a.z)
