package com.eezydraw.core.editing

import com.eezydraw.core.geometry.DrawingScene
import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.SceneSegment
import com.eezydraw.core.geometry.SnapEngine
import com.eezydraw.core.geometry.SpatialIndex
import com.eezydraw.core.geometry.Viewport2D
import com.eezydraw.core.model.ElementId
import com.eezydraw.core.model.Point2
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.ProjectDocument
import com.eezydraw.core.model.SnapMode
import com.eezydraw.core.model.SnapSettings
import com.eezydraw.core.model.measurementShape
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tan

/**
 * Immutable gesture index, prepared once for a selection rather than on every pointer event.
 * Selected objects are excluded so a moving item cannot snap to its original wireframe.
 */
class MoveContext(document: ProjectDocument, selectedIds: Set<ElementId>, scene: DrawingScene) {
    val projectId = document.project.id
    private val selected = selectedIds.mapTo(HashSet()) { it.value }.also { excluded ->
        // Associative annotations follow a selected host and must not attract that host
        // back to its old position while the next document snapshot is being prepared.
        document.elements.forEach { element ->
            if (measurementShape(element)?.measurement?.anchors?.any { it.elementId in selectedIds } == true)
                excluded.add(element.id.value)
        }
    }
    private val index = SpatialIndex(scene.segments.filterNot { it.elementId in selected })
    private val planSnaps = SnapEngine(index)

    /** Null [axis] moves on the horizontal XY plane through [pivot], preserving elevation. */
    fun delta(
        pivot: Point3,
        start: Point2,
        current: Point2,
        axis: Point3?,
        viewport: Viewport2D,
        orbit: OrbitCamera,
        is3D: Boolean,
        width: Double,
        height: Double,
        snapSettings: SnapSettings,
        tolerancePixels: Double,
        levelId: String? = null,
    ): EditDelta {
        if (start == current || width <= 0.0 || height <= 0.0 || !width.isFinite() || !height.isFinite()) return EditDelta(pivot)
        val unitAxis = axis?.let(::normalized)
        val displacement = if (unitAxis != null) {
            axisDisplacement(unitAxis, pivot, start, current, viewport, orbit, is3D, width, height)
        } else if (!is3D) {
            val first = viewport.screenToWorld(start.x, start.y, width, height)
            val last = viewport.screenToWorld(current.x, current.y, width, height)
            Point3(last.x - first.x, last.y - first.y)
        } else {
            groundDisplacement(pivot,start,current,orbit,width,height)
        }
        if (magnitude(displacement) < 1e-12) return EditDelta(pivot)
        val candidate = pivot + displacement
        val snapped = if (unitAxis == null) {
            // Ground movement shares the plan's six snap modes while keeping mounting height.
            val tolerance = max(snapSettings.tolerance, max(0.0,tolerancePixels) *
                moveMetresPerPixel(pivot,viewport,orbit,is3D,height))
            val snap = planSnaps.snap(Point2(candidate.x,candidate.y),snapSettings,tolerance,
                Point2(pivot.x,pivot.y),levelId).point
            Point3(snap.x,snap.y,pivot.z)
        } else snapAxis(candidate,pivot,unitAxis,viewport,orbit,is3D,height,snapSettings,tolerancePixels,levelId)
        return EditDelta(pivot = pivot, translation = snapped - pivot)
    }

