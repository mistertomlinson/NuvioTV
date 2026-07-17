package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.ui.components.LoadingIndicator
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@Composable
fun LoadingOverlay(
    visible: Boolean,
    backdropUrl: String?,
    logoUrl: String?,
    title: String? = null,
    message: String? = null,
    modifier: Modifier = Modifier
) {
    var logoLoadFailed by remember(logoUrl) { mutableStateOf(false) }
    val showLogo = !logoUrl.isNullOrBlank() && !logoLoadFailed

    // Keep animation outside AnimatedVisibility so message changes don't stutter the pulse
    val infiniteTransition = rememberInfiniteTransition(label = "loadingLogoPulse")
    val logoScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "loadingLogoScale"
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(250)),
        exit = fadeOut(animationSpec = tween(600)),
        modifier = modifier
    ) {
        val context = LocalContext.current
        val logoAlpha = 1f
        val backdropRequest = remember(context, backdropUrl) {
            backdropUrl?.takeIf { it.isNotBlank() }?.let { url ->
                ImageRequest.Builder(context)
                    .data(url)
                    .decoderFactory(coil.decode.SvgDecoder.Factory())
                    .crossfade(true)
                    .build()
            }
        }
        val logoRequest = remember(context, logoUrl) {
            logoUrl?.takeIf { it.isNotBlank() }?.let { url ->
                ImageRequest.Builder(context)
                    .data(url)
                    .decoderFactory(coil.decode.SvgDecoder.Factory())
                    .crossfade(true)
                    .build()
            }
        }
        val overlayBrush = remember {
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color(0x4D000000),
                    0.35f to Color(0x99000000),
                    0.7f to Color(0xCC000000),
                    1f to Color(0xE6000000)
                )
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (backdropRequest != null) {
                AsyncImage(
                    model = backdropRequest,
                    contentDescription = "Loading backdrop",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(overlayBrush)
            )

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                LoadingOverlayLogo(
                    showLogo = showLogo,
                    logoRequest = logoRequest,
                    logoAlpha = logoAlpha,
                    logoScale = logoScale,
                    title = title,
                    onLogoError = { logoLoadFailed = true }
                )

                if (!message.isNullOrBlank()) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.72f),
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 48.dp, bottom = 36.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingOverlayLogo(
    showLogo: Boolean,
    logoRequest: coil.request.ImageRequest?,
    logoAlpha: Float,
    logoScale: Float,
    title: String?,
    onLogoError: () -> Unit
) {
    if (showLogo) {
        AsyncImage(
            model = logoRequest,
            contentDescription = "Loading logo",
            onError = { onLogoError() },
            modifier = Modifier
                .width(320.dp)
                .height(180.dp)
                .graphicsLayer {
                    alpha = logoAlpha
                    scaleX = logoScale
                    scaleY = logoScale
                },
            contentScale = ContentScale.Fit
        )
    } else if (!title.isNullOrBlank()) {
        // Null-logo text. Line breaks MUST match the landscape poster's null-logo:
        // breaks are determined by the width-to-fontsize ratio, so we run the fit
        // at the poster's actual title-box geometry (same constants as
        // ModernHomeRows: width = cardWidth*0.65-20dp scaled by 0.92, height =
        // cardHeight*0.40*0.92, cardWidth = portraitBase*1.24*1.34), then render
        // the result scaled up into the overlay's 320dp box — same breaks, larger.
        // Rendered with Compose Text (not AndroidView): a native TextView under an
        // animated graphicsLayer scale re-rasterizes per-glyph and wiggles.
        val ctx = LocalContext.current
        val density = androidx.compose.ui.platform.LocalDensity.current
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val caslonFamily = remember(ctx) { com.nuvio.tv.ui.theme.buildCaslonFamily(ctx) }
        val overlayCaslonTypeface = remember(ctx) {
            android.graphics.Typeface.Builder(ctx.assets, "fonts/caslon_regular.ttf")
                .setFontVariationSettings("'wght' 300")
                .setWeight(300)
                .build()
        }
        // Reference geometry: the landscape poster's title box.
        val posterCardWidth = remember(configuration) {
            (configuration.screenWidthDp.dp / 6.4f) * 1.24f * 1.34f
        }
        val posterCardHeight = posterCardWidth * (9f / 16f)
        val refWidthPx = with(density) { ((posterCardWidth * 0.65f) - 20.dp).toPx() * 0.92f }
        val refHeightPx = with(density) { (posterCardHeight * 0.40f).toPx() } * 0.92f
        val refBaseSizePx = with(density) { MaterialTheme.typography.titleMedium.fontSize.toPx() }
        val fitted = remember(title, refWidthPx, refHeightPx) {
            val paint = android.text.TextPaint().apply {
                typeface = overlayCaslonTypeface
                isAntiAlias = true
            }
            var size = refBaseSizePx
            val minSize = refBaseSizePx * 0.15f
            val widthI = refWidthPx.toInt().coerceAtLeast(1)
            while (size > minSize) {
                paint.textSize = size
                val layout = android.text.StaticLayout.Builder
                    .obtain(title, 0, title.length, paint, widthI)
                    .setLineSpacing(0f, 0.9f)
                    .setIncludePad(false)
                    .setMaxLines(3)
                    .setEllipsize(null)
                    .build()
                val fits = layout.lineCount <= 3 && layout.height <= refHeightPx.toInt()
                val noOverflow = (0 until layout.lineCount).none { layout.getEllipsisCount(it) > 0 }
                if (fits && noOverflow) break
                size -= refBaseSizePx * 0.04f
            }
            size.coerceAtLeast(minSize)
        }
        // Scale reference fit up into the overlay box.
        val overlayWidthPx = with(density) { 320.dp.toPx() }
        val renderScale = overlayWidthPx / refWidthPx
        val overlayFontSp = with(density) { (fitted * renderScale).toSp() }
        Text(
            text = title,
            fontFamily = caslonFamily,
            fontWeight = FontWeight.Light,
            fontSize = overlayFontSp,
            lineHeight = overlayFontSp * 1.08f,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 3,
            modifier = Modifier
                .width(320.dp)
                .graphicsLayer {
                    alpha = logoAlpha
                    scaleX = logoScale
                    scaleY = logoScale
                }
        )
    } else {
        Box(
            modifier = Modifier.size(180.dp),
            contentAlignment = Alignment.Center
        ) {
            LoadingIndicator()
        }
    }
}
