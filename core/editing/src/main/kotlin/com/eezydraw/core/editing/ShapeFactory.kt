package com.eezydraw.core.editing

import com.eezydraw.core.model.DimensionKind
import com.eezydraw.core.model.Point2
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.Polygon2
import com.eezydraw.core.model.Shape
import com.eezydraw.core.model.Size3
import com.eezydraw.core.model.Transform3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

private const val MIN_LENGTH = 1e-9
private const val TWO_PI = PI * 2

data class SketchGeometry(val shape: Shape, val transform: Transform3)

/**
 * Inserts a closed shape without interpreting a screen gesture as its physical dimensions.
 * [anchor] is already in canonical project metres. Round tools use width as diameter; a
 * sphere always remains round, while ellipse and rectangular tools use independent depth.
 * Local construction avoids adding/subtracting survey coordinates when measuring a shape.
 */
fun createDefaultSketch(
    tool: DrawingTool,
    anchor: Point3,
    options: ToolOptions = ToolOptions(),
): SketchGeometry {
    require(supportsDefaultPlacement(tool)) { "This tool needs drawing points." }
    val width = options.width.also { requireLength(it, "Width") }
    val depth = if (tool in setOf(DrawingTool.ELLIPSE, DrawingTool.RECTANGLE, DrawingTool.POLYGON,
            DrawingTool.TRIANGLE, DrawingTool.BOX, DrawingTool.PYRAMID)) {
        options.depth.also { requireLength(it, "Depth") }
    } else width
    val zero = Point3()
    val points = when (tool) {
        DrawingTool.CIRCLE, DrawingTool.SPHERE, DrawingTool.CYLINDER, DrawingTool.CONE ->
            listOf(zero, Point3(width / 2))
        DrawingTool.ELLIPSE -> listOf(zero, Point3(width / 2, depth / 2))
        DrawingTool.RECTANGLE, DrawingTool.BOX, DrawingTool.PYRAMID -> listOf(zero, Point3(width, depth))
        DrawingTool.TRIANGLE -> listOf(zero, Point3(width), Point3(width / 2, depth))
        DrawingTool.POLYGON -> listOf(
            Point3(0.0, depth / 2), Point3(width / 4, 0.0), Point3(width * .75, 0.0),
            Point3(width, depth / 2), Point3(width * .75, depth), Point3(width / 4, depth),
        )
        else -> error("Default placement is unavailable for this tool.")
    }
    val local = createSketch(tool, points, options)
    val offset = local.transform.translation
    return local.copy(transform = local.transform.copy(translation = Point3(
        anchor.x + offset.x, anchor.y + offset.y, anchor.z + offset.z,
    )))
}

/**
 * Converts picked project points on one XY level into small local Double coordinates.
 * Construction either returns a complete shape or throws without modifying a document.
 */
