package com.eezydraw.core.geometry

import com.eezydraw.core.model.Bounds3
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.ObjectDisplay
import com.eezydraw.core.model.RoomSideRef

/**
 * Immutable GL_TRIANGLES batch. Every vertex contains local x/y/z, world normal x/y/z,
 * then r/g/b/a. Positions are small Float offsets from a Double project-space origin.
 */
data class SurfaceChunk(
    val origin: Point3,
    val bounds: Bounds3,
    val levelId: String?,
    val vertices: FloatArray,
    val elementId: String,
    val display: ObjectDisplay = ObjectDisplay.NORMAL,
    val roomSide: RoomSideRef? = null,
    val roomCenter: Point3? = null,
    val material:com.eezydraw.core.model.RenderMaterial=com.eezydraw.core.model.RenderMaterial.OTHER,
    val lodAllowed:Boolean=false,
    val depthBias:Float=1f,
)

data class SurfaceScene(val chunks: List<SurfaceChunk>, val bounds: Bounds3) {
    val triangleCount: Int get() = chunks.sumOf { it.vertices.size / 30 }

    fun boundsForLevel(levelId: String?): Bounds3 {
        if (levelId == null) return bounds
        return chunks.asSequence().filter { it.levelId == null || it.levelId == levelId }
            .map { it.bounds }.reduceOrNull(::unionBounds) ?: Bounds3(Point3(), Point3())
    }
}
