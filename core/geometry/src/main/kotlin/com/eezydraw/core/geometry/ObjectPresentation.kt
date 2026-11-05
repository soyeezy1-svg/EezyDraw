package com.eezydraw.core.geometry

import com.eezydraw.core.model.*

/** Visibility is presentation only. The underlying element bodies and measurement anchors stay intact. */
class ScenePresentation(private val document: ProjectDocument) {
    private val layers = document.layers.associateBy { it.id }
    private val systems = document.systems.associateBy { it.id }
    private val elements = document.elements.associateBy { it.id }
    private val rooms = document.elements.filter { it.body is ElementBody.Room }.associateBy { it.id }
    private val wallLinks: Map<ElementId,List<RoomSideRef>> = buildMap<ElementId,MutableList<RoomSideRef>> {
        for(room in rooms.values)for(face in (room.body as ElementBody.Room).wallFaces)
            getOrPut(face.wallId){ArrayList()}.add(RoomSideRef(room.id,face.edgeIndex,face.ringIndex))
    }.mapValues {it.value.toList()}

    private fun hierarchyHidden(element: Element) = layers[element.layerId]?.visible == false ||
        element.systemId?.let { systems[it]?.visible == false } == true ||
        element.levelId in document.project.settings.hiddenLevelIds

    fun display(element: Element): ObjectDisplay {
        if (hierarchyHidden(element) || element.objectDisplay == ObjectDisplay.HIDDEN) return ObjectDisplay.HIDDEN
        if (element.body !is ElementBody.Wall) return element.objectDisplay
        val links=wallLinks[element.id].orEmpty()
        val visibleOwners=links.mapNotNull {ref->rooms[ref.roomId]?.takeIf {!hierarchyHidden(it) && it.objectDisplay!=ObjectDisplay.HIDDEN}?.let {it to ref}}
        if(links.isNotEmpty() && visibleOwners.isEmpty())return ObjectDisplay.HIDDEN
        val modes = visibleOwners.map { (room,ref)->sideDisplay(room,ref) }
        if (ObjectDisplay.HIDDEN in modes) return ObjectDisplay.HIDDEN
        if (element.objectDisplay != ObjectDisplay.NORMAL) return element.objectDisplay
        return listOf(ObjectDisplay.HIDDEN, ObjectDisplay.XRAY, ObjectDisplay.WIREFRAME)
            .firstOrNull { it in modes } ?: ObjectDisplay.NORMAL
    }

    fun sideDisplay(room: Element, side: RoomSideRef): ObjectDisplay {
        if (hierarchyHidden(room) || room.objectDisplay == ObjectDisplay.HIDDEN) return ObjectDisplay.HIDDEN
        return if (room.hasRoomSideDisplay(side)) room.roomSideDisplay(side) else room.objectDisplay
    }

    fun shown(display: ObjectDisplay): Boolean = display != ObjectDisplay.HIDDEN || document.project.settings.showHiddenObjects

    fun linkedWall(room: Element, side: RoomSideRef): Element? = (room.body as? ElementBody.Room)?.wallFaces
        ?.firstOrNull { it.edgeIndex == side.edgeIndex && it.ringIndex == side.ringIndex }
        ?.wallId?.let(elements::get)?.takeIf { it.body is ElementBody.Wall }

    fun wallSide(element: Element): RoomSideRef? = wallLinks[element.id]?.let {links->
        links.firstOrNull {ref->rooms[ref.roomId]?.let {!hierarchyHidden(it) && it.objectDisplay!=ObjectDisplay.HIDDEN}==true} ?: links.firstOrNull()
    }
    fun roomCenter(element: Element): Point3? {
        val room = if (element.body is ElementBody.Room) element else wallSide(element)?.roomId?.let(rooms::get) ?: return null
        val body = room.body as ElementBody.Room
        val points = body.boundary.outer
        val center = Point3((points.minOf { it.x } + points.maxOf { it.x }) / 2,
            (points.minOf { it.y } + points.maxOf { it.y }) / 2, body.ceilingHeight / 2)
        return measurementToWorld(room.transform, center)
    }
}

/** Uses the exact same hierarchy and object display rules as scene construction and picking. */
fun elementShown(document: ProjectDocument, element: Element): Boolean =
    ScenePresentation(document).let { it.shown(it.display(element)) }

/** Physical dimensions/reference positions must not shrink because a wall was hidden or wireframed. */
fun unfilteredGeometryDocument(document: ProjectDocument): ProjectDocument = document.copy(
    project = document.project.copy(settings = document.project.settings.copy(showHiddenObjects = true, hiddenLevelIds = emptySet(), showMeasurements = true)),
    layers = document.layers.map { it.copy(visible = true) },
    systems = document.systems.map { it.copy(visible = true) },
    elements = document.elements.map { it.withDisplay(ObjectDisplay.NORMAL).withAllRoomSidesNormal() },
)
