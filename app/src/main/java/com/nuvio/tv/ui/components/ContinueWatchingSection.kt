package com.nuvio.tv.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.nuvio.tv.R
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import android.view.WindowManager
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.math.roundToInt
import java.util.concurrent.TimeUnit
import com.nuvio.tv.ui.util.localizeEpisodeTitle
import com.nuvio.tv.ui.util.computeAirDateBadgeText
import com.nuvio.tv.domain.model.CardDepthSurface
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild

internal val brokenImageUrls = java.util.Collections.synchronizedSet(mutableSetOf<String>())


private val CwCardShape = RoundedCornerShape(12.dp)
private val CwClipShape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
private val BadgeShape = RoundedCornerShape(4.dp)
private val CwNewEpisodeBadgeColor = Color(0xFF1D4ED8)
internal val CwNewSeasonBadgeColor = Color(0xFFB45309)

private val CwDialogGlassRowColor = Color.White.copy(alpha = 0.065f)
private val CwDialogGlassRowFocusedColor = Color.White.copy(alpha = 0.16f)
private val CwDialogGlassBrush = Brush.verticalGradient(
    colors = listOf(
        Color(0xAD2A3038),
        Color(0x9E20252C),
        Color(0xA824292F)
    )
)
private val CwDialogGlassBorderColor = Color.White.copy(alpha = 0.09f)

internal data class HomePopupGlassEnvironment(
    val hazeState: HazeState? = null,
    val blurEnabled: Boolean = false,
    val backdropAlreadyBlurred: Boolean = false,
    val onPopupVisibilityChanged: (Boolean) -> Unit = {},
    val catalogOptionsVisible: Boolean = false,
    val preserveCatalogTrailerPlayback: Boolean = false,

    /*
     * True only while a just-dismissed Home catalog popup is returning
     * focus to the poster that opened it.
     *
     * Expanded-card trailers use this tiny handoff window to avoid being
     * torn down by the row's temporary isScrollInProgress state near the
     * right edge of a LazyRow.
     */
    val catalogOptionsFocusRestoreActive: Boolean = false,

    val onCatalogOptionsOpening: (preserveTrailerPlayback: Boolean) -> Unit = {}
)

