package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.theme.NuvioColors
import androidx.compose.ui.res.stringResource

internal data class HeroPlatformSnapshot(
    val platformId: String,
    val preview: HeroPreview
)

private data class ModernHeroSecondaryMeta(
    val highlightText: String?,
    val ageRating: String?,
    val status: String?,
    val details: List<String>
)

@Composable
internal fun ModernHeroMediaLayer(
    heroBackdrop: String?,
    heroBackdropAlpha: Float,
    shouldPlayHeroTrailer: Boolean,
    heroTrailerUrl: String?,
    heroTrailerAudioUrl: String?,
    heroTrailerAlpha: Float,
    muted: Boolean,
    onTrailerEnded: () -> Unit,
    onFirstFrameRendered: () -> Unit,
    modifier: Modifier,
    requestWidthPx: Int,
    requestHeightPx: Int,
    backdropCrossfadeDuration: Int = 350
) {
    val localContext = LocalContext.current
    Box(modifier = modifier) {
        Crossfade(
            targetState = heroBackdrop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = heroBackdropAlpha },
            animationSpec = tween(durationMillis = backdropCrossfadeDuration),
            label = "modernHeroBackground"
        ) { imageUrl ->
            val imageModel = remember(localContext, imageUrl, requestWidthPx, requestHeightPx) {
                ImageRequest.Builder(localContext)
                    .data(imageUrl)
                    .crossfade(false)
                    .size(width = requestWidthPx, height = requestHeightPx)
                    .build()
            }
            AsyncImage(
                model = imageModel,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopEnd
            )
        }

        if (shouldPlayHeroTrailer) {
            TrailerPlayer(
                trailerUrl = heroTrailerUrl,
                trailerAudioUrl = heroTrailerAudioUrl,
                isPlaying = true,
                onEnded = onTrailerEnded,
                onFirstFrameRendered = onFirstFrameRendered,
                muted = muted,
                cropToFill = true,
                overscanZoom = MODERN_TRAILER_OVERSCAN_ZOOM,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = heroTrailerAlpha }
            )
        }

    }
}

