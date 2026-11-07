package com.eezydraw.core.model

import kotlinx.serialization.Serializable

/** Only device overrides and custom definitions are stored; shipped defaults remain immutable. */
@Serializable
data class LibraryCatalog(
    val items: List<LibraryItem> = emptyList(),
    val removedIds: Set<LibraryItemId> = emptySet(),
) {
    init {
        require(removedIds.size <= 10_000 && removedIds.none { BuiltInLibrary.find(it) != null })
        require(items.none { it.id in removedIds })
        require(items.size <= 2000) { "A device library supports up to 2000 saved items." }
        require(items.map { it.id }.distinct().size == items.size) { "Duplicate library item ID." }
        items.forEach { item ->
            require(item.builtIn == (BuiltInLibrary.find(item.id) != null)) { "Invalid built-in identity." }
            require(item.name.length <= 120 && item.category.length <= 120) { "Library names must be at most 120 characters." }
            require(item.materialId == null) { "Library defaults cannot refer to a project material." }
        }
    }

    fun effectiveItems(projectItems: List<LibraryItem> = emptyList()): List<LibraryItem> =
        (BuiltInLibrary.items + projectItems.filter { reusableProjectItem(it) && it.id !in removedIds } + items)
            .associateBy { it.id }.values.toList()
    fun find(id: LibraryItemId, projectItems: List<LibraryItem> = emptyList()): LibraryItem? =
        items.find { it.id == id } ?: BuiltInLibrary.find(id) ?: projectItems.find {
            it.id == id && reusableProjectItem(it) && id !in removedIds
        }
    private fun reusableProjectItem(item: LibraryItem) = item.catalogSourceId == null && !item.builtIn &&
        (item.fitting == null || item.fitting.baseTypeId == item.id)
    fun save(item: LibraryItem): LibraryCatalog = copy(
        items = items.filterNot { it.id == item.id } + item.copy(materialId = null, catalogSourceId = null),
        removedIds = removedIds - item.id,
    )
    fun remove(id: LibraryItemId): LibraryCatalog {
        require(BuiltInLibrary.find(id) == null) { "Built-in items can be restored, not deleted." }
        return copy(items = items.filterNot { it.id == id }, removedIds = removedIds + id)
    }
    fun restore(id: LibraryItemId): LibraryCatalog {
        require(BuiltInLibrary.find(id) != null) { "Only built-in items have shipped defaults." }
        return copy(items = items.filterNot { it.id == id })
    }
    fun duplicate(id: LibraryItemId, newId: LibraryItemId, name: String): LibraryCatalog {
        require(find(newId) == null) { "This library ID already exists." }
        val source = requireNotNull(find(id)) { "This item is no longer in the library." }
        return save(source.copy(id = newId, name = name, builtIn = false, version = 1,
            fitting = source.fitting?.copy(baseTypeId = newId)))
    }
    /** Import merges by stable ID. Exporting and reimporting never creates duplicate copies. */
    fun merge(imported: LibraryCatalog): LibraryCatalog {
        val merged = (items + imported.items).associateBy { it.id }.values.toList()
        return copy(items = merged, removedIds = (removedIds + imported.removedIds) - merged.map { it.id }.toSet())
    }
}

/** A bounded session history; callers persist a candidate before accepting it. */
class LibraryCatalogHistory(private val limit: Int = 50) {
    private val undo = ArrayDeque<LibraryCatalog>()
    private val redo = ArrayDeque<LibraryCatalog>()
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    fun record(previous: LibraryCatalog) {
        undo.addLast(previous)
        while (undo.size > limit) undo.removeFirst()
        redo.clear()
    }
    fun undoCandidate(): LibraryCatalog? = undo.lastOrNull()
    fun redoCandidate(): LibraryCatalog? = redo.lastOrNull()
    fun acceptUndo(previous: LibraryCatalog) { undo.removeLast(); redo.addLast(previous) }
    fun acceptRedo(previous: LibraryCatalog) { redo.removeLast(); undo.addLast(previous) }
}
