package com.eezydraw.core.editing

import com.eezydraw.core.model.Element
import com.eezydraw.core.model.ElementGroup
import com.eezydraw.core.model.ElementId
import com.eezydraw.core.model.Issue
import com.eezydraw.core.model.Layer
import com.eezydraw.core.model.ProjectDocument
import com.eezydraw.core.model.ProjectId
import com.eezydraw.core.model.ProjectSettings
import com.eezydraw.core.model.BuildingSystem
import com.eezydraw.core.model.LibraryItem
import com.eezydraw.core.model.Material
import java.util.ArrayDeque

internal data class ElementPatch(
    val id: ElementId,
    val beforeIndex: Int?,
    val before: Element?,
    val afterIndex: Int?,
    val after: Element?,
)

internal data class LinkPatch<T>(val before: List<T>, val after: List<T>)
internal data class ValuePatch<T>(val before: T, val after: T)

/** A sparse reversible edit. It never retains a ProjectDocument or its complete element list. */
class EditCommand internal constructor(
    val label: String,
    internal val projectId: ProjectId,
    internal val patches: List<ElementPatch>,
    private val beforeOrder: Long,
    private val afterOrder: Long,
    private val beforeCount: Int,
    private val afterCount: Int,
    private val layers: LinkPatch<Layer>? = null,
    private val groups: LinkPatch<ElementGroup>? = null,
    private val issues: LinkPatch<Issue>? = null,
    private val assets: LinkPatch<com.eezydraw.core.model.ProjectAsset>? = null,
    private val systems: LinkPatch<BuildingSystem>? = null,
    private val libraryItems: LinkPatch<LibraryItem>? = null,
    private val materials: LinkPatch<Material>? = null,
    private val settings: ValuePatch<ProjectSettings>? = null,
) {
    val changedElementCount: Int get() = patches.size
    val affectedElementIds: Set<ElementId> get() = patches.mapTo(linkedSetOf()) { it.id }
    val addedElementIds: Set<ElementId>
        get() = patches.filter { it.before == null && it.after != null }.mapTo(linkedSetOf()) { it.id }
    val isEmpty: Boolean get() = assets == null && patches.isEmpty() && layers == null && groups == null && issues == null && systems == null && libraryItems == null && materials == null && settings == null

    /** Apply without adding history, for composing a validated multi-part transaction. */
    fun preview(document: ProjectDocument): ProjectDocument = apply(document, forward = true)

    // Auxiliary records count toward the budget too, so large coordination edits stay bounded.
    internal val retainedChangeCount: Int
        get() = patches.size + (assets?.let {it.before.size+it.after.size} ?: 0) + (layers?.let { it.before.size + it.after.size } ?: 0) +
            (groups?.let { it.before.size + it.after.size } ?: 0) +
            (issues?.let { it.before.size + it.after.size } ?: 0) +
            (systems?.let { it.before.size + it.after.size } ?: 0) +
            (libraryItems?.let { it.before.size + it.after.size } ?: 0) +
            (materials?.let { it.before.size + it.after.size } ?: 0) + (if(settings==null)0 else 2)

    internal fun apply(document: ProjectDocument, forward: Boolean): ProjectDocument {
        require(document.project.id == projectId) { "This edit belongs to another project" }
        val expectedCount = if (forward) beforeCount else afterCount
        val expectedOrder = if (forward) beforeOrder else afterOrder
        require(document.elements.size == expectedCount && elementOrder(document.elements) == expectedOrder) {
            "The drawing changed; this edit is stale"
        }
        for (patch in patches) {
            val index = if (forward) patch.beforeIndex else patch.afterIndex
            val expected = if (forward) patch.before else patch.after
            if (index != null) {
                require(document.elements.getOrNull(index) == expected) { "The object changed; this edit is stale" }
            }
        }
        require(layers == null || document.layers == (if (forward) layers.before else layers.after)) {
            "The drawing layers changed; this edit is stale"
        }
        require(groups == null || document.groups == (if (forward) groups.before else groups.after)) {
            "The drawing groups changed; this edit is stale"
        }
        require(issues == null || document.issues == (if (forward) issues.before else issues.after)) {
            "The drawing issues changed; this edit is stale"
        }
        require(systems == null || document.systems == (if(forward)systems.before else systems.after)) {
            "The drawing systems changed; this edit is stale"
        }
        require(libraryItems == null || document.libraryItems == (if(forward)libraryItems.before else libraryItems.after)) {
            "The library definitions changed; this edit is stale"
        }
        require(materials == null || document.materials == (if(forward)materials.before else materials.after)) {
            "The drawing materials changed; this edit is stale"
        }
        require(settings == null || document.project.settings ==
            (if(forward)settings.before else settings.after).copy(canvasLayout=document.project.settings.canvasLayout)) {
            "The project settings changed; this edit is stale"
        }
        require(assets==null || document.assets==(if(forward)assets.before else assets.after)) {"Issue assets changed"}
        if (isEmpty) return document

        val targetCount = if (forward) afterCount else beforeCount
        val changedIds = patches.mapTo(HashSet()) { it.id }
        val targetByIndex = HashMap<Int, Element>()
        for (patch in patches) {
            val index = if (forward) patch.afterIndex else patch.beforeIndex
            val element = if (forward) patch.after else patch.before
            if (index != null && element != null) targetByIndex[index] = element
        }
        val elements = ArrayList<Element>(if(patches.isEmpty())0 else targetCount)
        val current = document.elements.iterator()
        for (index in 0 until (if(patches.isEmpty())0 else targetCount)) {
            val changed = targetByIndex[index]
            if (changed != null) {
                elements.add(changed)
            } else {
                var next: Element
                do {
                    check(current.hasNext()) { "Invalid element patch" }
                    next = current.next()
                } while (next.id in changedIds)
                elements.add(next)
            }
        }
        return document.copy(
            elements = if(patches.isEmpty())document.elements else elements,
            layers = layers?.let { if (forward) it.after else it.before } ?: document.layers,
            groups = groups?.let { if (forward) it.after else it.before } ?: document.groups,
            assets = assets?.let {if(forward)it.after else it.before} ?: document.assets,
            issues = issues?.let { if (forward) it.after else it.before } ?: document.issues,
            systems = systems?.let {if(forward)it.after else it.before} ?: document.systems,
            libraryItems = libraryItems?.let {if(forward)it.after else it.before} ?: document.libraryItems,
            materials = materials?.let {if(forward)it.after else it.before} ?: document.materials,
            project = settings?.let {document.project.copy(settings=(if(forward)it.after else it.before)
                .copy(canvasLayout=document.project.settings.canvasLayout))} ?: document.project,
        )
    }
}

