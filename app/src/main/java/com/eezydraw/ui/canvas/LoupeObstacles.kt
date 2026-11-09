package com.eezydraw.ui.canvas

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/** Bounds are measured in root pixels and converted once into canvas-local pixels. */
internal class LoupeObstacles {
    val bounds = mutableStateMapOf<String, Rect>()
}
internal val LocalLoupeObstacles = staticCompositionLocalOf<LoupeObstacles?> { null }

internal fun Modifier.loupeObstacle(key: String): Modifier = composed {
    val obstacles = LocalLoupeObstacles.current
    DisposableEffect(obstacles, key) { onDispose { obstacles?.bounds?.remove(key) } }
    onGloballyPositioned { coordinates ->
        val rect = coordinates.boundsInRoot()
        if (obstacles != null && obstacles.bounds[key] != rect) obstacles.bounds[key] = rect
    }
}
