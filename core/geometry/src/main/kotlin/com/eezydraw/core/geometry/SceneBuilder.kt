package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

private const val CURVE_STEPS = 64
private const val MAX_SEGMENTS = 500_000
private const val MAX_CHUNKS = 16_384
private const val CHUNK_SEGMENTS = 512
private const val TILE_SIZE = 128.0
private const val TAU = PI * 2

/**
 * Builds a bounded wireframe snapshot without Android dependencies. Call off the UI thread.
 * Curves use 64 segments per turn; routes show their actual centreline. Library geometry is
 * scaled to the instance size, with a size box only when its definition has no geometry.
 * The explicit 500,000-edge / 16,384-chunk budget rejects oversized documents rather than
 * silently dropping geometry or allocating unbounded GPU buffers.
 */
fun buildDrawingScene(document: ProjectDocument): DrawingScene {
    val layers = document.layers.associateBy { it.id }
    val systems = document.systems.associateBy { it.id }
    val library = document.libraryItems.associateBy { it.id }
    val segments = ArrayList<SceneSegment>(minOf(document.elements.size * 12L, MAX_SEGMENTS.toLong()).toInt())
    val bounds = SceneBounds()
    val levelBounds = linkedMapOf<String?, SceneBounds>()
    val chunks = ChunkCollector()
    for (element in document.elements) {
        val layer = layers[element.layerId]
        val system = element.systemId?.let(systems::get)
        if (layer?.visible == false || system?.visible == false) continue
        val transform = SceneTransform(element.transform)
        val levelId = element.levelId?.value
        val color = system?.colourArgb ?: layer?.colourArgb ?: 0xFF334455
        val emit: (Point3, Point3) -> Unit = { localA, localB ->
            val a = transform.apply(localA)
            val b = transform.apply(localB)
            if (a != b) {
                require(segments.size < MAX_SEGMENTS) { "Drawing exceeds the $MAX_SEGMENTS wireframe edge limit." }
                val edge = SceneSegment(a, b, element.id.value, levelId, color)
                segments.add(edge)
                bounds.add(a); bounds.add(b)
                levelBounds.getOrPut(levelId, ::SceneBounds).apply { add(a); add(b) }
                chunks.add(edge)
            }
        }
        when (val body = element.body) {
            is ElementBody.DrawingShape -> drawShape(body.shape, emit)
            is ElementBody.Room -> extrude(body.boundary, 0.0, body.ceilingHeight, emit)
            is ElementBody.Slab -> extrude(body.boundary, -body.thickness, 0.0, emit)
            is ElementBody.Wall -> wall(body, emit)
            is ElementBody.Route -> polyline(body.path, false, emit)
            is ElementBody.Component -> {
                val definition = library[body.libraryItemId]
                if (definition == null || definition.geometry.isEmpty()) {
                    box(body.size, emit)
                } else {
                    val sx = body.size.width / definition.defaultSize.width
                    val sy = body.size.depth / definition.defaultSize.depth
                    val sz = body.size.height / definition.defaultSize.height
                    val instance: (Point3, Point3) -> Unit = { a, b ->
                        emit(point(a.x * sx, a.y * sy, a.z * sz), point(b.x * sx, b.y * sy, b.z * sz))
                    }
                    definition.geometry.forEach { drawShape(it, instance) }
                }
            }
        }
    }
    return DrawingScene(segments, chunks.finish(), bounds.build(), levelBounds.mapValues { it.value.build() })
}

/** Intrinsic local XYZ Euler rotation: R = Rx * Ry * Rz; scale precedes rotation. */
private class SceneTransform(private val transform: Transform3) {
    private val cx = cos(transform.rotation.x)
    private val sx = sin(transform.rotation.x)
    private val cy = cos(transform.rotation.y)
    private val sy = sin(transform.rotation.y)
    private val cz = cos(transform.rotation.z)
    private val sz = sin(transform.rotation.z)

