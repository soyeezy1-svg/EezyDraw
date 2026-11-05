package com.eezydraw.ui.canvas.panels

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.eezydraw.R
import com.eezydraw.core.model.CanvasPanelId
import com.eezydraw.core.model.PanelPlacement
import com.eezydraw.core.model.PanelVisibility
import kotlin.math.roundToInt

/**
 * User-owned panel state. Recomposition and canvas actions cannot open, hide or move it.
 * The top-left anchor is normalized to the viewport, independently of changing content.
 * Body height fits below that anchor. Drag previews publish only on release.
 */
@Composable
fun PersistentPanel(
    id: CanvasPanelId,
    title: String,
    placement: PanelPlacement,
    onPlacementChange: (PanelPlacement) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = panelIcon(id),
    width: Dp = 320.dp,
    compact: Boolean = false,
    expandTag: String? = null,
    collapseTag: String? = null,
    onBoundsChanged:(Rect?)->Unit={},
    content: @Composable () -> Unit,
) {
    if (placement.state == PanelVisibility.HIDDEN) return
    val latestBounds by rememberUpdatedState(onBoundsChanged)
    DisposableEffect(id) {onDispose {latestBounds(null)}}
    var dragPosition by remember(id) { mutableStateOf<Offset?>(null) }
    val latestPlacement by rememberUpdatedState(placement)
    val latestChange by rememberUpdatedState(onPlacementChange)
    val tag = id.name.lowercase()
    val expandLabel = stringResource(R.string.persistent_panel_expand, title)
    val collapseLabel = stringResource(R.string.persistent_panel_collapse, title)
    val hideLabel = stringResource(R.string.persistent_panel_hide, title)
    val dragLabel = stringResource(R.string.persistent_panel_drag, title)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density=LocalDensity.current
        val panelWidth=with(density) {if(placement.state==PanelVisibility.COLLAPSED)48.dp.toPx() else width.toPx()}
        val minimumHeight=with(density) {when {placement.state==PanelVisibility.COLLAPSED->48.dp;compact->56.dp;else->144.dp}.toPx()}
        val travel=Offset((constraints.maxWidth-panelWidth).coerceAtLeast(0f),(constraints.maxHeight-minimumHeight).coerceAtLeast(0f))
        val saved=Offset((placement.x*constraints.maxWidth).toFloat().coerceIn(0f,travel.x),
            (placement.y*constraints.maxHeight).toFloat().coerceIn(0f,travel.y))
        val position=dragPosition?.let {Offset(it.x.coerceIn(0f,travel.x),it.y.coerceIn(0f,travel.y))} ?: saved
        val bodyHeight=with(density) {(constraints.maxHeight-position.y).coerceAtLeast(1f).toDp()}
        val latestViewport by rememberUpdatedState(Offset(constraints.maxWidth.toFloat(),constraints.maxHeight.toFloat()))
        val latestTravel by rememberUpdatedState(travel)
        val latestPosition by rememberUpdatedState(position)
        fun dragHandle() = Modifier.pointerInput(id) {
            detectDragGestures(
                onDragStart = { dragPosition = latestPosition },
                onDrag = { change, delta ->
                    change.consume()
                    val next = (dragPosition ?: latestPosition) + delta
                    dragPosition = Offset(next.x.coerceIn(0f, latestTravel.x), next.y.coerceIn(0f, latestTravel.y))
                },
                onDragEnd = {
                    val end = dragPosition
                    if (end != null) latestChange(latestPlacement.copy(
                        x = if (latestViewport.x > 0f) (end.x / latestViewport.x).toDouble().coerceIn(0.0, 1.0) else latestPlacement.x,
                        y = if (latestViewport.y > 0f) (end.y / latestViewport.y).toDouble().coerceIn(0.0, 1.0) else latestPlacement.y,
                    ))
                    dragPosition = null
                },
                onDragCancel = { dragPosition = null },
            )
        }
        Surface(
            Modifier.offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
                .widthIn(max = maxWidth).heightIn(max = bodyHeight)
                .testTag("panel_$tag").onGloballyPositioned {latestBounds(it.boundsInParent())}.pointerInput(id) { detectTapGestures(onTap = {}) },
            shape = RoundedCornerShape(14.dp), tonalElevation = 5.dp, shadowElevation = 4.dp,
        ) {
            if (placement.state == PanelVisibility.COLLAPSED) {
                IconButton(
                    { latestChange(latestPlacement.copy(state = PanelVisibility.OPEN)) },
                    Modifier.size(48.dp).then(dragHandle())
                        .testTag(expandTag ?: if (id == CanvasPanelId.DRAW) "tools_expand" else "panel_${tag}_expand"),
                ) { Icon(icon, expandLabel) }
            } else if(compact) {
                Row(Modifier.width(width)) {
                    IconButton(
                        {latestChange(latestPlacement.copy(state=PanelVisibility.COLLAPSED))},
                        Modifier.size(48.dp).then(dragHandle()).testTag(collapseTag ?: "panel_${tag}_collapse"),
                    ) {Icon(icon,collapseLabel)}
                    Box(Modifier.weight(1f)) {content()}
                }
            } else {
                Column(Modifier.width(width).heightIn(max = bodyHeight)) {
                    Row(Modifier.fillMaxWidth()) {
                        IconButton({}, Modifier.size(48.dp).then(dragHandle()).testTag("panel_${tag}_drag")) {
                            Icon(Icons.Outlined.DragIndicator, dragLabel)
                        }
                        Text(title, Modifier.weight(1f).padding(vertical = 14.dp), maxLines = 1,
                            style = MaterialTheme.typography.titleSmall)
                        IconButton(
                            { latestChange(latestPlacement.copy(state = PanelVisibility.COLLAPSED)) },
                            Modifier.size(48.dp).testTag(collapseTag ?: if (id == CanvasPanelId.DRAW) "tools_collapse" else "panel_${tag}_collapse"),
                        ) { Icon(Icons.Outlined.ExpandLess, collapseLabel) }
                        IconButton(
                            { latestChange(latestPlacement.copy(state = PanelVisibility.HIDDEN)) },
                            Modifier.size(48.dp).testTag("panel_${tag}_hide"),
                        ) { Icon(Icons.Outlined.Close, hideLabel) }
                    }
                    HorizontalDivider()
                    content()
                }
            }
        }
    }
}

private fun panelIcon(id: CanvasPanelId): ImageVector = when (id) {
    CanvasPanelId.DRAW -> Icons.Outlined.Draw
    CanvasPanelId.EDIT, CanvasPanelId.PROPERTIES -> Icons.Outlined.Tune
    CanvasPanelId.VIEW, CanvasPanelId.VISIBILITY -> Icons.Outlined.Visibility
    CanvasPanelId.MEASURE -> Icons.Outlined.Straighten
    CanvasPanelId.LIBRARY -> Icons.Outlined.Inventory2
    CanvasPanelId.MEASUREMENTS -> Icons.AutoMirrored.Outlined.ListAlt
}
