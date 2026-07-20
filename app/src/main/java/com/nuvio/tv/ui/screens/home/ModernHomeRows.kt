@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nuvio.tv.ui.screens.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.drawText
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusable
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.tv.material3.Border
import androidx.tv.material3.Icon
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.ContinueWatchingCard
import com.nuvio.tv.ui.components.MonochromePosterPlaceholder
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.LocalSidebarExpanded
import com.nuvio.tv.LocalSidebarOpenRequest
import com.nuvio.tv.LocalNoBackdropImage
import com.nuvio.tv.ui.theme.NuvioColors
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.nuvio.tv.ui.util.dpadRepeatThrottle

// Single-clock anchored expansion channel: for end-of-row (right-edge
// anchored) expansion the row drives the expanded card's width per animation
// frame; the card consumes it in place of its own width spring so width and
// scroll position land in the same measure pass. Null = card animates itself
// (mid-row expansion, or feature idle).
private val LocalAnchoredExpandSink =
    androidx.compose.runtime.compositionLocalOf<androidx.compose.runtime.State<((Float) -> Unit)?>> {
        androidx.compose.runtime.mutableStateOf(null)
    }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ModernContinueWatchingRowItem(
    payload: ModernPayload.ContinueWatching,
    requester: FocusRequester,
    cardWidth: Dp,
    imageHeight: Dp,
    onFocused: () -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onShowOptions: (ContinueWatchingItem) -> Unit,
    onUpPressed: (() -> Unit)? = null
) {
    ContinueWatchingCard(
        item = payload.item,
        onClick = { onContinueWatchingClick(payload.item) },
        onLongPress = { onShowOptions(payload.item) },
        cardWidth = cardWidth,
        imageHeight = imageHeight,
        modifier = Modifier
            .focusRequester(requester)
            .onFocusChanged {
                if (it.isFocused) {
                    onFocused()
                }
            }
            .then(if (onUpPressed != null) Modifier.onPreviewKeyEvent { event ->
                if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                    event.key == androidx.compose.ui.input.key.Key.DirectionUp) {
                    onUpPressed()
                    true
                } else false
            } else Modifier)
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ModernCatalogRowItem(
    modifier: Modifier = Modifier,
    item: ModernCarouselItem,
    payload: ModernPayload.Catalog,
    requester: FocusRequester,
    useLandscapePosters: Boolean,
    showLabels: Boolean,
    posterCardCornerRadius: Dp,
    modernCatalogCardWidth: Dp,
    modernCatalogCardHeight: Dp,
    focusedPosterBackdropTrailerMuted: Boolean,
    effectiveExpandEnabled: Boolean,
    effectiveAutoplayEnabled: Boolean,
    trailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget,
    expandedCatalogFocusKey: String?,
    expandedTrailerPreviewUrl: String?,
    expandedTrailerPreviewAudioUrl: String?,
    isWatched: Boolean,
    onFocused: () -> Unit,
    onItemFocus: (MetaPreview) -> Unit,
    onPreloadAdjacentItem: () -> Unit,
    onCatalogSelectionFocused: (FocusedCatalogSelection) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onLongPress: () -> Unit,
    onBackdropInteraction: () -> Unit,
    onExpandedCatalogFocusKeyChange: (String?) -> Unit,
    isNearRowEnd: Boolean = false,
    onUpPressed: (() -> Unit)? = null
) {
    val focusKey = payload.focusKey
    val upPressedModifier = if (onUpPressed != null) modifier.then(Modifier.onPreviewKeyEvent { event ->
        if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
            event.key == androidx.compose.ui.input.key.Key.DirectionUp) {
            onUpPressed()
            true
        } else false
    }) else modifier
    val suppressCardExpansionForHeroTrailer =
        effectiveAutoplayEnabled &&
            trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.HERO_MEDIA
    val isBackdropExpanded =
        effectiveExpandEnabled &&
            expandedCatalogFocusKey == focusKey &&
            !suppressCardExpansionForHeroTrailer
    val isSidebarExpanded = LocalSidebarExpanded.current
    val playTrailerInExpandedCard =
        effectiveAutoplayEnabled &&
            !isSidebarExpanded &&
            trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD &&
            isBackdropExpanded
    val trailerPreviewUrl = if (playTrailerInExpandedCard) expandedTrailerPreviewUrl else null
    val trailerPreviewAudioUrl = if (playTrailerInExpandedCard) expandedTrailerPreviewAudioUrl else null

    Box(modifier = upPressedModifier) {
    ModernCarouselCard(
        item = item,
        useLandscapePosters = useLandscapePosters,
        showLabels = showLabels,
        cardCornerRadius = posterCardCornerRadius,
        cardWidth = modernCatalogCardWidth,
        cardHeight = modernCatalogCardHeight,
        focusedPosterBackdropExpandEnabled = effectiveExpandEnabled && !useLandscapePosters,
        isBackdropExpanded = isBackdropExpanded,
        playTrailerInExpandedCard = playTrailerInExpandedCard,
        focusedPosterBackdropTrailerMuted = focusedPosterBackdropTrailerMuted,
        trailerPreviewUrl = trailerPreviewUrl,
        trailerPreviewAudioUrl = trailerPreviewAudioUrl,
        isWatched = isWatched,
        focusRequester = requester,
        onFocused = remember(focusKey, payload, onFocused, onItemFocus, onPreloadAdjacentItem, onCatalogSelectionFocused) {
            {
                onFocused()
                item.metaPreview?.let { onItemFocus(it) }
                onPreloadAdjacentItem()
                onCatalogSelectionFocused(
                    FocusedCatalogSelection(
                        focusKey = focusKey,
                        payload = payload
                    )
                )
            }
        },
        onClick = remember(payload, onNavigateToDetail) {
            {
                onNavigateToDetail(
                    payload.itemId,
                    payload.itemType,
                    payload.addonBaseUrl
                )
            }
        },
        onLongPress = onLongPress,
        onBackdropInteraction = onBackdropInteraction,
        onTrailerEnded = remember(onExpandedCatalogFocusKeyChange) { { onExpandedCatalogFocusKeyChange(null) } },
        isNearRowEnd = isNearRowEnd,
        onUpPressed = onUpPressed
    )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ModernRowSection(
    row: HeroCarouselRow,
    rowTitleBottom: Dp,
    defaultBringIntoViewSpec: BringIntoViewSpec,
    focusStateCatalogRowScrollStates: Map<String, Int>,
    uiCaches: ModernHomeUiCaches,
    pendingRowFocus: PendingRowFocusHolder,
    onPendingRowFocusCleared: () -> Unit,
    onRowItemFocused: (String, Int, Boolean) -> Unit,
    useLandscapePosters: Boolean,
    perCatalogLandscape: Boolean = false,
    showLabels: Boolean,
    posterCardCornerRadius: Dp,
    focusedPosterBackdropTrailerMuted: Boolean,
    effectiveExpandEnabled: Boolean,
    effectiveAutoplayEnabled: Boolean,
    trailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget,
    expandedCatalogFocusKey: String?,
    expandedTrailerPreviewUrl: String?,
    expandedTrailerPreviewAudioUrl: String?,
    modernCatalogCardWidth: Dp,
    modernCatalogCardHeight: Dp,
    continueWatchingCardWidth: Dp,
    continueWatchingCardHeight: Dp,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingOptions: (ContinueWatchingItem) -> Unit,
    numberStyle: NumberStyle = NumberStyle.OFF,
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit,
    onItemFocus: (MetaPreview) -> Unit,
    onPreloadAdjacentItem: (MetaPreview) -> Unit,
    onCatalogSelectionFocused: (FocusedCatalogSelection) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit,
    onBackdropInteraction: () -> Unit,
    onExpandedCatalogFocusKeyChange: (String?) -> Unit,
    useThemeColorForNumbers: Boolean = false,
    isFirstRow: Boolean = false,
    isSecondRow: Boolean = false,
    onRequestCarouselFocus: () -> Unit = {},
    catalogSlideAnimatable: Animatable<Float, AnimationVector1D>? = null
) {
    val focusedItemByRow = uiCaches.focusedItemByRow
    val itemFocusRequesters = uiCaches.itemFocusRequesters
    val sidebarOpenRequest = LocalSidebarOpenRequest.current
    val rowListStates = uiCaches.rowListStates
    val loadMoreRequestedTotals = uiCaches.loadMoreRequestedTotals

    Column {
        val titleMediumStyle = MaterialTheme.typography.titleMedium
        val rowTitleStyle = remember(titleMediumStyle) {
            titleMediumStyle.copy(fontWeight = FontWeight.SemiBold)
        }
        Text(
            text = row.title,
            style = rowTitleStyle,
            color = NuvioColors.TextPrimary,
            modifier = Modifier.padding(start = 52.dp, bottom = rowTitleBottom)
        )

        val isCwRow = row.key == "continue_watching"
        val skeletonCardWidth = if (isCwRow) continueWatchingCardWidth else modernCatalogCardWidth
        val skeletonCardHeight = if (isCwRow) continueWatchingCardHeight else modernCatalogCardHeight
        var enrichmentTimeoutReached by rememberSaveable(key = "enrich_timeout_${row.key}") { mutableStateOf(false) }
        LaunchedEffect(row.key, row.items.isNotEmpty()) {
            if (row.items.isNotEmpty() && !row.enrichmentReady && !enrichmentTimeoutReached) {
                kotlinx.coroutines.delay(6_000L)
                enrichmentTimeoutReached = true
            }
        }
        val showSkeleton = (row.items.isEmpty() && row.isLoading) ||
            (row.items.isNotEmpty() && !row.enrichmentReady && !enrichmentTimeoutReached)
        if (showSkeleton) {
            ModernSkeletonRow(
                rowKey = row.key,
                cardWidth = skeletonCardWidth,
                cardHeight = skeletonCardHeight,
                cornerRadius = posterCardCornerRadius,
                isFirstRow = isFirstRow,
                isContinueWatchingRow = isCwRow,
                uiCaches = uiCaches,
                onRowItemFocused = onRowItemFocused,
                onRequestCarouselFocus = onRequestCarouselFocus
            )
            return
        }

        val rowListState = rowListStates.getOrPut(row.key) {
            LazyListState(
                firstVisibleItemIndex = focusStateCatalogRowScrollStates[row.key] ?: 0
            )
        }

        // Detect last partially-clipped item (first row only) for edge fade effect

        // Read animatable value in composition — only first row reads it, so only first row recomposes during transition
        // Rows stay at full alpha during the platform transition: the fade is
        // now performed once by the black overlay over the whole composite in
        // ModernHomeContent. Fading rows here too would darken them twice.
        val parentAlpha = 1f
        val prevAlpha = remember { androidx.compose.runtime.mutableFloatStateOf(parentAlpha) }
        val isFadingIn = parentAlpha > prevAlpha.floatValue
        prevAlpha.floatValue = parentAlpha

        val isRowScrolling by remember(rowListState) {
            derivedStateOf { rowListState.isScrollInProgress }
        }

        val currentRowState = rememberUpdatedState(row)
        val loadMoreCatalogId = row.catalogId
        val loadMoreAddonId = row.addonId
        val loadMoreApiType = row.apiType
        val canObserveLoadMore = row.supportsSkip &&
            row.hasMore &&
            !loadMoreCatalogId.isNullOrBlank() &&
            !loadMoreAddonId.isNullOrBlank() &&
            !loadMoreApiType.isNullOrBlank()

        LaunchedEffect(row.key) {
            snapshotFlow { Triple(pendingRowFocus.nonce, pendingRowFocus.key, pendingRowFocus.index) }
                .collect { (_, pendingKey, pendingIndex) ->
            val liveItems = currentRowState.value.items
            if (pendingKey != row.key) return@collect
            val targetIndex = (pendingIndex ?: 0)
                .coerceIn(0, (liveItems.size - 1).coerceAtLeast(0))
            val targetItemKey = liveItems.getOrNull(targetIndex)?.key ?: return@collect
            val requester = uiCaches.requesterFor(row.key, targetItemKey)
            var didFocus = false
            var didScrollToTarget = false
            repeat(20) {
                didFocus = runCatching {
                    requester.requestFocus()
                    true
                }.getOrDefault(false)
                if (didFocus) {
                    return@repeat
                }
                if (!didScrollToTarget) {
                    val visibleIndices = rowListState.layoutInfo.visibleItemsInfo.map { it.index }
                    if (targetIndex !in visibleIndices) {
                        runCatching { rowListState.scrollToItem(targetIndex) }
                    }
                    didScrollToTarget = true
                }
                withFrameNanos { }
            }
            if (!didFocus) {
                val fallbackIndex = rowListState.firstVisibleItemIndex
                    .coerceIn(0, (liveItems.size - 1).coerceAtLeast(0))
                val fallbackItemKey = liveItems.getOrNull(fallbackIndex)?.key
                didFocus = runCatching {
                    if (fallbackItemKey != null) {
                        uiCaches.requesterFor(row.key, fallbackItemKey).requestFocus()
                    }
                    true
                }.getOrDefault(false)
            }
            if (didFocus) {
                onPendingRowFocusCleared()
            }
                }
        }

        if (canObserveLoadMore) {
            LaunchedEffect(
                row.key,
                rowListState,
                canObserveLoadMore
            ) {
                snapshotFlow {
                    val layoutInfo = rowListState.layoutInfo
                    val total = layoutInfo.totalItemsCount
                    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    lastVisible to total
                }
                    .distinctUntilChanged()
                    .collect { (lastVisible, total) ->
                        if (total <= 0) return@collect
                        val rowState = currentRowState.value
                        val isNearEnd = lastVisible >= total - 4
                        if (!isNearEnd) {
                            loadMoreRequestedTotals.remove(rowState.key)
                            return@collect
                        }
                        val lastRequestedTotal = loadMoreRequestedTotals[rowState.key]
                        if (rowState.hasMore &&
                            !rowState.isLoading &&
                            lastRequestedTotal != total
                        ) {
                            loadMoreRequestedTotals[rowState.key] = total
                            onLoadMoreCatalog(
                                loadMoreCatalogId,
                                loadMoreAddonId,
                                loadMoreApiType
                            )
                        }
                    }
            }
        }

        val density = LocalDensity.current
        val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx().toFloat() }
        val rowStartPadding = 52.dp

        // End-of-row scroll travel padding (portrait expand modes only).
        // Landscape posters already handle this correctly — leave them alone.
        //
        // Strategy: pre-grow the end contentPadding as soon as the 2nd-to-last card
        // becomes visible — well before focus reaches the last card. This guarantees
        // the last card always has a full card-width + gap of scroll travel, so it
        // slides at the same speed and duration as every other card rather than
        // snapping across a short remaining distance.
        val canExpand = effectiveExpandEnabled && !useLandscapePosters
        // expansionDelta: how much wider the card becomes when expanded.
        val expansionDelta = if (canExpand) {
            ((modernCatalogCardHeight * (16f / 9f)) - modernCatalogCardWidth).coerceAtLeast(0.dp)
        } else {
            0.dp
        }

        // When platform parallax is active, the layout is measured wider by
        // catalogSlideDistancePx. Add equivalent dp to end padding so the expanded
        // card doesn't clip against the real screen edge.
        val parallaxExtraPadding = if (catalogSlideAnimatable != null) {
            with(density) { (screenWidthPx * 0.15f).toDp() }
        } else 0.dp
        // Resting end padding matches the non-expand margin: the last card sits
        // near the screen edge like in non-autotrailer mode. Clip avoidance for
        // end-of-row expansion is handled by the leftward overflow scroll below
        // instead of reserved empty track.
        val endPaddingTarget = rowStartPadding + parallaxExtraPadding
        val animatedEndPadding by animateDpAsState(
            targetValue = endPaddingTarget,
            animationSpec = tween(durationMillis = 200),
            label = "rowEndPadding_${row.key}"
        )


        val useCenteredScroll = effectiveExpandEnabled && trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD
        val horizontalBringIntoViewSpec = remember(density, defaultBringIntoViewSpec, useCenteredScroll, screenWidthPx) {
            val parentStartOffsetPx = with(density) { rowStartPadding.roundToPx() }
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            object : BringIntoViewSpec {
                // Match the home vertical row scroll feel (spring, not the
                // softer app-wide default): same stiffness/damping, so
                // horizontal poster glide and vertical row slide share one
                // physical response, scaled to their respective distances.
                override val scrollAnimationSpec: AnimationSpec<Float> =
                    androidx.compose.animation.core.spring(
                        dampingRatio = 0.95f,
                        stiffness = 400f
                    )

                override fun calculateScrollDistance(
                    offset: Float,
                    size: Float,
                    containerSize: Float
                ): Float {
                    // Clamp containerSize to real screen width — parallax layout modifier
                    // widens the measured container, but scroll calculations must use
                    // the actual visible screen width for correct end padding behavior.
                    val effectiveContainerSize = containerSize.coerceAtMost(screenWidthPx)
                    val childSize = abs(size)
                    val targetForLeadingEdge = if (useCenteredScroll) {
                        val centeredTarget = (effectiveContainerSize - childSize) / 2f
                        centeredTarget.coerceAtLeast(parentStartOffsetPx.toFloat())
                    } else {
                        val childSmallerThanParent = childSize <= effectiveContainerSize
                        val initialTarget = parentStartOffsetPx.toFloat()
                        val spaceAvailable = effectiveContainerSize - initialTarget
                        if (childSmallerThanParent && spaceAvailable < childSize) {
                            effectiveContainerSize - childSize
                        } else {
                            initialTarget
                        }
                    }
                    return offset - targetForLeadingEdge
                }
            }
        }

        // For numbered rows, increase item spacing to accommodate the large number overlay
        val isNumbered = numberStyle != NumberStyle.OFF
        val numberedRowSpacing = if (isNumbered) {
            if (useLandscapePosters) (modernCatalogCardWidth * 0.30f).coerceAtLeast(12.dp)
            else (modernCatalogCardWidth * 0.64f).coerceAtLeast(12.dp)
        } else 12.dp

        // Pre-measure number widths once at row level for stable sizing across all items
        val numberFontSizeRow = androidx.compose.ui.unit.TextUnit(
            if (useLandscapePosters) modernCatalogCardHeight.value * 0.80f else modernCatalogCardHeight.value * 0.55f,
            androidx.compose.ui.unit.TextUnitType.Sp
        )
        val numberBaseStyleRow = androidx.compose.ui.text.TextStyle(
            fontSize = numberFontSizeRow,
            fontWeight = androidx.compose.ui.text.font.FontWeight.W500,
            color = androidx.compose.ui.graphics.Color(0xFF888888)
        )
        val rowTextMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
        val singleDigitWidth = remember(numberBaseStyleRow) { rowTextMeasurer.measure("8", numberBaseStyleRow).size.width }
        val oneDigitWidth = remember(numberBaseStyleRow) { rowTextMeasurer.measure("1", numberBaseStyleRow).size.width }
        val doubleDigitWidth = remember(numberBaseStyleRow) { rowTextMeasurer.measure("88", numberBaseStyleRow).size.width }
        val tripleDigitWidth = remember(numberBaseStyleRow) { rowTextMeasurer.measure("888", numberBaseStyleRow).size.width }

        val numberedRowStartPadding = if (isNumbered) {
            val singleDigitDp = with(density) { oneDigitWidth.toDp() }
            val overlapDp = modernCatalogCardWidth * 0.10f
            rowStartPadding + (singleDigitDp - overlapDp) - 16.dp
        } else rowStartPadding

        val anchoredExpandSink = remember { mutableStateOf<((Float) -> Unit)?>(null) }
        // Leftward expansion near the row end (any card size, numbered or
        // plain rows). LazyList item offsets are CONTENT coordinates: origin
        // after the start contentPadding, which is numberedRowStartPadding for
        // this row. Convert to screen via +startPadPx, target the standard
        // margin from the real screen edge, floor at the resting edge so a
        // card resting closer than the margin is never pushed rightward. The
        // per-frame loop follows the width spring by construction and handles
        // late expansion (slow trailer resolution); it lives until the expand
        // key changes (collapse/refocus cancels the LaunchedEffect).
        if (canExpand) {
            androidx.compose.runtime.LaunchedEffect(expandedCatalogFocusKey) {
                val key = expandedCatalogFocusKey ?: return@LaunchedEffect
                val expandedItem = row.items.firstOrNull {
                    (it.payload as? ModernPayload.Catalog)?.focusKey == key
                } ?: return@LaunchedEffect
                val info = rowListState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.key == expandedItem.key } ?: return@LaunchedEffect
                val cardWidthRestPx = with(density) { modernCatalogCardWidth.toPx() }
                val expandedWidthPx = with(density) { (modernCatalogCardHeight * (16f / 9f)).toPx() }
                val marginPx = with(density) { rowStartPadding.roundToPx() }.toFloat()
                val startPadPx = with(density) { numberedRowStartPadding.roundToPx() }.toFloat()
                val restingRightEdgeContent = (info.offset + info.size).toFloat()
                val marginTargetContent = screenWidthPx - marginPx - startPadPx
                val expansionDeltaPx = expandedWidthPx - cardWidthRestPx
                // Anchor only when rightward growth would cross the standard
                // margin; mid-row cards keep their existing expansion untouched.
                if (restingRightEdgeContent + expansionDeltaPx <= marginTargetContent) return@LaunchedEffect
                val slotPadPx = (info.size - cardWidthRestPx).toFloat()
                val itemIndex = info.index
                // Publish the pin sink. The card drives its own width animation
                // (it alone knows the exact frame expansion begins) and calls
                // this every frame with its current width; we pin the item start
                // so start + width == the resting right edge. Both the card's
                // width write and this request live in the card's per-frame
                // snapshot, so one measure pass sees them together — anchored
                // from frame zero, no takeover frame.
                anchoredExpandSink.value = { widthPx ->
                    rowListState.requestScrollToItem(
                        itemIndex,
                        Math.round(restingRightEdgeContent - slotPadPx - widthPx)
                    )
                }
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    anchoredExpandSink.value = null
                }
            }
        }

        CompositionLocalProvider(
            LocalBringIntoViewSpec provides horizontalBringIntoViewSpec,
            LocalAnchoredExpandSink provides anchoredExpandSink
        ) {
            LazyRow(
                state = rowListState,
                modifier = Modifier
                    .onPreviewKeyEvent { event ->
                        if (isFirstRow &&
                            event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                            event.key == androidx.compose.ui.input.key.Key.DirectionUp) {
                            onRequestCarouselFocus()
                            true
                        } else if (event.key == androidx.compose.ui.input.key.Key.DirectionLeft) {
                            val focusedIndex = focusedItemByRow[row.key] ?: 0
                            val isAtStart = focusedIndex == 0
                            if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                                if (isAtStart && event.nativeKeyEvent.repeatCount == 0) {
                                    sidebarOpenRequest()
                                    true // single press at edge — open sidebar
                                } else {
                                    false // repeat press or not at edge — let LazyRow handle
                                }
                            } else false
                        } else false
                    }
                    .dpadRepeatThrottle(horizontalGateMs = 100L, verticalGateMs = 100L)
                    .focusRestorer {
                            val hasInteracted = uiCaches.userInteractedRows.contains(row.key)
                            val rememberedIndex = (focusedItemByRow[row.key] ?: 0)
                                .coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
                            val fallbackIndex = rowListState.firstVisibleItemIndex
                                .coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
                            val restoreIndex = if (hasInteracted && rememberedIndex in row.items.indices) {
                                rememberedIndex
                            } else {
                                fallbackIndex
                            }
                            val visibleIndices = rowListState.layoutInfo.visibleItemsInfo.map { it.index }.toSet()
                            val safeIndex = if (restoreIndex in visibleIndices) restoreIndex else
                                visibleIndices.minByOrNull { kotlin.math.abs(it - restoreIndex) } ?: fallbackIndex
                            val itemKey = row.items.getOrNull(safeIndex)?.key ?: row.items.first().key
                            itemFocusRequesters[row.key]?.get(itemKey) ?: FocusRequester.Default
                    },
                contentPadding = PaddingValues(start = numberedRowStartPadding, end = animatedEndPadding),
                horizontalArrangement = Arrangement.spacedBy(numberedRowSpacing)
            ) {
                itemsIndexed(
                    items = row.items,
                    key = { _, item -> item.key },
                    contentType = { _, item ->
                        when (item.payload) {
                            is ModernPayload.ContinueWatching -> "modern_cw_card"
                            is ModernPayload.Catalog -> "modern_catalog_card"
                        }
                    }
                ) { index, item ->
                    val requester = uiCaches.requesterFor(row.key, item.key)
                    val isContinueWatchingRow = row.key == "continue_watching"
                    val onFocused = remember(row.key, index, isContinueWatchingRow) {
                        { onRowItemFocused(row.key, index, isContinueWatchingRow) }
                    }

                    when (val payload = item.payload) {
                        is ModernPayload.ContinueWatching -> {
                            ModernContinueWatchingRowItem(
                                payload = payload,
                                requester = requester,
                                cardWidth = continueWatchingCardWidth,
                                imageHeight = continueWatchingCardHeight,
                                onFocused = onFocused,
                                onContinueWatchingClick = onContinueWatchingClick,
                                onShowOptions = onContinueWatchingOptions,
                                onUpPressed = if (isFirstRow) onRequestCarouselFocus else null
                            )
                        }

                        is ModernPayload.Catalog -> {
                            val nextCatalogItem = row.items.getOrNull(index + 1)?.metaPreview
                            val isWatched = remember(item.key, isCatalogItemWatched) {
                                item.metaPreview?.let(isCatalogItemWatched) == true
                            }
                            val onLongPress: () -> Unit = remember(item.metaPreview, payload.addonBaseUrl) {
                                {
                                    item.metaPreview?.let { preview ->
                                        onCatalogItemLongPress(preview, payload.addonBaseUrl)
                                    }
                                    Unit
                                }
                            }
                            val cardNumber = index + 1
                            if (numberStyle != NumberStyle.OFF) {
                                val digitPadding = when {
                                    cardNumber >= 100 -> if (useLandscapePosters) modernCatalogCardWidth * 0.40f else modernCatalogCardWidth * 0.81f
                                    cardNumber >= 10 -> if (useLandscapePosters) modernCatalogCardWidth * 0.20f else modernCatalogCardWidth * 0.41f
                                    else -> 0.dp
                                }
                                val preMeasuredWidth = with(androidx.compose.ui.platform.LocalDensity.current) {
                                    when {
                                        cardNumber >= 100 -> tripleDigitWidth.toDp()
                                        cardNumber >= 10 -> doubleDigitWidth.toDp()
                                        else -> oneDigitWidth.toDp()
                                    }
                                }
                                NumberedCatalogCardWrapper(
                                    number = cardNumber,
                                    cardWidth = modernCatalogCardWidth,
                                    cardHeight = modernCatalogCardHeight,
                                    extraStartPadding = digitPadding,
                                    preMeasuredTextWidth = preMeasuredWidth,
                                    numberStyle = numberStyle,
                                    useThemeColorForNumbers = useThemeColorForNumbers,
                                    useLandscapePosters = useLandscapePosters || perCatalogLandscape
                                ) {
                                    ModernCatalogRowItem(
                                        modifier = Modifier,
                                        item = item,
                                        payload = payload,
                                        requester = requester,
                                        useLandscapePosters = useLandscapePosters || perCatalogLandscape,
                                        showLabels = showLabels,
                                        posterCardCornerRadius = posterCardCornerRadius,
                                        modernCatalogCardWidth = modernCatalogCardWidth,
                                        modernCatalogCardHeight = modernCatalogCardHeight,
                                        focusedPosterBackdropTrailerMuted = focusedPosterBackdropTrailerMuted,
                                        effectiveExpandEnabled = effectiveExpandEnabled && !isRowScrolling,
                                        effectiveAutoplayEnabled = effectiveAutoplayEnabled && !isRowScrolling,
                                        trailerPlaybackTarget = trailerPlaybackTarget,
                                        expandedCatalogFocusKey = expandedCatalogFocusKey,
                                        expandedTrailerPreviewUrl = expandedTrailerPreviewUrl,
                                        expandedTrailerPreviewAudioUrl = expandedTrailerPreviewAudioUrl,
                                        isWatched = isWatched,
                                        onFocused = onFocused,
                                        onItemFocus = onItemFocus,
                                        onPreloadAdjacentItem = remember(nextCatalogItem) {
                                            { nextCatalogItem?.let(onPreloadAdjacentItem) }
                                        },
                                        onCatalogSelectionFocused = onCatalogSelectionFocused,
                                        onNavigateToDetail = onNavigateToDetail,
                                        onLongPress = onLongPress,
                                        onBackdropInteraction = onBackdropInteraction,
                                        onExpandedCatalogFocusKeyChange = onExpandedCatalogFocusKeyChange,
                                        isNearRowEnd = index >= row.items.size - 2,
                                        onUpPressed = if (isFirstRow) onRequestCarouselFocus else null,
                                    )
                                }
                            } else {
                                ModernCatalogRowItem(
                                    modifier = Modifier,
                                    item = item,
                                    payload = payload,
                                    requester = requester,
                                    useLandscapePosters = useLandscapePosters || perCatalogLandscape,
                                    showLabels = showLabels,
                                    posterCardCornerRadius = posterCardCornerRadius,
                                    modernCatalogCardWidth = modernCatalogCardWidth,
                                    modernCatalogCardHeight = modernCatalogCardHeight,
                                    focusedPosterBackdropTrailerMuted = focusedPosterBackdropTrailerMuted,
                                    effectiveExpandEnabled = effectiveExpandEnabled && !isRowScrolling,
                                    effectiveAutoplayEnabled = effectiveAutoplayEnabled && !isRowScrolling,
                                    trailerPlaybackTarget = trailerPlaybackTarget,
                                    expandedCatalogFocusKey = expandedCatalogFocusKey,
                                    expandedTrailerPreviewUrl = expandedTrailerPreviewUrl,
                                    expandedTrailerPreviewAudioUrl = expandedTrailerPreviewAudioUrl,
                                    isWatched = isWatched,
                                    onFocused = onFocused,
                                    onItemFocus = onItemFocus,
                                    onPreloadAdjacentItem = remember(nextCatalogItem) {
                                        { nextCatalogItem?.let(onPreloadAdjacentItem) }
                                    },
                                    onCatalogSelectionFocused = onCatalogSelectionFocused,
                                    onNavigateToDetail = onNavigateToDetail,
                                    onLongPress = onLongPress,
                                    onBackdropInteraction = onBackdropInteraction,
                                    onExpandedCatalogFocusKeyChange = onExpandedCatalogFocusKeyChange,
                                    isNearRowEnd = index >= row.items.size - 2,
                                    onUpPressed = if (isFirstRow) onRequestCarouselFocus else null,
                                )
                            }
                        }
                    }
                }

            }
        }
    }
}


