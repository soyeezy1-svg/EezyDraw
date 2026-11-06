package com.eezydraw.core.editing

import com.eezydraw.core.model.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class LibraryPlacementTest {
    private val project = ProjectId("project")
    private val layer = LayerId("items")
    private val level = Level(LevelId("first"), project, "First", 3.0, 0.2, 3.5)
    private val wallItem = LibraryItem(LibraryItemId("switch"), "Switch", Discipline.ELECTRICAL, "Electrical",
        Size3(0.1, 0.04, 0.1), mounting = MountingType.WALL, defaultMountingHeight = 1.2,
        defaultSystem = "Power", defaultMaterial = "Plastic")
    private fun document(elements: List<Element> = emptyList()) = ProjectDocument(
        Project(project, "Mounting", 0, 0), levels = listOf(level), layers = listOf(Layer(layer, project, "Items")), elements = elements,
    )
    private fun wall(id: String = "wall", y: Double = 0.0, reference: WallReference = WallReference.CENTERLINE,
        transform: Transform3 = Transform3(translation = Point3(z = level.elevation))) = Element(
        ElementId(id), project, layer, ElementBody.Wall(Point2(0.0, y), Point2(10.0, y), 0.2, 3.0, reference),
        levelId = level.id, transform = transform,
    )
    private fun preview(doc: ProjectDocument, point: Point3, item: LibraryItem = wallItem) =
        previewLibraryPlacement(doc, item, point, level.id, layer, ElementId("placed"))

    @Test fun `wall insertion meets nearest finish face and local positive Y points outward`() {
        val doc = document(listOf(wall()))
        for ((point, expectedY, outward) in listOf(Triple(Point3(3.0, 2.0), 0.1, 1.0), Triple(Point3(3.0, -2.0), -0.1, -1.0))) {
            val candidate = preview(doc, point)
            val element = candidate.element
            assertTrue(candidate.wallSnapped)
            assertEquals(expectedY, element.transform.translation.y, 1e-9)
            assertEquals(4.2, element.transform.translation.z, 1e-9)
            assertEquals(1.2, candidate.mountingHeight, 1e-9)
            assertEquals(outward, cos(element.transform.rotation.z), 1e-9)
            val host = (element.body as ElementBody.Component).host!!
            assertEquals(ElementId("wall"), host.hostElementId)
            assertEquals(1.2, host.localPosition.z, 1e-9)
        }
    }

    @Test fun `wall reference faces offsets and closest host are resolved in world coordinates`() {
        for ((reference, expectedY) in listOf(WallReference.CENTERLINE to 0.1,
            WallReference.LEFT_FINISH_FACE to 0.0, WallReference.RIGHT_FINISH_FACE to 0.2)) {
            val candidate = preview(document(listOf(wall(reference = reference))), Point3(2.0, 2.0))
            assertEquals(expectedY, candidate.element.transform.translation.y, 1e-9)
        }
        val closest = preview(document(listOf(wall(), wall("near", 4.0))), Point3(2.0, 3.7))
        assertEquals(ElementId("near"), (closest.element.body as ElementBody.Component).host!!.hostElementId)
        assertEquals(3.9, closest.element.transform.translation.y, 1e-9)
    }

    @Test fun `rotated scaled mirrored diagonal wall keeps exact outward perpendicular`() {
        val host = wall(transform = Transform3(Point3(1e9, -1e9, 3.0), Rotation3(z = 0.4), Scale3(-2.0, 1.0, 1.0)))
            .copy(body = ElementBody.Wall(Point2(0.0, 0.0), Point2(10.0, 10.0), 0.2, 3.0))
        val doc = document(listOf(host))
        val pick = measurementToWorld(host.transform, Point3(4.0, 6.0))
        val candidate = preview(doc, pick)
        val origin = candidate.element.transform.translation
        val hostLocal = measurementToLocal(host.transform, origin)
        assertEquals(0.2 / 2, kotlin.math.abs((hostLocal.y - hostLocal.x) / kotlin.math.sqrt(2.0)), 2e-7)
        val a = measurementToWorld(host.transform, Point3())
        val b = measurementToWorld(host.transform, Point3(10.0, 10.0))
        val normal = Point2(-sin(candidate.element.transform.rotation.z), cos(candidate.element.transform.rotation.z))
        assertEquals(0.0, (b.x - a.x) * normal.x + (b.y - a.y) * normal.y, 3e-6)
        assertTrue((pick.x - origin.x) * normal.x + (pick.y - origin.y) * normal.y > 0.0)
    }

    @Test fun `wall endpoint clamps width inside face and insufficient host height is rejected`() {
        val candidate = preview(document(listOf(wall())), Point3(-5.0, 2.0))
        assertEquals(0.0, candidate.element.transform.translation.x, 1e-9)
        assertThrows(IllegalArgumentException::class.java) {
            preview(document(listOf(wall())), Point3(2.0, 2.0), wallItem.copy(defaultMountingHeight = 2.95))
        }
    }

    @Test fun `wall placement excludes other levels hidden faces and missing wall`() {
        val other = wall().copy(levelId = LevelId("second"))
        for (doc in listOf(document(), document(listOf(other)), document(listOf(wall().withDisplay(ObjectDisplay.HIDDEN))))) {
            assertThrows(IllegalArgumentException::class.java) { preview(doc, Point3(1.0, 1.0)) }
        }
        val lockedWall = wall().withLock(LockMode.ALL)
        assertTrue(preview(document(listOf(lockedWall)), Point3(1.0, 1.0)).wallSnapped)
    }

    @Test fun `room finish boundaries and holes host items without generating physical walls`() {
        val room = Element(ElementId("room"), project, layer, ElementBody.Room(Polygon2(
            listOf(Point2(0.0, 0.0), Point2(8.0, 0.0), Point2(8.0, 8.0), Point2(0.0, 8.0)),
            listOf(listOf(Point2(3.0, 3.0), Point2(5.0, 3.0), Point2(5.0, 5.0), Point2(3.0, 5.0))),
        )), levelId = level.id, transform = Transform3(Point3(z = 3.0)))
        val outer = preview(document(listOf(room)), Point3(2.0, 0.2))
        assertEquals(0.0, outer.element.transform.translation.y, 1e-9)
        assertEquals(room.id, (outer.element.body as ElementBody.Component).host!!.hostElementId)
        val hole = preview(document(listOf(room)), Point3(4.0, 4.0))
        assertEquals(3.0, hole.element.transform.translation.y, 1e-9)
    }

    @Test fun `linked room wall is not used as a duplicate finish face`() {
        val physical = wall()
        val room = Element(ElementId("room"), project, layer,
            ElementBody.Room(Polygon2(listOf(Point2(0.0, 0.0), Point2(8.0, 0.0), Point2(8.0, 8.0), Point2(0.0, 8.0))),
                wallFaces = listOf(RoomWallFace(0, physical.id, WallSide.LEFT))),
            levelId = level.id, transform = Transform3(Point3(z = 3.0)))
        val candidate = preview(document(listOf(room, physical)), Point3(2.0, 0.02))
        assertEquals(physical.id, (candidate.element.body as ElementBody.Component).host!!.hostElementId)
        assertEquals(0.1, candidate.element.transform.translation.y, 1e-9)
    }

    @Test fun `floor ignores picked Z and ceiling inserts at level ceiling hanging below it`() {
        val floor = preview(document(), Point3(2.0, 3.0, 800.0), wallItem.copy(mounting = MountingType.FLOOR))
        assertEquals(3.0, floor.element.transform.translation.z, 0.0)
        assertEquals(0.0, floor.mountingHeight, 0.0)
        assertFalse(floor.wallSnapped)
        val ceiling = preview(document(), Point3(2.0, 3.0, -800.0), wallItem.copy(mounting = MountingType.CEILING))
        assertEquals(6.3, ceiling.element.transform.translation.z, 1e-9)
        assertEquals(3.3, ceiling.mountingHeight, 1e-9)
        assertEquals(-1.0, ceiling.element.transform.scale.z, 0.0)
    }

    @Test fun `ceiling follows containing rooms finish ceiling instead of structural storey`() {
        val room = Element(ElementId("room"), project, layer,
            ElementBody.Room(Polygon2(listOf(Point2(0.0, 0.0), Point2(8.0, 0.0), Point2(8.0, 8.0), Point2(0.0, 8.0))), ceilingHeight = 2.8),
            levelId = level.id, transform = Transform3(Point3(z = 3.0)))
        val ceiling = preview(document(listOf(room)), Point3(2.0, 3.0), wallItem.copy(mounting = MountingType.CEILING))
        assertEquals(5.8, ceiling.element.transform.translation.z, 1e-9)
        assertEquals(2.8, ceiling.mountingHeight, 1e-9)
    }

    @Test fun `placement requires an editable visible layer`() {
        val locked = document().copy(layers = listOf(Layer(layer, project, "Locked", locked = true)))
        assertThrows(IllegalArgumentException::class.java) {
            preview(locked, Point3(), wallItem.copy(mounting = MountingType.FLOOR))
        }
    }
}
