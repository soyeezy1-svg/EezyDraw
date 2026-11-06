package com.eezydraw.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.eezydraw.R
import com.eezydraw.core.geometry.DrawingScene
import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.Viewport2D
import com.eezydraw.core.geometry.buildDrawingScene
import com.eezydraw.core.editing.LibraryPlacement
import com.eezydraw.core.editing.previewLibraryPlacement
import com.eezydraw.core.editing.projectPrecisionPoint
import com.eezydraw.core.model.*
import kotlin.math.max
import kotlin.math.roundToInt

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
    val markerColour = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surface
    val connectionLabel = stringResource(R.string.library_fitting_connection_point)
    BoxWithConstraints(modifier.fillMaxSize().testTag("library_ghost")) {
        val width = constraints.maxWidth.toDouble()
        val height = constraints.maxHeight.toDouble()
        Canvas(Modifier.fillMaxSize()) {
            fun screen(point: Point3): Offset? = projectPrecisionPoint(point,viewport,orbit,is3D,
                size.width.toDouble(),size.height.toDouble())?.let { Offset(it.x.toFloat(), it.y.toFloat()) }
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f * density, 4f * density))
            scene?.segments?.take(4096)?.forEach { edge ->
                val a = screen(edge.a); val b = screen(edge.b)
                if (a != null && b != null) drawLine(colour, a, b, 2f * density, pathEffect = dash)
            }
        }
        val connection = placement?.connectionPoint
        val projected = connection?.let { projectPrecisionPoint(it,viewport,orbit,is3D,width,height) }
        if (connection != null && projected != null && width > 0 && height > 0) {
            Canvas(Modifier.offset { IntOffset((projected.x-14*density).roundToInt(),(projected.y-14*density).roundToInt()) }
                .size(28.dp).testTag("fitting_connection_point").semantics {
                    contentDescription = connectionLabel
                    set(PrecisionScreenX,projected.x);set(PrecisionScreenY,projected.y)
                    set(PrecisionWorldX,connection.x);set(PrecisionWorldY,connection.y);set(PrecisionWorldZ,connection.z)
                }) {
                drawCircle(background.copy(alpha=.9f),12*density,center)
                drawCircle(markerColour,12*density,center,style=Stroke(2*density))
                drawCircle(markerColour,3*density,center)
            }
            val labelX=(projected.x+18*density).coerceIn(4.0,max(4.0,width-164*density))
            val labelY=(projected.y+18*density).coerceIn(4.0,max(4.0,height-40*density))
            Surface(Modifier.offset { IntOffset(labelX.roundToInt(),labelY.roundToInt()) }
                .widthIn(max=160.dp),color=background.copy(alpha=.94f),shape=MaterialTheme.shapes.small) {
                Text(connectionLabel,Modifier.padding(horizontal=8.dp,vertical=4.dp).testTag("fitting_connection_label"),
                    color=markerColour,style=MaterialTheme.typography.labelMedium)
            }
        }
    }
}
