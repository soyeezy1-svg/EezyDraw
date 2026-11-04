package com.eezydraw.core.editing

/** Point counts describe completed gestures; null means the user explicitly chooses Finish. */
enum class DrawingTool(val minimumPoints: Int, val autoFinishPoints: Int?) {
    SELECT(0, null),
    LINE(2, 2),
    POLYLINE(2, null),
    ARC(3, 3),
    CIRCLE(2, 2),
    ELLIPSE(2, 2),
    RECTANGLE(2, 2),
    POLYGON(3, null),
    TRIANGLE(3, 3),
    SPLINE(3, null),
    TEXT(1, 1),
    LEADER(2, null),
    DIM_ALIGNED(3, 3),
    DIM_ANGULAR(4, 4),
    DIM_RADIAL(3, 3),
    BOX(2, 2),
    SPHERE(2, 2),
    CYLINDER(2, 2),
    CONE(2, 2),
    PYRAMID(2, 2),
}

/**
 * Lengths are canonical metres; display-unit and pixel conversion belongs to the UI boundary.
 * Width is a diameter for round tools. Leader lettering retains its fixed preview height.
 */
data class ToolOptions(
    val text: String = "Text",
    val textHeight: Double = 0.25,
    val height: Double = 1.0,
    val closed: Boolean = false,
    val width: Double = 1.0,
    val depth: Double = 1.0,
    val useDefaultDimensions: Boolean = true,
    val keepProportions: Boolean = false,
)

/** Closed shapes can be inserted at a single location using explicit physical dimensions. */
fun supportsDefaultPlacement(tool: DrawingTool): Boolean = when (tool) {
    DrawingTool.CIRCLE, DrawingTool.ELLIPSE, DrawingTool.RECTANGLE, DrawingTool.POLYGON,
    DrawingTool.TRIANGLE, DrawingTool.BOX, DrawingTool.SPHERE, DrawingTool.CYLINDER,
    DrawingTool.CONE, DrawingTool.PYRAMID -> true
    else -> false
}
