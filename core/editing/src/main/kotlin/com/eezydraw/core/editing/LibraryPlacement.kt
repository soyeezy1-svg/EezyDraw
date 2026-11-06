package com.eezydraw.core.editing

import com.eezydraw.core.geometry.ScenePresentation
import com.eezydraw.core.model.*
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** A transient candidate. Constructing it never changes a project or its undo history. */
data class LibraryPlacement(
    val element: Element,
    val item: LibraryItem,
    val mountingHeight: Double,
    val wallSnapped: Boolean,
)

/**
 * Resolve mounting in project metres. The insertion point is on the mounting plane;
 * ceiling shapes use a negative local Z scale so they hang below that plane.
 * Wall items use local +Y as the outward face and +X along the mounting surface.
 */
fun previewLibraryPlacement(
    document: ProjectDocument,
    item: LibraryItem,
    point: Point3,
    levelId: LevelId? = null,
    layerId: LayerId? = null,
    id: ElementId = ElementId("library-preview"),
): LibraryPlacement {
    val level = placementLevel(document, levelId, point.z)
    val layer = if (layerId == null) document.layers.firstOrNull { it.visible && !it.locked }
        else document.layers.find { it.id == layerId && it.visible && !it.locked }
    requireNotNull(layer) { "Choose a visible, unlocked layer to place this item." }
    val floor = level?.elevation ?: 0.0
    val defaults = item.defaultParameters()
    val requestedHeight = (defaults[LibraryParameterKeys.MOUNTING_HEIGHT] as? ParameterValue.Number)?.value
        ?: item.defaultMountingHeight
    val height = when (item.mounting) {
        MountingType.FLOOR -> 0.0
        MountingType.WALL -> requestedHeight
        MountingType.CEILING -> libraryCeilingHeight(document, point, level)
    }
    require(height.isFinite() && height >= 0.0) { "Mounting height must be nonnegative." }
    var translation = Point3(point.x, point.y, floor + height)
    var rotation = Rotation3()
    var host: HostAttachment? = null
    if (item.mounting == MountingType.WALL) {
        val face = requireNotNull(nearestWallFace(document, translation, level?.id, item.defaultSize)) {
            "A wall-mounted item needs a visible wall on this level."
        }
        translation = face.origin
        rotation = Rotation3(z = atan2(-face.normalX, face.normalY))
        host = HostAttachment(face.host.id, HostMode.SURFACE, measurementToLocal(face.host.transform, translation))
    }
    val params = defaults + (LibraryParameterKeys.MOUNTING_HEIGHT to ParameterValue.Number(height, Quantity.LENGTH))
    val systemName = (params[LibraryParameterKeys.SYSTEM] as? ParameterValue.Text)?.value.orEmpty()
    val system = requireVisiblePlacementSystem(document, systemName)
    val materialName = (params[LibraryParameterKeys.MATERIAL] as? ParameterValue.Text)?.value.orEmpty()
    val colour = (params[LibraryParameterKeys.COLOUR] as? ParameterValue.Colour)?.argb ?: item.defaultColourArgb
    val element = Element(
        id = id,
        projectId = document.project.id,
        layerId = layer.id,
        levelId = level?.id,
        systemId = system?.id,
        materialId = item.materialId?.takeIf { id -> document.materials.any { it.id == id } }
            ?: document.materials.find { it.name.equals(materialName, ignoreCase = true) }?.id,
        name = (params[LibraryParameterKeys.NAME_TAG] as? ParameterValue.Text)?.value ?: item.name,
        body = ElementBody.Component(item.id, item.defaultSize, params, host),
        transform = Transform3(translation, rotation, Scale3(z = if (item.mounting == MountingType.CEILING) -1.0 else 1.0)),
        properties = mapOf(ELEMENT_COLOUR_KEY to ParameterValue.Colour(colour)),
    )
    return LibraryPlacement(element, item, height, host != null)
}

/** A valid placement must remain visible when its preview becomes a committed instance. */
internal fun requireVisiblePlacementSystem(document: ProjectDocument, name: String): BuildingSystem? {
    val system = name.takeIf { it.isNotBlank() }?.let { systemName ->
        document.systems.find { it.name.equals(systemName, ignoreCase = true) }
    }
    require(system?.visible != false) { "The ${system?.name} system is hidden. Show it before placing this item." }
    return system
}

internal fun placementLevel(document: ProjectDocument, levelId: LevelId?, z: Double): Level? {
    if (levelId != null) return requireNotNull(document.levels.find { it.id == levelId }) { "The selected level no longer exists." }
    return document.levels.filter { it.elevation <= z + 1e-8 }.maxByOrNull { it.elevation }
        ?: document.levels.minByOrNull { abs(it.elevation - z) }
}

