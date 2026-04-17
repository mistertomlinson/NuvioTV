@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.metrics.performance.PerformanceMetricsState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nuvio.tv.R
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.ContinueWatchingCard
import com.nuvio.tv.ui.components.ContinueWatchingOptionsDialog
import com.nuvio.tv.ui.components.MonochromePosterPlaceholder
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.LocalAppInForeground
import com.nuvio.tv.LocalSidebarExpanded
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.LocalCarouselFocusRequester
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.delay
import android.view.KeyEvent as AndroidKeyEvent
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.distinctUntilChanged

private const val MODERN_HERO_RAPID_NAV_THRESHOLD_MS = 130L
private const val MODERN_HERO_RAPID_NAV_SETTLE_MS = 170L
private const val KEY_REPEAT_THROTTLE_MS = 140L

@Composable
fun ModernHomeContent(
    uiState: HomeUiState,
    selectedPlatformId: String = "home",
    aggregatePlatformsEnabled: Boolean = true,
    showAllCatalogsOnHome: Boolean = false,
    focusState: HomeScreenFocusState,
    enrichingItemId: String? = null,
    trailerPreviewUrls: Map<String, String>,
    trailerPreviewAudioUrls: Map<String, String>,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit = {},
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit = {},
    showContinueWatchingManualPlayOption: Boolean = false,
    onRequestTrailerPreview: (String, String, String?, String) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit,
    onRemoveContinueWatching: (String, Int?, Int?, Boolean) -> Unit,
    numberedCatalogKeys: Set<String> = emptySet(),
    outlineNumberedCatalogKeys: Set<String> = emptySet(),
    useThemeColorForNumbers: Boolean = false,
    isCatalogItemWatched: (MetaPreview) -> Boolean = { false },
    onCatalogItemLongPress: (MetaPreview, String) -> Unit = { _, _ -> },
    onItemFocus: (MetaPreview) -> Unit = {},
    onPreloadAdjacentItem: (MetaPreview) -> Unit = {},
    onSaveFocusState: (Int, Int, Int, Int, Map<String, Int>, String?, String) -> Unit,
    onAtTopChanged: (Boolean) -> Unit = {},
    carouselGradientAlpha: Float = 0f,
    onCarouselOpenRequested: () -> Unit = {},
    isCarouselFocused: Boolean = false,
    onHeroTrailerPlayingChanged: (Boolean) -> Unit = {}
) {
    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
    val isSidebarExpanded = LocalSidebarExpanded.current
    val useLandscapePosters = uiState.modernLandscapePostersEnabled
    val showCatalogTypeSuffixInModern = uiState.catalogTypeSuffixEnabled
    val isLandscapeModern = useLandscapePosters
    val expandControlAvailable = !isLandscapeModern
    val trailerPlaybackTarget = uiState.focusedPosterBackdropTrailerPlaybackTarget
    val effectiveAutoplayEnabled =
        uiState.focusedPosterBackdropTrailerEnabled &&
            (isLandscapeModern || uiState.focusedPosterBackdropExpandEnabled)
    val landscapeExpandedCardMode =
        isLandscapeModern &&
            effectiveAutoplayEnabled &&
            trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD
    val effectiveExpandEnabled =
        (uiState.focusedPosterBackdropExpandEnabled && expandControlAvailable) ||
            landscapeExpandedCardMode
    val shouldActivateFocusedPosterFlow =
        effectiveExpandEnabled ||
            (effectiveAutoplayEnabled &&
                trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.HERO_MEDIA)
    val visibleCatalogRows = remember(uiState.catalogRows, selectedPlatformId) {
        uiState.catalogRows.filter { it.items.isNotEmpty() }.let { rows ->
            if (!aggregatePlatformsEnabled || selectedPlatformId == "home") {
                if (aggregatePlatformsEnabled && !showAllCatalogsOnHome) rows.filter { inferPlatformId(it.catalogName) == null }
                else rows
            } else {
                rows.filter { inferPlatformId(it.catalogName) == selectedPlatformId }
            }
        }
    }
    val strContinueWatching = stringResource(R.string.continue_watching)
    val strAirsDate = stringResource(R.string.cw_airs_date)
    val strUpcoming = stringResource(R.string.cw_upcoming)
    val strTypeMovie = stringResource(R.string.type_movie)
    val strTypeSeries = stringResource(R.string.type_series)
    val rowBuildCache = remember { ModernCarouselRowBuildCache() }
    val context = LocalContext.current
    val carouselRows = remember(
        uiState.continueWatchingItems,
        visibleCatalogRows,
        useLandscapePosters,
        showCatalogTypeSuffixInModern,
        strTypeMovie,
        strTypeSeries,
        numberedCatalogKeys,
        outlineNumberedCatalogKeys
    ) {
        buildList {
            val activeCatalogKeys = LinkedHashSet<String>(visibleCatalogRows.size)
            if (uiState.continueWatchingItems.isNotEmpty() && selectedPlatformId == "home") {
                val reuseContinueWatchingRow =
                    rowBuildCache.continueWatchingRow != null &&
                        rowBuildCache.continueWatchingItems == uiState.continueWatchingItems &&
                        rowBuildCache.continueWatchingTitle == strContinueWatching &&
                        rowBuildCache.continueWatchingAirsDateTemplate == strAirsDate &&
                        rowBuildCache.continueWatchingUpcomingLabel == strUpcoming &&
                        rowBuildCache.continueWatchingUseLandscapePosters == useLandscapePosters
                val continueWatchingRow = if (reuseContinueWatchingRow) {
                    checkNotNull(rowBuildCache.continueWatchingRow)
                } else {
                    HeroCarouselRow(
                        key = "continue_watching",
                        title = strContinueWatching,
                        globalRowIndex = -1,
                        items = uiState.continueWatchingItems.map { item ->
                            buildContinueWatchingItem(
                                item = item,
                                useLandscapePosters = useLandscapePosters,
                                airsDateTemplate = strAirsDate,
                                upcomingLabel = strUpcoming,
                                context = context
                            )
                        }
                    )
                }
                rowBuildCache.continueWatchingItems = uiState.continueWatchingItems
                rowBuildCache.continueWatchingTitle = strContinueWatching
                rowBuildCache.continueWatchingAirsDateTemplate = strAirsDate
                rowBuildCache.continueWatchingUpcomingLabel = strUpcoming
                rowBuildCache.continueWatchingUseLandscapePosters = useLandscapePosters
                rowBuildCache.continueWatchingRow = continueWatchingRow
                add(continueWatchingRow)
            } else {
                rowBuildCache.continueWatchingItems = emptyList()
                rowBuildCache.continueWatchingRow = null
            }

            visibleCatalogRows.forEachIndexed { index, row ->
                val rowKey = catalogRowKey(row)
                activeCatalogKeys += rowKey
                val cached = rowBuildCache.catalogRows[rowKey]
                val cachedNumberStyle = when {
                    rowKey in outlineNumberedCatalogKeys -> NumberStyle.OUTLINE
                    rowKey in numberedCatalogKeys -> NumberStyle.SOLID
                    else -> NumberStyle.OFF
                }
                val canReuseMappedRow =
                    cached != null &&
                        cached.source == row &&
                        cached.useLandscapePosters == useLandscapePosters &&
                        cached.showCatalogTypeSuffix == showCatalogTypeSuffixInModern &&
                        cached.mappedRow.numberStyle == cachedNumberStyle

                val mappedRow = if (canReuseMappedRow) {
                    val cachedMappedRow = checkNotNull(cached).mappedRow
                    if (cachedMappedRow.globalRowIndex == index) {
                        cachedMappedRow
                    } else {
                        cachedMappedRow.copy(globalRowIndex = index)
                    }
                } else {
                    val rowItemOccurrenceCounts = mutableMapOf<String, Int>()
                    val rowItemCache = rowBuildCache.catalogItemCache.getOrPut(rowKey) { mutableMapOf() }
                    HeroCarouselRow(
                        key = rowKey,
                        title = catalogRowTitle(
                            row = row,
                            showCatalogTypeSuffix = showCatalogTypeSuffixInModern,
                            strTypeMovie = strTypeMovie,
                            strTypeSeries = strTypeSeries
                        ),
                        globalRowIndex = index,
                        catalogId = row.catalogId,
                        addonId = row.addonId,
                        apiType = row.apiType,
                        supportsSkip = row.supportsSkip,
                        hasMore = row.hasMore,
                        isLoading = row.isLoading,
                        numberStyle = when {
                            rowKey in outlineNumberedCatalogKeys -> NumberStyle.OUTLINE
                            rowKey in numberedCatalogKeys -> NumberStyle.SOLID
                            else -> NumberStyle.OFF
                        },
                        items = row.items.map { item ->
                            val occurrence = rowItemOccurrenceCounts.getOrDefault(item.id, 0)
                            rowItemOccurrenceCounts[item.id] = occurrence + 1
                            val cacheKey = "${item.id}_$occurrence"
                            val cachedItem = rowItemCache[cacheKey]
                            if (cachedItem != null &&
                                cachedItem.source == item &&
                                cachedItem.useLandscapePosters == useLandscapePosters
                            ) {
                                cachedItem.carouselItem
                            } else {
                                val built = buildCatalogItem(
                                    item = item,
                                    row = row,
                                    useLandscapePosters = useLandscapePosters,
                                    occurrence = occurrence,
                                    strTypeMovie = strTypeMovie,
                                    strTypeSeries = strTypeSeries
                                )
                                rowItemCache[cacheKey] = CachedCarouselItem(
                                    source = item,
                                    useLandscapePosters = useLandscapePosters,
                                    carouselItem = built
                                )
                                built
                            }
                        }
                    )
                }

                rowBuildCache.catalogRows[rowKey] = ModernCatalogRowBuildCacheEntry(
                    source = row,
                    useLandscapePosters = useLandscapePosters,
                    showCatalogTypeSuffix = showCatalogTypeSuffixInModern,
                    mappedRow = mappedRow
                )
                add(mappedRow)
            }
            rowBuildCache.catalogRows.keys.retainAll(activeCatalogKeys)
            rowBuildCache.catalogItemCache.keys.retainAll(activeCatalogKeys)
        }
    }

    if (carouselRows.isEmpty()) return
    val carouselLookups = remember(carouselRows) {
        val rowIndexByKey = LinkedHashMap<String, Int>(carouselRows.size)
        val rowByKey = LinkedHashMap<String, HeroCarouselRow>(carouselRows.size)
        val activeRowKeys = LinkedHashSet<String>(carouselRows.size)
        val activeItemKeysByRow = LinkedHashMap<String, Set<String>>(carouselRows.size)
        val activeCatalogItemIds = LinkedHashSet<String>()

        carouselRows.forEachIndexed { index, row ->
            rowIndexByKey[row.key] = index
            rowByKey[row.key] = row
            activeRowKeys += row.key

            val itemKeys = LinkedHashSet<String>(row.items.size)
            row.items.forEach { item ->
                itemKeys += item.key
                val payload = item.payload
                if (payload is ModernPayload.Catalog) {
                    activeCatalogItemIds += payload.itemId
                }
            }
            activeItemKeysByRow[row.key] = itemKeys
        }

        CarouselRowLookups(
            rowIndexByKey = rowIndexByKey,
            rowByKey = rowByKey,
            activeRowKeys = activeRowKeys,
            activeItemKeysByRow = activeItemKeysByRow,
            activeCatalogItemIds = activeCatalogItemIds
        )
    }
    val rowIndexByKey = carouselLookups.rowIndexByKey
    val rowByKey = carouselLookups.rowByKey
    val activeRowKeys = carouselLookups.activeRowKeys
    val activeItemKeysByRow = carouselLookups.activeItemKeysByRow
    val activeCatalogItemIds = carouselLookups.activeCatalogItemIds
    val verticalRowListState = rememberLazyListState(
        initialFirstVisibleItemIndex = focusState.verticalScrollIndex,
        initialFirstVisibleItemScrollOffset = focusState.verticalScrollOffset
    )
    val isVerticalRowsScrolling by remember(verticalRowListState) {
        derivedStateOf { verticalRowListState.isScrollInProgress }
    }
    // One-way latch: once CW loads it is remembered forever
    var cwHasEverLoaded by remember { mutableStateOf(false) }
    if (uiState.continueWatchingItems.isNotEmpty()) cwHasEverLoaded = true
    val cwHasEverLoadedRef by rememberUpdatedState(cwHasEverLoaded)
    val currentCarouselRows by rememberUpdatedState(carouselRows)
    val onAtTopChangedUpdated by rememberUpdatedState(onAtTopChanged)


    // Tag JankStats with key UI states so jank reports are actionable.
    val metricsHolder = PerformanceMetricsState.getHolderForHierarchy(LocalView.current)
    LaunchedEffect(isVerticalRowsScrolling) {
        metricsHolder.state?.putState("HomeScrolling", isVerticalRowsScrolling.toString())
    }
    LaunchedEffect(enrichingItemId) {
        metricsHolder.state?.putState("HeroEnriching", (enrichingItemId != null).toString())
    }

    val uiCaches = remember { ModernHomeUiCaches() }
    val focusedItemByRow = uiCaches.focusedItemByRow
    val itemFocusRequesters = uiCaches.itemFocusRequesters
    val rowListStates = uiCaches.rowListStates
    val isAnyRowScrolling by remember(rowListStates) {
        derivedStateOf { rowListStates.values.any { it.isScrollInProgress } }
    }
    val loadMoreRequestedTotals = uiCaches.loadMoreRequestedTotals
    // Holder for hot-path focus tracking — lambdas read through reference, no stale closure
    val focusHolder = remember {
        object {
            var activeRowKey: String? = null
            var activeItemIndex: Int = 0
        }
    }
    var activeRowKey by remember { mutableStateOf<String?>(null) }

    // Show icons when focused row is the first row in the list.
    // Before CW loads: first row triggers icons.
    // After CW loads: only CW row (always index 0) triggers icons.
    LaunchedEffect(verticalRowListState) {
        snapshotFlow { activeRowKey + "|" + cwHasEverLoaded.toString() }
            .collect { snapshot ->
                val focusedKey = snapshot.substringBefore("|").let { if (it == "null") null else it }
                val cwLoaded = snapshot.substringAfter("|") == "true"
                val firstRowKey = currentCarouselRows.firstOrNull()?.key
                val secondRowKey = currentCarouselRows.getOrNull(1)?.key
                val showIcons = focusedKey == firstRowKey ||
                    (focusedKey == secondRowKey && !cwLoaded)
                onAtTopChangedUpdated(showIcons)
            }
    }
    var activeItemIndex by remember { mutableIntStateOf(0) }
    var pendingRowFocusKey by remember { mutableStateOf<String?>(null) }
    var pendingRowFocusIndex by remember { mutableStateOf<Int?>(null) }
    var pendingRowFocusNonce by remember { mutableIntStateOf(0) }
    var heroItem by remember { mutableStateOf<HeroPreview?>(null) }
    var heroItemRowKey by remember { mutableStateOf<String?>(null) }
    var frozenHeroItem by remember { mutableStateOf<HeroPreview?>(null) }
    var frozenHeroItemRowKey by remember { mutableStateOf<String?>(null) }
    var isFastScrolling by remember { mutableStateOf(false) }
    val heroTransitioningRef = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var restoredFromSavedState by remember { mutableStateOf(false) }
    var lastRestoredRowKey by remember { mutableStateOf<String?>(null) }
    if (focusState.hasSavedFocus && focusState.focusedRowKey != lastRestoredRowKey) {
        restoredFromSavedState = false
    }
    var optionsItem by remember { mutableStateOf<ContinueWatchingItem?>(null) }
    val lastFocusedContinueWatchingIndexRef = remember { java.util.concurrent.atomic.AtomicInteger(-1) }
    val lastHeroNavigationAtMsRef = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    val heroFocusSettleDelayMsRef = remember { java.util.concurrent.atomic.AtomicLong(MODERN_HERO_FOCUS_DEBOUNCE_MS) }
        val lastKeyRepeatTimeRef = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    val isFastScrollingRef = remember { kotlinx.coroutines.flow.MutableStateFlow(false) }
    val lastKeyUpTimeRef = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    var focusedCatalogSelection by remember { mutableStateOf<FocusedCatalogSelection?>(null) }
    var lastRequestedTrailerFocusKey by remember { mutableStateOf<String?>(null) }
    var expandedCatalogFocusKey by remember { mutableStateOf<String?>(null) }
    var expansionInteractionNonce by remember { mutableIntStateOf(0) }

    // Collapse expanded card and stop trailer when app goes to background.
    // Delay the state reset slightly so the app has already backgrounded before
    // any recomposition occurs — prevents visible collapse animation on home press.
    val appInForeground = LocalAppInForeground.current
    LaunchedEffect(appInForeground) {
        if (!appInForeground) {
            delay(500)
            expandedCatalogFocusKey = null
            focusedCatalogSelection = null
            lastRequestedTrailerFocusKey = null
        }
    }

    LaunchedEffect(
        focusedCatalogSelection?.focusKey,
        expansionInteractionNonce,
        shouldActivateFocusedPosterFlow,
        trailerPlaybackTarget,
        uiState.focusedPosterBackdropExpandDelaySeconds,
        isVerticalRowsScrolling
    ) {
        expandedCatalogFocusKey = null
        if (!shouldActivateFocusedPosterFlow) return@LaunchedEffect
        if (isVerticalRowsScrolling) return@LaunchedEffect
        val selection = focusedCatalogSelection ?: return@LaunchedEffect
        delay(uiState.focusedPosterBackdropExpandDelaySeconds.coerceAtLeast(0) * 1000L)
        if (shouldActivateFocusedPosterFlow &&
            !isVerticalRowsScrolling &&
            focusedCatalogSelection?.focusKey == selection.focusKey
        ) {
            expandedCatalogFocusKey = selection.focusKey
        }
    }

    LaunchedEffect(
        focusedCatalogSelection?.focusKey,
        effectiveAutoplayEnabled,
        isVerticalRowsScrolling
    ) {
        if (!effectiveAutoplayEnabled) {
            lastRequestedTrailerFocusKey = null
            return@LaunchedEffect
        }
        if (isVerticalRowsScrolling) {
            return@LaunchedEffect
        }
        val selection = focusedCatalogSelection ?: run {
            lastRequestedTrailerFocusKey = null
            return@LaunchedEffect
        }
        if (selection.focusKey == lastRequestedTrailerFocusKey) {
            return@LaunchedEffect
        }
        onRequestTrailerPreview(
            selection.payload.itemId,
            selection.payload.trailerTitle,
            selection.payload.trailerReleaseInfo,
            selection.payload.trailerApiType
        )
        lastRequestedTrailerFocusKey = selection.focusKey
    }

    LaunchedEffect(carouselRows, focusState.hasSavedFocus, focusState.focusedRowIndex, focusState.focusedItemIndex, focusState.focusedRowKey) {
        focusedItemByRow.keys.retainAll(activeRowKeys)
        itemFocusRequesters.keys.retainAll(activeRowKeys)
        rowListStates.keys.retainAll(activeRowKeys)
        loadMoreRequestedTotals.keys.retainAll(activeRowKeys)
        carouselRows.forEach { row ->
            val rowRequesters = itemFocusRequesters[row.key] ?: return@forEach
            val allowedKeys = activeItemKeysByRow[row.key] ?: emptySet()
            rowRequesters.keys.retainAll(allowedKeys)
        }
        if (focusedCatalogSelection?.payload?.itemId !in activeCatalogItemIds) {
            focusedCatalogSelection = null
            expandedCatalogFocusKey = null
        }

        carouselRows.forEach { row ->
            if (row.items.isNotEmpty() && row.key !in focusedItemByRow) {
                focusedItemByRow[row.key] = 0
            }
        }

        android.util.Log.d("NuvioFocus", "RESTORE CHECK: hasSavedFocus=${focusState.hasSavedFocus} restoredFromSavedState=$restoredFromSavedState focusedRowKey=${focusState.focusedRowKey} platform=${focusState.selectedPlatformId}")
        if (!restoredFromSavedState && focusState.hasSavedFocus) {
            val savedRowKey = when {
                focusState.focusedRowKey != null -> focusState.focusedRowKey
                focusState.focusedRowIndex == -1 && uiState.continueWatchingItems.isNotEmpty() -> "continue_watching"
                focusState.focusedRowIndex >= 0 -> visibleCatalogRows.getOrNull(focusState.focusedRowIndex)?.let { catalogRowKey(it) }
                else -> null
            }

            android.util.Log.d("NuvioFocus", "RESTORING: savedRowKey=$savedRowKey found=${carouselRows.any { it.key == savedRowKey }} carouselSize=${carouselRows.size} keys=${carouselRows.map { it.key }}")
            val resolvedRow = carouselRows.firstOrNull { it.key == savedRowKey } ?: carouselRows.first()
            val resolvedIndex = focusState.focusedItemIndex
                .coerceAtLeast(0)
                .coerceAtMost((resolvedRow.items.size - 1).coerceAtLeast(0))

            focusHolder.activeRowKey = resolvedRow.key
            focusHolder.activeItemIndex = resolvedIndex
            activeRowKey = resolvedRow.key
            activeItemIndex = resolvedIndex
            focusedItemByRow[resolvedRow.key] = resolvedIndex
            heroItem = resolvedRow.items.getOrNull(resolvedIndex)?.heroPreview
                ?: resolvedRow.items.firstOrNull()?.heroPreview
            heroItemRowKey = resolvedRow.key
            pendingRowFocusKey = resolvedRow.key
            pendingRowFocusIndex = resolvedIndex
            pendingRowFocusNonce++
            restoredFromSavedState = true
            lastRestoredRowKey = focusState.focusedRowKey
            return@LaunchedEffect
        }

        val hadActiveRow = focusHolder.activeRowKey != null
        val existingActive = focusHolder.activeRowKey?.let { key -> carouselRows.firstOrNull { it.key == key } }
        val resolvedActive = existingActive ?: carouselRows.first()
        val resolvedIndex = focusedItemByRow[resolvedActive.key]
            ?.coerceIn(0, (resolvedActive.items.size - 1).coerceAtLeast(0))
            ?: 0
        focusHolder.activeRowKey = resolvedActive.key
        focusHolder.activeItemIndex = resolvedIndex
        activeRowKey = resolvedActive.key
        activeItemIndex = resolvedIndex
        focusedItemByRow[resolvedActive.key] = resolvedIndex
        heroItem = resolvedActive.items.getOrNull(resolvedIndex)?.heroPreview
            ?: resolvedActive.items.firstOrNull()?.heroPreview
        heroItemRowKey = resolvedActive.key
        if (!focusState.hasSavedFocus && (!hadActiveRow || existingActive == null) && !isCarouselFocused) {
            pendingRowFocusKey = resolvedActive.key
            pendingRowFocusIndex = resolvedIndex
            pendingRowFocusNonce++
        }
    }

    LaunchedEffect(focusState.verticalScrollIndex, focusState.verticalScrollOffset) {
        val targetIndex = focusState.verticalScrollIndex
        val targetOffset = focusState.verticalScrollOffset
        if (verticalRowListState.firstVisibleItemIndex == targetIndex &&
            verticalRowListState.firstVisibleItemScrollOffset == targetOffset
        ) {
            return@LaunchedEffect
        }
        if (targetIndex > 0 || targetOffset > 0) {
            verticalRowListState.scrollToItem(targetIndex, targetOffset)
        }
    }

    val activeRow by remember(carouselRows, rowByKey, activeRowKey) {
        derivedStateOf {
            val activeKey = activeRowKey
            if (activeKey == null) {
                null
            } else {
                rowByKey[activeKey] ?: carouselRows.firstOrNull()
            }
        }
    }
    val clampedActiveItemIndex by remember(activeRow, activeItemIndex) {
        derivedStateOf {
            activeRow?.let { row ->
                activeItemIndex.coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
            } ?: 0
        }
    }

    LaunchedEffect(activeRow?.key, activeRow?.items?.size) {
        val row = activeRow ?: return@LaunchedEffect
        val clampedIndex = focusHolder.activeItemIndex.coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
        if (focusHolder.activeItemIndex != clampedIndex) {
            focusHolder.activeItemIndex = clampedIndex
            activeItemIndex = clampedIndex
        }
        focusedItemByRow[row.key] = clampedIndex
    }

    val latestActiveRow by rememberUpdatedState(activeRow)
    val latestActiveItemIndex by rememberUpdatedState(clampedActiveItemIndex)
    val latestCarouselRows by rememberUpdatedState(carouselRows)
    val latestVerticalRowListState by rememberUpdatedState(verticalRowListState)

    LaunchedEffect(Unit) {
        kotlinx.coroutines.flow.combine(
            snapshotFlow { Pair(activeRow, clampedActiveItemIndex) },
            isFastScrollingRef
        ) { pair, scrolling -> Pair(pair, scrolling) }
            .debounce(80L)
            .collectLatest { (_, isScrolling) ->
                if (isScrolling) return@collectLatest
                val row = activeRow ?: return@collectLatest
                val index = clampedActiveItemIndex
                val hero = row.items.getOrNull(index)?.heroPreview
                if (hero == null) return@collectLatest
                heroItem = hero
                heroItemRowKey = row.key
            }
    }
    LaunchedEffect(Unit) {
        isFastScrollingRef
            .collect { isScrolling ->
                if (isScrolling && !isFastScrolling) {
                    val currentRow = latestActiveRow
                    val currentIndex = latestActiveItemIndex
                    frozenHeroItem = currentRow?.items?.getOrNull(currentIndex)?.heroPreview ?: heroItem
                    frozenHeroItemRowKey = currentRow?.key ?: heroItemRowKey
                }
                isFastScrolling = isScrolling
            }
    }

    // Save focus state immediately before navigating away so it's available on back
    val latestSelectedPlatformId by rememberUpdatedState(selectedPlatformId)
    val wrappedOnNavigateToDetail: (String, String, String) -> Unit = remember(onNavigateToDetail, onSaveFocusState) {
        { itemId, itemType, addonBaseUrl ->
            val row = latestActiveRow
            val focusedRowIndex = row?.globalRowIndex ?: 0
            val focusedRowKey = row?.key
            val catalogRowScrollStates = latestCarouselRows
                .filter { it.globalRowIndex >= 0 }
                .associate { rowState -> rowState.key to (focusedItemByRow[rowState.key] ?: 0) }
            android.util.Log.d("NuvioFocus", "SAVING: rowKey=$focusedRowKey platform=$latestSelectedPlatformId itemIndex=$latestActiveItemIndex")
            onSaveFocusState(
                latestVerticalRowListState.firstVisibleItemIndex,
                latestVerticalRowListState.firstVisibleItemScrollOffset,
                focusedRowIndex,
                latestActiveItemIndex,
                catalogRowScrollStates,
                focusedRowKey,
                latestSelectedPlatformId
            )
            onNavigateToDetail(itemId, itemType, addonBaseUrl)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val row = latestActiveRow
            val focusedRowIndex = row?.globalRowIndex ?: 0
            val focusedRowKey = row?.key
            val catalogRowScrollStates = latestCarouselRows
                .filter { it.globalRowIndex >= 0 }
                .associate { rowState -> rowState.key to (focusedItemByRow[rowState.key] ?: 0) }

            onSaveFocusState(
                latestVerticalRowListState.firstVisibleItemIndex,
                latestVerticalRowListState.firstVisibleItemScrollOffset,
                focusedRowIndex,
                latestActiveItemIndex,
                catalogRowScrollStates,
                focusedRowKey,
                latestSelectedPlatformId
            )
        }
    }

    val portraitBaseWidth = uiState.posterCardWidthDp.dp
    val portraitBaseHeight = uiState.posterCardHeightDp.dp
    val modernPosterScale = if (useLandscapePosters) 1.34f else 1.08f
    val modernCatalogCardWidth = if (useLandscapePosters) {
        portraitBaseWidth * 1.24f * modernPosterScale
    } else {
        portraitBaseWidth * 0.84f * modernPosterScale
    }
    val modernCatalogCardHeight = if (useLandscapePosters) {
        modernCatalogCardWidth / 1.77f
    } else {
        portraitBaseHeight * 0.84f * modernPosterScale
    }
    val continueWatchingScale = 1.34f
    val continueWatchingCardWidth = portraitBaseWidth * 1.24f * continueWatchingScale
    val continueWatchingCardHeight = continueWatchingCardWidth / 1.77f

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val posterCardCornerRadius = remember(uiState.posterCardCornerRadiusDp) { uiState.posterCardCornerRadiusDp.dp }
        val rowHorizontalPadding = 52.dp

        val activeCarouselItem = remember(activeRow, clampedActiveItemIndex) {
            activeRow?.items?.getOrNull(clampedActiveItemIndex)
        }
        val activeItemId = activeCarouselItem?.metaPreview?.id
        val enrichmentActive = enrichingItemId != null && enrichingItemId == activeItemId
        // When enrichment is active use heroItem (frozen), when done use activeCarouselItem
        // which already has the enriched data from uiState update
        // Always use debounced heroItem so fast scrolling doesn't flash metadata.
        // Only fall back to activeCarouselItem when heroItem is null (cold start).
        val heroItemMatchesRow = heroItemRowKey == activeRow?.key
        val resolvedHero = if (isFastScrolling) frozenHeroItem ?: heroItem else if (heroItemMatchesRow) heroItem ?: activeCarouselItem?.heroPreview else activeCarouselItem?.heroPreview
        android.util.Log.d("NuvioHero", "RENDER: heroItem=${heroItem?.title} heroItemRow=${heroItemRowKey?.take(20)} activeRow=${activeRow?.key?.take(20)} rowMatch=$heroItemMatchesRow activeCarouselItem=${activeCarouselItem?.heroPreview?.title} resolvedHero=${resolvedHero?.title} index=$clampedActiveItemIndex")
        // Inject cached MDB ratings into the hero preview when home screen ratings are enabled

        val activeRowFallbackBackdrop = remember(activeRow?.key, activeRow?.items?.size) {
            activeRow?.items?.firstNotNullOfOrNull { item ->
                item.heroPreview.backdrop?.takeIf { it.isNotBlank() }
            }
        }
        val heroBackdrop = remember(resolvedHero, activeRowFallbackBackdrop, heroItem) {
            firstNonBlank(
                resolvedHero?.backdrop,
                resolvedHero?.imageUrl,
                resolvedHero?.poster,
                if (heroItem == null) activeRowFallbackBackdrop else null
            )
        }
        val expandedFocusedSelection = remember(focusedCatalogSelection, expandedCatalogFocusKey) {
            focusedCatalogSelection?.takeIf { it.focusKey == expandedCatalogFocusKey }
        }
        val heroTrailerUrl by remember(expandedFocusedSelection) {
            derivedStateOf {
                expandedFocusedSelection?.payload?.itemId?.let { trailerPreviewUrls[it] }
            }
        }
        val heroTrailerAudioUrl by remember(expandedFocusedSelection) {
            derivedStateOf {
                expandedFocusedSelection?.payload?.itemId?.let { trailerPreviewAudioUrls[it] }
            }
        }
        val expandedCatalogTrailerUrl = heroTrailerUrl
        val expandedCatalogTrailerAudioUrl = heroTrailerAudioUrl
        val shouldPlayHeroTrailer = remember(
            effectiveAutoplayEnabled,
            trailerPlaybackTarget,
            heroTrailerUrl,
            isVerticalRowsScrolling,
            isSidebarExpanded
        ) {
            effectiveAutoplayEnabled &&
                !isSidebarExpanded &&
                !isVerticalRowsScrolling &&
                trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.HERO_MEDIA &&
                !heroTrailerUrl.isNullOrBlank()
        }
        var heroTrailerFirstFrameRendered by remember(heroTrailerUrl) { mutableStateOf(false) }
        val isHeroTrailerActivelyPlaying = shouldPlayHeroTrailer && heroTrailerFirstFrameRendered
        LaunchedEffect(isHeroTrailerActivelyPlaying) {
            onHeroTrailerPlayingChanged(isHeroTrailerActivelyPlaying)
        }
        LaunchedEffect(shouldPlayHeroTrailer) {
            if (!shouldPlayHeroTrailer) heroTrailerFirstFrameRendered = false
        }
        val heroTransitionTarget = if (shouldPlayHeroTrailer && heroTrailerFirstFrameRendered) 1f else 0f
        val heroTransitionProgress by animateFloatAsState(
            targetValue = heroTransitionTarget,
            animationSpec = if (heroTransitionTarget == 1f) tween(durationMillis = 480) else tween(durationMillis = 150),
            label = "heroBackdropTrailerCrossfadeProgress"
        )
        val heroBackdropAlpha = 1f - heroTransitionProgress
        val heroTrailerAlpha = heroTransitionProgress
        var lbGradientVisible by remember(heroTrailerUrl) { mutableStateOf(false) }
        var lbTrailerVisible by remember(heroTrailerUrl) { mutableStateOf(false) }
        LaunchedEffect(heroTrailerFirstFrameRendered, uiState.heroTrailerAllowLetterboxing) {
            if (heroTrailerFirstFrameRendered && uiState.heroTrailerAllowLetterboxing) {
                delay(480)
                lbGradientVisible = true
                lbTrailerVisible = true
            } else {
                lbGradientVisible = false
                lbTrailerVisible = false
            }
        }
        val heroGradientProgress = if (uiState.heroTrailerAllowLetterboxing) {
            if (lbGradientVisible) 1f else 0f
        } else {
            heroTransitionProgress
        }
        val lbTrailerProgress by animateFloatAsState(
            targetValue = if (uiState.heroTrailerAllowLetterboxing && lbTrailerVisible) 1f else 0f,
            animationSpec = if (lbTrailerVisible) tween(durationMillis = 150) else snap(),
            label = "lbTrailerProgress"
        )
        val lbTrailerAlpha = if (uiState.heroTrailerAllowLetterboxing) lbTrailerProgress else heroTrailerAlpha
        val catalogBottomPadding = 0.dp
        val heroToCatalogGap = 16.dp
        val rowTitleBottom = 14.dp
        val rowsViewportHeightFraction = if (useLandscapePosters) 0.49f else 0.52f
        val rowsViewportHeight = maxHeight * rowsViewportHeightFraction
        val localDensity = LocalDensity.current
        val rowTitleLineHeight = MaterialTheme.typography.titleMedium.lineHeight
        val rowTitleHeight = with(localDensity) {
            runCatching { rowTitleLineHeight.toDp() }
                .getOrDefault(24.dp)
        }
        val heroBackdropHeight = (maxHeight - rowsViewportHeight + rowTitleHeight + rowTitleBottom)
            .coerceAtMost(maxHeight)
        val bgColor = NuvioColors.Background
        val contentFocusRequester = LocalContentFocusRequester.current
        val carouselFocusRequester = LocalCarouselFocusRequester.current
        val focusRestorerRequester by remember(carouselRows, uiCaches) {
            derivedStateOf {
                val rowKey = activeRowKey
                if (rowKey != null) {
                    val row = carouselRows.firstOrNull { it.key == rowKey }
                    val rowListState = uiCaches.rowListStates[rowKey]
                    val firstVisibleIndex = rowListState?.firstVisibleItemIndex ?: 0
                    val safeIndex = firstVisibleIndex.coerceIn(0, ((row?.items?.size ?: 1) - 1).coerceAtLeast(0))
                    val itemKey = row?.items?.getOrNull(safeIndex)?.key
                    if (itemKey != null) {
                        uiCaches.itemFocusRequesters[rowKey]?.get(itemKey) ?: FocusRequester.Default
                    } else FocusRequester.Default
                } else FocusRequester.Default
            }
        }
        val heroMediaWidthPx = remember(maxWidth, localDensity) {
            with(localDensity) { (maxWidth * MODERN_HERO_MEDIA_WIDTH_FRACTION).roundToPx() }
        }
        val heroMediaHeightPx = remember(heroBackdropHeight, localDensity) {
            with(localDensity) { heroBackdropHeight.roundToPx() }
        }

        // Cinematic mode: trailers off OR target is expanded card → full-screen backdrop + detail-style gradient
        val cinematicHeroMode = !uiState.focusedPosterBackdropTrailerEnabled ||
            uiState.focusedPosterBackdropTrailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD

        val heroMediaModifier = remember(heroBackdropHeight, cinematicHeroMode, maxHeight) {
            if (cinematicHeroMode) {
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = -(maxHeight * 0.10f))
                    .fillMaxSize()
            } else {
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 56.dp)
                    .fillMaxWidth(MODERN_HERO_MEDIA_WIDTH_FRACTION)
                    .height(heroBackdropHeight)
            }
        }

        ModernHeroMediaLayer(
            heroBackdrop = heroBackdrop,
            heroBackdropAlpha = heroBackdropAlpha,
            shouldPlayHeroTrailer = shouldPlayHeroTrailer && !uiState.heroTrailerAllowLetterboxing,
            heroTrailerUrl = heroTrailerUrl,
            heroTrailerAudioUrl = heroTrailerAudioUrl,
            heroTrailerAlpha = heroTrailerAlpha,
            muted = uiState.focusedPosterBackdropTrailerMuted,
            onTrailerEnded = { expandedCatalogFocusKey = null },
            onFirstFrameRendered = { heroTrailerFirstFrameRendered = true },
            modifier = heroMediaModifier,
            requestWidthPx = heroMediaWidthPx,
            requestHeightPx = heroMediaHeightPx
        )
        if (shouldPlayHeroTrailer && uiState.heroTrailerAllowLetterboxing) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .width(maxWidth * 0.60f)
                    .height(heroBackdropHeight)
                    .graphicsLayer { alpha = lbTrailerAlpha }
            ) {
                TrailerPlayer(
                    trailerUrl = heroTrailerUrl,
                    trailerAudioUrl = heroTrailerAudioUrl,
                    isPlaying = true,
                    onEnded = { expandedCatalogFocusKey = null },
                    onFirstFrameRendered = { heroTrailerFirstFrameRendered = true },
                    muted = uiState.focusedPosterBackdropTrailerMuted,
                    cropToFill = true,
                    overscanZoom = 1f,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        ModernHeroGradientLayer(
            bgColor = bgColor,
            allowLetterboxing = uiState.heroTrailerAllowLetterboxing,
            trailerTransitionProgress = heroGradientProgress,
            modifier = heroMediaModifier,
            cinematicMode = cinematicHeroMode,
            shouldPlayHeroTrailer = shouldPlayHeroTrailer
        )
        if (carouselGradientAlpha > 0f) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height(80.dp)
                    .graphicsLayer { alpha = carouselGradientAlpha }
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.050f to androidx.compose.ui.graphics.Color(0xCC000000),
                                0.150f to androidx.compose.ui.graphics.Color(0xBB000000),
                                0.250f to androidx.compose.ui.graphics.Color(0xA5000000),
                                0.350f to androidx.compose.ui.graphics.Color(0x8C000000),
                                0.450f to androidx.compose.ui.graphics.Color(0x70000000),
                                0.550f to androidx.compose.ui.graphics.Color(0x55000000),
                                0.630f to androidx.compose.ui.graphics.Color(0x3A000000),
                                0.690f to androidx.compose.ui.graphics.Color(0x2A000000),
                                0.750f to androidx.compose.ui.graphics.Color(0x1E000000),
                                0.800f to androidx.compose.ui.graphics.Color(0x16000000),
                                0.845f to androidx.compose.ui.graphics.Color(0x10000000),
                                0.880f to androidx.compose.ui.graphics.Color(0x0C000000),
                                0.915f to androidx.compose.ui.graphics.Color(0x08000000),
                                0.940f to androidx.compose.ui.graphics.Color(0x05000000),
                                0.962f to androidx.compose.ui.graphics.Color(0x03000000),
                                0.979f to androidx.compose.ui.graphics.Color(0x02000000),
                                0.991f to androidx.compose.ui.graphics.Color(0x01000000),
                                1.000f to androidx.compose.ui.graphics.Color.Transparent
                            )
                        )
                    )
            )
        }
        HeroTitleBlock(
            preview = resolvedHero,
            portraitMode = !useLandscapePosters,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = rowHorizontalPadding,
                    end = 48.dp,
                    bottom = catalogBottomPadding + rowsViewportHeight + heroToCatalogGap
                )
                .fillMaxWidth(MODERN_HERO_TEXT_WIDTH_FRACTION)
        )

        val verticalRowBringIntoViewSpec = remember(localDensity, defaultBringIntoViewSpec) {
            val topInsetPx = with(localDensity) { MODERN_ROW_HEADER_FOCUS_INSET.toPx() }
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            object : BringIntoViewSpec {
                override val scrollAnimationSpec: AnimationSpec<Float> =
                    defaultBringIntoViewSpec.scrollAnimationSpec

                override fun calculateScrollDistance(
                    offset: Float,
                    size: Float,
                    containerSize: Float
                ): Float = offset - topInsetPx
            }
        }

        CompositionLocalProvider(LocalBringIntoViewSpec provides verticalRowBringIntoViewSpec) {
            LazyColumn(
                state = verticalRowListState,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(rowsViewportHeight)
                    .padding(bottom = catalogBottomPadding)
                    .focusRequester(contentFocusRequester)
                    .focusRestorer { focusRestorerRequester }
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        val isDpad = native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP ||
                            native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN ||
                            native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
                            native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                        if (native.action == AndroidKeyEvent.ACTION_UP && isDpad) {
                            lastKeyUpTimeRef.set(System.currentTimeMillis())
                            isFastScrollingRef.value = false
                        }
                        if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount > 0 && isDpad) {
                            isFastScrollingRef.value = true
                            val now = System.currentTimeMillis()
                            if (native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP ||
                                native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                                if (now - lastKeyRepeatTimeRef.get() < KEY_REPEAT_THROTTLE_MS) {
                                    return@onPreviewKeyEvent true
                                }
                                lastKeyRepeatTimeRef.set(now)
                            }
                        }
                        // Navigate up to platform carousel when focused on first row
                        if (native.action == AndroidKeyEvent.ACTION_DOWN &&
                            native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                            val isAtTopRow = run {
                                val firstRow = carouselRows.firstOrNull()
                                firstRow != null && focusHolder.activeRowKey == firstRow.key
                            }
                            if (isAtTopRow) {
                                if (isFastScrollingRef.value) {
                                    return@onPreviewKeyEvent true
                                }
                                if (aggregatePlatformsEnabled) {
                                    focusedCatalogSelection = null
                                    onCarouselOpenRequested()
                                    try { carouselFocusRequester.requestFocus() } catch (e: Exception) {}
                                    return@onPreviewKeyEvent true
                                }
                            }
                        }
                        false
                    },
                contentPadding = PaddingValues(bottom = rowsViewportHeight),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                itemsIndexed(
                    items = carouselRows,
                    key = { _, row -> row.key },
                    contentType = { _, _ -> "modern_home_row" }
                ) { _, row ->
                    val stableOnContinueWatchingOptions = remember(Unit) {
                        { item: ContinueWatchingItem -> optionsItem = item }
                    }
                    val stableOnRequestCarouselFocus = remember(Unit) {
                        {
                            if (aggregatePlatformsEnabled) {
                                focusedCatalogSelection = null
                                onCarouselOpenRequested()
                                try { carouselFocusRequester.requestFocus() } catch (e: Exception) {}
                            }
                        }
                    }
                    val stableOnRowItemFocused = remember(Unit) {
                        { rowKey: String, index: Int, isContinueWatchingRow: Boolean ->
                            val rowBecameActive = focusHolder.activeRowKey != rowKey
                            val itemChanged = focusHolder.activeItemIndex != index
                            if (rowBecameActive || itemChanged) {
                                val now = System.currentTimeMillis()
                                val timeSinceLastHeroNav = now - lastHeroNavigationAtMsRef.get()
                                heroFocusSettleDelayMsRef.set(
                                    if (lastHeroNavigationAtMsRef.get() != 0L &&
                                        timeSinceLastHeroNav in 1 until MODERN_HERO_RAPID_NAV_THRESHOLD_MS
                                    ) MODERN_HERO_RAPID_NAV_SETTLE_MS
                                    else MODERN_HERO_FOCUS_DEBOUNCE_MS
                                )
                                lastHeroNavigationAtMsRef.set(now)
                                focusHolder.activeRowKey = rowKey
                                focusHolder.activeItemIndex = index
                                activeRowKey = rowKey
                                activeItemIndex = index
                            }
                            if (focusedItemByRow[rowKey] != index) {
                                focusedItemByRow[rowKey] = index
                            }
                            if (isContinueWatchingRow) {
                                if (lastFocusedContinueWatchingIndexRef.get() != index) {
                                    lastFocusedContinueWatchingIndexRef.set(index)
                                }
                                if (focusedCatalogSelection != null) {
                                    focusedCatalogSelection = null
                                }
                            }
                        }
                    }
                    val stableOnCatalogSelectionFocused = remember(Unit) {
                        { selection: FocusedCatalogSelection ->
                            if (focusedCatalogSelection != selection) {
                                focusedCatalogSelection = selection
                            }
                        }
                    }
                    val stableOnPendingRowFocusCleared = remember(Unit) {
                        { pendingRowFocusKey = null; pendingRowFocusIndex = null; Unit }
                    }
                    val stableOnBackdropInteraction = remember(Unit) {
                        { expansionInteractionNonce++; Unit }
                    }
                    val stableOnExpandedCatalogFocusKeyChange = remember(Unit) {
                        { key: String? -> expandedCatalogFocusKey = key }
                    }
                    val rowExpandedFocusKey = expandedCatalogFocusKey
                    val rowHasExpanded by remember(row.key) {
                        derivedStateOf {
                            expandedCatalogFocusKey != null &&
                                row.items.any { (it.payload as? ModernPayload.Catalog)?.focusKey == expandedCatalogFocusKey }
                        }
                    }
                    ModernRowSection(
                        row = row,
                        rowTitleBottom = rowTitleBottom,
                        numberStyle = row.numberStyle,
                        isFirstRow = carouselRows.firstOrNull()?.key == row.key,
                        onRequestCarouselFocus = stableOnRequestCarouselFocus,
                        defaultBringIntoViewSpec = defaultBringIntoViewSpec,
                        focusStateCatalogRowScrollStates = focusState.catalogRowScrollStates,
                        uiCaches = uiCaches,
                        pendingRowFocusKey = pendingRowFocusKey,
                        pendingRowFocusIndex = pendingRowFocusIndex,
                        pendingRowFocusNonce = pendingRowFocusNonce,
                        onPendingRowFocusCleared = stableOnPendingRowFocusCleared,
                        onRowItemFocused = stableOnRowItemFocused,
                        useLandscapePosters = useLandscapePosters,
                        showLabels = uiState.posterLabelsEnabled,
                        posterCardCornerRadius = posterCardCornerRadius,
                        focusedPosterBackdropTrailerMuted = uiState.focusedPosterBackdropTrailerMuted,
                        effectiveExpandEnabled = effectiveExpandEnabled,
                        effectiveAutoplayEnabled = effectiveAutoplayEnabled,
                        trailerPlaybackTarget = trailerPlaybackTarget,
                        expandedCatalogFocusKey = rowExpandedFocusKey,
                        expandedTrailerPreviewUrl = if (rowHasExpanded) expandedCatalogTrailerUrl else null,
                        expandedTrailerPreviewAudioUrl = if (rowHasExpanded) expandedCatalogTrailerAudioUrl else null,
                        modernCatalogCardWidth = modernCatalogCardWidth,
                        modernCatalogCardHeight = modernCatalogCardHeight,
                        continueWatchingCardWidth = continueWatchingCardWidth,
                        continueWatchingCardHeight = continueWatchingCardHeight,
                        onContinueWatchingClick = onContinueWatchingClick,
                        onContinueWatchingOptions = stableOnContinueWatchingOptions,
                        isCatalogItemWatched = isCatalogItemWatched,
                        onCatalogItemLongPress = onCatalogItemLongPress,
                        onItemFocus = onItemFocus,
                        onPreloadAdjacentItem = onPreloadAdjacentItem,
                        onCatalogSelectionFocused = stableOnCatalogSelectionFocused,
                        onNavigateToDetail = wrappedOnNavigateToDetail,
                        onLoadMoreCatalog = onLoadMoreCatalog,
                        onBackdropInteraction = stableOnBackdropInteraction,
                        onExpandedCatalogFocusKeyChange = stableOnExpandedCatalogFocusKeyChange,
                        useThemeColorForNumbers = useThemeColorForNumbers
                    )
                }
            }
        }
    }

    val selectedOptionsItem = optionsItem
    if (selectedOptionsItem != null) {
        ContinueWatchingOptionsDialog(
            item = selectedOptionsItem,
            onDismiss = { optionsItem = null },
            onRemove = {
                val targetIndex = if (uiState.continueWatchingItems.size <= 1) {
                    null
                } else {
                    minOf(lastFocusedContinueWatchingIndexRef.get(), uiState.continueWatchingItems.size - 2)
                        .coerceAtLeast(0)
                }
                pendingRowFocusKey = if (targetIndex != null) "continue_watching" else null
                pendingRowFocusIndex = targetIndex
                pendingRowFocusNonce++
                onRemoveContinueWatching(
                    selectedOptionsItem.contentId(),
                    selectedOptionsItem.season(),
                    selectedOptionsItem.episode(),
                    selectedOptionsItem is ContinueWatchingItem.NextUp
                )
                optionsItem = null
            },
            onDetails = {
                wrappedOnNavigateToDetail(
                    selectedOptionsItem.contentId(),
                    selectedOptionsItem.contentType(),
                    ""
                )
                optionsItem = null
            },
            onStartFromBeginning = {
                onContinueWatchingStartFromBeginning(selectedOptionsItem)
                optionsItem = null
            },
            showPlayManually = showContinueWatchingManualPlayOption,
            onPlayManually = {
                onContinueWatchingPlayManually(selectedOptionsItem)
                optionsItem = null
            }
        )
    }
}