fun createSketch(
    tool: DrawingTool,
    points: List<Point3>,
    options: ToolOptions = ToolOptions(),
): SketchGeometry {
    require(tool != DrawingTool.SELECT) { "Select a drawing tool." }
    require(points.size >= tool.minimumPoints) { "Pick at least ${tool.minimumPoints} points." }
    tool.autoFinishPoints?.let { require(points.size == it) { "This tool needs $it points." } }
    val origin = points.first()
    require(points.all { abs(it.z - origin.z) <= MIN_LENGTH }) { "Pick points on the same level plane." }
    val local = points.map { Point3(it.x - origin.x, it.y - origin.y, 0.0) }
    fun result(shape: Shape, at: Point3 = origin) = SketchGeometry(shape, Transform3(translation = at))
    fun radius() = distance(local[1]).also { requireLength(it, "Radius") }
    fun height() = options.height.also { requireLength(it, "Height") }
    fun corners(): Polygon2 {
        val p = local[1]
        requireLength(abs(p.x), "Width")
        requireLength(abs(p.y), "Depth")
        return Polygon2(listOf(Point2(0.0, 0.0), Point2(p.x, 0.0), Point2(p.x, p.y), Point2(0.0, p.y)))
    }
    return when (tool) {
        DrawingTool.SELECT -> error("Selection does not create geometry.")
        DrawingTool.LINE -> {
            requireLength(distance(local[1]), "Line length")
            result(Shape.Line(local[0], local[1]))
        }
        DrawingTool.POLYLINE -> {
            validatePath(local)
            if (options.closed) validatePolygon(local)
            result(Shape.Polyline(local, options.closed))
        }
        DrawingTool.ARC -> arc(points, local)
        DrawingTool.CIRCLE -> result(Shape.Circle(radius()))
        DrawingTool.ELLIPSE -> {
            val p = local[1]
            requireLength(abs(p.x), "Horizontal radius")
            requireLength(abs(p.y), "Vertical radius")
            result(Shape.Ellipse(abs(p.x), abs(p.y)))
        }
        DrawingTool.RECTANGLE -> result(Shape.Polygon(corners()))
        DrawingTool.POLYGON, DrawingTool.TRIANGLE -> {
            validatePolygon(local)
            result(Shape.Polygon(Polygon2(local.map { Point2(it.x, it.y) })))
        }
        DrawingTool.SPLINE -> {
            validatePath(local)
            result(Shape.Spline(local, minOf(3, local.size - 1)))
        }
        DrawingTool.TEXT -> {
            require(options.text.isNotBlank()) { "Enter text." }
            requireLength(options.textHeight, "Text height")
            result(Shape.Text(options.text, options.textHeight))
        }
        DrawingTool.LEADER -> {
            validatePath(local)
            require(options.text.isNotBlank()) { "Enter leader text." }
            result(Shape.Leader(local, options.text))
        }
        DrawingTool.DIM_ALIGNED -> {
            val b = local[1]
            val length = distance(b)
            requireLength(length, "Dimension length")
            val nx = -b.y / length
            val ny = b.x / length
            val offset = local[2].x * nx + local[2].y * ny
            requireLength(abs(offset), "Dimension offset")
            result(Shape.Dimension(DimensionKind.ALIGNED, local.take(2), Point3(nx * offset, ny * offset)))
        }
        DrawingTool.DIM_ANGULAR -> {
            val b = local[1]
            val c = local[2]
            val lb = distance(b).also { requireLength(it, "First arm") }
            val lc = distance(c).also { requireLength(it, "Second arm") }
            require(abs(b.x / lb * (c.y / lc) - b.y / lb * (c.x / lc)) > 1e-12) {
                "Angular dimension arms must not be collinear."
            }
            requireLength(distance(local[3]), "Dimension label offset")
            result(Shape.Dimension(DimensionKind.ANGULAR, local.take(3), local[3]))
        }
        DrawingTool.DIM_RADIAL -> {
            radius()
            val offset = Point3(local[2].x - local[1].x, local[2].y - local[1].y)
            requireLength(distance(offset), "Dimension label offset")
            result(Shape.Dimension(DimensionKind.RADIAL, local.take(2), offset))
        }
        DrawingTool.BOX -> {
            val boundary = corners()
            val p = local[1]
            val base = Point3(origin.x + minOf(0.0, p.x), origin.y + minOf(0.0, p.y), origin.z)
            result(Shape.Box(Size3(abs(boundary.outer[2].x), abs(boundary.outer[2].y), height())), base)
        }
        DrawingTool.SPHERE -> result(Shape.Sphere(radius()))
        DrawingTool.CYLINDER -> result(Shape.Cylinder(radius(), height()))
        DrawingTool.CONE -> result(Shape.Cone(baseRadius = radius(), height = height()))
        DrawingTool.PYRAMID -> {
            val base = corners()
            result(Shape.Pyramid(base, Point3(local[1].x / 2, local[1].y / 2, height())))
        }
    }
}

private fun arc(points: List<Point3>, local: List<Point3>): SketchGeometry {
    val u = local[1]
    val v = local[2]
    requireLength(distance(u), "Arc start to middle")
    requireLength(distance(v), "Arc start to end")
    requireLength(hypot(v.x - u.x, v.y - u.y), "Arc middle to end")
    // Normalize before finding the circumcentre, avoiding squared absolute survey coordinates.
    val scale = maxOf(abs(u.x), abs(u.y), abs(v.x), abs(v.y))
    val ux = u.x / scale
    val uy = u.y / scale
    val vx = v.x / scale
    val vy = v.y / scale
    val determinant = 2 * (ux * vy - uy * vx)
    require(abs(determinant) > 1e-12) { "Arc points must not be collinear." }
    val u2 = ux * ux + uy * uy
    val v2 = vx * vx + vy * vy
    val cx = (u2 * vy - v2 * uy) / determinant * scale
    val cy = (ux * v2 - vx * u2) / determinant * scale
    val radius = hypot(cx, cy).also { requireLength(it, "Arc radius") }
    val start = atan2(-cy, -cx)
    val middle = positiveAngle(atan2(u.y - cy, u.x - cx) - start)
    val end = positiveAngle(atan2(v.y - cy, v.x - cx) - start)
    val sweep = if (middle <= end) end else end - TWO_PI
    require(abs(sweep) > 1e-12) { "Arc sweep is too small." }
    val origin = points.first()
    return SketchGeometry(
        Shape.Arc(radius, start, sweep),
        Transform3(translation = Point3(origin.x + cx, origin.y + cy, origin.z)),
    )
}

private fun positiveAngle(value: Double): Double = ((value % TWO_PI) + TWO_PI) % TWO_PI

private fun distance(point: Point3): Double = hypot(point.x, point.y)

private fun requireLength(value: Double, name: String) {
    require(value.isFinite() && value >= MIN_LENGTH) { "$name must be at least $MIN_LENGTH metres." }
}

private fun validatePath(points: List<Point3>) {
    points.zipWithNext().forEach { (a, b) -> requireLength(hypot(b.x - a.x, b.y - a.y), "Segment length") }
}

private fun validatePolygon(points: List<Point3>) {
    require(points.size >= 3) { "A closed outline needs at least three points." }
    validatePath(points)
    requireLength(hypot(points.first().x - points.last().x, points.first().y - points.last().y), "Closing edge")
    var twiceArea = 0.0
    for (i in points.indices) {
        val a = points[i]
        val b = points[(i + 1) % points.size]
        twiceArea += a.x * b.y - b.x * a.y
    }
    require(twiceArea.isFinite() && abs(twiceArea) >= MIN_LENGTH * MIN_LENGTH) { "Outline must enclose an area." }
}
