package com.nuvio.tv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R

@Composable
fun PulsingLogoIndicator(
    modifier: Modifier = Modifier,
    active: Boolean = true,
    onCycleComplete: (() -> Unit)? = null
) {
    // One-time entrance fade so the logo doesn't pop in abruptly after profile selection.
    val entranceAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        entranceAlpha.animateTo(1f, animationSpec = tween(durationMillis = 450))
    }

    // Subtle shimmer: a low-alpha light band sweeps horizontally across the logo
    // box on a loop. Sweep offset goes off-screen-left to off-screen-right so the
    // band fully enters and exits. Freezes (parked off-screen) when not active.
    val infiniteTransition = rememberInfiniteTransition(label = "logoShimmer")
    val sweep by infiniteTransition.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800),
            repeatMode = RepeatMode.Restart
        ),
        label = "logoShimmerSweep"
    )
    val sweepPos = if (active) sweep else 2f

    // Fire onCycleComplete each time the sweep completes a pass (value wraps from
    // near-end back to near-start). Lets the caller dismiss only on whole cycles.
    val prevSweep = remember { androidx.compose.runtime.mutableStateOf(sweep) }
    LaunchedEffect(sweep, active) {
        if (active) {
            if (sweep < prevSweep.value - 0.5f) {
                onCycleComplete?.invoke()
            }
        }
        prevSweep.value = sweep
    }

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
            val aspect = logoBitmap.width.toFloat() / logoBitmap.height.toFloat()
            Box(
                modifier = Modifier
                    .width(220.dp)
                    .aspectRatio(aspect)
                    .alpha(entranceAlpha.value)
                    // Isolate into its own layer so SrcAtop blends against the
                    // logo's own pixels, not whatever is behind the Box.
                    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                    .drawWithContent {
                        // Draw the logo bitmap scaled to fill this box.
                        drawImage(
                            image = logoBitmap,
                            dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                            filterQuality = FilterQuality.High
                        )
                        // Sheen band, composited ONLY onto the logo's opaque pixels.
                        val bandW = size.width * 0.30f
                        val cx = size.width * sweepPos
                        drawRect(
                            brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                colors = listOf(
                                    androidx.compose.ui.graphics.Color.Transparent,
                                    androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f),
                                    androidx.compose.ui.graphics.Color.Transparent
                                ),
                                startX = cx - bandW,
                                endX = cx + bandW
                            ),
                            blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop
                        )
                    }
            )
        }
    }
}
