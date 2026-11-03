package com.eezydraw.core.data

import com.eezydraw.core.model.*
import java.util.UUID

/** Small editable room layouts, not pre-rendered thumbnail illustrations or fabricated recents. */
internal fun createFromTemplate(name: String, template: ProjectTemplate, settings: ProjectSettings): ProjectDocument {
    val now = System.currentTimeMillis()
    val projectId = ProjectId(UUID.randomUUID().toString())
    val levelId = LevelId(UUID.randomUUID().toString())
    val architecture = LayerId(UUID.randomUUID().toString())
    val rooms = when (template) {
        ProjectTemplate.EMPTY -> emptyList()
        ProjectTemplate.OFFICE -> listOf(
            RoomSeed("Reception", 0.0, 0.0, 4.0, 4.0),
            RoomSeed("Office", 4.0, 0.0, 8.0, 6.0),
            RoomSeed("Meeting room", 0.0, 4.0, 4.0, 4.0),
        )
        ProjectTemplate.RESIDENTIAL -> listOf(
            RoomSeed("Living room", 0.0, 0.0, 6.0, 5.0),
            RoomSeed("Bedroom", 6.0, 0.0, 4.0, 4.0),
            RoomSeed("Kitchen", 0.0, 5.0, 4.0, 3.0),
        )
        ProjectTemplate.DATA_CENTER -> listOf(
            RoomSeed("Data hall", 0.0, 0.0, 12.0, 8.0),
            RoomSeed("Electrical room", 12.0, 0.0, 4.0, 4.0),
            RoomSeed("Cooling plant", 12.0, 4.0, 4.0, 4.0),
        )
    }
    return ProjectDocument(
        project = Project(projectId, name.trim(), now, now, settings = settings, template = template),
        levels = listOf(Level(levelId, projectId, "Level 1", 0.0, 0.2, 3.4)),
        layers = listOf(
            Layer(architecture, projectId, "Architecture"),
            Layer(LayerId(UUID.randomUUID().toString()), projectId, "Electrical", 0xFFE9A23B),
            Layer(LayerId(UUID.randomUUID().toString()), projectId, "Mechanical", 0xFF245FC1),
            Layer(LayerId(UUID.randomUUID().toString()), projectId, "Plumbing", 0xFF168060),
        ),
        elements = rooms.mapIndexed { index, room ->
            Element(
                id = ElementId(UUID.randomUUID().toString()), projectId = projectId,
                layerId = architecture, levelId = levelId, name = room.name,
                body = ElementBody.Room(
                    boundary = Polygon2(listOf(
                        Point2(room.x, room.y), Point2(room.x + room.width, room.y),
                        Point2(room.x + room.width, room.y + room.depth), Point2(room.x, room.y + room.depth),
                    )),
                    number = (index + 1).toString(), ceilingHeight = settings.defaultWallHeight,
                ),
            )
        },
    )
}

private data class RoomSeed(val name: String, val x: Double, val y: Double, val width: Double, val depth: Double)
