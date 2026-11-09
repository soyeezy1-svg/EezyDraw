package com.eezydraw.core.editing

import com.eezydraw.core.geometry.wallCutMesh
import com.eezydraw.core.geometry.ScenePresentation
import com.eezydraw.core.geometry.DrawingScene
import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.SceneSegment
import com.eezydraw.core.geometry.SpatialIndex
import com.eezydraw.core.model.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Immutable selection snapshot. The segment index handles plan outline capture; a second
 * element-bounds tree prunes filled-body and orbit picks. Construct alongside the scene,
 * off the UI thread, and reuse through camera/gesture changes. Hidden geometry never enters it.
 */
class EditorPicker(
    private val document: ProjectDocument,
    private val scene: DrawingScene,
    private val index: SpatialIndex = SpatialIndex(scene.segments),
    private val planView:Boolean=false,
) {
    private val records: Map<String, PickRecord>
    private val root: PickNode?
    private val frames=wallFrames(document).associateBy {it.host.id to it.roomSide}
    private val cuts=wallServiceCuts(document).groupBy {it.wallId to it.roomSide}
    private val architecturalSources=document.elements.mapNotNull {e->(e.body as? ElementBody.Component)?.let {b->
        if(document.libraryItems.find {it.id==b.libraryItemId}?.openingKind()!=null)e.id else null
    }}.toSet()
    private val wallMeshes=frames.filterKeys {cuts[it].orEmpty().isNotEmpty()}.mapValues {(_,frame)->createCutMesh(frame)}
    private fun cutMesh(frame:WallFrame):Shape.Mesh? = wallMeshes[frame.host.id to frame.roomSide]
    private fun createCutMesh(frame:WallFrame):Shape.Mesh? {
        val active=cuts[frame.host.id to frame.roomSide].orEmpty().filter {cut->document.project.settings.wallServices.showCuts ||
            cut.sourceId in architecturalSources}
            .map {cut->if(planView && cut.sourceId in architecturalSources) {
                val l=cut.outline.minOf {it.x};val r=cut.outline.maxOf {it.x}
                cut.copy(outline=listOf(Point2(l,0.0),Point2(r,0.0),Point2(r,frame.height),Point2(l,frame.height)))
            }else cut}
        return wallCutMesh(frame,active,document.project.settings.wallServices.sectionHeight.takeIf {document.project.settings.viewMode==ViewMode.SECTION})
    }

    init {
        val sources = document.elements.associateBy { it.id.value }
        records = scene.segments.groupBy { it.elementId }.mapNotNull { (id, edges) ->
            sources[id]?.let { id to PickRecord(it, edges, edgeBounds(edges)) }
        }.toMap()
        root = buildTree(records.values.toList())
    }

    fun boundsFor(ids: Set<ElementId>): Bounds3? = ids.mapNotNull { records[it.value]?.bounds }.reduceOrNull(::pickUnion)

    /** Nearby outlines win before closed interiors. Empty polygon holes remain empty. */
    fun pickPlan(point: Point2, tolerance: Double, levelId: String? = null): ElementId? {
        if (!tolerance.isFinite() || tolerance < 0.0) return null
        val edge = index.queryNear2D(point, tolerance, levelId = levelId).minWithOrNull(
            compareBy<SceneSegment> { distanceToEdge(point.x, point.y, it.a.x, it.a.y, it.b.x, it.b.y).first }
                .thenByDescending { max(it.a.z, it.b.z) }.thenBy { it.elementId },
        )
        val ray = PickRay(DragVector(point.x, point.y, scene.bounds.max.z), DragVector(0.0, 0.0, -1.0))
        if (edge != null) return resolveRoomWallPick(records[edge.elementId]?.element,ray)
        var closest: Pair<Double, ElementId>? = null
        visit(root, { point.x in it.min.x..it.max.x && point.y in it.min.y..it.max.y }) { record ->
            if (record.onLevel(levelId)) {
                val hit = intersectElement(ray, record.element)
                if (hit != null && (closest == null || hit < closest!!.first)) closest = hit to record.element.id
            }
        }
        return closest?.second
    }

    /** Screen outlines are ranked by distance, then visible depth. Closed interiors use rays. */
    fun pick3D(
        camera: OrbitCamera,
        screenX: Double,
        screenY: Double,
        width: Double,
        height: Double,
        tolerancePixels: Double,
        levelId: String? = null,
    ): ElementId? {
        if (!screenX.isFinite() || !screenY.isFinite() || !width.isFinite() || !height.isFinite() ||
            width <= 0.0 || height <= 0.0 || !tolerancePixels.isFinite() || tolerancePixels < 0.0) return null
        val ray = cameraPickRay(camera, screenX, screenY, width, height)
        var outline: PickHit? = null
        var interior: Pair<Double, ElementId>? = null
        visit(root, { bounds -> screenBoundsContain(camera, bounds, screenX, screenY, width, height, tolerancePixels) }) { record ->
            if (record.onLevel(levelId)) {
                for (edge in record.edges) {
                    val projected = projectEdge(camera, edge, width, height) ?: continue
                    val a = projected.first; val b = projected.second
                    val (distance, fraction) = distanceToEdge(screenX, screenY, a.x, a.y, b.x, b.y)
                    if (distance > tolerancePixels) continue
                    val depth = a.z + (b.z - a.z) * fraction
                    val hit = PickHit(distance, depth, record.element.id)
                    val previous = outline
                    if (previous == null || distance < previous.distance - 0.25 ||
                        abs(distance - previous.distance) <= 0.25 && depth < previous.depth) outline = hit
                }
                if (rayBounds(ray, record.bounds) != null) {
                    val hit = intersectElement(ray, record.element)
                    if (hit != null && (interior == null || hit < interior!!.first)) interior = hit to record.element.id
                }
            }
        }
        val picked=outline?.id ?: interior?.second
        return resolveRoomWallPick(picked?.let {records[it.value]?.element},ray)
    }

    /** A room floor outline may coincide with the middle of a generated wall. Resolve that
     * boundary to the real wall solid; ray tests preserve the empty space in openings. */
    private fun resolveRoomWallPick(element:Element?,ray:PickRay):ElementId? {
        val room=element?.body as? ElementBody.Room ?: return element?.id
        return room.wallFaces.asSequence().map {it.wallId}.distinct().mapNotNull {id->
            records[id.value]?.element?.let {wall->intersectElement(ray,wall)?.let {it to id}}
        }.minByOrNull {it.first}?.second ?: element.id
    }

    private fun intersectElement(worldRay: PickRay, element: Element): Double? {
        val ray = inverseRay(worldRay, element.transform)
        return when (val body = element.body) {
            is ElementBody.Room -> {
                val presentation=ScenePresentation(document,planView)
                val hits=mutableListOf<Double>()
                polygonHit(ray,body.boundary,0.0)?.let(hits::add)
                frames.values.filter {it.host.id==element.id && it.roomSide!=null && presentation.sideDisplay(element,it.roomSide!!)!=ObjectDisplay.HIDDEN}
                     .forEach {frame->
                        if(cuts[frame.host.id to frame.roomSide].orEmpty().isNotEmpty())cutMesh(frame)?.let {mesh->meshHit(ray,mesh)?.let(hits::add)}
                        else {
                            val points=listOf(frame.world(0.0,0.0,0.0),frame.world(frame.length,0.0,0.0),frame.world(frame.length,0.0,frame.height),frame.world(0.0,0.0,frame.height)).map {measurementToLocal(element.transform,it)}
                            planarRingHit(ray,points)?.let(hits::add)
                        }
                    }
                hits.minOrNull()
            }
            is ElementBody.Slab -> extrudedPolygonHit(ray, body.boundary, -body.thickness, 0.0)
            is ElementBody.Wall -> if(cuts[element.id to null].orEmpty().isEmpty())extrudedPolygonHit(ray, wallPolygon(body), 0.0, body.height)
                else frames[element.id to null]?.let {f->cutMesh(f)?.let {meshHit(ray,it)}}
            is ElementBody.Component -> {
                // Detailed components retain outline selection rather than gaining an invented solid.
                val definition = document.libraryItems.firstOrNull { it.id == body.libraryItemId }
                if (definition == null || definition.geometry.isEmpty()) boxHit(ray, body.size) else null
            }
            is ElementBody.Route -> null
            is ElementBody.DrawingShape -> when (val shape = body.shape) {
                is Shape.Circle -> ellipseHit(ray, shape.radius, shape.radius)
                is Shape.Ellipse -> ellipseHit(ray, shape.radiusX, shape.radiusY)
                is Shape.Polygon -> polygonHit(ray, shape.boundary, 0.0)
                is Shape.Hatch -> polygonHit(ray, shape.boundary, 0.0)
                is Shape.RevisionCloud -> polygonHit(ray, shape.boundary, 0.0)
                is Shape.Polyline -> if (shape.closed) planarRingHit(ray, shape.points) else null
                is Shape.Box -> boxHit(ray, shape.size)
                is Shape.Sphere -> roots(ray.direction dot ray.direction,
                    2 * (ray.origin dot ray.direction), (ray.origin dot ray.origin) - shape.radius * shape.radius).firstOrNull()
                is Shape.Cylinder -> coneHit(ray, shape.radius, shape.radius, shape.height)
                is Shape.Cone -> coneHit(ray, shape.baseRadius, shape.topRadius, shape.height)
                is Shape.Pyramid -> pyramidHit(ray, shape)
                is Shape.Extrusion -> extrudedPolygonHit(ray, shape.profile, min(0.0, shape.distance), max(0.0, shape.distance))
                else -> null
            }
        }
    }

    private data class PickRecord(val element: Element, val edges: List<SceneSegment>, val bounds: Bounds3) {
        fun onLevel(levelId: String?) = levelId == null || element.levelId == null || element.levelId?.value == levelId
    }
    private data class PickNode(val bounds: Bounds3, val entries: List<PickRecord>, val left: PickNode? = null, val right: PickNode? = null)
    private data class PickHit(val distance: Double, val depth: Double, val id: ElementId)

    private fun buildTree(entries: List<PickRecord>): PickNode? {
        if (entries.isEmpty()) return null
        val bounds = entries.map { it.bounds }.reduce(::pickUnion)
        if (entries.size <= 8) return PickNode(bounds, entries)
        val spans = listOf(bounds.max.x - bounds.min.x, bounds.max.y - bounds.min.y, bounds.max.z - bounds.min.z)
        val axis = spans.indices.maxBy { spans[it] }
        fun middle(b: Bounds3) = when (axis) { 0 -> b.min.x / 2 + b.max.x / 2; 1 -> b.min.y / 2 + b.max.y / 2; else -> b.min.z / 2 + b.max.z / 2 }
        val sorted = entries.sortedBy { middle(it.bounds) }
        return PickNode(bounds, emptyList(), buildTree(sorted.subList(0, sorted.size / 2)), buildTree(sorted.subList(sorted.size / 2, sorted.size)))
    }

    private fun visit(node: PickNode?, intersects: (Bounds3) -> Boolean, action: (PickRecord) -> Unit) {
        if (node == null || !intersects(node.bounds)) return
        node.entries.forEach { if (intersects(it.bounds)) action(it) }
        visit(node.left, intersects, action); visit(node.right, intersects, action)
    }
}

