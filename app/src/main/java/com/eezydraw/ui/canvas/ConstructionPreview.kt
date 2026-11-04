package com.eezydraw.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import com.eezydraw.core.editing.DrawingTool
import com.eezydraw.core.editing.ToolOptions
import com.eezydraw.core.editing.createDefaultSketch
import com.eezydraw.core.editing.createSketch
import com.eezydraw.core.editing.supportsDefaultPlacement
import com.eezydraw.core.geometry.DrawingScene
import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.Viewport2D
import com.eezydraw.core.geometry.buildDrawingScene
import com.eezydraw.core.model.*

/**
 * Builds a disposable ghost, not an edit command. Incomplete tools use a small physical
 * example until enough distinct picks exist; invalid picks fall back to their open path.
 * No IDs or geometry are added to [document]. Pointer coordinates are already metres.
 */
internal fun buildConstructionPreview(
    document: ProjectDocument,
    tool: DrawingTool,
    options: ToolOptions,
    points: List<Point3>,
    cursor: Point3?,
): DrawingScene? {
    if (tool == DrawingTool.SELECT || cursor == null && points.isEmpty()) return null
    val at = cursor ?: points.last()
    val defaultPlacement = options.useDefaultDimensions && supportsDefaultPlacement(tool)
    val picked = points.toMutableList().apply { if (cursor != null && lastOrNull() != cursor) add(cursor) }
    val origin = points.firstOrNull() ?: at
    val example = examplePoints(tool, origin, options)
    if (picked.isEmpty()) picked.add(at)
    val minimum = if (tool == DrawingTool.POLYLINE && options.closed) 3 else tool.minimumPoints
    while (picked.size < minimum) picked.add(example[picked.size])
    val geometry = runCatching {
        if (defaultPlacement || points.isEmpty() && supportsDefaultPlacement(tool)) createDefaultSketch(tool, at, options)
        else createSketch(tool, picked, options)
    }.getOrNull()
    val layer = document.layers.firstOrNull()?.id ?: LayerId("preview")
    val body = geometry?.let { ElementBody.DrawingShape(it.shape) } ?: run {
        val path = (points + listOfNotNull(cursor)).fold(mutableListOf<Point3>()) { result, point ->
            if (result.lastOrNull() != point) result.add(point)
            result
        }
        if (path.size < 2) return null
        ElementBody.DrawingShape(Shape.Polyline(path))
    }
    val element = Element(ElementId("construction-preview"), document.project.id, layer, body,
        transform = geometry?.transform ?: Transform3())
    return runCatching {
        buildDrawingScene(document.copy(elements = listOf(element),
            layers = document.layers.map { it.copy(visible = true) }))
    }.getOrNull()
}

private fun examplePoints(tool: DrawingTool, at: Point3, options: ToolOptions): List<Point3> {
    val width = options.width.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
    val depth = options.depth.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
    fun p(x: Double, y: Double) = Point3(at.x + x, at.y + y, at.z)
    return when (tool) {
        DrawingTool.ARC -> listOf(at, p(width / 2, depth / 2), p(width, 0.0))
        DrawingTool.TRIANGLE, DrawingTool.POLYGON, DrawingTool.SPLINE ->
            listOf(at, p(width, 0.0), p(width / 2, depth))
        DrawingTool.DIM_ALIGNED -> listOf(at, p(width, 0.0), p(width / 2, depth / 4))
        DrawingTool.DIM_ANGULAR -> listOf(at, p(width, 0.0), p(0.0, depth), p(width / 2, depth / 2))
        DrawingTool.DIM_RADIAL -> listOf(at, p(width / 2, 0.0), p(width * .75, depth / 4))
        DrawingTool.ELLIPSE -> listOf(at, p(width / 2, depth / 2))
        DrawingTool.RECTANGLE, DrawingTool.BOX, DrawingTool.PYRAMID -> listOf(at, p(width, depth))
        DrawingTool.CIRCLE, DrawingTool.SPHERE, DrawingTool.CYLINDER, DrawingTool.CONE -> listOf(at, p(width / 2, 0.0))
        DrawingTool.POLYLINE -> if (options.closed) listOf(at, p(width, 0.0), p(width / 2, depth)) else listOf(at, p(width, 0.0))
        else -> listOf(at, p(width, 0.0))
    }
}

/** The committed drawing and its GPU buffers stay untouched while the finger moves. */
@Composable
internal fun ConstructionPreview(
    document: ProjectDocument, tool: DrawingTool, options: ToolOptions,
    points: List<Point3>, cursor: Point2?, floorZ: Double, viewport: Viewport2D,
    modifier: Modifier = Modifier, orbit: OrbitCamera = OrbitCamera(), is3D: Boolean = false,
    cursorWorld: Point3? = null,
) {
    val color = MaterialTheme.colorScheme.primary.copy(alpha = .35f)
    val density = LocalDensity.current.density
    val current = cursorWorld ?: cursor?.let { Point3(it.x, it.y, floorZ) }
    val scene = remember(tool, options, points, current, document.project.settings, document.layers) {
        buildConstructionPreview(document, tool, options, points, current)
    }
    Canvas(modifier.fillMaxSize().testTag("construction_ghost")) {
        fun screen(p: Point3): Offset? = if (is3D) orbit.project(p, size.width.toDouble(), size.height.toDouble())
            ?.let { Offset(it.x.toFloat(), it.y.toFloat()) }
        else viewport.worldToScreen(Point2(p.x, p.y), size.width.toDouble(), size.height.toDouble())
            .let { Offset(it.x.toFloat(), it.y.toFloat()) }
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f * density, 4f * density))
        scene?.segments?.take(4096)?.forEach { edge ->
            val a = screen(edge.a); val b = screen(edge.b)
            if (a != null && b != null) drawLine(color, a, b, 2f * density, pathEffect = dash)
        }
        points.forEach { screen(it)?.let { at -> drawCircle(color, 4f * density, at) } }
    }
}

/** A measurement remains an uncommitted ghost until its final tap/Done action. */
@Composable
internal fun MeasurementGhostPreview(
    points: List<Point3>, cursor: Point3?, viewport: Viewport2D,
    modifier: Modifier = Modifier, orbit: OrbitCamera = OrbitCamera(), is3D: Boolean = false,
) {
    val color = MaterialTheme.colorScheme.primary.copy(alpha = .35f)
    val density = LocalDensity.current.density
    Canvas(modifier.fillMaxSize().testTag("measurement_ghost")) {
        fun screen(p: Point3): Offset? = if (is3D) orbit.project(p, size.width.toDouble(), size.height.toDouble())
            ?.let { Offset(it.x.toFloat(), it.y.toFloat()) }
        else viewport.worldToScreen(Point2(p.x, p.y), size.width.toDouble(), size.height.toDouble())
            .let { Offset(it.x.toFloat(), it.y.toFloat()) }
        val live = points + listOfNotNull(cursor?.takeIf { points.lastOrNull() != it })
        val projected = live.mapNotNull(::screen)
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f * density, 4f * density))
        projected.zipWithNext().forEach { (a, b) -> drawLine(color, a, b, 2f * density, pathEffect = dash) }
        projected.forEach { drawCircle(color, 4f * density, it) }
    }
}