/** Rooms can override the storey's structural ceiling; their footprint must contain the pick. */
internal fun libraryCeilingHeight(document: ProjectDocument, point: Point3, level: Level?): Double {
    val presentation = ScenePresentation(document)
    val room = document.elements.firstOrNull { element ->
        val body = element.body as? ElementBody.Room ?: return@firstOrNull false
        if (level != null && element.levelId != null && element.levelId != level.id) return@firstOrNull false
        if (!presentation.shown(presentation.display(element))) return@firstOrNull false
        val local = measurementToLocal(element.transform, point)
        contains(body.boundary.outer, local.x, local.y) && body.boundary.holes.none { contains(it, local.x, local.y) }
    }
    if (room != null) {
        val ceiling = measurementToWorld(room.transform, Point3(z = (room.body as ElementBody.Room).ceilingHeight)).z
        return ceiling - (level?.elevation ?: 0.0)
    }
    return level?.let { it.storeyHeight - it.slabThickness } ?: 3.0
}

private fun contains(ring: List<Point2>, x: Double, y: Double): Boolean {
    var inside = false
    for (index in ring.indices) {
        val a = ring[index]; val b = ring[(index + 1) % ring.size]
        if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) inside = !inside
    }
    return inside
}

private data class WallFace(val host: Element, val origin: Point3, val normalX: Double, val normalY: Double, val distance: Double)

private fun nearestWallFace(document: ProjectDocument, point: Point3, levelId: LevelId?, size: Size3): WallFace? {
    val presentation = ScenePresentation(document)
    var best: WallFace? = null
    fun candidate(host: Element, first: Point3, last: Point3, nx: Double, ny: Double, height: Double, twoSides: Boolean = false) {
        val base = measurementToWorld(host.transform, Point3()).z
        val top = measurementToWorld(host.transform, Point3(z = height)).z
        if (point.z < minOf(base, top) - 1e-8 || point.z + size.height > maxOf(base, top) + 1e-8) return
        val a = measurementToWorld(host.transform, first)
        val b = measurementToWorld(host.transform, last)
        val dx = b.x - a.x; val dy = b.y - a.y
        val length = hypot(dx, dy)
        // Upright wall faces have a stable horizontal mounting line. Degenerate/tilted
        // wall records cannot be used as a vertical mounting plane.
        if (length < size.width || abs(a.z - b.z) > 1e-7 || abs(host.transform.rotation.x) > 1e-8 || abs(host.transform.rotation.y) > 1e-8) return
        val localNormal = measurementToWorld(host.transform, Point3(nx, ny))
        val worldOrigin = measurementToWorld(host.transform, Point3())
        // The perpendicular is taken after the host transform, so nonuniform scaling
        // and mirrored walls still use the actual finish-plane normal.
        var normalX = -dy / length
        var normalY = dx / length
        if (normalX * (localNormal.x - worldOrigin.x) + normalY * (localNormal.y - worldOrigin.y) < 0.0) {
            normalX = -normalX; normalY = -normalY
        }
        val along = (((point.x - a.x) * dx + (point.y - a.y) * dy) / length)
            .coerceIn(size.width / 2, length - size.width / 2)
        val centreX = a.x + dx / length * along
        val centreY = a.y + dy / length * along
        if (twoSides && (point.x - centreX) * normalX + (point.y - centreY) * normalY < 0.0) {
            normalX = -normalX; normalY = -normalY
        }
        val distance = hypot(point.x - centreX, point.y - centreY)
        // Local X=(normalY,-normalX), making +Y point away from the wall volume.
        val origin = Point3(centreX - normalY * size.width / 2, centreY + normalX * size.width / 2, point.z)
        val face = WallFace(host, origin, normalX, normalY, distance)
        if (best == null || distance < best!!.distance - 1e-10) best = face
    }
    for (element in document.elements) {
        if (levelId != null && element.levelId != null && element.levelId != levelId) continue
        if (!presentation.shown(presentation.display(element))) continue
        when (val wall = element.body) {
            is ElementBody.Wall -> {
                val dx = wall.end.x - wall.start.x; val dy = wall.end.y - wall.start.y
                val length = hypot(dx, dy)
                val nx = -dy / length; val ny = dx / length
                val left = when (wall.reference) {
                    WallReference.CENTERLINE -> wall.thickness / 2
                    WallReference.LEFT_FINISH_FACE -> 0.0
                    WallReference.RIGHT_FINISH_FACE -> wall.thickness
                }
                for ((offset, sign) in listOf(left to 1.0, left - wall.thickness to -1.0)) {
                    candidate(element, Point3(wall.start.x + nx * offset, wall.start.y + ny * offset),
                        Point3(wall.end.x + nx * offset, wall.end.y + ny * offset), nx * sign, ny * sign, wall.height)
                }
            }
            is ElementBody.Room -> {
                for ((ringIndex, ring) in listOf<Int?>(null).map { it to wall.boundary.outer } +
                    wall.boundary.holes.mapIndexed { index, ring -> index to ring }) {
                    for (index in ring.indices) {
                        val side = RoomSideRef(element.id, index, ringIndex)
                        if (presentation.linkedWall(element, side) != null || !presentation.shown(presentation.sideDisplay(element, side))) continue
                        val a = ring[index]; val b = ring[(index + 1) % ring.size]
                        val length = hypot(b.x - a.x, b.y - a.y)
                        if (length < 1e-12) continue
                        candidate(element, Point3(a.x, a.y), Point3(b.x, b.y), -(b.y - a.y) / length,
                            (b.x - a.x) / length, wall.ceilingHeight, twoSides = true)
                    }
                }
            }
            else -> Unit
        }
    }
    return best
}
