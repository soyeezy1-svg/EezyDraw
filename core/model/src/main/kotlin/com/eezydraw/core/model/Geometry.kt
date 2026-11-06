package com.eezydraw.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Domain coordinates use metres, radians, doubles and a right-handed Z-up frame. */
@Serializable
data class Point2(val x: Double, val y: Double) {
    init { require(x.isFinite() && y.isFinite()) }
}

@Serializable
data class Point3(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0) {
    init { require(x.isFinite() && y.isFinite() && z.isFinite()) }
}

/** Euler rotations are applied X, then Y, then Z about local axes. */
@Serializable
data class Rotation3(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0) {
    init { require(x.isFinite() && y.isFinite() && z.isFinite()) }
}

@Serializable
/** Signed nonzero scales preserve mirrored geometry without rewriting local coordinates. */
data class Scale3(val x: Double = 1.0, val y: Double = 1.0, val z: Double = 1.0) {
    init { require(x.isFinite() && y.isFinite() && z.isFinite() && x != 0.0 && y != 0.0 && z != 0.0) }
}

/** Local geometry is scaled, rotated, then translated into project coordinates. */
@Serializable
data class Transform3(
    val translation: Point3 = Point3(),
    val rotation: Rotation3 = Rotation3(),
    val scale: Scale3 = Scale3(),
)

@Serializable
data class Size3(val width: Double, val depth: Double, val height: Double) {
    init {
        require(width.isFinite() && depth.isFinite() && height.isFinite())
        require(width > 0 && depth > 0 && height > 0)
    }
}

/** No duplicate closing vertex. Topology validation (winding/intersections) is a future service. */
@Serializable
data class Polygon2(val outer: List<Point2>, val holes: List<List<Point2>> = emptyList()) {
    init { require(outer.size >= 3 && holes.all { it.size >= 3 }) }
}

@Serializable
data class Bounds3(val min: Point3, val max: Point3) {
    init { require(min.x <= max.x && min.y <= max.y && min.z <= max.z) }
}

@Serializable
sealed interface Shape {
    @Serializable @SerialName("line")
    data class Line(val start: Point3, val end: Point3) : Shape

    @Serializable @SerialName("polyline")
    data class Polyline(val points: List<Point3>, val closed: Boolean = false) : Shape {
        init { require(points.size >= if (closed) 3 else 2) }
    }

    /** Arc lies in local XY; sweep may be negative to specify direction. */
    @Serializable @SerialName("arc")
    data class Arc(val radius: Double, val startRadians: Double, val sweepRadians: Double) : Shape {
        init {
            require(radius.isFinite() && radius > 0)
            require(startRadians.isFinite() && sweepRadians.isFinite() && sweepRadians != 0.0)
        }
    }

    @Serializable @SerialName("circle")
    data class Circle(val radius: Double) : Shape {
        init { require(radius.isFinite() && radius > 0) }
    }

    @Serializable @SerialName("ellipse")
    data class Ellipse(val radiusX: Double, val radiusY: Double) : Shape {
        init { require(radiusX.isFinite() && radiusY.isFinite() && radiusX > 0 && radiusY > 0) }
    }

    @Serializable @SerialName("polygon")
    data class Polygon(val boundary: Polygon2) : Shape

    @Serializable @SerialName("spline")
    data class Spline(val controlPoints: List<Point3>, val degree: Int) : Shape {
        init { require(degree >= 1 && controlPoints.size > degree) }
    }

    @Serializable @SerialName("text")
    data class Text(val text: String, val height: Double) : Shape {
        init { require(height.isFinite() && height > 0) }
    }

    @Serializable @SerialName("dimension")
    data class Dimension(
        val kind: DimensionKind,
        val anchors: List<Point3>,
        val labelOffset: Point3,
        val measurement: MeasurementData? = null,
    ) : Shape {
        init {
            require(anchors.size >= 2)
            require(measurement == null || kind == DimensionKind.ALIGNED && anchors.size == 2) {
                "Saved measurements require two aligned anchors."
            }
        }
    }

    @Serializable @SerialName("leader")
    data class Leader(val points: List<Point3>, val text: String) : Shape {
        init { require(points.size >= 2) }
    }

    @Serializable @SerialName("hatch")
    data class Hatch(val boundary: Polygon2, val pattern: String, val spacing: Double) : Shape {
        init { require(spacing.isFinite() && spacing > 0) }
    }

    @Serializable @SerialName("revision_cloud")
    data class RevisionCloud(val boundary: Polygon2, val arcRadius: Double) : Shape {
        init { require(arcRadius.isFinite() && arcRadius > 0) }
    }

    @Serializable @SerialName("box")
    data class Box(val size: Size3) : Shape

    @Serializable @SerialName("sphere")
    data class Sphere(val radius: Double) : Shape {
        init { require(radius.isFinite() && radius > 0) }
    }

    @Serializable @SerialName("cylinder")
    data class Cylinder(val radius: Double, val height: Double) : Shape {
        init { require(radius.isFinite() && height.isFinite() && radius > 0 && height > 0) }
    }

    /** A zero top radius is a cone. */
    @Serializable @SerialName("cone")
    data class Cone(val baseRadius: Double, val topRadius: Double = 0.0, val height: Double) : Shape {
        init {
            require(baseRadius.isFinite() && topRadius.isFinite() && height.isFinite())
            require(baseRadius > 0 && topRadius >= 0 && height > 0)
        }
    }

    @Serializable @SerialName("pyramid")
    data class Pyramid(val base: Polygon2, val apex: Point3) : Shape

    /** Indexed local-space faces; counterclockwise triangles define outward-facing normals. */
    @Serializable @SerialName("mesh")
    data class Mesh(val vertices: List<Point3>, val triangles: List<Int>) : Shape {
        init {
            require(vertices.size in 3..500_002) { "Mesh must contain between 3 and 500002 vertices." }
            require(triangles.isNotEmpty() && triangles.size % 3 == 0 && triangles.size <= 1_500_000) {
                "Mesh must contain complete index triples for at most 500000 triangles."
            }
            for (index in triangles.indices step 3) {
                val a = triangles[index]; val b = triangles[index + 1]; val c = triangles[index + 2]
                require(a in vertices.indices && b in vertices.indices && c in vertices.indices) {
                    "Mesh triangle indices must reference existing vertices."
                }
                require(a != b && b != c && c != a) { "Mesh triangles must reference three distinct vertices." }
            }
        }
    }

    @Serializable @SerialName("extrusion")
    data class Extrusion(val profile: Polygon2, val distance: Double) : Shape {
        init { require(distance.isFinite() && distance != 0.0) }
    }

    /** Profile X is distance from the local Z axis; Y is height along that axis. */
    @Serializable @SerialName("revolution")
    data class Revolution(val profile: Polygon2, val sweepRadians: Double) : Shape {
        init { require(sweepRadians.isFinite() && sweepRadians != 0.0) }
    }
}

@Serializable enum class DimensionKind { ALIGNED, ANGULAR, RADIAL }
