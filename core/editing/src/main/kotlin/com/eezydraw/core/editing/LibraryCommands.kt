package com.eezydraw.core.editing

import com.eezydraw.core.model.*
import java.util.UUID
import kotlin.math.abs

/** Physical dimensions are measured along local axes, independently of rotation/mirroring. */
data class LibraryInstanceProperties(
    val size: Size3,
    val mountingHeight: Double,
    val material: String,
    val colourArgb: Long,
    val system: String,
    val manufacturer: String,
    val model: String,
    val nameTag: String,
) {
    init {
        require(mountingHeight.isFinite() && mountingHeight >= 0.0) { "Mounting height must be nonnegative." }
        require(colourArgb in 0..0xFFFFFFFFL) { "Colour must be an ARGB value." }
    }
}

fun readLibraryProperties(document: ProjectDocument, element: Element): LibraryInstanceProperties {
    val body = requireNotNull(element.body as? ElementBody.Component) { "Select a library item." }
    val item = requireNotNull(document.libraryItems.find { it.id == body.libraryItemId }) { "The library definition no longer exists." }
    val params = item.defaultParameters() + body.parameters
    fun text(key: String): String = (params[key] as? ParameterValue.Text)?.value.orEmpty()
    val floor = placementLevel(document, element.levelId, element.transform.translation.z)?.elevation ?: 0.0
    val scale = element.transform.scale
    val system = element.systemId?.let { id -> document.systems.find { it.id == id } }
    val material = (element.materialId ?: item.materialId)?.let { id -> document.materials.find { it.id == id } }
    val layer = document.layers.find { it.id == element.layerId }
    return LibraryInstanceProperties(
        size = Size3(body.size.width * abs(scale.x), body.size.depth * abs(scale.y), body.size.height * abs(scale.z)),
        mountingHeight = (element.transform.translation.z - floor).coerceAtLeast(0.0),
        material = material?.name ?: text(LibraryParameterKeys.MATERIAL),
        colourArgb = element.colourOverride ?: system?.colourArgb ?: material?.colourArgb ?: layer?.colourArgb
            ?: (params[LibraryParameterKeys.COLOUR] as? ParameterValue.Colour)?.argb
            ?: item.defaultColourArgb,
        system = system?.name ?: text(LibraryParameterKeys.SYSTEM),
        manufacturer = text(LibraryParameterKeys.MANUFACTURER),
        model = text(LibraryParameterKeys.MODEL),
        nameTag = (body.parameters[LibraryParameterKeys.NAME_TAG] as? ParameterValue.Text)?.value ?: element.name.ifBlank { item.name },
    )
}

/**
 * Return an instance edit only. Dimensions are baked into the component size while signed
 * transform scales retain handedness. The library definition is never modified.
 */
fun applyLibraryProperties(document: ProjectDocument, element: Element, properties: LibraryInstanceProperties): Element {
    val body = requireNotNull(element.body as? ElementBody.Component) { "Select a library item." }
    val floor = placementLevel(document, element.levelId, element.transform.translation.z)?.elevation ?: 0.0
    val scale = element.transform.scale
    val changed = element.copy(
        name = properties.nameTag,
        body = body.copy(
            size = properties.size,
            parameters = body.parameters + mapOf(
                LibraryParameterKeys.MOUNTING_HEIGHT to ParameterValue.Number(properties.mountingHeight, Quantity.LENGTH),
                LibraryParameterKeys.MATERIAL to ParameterValue.Text(properties.material),
                LibraryParameterKeys.COLOUR to ParameterValue.Colour(properties.colourArgb),
                LibraryParameterKeys.SYSTEM to ParameterValue.Text(properties.system),
                LibraryParameterKeys.MANUFACTURER to ParameterValue.Text(properties.manufacturer),
                LibraryParameterKeys.MODEL to ParameterValue.Text(properties.model),
                LibraryParameterKeys.NAME_TAG to ParameterValue.Text(properties.nameTag),
            ),
        ),
        transform = element.transform.copy(
            translation = element.transform.translation.copy(z = floor + properties.mountingHeight),
            scale = Scale3(if (scale.x < 0) -1.0 else 1.0, if (scale.y < 0) -1.0 else 1.0, if (scale.z < 0) -1.0 else 1.0),
        ),
        systemId = document.systems.find { it.name.equals(properties.system, ignoreCase = true) }?.id,
        materialId = document.materials.find { it.name.equals(properties.material, ignoreCase = true) }?.id,
        properties = element.properties + (ELEMENT_COLOUR_KEY to ParameterValue.Colour(properties.colourArgb)),
    )
    return synchronizeHostPosition(document, changed)
}

private fun synchronizeHostPosition(document: ProjectDocument, element: Element): Element {
    val body = element.body as ElementBody.Component
    val attachment = body.host ?: return element
    val host = document.elements.find { it.id == attachment.hostElementId } ?: return element
    return element.copy(body = body.copy(host = attachment.copy(
        localPosition = measurementToLocal(host.transform, element.transform.translation),
    )))
}

/** Library commands use the same sparse lock-aware transactions as ordinary drawing tools. */
object LibraryCommands {
    fun place(document: ProjectDocument, placement: LibraryPlacement): EditCommand {
        require(placement.element.projectId == document.project.id) { "This placement belongs to another project." }
        require(document.elements.none { it.id == placement.element.id }) { "Object ID already exists." }
        val existing = document.libraryItems.find { it.id == placement.item.id }
        require(existing == null || existing == placement.item) { "The library definition changed; choose the item again." }
        var prepared = if (existing == null) document.copy(libraryItems = document.libraryItems + placement.item) else document
        val properties = readLibraryProperties(prepared, placement.element)
        requireVisiblePlacementSystem(prepared, properties.system)
        val associated = associate(prepared, placement.element, properties)
        prepared = associated.first
        return EditCommands.between(document, prepared.copy(elements = prepared.elements + associated.second), "Place ${placement.item.name}")
    }