    private fun snapAxis(
        candidate:Point3,pivot:Point3,axis:Point3,viewport:Viewport2D,camera:OrbitCamera,
        is3D:Boolean,height:Double,settings:SnapSettings,pixels:Double,levelId:String?,
    ):Point3 {
        if(!settings.enabled || settings.modes.isEmpty())return candidate
        val tolerance=max(settings.tolerance,max(0.0,pixels)*moveMetresPerPixel(pivot,viewport,camera,is3D,height))
        val epsilon=max(1e-7,max(Math.ulp(pivot.x),max(Math.ulp(pivot.y),Math.ulp(pivot.z)))*8)
        var best:Point3?=null
        var bestDistance=Double.POSITIVE_INFINITY
        var bestPriority=Int.MAX_VALUE
        fun consider(point:Point3,priority:Int) {
            val offset=point-pivot
            if(magnitude(offset-axis*(offset dot axis))>epsilon)return
            val distance=magnitude(point-candidate)
            if(!distance.isFinite() || distance>tolerance)return
            if(distance<bestDistance-epsilon || abs(distance-bestDistance)<=epsilon && priority<bestPriority) {
                best=point;bestDistance=distance;bestPriority=priority
            }
        }
        val near=index.queryNear2D(Point2(candidate.x,candidate.y),tolerance,levelId=levelId)
        for(segment in near) {
            if(SnapMode.ENDPOINT in settings.modes) {consider(segment.a,0);consider(segment.b,0)}
            if(SnapMode.MIDPOINT in settings.modes)consider(segment.a+(segment.b-segment.a)*.5,2)
            if(SnapMode.PERPENDICULAR in settings.modes) {
                val edge=segment.b-segment.a;val length2=edge dot edge
                if(length2>1e-20) {
                    val t=((pivot-segment.a) dot edge)/length2
                    if(t in 0.0..1.0)consider(segment.a+edge*t,3)
                }
            }
        }
        if(SnapMode.INTERSECTION in settings.modes) {
            val edges=near.take(64)
            for(i in edges.indices)for(j in i+1 until edges.size)
                intersection(edges[i],edges[j],epsilon)?.let {consider(it,1)}
        }
        if(SnapMode.GRID in settings.modes) {
            val coordinate=round((candidate dot axis)/settings.gridSpacing)*settings.gridSpacing
            val amount=coordinate-(pivot dot axis)
            if(amount.isFinite())consider(pivot+axis*amount,5)
        }
        return best ?: candidate
    }

}

/** Unit screen direction for real or virtual world-axis arrows, shared by input and gizmos. */
fun moveAxisScreenDirection(axis:Point3,viewport:Viewport2D,orbit:OrbitCamera,is3D:Boolean,
    width:Double,height:Double,pivot:Point3=orbit.target):Point2 {
    val unit=normalized(axis)
    val projected=axisProjection(unit,pivot,viewport,orbit,is3D,width,height)
    if(projected!=null)return Point2(projected.first.x/projected.second,projected.first.y/projected.second)
    return virtualAxisDirection(unit)
}

private fun axisDisplacement(axis:Point3,pivot:Point3,start:Point2,current:Point2,
    viewport:Viewport2D,orbit:OrbitCamera,is3D:Boolean,width:Double,height:Double):Point3 {
    val projected=axisProjection(axis,pivot,viewport,orbit,is3D,width,height)
    if(projected!=null) {
        if(is3D)return screenAxisDrag(axis,pivot,start,current,viewport,orbit,true,width,height)
        val pixelsPerMetre=viewport.pixelsPerMetre
        val dx=axis.x*pixelsPerMetre;val dy=-axis.y*pixelsPerMetre
        val amount=((current.x-start.x)*dx+(current.y-start.y)*dy)/(dx*dx+dy*dy)
        return axis*amount
    }
    val direction=virtualAxisDirection(axis)
    val pixels=(current.x-start.x)*direction.x+(current.y-start.y)*direction.y
    return axis*(pixels*moveMetresPerPixel(pivot,viewport,orbit,is3D,height))
}

/** A collapsed arrow remains usable: red right, green down/right, blue up. */
private fun virtualAxisDirection(axis:Point3):Point2 = when {
    abs(axis.z)>=max(abs(axis.x),abs(axis.y))->Point2(0.0,if(axis.z<0.0)1.0 else -1.0)
    abs(axis.x)>=abs(axis.y)->Point2(if(axis.x<0.0)-1.0 else 1.0,0.0)
    else->{val sign=if(axis.y<0.0)-1.0 else 1.0;Point2(sign/kotlin.math.sqrt(2.0),sign/kotlin.math.sqrt(2.0))}
}

private fun axisProjection(axis:Point3,pivot:Point3,viewport:Viewport2D,orbit:OrbitCamera,
    is3D:Boolean,width:Double,height:Double):Pair<Point2,Double>? {
    if(width<=0.0 || height<=0.0)return null
    val pixels=if(!is3D)Point2(axis.x*viewport.pixelsPerMetre,-axis.y*viewport.pixelsPerMetre) else {
        val step=max(orbit.distance,orbit.orthographicHeight)*.001
        val a=orbit.project(pivot,width,height) ?: return null
        val b=orbit.project(pivot+axis*step,width,height) ?: return null
        Point2((b.x-a.x)/step,(b.y-a.y)/step)
    }
    val length=hypot(pixels.x,pixels.y)
    val expected=1.0/moveMetresPerPixel(pivot,viewport,orbit,is3D,height)
    return if(length.isFinite() && length>=max(1e-7,expected*.02))pixels to length else null
}

