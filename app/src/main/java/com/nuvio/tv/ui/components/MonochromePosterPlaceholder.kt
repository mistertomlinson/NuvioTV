package com.nuvio.tv.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.State
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import androidx.tv.material3.Icon
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioColors

@Composable
fun rememberPosterShimmerTranslateState(): State<Float> {
    val transition =
        rememberInfiniteTransition(label = "poster_shimmer")

    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1400,
                easing = LinearEasing
            )
        ),
        label = "poster_shimmer_translate"
    )
}

@Composable
fun MonochromePosterPlaceholder(
    modifier: Modifier = Modifier,
    shimmerTranslateState: State<Float> =
        rememberPosterShimmerTranslateState()
) {
    val shimmerColors = listOf(
        NuvioColors.SurfaceVariant.copy(alpha = 0.30f),
        NuvioColors.SurfaceVariant.copy(alpha = 0.60f),
        NuvioColors.SurfaceVariant.copy(alpha = 0.30f)
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                onDrawBehind {
                    val translate =
                        shimmerTranslateState.value

                    drawRect(
                        brush = Brush.linearGradient(
                            colors = shimmerColors,
                            start = Offset(
                                translate - 1000f,
                                0f
                            ),
                            end = Offset(translate, 0f)
                        )
                    )
                }
            }
    )
}

@Composable
private fun rememberRawSvgPainter(
    context: android.content.Context,
    @androidx.annotation.RawRes rawRes: Int
): Painter {
    val model = remember(rawRes, context) {
        ImageRequest.Builder(context)
            .data(rawRes)
            .decoderFactory(SvgDecoder.Factory())
            .build()
    }
    return rememberAsyncImagePainter(model = model)
}
