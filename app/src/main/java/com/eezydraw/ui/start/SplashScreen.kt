package com.eezydraw.ui.start

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eezydraw.R

@Composable
internal fun SplashScreen(onFinished: () -> Unit) {
    val reveal = remember { Animatable(0f) }
    val latestOnFinished = rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
        // Frame-clock animation honours the system duration scale, including disabled animations.
        reveal.animateTo(1f, tween(durationMillis = 1200, easing = FastOutSlowInEasing))
        latestOnFinished.value()
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xFF101D32)).testTag("startup_splash"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val step = 48.dp.toPx()
            var x = size.width / 2 % step
            while (x < size.width) {
                drawLine(Color.White.copy(alpha = .035f), Offset(x, 0f), Offset(x, size.height))
                x += step
            }
            var y = size.height / 2 % step
            while (y < size.height) {
                drawLine(Color.White.copy(alpha = .035f), Offset(0f, y), Offset(size.width, y))
                y += step
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.graphicsLayer {
                alpha = (reveal.value * 3f).coerceIn(0f, 1f)
                translationY = (1f - reveal.value) * 20.dp.toPx()
            },
        ) {
            Image(
                painterResource(R.drawable.eezy_draw_logo), contentDescription = null,
                modifier = Modifier.size(216.dp).graphicsLayer {
                    scaleX = .8f + .2f * reveal.value
                    scaleY = scaleX
                    rotationZ = -6f * (1f - reveal.value)
                },
            )
            Text(
                stringResource(R.string.app_name), color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold, letterSpacing = 4.sp,
            )
            Text(
                stringResource(R.string.splash_tagline), color = Color(0xFFA8D5FF),
                style = MaterialTheme.typography.labelLarge, letterSpacing = 2.sp,
            )
        }
    }
}
