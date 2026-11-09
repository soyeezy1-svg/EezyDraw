package com.eezydraw.ui

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowInsetsControllerCompat
import com.eezydraw.ui.components.CadStyle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,29,35])
@LooperMode(LooperMode.Mode.PAUSED)
class SystemBarsTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Suppress("DEPRECATION")
    @Test fun canvasNavigationMatchesTheBottomBarWithoutThreeButtonContrastScrimInEitherTheme() {
        var dark by mutableStateOf(false)
        compose.setContent {SyncSystemBars(dark,canvas=true)}
        for(theme in listOf(false,true)) {
            compose.runOnIdle {dark=theme}
            compose.waitForIdle()
            compose.runOnIdle {
                val window=compose.activity.window
                // API 35 enforces transparent gesture navigation; older APIs accept our fallback.
                if(Build.VERSION.SDK_INT<35)assertEquals(CadStyle.chrome.toArgb(),window.navigationBarColor)
                if(Build.VERSION.SDK_INT>=29)assertFalse(window.isNavigationBarContrastEnforced)
                assertFalse(WindowInsetsControllerCompat(window,window.decorView).isAppearanceLightNavigationBars)
            }
        }
    }
}
