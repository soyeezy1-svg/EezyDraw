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
import com.eezydraw.core.editing.*
import com.eezydraw.core.geometry.Viewport2D
import com.eezydraw.core.geometry.buildDrawingScene
import com.eezydraw.core.model.*

/** Small construction overlay; the committed document and static GPU buffers stay untouched. */
@Composable
internal fun ConstructionPreview(
    document: ProjectDocument, tool: DrawingTool, options: ToolOptions,
    points: List<Point3>, cursor: Point2?, floorZ: Double, viewport: Viewport2D,
) {
    val color = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current.density
    val livePoints = points + listOfNotNull(cursor?.let { Point3(it.x,it.y,floorZ) })
    val segments = remember(tool,options,points,cursor,floorZ,document.project.settings) {
        val defaultPlacement = options.useDefaultDimensions && supportsDefaultPlacement(tool)
        if (defaultPlacement && cursor != null || livePoints.size >= tool.minimumPoints) runCatching {
            val shape = if (defaultPlacement) createDefaultSketch(tool,livePoints.last(),options) else createSketch(tool,livePoints,options)
            val layer = document.layers.firstOrNull()?.id ?: LayerId("preview")
            val element = Element(ElementId("construction-preview"),document.project.id,layer,
                ElementBody.DrawingShape(shape.shape),transform = shape.transform)
            buildDrawingScene(document.copy(elements = listOf(element),
                layers = document.layers.map { it.copy(visible = true) })).segments
        }.getOrNull() else null
    }
    Canvas(Modifier.fillMaxSize()) {
        fun screen(p: Point3): Offset = viewport.worldToScreen(Point2(p.x,p.y),size.width.toDouble(),size.height.toDouble())
            .let { Offset(it.x.toFloat(),it.y.toFloat()) }
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f*density,4f*density))
        if (segments != null) segments.take(4096).forEach { drawLine(color,screen(it.a),screen(it.b),2f*density,pathEffect = dash) }
        else livePoints.zipWithNext().forEach { (a,b) -> drawLine(color,screen(a),screen(b),2f*density,pathEffect = dash) }
        points.forEach { drawCircle(color,4f*density,screen(it)) }
    }
}
