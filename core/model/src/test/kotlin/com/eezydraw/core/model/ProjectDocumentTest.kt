package com.eezydraw.core.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectDocumentTest {
    private val projectId = ProjectId("project-1")
    private val layerId = LayerId("architecture")
    private val levelId = LevelId("ground")

    @Test
    fun `document round trip preserves polymorphic geometry and shared wall references`() {
        val wallId = ElementId("shared-wall")
        val leftRoom = ElementBody.Room(
            boundary = Polygon2(listOf(Point2(0.0, 0.0), Point2(4.0, 0.0), Point2(4.0, 3.0), Point2(0.0, 3.0))),
            wallFaces = listOf(RoomWallFace(1, wallId, WallSide.LEFT)),
        )
        val rightRoom = ElementBody.Room(
            boundary = Polygon2(listOf(Point2(4.2, 0.0), Point2(8.0, 0.0), Point2(8.0, 3.0), Point2(4.2, 3.0))),
            wallFaces = listOf(RoomWallFace(3, wallId, WallSide.RIGHT)),
        )
        val document = ProjectDocument(
            project = Project(projectId, "Coordination", 1L, 2L),
            levels = listOf(Level(levelId, projectId, "Ground", 0.0, 0.2, 3.0)),
            layers = listOf(Layer(layerId, projectId, "Architecture")),
            elements = listOf(
                element(wallId, ElementBody.Wall(Point2(4.1, 0.0), Point2(4.1, 3.0), 0.2, 3.0)),
                element(ElementId("room-left"), leftRoom),
                element(ElementId("room-right"), rightRoom),
                element(ElementId("circle"), ElementBody.DrawingShape(Shape.Circle(0.25))),
                element(
                    ElementId("pipe"),
                    ElementBody.Route(
                        RouteKind.PIPE,
                        listOf(Point3(1.0, 1.0, 2.5), Point3(4.0, 1.0, 2.5)),
                        RouteSection.Circular(0.1),
                        ElevationSetting(2.5, ElevationReference.ABOVE_FINISHED_FLOOR),
                    ),
                ),
                element(
                    ElementId("socket"),
                    ElementBody.Component(
                        LibraryItemId("socket-1"), Size3(0.08, 0.04, 0.08),
                        parameters = mapOf("rating" to ParameterValue.Number(13.0)),
                        host = HostAttachment(wallId, HostMode.RECESSED, Point3(0.0, 1.0, 0.3), 0.04),
                    ),
                ),
            ),
        )

        val encoded = Json.encodeToString(document)
        val decoded = Json.decodeFromString<ProjectDocument>(encoded)

        assertEquals(document, decoded)
        assertTrue(encoded.contains("\"type\":\"route\""))
        val rooms = decoded.elements.map { it.body }.filterIsInstance<ElementBody.Room>()
        assertEquals(listOf(wallId, wallId), rooms.map { it.wallFaces.single().wallId })
    }

    @Test
    fun `large project coordinates retain double precision through serialization`() {
        val position = Point3(1_000_000_000.125, -1_000_000_000.25, 3.0625)
        assertEquals(position, Json.decodeFromString<Point3>(Json.encodeToString(position)))
    }

    private fun element(id: ElementId, body: ElementBody) = Element(
        id = id, projectId = projectId, layerId = layerId, levelId = levelId, body = body,
    )
}
