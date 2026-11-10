package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class ModelExportAxisTest {
    private val project = ProjectId("axes")
    private val layer = LayerId("model")
    private fun box(id: String, size: Size3, at: Point3) = Element(
        ElementId(id), project, layer, ElementBody.DrawingShape(Shape.Box(size)),
        name = id, transform = Transform3(translation = at)
    )
    private val document = ProjectDocument(
        Project(project, "Axis regression", 0, 0, origin = Point3(100.0, 200.0, 300.0)),
        layers = listOf(Layer(layer, project, "Structure")),
        elements = listOf(
            box("AxisBox", Size3(1.0, 2.0, 3.0), Point3(10.0, 20.0, 30.0)),
            box("TopMarker", Size3(.2, .3, .4), Point3(10.7, 21.5, 33.2))
        )
    )
    private fun write(options: ModelExportOptions) = ByteArrayOutputStream().also {
        ModelExport.write(document, options, it)
    }.toByteArray()

    @Test fun formatDefaultsAndRequiredAxes() {
        for (format in ModelFormat.entries) {
            val expected = if (format in listOf(ModelFormat.OBJ, ModelFormat.GLTF, ModelFormat.GLB)) ModelUpAxis.Y_UP else ModelUpAxis.Z_UP
            assertEquals(expected, ModelExportOptions(format = format).resolvedUpAxis())
            assertEquals(ModelUnits.M, ModelExportOptions(format = format).units)
        }
        for ((format, axis) in listOf(ModelFormat.IFC to ModelUpAxis.Y_UP, ModelFormat.GLTF to ModelUpAxis.Z_UP, ModelFormat.GLB to ModelUpAxis.Z_UP)) {
            val output = ByteArrayOutputStream()
            try {
                ModelExport.write(document, ModelExportOptions(format = format, upAxis = axis), output)
                fail("Unsupported standard axis accepted")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("requires"))
                assertEquals(0, output.size())
            }
        }
    }

    @Test fun rotationPreservesHandednessWindingAndOutwardNormals() {
        for (axis in ModelUpAxis.entries) for (move in listOf(false, true)) {
            val options = ModelExportOptions(format = ModelFormat.OBJ, upAxis = axis, moveToOrigin = move)
            val (meshes, shift) = prepareModelCoordinates(ModelExport.meshes(document, options), options)
            assertEquals(if (move) Point3(110.5, 221.0, 330.0) else Point3(), shift)
            for (mesh in meshes) {
                val center = Point3(mesh.vertices.map { it.x }.average(), mesh.vertices.map { it.y }.average(), mesh.vertices.map { it.z }.average())
                var volume = 0.0
                for ((a, b, c) in mesh.vertices.chunked(3)) {
                    val normal = triangleNormal(a, b, c)
                    val outward = normal.x * ((a.x + b.x + c.x) / 3 - center.x) + normal.y * ((a.y + b.y + c.y) / 3 - center.y) + normal.z * ((a.z + b.z + c.z) / 3 - center.z)
                    assertTrue("${mesh.name} inward face", outward > 0)
                    // Relative coordinates avoid cancellation in the signed-volume check.
                    val u = Point3(a.x-center.x, a.y-center.y, a.z-center.z)
                    val v = Point3(b.x-center.x, b.y-center.y, b.z-center.z)
                    val w = Point3(c.x-center.x, c.y-center.y, c.z-center.z)
                    volume += (u.x*(v.y*w.z-v.z*w.y) + u.y*(v.z*w.x-v.x*w.z) + u.z*(v.x*w.y-v.y*w.x))/6
                }
                assertEquals(if (mesh.name == "AxisBox") 6.0 else .024, volume, 1e-6)
            }
            val marker = meshes.first { it.name == "TopMarker" }.vertices
            val markerCentre = Point3(marker.map { it.x }.average(), marker.map { it.y }.average(), marker.map { it.z }.average())
            val expected = rotateModelAxis(Point3(110.8-shift.x, 221.65-shift.y, 333.4-shift.z), axis)
            assertEquals(expected.x, markerCentre.x, 1e-5)
            assertEquals(expected.y, markerCentre.y, 1e-5)
            assertEquals(expected.z, markerCentre.z, 1e-5)
        }
    }

    @Test fun serializedObjAndStlHaveCorrectExtentsNormalsAndMetadata() {
        for (format in listOf(ModelFormat.OBJ, ModelFormat.STL)) for (axis in ModelUpAxis.entries) for (units in ModelUnits.entries) {
            val text = write(ModelExportOptions(format = format, upAxis = axis, units = units, moveToOrigin = true, appVersion = "axis-test")).toString(Charsets.UTF_8)
            val prefix = if (format == ModelFormat.OBJ) "v " else "vertex "
            val points = text.lines().filter { it.startsWith(prefix) }.take(36).map {
                it.removePrefix(prefix).split(' ').map(String::toDouble)
            }
            val min = (0..2).map { i -> points.minOf { it[i] } / units.factor }
            val max = (0..2).map { i -> points.maxOf { it[i] } / units.factor }
            assertEquals(listOf(-.5, if (axis == ModelUpAxis.Y_UP) 0.0 else -1.0, if (axis == ModelUpAxis.Y_UP) -1.0 else 0.0), min)
            assertEquals(listOf(.5, if (axis == ModelUpAxis.Y_UP) 3.0 else 1.0, if (axis == ModelUpAxis.Y_UP) 1.0 else 3.0), max)
            assertTrue(text.contains(axis.name)); assertTrue(text.contains(units.name)); assertTrue(text.contains("axis-test"))
            assertEquals(24, text.lines().count { it.startsWith(if (format == ModelFormat.OBJ) "vn " else "facet normal ") })
        }
    }

    @Test fun everyFormatAxisUnitsAndOriginProducesMarkedBoxReaderFixtures() {
        val folder = File(System.getProperty("java.io.tmpdir"), "eezy-axis-export").apply { mkdirs() }
        for (format in ModelFormat.entries) {
            val axes = if (format in listOf(ModelFormat.OBJ, ModelFormat.STL)) ModelUpAxis.entries else listOf(format.defaultUpAxis())
            for (axis in axes) for (units in ModelUnits.entries) for (move in listOf(false, true)) {
                val options = ModelExportOptions(format = format, upAxis = axis, units = units, moveToOrigin = move, appVersion = "axis-test")
                val bytes = write(options)
                assertTrue(bytes.size > 100)
                File(folder, "${format.name}-${axis.name}-${units.name}-${if(move) "MOVE" else "PROJECT"}.${format.extension}").writeBytes(bytes)
            }
        }
        File(folder,"model.mtl").outputStream().use {ModelExport.writeObjMaterials(document,ModelExportOptions(format=ModelFormat.OBJ),it)}
        assertEquals(42, folder.listFiles()!!.count {it.extension in ModelFormat.entries.map {format->format.extension}})
    }
}
