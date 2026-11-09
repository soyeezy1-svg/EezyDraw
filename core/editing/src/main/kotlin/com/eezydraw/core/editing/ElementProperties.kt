package com.eezydraw.core.editing

import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.Viewport2D
import com.eezydraw.core.geometry.buildDrawingScene
import com.eezydraw.core.geometry.unfilteredGeometryDocument
import com.eezydraw.core.model.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Size is measured along the object's local axes before rotation, in canonical metres. */
data class ElementProperties(val position: Point3, val rotation: Rotation3, val size: Point3)

/** Hidden/locked metadata must not change the intrinsic dimensions displayed in Properties. */
fun elementLocalBounds(document: ProjectDocument, element: Element): Bounds3 {
    val b=element.body as? ElementBody.Component
    if(b!=null && document.libraryItems.find {it.id==b.libraryItemId}?.openingKind()!=null)return Bounds3(Point3(),Point3(b.size.width,b.size.depth,b.size.height))
    return buildDrawingScene(
    unfilteredGeometryDocument(document.copy(elements=listOf(element.copy(transform=Transform3())))), architecturalCuts=false,
).bounds
}

fun elementWorldBounds(document: ProjectDocument, ids: Set<ElementId>): Bounds3 = buildDrawingScene(
    unfilteredGeometryDocument(document.copy(elements=document.elements.filter {it.id in ids})), architecturalCuts=false,
).bounds

fun readProperties(document: ProjectDocument, element: Element): ElementProperties {
    val bounds = elementLocalBounds(document, element)
    val scale = element.transform.scale
    return ElementProperties(
        element.transform.translation,
        element.transform.rotation,
        Point3((bounds.max.x - bounds.min.x) * abs(scale.x),
            (bounds.max.y - bounds.min.y) * abs(scale.y),
            (bounds.max.z - bounds.min.z) * abs(scale.z)),
    )
}

/** Resizing preserves mirror handedness. A planar/degenerate axis cannot acquire thickness. */
fun applyProperties(document: ProjectDocument, element: Element, properties: ElementProperties): Element {
    val bounds = elementLocalBounds(document, element)
    val old = element.transform.scale
    fun scale(base: Double, requested: Double, previous: Double): Double {
        if (base == 0.0) {
            require(requested == 0.0) { "A flat axis has no editable size." }
            return previous
        }
        require(requested > 0.0) { "Object dimensions must be positive." }
        val result = requested / base * if (previous < 0.0) -1.0 else 1.0
        require(result.isFinite() && result != 0.0) { "Object size exceeds finite scale limits." }
        return result
    }
    return element.copy(transform = Transform3(
        properties.position, properties.rotation,
        Scale3(scale(bounds.max.x - bounds.min.x, properties.size.x, old.x),
            scale(bounds.max.y - bounds.min.y, properties.size.y, old.y),
            scale(bounds.max.z - bounds.min.z, properties.size.z, old.z)),
    ))
}

/**
 * Constrain a screen drag to one world-space axis through [pivot]. The closest ray/axis
 * parameters preserve scale in perspective and at survey coordinates. A view-parallel axis
 * has no stable screen displacement and returns zero (the overlay hides that handle).
 */
fun screenAxisDrag(
    axis: Point3,
    pivot: Point3,
    start: Point2,
    current: Point2,
    viewport: Viewport2D,
    orbit: OrbitCamera,
    is3D: Boolean,
    width: Double,
    height: Double,
): Point3 {
    if (width <= 0.0 || height <= 0.0 || !width.isFinite() || !height.isFinite()) return Point3()
    val length = sqrt(axis.x * axis.x + axis.y * axis.y + axis.z * axis.z)
    require(length.isFinite() && length > 0.0) { "A drag axis must be nonzero." }
    val a = DragVector(axis.x / length, axis.y / length, axis.z / length)
    if (!is3D) {
        val p0 = viewport.screenToWorld(start.x, start.y, width, height)
        val p1 = viewport.screenToWorld(current.x, current.y, width, height)
        val amount = (p1.x - p0.x) * a.x + (p1.y - p0.y) * a.y
        return Point3(a.x * amount, a.y * amount, a.z * amount)
    }
    val origin = DragVector(pivot.x, pivot.y, pivot.z)
    fun parameter(screen: Point2): Double? {
        val ray = cameraPickRay(orbit, screen.x, screen.y, width, height)
        val offset = ray.origin - origin
        val alignment = a dot ray.direction
        val denominator = 1.0 - alignment * alignment
        if (denominator < 1e-8) return null
        return ((a dot offset) - alignment * (ray.direction dot offset)) / denominator
    }
    val first = parameter(start) ?: return Point3()
    val last = parameter(current) ?: return Point3()
    val amount = last - first
    if (!amount.isFinite()) return Point3()
    return Point3(a.x * amount, a.y * amount, a.z * amount)
}

internal data class DragVector(val x: Double, val y: Double, val z: Double) {
    operator fun plus(other: DragVector) = DragVector(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: DragVector) = DragVector(x - other.x, y - other.y, z - other.z)
    operator fun times(factor: Double) = DragVector(x * factor, y * factor, z * factor)
    infix fun dot(other: DragVector) = x * other.x + y * other.y + z * other.z
    fun normalized(): DragVector {
        val length = sqrt(this dot this)
        return this * (1.0 / length)
    }
}

internal data class PickRay(val origin: DragVector, val direction: DragVector)

internal fun cameraPickRay(camera: OrbitCamera, x: Double, y: Double, width: Double, height: Double): PickRay {
    val cy = cos(camera.yaw); val sy = sin(camera.yaw)
    val cp = cos(camera.pitch); val sp = sin(camera.pitch)
    val right = DragVector(-sy, cy, 0.0)
    val up = DragVector(-sp * cy, -sp * sy, cp)
    val outward = DragVector(cp * cy, cp * sy, sp)
    val perspective = camera.projection == Projection.PERSPECTIVE
    val visibleHeight = if (perspective) 2 * camera.distance * tan(OrbitCamera.FOV_Y / 2) else camera.orthographicHeight
    val offset = right * ((2 * x / width - 1) * visibleHeight * width / height / 2) +
        up * ((1 - 2 * y / height) * visibleHeight / 2)
    val eye = DragVector(camera.target.x, camera.target.y, camera.target.z) + outward * camera.distance
    return if (perspective) PickRay(eye, (offset - outward * camera.distance).normalized())
    else PickRay(eye + offset, outward * -1.0)
}
