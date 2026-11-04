package com.eezydraw.core.editing

import com.eezydraw.core.model.Element
import com.eezydraw.core.model.ElementBody
import com.eezydraw.core.model.ElementGroup
import com.eezydraw.core.model.ElementId
import com.eezydraw.core.model.HostAttachment
import com.eezydraw.core.model.Issue
import com.eezydraw.core.model.LayerId
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.ProjectDocument
import com.eezydraw.core.model.Shape
import com.eezydraw.core.model.measurementShape
import java.util.UUID

/** Transaction builders. Creating a command does not mutate the document or history. */
object EditCommands {
    /**
     * Diff a prepared transaction, including a newly created drawing layer. Only changed records
     * are retained; project/settings and all other document metadata stay outside drawing history.
     */
    fun between(before: ProjectDocument, after: ProjectDocument, label: String = "Edit drawing"): EditCommand {
        require(before.project.id == after.project.id) { "An edit cannot change project ownership" }
        require(before.copy(elements = after.elements, layers = after.layers, groups = after.groups, issues = after.issues) == after) {
            "Drawing edits can change only elements, layers, groups and issues"
        }
        val old = before.elements.withIndex().associateBy { it.value.id }
        val next = after.elements.withIndex().associateBy { it.value.id }
        require(old.size == before.elements.size && next.size == after.elements.size) { "Duplicate object IDs" }
        require(before.elements.filter { it.id in next }.map { it.id } == after.elements.filter { it.id in old }.map { it.id }) {
            "An edit cannot reorder existing objects"
        }
        val patches = ArrayList<ElementPatch>()
        for ((index, element) in before.elements.withIndex()) {
            val target = next[element.id]
            if (target?.value != element) {
                requireEditable(before, element)
                patches.add(ElementPatch(element.id, index, element, target?.index, target?.value))
            }
        }
        for ((index, element) in after.elements.withIndex()) {
            if (element.id !in old) patches.add(ElementPatch(element.id, null, null, index, element))
        }
        require(after.layers.map { it.id }.toSet().size == after.layers.size &&
            after.layers.all { it.projectId == after.project.id }
        ) { "Invalid drawing layers" }
        val layerIds = after.layers.mapTo(HashSet()) { it.id }
        require(after.elements.all { it.layerId in layerIds }) { "An object layer no longer exists" }
        val candidates = patches.mapNotNull { it.after }
        candidates.forEach { requireEditable(after, it) }
        require(candidates.none { element -> after.underlays.any { it.id == element.id } }) { "Object ID is used by an underlay" }
        val deleted = old.keys - next.keys
        validateReferences(after, after.elements, if (deleted.isEmpty()) candidates else after.elements)
        if (deleted.isNotEmpty()) {
            require(after.groups.none { group -> group.elementIds.any { it in deleted } } &&
                after.issues.none { issue ->
                    issue.elementIds.any { it in deleted } || issue.clash?.firstElementId in deleted ||
                        issue.clash?.secondElementId in deleted
                }
            ) { "Deleting objects must also remove their group and issue links" }
        }
        return EditCommand(
            label = label,
            projectId = before.project.id,
            patches = patches.toList(),
            beforeOrder = elementOrder(before.elements),
            afterOrder = elementOrder(after.elements),
            beforeCount = before.elements.size,
            afterCount = after.elements.size,
            layers = if (before.layers == after.layers) null else LinkPatch(before.layers, after.layers),
            groups = if (before.groups == after.groups) null else LinkPatch(before.groups, after.groups),
            issues = if (before.issues == after.issues) null else LinkPatch(before.issues, after.issues),
        )
    }

    fun add(document: ProjectDocument, element: Element, label: String = "Create object"): EditCommand =
        add(document, listOf(element), label)

    fun add(document: ProjectDocument, elements: List<Element>, label: String = "Create objects"): EditCommand =
        command(document, label, additions = elements)

    fun replace(document: ProjectDocument, elements: List<Element>, label: String = "Edit properties"): EditCommand {
        require(elements.map { it.id }.toSet().size == elements.size) { "Duplicate replacement object IDs" }
        val existing = document.elements.associateBy { it.id }
        for (element in elements) {
            val old = requireNotNull(existing[element.id]) { "The selected object no longer exists" }
            requireEditable(document, old)
        }
        return command(document, label, replacements = elements.associateBy { it.id })
    }

    fun transform(document: ProjectDocument, ids: Set<ElementId>, delta: EditDelta): EditCommand {
        val elements = selected(document, ids)
        if (delta.isIdentity) return command(document, "Transform objects")
        return replace(
            document,
            elements.map { it.copy(transform = EditTransforms.apply(it.transform, delta)) },
            "Transform objects",
        )
    }