private fun screenBoundsContain(camera: OrbitCamera, bounds: Bounds3, x: Double, y: Double, width: Double, height: Double, tolerance: Double): Boolean {
    if (!camera.visible(bounds, width / height)) return false
    val points = pickCorners(bounds).map { camera.project(it, width, height) }
    // A box straddling the eye plane has unbounded projected extent and cannot be culled here.
    if (points.any { it == null }) return true
    val projected = points.filterNotNull()
    return x >= projected.minOf { it.x } - tolerance && x <= projected.maxOf { it.x } + tolerance &&
        y >= projected.minOf { it.y } - tolerance && y <= projected.maxOf { it.y } + tolerance
}

private fun projectEdge(camera: OrbitCamera, edge: SceneSegment, width: Double, height: Double): Pair<Point3, Point3>? {
    var a = camera.project(edge.a, width, height); var b = camera.project(edge.b, width, height)
    if (a == null && b == null) return null
    if (a == null || b == null) {
        val outward = DragVector(cos(camera.pitch) * cos(camera.yaw), cos(camera.pitch) * sin(camera.yaw), sin(camera.pitch))
        fun depth(p: Point3) = camera.distance - ((DragVector(p.x - camera.target.x, p.y - camera.target.y, p.z - camera.target.z)) dot outward)
        val near = camera.clipDistances().near * 1.0001
        val da = depth(edge.a); val db = depth(edge.b)
        val t = ((near - da) / (db - da)).coerceIn(0.0, 1.0)
        val clipped = Point3(edge.a.x + (edge.b.x - edge.a.x) * t, edge.a.y + (edge.b.y - edge.a.y) * t, edge.a.z + (edge.b.z - edge.a.z) * t)
        if (a == null) a = camera.project(clipped, width, height) else b = camera.project(clipped, width, height)
    }
    return if (a != null && b != null) a to b else null
}