    fun apply(p: Point3): Point3 {
        val x = p.x * transform.scale.x
        val y = p.y * transform.scale.y
        val z = p.z * transform.scale.z
        val zx = cz * x - sz * y
        val zy = sz * x + cz * y
        val yx = cy * zx + sy * z
        val yz = -sy * zx + cy * z
        return point(
            yx + transform.translation.x,
            cx * zy - sx * yz + transform.translation.y,
            sx * zy + cx * yz + transform.translation.z,
        )
    }
}

private fun point(x: Double, y: Double, z: Double = 0.0): Point3 {
    require(x.isFinite() && y.isFinite() && z.isFinite()) { "Drawing geometry exceeds finite coordinate limits." }
    return Point3(x, y, z)
}

private fun drawShape(shape: Shape, emit: (Point3, Point3) -> Unit) {
    when (shape) {
        is Shape.Line -> emit(shape.start, shape.end)
        is Shape.Polyline -> polyline(shape.points, shape.closed, emit)
        is Shape.Arc -> curve(shape.startRadians, boundedSweep(shape.sweepRadians), emit) { a ->
            point(shape.radius * cos(a), shape.radius * sin(a))
        }
        is Shape.Circle -> circle(shape.radius, 0.0, emit)
        is Shape.Ellipse -> curve(0.0, TAU, emit) { a -> point(shape.radiusX * cos(a), shape.radiusY * sin(a)) }
        is Shape.Polygon -> rings(shape.boundary).forEach { ring(it, 0.0, emit) }
        is Shape.Spline -> spline(shape, emit)
        is Shape.Text -> textStrokes(shape.text, shape.height, Point3(), emit)
        is Shape.Dimension -> dimension(shape, emit)
        is Shape.Leader -> {
            polyline(shape.points, false, emit)
            arrow(shape.points[0], shape.points[1], emit)
            textStrokes(shape.text, 0.25, shape.points.last(), emit)
        }
        is Shape.Hatch -> rings(shape.boundary).forEach { ring(it, 0.0, emit) }
        is Shape.RevisionCloud -> revisionCloud(shape, emit)
        is Shape.Box -> box(shape.size, emit)
        is Shape.Sphere -> {
            circle(shape.radius, 0.0, emit)
            curve(0.0, TAU, emit) { a -> point(shape.radius * cos(a), 0.0, shape.radius * sin(a)) }
            curve(0.0, TAU, emit) { a -> point(0.0, shape.radius * cos(a), shape.radius * sin(a)) }
            for (z in listOf(-shape.radius * 0.5, shape.radius * 0.5)) {
                circle(shape.radius * sqrt(0.75), z, emit)
            }
        }
        is Shape.Cylinder -> cone(shape.radius, shape.radius, shape.height, emit)
        is Shape.Cone -> cone(shape.baseRadius, shape.topRadius, shape.height, emit)
        is Shape.Pyramid -> rings(shape.base).forEach { vertices ->
            ring(vertices, 0.0, emit)
            vertices.forEach { emit(point(it.x, it.y), shape.apex) }
        }
        is Shape.Extrusion -> extrude(shape.profile, 0.0, shape.distance, emit)
        is Shape.Revolution -> revolution(shape, emit)
    }
}

private fun boundedSweep(sweep: Double) = sweep.coerceIn(-TAU, TAU)

private fun curve(
    start: Double,
    sweep: Double,
    emit: (Point3, Point3) -> Unit,
    evaluate: (Double) -> Point3,
) {
    val steps = ceil(abs(sweep) / TAU * CURVE_STEPS).toInt().coerceIn(2, CURVE_STEPS)
    var previous = evaluate(start)
    for (i in 1..steps) {
        // Closing at the exact start avoids a microscopic extra edge at 2π.
        val next = if (i == steps && abs(sweep) == TAU) evaluate(start) else evaluate(start + sweep * i / steps)
        emit(previous, next)
        previous = next
    }
}

private fun circle(radius: Double, z: Double, emit: (Point3, Point3) -> Unit) =
    curve(0.0, TAU, emit) { a -> point(radius * cos(a), radius * sin(a), z) }

