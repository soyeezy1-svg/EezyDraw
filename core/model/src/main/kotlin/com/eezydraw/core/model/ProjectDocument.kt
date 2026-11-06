package com.eezydraw.core.model

import kotlinx.serialization.Serializable

/**
 * Versioned document envelope for future import/export and persistence mapping.
 * Cross-record constraints (project ownership, dangling references, geometric topology) must be
 * checked at repository/import boundaries. Serialization is data representation, not file I/O.
 */
@Serializable
data class ProjectDocument(
    val project: Project,
    val levels: List<Level> = emptyList(),
    val layers: List<Layer> = emptyList(),
    val systems: List<BuildingSystem> = emptyList(),
    val materials: List<Material> = emptyList(),
    val elements: List<Element> = emptyList(),
    val libraryItems: List<LibraryItem> = emptyList(),
    val underlays: List<Underlay> = emptyList(),
    val views: List<SavedView> = emptyList(),
    val groups: List<ElementGroup> = emptyList(),
    val issues: List<Issue> = emptyList(),
    val sheets: List<Sheet> = emptyList(),
    val revisions: List<Revision> = emptyList(),
    val assets: List<ProjectAsset> = emptyList(),
    val libraryState: LibraryState = LibraryState(),
)