@Composable
internal fun ModernHeroGradientLayer(
    bgColor: Color,
    allowLetterboxing: Boolean,
    trailerTransitionProgress: Float,
    modifier: Modifier,
    cinematicMode: Boolean = false,
    shouldPlayHeroTrailer: Boolean = false
) {
    Canvas(modifier = modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val leftBlendSolidWidth = size.width * 0.018f
        val horizontalGradientStartX = leftBlendSolidWidth
        val horizontalFadeEndX = size.width * 0.65f
        val topContourGradient = Brush.linearGradient(
            colorStops = arrayOf(
                0.0f to bgColor.copy(alpha = 0.28f),
                0.38f to bgColor.copy(alpha = 0.14f),
                0.72f to bgColor.copy(alpha = 0.05f),
                1.0f to Color.Transparent
            ),
            start = Offset(0f, 0f),
            end = Offset(size.width * 0.24f, size.height * 0.40f)
        )
        val bottomContourGradient = Brush.linearGradient(
            colorStops = arrayOf(
                0.0f to bgColor.copy(alpha = 0.24f),
                0.42f to bgColor.copy(alpha = 0.12f),
                0.74f to bgColor.copy(alpha = 0.05f),
                1.0f to Color.Transparent
            ),
            start = Offset(0f, size.height),
            end = Offset(size.width * 0.24f, size.height * 0.61f)
        )

        val verticalGradient = if (cinematicMode) Brush.verticalGradient(
            0.38f to Color.Transparent,
            0.44f to bgColor.copy(alpha = 0.01f),
            0.50f to bgColor.copy(alpha = 0.02f),
            0.55f to bgColor.copy(alpha = 0.05f),
            0.61f to bgColor.copy(alpha = 0.10f),
            0.67f to bgColor.copy(alpha = 0.20f),
            0.73f to bgColor.copy(alpha = 0.36f),
            0.79f to bgColor.copy(alpha = 0.55f),
            0.85f to bgColor.copy(alpha = 0.74f),
            0.91f to bgColor.copy(alpha = 0.88f),
            0.96f to bgColor.copy(alpha = 0.96f),
            1.0f to bgColor
        ) else if (!cinematicMode) Brush.verticalGradient(
            0.89f to Color.Transparent,
            0.93f to bgColor.copy(alpha = 0.14f),
            0.965f to bgColor.copy(alpha = 0.52f),
            0.99f to bgColor.copy(alpha = 0.92f),
            1.0f to bgColor
        ) else null
        val defaultAlpha = if (allowLetterboxing) 1f - trailerTransitionProgress else 1f
        val lbAlpha = if (allowLetterboxing) trailerTransitionProgress else 0f

        // Default gradient — fades out as trailer fades in
        if (defaultAlpha > 0f) {
            val horizontalGradient = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0.0f  to bgColor,
                    0.10f to bgColor.copy(alpha = 0.97f * defaultAlpha),
                    0.22f to bgColor.copy(alpha = 0.88f * defaultAlpha),
                    0.36f to bgColor.copy(alpha = 0.76f * defaultAlpha),
                    0.52f to bgColor.copy(alpha = 0.58f * defaultAlpha),
                    0.66f to bgColor.copy(alpha = 0.38f * defaultAlpha),
                    0.78f to bgColor.copy(alpha = 0.20f * defaultAlpha),
                    0.90f to bgColor.copy(alpha = 0.08f * defaultAlpha),
                    1.0f  to Color.Transparent
                ),
                startX = horizontalGradientStartX,
                endX = horizontalFadeEndX
            )
            if (cinematicMode) drawRect(color = bgColor.copy(alpha = 0.18f), size = size)
            drawRect(color = bgColor.copy(alpha = defaultAlpha), size = Size(leftBlendSolidWidth, size.height))
            drawRect(brush = horizontalGradient, size = size)
            drawRect(brush = topContourGradient, size = size)
            drawRect(brush = bottomContourGradient, size = size)
        }

        // Letterboxing gradient — fades in as trailer fades in
        // 40% screen = 16.7% canvas, 45% screen = 23.6% canvas
        // Eased: slow fade at start (40-42%), accelerates toward end (43-45%)
        // Dithering extends to ~50% of screen (27.8% of canvas) for organic edge
        if (lbAlpha > 0f) {
            val lbSolidWidth = size.width * 0.097f
            val lbGradient = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0.0f   to bgColor,
                    0.097f to bgColor.copy(alpha = 1.00f * lbAlpha),
                    0.101f to bgColor.copy(alpha = 0.97f * lbAlpha),
                    0.105f to bgColor.copy(alpha = 0.93f * lbAlpha),
                    0.109f to bgColor.copy(alpha = 0.88f * lbAlpha),
                    0.113f to bgColor.copy(alpha = 0.83f * lbAlpha),
                    0.117f to bgColor.copy(alpha = 0.77f * lbAlpha),
                    0.121f to bgColor.copy(alpha = 0.71f * lbAlpha),
                    0.125f to bgColor.copy(alpha = 0.64f * lbAlpha),
                    0.129f to bgColor.copy(alpha = 0.57f * lbAlpha),
                    0.133f to bgColor.copy(alpha = 0.50f * lbAlpha),
                    0.137f to bgColor.copy(alpha = 0.43f * lbAlpha),
                    0.141f to bgColor.copy(alpha = 0.36f * lbAlpha),
                    0.145f to bgColor.copy(alpha = 0.29f * lbAlpha),
                    0.149f to bgColor.copy(alpha = 0.23f * lbAlpha),
                    0.153f to bgColor.copy(alpha = 0.17f * lbAlpha),
                    0.157f to bgColor.copy(alpha = 0.13f * lbAlpha),
                    0.161f to bgColor.copy(alpha = 0.10f * lbAlpha),
                    0.167f to bgColor.copy(alpha = 0.08f * lbAlpha),
                    0.178f to bgColor.copy(alpha = 0.06f * lbAlpha),
                    0.194f to bgColor.copy(alpha = 0.05f * lbAlpha),
                    0.222f to bgColor.copy(alpha = 0.04f * lbAlpha),
                    0.260f to bgColor.copy(alpha = 0.03f * lbAlpha),
                    0.306f to bgColor.copy(alpha = 0.02f * lbAlpha),
                    0.360f to bgColor.copy(alpha = 0.01f * lbAlpha),
                    0.420f to bgColor.copy(alpha = 0.005f * lbAlpha),
                    0.444f to Color.Transparent,
                    1.0f   to Color.Transparent
                ),
                startX = 0f,
                endX = size.width
            )
            drawRect(color = bgColor.copy(alpha = lbAlpha), size = Size(lbSolidWidth, size.height))
            drawRect(brush = lbGradient, size = size)

            // Dithering: light noise in fade zone to blur banding, plus feather at edge
            val fadeStart = size.width * 0.097f
            val edgeEnd = size.width * 0.500f
            val ditherRange = edgeEnd - fadeStart
            var seed = 1234567891L
            var x = fadeStart
            while (x < edgeEnd) {
                seed = seed * 1664525L + 1013904223L
                val randX = ((seed ushr 33) and 0xFFL).toFloat() / 255f
                seed = seed * 1664525L + 1013904223L
                val randY = ((seed ushr 33) and 0xFFL).toFloat() / 255f
                seed = seed * 1664525L + 1013904223L
                val randA = ((seed ushr 33) and 0xFFL).toFloat() / 255f
                val pos = (x - fadeStart) / ditherRange
                val baseAlpha = (1f - pos).coerceIn(0f, 1f)
                // In the main fade zone (0-55%): subtle anti-banding noise
                // In the edge zone (55-100%): feathering dots only
                val maxNoise = if (pos < 0.55f) baseAlpha * 0.12f else baseAlpha * 0.07f
                val noiseAlpha = (randA - 0.5f) * maxNoise * lbAlpha
                val finalAlpha = noiseAlpha.coerceIn(0f, 1f)
                if (finalAlpha > 0.003f) {
                    drawRect(
                        color = bgColor.copy(alpha = finalAlpha),
                        topLeft = Offset(x, randY * size.height),
                        size = Size(2f, 2f)
                    )
                }
                x += 3f + randX * 3f
            }
        }

        verticalGradient?.let { drawRect(brush = it, size = size) }
    }
}

