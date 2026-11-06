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
import com.eezydraw.core.editing.LibraryPlacement
import com.eezydraw.core.editing.previewLibraryPlacement
import com.eezydraw.core.model.*

/** The actual library definition is previewed at its SI size without issuing an edit. */
internal fun libraryPreviewDocument(document: ProjectDocument): ProjectDocument =
    if(document.layers.any {it.visible && !it.locked}) document else document.copy(layers=document.layers+
        Layer(LayerId("library-preview-layer"),document.project.id,"Preview"))

internal fun buildLibraryPreview(document: ProjectDocument, id: LibraryItemId, at: Point3?,
    levelId:LevelId?=null,planSymbols:Boolean=false): DrawingScene? {
    if (at == null) return null
    val item = document.libraryItems.find { it.id == id } ?: BuiltInLibrary.find(id) ?: return null
    return runCatching {buildLibraryPreview(document,
        previewLibraryPlacement(libraryPreviewDocument(document),item,at,levelId),planSymbols)}.getOrNull()
}

internal fun buildLibraryPreview(document:ProjectDocument,placement:LibraryPlacement,planSymbols:Boolean):DrawingScene =
    buildDrawingScene(libraryPreviewDocument(document).copy(elements=listOf(placement.element),
        libraryItems=document.libraryItems.filterNot {it.id==placement.item.id}+placement.item),planSymbols=planSymbols)

@Composable
internal fun LibraryGhostPreview(
    document: ProjectDocument, id: LibraryItemId, at: Point3?, viewport: Viewport2D,
    orbit: OrbitCamera, is3D: Boolean, modifier: Modifier = Modifier,
    placement:LibraryPlacement?=null,
) {
    val colour = MaterialTheme.colorScheme.primary.copy(alpha = .35f)
    val density = LocalDensity.current.density
    val scene = remember(document,id,at,placement,is3D) {
        placement?.let {buildLibraryPreview(document,it,!is3D)} ?: buildLibraryPreview(document,id,at,planSymbols=!is3D)
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
