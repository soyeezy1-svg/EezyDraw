package com.eezydraw.core.geometry

import com.eezydraw.core.model.Point3
import kotlin.math.sqrt

enum class ModelUpAxis(val label: String) { Y_UP("Y-up"), Z_UP("Z-up") }

fun ModelFormat.defaultUpAxis() = when (this) {
    ModelFormat.OBJ, ModelFormat.GLTF, ModelFormat.GLB -> ModelUpAxis.Y_UP
    ModelFormat.IFC, ModelFormat.STL -> ModelUpAxis.Z_UP
}

fun ModelExportOptions.resolvedUpAxis(): ModelUpAxis {
    val axis = upAxis ?: format.defaultUpAxis()
    require(format !in listOf(ModelFormat.GLTF, ModelFormat.GLB) || axis == ModelUpAxis.Y_UP) { "glTF/GLB requires Y-up" }
    require(format != ModelFormat.IFC || axis == ModelUpAxis.Z_UP) { "IFC requires Z-up" }
    return axis
}

/** Proper -90 degree rotation about X, determinant +1: native +Y forward becomes output -Z. */
internal fun rotateModelAxis(p: Point3, axis: ModelUpAxis) =
    if (axis == ModelUpAxis.Y_UP) Point3(p.x, p.z, -p.y) else p

/** Subtract a common native-space base centre before rotation; never recenter individual meshes. */
internal fun prepareModelCoordinates(meshes: List<ExportMesh>, options: ModelExportOptions): Pair<List<ExportMesh>, Point3> {
    val axis = options.resolvedUpAxis()
    val points = meshes.asSequence().flatMap { it.vertices.asSequence() }
    val shift = if (options.moveToOrigin) {
        val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
        Point3(minX + (maxX-minX)/2, minY + (maxY-minY)/2, points.minOf { it.z })
    } else Point3()
    if (axis == ModelUpAxis.Z_UP && !options.moveToOrigin) return meshes to shift
    val converted = meshes.map { mesh ->
        mesh.copy(vertices = mesh.vertices.map { point ->
            rotateModelAxis(Point3(point.x-shift.x, point.y-shift.y, point.z-shift.z), axis)
        })
    }
    return converted to shift
}

internal fun triangleNormal(a: Point3, b: Point3, c: Point3): Point3 {
    val x = (b.y-a.y)*(c.z-a.z) - (b.z-a.z)*(c.y-a.y)
    val y = (b.z-a.z)*(c.x-a.x) - (b.x-a.x)*(c.z-a.z)
    val z = (b.x-a.x)*(c.y-a.y) - (b.y-a.y)*(c.x-a.x)
    val length = sqrt(x*x+y*y+z*z).coerceAtLeast(1e-30)
    return Point3(x/length, y/length, z/length)
}

internal fun exportCoordinateNote(options: ModelExportOptions, shift: Point3): String =
    "upAxis=${options.resolvedUpAxis().name}; units=${options.units.name}; " +
    "appVersion=${options.appVersion.replace(Regex("[\\r\\n]"), " ")}; " +
    "origin=${if (options.moveToOrigin) "MOVE_TO_ORIGIN" else "PROJECT"}; " +
    "sourceOffsetMetres=${shift.x},${shift.y},${shift.z}; rightHanded=true"