/**
 * Session-only undo/redo. Entries retain changed records rather than drawing snapshots.
 * The setting bounds entry count; 250,000 changed/auxiliary records additionally bound bulk edits.
 * One operation larger than that budget remains undoable, while all older entries are evicted.
 */
class EditHistory(limit: Int = 100) {
    private val undo = ArrayDeque<EditCommand>()
    private val redo = ArrayDeque<EditCommand>()
    private var maximum = checkedLimit(limit)
    private var retainedChanges = 0L

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()
    val undoCount: Int get() = undo.size
    val redoCount: Int get() = redo.size
    val retainedChangeCount: Long get() = retainedChanges

    fun execute(document: ProjectDocument, command: EditCommand): ProjectDocument {
        // Validate first; a stale command must not consume or clear valid history.
        val result = command.apply(document, forward = true)
        if (command.isEmpty) return result
        while (redo.isNotEmpty()) retainedChanges -= redo.removeLast().retainedChangeCount
        if (maximum > 0) {
            undo.addLast(command)
            retainedChanges += command.retainedChangeCount
            trim()
        }
        return result
    }

    fun undo(document: ProjectDocument): ProjectDocument {
        val command = undo.peekLast() ?: return document
        val result = command.apply(document, forward = false)
        undo.removeLast()
        redo.addLast(command)
        return result
    }

    fun redo(document: ProjectDocument): ProjectDocument {
        val command = redo.peekLast() ?: return document
        val result = command.apply(document, forward = true)
        redo.removeLast()
        undo.addLast(command)
        return result
    }

    fun clear() {
        undo.clear()
        redo.clear()
        retainedChanges = 0L
    }

    fun setLimit(limit: Int) {
        maximum = checkedLimit(limit)
        trim()
    }

    private fun trim() {
        while (undo.size + redo.size > maximum ||
            retainedChanges > MAX_RETAINED_CHANGES && undo.size + redo.size > 1
        ) {
            // Keep the nearest actions. The front is the furthest reachable undo/redo entry.
            val removed = if (undo.isNotEmpty()) undo.removeFirst() else redo.removeFirst()
            retainedChanges -= removed.retainedChangeCount
        }
    }

    private fun checkedLimit(value: Int): Int {
        require(value in 0..10_000) { "Undo history must be between 0 and 10,000" }
        return value
    }

    private companion object {
        const val MAX_RETAINED_CHANGES = 250_000L
    }
}

/** Stable order guard without retaining every object ID in each history entry. */
internal fun elementOrder(elements: List<Element>): Long {
    var hash = -3750763034362895579L
    for (element in elements) {
        for (character in element.id.value) hash = (hash xor character.code.toLong()) * 1099511628211L
        hash = (hash xor 0xffL) * 1099511628211L
    }
    return hash
}
