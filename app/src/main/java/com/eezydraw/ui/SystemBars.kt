package com.eezydraw.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.toArgb
import com.eezydraw.ui.components.CadStyle

/** Keep system-bar contrast in sync with the navy splash and the session's selected theme. */
@Composable
internal fun SyncSystemBars(dark: Boolean, canvas: Boolean = false) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            view.context.activity()?.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                // Explicit styles disable the Android 10+ three-button contrast scrim.
                // The fallback colour matches the painted, edge-to-edge chrome.
                navigationBarStyle = if (canvas) SystemBarStyle.dark(CadStyle.chrome.toArgb())
                    else SystemBarStyle.auto(0xE6FFFFFF.toInt(), 0x801B1B1B.toInt()) { dark },
            )
        }
    }
}

private tailrec fun Context.activity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
