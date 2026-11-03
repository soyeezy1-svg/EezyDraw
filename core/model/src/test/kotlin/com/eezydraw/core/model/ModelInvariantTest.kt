package com.eezydraw.core.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelInvariantTest {
    @Test
    fun `nonfinite geometry is rejected at construction and decode`() {
        assertThrows(IllegalArgumentException::class.java) { Point3(Double.NaN, 0.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { Shape.Circle(Double.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) {
            Json.decodeFromString<Shape.Circle>("{\"radius\":-2.0}")
        }
    }

    @Test
    fun `degenerate wall and invalid route fitting references are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ElementBody.Wall(Point2(0.0, 0.0), Point2(0.0, 0.0), 0.2, 3.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ElementBody.Room(
                Polygon2(listOf(Point2(0.0, 0.0), Point2(1.0, 0.0), Point2(0.0, 1.0))),
                wallFaces = listOf(RoomWallFace(3, ElementId("wall"), WallSide.LEFT)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ElementBody.Route(
                RouteKind.CONDUIT,
                listOf(Point3(), Point3(1.0, 0.0, 0.0)),
                RouteSection.Circular(0.025),
                ElevationSetting(2.5, ElevationReference.ABOVE_FINISHED_FLOOR),
                listOf(RouteFitting(2, FittingKind.ELBOW_90)),
            )
        }
    }

    @Test
    fun `invalid dimensions settings and asset traversal are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { Size3(1.0, 0.0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { SnapSettings(gridSpacing = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { ProjectSettings(decimalPlaces = -1) }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectAsset(AssetId("asset"), ProjectId("project"), "../private.pdf", "application/pdf")
        }
    }
}
