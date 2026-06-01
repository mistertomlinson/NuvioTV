package com.nuvio.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
fun MonochromePosterPlaceholder(
    modifier: Modifier = Modifier
) {
    val base = NuvioColors.BackgroundCard
    val strokeColor = NuvioColors.TextTertiary.copy(alpha = 0.28f)
    val centerButtonBorder = NuvioColors.TextTertiary.copy(alpha = 0.18f)
    val backgroundGradient = remember(base) {
        Brush.verticalGradient(
            colors = listOf(
                base.copy(alpha = 0.92f),
                base.copy(alpha = 0.98f)
            )
        )
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundGradient)
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
