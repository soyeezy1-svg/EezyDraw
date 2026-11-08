package com.eezydraw.core.model

import kotlinx.serialization.Serializable

@Serializable enum class CanvasPanelId { DRAW, EDIT, VIEW, MEASURE, LIBRARY, PROPERTIES, MEASUREMENTS, VISIBILITY, CLASHES, ISSUES }
@Serializable enum class PanelVisibility { OPEN, COLLAPSED, HIDDEN }

/** The top-left anchor is normalized to the viewport; content resizes below it without moving the panel. */
@Serializable
data class PanelPlacement(
    val state: PanelVisibility = PanelVisibility.COLLAPSED,
    val x: Double = .04,
    val y: Double = .04,
) {
    init { require(x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0) }
}

/** User-controlled presentation state; saved with the project and excluded from edit history. */
@Serializable
data class CanvasLayout(
    val panels: Map<CanvasPanelId, PanelPlacement> = defaultCanvasPanels(),
    val hideAll: Boolean = false,
) {
    fun panel(id: CanvasPanelId): PanelPlacement = panels[id] ?: defaultCanvasPanels().getValue(id)
    fun withPanel(id: CanvasPanelId, placement: PanelPlacement): CanvasLayout = copy(panels = panels + (id to placement))
}

fun defaultCanvasPanels(): Map<CanvasPanelId, PanelPlacement> = mapOf(
    CanvasPanelId.DRAW to PanelPlacement(x=.03,y=.23),
    CanvasPanelId.EDIT to PanelPlacement(x=.36,y=.23),
    CanvasPanelId.VIEW to PanelPlacement(PanelVisibility.OPEN,x=.50,y=1.0),
    CanvasPanelId.MEASURE to PanelPlacement(x=.64,y=.02),
    CanvasPanelId.LIBRARY to PanelPlacement(x=.68,y=.23),
    CanvasPanelId.PROPERTIES to PanelPlacement(PanelVisibility.HIDDEN,x=.98,y=.36),
    CanvasPanelId.MEASUREMENTS to PanelPlacement(PanelVisibility.HIDDEN,x=.68,y=.36),
    CanvasPanelId.ISSUES to PanelPlacement(PanelVisibility.HIDDEN,x=.65,y=.1),
    CanvasPanelId.CLASHES to PanelPlacement(PanelVisibility.HIDDEN,x=.68,y=.10),
    CanvasPanelId.VISIBILITY to PanelPlacement(PanelVisibility.HIDDEN,x=.03,y=.42),
)