@Composable
internal fun HeroTitleBlock(
    preview: HeroPreview?,
    enrichmentActive: Boolean = false,
    portraitMode: Boolean,
    selectedPlatformId: String = "home",
    platformNavDirection: Int = 0,
    modifier: Modifier = Modifier
) {
    if (preview == null) return

    val screenWidthPx = with(LocalDensity.current) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.roundToPx()
    }
    val slideDistancePx = (screenWidthPx * 0.25f).toInt()

    // Per-platform frozen snapshots — each AnimatedContent slot reads its own frozen copy.
    // No lock needed — the unified slide+fade transition fades everything out before
    // the platform flips, so any TMDB enrichment snap is invisible.
    val previewByPlatform = remember { mutableMapOf<String, HeroPreview>() }

    // Always update snapshot — enrichment data flows in naturally as it arrives.
    // No lock needed since the unified fade-out makes any snap invisible.
    if (preview != null) {
        previewByPlatform[selectedPlatformId] = preview
    }

    if (previewByPlatform.isEmpty()) return

    Box(modifier = modifier, contentAlignment = Alignment.BottomStart) {
        AnimatedContent(
            targetState = selectedPlatformId,
            transitionSpec = {
                if (platformNavDirection != 0) {
                    val dir = platformNavDirection
                    val inOffset: (Int) -> Int = if (dir > 0) ({ slideDistancePx }) else ({ -slideDistancePx })
                    val outOffset: (Int) -> Int = if (dir > 0) ({ -slideDistancePx }) else ({ slideDistancePx })
                    (slideInHorizontally(tween(600, easing = FastOutSlowInEasing), inOffset) + fadeIn(tween(600))) togetherWith
                    (slideOutHorizontally(tween(600, easing = FastOutSlowInEasing), outOffset) + fadeOut(tween(600))) using null
                } else {
                    fadeIn(tween(500)) togetherWith fadeOut(tween(300)) using null
                }
            },
            contentAlignment = Alignment.BottomStart,
            label = "heroTitleSlide"
        ) { pid ->
            val frozenPreview = previewByPlatform[pid] ?: return@AnimatedContent
            HeroTitleContent(preview = frozenPreview, portraitMode = portraitMode)
        }
    }
}


