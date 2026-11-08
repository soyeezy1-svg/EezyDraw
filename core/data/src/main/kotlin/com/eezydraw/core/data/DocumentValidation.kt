package com.eezydraw.core.data

import com.eezydraw.core.model.*

/** Structural import/storage validation; geometry topology and coordination remain separate services. */
internal fun validateDocument(document: ProjectDocument) {
    fun valid(condition: Boolean, message: String) {
        if (!condition) throw ProjectStorageException(message)
    }
    fun <T> unique(values: List<T>, label: String): Set<T> {
        valid(values.size == values.toSet().size, "The project has duplicate $label identifiers.")
        return values.toSet()
    }
    val owner = document.project.id
    valid(document.project.schemaVersion == 1, "This project version is not supported.")
    valid(document.project.name.trim().isNotEmpty(), "The project name is empty.")
    valid(document.project.name.length <= 120, "The project name is too long.")
    valid(document.project.name.none { it.code < 32 || it.code == 127 },
        "The project name contains control characters.")
    val levels = unique(document.levels.map { it.id }, "level")
    val layers = unique(document.layers.map { it.id }, "layer")
    val systems = unique(document.systems.map { it.id }, "system")
    val materials = unique(document.materials.map { it.id }, "material")
    val libraryItems = unique(document.libraryItems.map { it.id }, "library item")
    // Browsing IDs may refer to the separate device catalogue, including after geometry undo.
    // Component definitions below remain mandatory project-owned records.
    valid(document.libraryState.favoriteIds.size <= 10_000, "Too many library favorites.")
    valid(document.libraryState.recentIds.size <= 24 &&
        document.libraryState.recentIds.distinct().size == document.libraryState.recentIds.size,
        "The recently used library items are invalid.")
    val elementIds = unique(document.elements.map { it.id } + document.underlays.map { it.id }, "element")
    val elements = document.elements.associateBy { it.id }
    val wallFaces=wallFrames(document).mapTo(hashSetOf()){it.host.id to it.roomSide}
    val views = unique(document.views.map { it.id }, "view")
    unique(document.groups.map { it.id }, "group")
    unique(document.issues.map { it.id }, "issue")
    unique(document.sheets.map { it.id }, "sheet")
    val revisions = unique(document.revisions.map { it.id }, "revision")
    val assets = unique(document.assets.map { it.id }, "asset")
    unique(document.assets.map { it.relativePath }, "asset path")

    val owners = document.levels.map { it.projectId } + document.layers.map { it.projectId } +
        document.systems.map { it.projectId } + document.elements.map { it.projectId } +
        document.underlays.map { it.projectId } + document.views.map { it.projectId } +
        document.groups.map { it.projectId } + document.issues.map { it.projectId } +
        document.sheets.map { it.projectId } + document.revisions.map { it.projectId } +
        document.assets.map { it.projectId }
    valid(owners.all { it == owner }, "The project contains records belonging to another project.")
    valid(document.project.thumbnailAssetId == null || document.project.thumbnailAssetId in assets,
        "The project thumbnail asset is missing.")

    document.elements.forEach { element ->
        valid(element.layerId in layers, "An element refers to a missing layer.")
        valid(element.levelId == null || element.levelId in levels, "An element refers to a missing level.")
        valid(element.systemId == null || element.systemId in systems, "An element refers to a missing system.")
        valid(element.materialId == null || element.materialId in materials, "An element refers to a missing material.")
        when (val body = element.body) {
            is ElementBody.DrawingShape -> {
                val data = measurementShape(element)?.measurement
                valid(data == null || data.anchors.all { anchor ->
                    anchor.elementId == null || anchor.elementId != element.id && anchor.elementId in elements
                }, "A measurement refers to a missing object.")
                valid(data?.floorLevelId == null || data.floorLevelId in levels,
                    "A measurement refers to a missing floor level.")
            }
            is ElementBody.Component -> {
                valid(body.wallService?.let {mount->(mount.wallId to mount.roomSide) in wallFaces} ?: true,"A wall service host is missing.")
                valid(body.libraryItemId in libraryItems, "A component definition is missing.")
                val host = body.host
                valid(host == null || host.hostElementId in elements, "A component host is missing.")
            }
            is ElementBody.Route -> {
                valid(body.wallService?.let {mount->(mount.wallId to mount.roomSide) in wallFaces} ?: true,"A wall route host is missing.")
                val host = body.host
                valid(host == null || host.hostElementId in elements, "A route host is missing.")
                valid(listOfNotNull(body.routing?.startAnchor,body.routing?.endAnchor).all {it.elementId in elements && it.elementId!=element.id},"A routing connection is missing.")
                valid(body.fittings.all { it.libraryItemId == null || it.libraryItemId in libraryItems },
                    "A fitting definition is missing.")
            }
            is ElementBody.Room -> valid(body.wallFaces.all { elements[it.wallId]?.body is ElementBody.Wall },
                "A room refers to a missing wall.")
            else -> Unit
        }
    }
    document.libraryItems.forEach {
        valid(it.materialId == null || it.materialId in materials, "A library material is missing.")
    }
    document.underlays.forEach {
        valid(it.assetId in assets && it.layerId in layers && (it.levelId == null || it.levelId in levels),
            "An underlay refers to missing project data.")
    }
    document.views.forEach {
        valid((it.levelId == null || it.levelId in levels) && it.hiddenLayerIds.all(layers::contains) &&
            it.hiddenSystemIds.all(systems::contains), "A saved view refers to missing project data.")
    }
    document.groups.forEach {
        valid(it.elementIds.all(elementIds::contains), "A group refers to a missing element.")
    }
    document.issues.forEach { issue ->
        valid((issue.attachments+listOfNotNull(issue.screenshot,issue.capture?.screenshot)+issue.savedViews.mapNotNull {it.screenshot}).all {id->document.assets.any {it.id==id}}, "An issue attachment is missing.")
        valid(issue.elementIds.all(elementIds::contains) && (issue.viewId == null || issue.viewId in views),
            "An issue refers to missing project data.")
        issue.clash?.let {
            valid(it.checkKey!=null && it.status==ClashStatus.RESOLVED || it.firstElementId in elementIds && it.secondElementId in elementIds,
                "A clash refers to a missing element.")
        }
        unique(issue.comments.map { it.id }, "comment")
    }
    document.sheets.forEach {
        valid(it.viewports.all { viewport -> viewport.viewId in views }, "A sheet refers to a missing view.")
    }
    document.revisions.forEach {
        valid((it.parentId == null || it.parentId in revisions) && it.snapshotAssetId in assets,
            "A revision refers to missing project data.")
    }
    document.assets.forEach {
        valid(isSafeAssetPath(it.relativePath), "The project contains an unsafe asset path.")
        val checksum = it.sha256
        valid(checksum == null || checksum.matches(Regex("[a-fA-F0-9]{64}")),
            "An asset checksum is invalid.")
    }
    validateParentChains(document.elements.associate { element ->
        element.id to when (val body = element.body) {
            is ElementBody.Component -> body.host?.hostElementId
            is ElementBody.Route -> body.host?.hostElementId
            else -> null
        }
    }, "The project contains a cycle of hosted elements.")
    validateParentChains(document.revisions.associate { it.id to it.parentId },
        "The project contains a revision cycle.")
}

internal fun isSafeAssetPath(path: String): Boolean = path.isNotBlank() && path.length <= 1024 &&
    path.none { it == '\\' || it == ':' || it.code < 32 || it.code == 127 } &&
    path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

private fun <T> validateParentChains(parents: Map<T, T?>, error: String) {
    val complete = mutableSetOf<T>()
    for (start in parents.keys) {
        var current: T? = start
        val chain = mutableSetOf<T>()
        while (current != null && current !in complete) {
            if (!chain.add(current)) throw ProjectStorageException(error)
            current = parents[current]
        }
        complete.addAll(chain)
    }
}