private fun distanceToEdge(x: Double, y: Double, ax: Double, ay: Double, bx: Double, by: Double): Pair<Double, Double> {
    val dx = bx - ax; val dy = by - ay
    val squared = dx * dx + dy * dy
    val t = if (squared > 0.0) (((x - ax) * dx + (y - ay) * dy) / squared).coerceIn(0.0, 1.0) else 0.0
    return hypot(x - (ax + dx * t), y - (ay + dy * t)) to t
}

private fun inverseRay(ray: PickRay, transform: Transform3): PickRay {
    val cx = cos(transform.rotation.x); val sx = sin(transform.rotation.x)
    val cy = cos(transform.rotation.y); val sy = sin(transform.rotation.y)
    val cz = cos(transform.rotation.z); val sz = sin(transform.rotation.z)
    fun inverse(v: DragVector): DragVector {
        val y = cx * v.y + sx * v.z; val z = -sx * v.y + cx * v.z
        val x = cy * v.x - sy * z; val zz = sy * v.x + cy * z
        return DragVector((cz * x + sz * y) / transform.scale.x,
            (-sz * x + cz * y) / transform.scale.y, zz / transform.scale.z)
    }
    return PickRay(inverse(ray.origin - DragVector(transform.translation.x, transform.translation.y, transform.translation.z)), inverse(ray.direction))
}

