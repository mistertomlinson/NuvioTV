@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.material.icons.filled.Check
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.annotation.RawRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MDBListRatings
import com.nuvio.tv.ui.components.SynopsisDescription
import com.nuvio.tv.ui.components.SynopsisOverlay
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
internal fun PostPlayRecommendationOverlay(
    recommendation: PostPlayRecommendation,
    currentTitle: String,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    playFocusRequester: FocusRequester,
    playerWindowFocusRequester: FocusRequester,
    isTrailerPlaying: Boolean,
    hasPlayedTrailer: Boolean,
    trailerCountdownSec: Int?,
    isInLibrary: Boolean,
    libraryMessage: String?,
    onToggleLibrary: (Boolean) -> Unit,
    onLibraryMessageShown: () -> Unit,
    onPlay: () -> Unit,
    onPlayTrailer: () -> Unit,
    onTrailerEnded: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val previousFocusRequester = remember { FocusRequester() }
    val nextFocusRequester = remember { FocusRequester() }
    val readMoreFocusRequester = remember(recommendation.id) { FocusRequester() }
    val trailerFocusRequester = remember(recommendation.id) { FocusRequester() }
    val libraryFocusRequester = remember(recommendation.id) { FocusRequester() }
    var pendingNavigationDirection by remember { mutableIntStateOf(0) }
    var synopsisExpanded by remember(recommendation.id) { mutableStateOf(false) }
    var descriptionTruncated by remember(recommendation.id) { mutableStateOf(false) }
    val playPainter = rememberPostPlayIcon(R.raw.ic_player_play)
    val trailerPainter = rememberPostPlayIcon(R.raw.trailer_play_button)
    val libraryAddPainter = rememberPostPlayIcon(R.raw.library_add_plus)
    val logoHeight by animateDpAsState(
        targetValue = if (isTrailerPlaying) 60.dp else 92.dp,
        animationSpec = tween(600),
        label = "postPlayRecommendationLogoHeight"
    )
    val logoMaxWidth by animateFloatAsState(
        targetValue = if (isTrailerPlaying) 0.48f else 0.76f,
        animationSpec = tween(600),
        label = "postPlayRecommendationLogoWidth"
    )
    val actionTopSpacing by animateDpAsState(
        targetValue = if (isTrailerPlaying) 16.dp else 0.dp,
        animationSpec = tween(600),
        label = "postPlayRecommendationActionSpacing"
    )

    LaunchedEffect(libraryMessage) {
        if (!libraryMessage.isNullOrBlank()) {
            delay(2500L)
            onLibraryMessageShown()
        }
    }

    LaunchedEffect(isTrailerPlaying) {
        delay(420L)
        runCatching { playFocusRequester.requestFocus() }
    }

    // Keep focus on the arrow that initiated paging after the new item composes.
    LaunchedEffect(recommendation.id) {
        if (pendingNavigationDirection == 0) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        val requester = when {
            pendingNavigationDirection < 0 && canGoPrevious -> previousFocusRequester
            pendingNavigationDirection > 0 && canGoNext -> nextFocusRequester
            canGoPrevious -> previousFocusRequester
            canGoNext -> nextFocusRequester
            else -> playFocusRequester
        }
        runCatching { requester.requestFocus() }
        pendingNavigationDirection = 0
    }

    Box(modifier = modifier.background(Color.Black)) {
        AnimatedContent(
            targetState = recommendation,
            transitionSpec = {
                fadeIn(tween(180)) togetherWith fadeOut(tween(180))
            },
            contentKey = { it.id },
            label = "postPlayRecommendationBackdrop",
            modifier = Modifier.fillMaxSize()
        ) { displayedRecommendation ->
            AsyncImage(
                model = displayedRecommendation.backdrop ?: displayedRecommendation.poster,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        androidx.compose.runtime.key(recommendation.trailerVideoUrl ?: recommendation.id) {
            TrailerPlayer(
                trailerUrl = recommendation.trailerVideoUrl,
                trailerAudioUrl = recommendation.trailerAudioUrl,
                isPlaying = isTrailerPlaying,
                onEnded = onTrailerEnded,
                cropToFill = true,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.88f),
                        0.54f to Color.Black.copy(alpha = 0.16f),
                        1f to Color.Black.copy(alpha = 0.22f)
                    )
                )
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.08f),
                        0.58f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.82f)
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.52f)
                .padding(start = 52.dp, bottom = 42.dp),
            horizontalAlignment = Alignment.Start
        ) {
            AnimatedVisibility(visible = !isTrailerPlaying) {
                Column {
                    Text(
                        text = stringResource(R.string.player_post_play_because, currentTitle),
                        color = Color.White.copy(alpha = 0.62f),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }

            AnimatedContent(
                targetState = recommendation,
                transitionSpec = {
                    fadeIn(tween(180)) togetherWith fadeOut(tween(180))
                },
                contentKey = { it.id },
                label = "postPlayRecommendationLogo",
                modifier = Modifier
                    .fillMaxWidth(logoMaxWidth)
                    .height(logoHeight)
            ) { displayedRecommendation ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    var logoFailed by remember(displayedRecommendation.logo) {
                        mutableStateOf(false)
                    }
                    if (!displayedRecommendation.logo.isNullOrBlank() && !logoFailed) {
                        AsyncImage(
                            model = displayedRecommendation.logo,
                            contentDescription = displayedRecommendation.title,
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterStart,
                            modifier = Modifier.fillMaxSize(),
                            onError = { logoFailed = true }
                        )
                    } else {
                        Text(
                            text = displayedRecommendation.title,
                            color = Color.White,
                            fontSize = 36.sp,
                            lineHeight = 40.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            AnimatedVisibility(visible = !isTrailerPlaying) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.TopStart
                ) {
                    AnimatedContent(
                        targetState = recommendation,
                        transitionSpec = {
                            fadeIn(tween(180)) togetherWith fadeOut(tween(180))
                        },
                        contentKey = { it.id },
                        label = "postPlayRecommendationDetails",
                        modifier = Modifier.fillMaxSize()
                    ) { displayedRecommendation ->
                        PostPlayRecommendationDetails(
                            recommendation = displayedRecommendation,
                            readMoreFocusRequester = readMoreFocusRequester,
                            playerWindowFocusRequester = playerWindowFocusRequester,
                            playFocusRequester = playFocusRequester,
                            onShowSynopsis = { synopsisExpanded = true },
                            onDescriptionTruncationChanged = { truncated ->
                                if (displayedRecommendation.id == recommendation.id) {
                                    descriptionTruncated = truncated
                                }
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(actionTopSpacing))

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val showTrailerButton = shouldShowPostPlayTrailerAction(
                    recommendation = recommendation,
                    isTrailerPlaying = isTrailerPlaying
                )
                val showNavigationButtons = canGoPrevious || canGoNext
                val actionCount = if (showTrailerButton) 2 else 1
                val navigationCount = if (showNavigationButtons) 2 else 0
                val fixedIconCount = navigationCount + 1
                val itemCount = actionCount + fixedIconCount
                val spacing = 12.dp
                val availableActionWidth =
                    maxWidth - 48.dp * fixedIconCount - spacing * (itemCount - 1)
                val buttonWidth =
                    minOf((maxWidth - spacing) / 2, availableActionWidth / actionCount)
                Row(horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.CenterVertically) {
                    PostPlayActionButton(
                        label = stringResource(R.string.player_post_play_play),
                        painter = playPainter,
                        primary = true,
                        focusRequester = playFocusRequester,
                        onClick = onPlay,
                        modifier = Modifier
                            .width(buttonWidth)
                            .focusProperties {
                                when {
                                    descriptionTruncated && !isTrailerPlaying -> up = readMoreFocusRequester
                                    !hasPlayedTrailer -> up = playerWindowFocusRequester
                                }
                            }
                    )
                    if (showTrailerButton) {
                        PostPlayActionButton(
                            label = if (trailerCountdownSec != null) {
                                stringResource(
                                    R.string.player_post_play_trailer_countdown,
                                    trailerCountdownSec
                                )
                            } else {
                                stringResource(R.string.player_post_play_trailer)
                            },
                            painter = trailerPainter,
                            primary = false,
                            focusRequester = trailerFocusRequester,
                            onClick = onPlayTrailer,
                            modifier = Modifier
                                .width(buttonWidth)
                                .focusProperties {
                                    when {
                                        descriptionTruncated -> up = readMoreFocusRequester
                                        !hasPlayedTrailer -> up = playerWindowFocusRequester
                                    }
                                }
                        )
                    }
                    PostPlayLibraryButton(
                        isInLibrary = isInLibrary,
                        addPainter = libraryAddPainter,
                        contentDescription = if (isInLibrary) {
                            stringResource(R.string.hero_remove_from_library)
                        } else {
                            stringResource(R.string.hero_add_to_library)
                        },
                        focusRequester = libraryFocusRequester,
                        onClick = { onToggleLibrary(isInLibrary) },
                        modifier = Modifier.focusProperties {
                            when {
                                descriptionTruncated -> up = readMoreFocusRequester
                                !hasPlayedTrailer -> up = playerWindowFocusRequester
                            }
                        }
                    )

                    if (showNavigationButtons) {
                    PostPlayNavigationButton(
                        focusRequester = previousFocusRequester,
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.player_post_play_previous_recommendation),
                        playerWindowFocusRequester = playerWindowFocusRequester,
                        canFocusPlayerWindow = !hasPlayedTrailer,
                        enabled = canGoPrevious,
                        onClick = {
                            pendingNavigationDirection = -1
                            onPrevious()
                        }
                    )
                    PostPlayNavigationButton(
                        focusRequester = nextFocusRequester,
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.player_post_play_next_recommendation),
                        playerWindowFocusRequester = playerWindowFocusRequester,
                        canFocusPlayerWindow = !hasPlayedTrailer,
                        enabled = canGoNext,
                        onClick = {
                            pendingNavigationDirection = 1
                            onNext()
                        }
                    )
                    }
                }
            }
        }
    }

    val message = libraryMessage
    if (!message.isNullOrBlank()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f),
            contentAlignment = androidx.compose.ui.Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 24.dp)
                    .background(
                        color = com.nuvio.tv.ui.theme.NuvioColors.BackgroundElevated,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = com.nuvio.tv.ui.theme.NuvioColors.TextPrimary
                )
            }
        }
    }

    if (synopsisExpanded) {
        SynopsisOverlay(
            title = recommendation.title,
            description = recommendation.description.orEmpty(),
            onDismiss = { synopsisExpanded = false }
        )
    }
}