    fun mirror(
        document: ProjectDocument,
        ids: Set<ElementId>,
        axis: MirrorAxis,
        pivot: Point3,
    ): EditCommand = replace(
        document,
        selected(document, ids).map { it.copy(transform = EditTransforms.mirror(it.transform, axis, pivot)) },
        "Mirror objects",
    )

    fun delete(document: ProjectDocument, ids: Set<ElementId>): EditCommand {
        selected(document, ids).forEach { requireEditable(document, it) }
        val replacements = LinkedHashMap<ElementId, Element?>()
        for (element in document.elements) {
            if (element.id in ids) {
                replacements[element.id] = null
            } else {
                val detached = detachMeasurementReferences(document, element, ids)
                val changed = detached.copy(body = detach(detached.body, ids))
                if (changed != element) {
                    requireEditable(document, element)
                    replacements[element.id] = changed
                }
            }
        }
        val groups = document.groups.map { group ->
            if (group.elementIds.any { it in ids }) group.copy(elementIds = group.elementIds - ids) else group
        }
        val issues = document.issues.map { issue ->
            val clash = issue.clash?.takeUnless { it.firstElementId in ids || it.secondElementId in ids }
            if (issue.elementIds.any { it in ids } || clash != issue.clash) {
                issue.copy(elementIds = issue.elementIds - ids, clash = clash)
            } else issue
        }
        return command(document, "Delete objects", replacements, groups = groups, issues = issues)
    }

    fun paste(
        document: ProjectDocument,
        clipboard: List<Element>,
        offset: Point3 = Point3(),
        layerId: LayerId? = null,
    ): EditCommand {
        require(clipboard.all { it.projectId == document.project.id }) { "Clipboard objects belong to another project" }
        require(clipboard.map { it.id }.toSet().size == clipboard.size) { "Duplicate clipboard object IDs" }
        return command(document, "Paste objects", additions = clones(document, clipboard, offset, layerId))
    }

