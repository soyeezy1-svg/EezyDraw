package com.eezydraw.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.eezydraw.core.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF245FC1), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEAFF), onPrimaryContainer = Color(0xFF102E58),
    secondary = Color(0xFF53677F),
    background = Color(0xFFF4F7FC), surface = Color(0xFFF4F7FC),
    surfaceContainer = Color.White, surfaceContainerLow = Color(0xFFF9FBFF),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C8FF), onPrimary = Color(0xFF113663),
    primaryContainer = Color(0xFF203F6A), onPrimaryContainer = Color(0xFFDDEAFF),
    background = Color(0xFF101D32), surface = Color(0xFF101D32),
    surfaceContainer = Color(0xFF19283D), surfaceContainerLow = Color(0xFF152238),
)

@Composable
fun EezyDrawTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
