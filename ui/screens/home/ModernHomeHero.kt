package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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

import com.nuvio.tv.domain.model.MDBListRatings

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
    requestHeightPx: Int
) {
    val localContext = LocalContext.current
    Box(modifier = modifier) {
        Crossfade(
            targetState = heroBackdrop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = heroBackdropAlpha },
            animationSpec = tween(durationMillis = 350),
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
    modifier: Modifier
) {
    Canvas(modifier = modifier) {
        val leftBlendSolidWidth = size.width * 0.018f
        val horizontalGradientStartX = leftBlendSolidWidth
        val horizontalFadeEndX = horizontalGradientStartX + (size.width * 0.42f)
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
        val verticalGradient = Brush.verticalGradient(
            0.89f to Color.Transparent,
            0.93f to bgColor.copy(alpha = 0.14f),
            0.965f to bgColor.copy(alpha = 0.52f),
            0.99f to bgColor.copy(alpha = 0.92f),
            1.0f to bgColor
        )

        val defaultAlpha = if (allowLetterboxing) 1f - trailerTransitionProgress else 1f
        val lbAlpha = if (allowLetterboxing) trailerTransitionProgress else 0f

        // Default gradient — fades out as trailer fades in
        if (defaultAlpha > 0f) {
            val horizontalGradient = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0.0f to bgColor,
                    0.22f to bgColor.copy(alpha = 0.86f * defaultAlpha),
                    0.46f to bgColor.copy(alpha = 0.56f * defaultAlpha),
                    0.76f to bgColor.copy(alpha = 0.16f * defaultAlpha),
                    1.0f to Color.Transparent
                ),
                startX = horizontalGradientStartX,
                endX = horizontalFadeEndX
            )
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

        drawRect(brush = verticalGradient, size = size)
    }
}

