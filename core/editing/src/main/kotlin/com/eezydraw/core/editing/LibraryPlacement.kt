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
    /** The input centreline remains the pointer target even when geometry starts beside it. */
    val connectionPoint: Point3? = null,
    val connectedRouteId: ElementId? = null,
    val connectionSection: RouteSection? = null,
    val wallPoint:Point3?=null,
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
    wallOptions:WallServiceOptions? = null,
): LibraryPlacement {
    val level = placementLevel(document, levelId, point.z)
    require(level == null || level.id !in document.project.settings.hiddenLevelIds) {
        "The selected level is hidden. Show it before placing this item."
    }
    val layer = if (layerId == null) document.layers.firstOrNull { it.visible && !it.locked }
        else document.layers.find { it.id == layerId && it.visible && !it.locked }
    requireNotNull(layer) { "Choose a visible, unlocked layer to place this item." }
    previewFittingRoutePlacement(document, item, point, level, layer, id)?.let { placement ->
        val carried = libraryPlacementDefinition(document, placement.item)
        return placement.copy(item = carried, element = placement.element.copy(
            body = (placement.element.body as ElementBody.Component).copy(libraryItemId = carried.id)))
    }
    val floor = level?.elevation ?: 0.0
    val defaults = item.defaultParameters()
    val requestedHeight = (defaults[LibraryParameterKeys.MOUNTING_HEIGHT] as? ParameterValue.Number)?.value
        ?: item.defaultMountingHeight
    val height = when (item.mounting) {
        MountingType.FREE -> point.z - floor
        MountingType.FLOOR -> 0.0
        MountingType.WALL -> requestedHeight
        MountingType.CEILING -> libraryCeilingHeight(document, point, level)
    }
    require(height.isFinite() && (item.mounting == MountingType.FREE || height >= 0.0)) { "Mounting height must be nonnegative." }
    var translation = Point3(point.x, point.y, floor + height)
    var rotation = Rotation3()
    var host: HostAttachment? = null
    val params = defaults + (LibraryParameterKeys.MOUNTING_HEIGHT to ParameterValue.Number(height, Quantity.LENGTH))
    val systemName = (params[LibraryParameterKeys.SYSTEM] as? ParameterValue.Text)?.value.orEmpty()
    val system = requireVisiblePlacementSystem(document, systemName)
    val materialName = (params[LibraryParameterKeys.MATERIAL] as? ParameterValue.Text)?.value.orEmpty()
    val colour = (params[LibraryParameterKeys.COLOUR] as? ParameterValue.Colour)?.argb ?: item.defaultColourArgb
    var element = Element(
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
    if(item.mounting==MountingType.WALL)element=WallServices.attach(if(document.libraryItems.any {it.id==item.id})document else document.copy(libraryItems=document.libraryItems+item),element,wallOptions ?: defaultWallDeviceOptions(item),aim=translation.copy(z=translation.z+item.defaultSize.height/2))
    if(item.ceiling || item.id.value=="eezy.architecture.ceiling")element=element.copy(properties=element.properties+(CEILING_OBJECT to ParameterValue.Flag(true)))
    host=(element.body as ElementBody.Component).host
    if(floorDevice(item)!=null)element=FloorServices.place(document,item,element)
    val connection = libraryFittingConnectionPoint(item, element)
    if (connection != null) {
        val connectionHeight = connection.z - floor
        val connectedElement = element.copy(body = (element.body as ElementBody.Component).copy(parameters =
            params + (LibraryParameterKeys.MOUNTING_HEIGHT to ParameterValue.Number(connectionHeight, Quantity.LENGTH))))
        return LibraryPlacement(connectedElement, item, connectionHeight, host != null,
            connectionPoint = connection, connectionSection = item.fitting?.section)
    }
    val wallPoint=element.wallService()?.let {m->wallFrames(document).find {it.host.id==m.wallId && it.roomSide==m.roomSide}?.let {f->
        val b=element.body as ElementBody.Component
        val q=f.coordinates(measurementToWorld(element.transform,Point3(b.size.width/2,0.0)))
        f.world(q.x,f.faceDepth(m.side),q.z)
    }}
    return LibraryPlacement(element, item, height, host != null,wallPoint=wallPoint)
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
internal fun libraryCeilingHeight(document: ProjectDocument, point: Point3, level: Level?): Double =
    ceilingElevation(document,point,level)-(level?.elevation ?: 0.0)