private fun rayBounds(ray: PickRay, bounds: Bounds3): Double? {
    var near = 0.0; var far = Double.POSITIVE_INFINITY
    fun slab(origin: Double, direction: Double, low: Double, high: Double): Boolean {
        if (direction == 0.0) return origin in low..high
        val first = (low - origin) / direction; val second = (high - origin) / direction
        near = max(near, min(first, second)); far = min(far, max(first, second))
        return far >= near
    }
    return if (slab(ray.origin.x, ray.direction.x, bounds.min.x, bounds.max.x) &&
        slab(ray.origin.y, ray.direction.y, bounds.min.y, bounds.max.y) &&
        slab(ray.origin.z, ray.direction.z, bounds.min.z, bounds.max.z)) near else null
}

private fun boxHit(ray: PickRay, size: Size3) = rayBounds(ray, Bounds3(Point3(), Point3(size.width, size.depth, size.height)))

private fun planeHit(ray: PickRay, z: Double): Double? {
    if (ray.direction.z == 0.0) return null
    val t = (z - ray.origin.z) / ray.direction.z
    return t.takeIf { it.isFinite() && it >= 0.0 }
}

private fun ellipseHit(ray: PickRay, rx: Double, ry: Double): Double? {
    val t = planeHit(ray, 0.0) ?: return null
    val p = ray.origin + ray.direction * t
    return t.takeIf { (p.x / rx) * (p.x / rx) + (p.y / ry) * (p.y / ry) <= 1.0 }
}

