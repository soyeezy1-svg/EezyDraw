package com.eezydraw.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.tooling.preview.Preview
import com.eezydraw.core.model.ThemeMode
import com.eezydraw.ui.start.MenuActionDialog
import com.eezydraw.ui.start.SplashScreen
import com.eezydraw.ui.start.StartMenuAction
import com.eezydraw.ui.start.StartMenuScreen
import com.eezydraw.ui.start.StartMenuUiState
import com.eezydraw.ui.theme.EezyDrawTheme

/** Startup completion is saved so activity recreation does not replay the splash. */
@Composable
fun EezyDrawApp(
    menuState: StartMenuUiState = StartMenuUiState(),
    onAction: ((StartMenuAction) -> Unit)? = null,
) {
    var startupComplete by rememberSaveable { mutableStateOf(false) }
    var activeAction by rememberSaveable { mutableStateOf<String?>(null) }
    var themeMode by rememberSaveable { mutableStateOf(ThemeMode.SYSTEM) }
    EezyDrawTheme(themeMode = themeMode) {
        SyncSystemBars(dark = !startupComplete || MaterialTheme.colorScheme.background.luminance() < .5f)
        Surface(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = startupComplete,
                transitionSpec = {
                    (fadeIn(tween(450)) + slideInVertically(tween(450)) { it / 24 })
                        .togetherWith(fadeOut(tween(300)))
                },
                label = "Splash to start menu",
            ) { complete ->
                if (complete) {
                    StartMenuScreen(menuState, onAction = { action ->
                        if (onAction != null) onAction(action)
                        else activeAction = when (action) {
                            StartMenuAction.CreateProject -> "create"
                            StartMenuAction.ContinueProject -> "continue"
                            StartMenuAction.OpenFile -> "open"
                            StartMenuAction.Templates -> "templates"
                            StartMenuAction.Settings -> "settings"
                            StartMenuAction.Help -> "help"
                            is StartMenuAction.OpenRecent -> "recent"
                        }
                    })
                } else SplashScreen(onFinished = { startupComplete = true })
            }
            activeAction?.let { action ->
                MenuActionDialog(action, themeMode, { themeMode = it }, { activeAction = null })
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 1024, heightDp = 768)
@Composable
private fun StartMenuPreview() {
    EezyDrawTheme { StartMenuScreen(StartMenuUiState(), onAction = {}) }
}