private fun moveMetresPerPixel(pivot:Point3,viewport:Viewport2D,camera:OrbitCamera,is3D:Boolean,height:Double):Double {
    if(!is3D)return 1.0/viewport.pixelsPerMetre
    val span=if(camera.projection==com.eezydraw.core.model.Projection.PERSPECTIVE) {
        val normal=facingNormal(camera)
        val depth=camera.distance-((pivot-camera.target) dot normal)
        2*max(1e-5,depth)*tan(OrbitCamera.FOV_Y/2)
    } else camera.orthographicHeight
    return span/height
}

private fun groundDisplacement(pivot:Point3,start:Point2,current:Point2,camera:OrbitCamera,width:Double,height:Double):Point3 {
    val normal=facingNormal(camera)
    if(abs(normal.z)>=.15) {
        val first=planePick(camera,start,width,height,pivot,Point3(z=1.0))
        val last=planePick(camera,current,width,height,pivot,Point3(z=1.0))
        if(first!=null && last!=null)return Point3(last.x-first.x,last.y-first.y)
    }
    // At front/side the ground projects to a line. The free square still offers both
    // horizontal dimensions: screen horizontal follows camera-right, vertical its depth.
    val right=Point3(-sin(camera.yaw),cos(camera.yaw),0.0)
    val sign=if(abs(normal.x)>=abs(normal.y))if(normal.x<0.0)-1.0 else 1.0 else if(normal.y<0.0)-1.0 else 1.0
    val forward=normalized(Point3(normal.x*sign,normal.y*sign,0.0))
    val span=if(camera.projection==com.eezydraw.core.model.Projection.PERSPECTIVE)2*camera.distance*tan(OrbitCamera.FOV_Y/2) else camera.orthographicHeight
    val scale=span/height
    return right*((current.x-start.x)*scale)+forward*((start.y-current.y)*scale)
}

private fun facingNormal(camera: OrbitCamera) = Point3(cos(camera.pitch) * cos(camera.yaw),
    cos(camera.pitch) * sin(camera.yaw), sin(camera.pitch))

private fun planePick(camera: OrbitCamera, screen: Point2, width: Double, height: Double, point: Point3, normal: Point3): Point3? {
    val ray = cameraPickRay(camera, screen.x, screen.y, width, height)
    val origin = Point3(ray.origin.x, ray.origin.y, ray.origin.z)
    val direction = Point3(ray.direction.x, ray.direction.y, ray.direction.z)
    val denominator = direction dot normal
    if (abs(denominator) < 1e-10) return null
    val t = ((point - origin) dot normal) / denominator
    if (!t.isFinite() || t < 0.0) return null
    return origin + direction * t
}

private fun intersection(a: SceneSegment, b: SceneSegment, epsilon: Double): Point3? {
    val u = a.b - a.a; val v = b.b - b.a; val w = a.a - b.a
    val aa = u dot u; val bb = u dot v; val cc = v dot v
    val dd = u dot w; val ee = v dot w
    val denominator = aa * cc - bb * bb
    if (aa <= 1e-20 || cc <= 1e-20 || abs(denominator) <= aa * cc * 1e-12) return null
    val s = (bb * ee - cc * dd) / denominator
    val t = (aa * ee - bb * dd) / denominator
    if (s !in 0.0..1.0 || t !in 0.0..1.0) return null
    val first = a.a + u * s; val second = b.a + v * t
    return if (magnitude(first - second) <= epsilon) first else null
}

private fun normalized(axis: Point3): Point3 {
    val length = magnitude(axis)
    require(length.isFinite() && length > 0) { "Move axis must be nonzero." }
    return axis * (1.0 / length)
}
private fun magnitude(p: Point3) = hypot(hypot(p.x, p.y), p.z)
private operator fun Point3.plus(other: Point3) = Point3(x + other.x, y + other.y, z + other.z)
private operator fun Point3.minus(other: Point3) = Point3(x - other.x, y - other.y, z - other.z)
private operator fun Point3.times(value: Double) = Point3(x * value, y * value, z * value)
private infix fun Point3.dot(other: Point3) = x * other.x + y * other.y + z * other.z