private fun polygonHit(ray: PickRay, polygon: Polygon2, z: Double): Double? {
    val t = planeHit(ray, z) ?: return null
    val p = ray.origin + ray.direction * t
    return t.takeIf { insideRing(p.x, p.y, polygon.outer) && polygon.holes.none { insideRing(p.x, p.y, it) } }
}

private fun insideRing(x: Double, y: Double, ring: List<Point2>): Boolean {
    var inside = false
    var last = ring.last()
    for (next in ring) {
        if ((next.y > y) != (last.y > y) && x < (last.x - next.x) * ((y - next.y) / (last.y - next.y)) + next.x) inside = !inside
        last = next
    }
    return inside
}

private fun extrudedPolygonHit(ray: PickRay, polygon: Polygon2, bottom: Double, top: Double): Double? {
    val hits = ArrayList<Double>()
    polygonHit(ray, polygon, bottom)?.let(hits::add); polygonHit(ray, polygon, top)?.let(hits::add)
    for (ring in listOf(polygon.outer) + polygon.holes) for (i in ring.indices) {
        val a = ring[i]; val b = ring[(i + 1) % ring.size]
        val dx = b.x - a.x; val dy = b.y - a.y
        val denominator = ray.direction.x * dy - ray.direction.y * dx
        if (denominator == 0.0) continue
        val t = ((a.x - ray.origin.x) * dy - (a.y - ray.origin.y) * dx) / denominator
        if (t < 0.0 || !t.isFinite()) continue
        val p = ray.origin + ray.direction * t
        val fraction = if (abs(dx) >= abs(dy)) (p.x - a.x) / dx else (p.y - a.y) / dy
        if (fraction in 0.0..1.0 && p.z in bottom..top) hits.add(t)
    }
    return hits.minOrNull()
}

private fun coneHit(ray: PickRay, base: Double, top: Double, height: Double): Double? {
    val slope = (top - base) / height
    val r0 = base + slope * ray.origin.z
    val rz = slope * ray.direction.z
    val hits = roots(ray.direction.x * ray.direction.x + ray.direction.y * ray.direction.y - rz * rz,
        2 * (ray.origin.x * ray.direction.x + ray.origin.y * ray.direction.y - r0 * rz),
        ray.origin.x * ray.origin.x + ray.origin.y * ray.origin.y - r0 * r0)
        .filter { (ray.origin.z + ray.direction.z * it) in 0.0..height }.toMutableList()
    for ((z, radius) in listOf(0.0 to base, height to top)) {
        val t = planeHit(ray, z) ?: continue
        val p = ray.origin + ray.direction * t
        if (p.x * p.x + p.y * p.y <= radius * radius) hits.add(t)
    }
    return hits.minOrNull()
}

private fun roots(a: Double, b: Double, c: Double): List<Double> {
    if (a == 0.0) return if (b == 0.0) emptyList() else listOf(-c / b).filter { it.isFinite() && it >= 0.0 }
    val discriminant = b * b - 4 * a * c
    if (discriminant < 0.0 || !discriminant.isFinite()) return emptyList()
    // Stable quadratic roots avoid cancellation for rays originating far from small objects.
    val q = -0.5 * (b + if (b >= 0.0) sqrt(discriminant) else -sqrt(discriminant))
    val values = if (q == 0.0) listOf(-b / (2 * a)) else listOf(q / a, c / q)
    return values.filter { it.isFinite() && it >= 0.0 }.sorted()
}