private fun polyline(points: List<Point3>, closed: Boolean, emit: (Point3, Point3) -> Unit) {
    for (i in 1 until points.size) emit(points[i - 1], points[i])
    if (closed) emit(points.last(), points.first())
}

private fun rings(boundary: Polygon2) = sequenceOf(boundary.outer) + boundary.holes.asSequence()

private fun ring(vertices: List<Point2>, z: Double, emit: (Point3, Point3) -> Unit) {
    for (i in vertices.indices) {
        val a = vertices[i]
        val b = vertices[(i + 1) % vertices.size]
        emit(point(a.x, a.y, z), point(b.x, b.y, z))
    }
}

private fun extrude(boundary: Polygon2, bottom: Double, top: Double, emit: (Point3, Point3) -> Unit) {
    rings(boundary).forEach { vertices ->
        ring(vertices, bottom, emit)
        ring(vertices, top, emit)
        vertices.forEach { emit(point(it.x, it.y, bottom), point(it.x, it.y, top)) }
    }
}

private fun wall(wall: ElementBody.Wall, emit: (Point3, Point3) -> Unit) {
    val dx = wall.end.x - wall.start.x
    val dy = wall.end.y - wall.start.y
    val length = hypot(dx, dy)
    require(length.isFinite() && length > 0) { "Wall reference line exceeds finite coordinate limits." }
    val nx = -dy / length
    val ny = dx / length
    val left = when (wall.reference) {
        WallReference.CENTERLINE -> wall.thickness / 2
        WallReference.LEFT_FINISH_FACE -> 0.0
        WallReference.RIGHT_FINISH_FACE -> wall.thickness
    }
    val right = left - wall.thickness
    fun offset(p: Point2, distance: Double) = Point2(p.x + nx * distance, p.y + ny * distance)
    extrude(
        Polygon2(listOf(offset(wall.start, left), offset(wall.end, left), offset(wall.end, right), offset(wall.start, right))),
        0.0, wall.height, emit,
    )
}

private fun box(size: Size3, emit: (Point3, Point3) -> Unit) = extrude(
    Polygon2(listOf(Point2(0.0, 0.0), Point2(size.width, 0.0), Point2(size.width, size.depth), Point2(0.0, size.depth))),
    0.0, size.height, emit,
)

private fun cone(base: Double, top: Double, height: Double, emit: (Point3, Point3) -> Unit) {
    circle(base, 0.0, emit)
    if (top > 0) circle(top, height, emit)
    for (i in 0 until 8) {
        val angle = TAU * i / 8
        emit(point(base * cos(angle), base * sin(angle)), point(top * cos(angle), top * sin(angle), height))
    }
}

private fun revolution(shape: Shape.Revolution, emit: (Point3, Point3) -> Unit) {
    val sweep = boundedSweep(shape.sweepRadians)
    rings(shape.profile).forEach { vertices ->
        vertices.forEach { p -> curve(0.0, sweep, emit) { a -> point(p.x * cos(a), p.x * sin(a), p.y) } }
        val meridians = ceil(abs(sweep) / TAU * 8).toInt().coerceIn(1, 8)
        val closed = abs(sweep) == TAU
        for (i in 0..meridians) {
            if (closed && i == meridians) continue
            val angle = sweep * i / meridians
            val c = cos(angle)
            val s = sin(angle)
            for (j in vertices.indices) {
                val a = vertices[j]
                val b = vertices[(j + 1) % vertices.size]
                emit(point(a.x * c, a.x * s, a.y), point(b.x * c, b.x * s, b.y))
            }
        }
    }
}

