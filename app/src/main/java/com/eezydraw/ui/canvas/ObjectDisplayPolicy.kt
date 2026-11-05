package com.eezydraw.ui.canvas

import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.model.ParameterValue
import com.eezydraw.core.model.ProjectDocument
import com.eezydraw.core.model.ROOM_SIDE_DISPLAY_PREFIX
import com.eezydraw.core.model.objectDisplay
import com.eezydraw.core.model.Bounds3
import com.eezydraw.core.model.ObjectDisplay
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.Projection
import com.eezydraw.core.model.RoomSideRef
import com.eezydraw.core.model.ViewMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Object presentation overrides the project mode without changing its geometry or anchors. */
internal fun objectViewMode(mode: ViewMode, display: ObjectDisplay, frontWall: Boolean = false): ViewMode = when (display) {
    ObjectDisplay.HIDDEN, ObjectDisplay.WIREFRAME -> ViewMode.WIREFRAME
    ObjectDisplay.XRAY -> ViewMode.XRAY
    ObjectDisplay.NORMAL -> if (frontWall) ViewMode.XRAY else mode
}

internal fun objectRenderPlan(mode: ViewMode, display: ObjectDisplay, frontWall: Boolean = false): ViewRenderPlan =
    viewRenderPlan(objectViewMode(mode, display, frontWall))

internal fun hasXrayOverrides(document: ProjectDocument): Boolean = document.elements.any { element ->
    element.objectDisplay == ObjectDisplay.XRAY || element.properties.any { (key, value) ->
        key.startsWith(ROOM_SIDE_DISPLAY_PREFIX) && (value as? ParameterValue.Text)?.value == ObjectDisplay.XRAY.name
    }
}

internal fun objectEdgeOpacity(display: ObjectDisplay, frontWall: Boolean = false): Float = when {
    display == ObjectDisplay.HIDDEN -> .18f
    display == ObjectDisplay.XRAY || display == ObjectDisplay.NORMAL && frontWall -> .65f
    else -> 1f
}

/** Metadata gathered once with immutable scene batches, including complete subdivided sides. */
internal data class RoomWallPart(val side: RoomSideRef?, val bounds: Bounds3, val roomCenter: Point3?)

/** Camera movement only tests the prepared side centres; it never rebuilds or uploads geometry. */
internal class RoomWallIndex(parts: Iterable<RoomWallPart>) {
    private data class Side(val bounds: Bounds3, val roomCenter: Point3?)
    private val sides: Map<RoomSideRef, Side>
    init {
        val gathered = linkedMapOf<RoomSideRef, Side>()
        for (part in parts) {
            val key = part.side ?: continue
            val old = gathered[key]
            gathered[key] = Side(old?.bounds?.let { union(it, part.bounds) } ?: part.bounds,
                old?.roomCenter ?: part.roomCenter)
        }
        val roomBounds = gathered.entries.groupBy { it.key.roomId }.mapValues { (_, values) ->
            values.map { it.value.bounds }.reduce(::union)
        }
        sides = gathered.mapValues { (key, side) -> side.copy(roomCenter = side.roomCenter ?: center(roomBounds.getValue(key.roomId))) }
    }

    fun isFront(side: RoomSideRef?, camera: OrbitCamera, enabled: Boolean, is3D: Boolean): Boolean {
        if (!enabled || !is3D || side == null) return false
        val wall = sides[side] ?: return false
        val room = wall.roomCenter ?: return false
        val center = center(wall.bounds)
        val outward = Point3(cos(camera.pitch) * cos(camera.yaw), cos(camera.pitch) * sin(camera.yaw), sin(camera.pitch))
        val towardEye = if (camera.projection == Projection.ORTHOGRAPHIC) outward else Point3(
            camera.target.x + outward.x * camera.distance - room.x,
            camera.target.y + outward.y * camera.distance - room.y,
            camera.target.z + outward.z * camera.distance - room.z,
        )
        val offset = Point3(center.x - room.x, center.y - room.y, center.z - room.z)
        val dot = offset.x * towardEye.x + offset.y * towardEye.y + offset.z * towardEye.z
        val resolution = maxOf(Math.ulp(center.x), Math.ulp(center.y), Math.ulp(center.z)) * 8
        val tolerance = maxOf(1e-9, resolution * maxOf(1.0, abs(towardEye.x) + abs(towardEye.y) + abs(towardEye.z)))
        return dot > tolerance
    }

    private companion object {
        fun union(a: Bounds3, b: Bounds3) = Bounds3(
            Point3(minOf(a.min.x, b.min.x), minOf(a.min.y, b.min.y), minOf(a.min.z, b.min.z)),
            Point3(maxOf(a.max.x, b.max.x), maxOf(a.max.y, b.max.y), maxOf(a.max.z, b.max.z)),
        )
        fun center(bounds: Bounds3) = Point3(bounds.min.x / 2 + bounds.max.x / 2, bounds.min.y / 2 + bounds.max.y / 2, bounds.min.z / 2 + bounds.max.z / 2)
    }
}