@Composable
internal fun HeroTitleBlock(
    preview: HeroPreview?,
    enrichmentActive: Boolean = false,
    portraitMode: Boolean,
    modifier: Modifier = Modifier
) {
    val fadeDuration = 220
    AnimatedContent(
        targetState = preview,
        transitionSpec = { fadeIn(tween(fadeDuration)) togetherWith fadeOut(tween(fadeDuration)) using null },
        contentAlignment = Alignment.BottomStart,
        label = "heroTitleCrossfade",
        modifier = modifier
    ) { animatedPreview ->
        HeroTitleContent(preview = animatedPreview, portraitMode = portraitMode)
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
    val context = LocalContext.current
    val density = LocalDensity.current
    val headlineLarge = MaterialTheme.typography.headlineLarge
    val labelMedium = MaterialTheme.typography.labelMedium
    val bodyMedium = MaterialTheme.typography.bodyMedium
    val logoMaxWidthPx = remember(density) { with(density) { 220.dp.roundToPx() } }
    val logoHeightPx = remember(density) { with(density) { 100.dp.roundToPx() } }
    val logoModel = remember(context, preview.logo, logoMaxWidthPx, logoHeightPx) {
        preview.logo?.let {
            ImageRequest.Builder(context)
                .data(it)
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

    // Resolve status strings for badge
    val strStatusEnded = stringResource(R.string.series_status_ended)
    val strStatusContinuing = stringResource(R.string.series_status_continuing)
    val strStatusCurrent = stringResource(R.string.series_status_current)
    val strStatusCancelled = stringResource(R.string.series_status_cancelled)
    val strStatusReleased = stringResource(R.string.series_status_released)
    val strStatusPlanned = stringResource(R.string.series_status_planned)
    val strStatusRumored = stringResource(R.string.series_status_rumored)
    val strStatusInProduction = stringResource(R.string.series_status_in_production)
    val strStatusPostProduction = stringResource(R.string.series_status_post_production)

    // Age rating badge — always shown when present
    val ageRatingBadge = remember(preview.ageRatingText) {
        preview.ageRatingText?.trim()?.takeIf { it.isNotBlank() }
    }
    // Status badge — series only
    val statusBadge = remember(preview.isSeries, preview.statusText) {
        if (!preview.isSeries) null
        else when (preview.statusText?.trim()?.lowercase()) {
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
        }
    }
    val hasRatingBadge = ageRatingBadge != null || statusBadge != null

    // MDB ratings row — only when populated
    val mdbRatings = preview.mdbRatings
    val showMdbRatings = mdbRatings != null && !mdbRatings.isEmpty()

    Column(
        modifier = Modifier,
        verticalArrangement = Arrangement.spacedBy(titleSpacing)
    ) {
        // ── Logo / Title ──────────────────────────────────────────────────────
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

        // ── Secondary highlight (Continue Watching progress label etc.) ───────
        val secondaryHighlightText = remember(preview.secondaryHighlightText) {
            preview.secondaryHighlightText?.trim()?.takeIf { it.isNotBlank() }
        }
        if (secondaryHighlightText != null) {
            val semiBoldLabelMedium = remember(labelMedium) {
                labelMedium.copy(fontWeight = FontWeight.SemiBold)
            }
            Text(
                text = secondaryHighlightText,
                style = semiBoldLabelMedium,
                color = NuvioColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // ── Line 1: Genre • Type  ·  Runtime • Year • [AGE RATING / AGE | STATUS] ──
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
            val hasTrailingMeta = !runtimeText.isNullOrBlank() || !yearText.isNullOrBlank() || hasRatingBadge

            if (hasLeadingMeta) {
                Text(
                    text = leadingMetaText,
                    style = labelMedium,
                    color = NuvioColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (hasTrailingMeta) Modifier.weight(1f, fill = false) else Modifier
                )
            }

            if (hasTrailingMeta) {
                if (hasLeadingMeta) HeroMetaDivider(metaScale)
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
                        if (!runtimeText.isNullOrBlank()) HeroMetaDivider(metaScale)
                        Text(
                            text = yearText,
                            style = labelMedium,
                            color = NuvioColors.TextSecondary,
                            maxLines = 1
                        )
                    }
                    // Age rating badge (and series status) inline after year
                    if (hasRatingBadge) {
                        if (!runtimeText.isNullOrBlank() || !yearText.isNullOrBlank()) {
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
                    }
                }
            }
        }

        // ── Line 2: MDBList ratings row (only when configured and fetched) ────
        if (showMdbRatings && mdbRatings != null) {
            HeroMdbRatingsRow(
                ratings = mdbRatings,
                context = context,
                textStyle = labelMedium
            )
        }

        // ── Line 3: Description ───────────────────────────────────────────────
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
private fun HeroMdbRatingsRow(
    ratings: MDBListRatings,
    context: android.content.Context,
    textStyle: androidx.compose.ui.text.TextStyle
) {
    val items = remember(ratings) {
        listOf(
            Triple("trakt", com.nuvio.tv.R.raw.mdblist_trakt, ratings.trakt),
            Triple("imdb", com.nuvio.tv.R.raw.imdb_logo_2016, ratings.imdb),
            Triple("tmdb", com.nuvio.tv.R.raw.mdblist_tmdb, ratings.tmdb),
            Triple("letterboxd", com.nuvio.tv.R.raw.mdblist_letterboxd, ratings.letterboxd),
            Triple("tomatoes", com.nuvio.tv.R.raw.mdblist_tomatoes, ratings.tomatoes)
        ).filter { it.third != null }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { (provider, logoRes, rating) ->
            val resolvedRating = rating ?: return@forEach
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val model = remember(context, logoRes) {
                    coil.request.ImageRequest.Builder(context)
                        .data(logoRes)
                        .decoderFactory(coil.decode.SvgDecoder.Factory())
                        .build()
                }
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    contentScale = ContentScale.Fit
                )
                Text(
                    text = formatMdbRating(provider, resolvedRating),
                    style = textStyle,
                    color = NuvioColors.TextSecondary,
                    maxLines = 1
                )
            }
        }

        ratings.audience?.let { rating ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(
                        id = com.nuvio.tv.R.drawable.mdblist_audience
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = formatMdbRating("audience", rating),
                    style = textStyle,
                    color = NuvioColors.TextSecondary,
                    maxLines = 1
                )
            }
        }

        ratings.metacritic?.let { rating ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(
                        id = com.nuvio.tv.R.drawable.mdblist_metacritic
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = formatMdbRating("metacritic", rating),
                    style = textStyle,
                    color = NuvioColors.TextSecondary,
                    maxLines = 1
                )
            }
        }
    }
}

private fun formatMdbRating(provider: String, rating: Double): String {
    return when (provider) {
        "imdb", "tmdb", "letterboxd" -> String.format("%.1f", rating)
        else -> if (rating % 1.0 == 0.0) rating.toInt().toString()
                else String.format("%.1f", rating)
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
