package com.eezydraw.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * IDs reference the same project; repositories will enforce referential integrity.
 * Transform translation is project-space. Level ownership does not implicitly add level elevation;
 * library placement resolves floor/wall/ceiling constraints to canonical project coordinates.
 */
@Serializable
data class Element(
    val id: ElementId,
    val projectId: ProjectId,
    val layerId: LayerId,
    val body: ElementBody,
    val levelId: LevelId? = null,
    val systemId: SystemId? = null,
    val name: String = "",
    val transform: Transform3 = Transform3(),
    val materialId: MaterialId? = null,
    val tags: Set<String> = emptySet(),
    val properties: Map<String, ParameterValue> = emptyMap(),
    val wallDistances: List<WallDistance> = emptyList(),
)

@Serializable
sealed interface ElementBody {
    @Serializable @SerialName("shape")
    data class DrawingShape(val shape: Shape) : ElementBody

    /**
     * Straight reference line in local XY; endpoints follow [reference]. Left/right are viewed
     * from start toward end with +Z up. Local Z=0 base is placed by the element transform.
     */
    @Serializable @SerialName("wall")
    data class Wall(
        val start: Point2,
        val end: Point2,
        val thickness: Double,
        val height: Double,
        val reference: WallReference = WallReference.CENTERLINE,
        val joinProfile:Polygon2?=null,
    ) : ElementBody {
        init {
            require(start != end)
            require(thickness.isFinite() && thickness > 0 && height.isFinite() && height > 0)
        }
    }

    /** Boundary is the finish-face polygon. Adjacent rooms reference the SAME wall element. */
    @Serializable @SerialName("room")
    data class Room(
        val boundary: Polygon2,
        val number: String = "",
        val ceilingHeight: Double = 3.0,
        val wallFaces: List<RoomWallFace> = emptyList(),
        val wallOptions:RoomWallOptions?=null,
        val ceiling:CeilingOptions?=null,
    ) : ElementBody {
        init {
            require(ceilingHeight.isFinite() && ceilingHeight > 0)
            require(wallFaces.all { face ->
                val ring = if (face.ringIndex == null) boundary.outer else boundary.holes.getOrNull(face.ringIndex)
                ring != null && face.edgeIndex in ring.indices
            })
        }
    }

    /** Top surface is local Z=0, underside is -thickness. */
    @Serializable @SerialName("slab")
    data class Slab(val boundary: Polygon2, val thickness: Double) : ElementBody {
        init { require(thickness.isFinite() && thickness > 0) }
    }

    @Serializable @SerialName("component")
    data class Component(
        val libraryItemId: LibraryItemId,
        val size: Size3,
        val parameters: Map<String, ParameterValue> = emptyMap(),
        val host: HostAttachment? = null,
        val wallService: WallServiceAttachment? = null,
    ) : ElementBody

    /** Path points are local 3D centreline positions; elevation setting records placement intent. */
    @Serializable @SerialName("route")
    data class Route(
        val kind: RouteKind,
        val path: List<Point3>,
        val section: RouteSection,
        val elevation: ElevationSetting,
        val fittings: List<RouteFitting> = emptyList(),
        val host: HostAttachment? = null,
        val wallService: WallServiceAttachment? = null,
        val routing: RouteTopology? = null,
        /** Trimmed physical section endpoints; path retains the editable node centerline. */
        val fabricationPath: List<Point3>? = null,
    ) : ElementBody {
        init {
            require(path.size >= 2)
            require(routing==null || path.size==2)
            require(fabricationPath==null || fabricationPath.size==path.size)
            require(fittings.all { it.pathVertexIndex in path.indices })
        }
    }
}

@Serializable enum class WallReference { CENTERLINE, LEFT_FINISH_FACE, RIGHT_FINISH_FACE }
@Serializable enum class WallSide { LEFT, RIGHT }

/** Edge indices refer to the outer ring or a hole, with null ringIndex denoting the outer ring. */
@Serializable
data class RoomWallFace(
    val edgeIndex: Int,
    val wallId: ElementId,
    val side: WallSide,
    val ringIndex: Int? = null,
) {
    init { require(edgeIndex >= 0 && (ringIndex == null || ringIndex >= 0)) }
}

/** Parameters describe the desired penetration; no boolean cut/groove operation is performed here. */
@Serializable
data class HostAttachment(
    val hostElementId: ElementId,
    val mode: HostMode,
    val localPosition: Point3,
    val recessDepth: Double = 0.0,
) {
    init { require(recessDepth.isFinite() && recessDepth >= 0) }
}

@Serializable enum class HostMode { SURFACE, RECESSED, CHASE, THROUGH_OPENING }