@Composable
private fun PostPlayActionButton(
    label: String,
    painter: Painter,
    primary: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(24.dp)
    Button(
        onClick = onClick,
        modifier = modifier.focusRequester(focusRequester),
        colors = ButtonDefaults.colors(
            containerColor = if (primary) Color.White else NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = if (primary) Color.White else NuvioTheme.colors.Secondary,
            contentColor = if (primary) Color.Black else NuvioTheme.colors.TextPrimary,
            focusedContentColor = if (primary) Color.Black else NuvioTheme.colors.OnSecondary
        ),
        shape = ButtonDefaults.shape(shape = shape),
        scale = ButtonDefaults.scale(focusedScale = 1.1f),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioTheme.colors.FocusRing),
                shape = shape
            )
        ),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(painter, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
    }
}

@Composable
private fun PostPlayRecommendationDetails(
    recommendation: PostPlayRecommendation,
    readMoreFocusRequester: FocusRequester,
    playerWindowFocusRequester: FocusRequester,
    playFocusRequester: FocusRequester,
    onShowSynopsis: () -> Unit,
    onDescriptionTruncationChanged: (Boolean) -> Unit
) {
    Column {
        val metadata = recommendation.metadataLine()
        if (metadata.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = metadata,
                color = Color.White.copy(alpha = 0.76f),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        recommendation.mdbListRatings?.takeUnless { it.isEmpty() }?.let { ratings ->
            Spacer(Modifier.height(10.dp))
            PostPlayRatingsRow(ratings)
        }

        recommendation.description?.takeIf { it.isNotBlank() }?.let { description ->
            Spacer(Modifier.height(12.dp))
            SynopsisDescription(
                description = description,
                onShowFullDescription = onShowSynopsis,
                maxLines = 3,
                focusRequester = readMoreFocusRequester,
                upFocusRequester = playerWindowFocusRequester,
                downFocusRequester = playFocusRequester,
                onTruncationChanged = onDescriptionTruncationChanged,
                modifier = Modifier.fillMaxWidth(0.92f)
            )
        }
    }
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun PostPlayLibraryButton(
    isInLibrary: Boolean,
    addPainter: androidx.compose.ui.graphics.painter.Painter,
    contentDescription: String,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.tv.material3.IconButton(
        onClick = onClick,
        modifier = modifier
            .size(48.dp)
            .focusRequester(focusRequester),
        colors = androidx.tv.material3.IconButtonDefaults.colors(
            containerColor = com.nuvio.tv.ui.theme.NuvioColors.BackgroundCard,
            focusedContainerColor = com.nuvio.tv.ui.theme.NuvioColors.Secondary,
            contentColor = com.nuvio.tv.ui.theme.NuvioColors.TextPrimary,
            focusedContentColor = com.nuvio.tv.ui.theme.NuvioColors.OnSecondary
        ),
        border = androidx.tv.material3.IconButtonDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(
                    2.dp,
                    com.nuvio.tv.ui.theme.NuvioColors.FocusRing
                ),
                shape = androidx.compose.foundation.shape.CircleShape
            )
        ),
        shape = androidx.tv.material3.IconButtonDefaults.shape(
            shape = androidx.compose.foundation.shape.CircleShape
        )
    ) {
        if (isInLibrary) {
            androidx.tv.material3.Icon(
                imageVector = Icons.Default.Check,
                contentDescription = contentDescription,
                modifier = Modifier.size(22.dp)
            )
        } else {
            androidx.tv.material3.Icon(
                painter = addPainter,
                contentDescription = contentDescription,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun PostPlayNavigationButton(
    focusRequester: FocusRequester,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    playerWindowFocusRequester: FocusRequester,
    canFocusPlayerWindow: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .focusRequester(focusRequester)
            .focusProperties {
                if (canFocusPlayerWindow) up = playerWindowFocusRequester
            },
        colors = IconButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.Secondary,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContentColor = NuvioTheme.colors.OnSecondary
        ),
        scale = IconButtonDefaults.scale(focusedScale = 1.1f),
        border = IconButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioTheme.colors.FocusRing),
                shape = CircleShape
            )
        ),
        shape = IconButtonDefaults.shape(shape = CircleShape)
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun PostPlayRatingsRow(ratings: MDBListRatings) {
    val logoRatings = listOfNotNull(
        ratings.trakt?.let { Triple("trakt", R.raw.mdblist_trakt, it) },
        ratings.imdb?.let { Triple("imdb", R.raw.imdb_logo_2016, it) },
        ratings.tmdb?.let { Triple("tmdb", R.raw.mdblist_tmdb, it) },
        ratings.letterboxd?.let { Triple("letterboxd", R.raw.mdblist_letterboxd, it) },
        ratings.tomatoes?.let { Triple("tomatoes", R.raw.mdblist_tomatoes, it) }
    )
    Row(horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.CenterVertically) {
        logoRatings.forEach { (provider, icon, rating) ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(model = icon, contentDescription = null, modifier = Modifier.size(22.dp))
                Text(
                    text = formatPostPlayRating(provider, rating),
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 13.sp
                )
            }
        }
        ratings.audience?.let { rating ->
            PostPlayDrawableRating(R.drawable.mdblist_audience, formatPostPlayRating("audience", rating))
        }
        ratings.metacritic?.let { rating ->
            PostPlayDrawableRating(R.drawable.mdblist_metacritic, formatPostPlayRating("metacritic", rating))
        }
    }
}

