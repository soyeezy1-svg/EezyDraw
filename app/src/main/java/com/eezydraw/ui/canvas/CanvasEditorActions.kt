package com.eezydraw.ui.canvas

import com.eezydraw.core.editing.*
import com.eezydraw.core.model.*

/** Session commands belong to the ViewModel; construction gestures stay on the canvas. */
data class CanvasEditorActions(
    val selectedIds: Set<ElementId> = emptySet(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val canPaste: Boolean = false,
    val onAddSketch: (SketchGeometry, String?) -> Boolean,
    val onSelect: (Set<ElementId>) -> Unit,
    val onTransform: (EditDelta) -> Boolean,
    val onProperties: (ElementProperties) -> Unit,
    val onCopy: () -> Unit,
    val onPaste: () -> Unit,
    val onDelete: () -> Unit,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onMirror: (MirrorAxis, Point3) -> Unit,
    val onArray: (Int, Int, Double, Double) -> Unit,
    val onViewMode: (ViewMode) -> Unit = {},
    val onLayerVisibility: (LayerId, Boolean) -> Unit = { _, _ -> },
    val onSystemVisibility: (SystemId, Boolean) -> Unit = { _, _ -> },
    val onMeasurementsVisibility: (Boolean) -> Unit = {},
    val onUpdateMeasurement: (ElementId,Point3,Point3,Point3,String,Boolean,Boolean) -> Unit = { _,_,_,_,_,_,_ -> },
    val onDeleteElements: (Set<ElementId>) -> Unit = {},
    val onInsertLibrary: (LibraryItemId,Point3,String?) -> Unit = { _,_,_ -> },
)

internal fun Bounds3.center() = Point3(
    min.x + (max.x - min.x) / 2,
    min.y + (max.y - min.y) / 2,
    min.z + (max.z - min.z) / 2,
)