    /**
     * Update all copies parameters and local size, preserving each insertion X/Y and rotation.
     * Mounting height is resolved against each instance's own floor. A locked matching
     * instance rejects the entire operation, so a bulk edit is never partially applied.
     */
    fun edit(
        document: ProjectDocument,
        id: ElementId,
        properties: LibraryInstanceProperties,
        transform: ElementProperties? = null,
        updateAll: Boolean = false,
    ): EditCommand {
        val selected = requireNotNull(document.elements.find { it.id == id }) { "The selected object no longer exists." }
        val body = requireNotNull(selected.body as? ElementBody.Component) { "Select a library item." }
        val previous = readLibraryProperties(document, selected)
        var prepared = document
        val replacements = document.elements.map { element ->
            if (element.id != id && (!updateAll || (element.body as? ElementBody.Component)?.libraryItemId != body.libraryItemId)) return@map element
            var changed = applyLibraryProperties(prepared, element, properties)
            if (element.id == id && transform != null) {
                val mountingChanged = abs(properties.mountingHeight - previous.mountingHeight) > 1e-10
                val position = if (mountingChanged) transform.position.copy(z = changed.transform.translation.z) else transform.position
                changed = changed.copy(transform = changed.transform.copy(translation = position, rotation = transform.rotation))
                val floor = placementLevel(document, selected.levelId, position.z)?.elevation ?: 0.0
                changed = changed.copy(body = (changed.body as ElementBody.Component).copy(parameters =
                    (changed.body as ElementBody.Component).parameters +
                        (LibraryParameterKeys.MOUNTING_HEIGHT to ParameterValue.Number(position.z - floor, Quantity.LENGTH))))
            }
            val associated = associate(prepared, synchronizeHostPosition(prepared, changed), properties)
            prepared = associated.first
            associated.second
        }
        return EditCommands.between(document, prepared.copy(elements = replacements),
            if (updateAll) "Update all of this type" else "Edit library item")
    }

    fun reset(document: ProjectDocument, id: ElementId): EditCommand {
        val element = requireNotNull(document.elements.find { it.id == id }) { "The selected object no longer exists." }
        val body = requireNotNull(element.body as? ElementBody.Component) { "Select a library item." }
        val item = requireNotNull(document.libraryItems.find { it.id == body.libraryItemId }) { "The library definition no longer exists." }
        val defaults = item.defaultParameters()
        fun text(key: String) = (defaults[key] as? ParameterValue.Text)?.value.orEmpty()
        val level = placementLevel(document, element.levelId, element.transform.translation.z)
        val height = if (element.lockMode == LockMode.POSITION_ONLY) readLibraryProperties(document, element).mountingHeight else when (item.mounting) {
            MountingType.FLOOR -> 0.0
            MountingType.CEILING -> libraryCeilingHeight(document, element.transform.translation, level)
            MountingType.WALL -> (defaults[LibraryParameterKeys.MOUNTING_HEIGHT] as? ParameterValue.Number)?.value ?: item.defaultMountingHeight
        }
        val parameters = LibraryInstanceProperties(
            item.defaultSize, height, text(LibraryParameterKeys.MATERIAL).ifBlank {
                item.materialId?.let { id -> document.materials.find { it.id == id }?.name }.orEmpty()
            },
            (defaults[LibraryParameterKeys.COLOUR] as? ParameterValue.Colour)?.argb ?: item.defaultColourArgb,
            text(LibraryParameterKeys.SYSTEM), text(LibraryParameterKeys.MANUFACTURER),
            text(LibraryParameterKeys.MODEL), text(LibraryParameterKeys.NAME_TAG),
        )
        val reset = edit(document, id, parameters).preview(document)
        val result = reset.copy(elements = reset.elements.map { current ->
            if (current.id != id) current else current.copy(body = (current.body as ElementBody.Component).copy(
                parameters = defaults + (LibraryParameterKeys.MOUNTING_HEIGHT to ParameterValue.Number(height, Quantity.LENGTH)),
            ))
        })
        return EditCommands.between(document, result, "Reset to library default")
    }

    /** Associate editable names with real project records; their additions share the same undo. */
    private fun associate(document: ProjectDocument, element: Element, properties: LibraryInstanceProperties): Pair<ProjectDocument, Element> {
        var prepared = document
        val definition = requireNotNull(document.libraryItems.find { it.id == (element.body as ElementBody.Component).libraryItemId })
        val system = properties.system.takeIf { it.isNotBlank() }?.let { name ->
            prepared.systems.find { it.name.equals(name, ignoreCase = true) } ?: BuildingSystem(
                SystemId(UUID.randomUUID().toString()), document.project.id, name, definition.discipline, properties.colourArgb,
            ).also { prepared = prepared.copy(systems = prepared.systems + it) }
        }
        val material = properties.material.takeIf { it.isNotBlank() }?.let { name ->
            prepared.materials.find { it.name.equals(name, ignoreCase = true) } ?: Material(
                MaterialId(UUID.randomUUID().toString()), name, properties.colourArgb,
            ).also { prepared = prepared.copy(materials = prepared.materials + it) }
        }
        return prepared to element.copy(systemId = system?.id, materialId = material?.id)
    }
}