/** Uniform clamped B-spline, because the model stores no explicit knots or weights. */
private fun spline(shape: Shape.Spline, emit: (Point3, Point3) -> Unit) {
    val points = shape.controlPoints
    val degree = shape.degree
    require(degree <= 32) { "Spline degree exceeds the supported wireframe limit of 32." }
    fun knot(i: Int): Double = when {
        i <= degree -> 0.0
        i >= points.size -> 1.0
        else -> (i - degree).toDouble() / (points.size - degree)
    }
    fun evaluate(t: Double): Point3 {
        if (t == 1.0) return points.last()
        val span = (floor(t * (points.size - degree)).toInt() + degree).coerceAtMost(points.lastIndex)
        val work = Array(degree + 1) { points[span - degree + it] }
        for (r in 1..degree) for (j in degree downTo r) {
            val index = span - degree + j
            val denominator = knot(index + degree - r + 1) - knot(index)
            val alpha = if (denominator == 0.0) 0.0 else (t - knot(index)) / denominator
            val a = work[j - 1]
            val b = work[j]
            work[j] = point(a.x * (1 - alpha) + b.x * alpha, a.y * (1 - alpha) + b.y * alpha, a.z * (1 - alpha) + b.z * alpha)
        }
        return work[degree]
    }
    var previous = points.first()
    for (i in 1..CURVE_STEPS) {
        val next = evaluate(i.toDouble() / CURVE_STEPS)
        emit(previous, next)
        previous = next
    }
}

private fun dimension(shape: Shape.Dimension, emit: (Point3, Point3) -> Unit) {
    val a = shape.anchors[0]
    val b = shape.anchors[1]
    fun offset(p: Point3) = point(p.x + shape.labelOffset.x, p.y + shape.labelOffset.y, p.z + shape.labelOffset.z)
    when (shape.kind) {
        DimensionKind.ALIGNED -> {
            val c = offset(a)
            val d = offset(b)
            emit(a, c); emit(b, d); emit(c, d)
            arrow(c, d, emit); arrow(d, c, emit)
        }
        DimensionKind.RADIAL -> {
            emit(a, b); emit(b, offset(b)); arrow(b, a, emit)
        }
        DimensionKind.ANGULAR -> {
            val c = shape.anchors.getOrNull(2)
            if (c == null) {
                emit(a, b)
            } else {
                emit(a, b); emit(a, c)
                val start = atan2(b.y - a.y, b.x - a.x)
                var sweep = atan2(c.y - a.y, c.x - a.x) - start
                while (sweep > PI) sweep -= TAU
                while (sweep < -PI) sweep += TAU
                val radius = minOf(hypot(b.x - a.x, b.y - a.y), hypot(c.x - a.x, c.y - a.y)) * 0.6
                if (radius > 0) curve(start, sweep, emit) { angle -> point(a.x + radius * cos(angle), a.y + radius * sin(angle), a.z) }
            }
        }
    }
}

private fun arrow(tip: Point3, toward: Point3, emit: (Point3, Point3) -> Unit) {
    val dx = toward.x - tip.x
    val dy = toward.y - tip.y
    val length = hypot(dx, dy)
    if (length <= 0 || !length.isFinite()) return
    val size = (length * 0.08).coerceIn(0.005, 0.2)
    val ux = dx / length * size
    val uy = dy / length * size
    emit(tip, point(tip.x + ux - uy * 0.4, tip.y + uy + ux * 0.4, tip.z))
    emit(tip, point(tip.x + ux + uy * 0.4, tip.y + uy - ux * 0.4, tip.z))
}

private fun revisionCloud(shape: Shape.RevisionCloud, emit: (Point3, Point3) -> Unit) {
    rings(shape.boundary).forEach { vertices ->
        for (i in vertices.indices) {
            val a = vertices[i]
            val b = vertices[(i + 1) % vertices.size]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val length = hypot(dx, dy)
            if (length == 0.0) continue
            require(length.isFinite()) { "Revision cloud exceeds finite coordinate limits." }
            val lobes = ceil(length / (shape.arcRadius * 2)).toInt().coerceIn(1, 64)
            for (lobe in 0 until lobes) {
                var previous = point(a.x + dx * lobe / lobes, a.y + dy * lobe / lobes)
                for (step in 1..4) {
                    val t = step / 4.0
                    val fraction = (lobe + t) / lobes
                    val bulge = if (step == 4) 0.0 else sin(PI * t) * length / lobes * 0.25
                    val next = point(a.x + dx * fraction - dy / length * bulge, a.y + dy * fraction + dx / length * bulge)
                    emit(previous, next)
                    previous = next
                }
            }
        }
    }
}