@Composable
private fun PostPlayDrawableRating(drawable: Int, rating: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(drawable), contentDescription = null, modifier = Modifier.size(22.dp))
        Text(rating, color = Color.White.copy(alpha = 0.78f), fontSize = 13.sp)
    }
}

private fun PostPlayRecommendation.metadataLine(): String = buildList {
    genres.take(2).takeIf { it.isNotEmpty() }?.joinToString(" • ")?.let(::add)
    formatPostPlayReleaseInfo(releaseInfo)?.takeIf { it.isNotBlank() }?.let(::add)
    formatPostPlayRuntime(runtime)?.takeIf { it.isNotBlank() }?.let(::add)
}.joinToString("  •  ")

private fun formatPostPlayReleaseInfo(releaseInfo: String?): String? {
    val value = releaseInfo?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val years = Regex("\\b(?:19|20)\\d{2}\\b")
        .findAll(value)
        .map { it.value }
        .distinct()
        .toList()
    return when {
        years.size > 1 -> years.joinToString("–")
        years.size == 1 -> years.single()
        else -> value
    }
}

private fun formatPostPlayRuntime(runtime: String?): String? {
    val value = runtime?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val minutes = value.filter(Char::isDigit).toIntOrNull() ?: return value
    if (minutes < 60) return "${minutes}m"
    val hours = minutes / 60
    val remainder = minutes % 60
    return if (remainder == 0) "${hours}h" else "${hours}h ${remainder}m"
}

private fun formatPostPlayRating(provider: String, rating: Double): String = when (provider) {
    "imdb", "tmdb", "letterboxd" -> formatOneDecimal(rating)
    else -> if (rating % 1.0 == 0.0) rating.toInt().toString() else formatOneDecimal(rating)
}

private fun formatOneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)

@Composable
private fun rememberPostPlayIcon(@RawRes iconRes: Int): Painter {
    val context = LocalContext.current
    val model = remember(iconRes, context) {
        ImageRequest.Builder(context)
            .data(iconRes)
            .decoderFactory(SvgDecoder.Factory())
            .build()
    }
    return rememberAsyncImagePainter(model = model)
}

@Composable
internal fun PostPlayPlayerWindow(
    focusRequester: FocusRequester,
    downFocusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .focusRequester(focusRequester)
            .focusProperties { down = downFocusRequester },
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp)),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(3.dp, MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Box(modifier = Modifier.fillMaxSize())
    }
}