@androidx.compose.ui.ExperimentalComposeUiApi
@Composable
private fun ModernSkeletonRow(
    rowKey: String,
    cardWidth: Dp,
    cardHeight: Dp,
    cornerRadius: Dp,
    isFirstRow: Boolean,
    isContinueWatchingRow: Boolean,
    uiCaches: ModernHomeUiCaches,
    onRowItemFocused: (String, Int, Boolean) -> Unit,
    onRequestCarouselFocus: () -> Unit
) {
    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx().toFloat() }
    val spacing = 12.dp
    val rowStartPadding = 52.dp
    val cardWidthPx = with(density) { cardWidth.roundToPx() }
    val spacingPx = with(density) { spacing.roundToPx() }
    val rowStartPaddingPx = with(density) { rowStartPadding.roundToPx() }
    val availablePx = (screenWidthPx - rowStartPaddingPx).coerceAtLeast(0f)
    val count = (kotlin.math.ceil(availablePx / (cardWidthPx + spacingPx).toFloat()).toInt() + 1)
        .coerceAtLeast(1)
    val sidebarOpenRequest = LocalSidebarOpenRequest.current
    val rowListState = uiCaches.rowListStates.getOrPut(rowKey) { androidx.compose.foundation.lazy.LazyListState() }
    val skeletonFallbackRequester = remember(rowKey) { uiCaches.requesterFor(rowKey, "skeleton_0") }
    LazyRow(
        state = rowListState,
        modifier = Modifier
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionUp -> { if (isFirstRow) { onRequestCarouselFocus(); true } else false }
                        Key.DirectionLeft -> {
                            val focused = uiCaches.focusedItemByRow[rowKey] ?: 0
                            if (focused == 0) { sidebarOpenRequest(); true } else false
                        }
                        else -> false
                    }
                } else false
            }
            .dpadRepeatThrottle(horizontalGateMs = 100L, verticalGateMs = 100L)
            .focusRestorer { skeletonFallbackRequester },
        contentPadding = PaddingValues(start = rowStartPadding, end = rowStartPadding),
        horizontalArrangement = Arrangement.spacedBy(spacing)
    ) {
        items(count) { index ->
            val requester = uiCaches.requesterFor(rowKey, "skeleton_$index")
            var isFocused by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .size(width = cardWidth, height = cardHeight)
                    .clip(RoundedCornerShape(cornerRadius))
                    .focusRequester(requester)
                    .onFocusChanged { fs ->
                        isFocused = fs.isFocused
                        if (fs.isFocused) {
                            uiCaches.focusedItemByRow[rowKey] = index
                            onRowItemFocused(rowKey, index, isContinueWatchingRow)
                        }
                    }
                    .focusable()
                    .border(
                        width = if (isFocused) 2.dp else 0.dp,
                        color = if (isFocused) Color.White.copy(alpha = 0.7f) else Color.Transparent,
                        shape = RoundedCornerShape(cornerRadius)
                    )
            ) {
                MonochromePosterPlaceholder()
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ModernCarouselCard(
    item: ModernCarouselItem,
    useLandscapePosters: Boolean,
    showLabels: Boolean,
    cardCornerRadius: Dp,
    cardWidth: Dp,
    cardHeight: Dp,
    focusedPosterBackdropExpandEnabled: Boolean,
    isBackdropExpanded: Boolean,
    playTrailerInExpandedCard: Boolean,
    focusedPosterBackdropTrailerMuted: Boolean,
    trailerPreviewUrl: String?,
    trailerPreviewAudioUrl: String?,
    isWatched: Boolean,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onBackdropInteraction: () -> Unit,
    onTrailerEnded: () -> Unit,
    isNearRowEnd: Boolean = false,
    onUpPressed: (() -> Unit)? = null
) {
    val cardShape = remember(cardCornerRadius) { RoundedCornerShape(cardCornerRadius) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val expandedCardWidth = remember(cardHeight) { cardHeight * (16f / 9f) }
    val isSidebarExpanded = LocalSidebarExpanded.current
    val noBackdropImage = LocalNoBackdropImage.current

    // In noBackdropImage mode: card expansion is gated on trailer first frame.
    // When off: simple boolean, no extra state.
    // Always remember unconditionally (Compose rule), but only key on isBackdropExpanded when needed.
    var trailerFirstFrameRendered by remember(
        if (noBackdropImage) trailerPreviewUrl else null,
        if (noBackdropImage) isBackdropExpanded else null
    ) { mutableStateOf(false) }

    // In noBackdropImage mode: card only expands once the trailer has its first frame.
    // Otherwise use original behavior — playTrailerInExpandedCard already has !isSidebarExpanded
    // baked in, so effectiveIsExpanded collapses instantly when sidebar opens.
    val effectiveIsExpanded = if (noBackdropImage && playTrailerInExpandedCard) {
        isBackdropExpanded && trailerFirstFrameRendered && !isSidebarExpanded
    } else {
        isBackdropExpanded && !isSidebarExpanded
    }

    val targetCardWidth = if (focusedPosterBackdropExpandEnabled && effectiveIsExpanded) {
        expandedCardWidth
    } else {
        cardWidth
    }
    // Original: default spring animation — no snap() override
    val animatedCardWidthBase by if (focusedPosterBackdropExpandEnabled) {
        animateDpAsState(
            targetValue = targetCardWidth,
            label = "modernCardWidth"
        )
    } else {
        rememberUpdatedState(cardWidth)
    }
    // Single-clock anchored expansion, driven from the card: the card knows
    // the exact frame expansion begins (its own effectiveIsExpanded, which in
    // noBackdropImage mode gates on the trailer's first frame), so it owns the
    // width Animatable and pins the row every frame in the same snapshot — the
    // measure pass sees width + item-start together, anchored from frame zero.
    val anchoredSink = LocalAnchoredExpandSink.current.value
    val isAnchored = anchoredSink != null && focusedPosterBackdropExpandEnabled
    val cardWidthPxForAnim = with(density) { cardWidth.toPx() }
    val anchoredWidth = remember { androidx.compose.animation.core.Animatable(cardWidthPxForAnim) }
    androidx.compose.runtime.LaunchedEffect(isAnchored, effectiveIsExpanded, cardWidth, targetCardWidth) {
        val collapsedPx = with(density) { cardWidth.toPx() }
        // Not actively anchored-expanded: hard-reset to the collapsed width so
        // every fresh expansion starts from true rest. Without this, the
        // Animatable retains its prior end value across a collapse+re-expand in
        // the same row visit, and the next expansion snaps right before
        // settling (the fast-right glitch).
        if (!isAnchored || !effectiveIsExpanded) {
            anchoredWidth.snapTo(collapsedPx)
            return@LaunchedEffect
        }
        // Guarantee we begin from collapsed even if a prior value lingered, then
        // drive expansion; the card is anchored from the first driven frame.
        if (anchoredWidth.value != collapsedPx) anchoredWidth.snapTo(collapsedPx)
        val targetPx = with(density) { targetCardWidth.toPx() }
        anchoredWidth.animateTo(targetPx) {
            val sink = anchoredSink ?: return@animateTo
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                sink(value)
            }
        }
    }
    val animatedCardWidth = if (isAnchored) with(density) { anchoredWidth.value.toDp() } else animatedCardWidthBase

    // In noBackdropImage mode, NEVER switch to the backdrop image.
    // The poster stays as-is; covered by the black overlay then the trailer.
    val imageUrl = remember(noBackdropImage, playTrailerInExpandedCard, effectiveIsExpanded, item.imageUrl, item.heroPreview.backdrop, item.heroPreview.poster) {
        if (useLandscapePosters) {
            item.imageUrl
        } else if (noBackdropImage && playTrailerInExpandedCard) {
            item.imageUrl ?: item.heroPreview.poster ?: item.heroPreview.backdrop
        } else if (focusedPosterBackdropExpandEnabled && effectiveIsExpanded) {
            item.heroPreview.backdrop ?: item.imageUrl ?: item.heroPreview.poster
        } else {
            item.imageUrl ?: item.heroPreview.poster ?: item.heroPreview.backdrop
        }
    }
    val maxRequestCardWidth = if (focusedPosterBackdropExpandEnabled) {
        maxOf(cardWidth, expandedCardWidth)
    } else {
        cardWidth
    }
    val requestWidthPx = remember(maxRequestCardWidth, density) {
        with(density) { maxRequestCardWidth.roundToPx() }
    }
    val requestHeightPx = remember(cardHeight, density) {
        with(density) { cardHeight.roundToPx() }
    }
    val imageModel = remember(context, imageUrl, requestWidthPx, requestHeightPx) {
        imageUrl?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(false)
                .size(width = requestWidthPx, height = requestHeightPx)
                // Bridge reloads with the last cached bitmap for this URL (from
                // any size entry) instead of a blank frame. Fixes the disk-
                // reload blink on titles whose fallback enrichment collapses
                // poster/backdrop/imageUrl to one file requested at multiple
                // sizes (e.g. House: Swan Song), which fragments the auto cache
                // key and evicts the card-size entry. Size-keyed caching and
                // hero/backdrop rendering are unchanged.
                .placeholderMemoryCacheKey(it)
                .build()
        }
    }
    val logoHeight = remember(cardHeight) { cardHeight * 0.34f }
    val logoHeightPx = remember(logoHeight, density) {
        with(density) { logoHeight.roundToPx() }
    }
    val maxLogoWidthPx = remember(maxRequestCardWidth, density) {
        with(density) { (maxRequestCardWidth * 0.62f).roundToPx() }
    }
    // Freeze logo URL — enrichment updates must not cause image reload/flash while
    // the URL stays the same. But the enrichment fallback chain (TMDB -> metahub ->
    // meta addon) can settle on a better/different URL after an earlier attempt already
    // populated a worse one (e.g. a transient network hiccup on a fallback tier) — so we
    // accept any new non-blank value that actually differs, not just the very first one.
    val frozenLogoUrl = remember(item.key) { mutableStateOf(item.heroPreview.logo) }
    if (!item.heroPreview.logo.isNullOrBlank() && item.heroPreview.logo != frozenLogoUrl.value) {
        frozenLogoUrl.value = item.heroPreview.logo
    }
    val effectiveLogoUrl = frozenLogoUrl.value

    val logoModel = remember(item.key, effectiveLogoUrl) {
        effectiveLogoUrl?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(false)
                .size(width = maxLogoWidthPx, height = logoHeightPx)
                .build()
        }
    }
    var landscapeLogoLoadFailed by remember(effectiveLogoUrl) { mutableStateOf(false) }
    // shouldPlayTrailerInCard: original = playTrailerInExpandedCard only.
    // playTrailerInExpandedCard already includes !isSidebarExpanded so trailer stops instantly.
    val shouldPlayTrailerInCard = playTrailerInExpandedCard && !trailerPreviewUrl.isNullOrBlank()
    val hasImage = !imageUrl.isNullOrBlank()
    // Freeze hasLandscapeLogo — once logo is available, never hide it to avoid flash
    val hasLandscapeLogoInstant = useLandscapePosters &&
        !effectiveLogoUrl.isNullOrBlank() &&
        !landscapeLogoLoadFailed
    val frozenHasLandscapeLogo = remember(item.key) { mutableStateOf(hasLandscapeLogoInstant) }
    if (hasLandscapeLogoInstant && !frozenHasLandscapeLogo.value) {
        frozenHasLandscapeLogo.value = true
    }
    val hasLandscapeLogo = frozenHasLandscapeLogo.value && !landscapeLogoLoadFailed
    var isFocused by remember { mutableStateOf(false) }
    var longPressTriggered by remember { mutableStateOf(false) }

    val backgroundCardColor = NuvioColors.BackgroundCard
    val focusRingColor = NuvioColors.FocusRing
    val titleMedium = MaterialTheme.typography.titleMedium
    val focusedBorder = remember(cardShape, focusRingColor) {
        Border(
            border = BorderStroke(2.dp, focusRingColor),
            shape = cardShape
        )
    }
    val titleStyle = remember(titleMedium) {
        titleMedium.copy(fontWeight = FontWeight.Medium)
    }

    // Collapse overlay — only active when noBackdropImage is on.
    var blackOverlayPhase by remember(trailerPreviewUrl, isBackdropExpanded) { mutableStateOf(0) }
    var collapseOverlayVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun triggerCollapseOverlay(holdMs: Long = 300L) {
        scope.launch {
            collapseOverlayVisible = true
            delay(holdMs)
            collapseOverlayVisible = false
        }
    }

    LaunchedEffect(effectiveIsExpanded, noBackdropImage, playTrailerInExpandedCard) {
        if (noBackdropImage && playTrailerInExpandedCard && effectiveIsExpanded) {
            blackOverlayPhase = 1
            delay(500)
            blackOverlayPhase = 2
        } else {
            blackOverlayPhase = 0
        }
    }

    LaunchedEffect(isSidebarExpanded) {
        if (noBackdropImage && isSidebarExpanded && trailerFirstFrameRendered) {
            triggerCollapseOverlay(300L)
        }
    }

    val blackOverlayAlpha = if (blackOverlayPhase == 1) 1f else 0f

    val topOverlayAlpha = if (noBackdropImage) {
        when {
            isSidebarExpanded && trailerFirstFrameRendered -> 1f
            collapseOverlayVisible -> 1f
            else -> 0f
        }
    } else {
        0f
    }

    Column(
        modifier = Modifier.width(animatedCardWidth),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Card(
            onClick = {
                if (longPressTriggered) {
                    longPressTriggered = false
                } else {
                    onClick()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight)
                .focusRequester(focusRequester)
                .onFocusChanged {
                    isFocused = it.isFocused
                    if (it.isFocused) {
                        onFocused()
                    }
                }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN) {
                        if (focusedPosterBackdropExpandEnabled && shouldResetBackdropTimer(event.key)) {
                            onBackdropInteraction()
                        }
                        if (native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                            longPressTriggered = true
                            onLongPress()
                            return@onPreviewKeyEvent true
                        }
                        val isLongPress = native.isLongPress || native.repeatCount > 0
                        if (isLongPress && isSelectKey(native.keyCode)) {
                            longPressTriggered = true
                            onLongPress()
                            return@onPreviewKeyEvent true
                        }
                    }
                    if (native.action == AndroidKeyEvent.ACTION_UP &&
                        longPressTriggered &&
                        isSelectKey(native.keyCode)
                    ) {
                        longPressTriggered = false
                        return@onPreviewKeyEvent true
                    }
                    if (native.action == AndroidKeyEvent.ACTION_DOWN &&
                        native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP &&
                        onUpPressed != null
                    ) {
                        onUpPressed()
                        return@onPreviewKeyEvent true
                    }
                    false
                },
            shape = CardDefaults.shape(shape = cardShape),
            colors = CardDefaults.colors(
                containerColor = backgroundCardColor,
                focusedContainerColor = backgroundCardColor
            ),
            border = CardDefaults.border(
                focusedBorder = focusedBorder
            ),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val mediaLayerModifier = remember(hasLandscapeLogo) {
                    if (hasLandscapeLogo) {
                        Modifier
                            .fillMaxSize()
                            .drawWithCache {
                                onDrawWithContent {
                                    drawContent()
                                    drawRect(brush = MODERN_LANDSCAPE_LOGO_GRADIENT, size = size)
                                }
                            }
                    } else {
                        Modifier.fillMaxSize()
                    }
                }

                // Layer 1 (bottom): Poster image — hidden when collapse overlay is fully opaque
                val posterAlpha = if (noBackdropImage && topOverlayAlpha == 1f) 0f else 1f

                Box(modifier = mediaLayerModifier.graphicsLayer { alpha = posterAlpha }) {
                    if (hasImage) {
                        AsyncImage(
                            model = imageModel,
                            contentDescription = item.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else if (!useLandscapePosters) {
                        MonochromePosterPlaceholder()
                    }
                }

                // Layer 2: Black backdrop behind trailer — only once trailer is painting,
                // so the poster/backdrop stays visible until the trailer is ready.
                if (shouldPlayTrailerInCard && trailerFirstFrameRendered) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                    )
                }

                // Layer 3: Trailer video (no overscan — letterbox naturally)
                if (shouldPlayTrailerInCard) {
                    TrailerPlayer(
                        trailerUrl = trailerPreviewUrl,
                        trailerAudioUrl = trailerPreviewAudioUrl,
                        isPlaying = !isSidebarExpanded,
                        onEnded = {
                            trailerFirstFrameRendered = false
                            blackOverlayPhase = 0
                            onTrailerEnded()
                        },
                        muted = focusedPosterBackdropTrailerMuted,
                        onFirstFrameRendered = {
                            trailerFirstFrameRendered = true
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Layer 3: Black overlay for noBackdropImage expand transition.
                if (noBackdropImage && blackOverlayAlpha > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                    )
                }

                // Layer 4: Collapse overlay — covers sidebar-open transition.
                if (noBackdropImage && topOverlayAlpha > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = topOverlayAlpha }
                            .background(Color.Black)
                    )
                }

                if (hasLandscapeLogo) {
                    AsyncImage(
                        model = logoModel,
                        contentDescription = item.title,
                        onError = { landscapeLogoLoadFailed = true },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(0.65f)
                            .height(cardHeight * 0.40f)
                            .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart
                    )
                } else if (useLandscapePosters) {
                    val posterCaslonTypeface = remember {
                        android.graphics.Typeface.Builder(context.assets, "fonts/caslon_regular.ttf")
                            .setFontVariationSettings("'wght' 300")
                            .setWeight(300)
                            .build()
                    }
                    val posterBaseSizePx = with(density) { titleStyle.fontSize.toPx() }
                    val posterBoxHeightPx = with(density) { (cardHeight * 0.40f).toPx() }
                    // Safety margin: shrink the measured box slightly so real TextView rendering
                    // (which can have small padding/metric differences from StaticLayout) never overflows.
                    val posterAvailWidthPx = with(density) { (cardWidth * 0.65f - 20.dp).toPx() * 0.92f }
                    val posterAvailHeightPx = posterBoxHeightPx * 0.92f
                    val posterComputedSizePx = remember(item.title, posterAvailWidthPx, posterAvailHeightPx) {
                        val paint = android.text.TextPaint().apply {
                            typeface = posterCaslonTypeface
                            isAntiAlias = true
                        }
                        var size = posterBaseSizePx
                        val minSize = posterBaseSizePx * 0.15f
                        val widthI = posterAvailWidthPx.toInt().coerceAtLeast(1)
                        while (size > minSize) {
                            paint.textSize = size
                            val layout = android.text.StaticLayout.Builder
                                .obtain(item.title, 0, item.title.length, paint, widthI)
                                .setLineSpacing(0f, 0.9f)
                                .setIncludePad(false)
                                .setMaxLines(3)
                                .setEllipsize(null)
                                .build()
                            val fits = layout.lineCount <= 3 && layout.height <= posterAvailHeightPx.toInt()
                            val noOverflow = (0 until layout.lineCount).none { layout.getEllipsisCount(it) > 0 }
                            if (fits && noOverflow) break
                            size -= posterBaseSizePx * 0.04f
                        }
                        size.coerceAtLeast(minSize)
                    }
                    androidx.compose.ui.viewinterop.AndroidView(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(0.65f)
                            .height(cardHeight * 0.40f)
                            .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                        factory = { ctx ->
                            android.widget.TextView(ctx).apply {
                                layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                typeface = posterCaslonTypeface
                                setTextColor(android.graphics.Color.WHITE)
                                maxLines = 3
                                ellipsize = android.text.TextUtils.TruncateAt.END
                                gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.CENTER_HORIZONTAL
                                setLineSpacing(0f, 0.9f)
                                includeFontPadding = false
                                setPadding(0, 0, 0, 0)
                            }
                        },
                        update = { tv ->
                            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, posterComputedSizePx)
                            tv.text = item.title
                        }
                    )
                }

                if (isWatched) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.episodes_cd_watched),
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 8.dp, top = 8.dp)
                            .zIndex(2f)
                            .size(21.dp)
                            .drawBehind {
                                drawCircle(
                                    color = androidx.compose.ui.graphics.Color.Black,
                                    radius = size.minDimension / 2f + 1.5f
                                )
                            }
                    )
                }
            }
        }

        if (showLabels && !effectiveIsExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            ) {
                Text(
                    text = item.title,
                    style = titleStyle,
                    color = NuvioColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                item.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = NuvioColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun NumberedCatalogCardWrapper(
    number: Int,
    cardWidth: androidx.compose.ui.unit.Dp,
    cardHeight: androidx.compose.ui.unit.Dp,
    extraStartPadding: androidx.compose.ui.unit.Dp = 0.dp,
    preMeasuredTextWidth: androidx.compose.ui.unit.Dp = 0.dp,
    numberStyle: NumberStyle = NumberStyle.SOLID,
    useThemeColorForNumbers: Boolean = false,
    useLandscapePosters: Boolean = false,
    content: @Composable () -> Unit
) {
    val numberText = number.toString()
    val numberFontSize = androidx.compose.ui.unit.TextUnit(
        if (useLandscapePosters) cardHeight.value * 0.80f else cardHeight.value * 0.55f,
        androidx.compose.ui.unit.TextUnitType.Sp
    )
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current

    val leagueSpartanTypeface = remember(context) {
        androidx.core.content.res.ResourcesCompat.getFont(context, com.nuvio.tv.R.font.jost_variable)
            ?: android.graphics.Typeface.DEFAULT_BOLD
    }
    val themeColor = NuvioColors.Secondary
    val numberColor = remember(useThemeColorForNumbers, themeColor) {
        if (useThemeColorForNumbers) {
            android.graphics.Color.argb(
                255,
                (themeColor.red * 255 * 0.40f + 8).toInt(),
                (themeColor.green * 255 * 0.40f + 8).toInt(),
                (themeColor.blue * 255 * 0.40f + 8).toInt()
            )
        } else {
            android.graphics.Color.argb(255, 136, 136, 136)
        }
    }

    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val baseStyle = remember(numberFontSize) {
        androidx.compose.ui.text.TextStyle(
            fontSize = numberFontSize,
            fontWeight = androidx.compose.ui.text.font.FontWeight.W500,
            color = androidx.compose.ui.graphics.Color(0xFF888888)
        )
    }

    val referenceText = when {
        number >= 100 -> "888"
        number >= 10 -> "88"
        else -> "8"
    }
    val measured = remember(referenceText, numberFontSize) { textMeasurer.measure(referenceText, baseStyle) }
    val textWidthDp = if (preMeasuredTextWidth > 0.dp) preMeasuredTextWidth else with(density) { measured.size.width.toDp() }
    val textHeightDp = with(density) { measured.size.height.toDp() }

    val overlapDp = if (useLandscapePosters) cardWidth * 0.06f else cardWidth * 0.10f
    val offsetX = -(textWidthDp - overlapDp)

    Box(modifier = androidx.compose.ui.Modifier.padding(start = extraStartPadding)) {
        Box(
            modifier = androidx.compose.ui.Modifier
                .height(textHeightDp)
                .align(androidx.compose.ui.Alignment.BottomStart)
                .offset(x = offsetX, y = if (useLandscapePosters) cardHeight * 0.14f else cardHeight * 0.105f)
                .zIndex(-1f)
                .layout { measurable, constraints ->
                    val textWidthPx = with(density) { textWidthDp.roundToPx() }
                    val placeable = measurable.measure(constraints.copy(minWidth = textWidthPx, maxWidth = textWidthPx))
                    layout(0, placeable.height) {
                        placeable.place(0, 0)
                    }
                }
                .drawWithCache {
                    val paint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        textSize = numberFontSize.value * density.density
                        typeface = leagueSpartanTypeface
                        color = numberColor
                        style = android.graphics.Paint.Style.FILL
                    }
                    onDrawBehind {
                        val textW = paint.measureText(numberText)
                        val oneAdjust = if (numberText == "1") 5f * density.density else 0f
                        val x = size.width - textW + oneAdjust
                        if (numberStyle == NumberStyle.OUTLINE) {
                            // Outline style: thin stroke with black fill inside
                            val strokePaint = android.graphics.Paint().apply {
                                isAntiAlias = true
                                textSize = numberFontSize.value * density.density
                                typeface = leagueSpartanTypeface
                                color = numberColor
                                style = android.graphics.Paint.Style.STROKE
                                strokeWidth = 1.5f * density.density
                                strokeJoin = android.graphics.Paint.Join.ROUND
                                strokeCap = android.graphics.Paint.Cap.ROUND
                            }
                            val fillPaint = android.graphics.Paint().apply {
                                isAntiAlias = true
                                textSize = numberFontSize.value * density.density
                                typeface = leagueSpartanTypeface
                                color = android.graphics.Color.argb(255, 0, 0, 0)
                                style = android.graphics.Paint.Style.FILL
                            }
                            drawContext.canvas.nativeCanvas.drawText(numberText, x, size.height * 0.88f, strokePaint)
                            drawContext.canvas.nativeCanvas.drawText(numberText, x, size.height * 0.88f, fillPaint)
                        } else {
                            drawContext.canvas.nativeCanvas.drawText(numberText, x, size.height * 0.88f, paint)
                        }
                    }
                }
        ) {}
        Box(modifier = androidx.compose.ui.Modifier.zIndex(1f)) {
            content()
        }
    }
}

private fun shouldResetBackdropTimer(key: Key): Boolean {
    return when (key) {
        Key.DirectionUp,
        Key.DirectionDown,
        Key.DirectionLeft,
        Key.DirectionRight,
        Key.DirectionCenter,
        Key.Enter,
        Key.NumPadEnter,
        Key.Back -> true
        else -> false
    }
}

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}