// A compact CAD stroke alphabet avoids platform font/shaping work on the background builder.
// Unsupported glyphs use a diamond. This is a wireframe annotation preview, not text editing.
private val glyphStrokes = mapOf(
    '0' to "abcdef", '1' to "bc", '2' to "abged", '3' to "abgcd", '4' to "fgbc",
    '5' to "afgcd", '6' to "afgecd", '7' to "abc", '8' to "abcdefg", '9' to "abfgcd",
    'A' to "abcefg", 'B' to "abcdefg", 'C' to "afed", 'D' to "abcdil", 'E' to "afged",
    'F' to "afge", 'G' to "afedcg", 'H' to "febcg", 'I' to "adil", 'J' to "bced",
    'K' to "fejm", 'L' to "fed", 'M' to "febchj", 'N' to "febchm", 'O' to "abcdef",
    'P' to "abfge", 'Q' to "abcdefm", 'R' to "abfegm", 'S' to "afgcd", 'T' to "ail",
    'U' to "febcd", 'V' to "fekm", 'W' to "febckm", 'X' to "hjkm", 'Y' to "hjl",
    'Z' to "ajkd", '-' to "g", '+' to "gil", '/' to "jk", '\\' to "hm", '_' to "d",
    '=' to "gd", '.' to "o", ':' to "op", '(' to "jk", ')' to "hm", '?' to "abglo",
)
private val glyphEdges = arrayOf(
    doubleArrayOf(0.0, 1.0, 0.6, 1.0), doubleArrayOf(0.6, 1.0, 0.6, 0.5),
    doubleArrayOf(0.6, 0.5, 0.6, 0.0), doubleArrayOf(0.6, 0.0, 0.0, 0.0),
    doubleArrayOf(0.0, 0.0, 0.0, 0.5), doubleArrayOf(0.0, 0.5, 0.0, 1.0),
    doubleArrayOf(0.0, 0.5, 0.6, 0.5), doubleArrayOf(0.0, 1.0, 0.3, 0.5),
    doubleArrayOf(0.3, 1.0, 0.3, 0.5), doubleArrayOf(0.6, 1.0, 0.3, 0.5),
    doubleArrayOf(0.3, 0.5, 0.0, 0.0), doubleArrayOf(0.3, 0.5, 0.3, 0.0),
    doubleArrayOf(0.3, 0.5, 0.6, 0.0), doubleArrayOf(0.0, 0.0, 0.0, 0.0),
    doubleArrayOf(0.26, 0.0, 0.34, 0.0), doubleArrayOf(0.26, 0.5, 0.34, 0.5),
)

private fun textStrokes(text: String, height: Double, origin: Point3, emit: (Point3, Point3) -> Unit) {
    var x = origin.x
    var y = origin.y
    for (character in text) {
        if (character == '\n') { x = origin.x; y -= height * 1.4; continue }
        if (character != ' ') {
            val strokes = glyphStrokes[character.uppercaseChar()]
            if (strokes == null) {
                val vertices = listOf(point(x, y + height * 0.5, origin.z), point(x + height * 0.3, y + height, origin.z),
                    point(x + height * 0.6, y + height * 0.5, origin.z), point(x + height * 0.3, y, origin.z))
                polyline(vertices, true, emit)
            } else for (stroke in strokes) {
                val edge = glyphEdges[stroke - 'a']
                emit(point(x + edge[0] * height, y + edge[1] * height, origin.z),
                    point(x + edge[2] * height, y + edge[3] * height, origin.z))
            }
        }
        x += height * 0.8
    }
}

