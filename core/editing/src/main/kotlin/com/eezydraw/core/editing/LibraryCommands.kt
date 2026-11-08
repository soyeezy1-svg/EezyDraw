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
    val section: RouteSection? = null,
    val outletSection: RouteSection? = null,
    val connectionProfileRepresentable: Boolean = true,
    val freeMounting: Boolean = false,
) {
    init {
        require(mountingHeight.isFinite()) { "Mounting height must be finite." }
        require(freeMounting || section != null || mountingHeight >= 0.0) { "Mounting height must be nonnegative." }
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
    val connection = effectiveFittingSections(item, element)
    return LibraryInstanceProperties(
        size = Size3(body.size.width * abs(scale.x), body.size.depth * abs(scale.y), body.size.height * abs(scale.z)),
        mountingHeight = libraryFittingConnectionPoint(item, element)?.let { it.z - floor }
            ?: (element.transform.translation.z - floor).let { if (item.mounting == MountingType.FREE) it else it.coerceAtLeast(0.0) },
        material = material?.name ?: text(LibraryParameterKeys.MATERIAL),
        colourArgb = element.colourOverride ?: system?.colourArgb ?: material?.colourArgb ?: layer?.colourArgb
            ?: (params[LibraryParameterKeys.COLOUR] as? ParameterValue.Colour)?.argb
            ?: item.defaultColourArgb,
        system = system?.name ?: text(LibraryParameterKeys.SYSTEM),
        manufacturer = text(LibraryParameterKeys.MANUFACTURER),
        model = text(LibraryParameterKeys.MODEL),
        nameTag = (body.parameters[LibraryParameterKeys.NAME_TAG] as? ParameterValue.Text)?.value ?: element.name.ifBlank { item.name },
        section = connection.section,
        outletSection = connection.outletSection,
        connectionProfileRepresentable = connection.representable,
        freeMounting = item.mounting == MountingType.FREE,
    )
}

private data class EffectiveFittingSections(
    val section: RouteSection?, val outletSection: RouteSection?, val representable: Boolean,
)

/** Input profiles lie in local X/Z; signed axes mirror without changing connection sizes. */
private fun effectiveFittingSections(item: LibraryItem, element: Element): EffectiveFittingSections {
    val fitting = item.fitting ?: return EffectiveFittingSections(null, null, true)
    val body = element.body as ElementBody.Component
    val widthFactor = body.size.width / (item.geometrySize ?: item.defaultSize).width * abs(element.transform.scale.x)
    val heightFactor = body.size.height / (item.geometrySize ?: item.defaultSize).height * abs(element.transform.scale.z)
    val circularRepresentable = abs(widthFactor - heightFactor) <= maxOf(widthFactor, heightFactor) * 1e-8
    fun scaled(section: RouteSection?): RouteSection? = when (section) {
        is RouteSection.Rectangular -> RouteSection.Rectangular(section.width * widthFactor, section.height * heightFactor)
        is RouteSection.Circular -> if (circularRepresentable) RouteSection.Circular(section.diameter * widthFactor) else section
        null -> null
    }
    return EffectiveFittingSections(scaled(fitting.section), scaled(fitting.outletSection),
        fitting.section !is RouteSection.Circular || circularRepresentable)
}

/**
 * Return an instance edit only. Dimensions are baked into the component size while signed
 * transform scales retain handedness. The library definition is never modified.
 */
fun applyLibraryProperties(document: ProjectDocument, element: Element, properties: LibraryInstanceProperties): Element {
    val body = requireNotNull(element.body as? ElementBody.Component) { "Select a library item." }
    val definition = requireNotNull(document.libraryItems.find { it.id == body.libraryItemId })
    definition.fitting?.let {
        val effective = effectiveFittingSections(definition, element)
        require(effective.representable || properties.size == definition.defaultSize) {
            "The scaled fitting has an elliptical connection. Edit connection sizes to restore its circular profile."
        }
        require((properties.section == null || properties.section == effective.section) &&
            (properties.outletSection == null || properties.outletSection == effective.outletSection)) {
            "Use LibraryCommands.edit to change fitting connection sizes."
        }
        require(properties.size == readLibraryProperties(document, element).size || properties.size == definition.defaultSize) {
            "Edit connection sizes to resize a fitting."
        }
    }
    val connection = libraryFittingConnectionPoint(definition, element)
    val floor = placementLevel(document, element.levelId, element.transform.translation.z)?.elevation ?: 0.0
    val scale = element.transform.scale
    var changed = element.copy(
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
    if (connection != null) {
        val resizedConnection = requireNotNull(libraryFittingConnectionPoint(definition, changed))
        changed = changed.copy(transform = changed.transform.copy(translation = Point3(
            changed.transform.translation.x + connection.x - resizedConnection.x,
            changed.transform.translation.y + connection.y - resizedConnection.y,
            changed.transform.translation.z + floor + properties.mountingHeight - resizedConnection.z,
        )))
    }
    if(definition.floorService!=null)changed=FloorServices.place(document,definition,changed)
    return synchronizeHostPosition(document, changed)
}

private fun synchronizeHostPosition(document: ProjectDocument, element: Element): Element {
    val body = element.body as ElementBody.Component
    val attachment = body.host ?: return element
    val host = document.elements.find { it.id == attachment.hostElementId } ?: return element
    val item = document.libraryItems.find { it.id == body.libraryItemId }
    val position = item?.let { libraryFittingConnectionPoint(it, element) } ?: element.transform.translation
    return element.copy(body = body.copy(host = attachment.copy(
        localPosition = measurementToLocal(host.transform, position),
    )))
}

/** Library commands use the same sparse lock-aware transactions as ordinary drawing tools. */
object LibraryCommands {
    fun place(document: ProjectDocument, placement: LibraryPlacement): EditCommand {
        require(placement.element.projectId == document.project.id) { "This placement belongs to another project." }
        require(document.elements.none { it.id == placement.element.id }) { "Object ID already exists." }
        validateFittingPlacement(document, placement)
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
        val selectedDefinition = requireNotNull(document.libraryItems.find { it.id == body.libraryItemId })
        val typeId = selectedDefinition.fitting?.baseTypeId ?: selectedDefinition.id
        var prepared = document
        val replacements = document.elements.map { element ->
            val component = element.body as? ElementBody.Component
            val definition = component?.let { value -> document.libraryItems.find { it.id == value.libraryItemId } }
            if (element.id != id && (!updateAll || (definition?.fitting?.baseTypeId ?: definition?.id) != typeId)) return@map element
            val adapted = prepareFittingEdit(prepared, element, properties)
            prepared = adapted.first
            val effective = adapted.second
            var changed = applyLibraryProperties(prepared, effective.first, effective.second)
            if (element.id == id && transform != null) {
                val mountingChanged = abs(properties.mountingHeight - previous.mountingHeight) > 1e-10
                val requested = if (selectedDefinition.fitting != null && transform.position == selected.transform.translation)
                    changed.transform.translation else transform.position
                val position = if (mountingChanged) requested.copy(z = changed.transform.translation.z) else requested
                changed = changed.copy(transform = changed.transform.copy(translation = position, rotation = transform.rotation))
                val floor = placementLevel(document, selected.levelId, position.z)?.elevation ?: 0.0
                val item = requireNotNull(prepared.libraryItems.find { it.id == (changed.body as ElementBody.Component).libraryItemId })
                val mountingPoint = libraryFittingConnectionPoint(item, changed) ?: position
                changed = changed.copy(body = (changed.body as ElementBody.Component).copy(parameters =
                    (changed.body as ElementBody.Component).parameters +
                        (LibraryParameterKeys.MOUNTING_HEIGHT to ParameterValue.Number(mountingPoint.z - floor, Quantity.LENGTH))))
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
        val currentItem = requireNotNull(document.libraryItems.find { it.id == body.libraryItemId }) { "The library definition no longer exists." }
        val item = currentItem.fitting?.baseTypeId?.let { base -> document.libraryItems.find { it.id == base } ?: BuiltInLibrary.find(base) }
            ?: currentItem
        val defaults = item.defaultParameters()
        fun text(key: String) = (defaults[key] as? ParameterValue.Text)?.value.orEmpty()
        val level = placementLevel(document, element.levelId, element.transform.translation.z)
        val height = if (element.lockMode == LockMode.POSITION_ONLY || currentItem.fitting != null)
            readLibraryProperties(document, element).mountingHeight else when (item.mounting) {
            MountingType.FREE -> readLibraryProperties(document, element).mountingHeight
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
            section = item.fitting?.section, outletSection = item.fitting?.outletSection,
            freeMounting = item.mounting == MountingType.FREE,
        )
        val reset = edit(document, id, parameters).preview(document)
        val connectionBindings = if (currentItem.fitting == null) emptyMap() else body.parameters.filterKeys {
            it == LibraryConnectionParameterKeys.ENDPOINT || it == LibraryConnectionParameterKeys.PORT
        }
        val result = reset.copy(elements = reset.elements.map { current ->
            if (current.id != id) current else current.copy(body = (current.body as ElementBody.Component).copy(
                parameters = defaults + connectionBindings +
                    (LibraryParameterKeys.MOUNTING_HEIGHT to ParameterValue.Number(height, Quantity.LENGTH)),
            ))
        })
        return EditCommands.between(document, result, "Reset to library default")
    }

    /** A connection-size edit carries a new immutable definition in the same undo transaction. */
    private fun prepareFittingEdit(
        document: ProjectDocument, element: Element, properties: LibraryInstanceProperties,
    ): Pair<ProjectDocument, Pair<Element, LibraryInstanceProperties>> {
        val body = element.body as ElementBody.Component
        val item = requireNotNull(document.libraryItems.find { it.id == body.libraryItemId })
        if (item.fitting == null) return document to (element to properties)
        val effective = effectiveFittingSections(item, element)
        val section = properties.section ?: requireNotNull(effective.section)
        val outlet = properties.outletSection ?: effective.outletSection
        val changedSection = section != effective.section || outlet != effective.outletSection
        if (!changedSection) {
            require(effective.representable || properties.size == item.defaultSize) {
                "The scaled fitting has an elliptical connection. Edit connection sizes to restore its circular profile."
            }
            require(properties.size == readLibraryProperties(document, element).size || properties.size == item.defaultSize) {
                "Edit connection sizes to resize a fitting."
            }
            return document to (element to properties)
        }
        val adapted = libraryPlacementDefinition(document, requireNotNull(adaptedFittingItem(item, section, outlet)) { "This connection profile is incompatible with the fitting." })
        val existing = document.libraryItems.find { it.id == adapted.id }
        require(existing == null || existing == adapted) { "The fitting definition changed; choose the item again." }
        val prepared = if (existing == null) document.copy(libraryItems = document.libraryItems + adapted) else document
        val anchor = requireNotNull(libraryFittingConnectionPoint(item, element))
        val scale = element.transform.scale
        var changed = element.copy(body = body.copy(libraryItemId = adapted.id, size = adapted.defaultSize,
            parameters = body.parameters + adapted.parameters), transform = element.transform.copy(scale = Scale3(
                if (scale.x < 0) -1.0 else 1.0, if (scale.y < 0) -1.0 else 1.0, if (scale.z < 0) -1.0 else 1.0)))
        val after = requireNotNull(libraryFittingConnectionPoint(adapted, changed))
        changed = changed.copy(transform = changed.transform.copy(translation = Point3(
            changed.transform.translation.x + anchor.x - after.x,
            changed.transform.translation.y + anchor.y - after.y,
            changed.transform.translation.z + anchor.z - after.z,
        )))
        return prepared to (changed to properties.copy(size = adapted.defaultSize, section = adapted.fitting?.section,
            outletSection = adapted.fitting?.outletSection, connectionProfileRepresentable = true))
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
