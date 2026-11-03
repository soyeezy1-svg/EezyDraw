package com.eezydraw.core.model

import kotlinx.serialization.Serializable

/**
 * Epoch timestamps are UTC milliseconds. World position = origin + project position, in metres.
 * Origin is a translation offset only; no geographic CRS or map projection is assumed.
 */
@Serializable
data class Project(
    val id: ProjectId,
    val name: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val description: String = "",
    val author: String = "",
    val projectNumber: String = "",
    val origin: Point3 = Point3(),
    val settings: ProjectSettings = ProjectSettings(),
    val thumbnailAssetId: AssetId? = null,
    val schemaVersion: Int = 1,
    val template: ProjectTemplate = ProjectTemplate.EMPTY,
) {
    init {
        require(name.isNotBlank())
        require(updatedAtEpochMillis >= createdAtEpochMillis)
        require(schemaVersion > 0)
    }
}

/** Level elevation is finished-floor Z. Slab occupies [elevation - slabThickness, elevation]. */
@Serializable
data class Level(
    val id: LevelId,
    val projectId: ProjectId,
    val name: String,
    val elevation: Double,
    val slabThickness: Double,
    val storeyHeight: Double,
) {
    init {
        require(name.isNotBlank() && elevation.isFinite())
        require(slabThickness.isFinite() && slabThickness > 0)
        require(storeyHeight.isFinite() && storeyHeight > slabThickness)
    }
}

/** Colour is packed ARGB, independent of the UI toolkit. Line weight is in paper millimetres. */
@Serializable
data class Layer(
    val id: LayerId,
    val projectId: ProjectId,
    val name: String,
    val colourArgb: Long = 0xFF333333,
    val lineWeightMm: Double = 0.25,
    val visible: Boolean = true,
    val locked: Boolean = false,
) {
    init { require(lineWeightMm.isFinite() && lineWeightMm > 0) }
}

@Serializable
data class BuildingSystem(
    val id: SystemId,
    val projectId: ProjectId,
    val name: String,
    val discipline: Discipline,
    val colourArgb: Long,
    val visible: Boolean = true,
)

@Serializable enum class Discipline { ELECTRICAL, ICT, HVAC, PLUMBING, FIRE_PROTECTION, ARCHITECTURE, STRUCTURE, CIVIL }

@Serializable
data class Material(val id: MaterialId, val name: String, val colourArgb: Long)

/** Relative logical path resolves inside project storage; never store device absolute paths in a project. */
@Serializable
data class ProjectAsset(
    val id: AssetId,
    val projectId: ProjectId,
    val relativePath: String,
    val mimeType: String,
    val sha256: String? = null,
) {
    init {
        require(relativePath.isNotBlank() && '\\' !in relativePath && ':' !in relativePath)
        require(relativePath.split('/').none { it.isEmpty() || it == "." || it == ".." })
    }
}

@Serializable
data class ElementGroup(val id: GroupId, val projectId: ProjectId, val name: String, val elementIds: Set<ElementId>)