private class SceneBounds {
    private var minX = Double.POSITIVE_INFINITY
    private var minY = Double.POSITIVE_INFINITY
    private var minZ = Double.POSITIVE_INFINITY
    private var maxX = Double.NEGATIVE_INFINITY
    private var maxY = Double.NEGATIVE_INFINITY
    private var maxZ = Double.NEGATIVE_INFINITY
    fun add(p: Point3) {
        minX = minOf(minX, p.x); minY = minOf(minY, p.y); minZ = minOf(minZ, p.z)
        maxX = maxOf(maxX, p.x); maxY = maxOf(maxY, p.y); maxZ = maxOf(maxZ, p.z)
    }
    fun build() = if (minX.isFinite()) Bounds3(Point3(minX, minY, minZ), Point3(maxX, maxY, maxZ)) else Bounds3(Point3(), Point3())
}

private data class ChunkKey(val x: Long, val y: Long, val z: Long, val levelId: String?)

private class ChunkCollector {
    private val pending = linkedMapOf<ChunkKey, ChunkBuffer>()
    private val complete = ArrayList<SceneChunk>()
    private var edgeCount = 0

    fun add(edge: SceneSegment) {
        val dx = edge.b.x - edge.a.x
        val dy = edge.b.y - edge.a.y
        val dz = edge.b.z - edge.a.z
        val span = maxOf(abs(dx), abs(dy), abs(dz))
        require(span.isFinite() && span <= TILE_SIZE * MAX_SEGMENTS) {
            "Drawing edge exceeds the ${TILE_SIZE * MAX_SEGMENTS} metre render span limit."
        }
        val pieces = ceil(span / TILE_SIZE).toInt().coerceAtLeast(1)
        require(edgeCount + pieces <= MAX_SEGMENTS) { "Drawing exceeds the $MAX_SEGMENTS render edge limit." }
        edgeCount += pieces
        var a = edge.a
        for (i in 1..pieces) {
            val t = i.toDouble() / pieces
            val b = if (i == pieces) edge.b else point(edge.a.x + dx * t, edge.a.y + dy * t, edge.a.z + dz * t)
            val key = ChunkKey(
                floor((a.x + (b.x - a.x) * 0.5) / TILE_SIZE).toLong(),
                floor((a.y + (b.y - a.y) * 0.5) / TILE_SIZE).toLong(),
                floor((a.z + (b.z - a.z) * 0.5) / TILE_SIZE).toLong(), edge.levelId,
            )
            var buffer = pending[key]
            if (buffer == null || buffer.count == CHUNK_SEGMENTS) {
                if (buffer != null) complete.add(buffer.finish())
                require(complete.size + pending.size < MAX_CHUNKS) { "Drawing exceeds the $MAX_CHUNKS spatial chunk limit." }
                buffer = ChunkBuffer(a, edge.levelId)
                pending[key] = buffer
            }
            buffer.add(a, b, edge.colorArgb)
            a = b
        }
    }

    fun finish(): List<SceneChunk> {
        pending.values.forEach { complete.add(it.finish()) }
        return complete
    }
}

private class ChunkBuffer(private val origin: Point3, private val levelId: String?) {
    var count = 0
        private set
    private var values = FloatArray(14 * 8)
    private val bounds = SceneBounds()
    fun add(a: Point3, b: Point3, color: Long) {
        if ((count + 1) * 14 > values.size) values = values.copyOf(minOf(values.size * 2, CHUNK_SEGMENTS * 14))
        vertex(a, color, count * 14)
        vertex(b, color, count * 14 + 7)
        bounds.add(a); bounds.add(b)
        count++
    }
    private fun vertex(p: Point3, color: Long, offset: Int) {
        values[offset] = (p.x - origin.x).toFloat()
        values[offset + 1] = (p.y - origin.y).toFloat()
        values[offset + 2] = (p.z - origin.z).toFloat()
        values[offset + 3] = ((color shr 16) and 255) / 255f
        values[offset + 4] = ((color shr 8) and 255) / 255f
        values[offset + 5] = (color and 255) / 255f
        values[offset + 6] = ((color shr 24) and 255) / 255f
    }
    fun finish() = SceneChunk(origin, bounds.build(), levelId, values.copyOf(count * 14))
}