@Composable
private fun HeroTitleContent(
    preview: HeroPreview?,
    portraitMode: Boolean
) {
    if (preview == null) return
    val descriptionMaxLines = if (portraitMode) 4 else 5
    val descriptionScale = if (portraitMode) 0.90f else 1f
    val titleScale = if (portraitMode) 0.92f else 1f
    val metaScale = 1f
    val titleSpacing = 8.dp * titleScale
    val metaSpacing = 8.dp * metaScale
    val imdbMetaSpacing = 4.dp * metaScale
    val context = LocalContext.current
    val density = LocalDensity.current
    val headlineLarge = MaterialTheme.typography.headlineLarge
    val labelMedium = MaterialTheme.typography.labelMedium
    val bodyMedium = MaterialTheme.typography.bodyMedium
    val logoMaxWidthPx = remember(density) { with(density) { 220.dp.roundToPx() } }
    val logoHeightPx = remember(density) { with(density) { 100.dp.roundToPx() } }
    val imdbLogoModel = remember(context) {
        ImageRequest.Builder(context)
            .data(com.nuvio.tv.R.raw.imdb_logo_2016)
            .decoderFactory(SvgDecoder.Factory())
            .build()
    }
    val logoModel = remember(context, preview.logo, logoMaxWidthPx, logoHeightPx) {
        preview.logo?.let {
            // TMDB logo URLs sometimes arrive without a file extension,
            // ending with a bare period (e.g. ".../logo." instead of ".../logo.png").
            // Append .png in this case — TMDB logos are always PNG.
            val cleanedUrl = if (it.endsWith('.')) it + "png" else it
            ImageRequest.Builder(context)
                .data(cleanedUrl)
                .decoderFactory(SvgDecoder.Factory())
                .crossfade(false)
                .size(width = logoMaxWidthPx, height = logoHeightPx)
                .build()
        }
    }
    val scaledTitleStyle = remember(headlineLarge, titleScale) {
        headlineLarge.copy(
            fontSize = headlineLarge.fontSize * titleScale,
            lineHeight = headlineLarge.lineHeight * titleScale
        )
    }
    val scaledDescriptionStyle = remember(bodyMedium, descriptionScale) {
        bodyMedium.copy(
            fontSize = bodyMedium.fontSize * descriptionScale,
            lineHeight = bodyMedium.lineHeight * descriptionScale
        )
    }

    // Key on selectedPlatformId so the slide transition only fires when switching
    // platforms. Focusing different titles within a platform uses plain fade (key unchanged).

    Column(
        modifier = Modifier,
        verticalArrangement = Arrangement.spacedBy(titleSpacing)
    ) {
        var logoLoadFailed by remember(preview.logo) { mutableStateOf(false) }
        val showLogo = !preview.logo.isNullOrBlank() && !logoLoadFailed
        if (showLogo) {
            AsyncImage(
                model = logoModel,
                contentDescription = preview.title,
                onError = { logoLoadFailed = true },
                modifier = Modifier
                    .height(100.dp)
                    .widthIn(min = 100.dp, max = 220.dp)
                    .fillMaxWidth(),
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart
            )
        } else {
            Text(
                text = preview.title,
                style = scaledTitleStyle,
                color = NuvioColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        val strStatusEnded = stringResource(R.string.series_status_ended)
        val strStatusContinuing = stringResource(R.string.series_status_continuing)
        val strStatusCurrent = stringResource(R.string.series_status_current)
        val strStatusCancelled = stringResource(R.string.series_status_cancelled)
        val strStatusReleased = stringResource(R.string.series_status_released)
        val strStatusPlanned = stringResource(R.string.series_status_planned)
        val strStatusRumored = stringResource(R.string.series_status_rumored)
        val strStatusInProduction = stringResource(R.string.series_status_in_production)
        val strStatusPostProduction = stringResource(R.string.series_status_post_production)
        val secondaryMeta = remember(
            preview.secondaryHighlightText,
            preview.ageRatingText,
            preview.statusText,
            preview.languageText
        ) {
            ModernHeroSecondaryMeta(
                highlightText = preview.secondaryHighlightText?.trim()?.takeIf { it.isNotBlank() },
                ageRating = preview.ageRatingText?.trim()?.takeIf { it.isNotBlank() },
                status = when (preview.statusText?.trim()?.lowercase()) {
                    "ended" -> strStatusEnded.uppercase()
                    "continuing", "returning series" -> strStatusContinuing.uppercase()
                    "current" -> strStatusCurrent.uppercase()
                    "cancelled", "canceled" -> strStatusCancelled.uppercase()
                    "released" -> strStatusReleased.uppercase()
                    "planned" -> strStatusPlanned.uppercase()
                    "rumored" -> strStatusRumored.uppercase()
                    "in production" -> strStatusInProduction.uppercase()
                    "post production" -> strStatusPostProduction.uppercase()
                    else -> preview.statusText?.trim()?.takeIf { it.isNotBlank() }?.uppercase()
                },
                details = buildList {
                    preview.languageText?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
                }
            )
        }

        val secondaryHighlightText = secondaryMeta.highlightText
        val ageRatingBadge = secondaryMeta.ageRating
        // Status badge only shown for series — movies don't show status
        val statusBadge = secondaryMeta.status
        val secondaryDetails = secondaryMeta.details
        val hasSecondaryBadge = ageRatingBadge != null || statusBadge != null
        val showImdbInPrimary = !preview.isSeries && !hasSecondaryBadge && !preview.imdbText.isNullOrBlank()
        val showImdbInPrimaryWithHighlight = showImdbInPrimary && secondaryHighlightText == null
        val showImdbInSecondary = !preview.imdbText.isNullOrBlank() &&
            (preview.isSeries || hasSecondaryBadge || secondaryHighlightText != null)
            (preview.isSeries || hasSecondaryBadge || secondaryHighlightText != null)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metaSpacing)
        ) {
            val leadingMetaText = remember(preview.contentTypeText, preview.genres) {
                buildList {
                    preview.contentTypeText?.takeIf { it.isNotBlank() }?.let(::add)
                    preview.genres.firstOrNull()?.takeIf { it.isNotBlank() }?.let(::add)
                }.joinToString(separator = " • ")
            }
            val hasLeadingMeta = leadingMetaText.isNotBlank()

            val runtimeText = preview.runtimeText
            val yearText = preview.yearText
            val imdbText = preview.imdbText
            val hasTrailingMeta = !runtimeText.isNullOrBlank() ||
                !yearText.isNullOrBlank() ||
                showImdbInPrimaryWithHighlight

            if (hasLeadingMeta) {
                Text(
                    text = leadingMetaText,
                    style = labelMedium,
                    color = NuvioColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (hasTrailingMeta) {
                        Modifier.weight(1f, fill = false)
                    } else {
                        Modifier
                    }
                )
            }

            if (hasTrailingMeta) {
                if (hasLeadingMeta) {
                    HeroMetaDivider(metaScale)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(metaSpacing)
                ) {
                    if (!runtimeText.isNullOrBlank()) {
                        Text(
                            text = runtimeText,
                            style = labelMedium,
                            color = NuvioColors.TextSecondary,
                            maxLines = 1
                        )
                    }
                    if (!yearText.isNullOrBlank()) {
                        Text(
                            text = yearText,
                            style = labelMedium,
                            color = NuvioColors.TextSecondary,
                            maxLines = 1
                        )
                    }
                    if (showImdbInPrimaryWithHighlight && !imdbText.isNullOrBlank()) {
                        HeroImdbMeta(
                            imdbText = imdbText,
                            imdbLogoModel = imdbLogoModel,
                            textStyle = labelMedium,
                            textColor = NuvioColors.TextSecondary,
                            logoSize = 30.dp * metaScale,
                            spacing = imdbMetaSpacing
                        )
                    }
                }
            }
        }

        if (secondaryHighlightText != null || ageRatingBadge != null || showImdbInSecondary || statusBadge != null || secondaryDetails.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(metaSpacing)
            ) {
                val semiBoldLabelMedium = remember(labelMedium) { labelMedium.copy(fontWeight = FontWeight.SemiBold) }
        secondaryHighlightText?.let { text ->
                    Text(
                        text = text,
                        style = semiBoldLabelMedium,
                        color = NuvioColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (secondaryHighlightText != null && (hasSecondaryBadge || showImdbInSecondary || secondaryDetails.isNotEmpty())) {
                    HeroMetaDivider(metaScale)
                }
                if (ageRatingBadge != null && statusBadge != null) {
                    HeroCombinedMetaBadge(
                        leftText = ageRatingBadge,
                        rightText = statusBadge,
                        textStyle = labelMedium,
                        contentColor = NuvioColors.TextPrimary
                    )
                } else {
                    ageRatingBadge?.let { badge ->
                        HeroMetaBadge(
                            text = badge,
                            textStyle = labelMedium,
                            contentColor = NuvioColors.TextPrimary
                        )
                    }
                    statusBadge?.let { badge ->
                        HeroMetaBadge(
                            text = badge,
                            textStyle = labelMedium,
                            contentColor = NuvioColors.TextPrimary
                        )
                    }
                }
                if ((ageRatingBadge != null || statusBadge != null) && (showImdbInSecondary || secondaryDetails.isNotEmpty())) {
                    HeroMetaDivider(metaScale)
                }
                if (showImdbInSecondary) {
                    HeroImdbMeta(
                        imdbText = preview.imdbText.orEmpty(),
                        imdbLogoModel = imdbLogoModel,
                        textStyle = labelMedium,
                        textColor = NuvioColors.TextSecondary,
                        logoSize = 30.dp * metaScale,
                        spacing = imdbMetaSpacing
                    )
                }
                if (showImdbInSecondary && secondaryDetails.isNotEmpty()) {
                    HeroMetaDivider(metaScale)
                }
                secondaryDetails.forEachIndexed { index, value ->
                    Text(
                        text = value,
                        style = labelMedium,
                        color = NuvioColors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (index < secondaryDetails.lastIndex) {
                        HeroMetaDivider(metaScale)
                    }
                }
            }
        }

        preview.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = scaledDescriptionStyle,
                color = NuvioColors.TextPrimary,
                maxLines = descriptionMaxLines,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}


@Composable
private fun HeroImdbMeta(
    imdbText: String,
    imdbLogoModel: Any,
    textStyle: androidx.compose.ui.text.TextStyle,
    textColor: Color,
    logoSize: androidx.compose.ui.unit.Dp,
    spacing: androidx.compose.ui.unit.Dp
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing)
    ) {
        AsyncImage(
            model = imdbLogoModel,
            contentDescription = "IMDb",
            modifier = Modifier.size(logoSize),
            contentScale = ContentScale.Fit
        )
        Text(
            text = imdbText,
            style = textStyle,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HeroCombinedMetaBadge(
    leftText: String,
    rightText: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    contentColor: Color
) {
    val dividerColor = contentColor.copy(alpha = 0.55f)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(
                border = BorderStroke(1.dp, dividerColor),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val semiBoldStyle = remember(textStyle) { textStyle.copy(fontWeight = FontWeight.SemiBold) }
        Text(
            text = leftText,
            style = semiBoldStyle,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(12.dp)
                .background(dividerColor)
        )
        Text(
            text = rightText,
            style = semiBoldStyle,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HeroMetaBadge(
    text: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    contentColor: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(
                border = BorderStroke(1.dp, contentColor.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = remember(textStyle) { textStyle.copy(fontWeight = FontWeight.SemiBold) },
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HeroMetaDivider(scale: Float) {
    Box(
        modifier = Modifier
            .size((4.dp * scale).coerceAtLeast(2.dp))
            .clip(RoundedCornerShape(percent = 50))
            .background(NuvioColors.TextTertiary.copy(alpha = 0.78f))
    )
}