    /** Originals occupy cell (0,0); all remaining cells clone the complete selected set. */
    fun array(
        document: ProjectDocument,
        ids: Set<ElementId>,
        columns: Int,
        rows: Int,
        dx: Double,
        dy: Double,
    ): EditCommand {
        require(columns > 0 && rows > 0) { "Array rows and columns must be positive" }
        require(dx.isFinite() && dy.isFinite()) { "Array spacing must be finite" }
        val elements = selected(document, ids)
        elements.forEach { requireEditable(document, it) }
        val cells = columns.toLong() * rows.toLong()
        require(elements.isEmpty() || cells - 1L <= MAX_ARRAY_ADDITIONS / elements.size) {
            "An array can create at most 1,000 objects"
        }
        if (elements.isEmpty()) return command(document, "Array objects")
        val additions = ArrayList<Element>(((cells - 1L) * elements.size).toInt())
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                if (column != 0 || row != 0) {
                    additions.addAll(clones(document, elements, Point3(column * dx, row * dy), null))
                }
            }
        }
        return command(document, "Array objects", additions = additions)
    }

    private fun clones(
        document: ProjectDocument,
        elements: List<Element>,
        offset: Point3,
        layerId: LayerId?,
    ): List<Element> {
        val ids = elements.associate { it.id to ElementId(UUID.randomUUID().toString()) }
        return elements.map { element ->
            val t = element.transform.translation
            element.copy(
                id = ids.getValue(element.id),
                projectId = document.project.id,
                layerId = layerId ?: element.layerId,
                transform = element.transform.copy(translation = Point3(t.x + offset.x, t.y + offset.y, t.z + offset.z)),
                body = remap(element.body, ids),
            )
        }
    }

    private fun remap(body: ElementBody, ids: Map<ElementId, ElementId>): ElementBody {
        fun host(host: HostAttachment?): HostAttachment? = host?.let {
            it.copy(hostElementId = ids[it.hostElementId] ?: it.hostElementId)
        }
        return when (body) {
            is ElementBody.DrawingShape -> {
                val dimension = body.shape as? Shape.Dimension
                val measurement = dimension?.measurement
                if (dimension == null || measurement == null) body else body.copy(shape = dimension.copy(
                    measurement = measurement.copy(anchors = measurement.anchors.map { anchor ->
                        anchor.copy(elementId = ids[anchor.elementId] ?: anchor.elementId)
                    }),
                ))
            }
            is ElementBody.Room -> body.copy(wallFaces = body.wallFaces.map { it.copy(wallId = ids[it.wallId] ?: it.wallId) })
            is ElementBody.Component -> body.copy(host = host(body.host))
            is ElementBody.Route -> body.copy(host = host(body.host))
            else -> body
        }
    }

    private fun detach(body: ElementBody, ids: Set<ElementId>): ElementBody = when (body) {
        is ElementBody.Room -> if (body.wallFaces.any { it.wallId in ids }) {
            body.copy(wallFaces = body.wallFaces.filterNot { it.wallId in ids })
        } else body
        is ElementBody.Component -> if (body.host?.hostElementId in ids) body.copy(host = null) else body
        is ElementBody.Route -> if (body.host?.hostElementId in ids) body.copy(host = null) else body
        else -> body
    }

    private fun selected(document: ProjectDocument, ids: Set<ElementId>): List<Element> {
        val selected = document.elements.filter { it.id in ids }
        require(selected.size == ids.size) { "A selected object no longer exists" }
        return selected
    }

    private fun requireEditable(document: ProjectDocument, element: Element) {
        require(element.projectId == document.project.id) { "Object belongs to another project" }
        val layer = requireNotNull(document.layers.find { it.id == element.layerId }) { "Object layer no longer exists" }
        require(!layer.locked) { "The ${layer.name} layer is locked" }
    }

    private fun command(
        document: ProjectDocument,
        label: String,
        replacements: Map<ElementId, Element?> = emptyMap(),
        additions: List<Element> = emptyList(),
        groups: List<ElementGroup> = document.groups,
        issues: List<Issue> = document.issues,
    ): EditCommand {
        val originalIds = document.elements.mapTo(HashSet()) { it.id }
        require(replacements.keys.all { it in originalIds }) { "The selected object no longer exists" }
        val newIds = additions.mapTo(HashSet()) { it.id }
        require(newIds.size == additions.size && newIds.none { it in originalIds }) { "Object ID already exists" }
        require(newIds.none { id -> document.underlays.any { it.id == id } }) { "Object ID is used by an underlay" }
        val after = ArrayList<Element>(document.elements.size + additions.size)
        document.elements.forEach { old ->
            val changed = replacements.containsKey(old.id)
            val next = if (changed) replacements[old.id] else old
            if (next != null) require(next.id == old.id) { "Replacement must retain object ID" }
            if (next != null) after.add(next)
        }
        for (element in additions) {
            after.add(element)
        }
        return between(document, document.copy(elements = after, groups = groups, issues = issues), label)
    }

    private fun validateReferences(document: ProjectDocument, all: List<Element>, changed: List<Element>) {
        if (changed.isEmpty()) return
        val elements = all.associateBy { it.id }
        val levels = document.levels.mapTo(HashSet()) { it.id }
        val systems = document.systems.mapTo(HashSet()) { it.id }
        val materials = document.materials.mapTo(HashSet()) { it.id }
        val library = document.libraryItems.mapTo(HashSet()) { it.id }
        fun host(host: HostAttachment?, owner: ElementId) {
            require(host == null || host.hostElementId != owner && host.hostElementId in elements) {
                "The host object no longer exists"
            }
        }
        for (element in changed) {
            require(element.levelId == null || element.levelId in levels) { "Object level no longer exists" }
            require(element.systemId == null || element.systemId in systems) { "Object system no longer exists" }
            require(element.materialId == null || element.materialId in materials) { "Object material no longer exists" }
            when (val body = element.body) {
                is ElementBody.DrawingShape -> {
                    val data = measurementShape(element)?.measurement
                    require(data == null || data.anchors.all { it.elementId == null || it.elementId != element.id && it.elementId in elements }) {
                        "A measurement reference object no longer exists"
                    }
                    require(data?.floorLevelId == null || data.floorLevelId in levels) { "The measurement floor no longer exists" }
                }
                is ElementBody.Room -> require(body.wallFaces.all { elements[it.wallId]?.body is ElementBody.Wall }) {
                    "A referenced wall no longer exists"
                }
                is ElementBody.Component -> {
                    require(body.libraryItemId in library) { "Component library item no longer exists" }
                    host(body.host, element.id)
                }
                is ElementBody.Route -> {
                    require(body.fittings.all { it.libraryItemId == null || it.libraryItemId in library }) {
                        "Route fitting library item no longer exists"
                    }
                    host(body.host, element.id)
                }
                else -> Unit
            }
        }
        // A replacement may attach a host to one of its descendants. Check only affected chains.
        val checkedHostChains = HashSet<ElementId>()
        for (element in changed) {
            val visited = HashSet<ElementId>()
            var cursor: Element? = element
            while (cursor != null && cursor.id !in checkedHostChains) {
                require(visited.add(cursor.id)) { "Host attachments cannot form a cycle" }
                val hostId = when (val body = cursor.body) {
                    is ElementBody.Component -> body.host?.hostElementId
                    is ElementBody.Route -> body.host?.hostElementId
                    else -> null
                }
                cursor = elements[hostId]
            }
            checkedHostChains.addAll(visited)
        }
    }

    private const val MAX_ARRAY_ADDITIONS = 1_000L
}
