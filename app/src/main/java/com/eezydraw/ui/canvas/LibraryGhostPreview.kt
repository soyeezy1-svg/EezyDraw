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
import com.eezydraw.core.geometry.DrawingScene
import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.Viewport2D
import com.eezydraw.core.geometry.buildDrawingScene
import com.eezydraw.core.model.*

/** The actual library definition is previewed at its SI size without issuing an edit. */
internal fun buildLibraryPreview(document: ProjectDocument, id: LibraryItemId, at: Point3?): DrawingScene? {
    if (at == null) return null
    val item = document.libraryItems.find { it.id == id } ?: return null
    val layer = document.layers.firstOrNull()?.id ?: LayerId("library-preview")
    val element = Element(ElementId("library-preview"), document.project.id, layer,
        ElementBody.Component(item.id, item.defaultSize, item.parameters),
        name = item.name, transform = Transform3(translation = at), materialId = item.materialId)
    return runCatching {
        buildDrawingScene(document.copy(elements = listOf(element),
            layers = document.layers.map { it.copy(visible = true) }))
    }.getOrNull()
}

@Composable
internal fun LibraryGhostPreview(
    document: ProjectDocument, id: LibraryItemId, at: Point3?, viewport: Viewport2D,
    orbit: OrbitCamera, is3D: Boolean, modifier: Modifier = Modifier,
) {
    val colour = MaterialTheme.colorScheme.primary.copy(alpha = .35f)
    val density = LocalDensity.current.density
    val scene = remember(document.project.id, document.libraryItems, document.layers, document.materials, id, at) {
        buildLibraryPreview(document, id, at)
    }
    Canvas(modifier.fillMaxSize().testTag("library_ghost")) {
        fun screen(point: Point3): Offset? = if (is3D) orbit.project(point, size.width.toDouble(), size.height.toDouble())
            ?.let { Offset(it.x.toFloat(), it.y.toFloat()) }
        else viewport.worldToScreen(Point2(point.x, point.y), size.width.toDouble(), size.height.toDouble())
            .let { Offset(it.x.toFloat(), it.y.toFloat()) }
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f * density, 4f * density))
        scene?.segments?.take(4096)?.forEach { edge ->
            val a = screen(edge.a); val b = screen(edge.b)
            if (a != null && b != null) drawLine(colour, a, b, 2f * density, pathEffect = dash)
        }
    }
}
