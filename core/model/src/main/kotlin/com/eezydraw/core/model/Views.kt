package com.eezydraw.core.model

import kotlinx.serialization.Serializable

@Serializable enum class Projection { ORTHOGRAPHIC, PERSPECTIVE }
@Serializable enum class ViewMode { WIREFRAME, HIDDEN_LINE, SHADED, REALISTIC, TRANSPARENT, SECTION, XRAY, SHADED_WITH_EDGES }

@Serializable
data class Camera(
    val position: Point3,
    val target: Point3,
    val up: Point3 = Point3(0.0, 0.0, 1.0),
    val projection: Projection = Projection.ORTHOGRAPHIC,
    val verticalFieldOfViewRadians: Double = Math.PI / 3,
    val orthographicHeight: Double = 20.0,
) {
    init {
        require(position != target && up != Point3())
        require(verticalFieldOfViewRadians > 0 && verticalFieldOfViewRadians < Math.PI)
        require(orthographicHeight.isFinite() && orthographicHeight > 0)
    }
}

@Serializable
data class SavedView(
    val id: ViewId,
    val projectId: ProjectId,
    val name: String,
    val camera: Camera,
    val mode: ViewMode = ViewMode.SHADED,
    val levelId: LevelId? = null,
    val hiddenLayerIds: Set<LayerId> = emptySet(),
    val hiddenSystemIds: Set<SystemId> = emptySet(),
    val sectionBox: Bounds3? = null,
    val cutPlane: CutPlane? = null,
    val capture:IssueView?=null,
)

@Serializable
data class CutPlane(val origin: Point3, val normal: Point3) {
    init { require(normal != Point3()) }
}

@Serializable enum class UnderlayFormat { DWG, DXF, PDF, IMAGE, IFC }

/** Asset-native units are converted with metresPerSourceUnit before applying the transform. */
@Serializable
data class Underlay(
    val id: ElementId,
    val projectId: ProjectId,
    val assetId: AssetId,
    val layerId: LayerId,
    val format: UnderlayFormat,
    val metresPerSourceUnit: Double,
    val levelId: LevelId? = null,
    val transform: Transform3 = Transform3(),
    val opacity: Double = 0.5,
    val locked: Boolean = true,
    val pageIndex: Int = 0,
) {
    init {
        require(metresPerSourceUnit.isFinite() && metresPerSourceUnit > 0)
        require(opacity in 0.0..1.0 && pageIndex >= 0)
    }
}
