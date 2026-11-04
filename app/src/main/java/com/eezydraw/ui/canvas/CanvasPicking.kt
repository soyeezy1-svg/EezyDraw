package com.eezydraw.ui.canvas

import com.eezydraw.core.geometry.*
import com.eezydraw.core.model.*
import kotlin.math.*

/** Project-space measurement picks use scene geometry, never screen pixels as lengths. */
internal data class CanvasPick(val point:Point3,val elementId:ElementId?=null)
internal fun pickMeasurementPoint(scene: DrawingScene, surfaces: SurfaceScene?, frame: CanvasFrame,
    x: Double, y: Double, width: Double, height: Double, tolerance: Double): Point3? =
    pickSceneHit(scene,surfaces,frame,x,y,width,height,tolerance)?.point

internal fun pickSceneHit(scene: DrawingScene, surfaces: SurfaceScene?, frame: CanvasFrame,
    x: Double, y: Double, width: Double, height: Double, tolerance: Double): CanvasPick? {
    if (!frame.is3D) return frame.viewport.screenToWorld(x,y,width,height).let { CanvasPick(Point3(it.x,it.y,frame.floorElevation)) }
    val ray = viewRay(frame.orbit,x,y,width,height)
    var nearest = Double.POSITIVE_INFINITY
    var hit: Point3? = null
    var elementId:ElementId?=null
    for (chunk in surfaces?.chunks.orEmpty()) {
        if (frame.levelId != null && chunk.levelId != null && chunk.levelId != frame.levelId) continue
        val vertices = chunk.vertices
        for (offset in vertices.indices step 30) {
            if (offset + 29 >= vertices.size) break
            fun point(at: Int) = PickVector(chunk.origin.x+vertices[at],chunk.origin.y+vertices[at+1],chunk.origin.z+vertices[at+2])
            val a = point(offset); val b = point(offset+10); val c = point(offset+20)
            val e1 = b-a; val e2 = c-a; val p = ray.second.cross(e2)
            val determinant = e1.dot(p)
            if (abs(determinant) < 1e-12) continue
            val inv = 1.0/determinant; val tvec = ray.first-a; val u = tvec.dot(p)*inv
            if (u < -1e-9 || u > 1+1e-9) continue
            val q = tvec.cross(e1); val v = ray.second.dot(q)*inv
            if (v < -1e-9 || u+v > 1+1e-9) continue
            val distance = e2.dot(q)*inv
            if (distance >= 0.0 && distance < nearest) {
                nearest = distance
                hit = (ray.first+ray.second*distance).point()
                elementId=ElementId(chunk.elementId)
            }
        }
    }
    var edgeDistance = tolerance
    for (edge in scene.segments) {
        if (frame.levelId != null && edge.levelId != null && edge.levelId != frame.levelId) continue
        val a = frame.orbit.project(edge.a,width,height) ?: continue
        val b = frame.orbit.project(edge.b,width,height) ?: continue
        val dx = b.x-a.x; val dy = b.y-a.y; val length = dx*dx+dy*dy
        val t = if (length <= 1e-12) 0.0 else (((x-a.x)*dx+(y-a.y)*dy)/length).coerceIn(0.0,1.0)
        val distance = hypot(x-a.x-t*dx,y-a.y-t*dy)
        if (distance > edgeDistance) continue
        // Recover the point on the 3D edge closest to the viewing ray (perspective-safe).
        val origin = PickVector(edge.a.x,edge.a.y,edge.a.z)
        val direction = PickVector(edge.b.x-edge.a.x,edge.b.y-edge.a.y,edge.b.z-edge.a.z)
        val offset = origin-ray.first
        val aa = direction.dot(direction); val ab = direction.dot(ray.second)
        val denominator = aa-ab*ab
        val fraction = if (denominator < 1e-12) t else ((ab*offset.dot(ray.second)-direction.dot(offset))/denominator).coerceIn(0.0,1.0)
        val point = origin+direction*fraction
        val depth = (point-ray.first).dot(ray.second)
        if (depth >= 0 && (hit == null || depth <= nearest+max(.005,frame.orbit.distance*.002))) {
            edgeDistance = distance
            hit = point.point()
            elementId=ElementId(edge.elementId)
        }
    }
    val point=hit ?: frame.orbit.rayToPlaneZ(x,y,width,height,frame.floorElevation)
        ?: run {
            val target = PickVector(frame.orbit.target.x,frame.orbit.target.y,frame.orbit.target.z)
            val distance = (target-ray.first).dot(ray.second)
            (ray.first+ray.second*max(0.0,distance)).point()
        }
    return CanvasPick(point,elementId)
}

private data class PickVector(val x: Double,val y: Double,val z: Double) {
    operator fun plus(b: PickVector) = PickVector(x+b.x,y+b.y,z+b.z)
    operator fun minus(b: PickVector) = PickVector(x-b.x,y-b.y,z-b.z)
    operator fun times(s: Double) = PickVector(x*s,y*s,z*s)
    fun dot(b: PickVector) = x*b.x+y*b.y+z*b.z
    fun cross(b: PickVector) = PickVector(y*b.z-z*b.y,z*b.x-x*b.z,x*b.y-y*b.x)
    fun point() = Point3(x,y,z)
}

private fun viewRay(camera: OrbitCamera,x: Double,y: Double,width: Double,height: Double): Pair<PickVector,PickVector> {
    val cy = cos(camera.yaw); val sy = sin(camera.yaw); val cp = cos(camera.pitch); val sp = sin(camera.pitch)
    val right = PickVector(-sy,cy,0.0); val up = PickVector(-sp*cy,-sp*sy,cp)
    val outward = PickVector(cp*cy,cp*sy,sp)
    val span = if (camera.projection == Projection.PERSPECTIVE) 2*camera.distance*tan(OrbitCamera.FOV_Y/2) else camera.orthographicHeight
    val offset = right*((2*x/width-1)*span*width/height/2)+up*((1-2*y/height)*span/2)
    val eye = PickVector(camera.target.x,camera.target.y,camera.target.z)+outward*camera.distance
    val direction = if (camera.projection == Projection.PERSPECTIVE) offset-outward*camera.distance else outward*(-1.0)
    val norm = sqrt(direction.dot(direction))
    return (if (camera.projection == Projection.PERSPECTIVE) eye else eye+offset) to direction*(1.0/norm)
}

/** A cheap cursor projection for previews; committed picks still test actual geometry. */
internal fun viewPlanePoint(camera:OrbitCamera,x:Double,y:Double,width:Double,height:Double):Point3 {
    val ray=viewRay(camera,x,y,width,height)
    val normal=PickVector(cos(camera.pitch)*cos(camera.yaw),cos(camera.pitch)*sin(camera.yaw),sin(camera.pitch))
    val target=PickVector(camera.target.x,camera.target.y,camera.target.z)
    val denominator=ray.second.dot(normal)
    val distance=if(abs(denominator)<1e-12)0.0 else (target-ray.first).dot(normal)/denominator
    return (ray.first+ray.second*distance).point()
}
