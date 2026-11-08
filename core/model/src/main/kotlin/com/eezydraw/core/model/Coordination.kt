package com.eezydraw.core.model

import kotlinx.serialization.Serializable

@Serializable enum class ClashKind { HARD, SOFT, CLEARANCE, DUPLICATE }
@Serializable enum class ClashStatus { NEW, ACTIVE, RESOLVED, APPROVED, IGNORED }
@Serializable enum class IssueStatus { OPEN, IN_PROGRESS, RESOLVED, CLOSED }

/** Stored coordination result, separate from a future clash-detection algorithm. */
@Serializable
data class Clash(
    val firstElementId: ElementId,
    val secondElementId: ElementId,
    val kind: ClashKind,
    val position: Point3,
    val requiredClearance: Double = 0.0,
    val distance: Double = 0.0,
    val overlap: Double = 0.0,
    val status: ClashStatus = ClashStatus.NEW,
    val note: String = "",
    val checkKey: String? = null,
    val firstName: String = "",
    val secondName: String = "",
    val levelIds: Set<LevelId> = emptySet(),
) {
    init {
        require(distance.isFinite() && distance >= 0 && overlap.isFinite() && overlap >= 0 && note.length <= 4000)
        require(firstElementId != secondElementId)
        require(requiredClearance.isFinite() && requiredClearance >= 0)
    }
}

@Serializable
data class Issue(
    val id: IssueId,
    val projectId: ProjectId,
    val title: String,
    val createdAtEpochMillis: Long,
    val status: IssueStatus = IssueStatus.OPEN,
    val description: String = "",
    val elementIds: Set<ElementId> = emptySet(),
    val viewId: ViewId? = null,
    val position: Point3? = null,
    val clash: Clash? = null,
    val assignedTo: String? = null,
    val comments: List<IssueComment> = emptyList(),
    val markup: List<Shape> = emptyList(),
) {
    init { require(title.isNotBlank()) }
}

@Serializable
data class IssueComment(val id: String, val authorId: String, val text: String, val createdAtEpochMillis: Long)

/** File-oriented revision metadata; snapshots/history/sync are deliberately not implemented. */
@Serializable
data class Revision(
    val id: RevisionId,
    val projectId: ProjectId,
    val parentId: RevisionId?,
    val authorId: String,
    val createdAtEpochMillis: Long,
    val message: String,
    val snapshotAssetId: AssetId,
)

@Serializable
data class Sheet(
    val id: SheetId,
    val projectId: ProjectId,
    val number: String,
    val title: String,
    val paperWidthMm: Double,
    val paperHeightMm: Double,
    val viewports: List<SheetViewport> = emptyList(),
    val titleBlockFields: Map<String, String> = emptyMap(),
) {
    init {
        require(paperWidthMm.isFinite() && paperWidthMm > 0)
        require(paperHeightMm.isFinite() && paperHeightMm > 0)
    }
}

/** Sheet positions and sizes use paper millimetres; scaleDenominator=100 denotes 1:100. */
@Serializable
data class SheetViewport(
    val viewId: ViewId,
    val originMm: Point2,
    val widthMm: Double,
    val heightMm: Double,
    val scaleDenominator: Double,
) {
    init {
        require(widthMm.isFinite() && widthMm > 0 && heightMm.isFinite() && heightMm > 0)
        require(scaleDenominator.isFinite() && scaleDenominator > 0)
    }
}