internal val LocalHomePopupGlassEnvironment =
    staticCompositionLocalOf { HomePopupGlassEnvironment() }

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ContinueWatchingSection(
    items: List<ContinueWatchingItem>,
    onItemClick: (ContinueWatchingItem) -> Unit,
    onDetailsClick: (ContinueWatchingItem) -> Unit = onItemClick,
    onRemoveItem: (ContinueWatchingItem) -> Unit,
    onStartFromBeginning: (ContinueWatchingItem) -> Unit = {},
    showManualPlayOption: Boolean = false,
    onPlayManually: (ContinueWatchingItem) -> Unit = {},
    modifier: Modifier = Modifier,
    focusedItemIndex: Int = -1,
    onItemFocused: (itemIndex: Int) -> Unit = {},
    cardWidth: Dp = 288.dp,
    imageHeight: Dp = 162.dp,
    cardStyle: ContinueWatchingCardStyle = ContinueWatchingCardStyle.CARD,
    cornerRadius: Dp = 12.dp
) {
    if (items.isEmpty()) return

    val focusRequesters = remember(items.size) { List(items.size) { FocusRequester() } }
    var lastFocusedIndex by remember { mutableIntStateOf(-1) }
    var lastRequestedFocusIndex by remember { mutableIntStateOf(-1) }
    var pendingFocusIndex by remember { mutableStateOf<Int?>(null) }
    var optionsItem by remember { mutableStateOf<ContinueWatchingItem?>(null) }
    
    val listState = rememberLazyListState()

    // Restore focus to specific item if requested
    LaunchedEffect(focusedItemIndex) {
        if (focusedItemIndex >= 0 && focusedItemIndex < items.size) {
            if (lastRequestedFocusIndex == focusedItemIndex) return@LaunchedEffect
            var focused = false
            for (attempt in 0 until 3) {
                withFrameNanos { }
                focused = runCatching { focusRequesters[focusedItemIndex].requestFocus() }.isSuccess
                if (focused) break
            }
            if (focused) {
                lastRequestedFocusIndex = focusedItemIndex
            }
        } else {
            lastRequestedFocusIndex = -1
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp, end = 48.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.continue_watching),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioColors.TextPrimary
            )
        }

        val restoreFocusRequester = remember(lastFocusedIndex, focusRequesters.size) {
            val idx = if (lastFocusedIndex >= 0 && lastFocusedIndex < focusRequesters.size)
                lastFocusedIndex else 0
            focusRequesters.getOrNull(idx) ?: FocusRequester.Default
        }

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusRestorer(restoreFocusRequester),
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            state = listState
        ) {
            itemsIndexed(
                items = items,
                key = { _, progress ->
                    when (progress) {
                        is ContinueWatchingItem.InProgress ->
                            "cw_${progress.progress.contentId}_${progress.progress.videoId}_${progress.progress.season ?: -1}_${progress.progress.episode ?: -1}"
                        is ContinueWatchingItem.NextUp ->
                            "nextup_${progress.info.contentId}_${progress.info.videoId}_${progress.info.season}_${progress.info.episode}"
                    }
                }
            ) { index, progress ->
                val focusModifier = when {
                    index < focusRequesters.size -> Modifier.focusRequester(focusRequesters[index])
                    else -> Modifier
                }

                ContinueWatchingCard(
                    item = progress,
                    onClick = { onItemClick(progress) },
                    onLongPress = { optionsItem = progress },
                    cardWidth = cardWidth,
                    imageHeight = imageHeight,
                    cardStyle = cardStyle,
                    cornerRadius = cornerRadius,
                    modifier = Modifier
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused && lastFocusedIndex != index) {
                                lastFocusedIndex = index
                                onItemFocused(index)
                            }
                        }
                        .then(focusModifier)
                )
            }
        }
    }

    val menuItem = optionsItem
    if (menuItem != null) {
        ContinueWatchingOptionsDialog(
            item = menuItem,
            onDismiss = { optionsItem = null },
            onRemove = {
                val targetIndex = if (items.size <= 1) null else minOf(lastFocusedIndex, items.size - 2)
                pendingFocusIndex = targetIndex
                onRemoveItem(menuItem)
                optionsItem = null
            },
            onDetails = {
                onDetailsClick(menuItem)
                optionsItem = null
            },
            onStartFromBeginning = {
                onStartFromBeginning(menuItem)
                optionsItem = null
            },
            showPlayManually = showManualPlayOption,
            onPlayManually = {
                onPlayManually(menuItem)
                optionsItem = null
            }
        )
    }

    LaunchedEffect(items.size, pendingFocusIndex) {
        val target = pendingFocusIndex
        if (target != null && target >= 0 && target < focusRequesters.size) {
            var focused = false
            for (attempt in 0 until 3) {
                withFrameNanos { }
                focused = runCatching { focusRequesters[target].requestFocus() }.isSuccess
                if (focused) break
            }
            if (focused) {
                lastRequestedFocusIndex = target
            }
            pendingFocusIndex = null
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ContinueWatchingCard(
    item: ContinueWatchingItem,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 288.dp,
    imageHeight: Dp = 162.dp,
    retainFocusOutline: Boolean = false,
    cardStyle: ContinueWatchingCardStyle = ContinueWatchingCardStyle.CARD,
    cornerRadius: Dp = 12.dp
) {
    val isPosterStyle = cardStyle == ContinueWatchingCardStyle.POSTER
    val isWideStyle = cardStyle == ContinueWatchingCardStyle.WIDE
    val usePosterArtwork = isPosterStyle || isWideStyle

    val cwCardShape = remember(cornerRadius) {
        RoundedCornerShape(cornerRadius)
    }
    val cwClipShape = remember(cornerRadius, isPosterStyle) {
        if (isPosterStyle) {
            RoundedCornerShape(cornerRadius)
        } else {
            RoundedCornerShape(
                topStart = cornerRadius,
                topEnd = cornerRadius
            )
        }
    }

    var longPressTriggered by remember { mutableStateOf(false) }
    var isCardFocused by remember { mutableStateOf(false) }

    val cardDepthStyle = LocalCardDepthStyle.current
    val continueWatchingDepthModifier = remember(
        cardDepthStyle,
        cwCardShape
    ) {
        Modifier.nuvioCardDepth(
            shape = cwCardShape,
            surface = CardDepthSurface.CONTINUE_WATCHING,
            style = cardDepthStyle
        )
    }

    val progress =
        remember(item) {
            (item as? ContinueWatchingItem.InProgress)?.progress
        }
    val episodeThumbnail =
        remember(item) {
            (item as? ContinueWatchingItem.InProgress)?.episodeThumbnail
        }
    val nextUp =
        remember(item) {
            (item as? ContinueWatchingItem.NextUp)?.info
        }

    val episodeStr = remember(progress, nextUp) {
        progress?.episodeDisplayString
            ?: nextUp?.let { "S${it.season}E${it.episode}" }
    }

    val strUpcoming = stringResource(R.string.cw_upcoming)
    val strNextUp = stringResource(R.string.cw_next_up)
    val strNewEpisode = stringResource(R.string.cw_new_episode)
    val strNewSeason = stringResource(R.string.cw_new_season)
    val strResume = stringResource(R.string.cw_resume)
    val strPercentWatched = stringResource(R.string.cw_percent_watched)
    val strHoursMinLeft = stringResource(R.string.cw_hours_min_left)
    val strMinLeft = stringResource(R.string.cw_min_left)

    val cardContext = LocalContext.current

    val nextUpBadgeText = remember(
        nextUp?.hasAired,
        nextUp?.isReleaseAlert,
        nextUp?.isNewSeasonRelease,
        nextUp?.released,
        nextUp?.airDateLabel,
        strUpcoming,
        strNextUp,
        strNewEpisode,
        strNewSeason
    ) {
        nextUp?.let { info ->
            if (info.isReleaseAlert) {
                if (info.isNewSeasonRelease) {
                    strNewSeason
                } else {
                    strNewEpisode
                }
            } else if (!info.hasAired) {
                computeAirDateBadgeText(
                    cardContext,
                    info.released,
                    info.airDateLabel
                ) ?: strUpcoming
            } else {
                strNextUp
            }
        }
    }

    val remainingText = progress?.let {
        remember(
            it.position,
            it.duration,
            it.progressPercent
        ) {
            formatContinueWatchingProgressLabel(
                progress = it,
                resumeLabel = strResume,
                percentWatchedLabel = strPercentWatched,
                hoursMinLeftLabel = strHoursMinLeft,
                minLeftLabel = strMinLeft
            )
        }
    }

    val badgeText = remember(
        remainingText,
        nextUpBadgeText,
        strNextUp
    ) {
        remainingText ?: nextUpBadgeText ?: strNextUp
    }

    val progressFraction =
        remember(progress) {
            progress?.progressPercentage ?: 0f
        }

    /*
     * CARD deliberately keeps the exact artwork priority used by this fork
     * before the style feature was introduced.
     *
     * WIDE / POSTER prefer poster art because their artwork viewport is 2:3.
     */
    val imageModel = remember(
        item,
        cardStyle
    ) {
        if (usePosterArtwork) {
            firstNonBlank(
                nextUp?.poster,
                progress?.poster,
                nextUp?.backdrop,
                progress?.backdrop,
                nextUp?.thumbnail,
                episodeThumbnail
            )
        } else {
            when {
                nextUp != null && !nextUp.hasAired ->
                    firstNonBlank(
                        nextUp.backdrop,
                        nextUp.poster,
                        nextUp.thumbnail,
                        progress?.backdrop,
                        progress?.poster
                    )

                else ->
                    firstNonBlank(
                        episodeThumbnail,
                        nextUp?.thumbnail,
                        progress?.backdrop,
                        progress?.poster,
                        nextUp?.backdrop,
                        nextUp?.poster
                    )
            }
        }
    }

    val titleText =
        remember(progress, nextUp) {
            progress?.name ?: nextUp?.name.orEmpty()
        }

    val context = LocalContext.current
    val strAirsDateForEpisode =
        computeAirDateBadgeText(
            context,
            nextUp?.released,
            nextUp?.airDateLabel
        )

    val episodeTitle = remember(
        progress,
        nextUp,
        context,
        strAirsDateForEpisode
    ) {
        when {
            progress != null ->
                progress.episodeTitle?.localizeEpisodeTitle(context)

            nextUp != null && !nextUp.hasAired ->
                nextUp.episodeTitle?.localizeEpisodeTitle(context)
                    ?: strAirsDateForEpisode

            else ->
                nextUp?.episodeTitle?.localizeEpisodeTitle(context)
        }
    }

    val density = LocalDensity.current

    val artworkWidth =
        if (isWideStyle) {
            imageHeight * (2f / 3f)
        } else {
            cardWidth
        }

    val requestWidthPx =
        remember(artworkWidth, density) {
            with(density) {
                artworkWidth.roundToPx().coerceAtLeast(1)
            }
        }

    val requestHeightPx =
        remember(imageHeight, density) {
            with(density) {
                imageHeight.roundToPx().coerceAtLeast(1)
            }
        }

    val imageRequest =
        remember(
            imageModel,
            requestWidthPx,
            requestHeightPx
        ) {
            ImageRequest.Builder(context)
                .data(imageModel)
                .crossfade(false)
                .memoryCacheKey(
                    "${imageModel}_${requestWidthPx}x${requestHeightPx}"
                )
                .size(
                    width = requestWidthPx,
                    height = requestHeightPx
                )
                .build()
        }

    val bgColor = NuvioColors.Background
    val cwFocusRingColor = NuvioColors.FocusRing

    val cwFocusedBorder = remember(
        cwFocusRingColor,
        cwCardShape
    ) {
        Border(
            border = BorderStroke(
                2.dp,
                cwFocusRingColor
            ),
            shape = cwCardShape
        )
    }

    val badgeBackground = remember(
        bgColor,
        nextUp?.isReleaseAlert,
        nextUp?.isNewSeasonRelease
    ) {
        when {
            nextUp?.isNewSeasonRelease == true ->
                CwNewSeasonBadgeColor.copy(alpha = 0.8f)

            nextUp?.isReleaseAlert == true ->
                CwNewEpisodeBadgeColor.copy(alpha = 0.8f)

            else ->
                bgColor.copy(alpha = 0.8f)
        }
    }

    Card(
        onClick = {
            if (longPressTriggered) {
                longPressTriggered = false
            } else {
                onClick()
            }
        },
        modifier = modifier
            .width(cardWidth)
            .onFocusChanged {
                isCardFocused = it.isFocused
            }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent

                if (native.action == AndroidKeyEvent.ACTION_DOWN) {
                    if (native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                        longPressTriggered = true
                        onLongPress()
                        return@onPreviewKeyEvent true
                    }

                    val isLongPress =
                        native.isLongPress ||
                            native.repeatCount > 0

                    if (
                        isLongPress &&
                        isSelectKey(native.keyCode)
                    ) {
                        longPressTriggered = true
                        onLongPress()
                        return@onPreviewKeyEvent true
                    }
                }

                if (
                    native.action == AndroidKeyEvent.ACTION_UP &&
                    longPressTriggered &&
                    isSelectKey(native.keyCode)
                ) {
                    longPressTriggered = false
                    return@onPreviewKeyEvent true
                }

                false
            },
        shape = CardDefaults.shape(
            shape = cwCardShape
        ),
        colors = CardDefaults.colors(
            containerColor =
                if (isPosterStyle) {
                    Color.Transparent
                } else {
                    NuvioColors.BackgroundCard
                },
            focusedContainerColor =
                if (isPosterStyle) {
                    Color.Transparent
                } else {
                    NuvioColors.FocusBackground
                }
        ),
        border =
            if (isPosterStyle) {
                CardDefaults.border(
                    border = Border.None,
                    focusedBorder = Border.None
                )
            } else {
                CardDefaults.border(
                    border =
                        if (retainFocusOutline) {
                            cwFocusedBorder
                        } else {
                            Border.None
                        },
                    focusedBorder = cwFocusedBorder
                )
            },
        scale = CardDefaults.scale(
            focusedScale = 1f
        )
    ) {
        if (isWideStyle) {
            WideContinueWatchingCardContent(
                imageRequest = imageRequest,
                imageModel = imageModel,
                artworkWidth = artworkWidth,
                imageHeight = imageHeight,
                depthModifier = continueWatchingDepthModifier,
                cardShape = cwCardShape,
                titleText = titleText,
                episodeStr = episodeStr,
                episodeTitle = episodeTitle,
                badgeText = badgeText,
                badgeBackground = badgeBackground,
                progressFraction = progressFraction,
                hasProgress = progress != null
            )
            return@Card
        }

        Column(
            modifier =
                if (isPosterStyle) {
                    Modifier
                } else {
                    continueWatchingDepthModifier
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(imageHeight)
                    .then(
                        if (isPosterStyle) {
                            Modifier.nuvioCardDepth(
                                shape = cwCardShape,
                                surface =
                                    CardDepthSurface.CONTINUE_WATCHING,
                                style = cardDepthStyle
                            )
                        } else {
                            Modifier
                        }
                    )
                    .clip(cwClipShape)
                    .then(
                        if (
                            isPosterStyle &&
                            (
                                isCardFocused ||
                                    retainFocusOutline
                                )
                        ) {
                            Modifier.border(
                                border = BorderStroke(
                                    2.dp,
                                    cwFocusRingColor
                                ),
                                shape = cwCardShape
                            )
                        } else {
                            Modifier
                        }
                    )
            ) {
                if (imageModel.isNullOrBlank()) {
                    MonochromePosterPlaceholder()
                } else {
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = titleText,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                // Existing CARD gradient. Poster art stays clean.
                if (!isPosterStyle) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawWithCache {
                                val gradient =
                                    Brush.verticalGradient(
                                        colorStops = arrayOf(
                                            0.0f to Color.Transparent,
                                            0.5f to Color.Transparent,
                                            0.8f to
                                                bgColor.copy(
                                                    alpha = 0.7f
                                                ),
                                            1.0f to
                                                bgColor.copy(
                                                    alpha = 0.95f
                                                )
                                        ),
                                        startY = 0f,
                                        endY = size.height
                                    )

                                onDrawBehind {
                                    drawRect(gradient)
                                }
                            }
                    )

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp)
                    ) {
                        if (episodeStr != null) {
                            Text(
                                text = episodeStr,
                                style =
                                    MaterialTheme.typography.labelMedium,
                                color = NuvioColors.TextPrimary
                            )
                        }

                        Text(
                            text = titleText,
                            style =
                                MaterialTheme.typography.titleSmall,
                            color = NuvioColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        episodeTitle?.let { title ->
                            Text(
                                text = title,
                                style =
                                    MaterialTheme.typography.bodySmall,
                                color =
                                    NuvioTheme.extendedColors
                                        .textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                /*
                 * Poster cards omit resume/percent badges; Next Up / release
                 * badges remain useful and match the official treatment.
                 */
                if (!isPosterStyle || progress == null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clip(BadgeShape)
                            .background(badgeBackground)
                            .padding(
                                horizontal = 8.dp,
                                vertical = 4.dp
                            )
                    ) {
                        Text(
                            text = badgeText,
                            style =
                                MaterialTheme.typography.labelSmall,
                            color = NuvioColors.TextPrimary
                        )
                    }
                }

                if (progress != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(
                                horizontal = 10.dp,
                                vertical =
                                    if (isPosterStyle) {
                                        10.dp
                                    } else {
                                        4.dp
                                    }
                            )
                            .fillMaxWidth()
                            .then(
                                if (isPosterStyle) {
                                    Modifier
                                        .clip(
                                            RoundedCornerShape(
                                                999.dp
                                            )
                                        )
                                        .background(
                                            NuvioColors.Background
                                                .copy(alpha = 0.70f)
                                        )
                                        .padding(3.dp)
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(
                                    RoundedCornerShape(1.5.dp)
                                )
                                .height(3.dp)
                                .background(
                                    Color.Black.copy(
                                        alpha = 0.3f
                                    )
                                )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(
                                        progressFraction
                                    )
                                    .clip(
                                        RoundedCornerShape(
                                            1.5.dp
                                        )
                                    )
                                    .height(3.dp)
                                    .background(
                                        NuvioColors.Primary
                                    )
                            )
                        }
                    }
                }
            }

            if (isPosterStyle) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            top = 4.dp,
                            start = 4.dp,
                            end = 4.dp
                        )
                        .height(30.dp),
                    horizontalArrangement =
                        Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = titleText,
                            style =
                                MaterialTheme.typography.titleSmall,
                            color = NuvioColors.TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (episodeStr != null) {
                        Text(
                            text = episodeStr,
                            modifier =
                                Modifier.padding(start = 4.dp),
                            style =
                                MaterialTheme.typography.labelSmall,
                            color =
                                NuvioTheme.extendedColors
                                    .textSecondary,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WideContinueWatchingCardContent(
    imageRequest: ImageRequest,
    imageModel: String?,
    artworkWidth: Dp,
    imageHeight: Dp,
    depthModifier: Modifier,
    cardShape: RoundedCornerShape,
    titleText: String,
    episodeStr: String?,
    episodeTitle: String?,
    badgeText: String,
    badgeBackground: Color,
    progressFraction: Float,
    hasProgress: Boolean
) {
    Row(
        modifier = depthModifier
            .fillMaxWidth()
            .height(imageHeight)
            .clip(cardShape)
            .background(NuvioColors.BackgroundCard)
    ) {
        Box(
            modifier = Modifier
                .width(artworkWidth)
                .fillMaxHeight()
        ) {
            if (imageModel.isNullOrBlank()) {
                MonochromePosterPlaceholder()
            } else {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = titleText,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .padding(
                    horizontal = 12.dp,
                    vertical = 8.dp
                ),
            verticalArrangement =
                Arrangement.spacedBy(3.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = titleText,
                        style =
                            MaterialTheme.typography.titleSmall,
                        color = NuvioColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (!hasProgress) {
                    Box(
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .clip(BadgeShape)
                            .background(badgeBackground)
                            .padding(
                                horizontal = 7.dp,
                                vertical = 3.dp
                            )
                    ) {
                        Text(
                            text = badgeText,
                            style =
                                MaterialTheme.typography.labelSmall,
                            color = NuvioColors.TextPrimary
                        )
                    }
                }
            }

            Text(
                text = episodeStr.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color =
                    NuvioTheme.extendedColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = episodeTitle.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color =
                    NuvioTheme.extendedColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(
                modifier = Modifier.weight(1f)
            )

            if (hasProgress) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(1.5.dp))
                        .height(3.dp)
                        .background(
                            Color.Black.copy(alpha = 0.3f)
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressFraction)
                            .clip(
                                RoundedCornerShape(1.5.dp)
                            )
                            .height(3.dp)
                            .background(NuvioColors.Primary)
                    )
                }

                Text(
                    text = badgeText,
                    style =
                        MaterialTheme.typography.labelSmall,
                    color =
                        NuvioTheme.extendedColors
                            .textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ContinueWatchingOptionsDialog(
    item: ContinueWatchingItem,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    onDetails: () -> Unit,
    onStartFromBeginning: () -> Unit = {},
    showPlayManually: Boolean = false,
    onPlayManually: () -> Unit = {}
) {
    val title = when (item) {
        is ContinueWatchingItem.InProgress -> item.progress.name
        is ContinueWatchingItem.NextUp -> item.info.name
    }

    val glassEnvironment = LocalHomePopupGlassEnvironment.current
    val detailsFocusRequester = remember { FocusRequester() }
    var suppressNextKeyUp by remember { mutableStateOf(true) }
    val appearanceProgress = remember { Animatable(0f) }

    DisposableEffect(Unit) {
        glassEnvironment.onPopupVisibilityChanged(true)
        onDispose {
            glassEnvironment.onPopupVisibilityChanged(false)
        }
    }

    LaunchedEffect(Unit) {
        runCatching { detailsFocusRequester.requestFocus() }
        appearanceProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 220,
                easing = FastOutSlowInEasing
            )
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        val dialogView = LocalView.current

        DisposableEffect(dialogView) {
            val window =
                (dialogView.parent as? DialogWindowProvider)?.window

            window?.clearFlags(
                WindowManager.LayoutParams.FLAG_DIM_BEHIND
            )

            onDispose { }
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val panelShape = RoundedCornerShape(24.dp)
            val hazeState = glassEnvironment.hazeState
            val blurModifier =
                if (glassEnvironment.blurEnabled && hazeState != null) {
                    Modifier.hazeChild(
                        state = hazeState,
                        shape = panelShape,
                        tint = Color.Unspecified,
                        blurRadius = (1f + (29f * appearanceProgress.value)).dp,
                        noiseFactor = 0.025f * appearanceProgress.value
                    )
                } else {
                    Modifier
                }

            Box(
                modifier = Modifier
                    .width(520.dp)
                    .graphicsLayer {
                        alpha = appearanceProgress.value
                        val animatedScale =
                            0.96f + (0.04f * appearanceProgress.value)
                        scaleX = animatedScale
                        scaleY = animatedScale
                    }
                    .then(blurModifier)
                    .clip(panelShape)
                    .background(CwDialogGlassBrush, panelShape)
                    .border(1.dp, CwDialogGlassBorderColor, panelShape)
                    .padding(24.dp)
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (
                            suppressNextKeyUp &&
                            native.action == AndroidKeyEvent.ACTION_UP &&
                            (
                                native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                                    native.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                                    native.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER ||
                                    native.keyCode == AndroidKeyEvent.KEYCODE_MENU
                                )
                        ) {
                            suppressNextKeyUp = false
                            true
                        } else {
                            false
                        }
                    }
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = NuvioColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = stringResource(R.string.cw_dialog_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioColors.TextSecondary
                    )

                    Button(
                        onClick = onDetails,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(detailsFocusRequester),
                        colors = ButtonDefaults.colors(
                            containerColor = CwDialogGlassRowColor,
                            focusedContainerColor = CwDialogGlassRowFocusedColor,
                            contentColor = NuvioColors.TextPrimary,
                            focusedContentColor = Color.White
                        ),
                        scale = ButtonDefaults.scale(
                            focusedScale = 1.018f,
                            pressedScale = 0.99f
                        )
                    ) {
                        Text(stringResource(R.string.cw_action_go_to_details))
                    }

                    if (showPlayManually) {
                        Button(
                            onClick = onPlayManually,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.colors(
                                containerColor = CwDialogGlassRowColor,
                                focusedContainerColor = CwDialogGlassRowFocusedColor,
                                contentColor = NuvioColors.TextPrimary,
                                focusedContentColor = Color.White
                            ),
                            scale = ButtonDefaults.scale(
                                focusedScale = 1.018f,
                                pressedScale = 0.99f
                            )
                        ) {
                            Text(stringResource(R.string.play_manually))
                        }
                    }

                    if (item is ContinueWatchingItem.InProgress) {
                        Button(
                            onClick = onStartFromBeginning,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.colors(
                                containerColor = CwDialogGlassRowColor,
                                focusedContainerColor = CwDialogGlassRowFocusedColor,
                                contentColor = NuvioColors.TextPrimary,
                                focusedContentColor = Color.White
                            ),
                            scale = ButtonDefaults.scale(
                                focusedScale = 1.018f,
                                pressedScale = 0.99f
                            )
                        ) {
                            Text(
                                stringResource(
                                    R.string.cw_action_start_from_beginning
                                )
                            )
                        }
                    }

                    Button(
                        onClick = onRemove,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.colors(
                            containerColor = CwDialogGlassRowColor,
                            focusedContainerColor = CwDialogGlassRowFocusedColor,
                            contentColor = NuvioColors.TextPrimary,
                            focusedContentColor = Color.White
                        ),
                        scale = ButtonDefaults.scale(
                            focusedScale = 1.018f,
                            pressedScale = 0.99f
                        )
                    ) {
                        Text(stringResource(R.string.cw_action_remove))
                    }
                }
            }
        }
    }
}

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}

private fun firstNonBlank(vararg candidates: String?): String? {
    return candidates.firstOrNull { !it.isNullOrBlank() }?.trim()
}

internal fun formatRemainingTime(
    remainingMs: Long,
    strHoursMinLeft: String,
    strMinLeft: String,
    strAlmostDone: String
): String {
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60

    return when {
        hours > 0 -> strHoursMinLeft.format(hours, minutes)
        minutes > 0 -> strMinLeft.format(minutes)
        else -> strAlmostDone
    }
}
