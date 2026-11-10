package com.eezydraw.core.data

import android.content.Context
import androidx.room.Room
import com.eezydraw.core.model.DimensionKind
import com.eezydraw.core.model.Element
import com.eezydraw.core.model.ElementBody
import com.eezydraw.core.model.ElementId
import com.eezydraw.core.model.Point2
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.Polygon2
import com.eezydraw.core.model.ProjectSettings
import com.eezydraw.core.model.ProjectTemplate
import com.eezydraw.core.model.Rotation3
import com.eezydraw.core.model.Scale3
import com.eezydraw.core.model.Shape
import com.eezydraw.core.model.Size3
import com.eezydraw.core.model.Transform3
import com.eezydraw.core.model.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises real Room catalogue, versioned JSON files, preview writes and portable ZIP archives. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditedGeometryPersistenceTest {
    private lateinit var context: Context
    private lateinit var database: ProjectDatabase
    private lateinit var repository: RoomProjectRepository
    private lateinit var databaseName: String
    private val ownedProjectIds = mutableSetOf<String>()

    @Before fun openDatabase() {
        context = RuntimeEnvironment.getApplication()
        databaseName = "edited-geometry-${UUID.randomUUID()}.db"
        reopenDatabase()
    }

    @After fun closeDatabase() {
        database.close()
        context.deleteDatabase(databaseName)
        ownedProjectIds.forEach { File(File(context.filesDir, "projects"), it).deleteRecursively() }
    }

    @Test fun dimensionsWallFaceLocksAndFixedEndDefaultSurviveRepositoryReopenAndArchive() = runBlocking {
        val created=repository.create("Dimension positioning",ProjectTemplate.EMPTY,ProjectSettings(dimensionFixedEnd=FixedEnd.CENTER))
        ownedProjectIds += created.project.id.value
        val wall=Element(ElementId("dimension-wall"),created.project.id,created.layers.first().id,
            ElementBody.Wall(Point2(0.0,0.0),Point2(5.0,0.0),.2,3.0),created.levels.first().id)
        val item=wall.copy(id=ElementId("positioned-item"),body=ElementBody.DrawingShape(Shape.Box(Size3(.5,.8,.6))),
            transform=Transform3(Point3(1.0,1.1)),wallDistances=listOf(WallDistance(wall.id,WallFaceSide.LEFT,1.0,DistanceReference.NEAR_EDGE)))
        val binding=DimensionBinding(wall.id,wallFace=WallLengthFace.INSIDE)
        val anchors=listOf(MeasurementAnchor(Point3(0.0,.1),wall.id,Point3(0.0,.1),vertexIndex=0),MeasurementAnchor(Point3(5.0,.1),wall.id,Point3(5.0,.1),vertexIndex=1))
        val dimension=wall.copy(id=ElementId("bound-dimension"),body=ElementBody.DrawingShape(Shape.Dimension(DimensionKind.ALIGNED,
            listOf(Point3(0.0,.1),Point3(5.0,.1)),Point3(y=.3),MeasurementData("Wall inside",anchors,binding=binding))))
        val saved=repository.save(created.copy(elements=listOf(wall,item,dimension)))
        database.close();reopenDatabase()
        val loaded=repository.load(saved.project.id.value)
        assertEquals(saved,loaded)
        assertEquals(FixedEnd.CENTER,loaded.project.settings.dimensionFixedEnd)
        assertEquals(item.wallDistances,loaded.elements[1].wallDistances)
        assertEquals(5.0,resolveMeasurement(loaded,loaded.elements[2])!!.distance,1e-8)
        val bytes=ByteArrayOutputStream().also {repository.exportProject(saved.project.id.value,it)}.toByteArray()
        val imported=repository.importProject(ByteArrayInputStream(bytes))
        ownedProjectIds += imported.project.id.value
        assertEquals(saved.elements,imported.elements.map {it.copy(projectId=saved.project.id)})
        assertEquals(binding,measurementShape(imported.elements[2])!!.measurement!!.binding)
    }

    @Test fun drawingToolsAndMirroredTransformsSurviveReopenAndPortableArchiveImport() = runBlocking {
        val created = repository.create("Edited geometry", ProjectTemplate.EMPTY, ProjectSettings())
        ownedProjectIds += created.project.id.value
        val drawing = created.copy(elements = toolShapes().mapIndexed { index, (name, shape) ->
            Element(
                id = ElementId("edited-$name"),
                projectId = created.project.id,
                layerId = created.layers[index % created.layers.size].id,
                levelId = created.levels.single().id,
                name = name,
                body = ElementBody.DrawingShape(shape),
                transform = Transform3(
                    translation = Point3(index * 4.25, index * -2.5, index * 0.125),
                    rotation = Rotation3(0.125, -0.25, index * 0.1),
                    scale = when (index % 4) {
                        0 -> Scale3(-1.5, 2.0, 0.75)
                        1 -> Scale3(1.0, -0.5, 2.0)
                        2 -> Scale3(1.25, 2.0, -3.0)
                        else -> Scale3(-0.8, -1.25, 3.0)
                    },
                ),
            )
        })
        val saved = repository.save(drawing)
        assertTrue(saved.elements.any { it.transform.scale.x < 0.0 })
        assertTrue(saved.elements.any { it.transform.scale.y < 0.0 })
        assertTrue(saved.elements.any { it.transform.scale.z < 0.0 })

        database.close()
        reopenDatabase()
        assertEquals(saved, repository.load(saved.project.id.value))
        val thumbnail = repository.recentProjects.first().single().thumbnailUri!!
        assertTrue(File(java.net.URI(thumbnail)).length() > 0)

        val bytes = ByteArrayOutputStream().also { repository.exportProject(saved.project.id.value, it) }.toByteArray()
        val imported = repository.importProject(ByteArrayInputStream(bytes))
        ownedProjectIds += imported.project.id.value
        assertNotEquals(saved.project.id, imported.project.id)
        // Local IDs remain stable inside the new project; ownership alone changes on import.
        assertEquals(saved.elements, imported.elements.map { it.copy(projectId = saved.project.id) })
        assertEquals(saved.layers, imported.layers.map { it.copy(projectId = saved.project.id) })
        assertEquals(saved.levels, imported.levels.map { it.copy(projectId = saved.project.id) })
        assertTrue(imported.elements.all { it.projectId == imported.project.id })

        database.close()
        reopenDatabase()
        assertEquals(saved, repository.load(saved.project.id.value))
        assertEquals(imported, repository.load(imported.project.id.value))
        assertEquals(2, repository.recentProjects.first().size)
    }

    @Test fun backupRestoresMirroredGeometryWithoutChangingItsLocalShape() = runBlocking {
        val created = repository.create("Mirrored backup", ProjectTemplate.EMPTY, ProjectSettings())
        ownedProjectIds += created.project.id.value
        val shape = Shape.Box(Size3(2.0, 3.0, 4.0))
        val mirrored = Element(
            ElementId("mirrored-box"), created.project.id, created.layers.first().id,
            ElementBody.DrawingShape(shape),
            transform = Transform3(Point3(7.0, -9.0, 2.0), Rotation3(0.2, -0.3, 0.4), Scale3(-2.0, 1.5, 0.75)),
        )
        val saved = repository.save(created.copy(elements = listOf(mirrored)))
        repository.save(saved.copy(elements = listOf(mirrored.copy(transform = Transform3()))))

        val restored = repository.restoreBackup(saved.project.id.value)

        assertEquals(listOf(mirrored), restored.elements)
        assertEquals(shape, (restored.elements.single().body as ElementBody.DrawingShape).shape)
        assertEquals(restored, repository.load(saved.project.id.value))
    }

    @Test fun associatedMeasurementsAndViewPreferencesSurviveRoomAndPortableArchive() = runBlocking {
        val created=repository.create("Shared positions",ProjectTemplate.EMPTY,ProjectSettings())
        ownedProjectIds+=created.project.id.value
        val layer=created.layers.first()
        val level=created.levels.single()
        val system=BuildingSystem(SystemId("electrical"),created.project.id,"Electrical",Discipline.ELECTRICAL,0xFF009688,visible=false)
        val socket=Element(ElementId("socket"),created.project.id,layer.id,ElementBody.DrawingShape(Shape.Box(Size3(.1,.1,.1))),
            levelId=level.id,systemId=system.id,name="Socket",transform=Transform3(translation=Point3(.3,0.0,1.2)))
        val wall=Element(ElementId("wall"),created.project.id,layer.id,ElementBody.Wall(Point2(0.0,0.0),Point2(0.0,2.0),.2,3.0),levelId=level.id)
        val data=MeasurementData("Socket to wall",listOf(MeasurementAnchor(Point3(),socket.id,Point3(.3,0.0,1.2)),
            MeasurementAnchor(Point3(),wall.id,Point3())),showHeightAboveFloor=true,floorLevelId=level.id)
        val dimension=Element(ElementId("position-dimension"),created.project.id,layer.id,ElementBody.DrawingShape(
            Shape.Dimension(DimensionKind.ALIGNED,listOf(Point3(.3,0.0,1.2),Point3()),Point3(0.0,.5),data)),levelId=level.id)
        val saved=repository.save(created.copy(project=created.project.copy(settings=created.project.settings.copy(
            viewMode=ViewMode.SHADED_WITH_EDGES,showMeasurements=false)),layers=created.layers.map {it.copy(visible=false)},
            systems=listOf(system),elements=listOf(socket,wall,dimension)))
        database.close();reopenDatabase()
        val reopened=repository.load(saved.project.id.value)
        assertEquals(saved,reopened)
        assertEquals(.3,resolveMeasurement(reopened,reopened.elements.last())!!.horizontalDistance,1e-12)
        assertEquals(1.2,resolveMeasurement(reopened,reopened.elements.last())!!.heightAboveFloorMetres!!,1e-12)
        val archive=ByteArrayOutputStream().also {repository.exportProject(saved.project.id.value,it)}
        val imported=repository.importProject(ByteArrayInputStream(archive.toByteArray()))
        ownedProjectIds+=imported.project.id.value
        assertNotEquals(saved.project.id,imported.project.id)
        assertEquals(saved.project.settings,imported.project.settings)
        assertTrue(imported.layers.none {it.visible})
        assertTrue(imported.systems.none {it.visible})
        assertEquals(data,measurementShape(imported.elements.last())!!.measurement)
        assertEquals(.3,resolveMeasurement(imported,imported.elements.last())!!.horizontalDistance,1e-12)
        assertEquals(1.2,resolveMeasurement(imported,imported.elements.last())!!.heightAboveFloorMetres!!,1e-12)
    }

    private fun reopenDatabase() {
        database = Room.databaseBuilder(context, ProjectDatabase::class.java, databaseName).build()
        repository = RoomProjectRepository(context, database)
    }

    private fun toolShapes(): List<Pair<String, Shape>> {
        val rectangle = Polygon2(listOf(Point2(0.0, 0.0), Point2(3.0, 0.0), Point2(3.0, 2.0), Point2(0.0, 2.0)))
        val triangle = Polygon2(listOf(Point2(0.0, 0.0), Point2(2.0, 0.0), Point2(1.0, 3.0)))
        val polygon = Polygon2(
            outer = listOf(Point2(0.0, 0.0), Point2(4.0, 0.0), Point2(5.0, 2.0), Point2(3.0, 4.0), Point2(0.0, 3.0)),
            holes = listOf(listOf(Point2(1.0, 1.0), Point2(1.0, 2.0), Point2(2.0, 1.0))),
        )
        val path = listOf(Point3(), Point3(1.0, 2.0), Point3(3.0, 1.0), Point3(4.0, 3.0))
        return listOf(
            "line" to Shape.Line(Point3(), Point3(2.0, 1.0, 0.5)),
            "polyline" to Shape.Polyline(path),
            "closed-polyline" to Shape.Polyline(path, closed = true),
            "arc" to Shape.Arc(2.5, 0.25, 1.75),
            "clockwise-arc" to Shape.Arc(1.5, 2.0, -1.25),
            "circle" to Shape.Circle(2.5),
            "ellipse" to Shape.Ellipse(3.0, 1.5),
            "rectangle" to Shape.Polygon(rectangle),
            "polygon" to Shape.Polygon(polygon),
            "triangle" to Shape.Polygon(triangle),
            "spline" to Shape.Spline(path, degree = 3),
            "text" to Shape.Text("EEZY DRAW\nØ 25° – Café", 0.25),
            "leader" to Shape.Leader(path.take(3), "Level +3.50 m"),
            "aligned-dimension" to Shape.Dimension(DimensionKind.ALIGNED,
                listOf(Point3(), Point3(3.0, 4.0)), Point3(-0.4, 0.3)),
            "angular-dimension" to Shape.Dimension(DimensionKind.ANGULAR,
                listOf(Point3(), Point3(2.0), Point3(0.0, 2.0)), Point3(1.5, 1.5)),
            "radial-dimension" to Shape.Dimension(DimensionKind.RADIAL,
                listOf(Point3(), Point3(1.5, 1.5)), Point3(0.5, 0.25)),
            "box" to Shape.Box(Size3(2.0, 3.0, 4.0)),
            "sphere" to Shape.Sphere(2.0),
            "cylinder" to Shape.Cylinder(1.5, 4.0),
            "cone" to Shape.Cone(baseRadius = 2.0, height = 3.0),
            "pyramid" to Shape.Pyramid(rectangle, Point3(1.5, 1.0, 4.0)),
            // Also protect the remaining pre-existing sealed Shape variants in the same archive.
            "hatch" to Shape.Hatch(polygon, "diagonal", 0.2),
            "revision-cloud" to Shape.RevisionCloud(rectangle, 0.25),
            "extrusion" to Shape.Extrusion(triangle, -2.0),
            "revolution" to Shape.Revolution(triangle, 4.5),
        )
    }
}
