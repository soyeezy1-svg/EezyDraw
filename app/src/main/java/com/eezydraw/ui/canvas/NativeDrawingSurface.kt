package com.eezydraw.ui.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.eezydraw.core.geometry.DrawingScene
import com.eezydraw.core.model.Bounds3
import com.eezydraw.core.model.Point3

private val EmptyDrawingScene = DrawingScene(emptyList(), emptyList(), Bounds3(Point3(), Point3()))

@Composable
internal fun NativeDrawingSurface(
    scene: DrawingScene?,
    frame: CanvasFrame,
    modifier: Modifier,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember(context) { DrawingSurfaceView(context) }
    DisposableEffect(view, owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            view.release()
            view.onPause()
        }
    }
    AndroidView(
        factory = { view },
        modifier = modifier,
        update = {
            it.onRenderError = onError
            it.render(scene ?: EmptyDrawingScene, frame)
        },
    )
}
