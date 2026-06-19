package com.nuvio.tv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R

@Composable
fun PulsingLogoIndicator(
    modifier: Modifier = Modifier
) {
    // One-time entrance fade so the logo doesn't pop in abruptly after profile selection.
    val entranceAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        entranceAlpha.animateTo(1f, animationSpec = tween(durationMillis = 450))
    }

    val infiniteTransition = rememberInfiniteTransition(label = "logoPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logoPulseAlpha"
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logoPulseScale"
    )

    val context = LocalContext.current
    // Loaded as a raw ImageBitmap (rather than via painterResource) so we can pass
    // filterQuality = High explicitly — that overload of Image() is the one that
    // exposes the parameter, and smooths the jagged/aliased edges on the logo's text
    // when it's scaled.
    val logoBitmap = remember {
        androidx.core.content.ContextCompat.getDrawable(context, R.drawable.nuvio_logo_pulse)
            ?.let { drawable ->
                val bmp = android.graphics.Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    android.graphics.Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp.asImageBitmap()
            }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (logoBitmap != null) {
            Image(
                bitmap = logoBitmap,
                contentDescription = null,
                modifier = Modifier
                    .width(220.dp)
                    .alpha(entranceAlpha.value * pulseAlpha)
                    .scale(pulseScale),
                alignment = Alignment.Center,
                contentScale = ContentScale.Fit,
                alpha = 1f,
                colorFilter = null,
                filterQuality = FilterQuality.High
            )
        }
    }
}