private fun planarRingHit(ray: PickRay, points: List<Point3>): Double? {
    val first = vector(points.first())
    val normal = points.drop(1).zipWithNext().map { (a, b) -> cross(vector(a) - first, vector(b) - first) }
        .firstOrNull { (it dot it) > 0.0 } ?: return null
    val denominator = normal dot ray.direction
    if (denominator == 0.0) return null
    val t = (normal dot (first - ray.origin)) / denominator
    if (t < 0.0 || !t.isFinite()) return null
    val p = ray.origin + ray.direction * t
    val axis = listOf(abs(normal.x), abs(normal.y), abs(normal.z)).indices.maxBy { listOf(abs(normal.x), abs(normal.y), abs(normal.z))[it] }
    fun flatten(v: DragVector) = when (axis) { 0 -> Point2(v.y, v.z); 1 -> Point2(v.x, v.z); else -> Point2(v.x, v.y) }
    val tap = flatten(p)
    return t.takeIf { insideRing(tap.x, tap.y, points.map { flatten(vector(it)) }) }
}

private fun pyramidHit(ray: PickRay, shape: Shape.Pyramid): Double? {
    val hits = mutableListOf<Double>()
    polygonHit(ray, shape.base, 0.0)?.let(hits::add)
    for (ring in listOf(shape.base.outer) + shape.base.holes) for (i in ring.indices) {
        val a = ring[i]; val b = ring[(i + 1) % ring.size]
        planarRingHit(ray, listOf(Point3(a.x, a.y), Point3(b.x, b.y), shape.apex))?.let(hits::add)
    }
    return hits.minOrNull()
}

private fun wallPolygon(wall: ElementBody.Wall): Polygon2 {
    val dx = wall.end.x - wall.start.x; val dy = wall.end.y - wall.start.y; val length = hypot(dx, dy)
    val left = when (wall.reference) { WallReference.CENTERLINE -> wall.thickness / 2; WallReference.LEFT_FINISH_FACE -> 0.0; WallReference.RIGHT_FINISH_FACE -> wall.thickness }
    fun offset(p: Point2, distance: Double) = Point2(p.x - dy / length * distance, p.y + dx / length * distance)
    return Polygon2(listOf(offset(wall.start, left), offset(wall.end, left), offset(wall.end, left - wall.thickness), offset(wall.start, left - wall.thickness)))
}

private fun vector(p: Point3) = DragVector(p.x, p.y, p.z)
private fun cross(a: DragVector, b: DragVector) = DragVector(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
private fun pickCorners(bounds: Bounds3) = listOf(bounds.min.x, bounds.max.x).flatMap { x -> listOf(bounds.min.y, bounds.max.y).flatMap { y -> listOf(bounds.min.z, bounds.max.z).map { z -> Point3(x, y, z) } } }
private fun edgeBounds(edges: List<SceneSegment>): Bounds3 {
    val points = edges.asSequence().flatMap { sequenceOf(it.a, it.b) }
    var bounds: Bounds3? = null
    for (point in points) bounds = bounds?.let { pickUnion(it, Bounds3(point, point)) } ?: Bounds3(point, point)
    return checkNotNull(bounds)
}
private fun pickUnion(a: Bounds3, b: Bounds3) = Bounds3(
    Point3(min(a.min.x, b.min.x), min(a.min.y, b.min.y), min(a.min.z, b.min.z)),
    Point3(max(a.max.x, b.max.x), max(a.max.y, b.max.y), max(a.max.z, b.max.z)),
)

private fun meshHit(ray:PickRay,mesh:Shape.Mesh):Double? {
    var nearest:Double?=null
    for(i in mesh.triangles.indices step 3) {
        val a=vector(mesh.vertices[mesh.triangles[i]]);val b=vector(mesh.vertices[mesh.triangles[i+1]]);val c=vector(mesh.vertices[mesh.triangles[i+2]])
        val e1=b-a;val e2=c-a;val p=cross(ray.direction,e2);val det=e1 dot p
        if(abs(det)<1e-12)continue
        val q=ray.origin-a;val u=(q dot p)/det;if(u<0 || u>1)continue
        val r=cross(q,e1);val v=(ray.direction dot r)/det;if(v<0 || u+v>1)continue
        val t=(e2 dot r)/det;if(t>=0 && (nearest==null || t<nearest))nearest=t
    }
    return nearest
}
