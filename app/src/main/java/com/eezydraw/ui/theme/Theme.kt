package com.eezydraw.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.eezydraw.core.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF006580), onPrimary = Color.White,
    primaryContainer = Color(0xFFC2EFFF), onPrimaryContainer = Color(0xFF092B38),
    secondary = Color(0xFF53677F),
    background = Color(0xFFF4F7FC), surface = Color(0xFFF4F7FC),
    surfaceContainer = Color.White, surfaceContainerLow = Color(0xFFF9FBFF),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF65D7FF), onPrimary = Color(0xFF003546),
    primaryContainer = Color(0xFF164558), onPrimaryContainer = Color(0xFFC2EFFF),
    background = Color(0xFF101820), surface = Color(0xFF101820),
    surfaceContainer = Color(0xFF17202B), surfaceContainerLow = Color(0xFF131D26),
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
