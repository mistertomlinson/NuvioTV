@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nuvio.tv.ui.screens.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.nuvio.tv.LocalNoBackdropImage
import com.nuvio.tv.ui.theme.NuvioColors
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

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
    val upPressedModifier = if (onUpPressed != null) Modifier.onPreviewKeyEvent { event ->
        if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
            event.key == androidx.compose.ui.input.key.Key.DirectionUp) {
            onUpPressed()
            true
        } else false
    } else Modifier
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

    ModernCarouselCard(
        item = item,
        useLandscapePosters = useLandscapePosters,
        showLabels = showLabels,
        cardCornerRadius = posterCardCornerRadius,
        cardWidth = modernCatalogCardWidth,
        cardHeight = modernCatalogCardHeight,
        focusedPosterBackdropExpandEnabled = effectiveExpandEnabled,
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ModernRowSection(
    row: HeroCarouselRow,
    rowTitleBottom: Dp,
    defaultBringIntoViewSpec: BringIntoViewSpec,
    focusStateCatalogRowScrollStates: Map<String, Int>,
    uiCaches: ModernHomeUiCaches,
    pendingRowFocusKey: String?,
    pendingRowFocusIndex: Int?,
    pendingRowFocusNonce: Int,
    onPendingRowFocusCleared: () -> Unit,
    onRowItemFocused: (String, Int, Boolean) -> Unit,
    useLandscapePosters: Boolean,
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
    onRequestCarouselFocus: () -> Unit = {}
) {
    val focusedItemByRow = uiCaches.focusedItemByRow
    val itemFocusRequesters = uiCaches.itemFocusRequesters
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

        val rowListState = rowListStates.getOrPut(row.key) {
            LazyListState(
                firstVisibleItemIndex = focusStateCatalogRowScrollStates[row.key] ?: 0
            )
        }
        var rowLeftReleasedAtEdge by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
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

        LaunchedEffect(row.key, pendingRowFocusNonce) {
            if (pendingRowFocusKey != row.key) return@LaunchedEffect
            val targetIndex = (pendingRowFocusIndex ?: 0)
                .coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
            val targetItemKey = row.items.getOrNull(targetIndex)?.key ?: return@LaunchedEffect
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
                    runCatching { rowListState.scrollToItem(targetIndex) }
                    didScrollToTarget = true
                }
                withFrameNanos { }
            }
            if (!didFocus) {
                val fallbackIndex = rowListState.firstVisibleItemIndex
                    .coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
                val fallbackItemKey = row.items.getOrNull(fallbackIndex)?.key
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

        // Trigger one card early: when the 2nd-to-last item is visible, the padding
        // is already at full size before focus ever reaches the last card.
        val isNearRowEndVisible by remember(rowListState) {
            derivedStateOf {
                val info = rowListState.layoutInfo
                val total = info.totalItemsCount
                if (total == 0) false
                else info.visibleItemsInfo.any { it.index >= total - 2 }
            }
        }

        // End padding must be large enough to give the last card a full scroll travel:
        // at minimum cardWidth + gap (12dp) so the LazyRow scrolls the same distance
        // for the last card as for any other. Also keep enough room for the expanded
        // card not to clip the viewport edge.
        val fullTravelPadding = modernCatalogCardWidth + 12.dp
        val endPaddingTarget = when {
            !canExpand -> rowStartPadding
            isNearRowEndVisible -> maxOf(expansionDelta + 8.dp, fullTravelPadding)
            else -> expansionDelta + 20.dp
        }
        val animatedEndPadding by animateDpAsState(
            targetValue = endPaddingTarget,
            animationSpec = tween(durationMillis = 200),
            label = "rowEndPadding_${row.key}"
        )

        val useCenteredScroll = effectiveExpandEnabled && trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD
        val horizontalBringIntoViewSpec = remember(density, defaultBringIntoViewSpec, useCenteredScroll) {
            val parentStartOffsetPx = with(density) { rowStartPadding.roundToPx() }
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            object : BringIntoViewSpec {
                override val scrollAnimationSpec: AnimationSpec<Float>
                    get() = defaultBringIntoViewSpec.scrollAnimationSpec

                override fun calculateScrollDistance(
                    offset: Float,
                    size: Float,
                    containerSize: Float
                ): Float {
                    val childSize = abs(size)
                    val targetForLeadingEdge = if (useCenteredScroll) {
                        val centeredTarget = (containerSize - childSize) / 2f
                        centeredTarget.coerceAtLeast(parentStartOffsetPx.toFloat())
                    } else {
                        val childSmallerThanParent = childSize <= containerSize
                        val initialTarget = parentStartOffsetPx.toFloat()
                        val spaceAvailable = containerSize - initialTarget
                        if (childSmallerThanParent && spaceAvailable < childSize) {
                            containerSize - childSize
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
        val numberedRowSpacing = if (isNumbered) (modernCatalogCardWidth * 0.64f).coerceAtLeast(12.dp) else 12.dp

        // Pre-measure number widths once at row level for stable sizing across all items
        val numberFontSizeRow = androidx.compose.ui.unit.TextUnit(modernCatalogCardHeight.value * 0.55f, androidx.compose.ui.unit.TextUnitType.Sp)
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

        CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalBringIntoViewSpec) {
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
                            val isAtStart = rowListState.firstVisibleItemIndex == 0 &&
                                rowListState.firstVisibleItemScrollOffset == 0
                            if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyUp) {
                                if (isAtStart) rowLeftReleasedAtEdge = true
                                false
                            } else if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                                if (isAtStart && rowLeftReleasedAtEdge) {
                                    rowLeftReleasedAtEdge = false
                                    false // let it bubble to open sidebar
                                } else {
                                    if (!isAtStart) rowLeftReleasedAtEdge = false
                                    false // let LazyRow scroll normally
                                }
                            } else false
                        } else false
                    }
                    .focusRestorer(
                        run {
                            val rememberedIndex = (focusedItemByRow[row.key] ?: 0)
                                .coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
                            val fallbackIndex = rowListState.firstVisibleItemIndex
                                .coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
                            val restoreIndex = if (rememberedIndex in row.items.indices) {
                                rememberedIndex
                            } else {
                                fallbackIndex
                            }
                            val visibleIndices = rowListState.layoutInfo.visibleItemsInfo.map { it.index }.toSet()
                            val safeIndex = if (restoreIndex in visibleIndices) restoreIndex else
                                visibleIndices.minByOrNull { kotlin.math.abs(it - restoreIndex) } ?: fallbackIndex
                            val itemKey = row.items.getOrNull(safeIndex)?.key ?: row.items.first().key
                            itemFocusRequesters[row.key]?.get(itemKey) ?: FocusRequester.Default
                        }
                    ),
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
                            val isWatched = remember(item.metaPreview?.id) {
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
                                    cardNumber >= 100 -> modernCatalogCardWidth * 0.81f
                                    cardNumber >= 10 -> modernCatalogCardWidth * 0.41f
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
                                    useThemeColorForNumbers = useThemeColorForNumbers
                                ) {
                                    ModernCatalogRowItem(
                                        item = item,
                                        payload = payload,
                                        requester = requester,
                                        useLandscapePosters = useLandscapePosters,
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
                                        onUpPressed = if (isFirstRow) onRequestCarouselFocus else null
                                    )
                                }
                            } else {
                                ModernCatalogRowItem(
                                    item = item,
                                    payload = payload,
                                    requester = requester,
                                    useLandscapePosters = useLandscapePosters,
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
                                    onUpPressed = if (isFirstRow) onRequestCarouselFocus else null
                                )
                            }
                        }
                    }
                }

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
    val animatedCardWidth by if (focusedPosterBackdropExpandEnabled) {
        animateDpAsState(
            targetValue = targetCardWidth,
            label = "modernCardWidth"
        )
    } else {
        rememberUpdatedState(cardWidth)
    }

    // In noBackdropImage mode, NEVER switch to the backdrop image.
    // The poster stays as-is; covered by the black overlay then the trailer.
    val imageUrl = remember(noBackdropImage, playTrailerInExpandedCard, effectiveIsExpanded, item.imageUrl, item.heroPreview.backdrop, item.heroPreview.poster) {
        if (noBackdropImage && playTrailerInExpandedCard) {
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
    val logoModel = remember(context, item.heroPreview.logo, maxLogoWidthPx, logoHeightPx) {
        item.heroPreview.logo?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(true)
                .size(width = maxLogoWidthPx, height = logoHeightPx)
                .build()
        }
    }
    var landscapeLogoLoadFailed by remember(item.heroPreview.logo) { mutableStateOf(false) }
    // shouldPlayTrailerInCard: original = playTrailerInExpandedCard only.
    // playTrailerInExpandedCard already includes !isSidebarExpanded so trailer stops instantly.
    val shouldPlayTrailerInCard = playTrailerInExpandedCard && !trailerPreviewUrl.isNullOrBlank()
    val hasImage = !imageUrl.isNullOrBlank()
    val hasLandscapeLogo =
        useLandscapePosters &&
            !item.heroPreview.logo.isNullOrBlank() &&
            !landscapeLogoLoadFailed
    var isFocused by remember { mutableStateOf(false) }
    var longPressTriggered by remember { mutableStateOf(false) }
    val watchedIconEndPadding by animateDpAsState(
        targetValue = if (isFocused) 16.dp else 8.dp,
        animationSpec = tween(durationMillis = 180),
        label = "modernCardWatchedIconEndPadding"
    )
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
                    } else {
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
                            .fillMaxWidth(0.62f)
                            .height(cardHeight * 0.34f)
                            .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart
                    )
                } else if (useLandscapePosters) {
                    Text(
                        text = item.title,
                        style = titleStyle,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(0.62f)
                            .padding(start = 10.dp, end = 10.dp, bottom = 12.dp)
                    )
                }

                if (isWatched) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = watchedIconEndPadding, top = 8.dp)
                            .zIndex(2f),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(24.dp)
                        )
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = stringResource(R.string.episodes_cd_watched),
                            tint = Color.White,
                            modifier = Modifier.size(21.dp)
                        )
                    }
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
    content: @Composable () -> Unit
) {
    val numberText = number.toString()
    val numberFontSize = androidx.compose.ui.unit.TextUnit(cardHeight.value * 0.55f, androidx.compose.ui.unit.TextUnitType.Sp)
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current

    val leagueSpartanTypeface = remember(context) {
        androidx.core.content.res.ResourcesCompat.getFont(context, com.nuvio.tv.R.font.jost_variable)
            ?: android.graphics.Typeface.DEFAULT_BOLD
    }
    val themeColor = NuvioColors.Secondary
    val numberColor = if (useThemeColorForNumbers) {
        android.graphics.Color.argb(
            255,
            (themeColor.red * 255 * 0.40f + 8).toInt(),
            (themeColor.green * 255 * 0.40f + 8).toInt(),
            (themeColor.blue * 255 * 0.40f + 8).toInt()
        )
    } else {
        android.graphics.Color.argb(255, 136, 136, 136)
    }

    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val baseStyle = androidx.compose.ui.text.TextStyle(
        fontSize = numberFontSize,
        fontWeight = androidx.compose.ui.text.font.FontWeight.W500,
        color = androidx.compose.ui.graphics.Color(0xFF888888)
    )

    val referenceText = when {
        number >= 100 -> "888"
        number >= 10 -> "88"
        else -> "8"
    }
    val measured = remember(referenceText, numberFontSize) { textMeasurer.measure(referenceText, baseStyle) }
    val textWidthDp = if (preMeasuredTextWidth > 0.dp) preMeasuredTextWidth else with(density) { measured.size.width.toDp() }
    val textHeightDp = with(density) { measured.size.height.toDp() }

    val overlapDp = cardWidth * 0.10f
    val offsetX = -(textWidthDp - overlapDp)

    Box(modifier = androidx.compose.ui.Modifier.padding(start = extraStartPadding)) {
        Box(
            modifier = androidx.compose.ui.Modifier
                .height(textHeightDp)
                .align(androidx.compose.ui.Alignment.BottomStart)
                .offset(x = offsetX, y = cardHeight * 0.105f)
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
                        val x = size.width - textW
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







