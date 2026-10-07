@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.ui.util.dpadVerticalFastScroll
import androidx.compose.animation.Crossfade
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
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
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.ContinueWatchingCard
import com.nuvio.tv.ui.components.ContinueWatchingOptionsDialog
import com.nuvio.tv.ui.components.LocalHomePopupGlassEnvironment
import com.nuvio.tv.ui.components.MonochromePosterPlaceholder
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.LocalAppInForeground
import com.nuvio.tv.LocalSidebarExpanded
import com.nuvio.tv.LocalHomeHeroTrailerPlaying
import com.nuvio.tv.LocalPreserveSidebarTrailerPlayback
import com.nuvio.tv.LocalSidebarFocusRestoreActive
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.LocalCarouselFocusRequester
import com.nuvio.tv.LocalIsScrolling
import com.nuvio.tv.LocalRowFocusRestorer
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.view.KeyEvent as AndroidKeyEvent
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import androidx.compose.ui.draw.drawWithCache
import com.nuvio.tv.ui.util.StableList
import com.nuvio.tv.ui.util.StableMap
import com.nuvio.tv.ui.util.StableSet
import com.nuvio.tv.ui.util.asStable

private const val MODERN_HERO_RAPID_NAV_THRESHOLD_MS = 130L
private const val MODERN_HERO_RAPID_NAV_SETTLE_MS = 400L
private const val MODERN_HERO_NORMAL_SETTLE_MS = 450L
private const val KEY_REPEAT_THROTTLE_MS = 140L

private const val HOME_DOUBLE_UP_GAP_MS = 80L
private const val HOME_DOUBLE_UP_LEAD_ROWS = 3
private const val HOME_DOUBLE_UP_TOP_RUNWAY_ROWS = 6
private const val HOME_DOUBLE_UP_VELOCITY_DP_PER_SEC = 2400f

@androidx.compose.runtime.Stable
private class EnhancedHomeRowsFocusHolder {
    var activeRowKey: String? = null
    var activeItemIndex: Int = 0
}

private val TMDB_BACKDROP_SIZE_SEGMENT = Regex("""(/t/p/)[^/]+/""")

private fun cinematicBackdropIdentity(url: String?): String? {
    val cleanUrl = url
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.substringBefore('?')
        ?.substringBefore('#')
        ?: return null

    return TMDB_BACKDROP_SIZE_SEGMENT.replace(cleanUrl) { match ->
        match.groupValues[1]
    }
}

@Composable
fun ModernHomeContent(
    uiState: HomeUiState,
    selectedPlatformId: String = "home",
    aggregatePlatformsEnabled: Boolean = true,
    showAllCatalogsOnHome: Boolean = false,
    fullWidthIconRowEnabled: Boolean = false,
    heroMetadataLarge: Boolean = false,
    focusState: HomeScreenFocusState,
    myListHeadResetPending: Boolean = false,
    onMyListHeadResetConsumed: () -> Unit = {},
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
    isAtTop: Boolean = true,
    sharedTrailerPlayer: androidx.media3.exoplayer.ExoPlayer? = null,
    carouselGradientAlpha: Float = 0f,
    platformGradientRevealAlpha: () -> Float = { 1f },
    onCarouselOpenRequested: () -> Unit = {},
    isCarouselFocused: Boolean = false,
    onHeroTrailerPlayingChanged: (Boolean) -> Unit = {},
    onHeroBackdropAlphaChanged: (Float) -> Unit = {},
    platformNavDirection: Int = 0,
    isPlatformDpadHeld: () -> Boolean = { false },
    onBackdropPreloadSizeKnown: (Int, Int) -> Unit = { _, _ -> },
    onComingSoonGlassTextChanged: (String?) -> Unit = {}
) {
    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
    val isSidebarExpanded = LocalSidebarExpanded.current
    val homeHeroTrailerPlayingState =
        LocalHomeHeroTrailerPlaying.current
    val preserveSidebarTrailerPlayback =
        LocalPreserveSidebarTrailerPlayback.current
    val sidebarFocusRestoreActive =
        LocalSidebarFocusRestoreActive.current
    val homePopupGlassEnvironment = LocalHomePopupGlassEnvironment.current
    val suppressFocusedPosterAutoplayForOptions =
        shouldSuppressFocusedPosterAutoplayForPopup(
            popupVisible = homePopupGlassEnvironment.catalogOptionsVisible,
            preservePlayingTrailer =
                homePopupGlassEnvironment.preserveCatalogTrailerPlayback
        )

    /*
     * A LaunchedEffect that is already inside the user's autoplay delay must
     * consult the CURRENT popup state immediately before promotion. This
     * closes the race where options open near the end of that delay.
     */
    val latestSuppressFocusedPosterAutoplayForOptions =
        androidx.compose.runtime.rememberUpdatedState(
            suppressFocusedPosterAutoplayForOptions
        )

    val preserveFocusedPosterPlaybackForOptions =
        homePopupGlassEnvironment.preserveCatalogTrailerPlayback &&
            (
                homePopupGlassEnvironment.catalogOptionsVisible ||
                    homePopupGlassEnvironment
                        .catalogOptionsFocusRestoreActive
            )

    val useLandscapePosters = uiState.modernLandscapePostersEnabled
    val showCatalogTypeSuffixInModern = uiState.catalogTypeSuffixEnabled
    val hidePlatformNameInModern = uiState.hidePlatformNameInCatalogTitleEnabled
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
    // Flips immediately on platform change — drives metadata AnimatedContent
    var displayedPlatformId by remember { mutableStateOf(selectedPlatformId) }
    // Lags behind by exit animation duration — drives catalog LazyColumn content
    var catalogDisplayedPlatformId by remember { mutableStateOf(selectedPlatformId) }

    val visibleCatalogRows = remember(
        uiState.catalogRows,
        catalogDisplayedPlatformId,
        aggregatePlatformsEnabled,
        showAllCatalogsOnHome
    ) {
        uiState.catalogRows
            .filter { it.items.isNotEmpty() || it.isLoading }
            .let { rows ->
                if (
                    !aggregatePlatformsEnabled ||
                    catalogDisplayedPlatformId == "home"
                ) {
                    if (
                        aggregatePlatformsEnabled &&
                        !showAllCatalogsOnHome
                    ) {
                        rows.filter {
                            inferPlatformId(it.catalogName) == null
                        }
                    } else {
                        rows
                    }
                } else {
                    rows.filter {
                        inferPlatformId(it.catalogName) ==
                            catalogDisplayedPlatformId
                    }
                }
            }
    }

    val strContinueWatching = stringResource(R.string.continue_watching)
    val strAirsDate = stringResource(R.string.cw_airs_date)
    val strUpcoming = stringResource(R.string.cw_upcoming)
    val strTypeMovie = stringResource(R.string.type_movie)
    val strTypeSeries = stringResource(R.string.type_series)
    // Reminder fulfillment is rare; recreating this mapping cache guarantees
    // that a badge appears or clears atomically without touching scroll paths.
    val rowBuildCache = remember(uiState.releaseReminderBadges) { ModernCarouselRowBuildCache() }
    val context = LocalContext.current
    val density = LocalDensity.current
    val enrichmentReadyRowKeys: Set<String> = uiState.enrichmentReadyRowKeys
    val continueWatchingEnrichmentReady: Boolean = uiState.continueWatchingEnrichmentReady
    val currentContinueWatchingOrderKeys =
        remember(uiState.continueWatchingItems) {
            stableContinueWatchingOrderKeys(
                uiState.continueWatchingItems
            )
        }

    val carouselRows = remember(
        uiState.continueWatchingItems,
        visibleCatalogRows,
        useLandscapePosters,
        showCatalogTypeSuffixInModern,
        hidePlatformNameInModern,
        strTypeMovie,
        strTypeSeries,
        numberedCatalogKeys,
        outlineNumberedCatalogKeys,
        uiState.landscapeCatalogKeys,
        uiState.releaseReminderBadges,
        enrichmentReadyRowKeys,
        continueWatchingEnrichmentReady
    ) {
        buildList {
            val activeCatalogKeys = LinkedHashSet<String>(visibleCatalogRows.size)
            if (uiState.continueWatchingItems.isNotEmpty() && catalogDisplayedPlatformId == "home") {
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
                        enrichmentReady = uiState.continueWatchingEnrichmentReady,
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
                val rowUseLandscapePosters = useLandscapePosters || rowKey in uiState.landscapeCatalogKeys
                activeCatalogKeys += rowKey
                val cached = rowBuildCache.catalogRows[rowKey]
                val cachedNumberStyle = when {
                    rowKey in outlineNumberedCatalogKeys -> NumberStyle.OUTLINE
                    rowKey in numberedCatalogKeys -> NumberStyle.SOLID
                    else -> NumberStyle.OFF
                }
                val rowEnrichmentReady = rowKey in uiState.enrichmentReadyRowKeys
                val canReuseMappedRow =
                    cached != null &&
                        cached.source == row &&
                        cached.useLandscapePosters == rowUseLandscapePosters &&
                        cached.showCatalogTypeSuffix == showCatalogTypeSuffixInModern &&
                        cached.hidePlatformName == hidePlatformNameInModern &&
                        cached.mappedRow.numberStyle == cachedNumberStyle

                val mappedRow = if (canReuseMappedRow) {
                    val cachedMappedRow = checkNotNull(cached).mappedRow
                    if (cachedMappedRow.globalRowIndex == index &&
                        cachedMappedRow.enrichmentReady == rowEnrichmentReady) {
                        cachedMappedRow
                    } else {
                        cachedMappedRow.copy(globalRowIndex = index, enrichmentReady = rowEnrichmentReady)
                    }
                } else {
                    val rowItemOccurrenceCounts = mutableMapOf<String, Int>()
                    val rowItemCache = rowBuildCache.catalogItemCache.getOrPut(rowKey) { mutableMapOf() }
                    HeroCarouselRow(
                        key = rowKey,
                        title = catalogRowTitle(
                            row = row,
                            showCatalogTypeSuffix = showCatalogTypeSuffixInModern,
                            hidePlatformName = hidePlatformNameInModern,
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
                        enrichmentReady = rowEnrichmentReady,
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
                                cachedItem.useLandscapePosters == rowUseLandscapePosters
                            ) {
                                cachedItem.carouselItem
                            } else {
                                val built = buildCatalogItem(
                                    item = item,
                                    row = row,
                                    useLandscapePosters = rowUseLandscapePosters,
                                    occurrence = occurrence,
                                    strTypeMovie = strTypeMovie,
                                    strTypeSeries = strTypeSeries,
                                    releaseReminderBadge =
                                if (rowKey == HomeViewModel.MY_LIST_CATALOG_KEY) {
                                    val primaryKey =
                                        com.nuvio.tv.data.local.releaseReminderKey(
                                            item.id,
                                            item.apiType
                                        )
                                    val imdbKey =
                                        item.imdbId?.let { imdbId ->
                                            com.nuvio.tv.data.local.releaseReminderKey(
                                                imdbId,
                                                item.apiType
                                            )
                                        }

                                    val resolvedBadge =
                                        primaryKey?.let(uiState.releaseReminderBadges::get)
                                            ?: imdbKey?.let(uiState.releaseReminderBadges::get)


                                    resolvedBadge
                                } else {
                                    null
                                }
                                )
                                rowItemCache[cacheKey] = CachedCarouselItem(
                                    source = item,
                                    useLandscapePosters = rowUseLandscapePosters,
                                    carouselItem = built
                                )
                                built
                            }
                        }
                    )
                }

                rowBuildCache.catalogRows[rowKey] = ModernCatalogRowBuildCacheEntry(
                    source = row,
                    useLandscapePosters = rowUseLandscapePosters,
                    showCatalogTypeSuffix = showCatalogTypeSuffixInModern,
                    hidePlatformName = hidePlatformNameInModern,
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
        val orderedItemKeysByRow =
            LinkedHashMap<String, List<String>>(carouselRows.size)
        val activeCatalogItemIds = LinkedHashSet<String>()

        carouselRows.forEachIndexed { index, row ->
            rowIndexByKey[row.key] = index
            rowByKey[row.key] = row
            activeRowKeys += row.key

            val itemKeys = LinkedHashSet<String>(row.items.size)
            val orderedItemKeys = ArrayList<String>(row.items.size)
            row.items.forEach { item ->
                itemKeys += item.key
                orderedItemKeys += item.key
                val payload = item.payload
                if (payload is ModernPayload.Catalog) {
                    activeCatalogItemIds += payload.itemId
                }
            }
            activeItemKeysByRow[row.key] = itemKeys
            orderedItemKeysByRow[row.key] = orderedItemKeys
        }

        CarouselRowLookups(
            rowIndexByKey = rowIndexByKey,
            rowByKey = rowByKey,
            activeRowKeys = activeRowKeys,
            activeItemKeysByRow = activeItemKeysByRow,
            orderedItemKeysByRow = orderedItemKeysByRow,
            activeCatalogItemIds = activeCatalogItemIds
        )
    }
    val rowIndexByKey = carouselLookups.rowIndexByKey
    val rowByKey = carouselLookups.rowByKey
    val activeRowKeys = carouselLookups.activeRowKeys
    val activeItemKeysByRow = carouselLookups.activeItemKeysByRow
    val orderedItemKeysByRow = carouselLookups.orderedItemKeysByRow
    val activeCatalogItemIds = carouselLookups.activeCatalogItemIds
    /*
     * Keep a modest composition cushion around the visible vertical rows.
     *
     * Held-DPAD scrolling uses the full row renderer, so a small cache helps
     * the next row finish composition before it physically enters the viewport
     * without restoring Enhanced's former large 1.0 / 0.5 retention window.
     */
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    val verticalRowListState = rememberLazyListState(
        cacheWindow =
            androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow(
                aheadFraction = 0.5f,
                behindFraction = 0.25f
            ),
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
    val rowsBoundaryCurrentCarouselRowsState =
        rememberUpdatedState(carouselRows)
    val currentCarouselRows by rowsBoundaryCurrentCarouselRowsState
    val onAtTopChangedUpdated by rememberUpdatedState(onAtTopChanged)


    // Tag JankStats with key UI states so jank reports are actionable.
    val metricsHolder = PerformanceMetricsState.getHolderForHierarchy(LocalView.current)

    LaunchedEffect(isVerticalRowsScrolling) {
        metricsHolder.state?.putState(
            "HomeScrolling",
            isVerticalRowsScrolling.toString()
        )

    }

    /*
     * Held-DPAD input has ended, but the destination card may not have
     * committed real Compose focus yet. Keep Hero/enrichment frozen only
     * until confirmed destination focus.
     */
    val fullyVisibleOverlayAlphaState = remember {
        androidx.compose.runtime.mutableFloatStateOf(1f)
    }

    val rowsBoundaryFastScrollLandingVisualPendingState =
        remember {
            mutableStateOf(false)
        }
    var fastScrollLandingVisualPending by
        rowsBoundaryFastScrollLandingVisualPendingState

    val fastScrollLandingVisualPendingRef = remember {
        java.util.concurrent.atomic.AtomicBoolean(false)
    }

    /*
     * Incremented when the fast-scroll destination has committed focus.
     * A later effect performs the deferred hero/enrichment catch-up once.
     */
    val rowsBoundaryFastScrollHeroCatchUpGenerationState =
        remember {
            mutableIntStateOf(0)
        }
    var fastScrollHeroCatchUpGeneration by
        rowsBoundaryFastScrollHeroCatchUpGenerationState

    LaunchedEffect(enrichingItemId) {
        metricsHolder.state?.putState("HeroEnriching", (enrichingItemId != null).toString())
    }


    /*
     * Backdrop overscan is a property of the canonical top row's content,
     * not of whichever row currently has focus.
     *
     * This set is rebuilt only when carousel row data changes. D-pad focus
     * movement performs no row scans.
     */
    val canonicalTopRowBackdropKeys = remember(carouselRows) {
        buildSet {
            carouselRows
                .firstOrNull()
                ?.items
                ?.forEach { item ->
                    val identity =
                        cinematicBackdropIdentity(item.heroPreview.backdrop)

                    if (identity != null) {
                        add(identity)
                    }
                }
        }
    }

    /*
     * Cache the canonical top row's catalog focus keys alongside its backdrop
     * identities. Horizontal trailer handoff can then identify a same-top-row
     * move with O(1) set membership instead of scanning row contents on D-pad
     * focus changes.
     */
    val canonicalTopRowFocusKeys = remember(carouselRows) {
        buildSet {
            carouselRows
                .firstOrNull()
                ?.items
                ?.forEach { item ->
                    (item.payload as? ModernPayload.Catalog)
                        ?.focusKey
                        ?.let(::add)
                }
        }
    }

    val uiCaches = remember(uiState.homeLoadSessionId) { ModernHomeUiCaches() }
    val focusedItemByRow = uiCaches.focusedItemByRow
    val itemFocusRequesters = uiCaches.itemFocusRequesters
    val rowListStates = uiCaches.rowListStates
    val isAnyRowScrolling by remember(rowListStates) {
        derivedStateOf { rowListStates.values.any { it.isScrollInProgress } }
    }
    val loadMoreRequestedTotals = uiCaches.loadMoreRequestedTotals
    // Holder for hot-path focus tracking — lambdas read through reference, no stale closure
    val focusHolder = remember {
        EnhancedHomeRowsFocusHolder()
    }
    val rowsBoundaryActiveRowKeyState =
        remember { mutableStateOf<String?>(null) }
    var activeRowKey by rowsBoundaryActiveRowKeyState

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
    val rowsBoundaryActiveItemIndexState =
        remember { mutableIntStateOf(0) }
    var activeItemIndex by rowsBoundaryActiveItemIndexState
    val pendingRowFocus = remember { PendingRowFocusHolder() }

    /*
     * My List uses positional LazyRow slots. A new head must invalidate any
     * child slot remembered by the parent focusRestorer (for example slot 5),
     * otherwise that old slot can win when the user re-enters the row.
     *
     * Removals do NOT change this generation, so their same-slot behavior
     * remains untouched.
     */
    var myListSlotGeneration by remember(
        uiState.homeLoadSessionId
    ) {
        mutableIntStateOf(0)
    }

    LaunchedEffect(
        myListHeadResetPending,
        activeRowKey,
        isCarouselFocused,
        carouselRows
    ) {
        if (!myListHeadResetPending) return@LaunchedEffect

        val myListRow =
            carouselRows.firstOrNull {
                it.key == HomeViewModel.MY_LIST_CATALOG_KEY
            } ?: return@LaunchedEffect

        val myListOwnsFocus =
            activeRowKey == myListRow.key &&
                !isCarouselFocused

        if (myListOwnsFocus) return@LaunchedEffect

        uiCaches.lastActuallyFocusedIndexByRow.remove(myListRow.key)
        uiCaches.focusedItemByRow[myListRow.key] = 0
        uiCaches.rowListStates[myListRow.key]?.scrollToItem(0, 0)

        /*
         * The viewport is now explicitly at zero. Re-key My List's positional
         * children so focusRestorer cannot resurrect a previously remembered
         * slot from before the insertion.
         */
        myListSlotGeneration++

        onMyListHeadResetConsumed()
    }

    // When a skeleton row transitions to real content (items arrive or enrichment
    // completes), restore focus to where it was rather than letting Compose move
    // it to another row.
    LaunchedEffect(carouselRows) {
        carouselRows.forEach { row ->
            val prevCount = uiCaches.previousRowItemCounts[row.key] ?: 0
            val nowCount = row.items.size
            val prevReady = uiCaches.previousEnrichmentReadyByRow[row.key] ?: row.enrichmentReady
            val nowReady = row.enrichmentReady
            val transitioned = (prevCount == 0 && nowCount > 0) ||
                (!prevReady && nowReady && nowCount > 0)
            if (transitioned && focusHolder.activeRowKey == row.key) {
                val restoreIndex = (uiCaches.focusedItemByRow[row.key] ?: 0)
                    .coerceIn(0, (nowCount - 1).coerceAtLeast(0))
                pendingRowFocus.key = row.key
                pendingRowFocus.index = restoreIndex
                pendingRowFocus.nonce++
            }
            uiCaches.previousRowItemCounts[row.key] = nowCount
            uiCaches.previousEnrichmentReadyByRow[row.key] = nowReady
        }
    }
    val rowsBoundaryHeroItemState =
        remember { mutableStateOf<HeroPreview?>(null) }
    var heroItem by rowsBoundaryHeroItemState
    val rowsBoundaryHeroItemRowKeyState =
        remember { mutableStateOf<String?>(null) }
    var heroItemRowKey by rowsBoundaryHeroItemRowKeyState

    var lastHandledContinueWatchingOrderKeys by remember(
        uiState.homeLoadSessionId
    ) {
        mutableStateOf(
            focusState.continueWatchingOrderKeys
                .takeIf {
                    focusState.hasSavedFocus &&
                        it.isNotEmpty()
                }
                ?: currentContinueWatchingOrderKeys
        )
    }

    val rowsBoundaryFrozenHeroItemState =
        remember { mutableStateOf<HeroPreview?>(null) }
    var frozenHeroItem by rowsBoundaryFrozenHeroItemState
    val rowsBoundaryFrozenHeroItemRowKeyState =
        remember { mutableStateOf<String?>(null) }
    var frozenHeroItemRowKey by rowsBoundaryFrozenHeroItemRowKeyState
    val rowsBoundaryIsFastScrollingState =
        remember { mutableStateOf(false) }
    var isFastScrolling by rowsBoundaryIsFastScrollingState
    val landingScope = rememberCoroutineScope()
    val heroTransitioningRef = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var restoredFromSavedState by remember { mutableStateOf(false) }
    var lastRestoredRowKey by remember { mutableStateOf<String?>(null) }
    if (focusState.hasSavedFocus && focusState.focusedRowKey != lastRestoredRowKey) {
        restoredFromSavedState = false
    }

    val savedFocusTargetsContinueWatching =
        focusState.focusedRowKey == "continue_watching" ||
            (
                focusState.focusedRowKey == null &&
                    focusState.focusedRowIndex == -1 &&
                    uiState.continueWatchingItems.isNotEmpty()
            )

    val forceContinueWatchingRestoreToStart =
        !restoredFromSavedState &&
            focusState.hasSavedFocus &&
            savedFocusTargetsContinueWatching &&
            focusState.continueWatchingOrderKeys.isNotEmpty() &&
            currentContinueWatchingOrderKeys.isNotEmpty() &&
            focusState.continueWatchingOrderKeys !=
                currentContinueWatchingOrderKeys

    /*
     * PATCH_DETAILS_RETURN_EXACT_FOCUS_PRIORITY
     *
     * Focus identity and horizontal viewport position are separate.
     *
     * If Details is opened while the LazyRow is still settling, the saved
     * first-visible index can legitimately describe the previous card even
     * though Compose focus has already reached the card the user selected.
     *
     * Seed the row's authoritative focus cache synchronously, before its
     * focusRestorer is composed, so the exact selected card outranks the
     * still-moving viewport position on return from Details.
     *
     * Leave the saved LazyRow index/offset untouched: the row may finish its
     * normal visual settlement after return, but focus remains on the card
     * that actually opened Details.
     *
     * Continue Watching's structural-reorder reset remains authoritative.
     */
    if (
        !restoredFromSavedState &&
        focusState.hasSavedFocus &&
        !forceContinueWatchingRestoreToStart
    ) {
        focusState.focusedRowKey?.let { savedRowKey ->
            focusedItemByRow[savedRowKey] =
                focusState.focusedItemIndex
        }
    }

    val rowsBoundaryOptionsItemState =
        remember { mutableStateOf<ContinueWatchingItem?>(null) }
    var optionsItem by rowsBoundaryOptionsItemState
    val lastFocusedContinueWatchingIndexRef = remember { java.util.concurrent.atomic.AtomicInteger(-1) }
    val lastHeroNavigationAtMsRef = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    val heroFocusSettleDelayMsRef = remember { java.util.concurrent.atomic.AtomicLong(MODERN_HERO_FOCUS_DEBOUNCE_MS) }
        val lastKeyRepeatTimeRef = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    val isFastScrollingRef = remember { kotlinx.coroutines.flow.MutableStateFlow(false) }
    val suppressCatalogSelectionForDoubleUpRef =
        remember {
            java.util.concurrent.atomic.AtomicBoolean(false)
        }
    val lastKeyUpTimeRef = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    val rowsBoundaryFocusedCatalogSelectionState =
        remember { mutableStateOf<FocusedCatalogSelection?>(null) }
    var focusedCatalogSelection by
        rowsBoundaryFocusedCatalogSelectionState
    var lastRequestedTrailerFocusKey by remember { mutableStateOf<String?>(null) }
    val rowsBoundaryExpandedCatalogFocusKeyState =
        remember { mutableStateOf<String?>(null) }
    var expandedCatalogFocusKey by
        rowsBoundaryExpandedCatalogFocusKeyState

    /*
     * When Trailer A exits because focus moved horizontally within the
     * canonical top row, the backdrop underneath should switch directly to B.
     * The trailer itself still owns the normal 480 ms visual fade.
     */
    var instantTopRowBackdropSwapFocusKey by remember {
        mutableStateOf<String?>(null)
    }

    /*
     * Trailer ownership remains separate from current focus so an
     * already-playing trailer can survive popup/sidebar overlays.
     */
    var retainedHeroTrailerSelection by remember {
        mutableStateOf<FocusedCatalogSelection?>(null)
    }
    var heroTrailerHoldMuted by remember { mutableStateOf(false) }

    // Patch 8: gate per-landing work (enrichment, preload, selection) during fast scroll.
    // Catch-up effect below re-fires for the settled item when scrolling stops.
    val latestOnItemFocus by rememberUpdatedState(onItemFocus)
    val latestOnPreloadAdjacentItem by rememberUpdatedState(onPreloadAdjacentItem)
    val gatedOnItemFocus: (MetaPreview) -> Unit = remember(Unit) {
        { preview ->
            if (
                !isFastScrollingRef.value &&
                !fastScrollLandingVisualPendingRef.get()
            ) {
                latestOnItemFocus(preview)
            }
        }
    }
    val gatedOnPreloadAdjacentItem: (MetaPreview) -> Unit = remember(Unit) {
        { preview ->
            if (
                !isFastScrollingRef.value &&
                !fastScrollLandingVisualPendingRef.get()
            ) {
                latestOnPreloadAdjacentItem(
                    preview
                )
            }
        }
    }
    val gatedOnCatalogSelectionFocused: (FocusedCatalogSelection) -> Unit = remember(Unit) {
        { selection ->
            if (
                !suppressCatalogSelectionForDoubleUpRef.get() &&
                !isFastScrollingRef.value &&
                !fastScrollLandingVisualPendingRef.get() &&
                focusedCatalogSelection != selection
            ) {
                focusedCatalogSelection =
                    selection
            }
        }
    }
    /*
     * One authoritative fast-scroll catch-up path. Key-up and measured visual
     * settlement cannot run different hero/enrichment logic.
     */
    val catchUpFastScrollLandingHero:
        suspend () -> Unit =
        catchUp@{
            if (
                suppressCatalogSelectionForDoubleUpRef.get()
            ) {
                return@catchUp
            }

            val rowKey = focusHolder.activeRowKey ?: return@catchUp
            val row = currentCarouselRows.firstOrNull { it.key == rowKey } ?: return@catchUp
            val item = row.items.getOrNull(
                focusHolder.activeItemIndex.coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
            ) ?: return@catchUp
            item.metaPreview?.let { latestOnItemFocus(it) }
            val payload = item.payload as? ModernPayload.Catalog
            if (payload != null) {
                val selection = FocusedCatalogSelection(
                    focusKey = payload.focusKey,
                    payload = payload
                )
                if (focusedCatalogSelection != selection) {
                    focusedCatalogSelection = selection
                }
            }
        }

    LaunchedEffect(Unit) {
        isFastScrollingRef
            .collect { fast ->
                if (
                    !fast &&
                    !fastScrollLandingVisualPendingRef.get()
                ) {
                    catchUpFastScrollLandingHero()
                }
            }
    }

    LaunchedEffect(
        fastScrollHeroCatchUpGeneration
    ) {
        if (
            fastScrollHeroCatchUpGeneration == 0
        ) {
            return@LaunchedEffect
        }

        catchUpFastScrollLandingHero()
    }


    // Stop any expanded-card trailer the instant FAST-scrolling begins. Only fast-scroll
    // is affected: the custom fast-scroll modifier moves the scroll position without
    // moving focus until the gesture lands, so without this the previously expanded/
    // playing card's PlayerView (a native View wrapped via AndroidView — draw() is real
    // per-frame CPU work, not free like a Compose recomposition) stays alive and drawing
    // every frame for the entire scroll, well past the point it's off-screen. A single
    // dpad step is NOT touched here — it lands immediately and its own normal focus-
    // change logic already handles starting the trailer on the newly focused item.
    val rowsBoundaryExpansionInteractionNonceState =
        remember { mutableIntStateOf(0) }
    var expansionInteractionNonce by
        rowsBoundaryExpansionInteractionNonceState

    /*
     * Once a playing trailer elects to survive a sidebar session, retain that
     * ownership through the complete exact-focus handoff. Do not infer the
     * handoff from scroll activity: some restores legitimately produce no
     * scroll at all.
     */
    var preserveSidebarTrailerThroughFocusReturn by remember {
        mutableStateOf(false)
    }

    LaunchedEffect(isSidebarExpanded, preserveSidebarTrailerPlayback) {
        if (preserveSidebarTrailerPlayback) {
            preserveSidebarTrailerThroughFocusReturn = true
        } else if (isSidebarExpanded) {
            preserveSidebarTrailerThroughFocusReturn = false
        }
    }

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
        isVerticalRowsScrolling,
        isSidebarExpanded,
        preserveSidebarTrailerPlayback,
        sidebarFocusRestoreActive,
        suppressFocusedPosterAutoplayForOptions
    ) {
        /*
         * Sidebar time must not count toward the trailer delay. If a trailer
         * was already actively playing when the translucent sidebar opened,
         * leave the expanded card/player untouched so playback remains visible
         * beneath it. Otherwise retain the old behavior: suppress activation
         * for the sidebar's entire lifetime and restart the full delay after
         * focus returns to Home.
         */
        /*
         * Preserve the exact same expanded trailer through the entire Legacy
         * sidebar handoff. LocalSidebarFocusRestoreActive is authoritative;
         * vertical-scroll state is only an additional settle signal, not the
         * thing that decides whether a restoration happened.
         */
        val sameSidebarTrailerOwner =
            expandedCatalogFocusKey != null &&
                focusedCatalogSelection?.focusKey ==
                    expandedCatalogFocusKey

        if (
            sameSidebarTrailerOwner &&
            (
                preserveSidebarTrailerPlayback ||
                    preserveSidebarTrailerThroughFocusReturn
            )
        ) {
            if (
                isSidebarExpanded ||
                preserveSidebarTrailerPlayback ||
                sidebarFocusRestoreActive ||
                isVerticalRowsScrolling
            ) {
                return@LaunchedEffect
            }

            /*
             * The drawer is gone, exact focus has returned, and any vertical
             * layout settlement has finished. Disarm the temporary latch but
             * preserve this final effect pass too, so ownership transfers back
             * to normal autoplay without a one-frame teardown.
             */
            preserveSidebarTrailerThroughFocusReturn = false
            return@LaunchedEffect
        }

        if (isSidebarExpanded) {
            if (preserveSidebarTrailerPlayback) {
                return@LaunchedEffect
            }

            expandedCatalogFocusKey = null
            retainedHeroTrailerSelection = null
            heroTrailerHoldMuted = false

            sharedTrailerPlayer?.let { player ->
                player.volume = 0f
            }

            return@LaunchedEffect
        }

        /*
         * Already-playing trailer:
         *
         * Popup focus is temporarily owned by a separate Dialog window. Keep
         * the existing expansion/trailer owner intact while that popup is open
         * and through exact-focus restoration.
         *
         * This check is deliberately NOT a LaunchedEffect key. Ending the tiny
         * restore phase must not itself restart the autoplay lifecycle.
         */
        if (
            preserveFocusedPosterPlaybackForOptions &&
            expandedCatalogFocusKey != null
        ) {
            return@LaunchedEffect
        }

        /*
         * Popup opened BEFORE playback:
         *
         * Collapse/suppress immediately. Because
         * suppressFocusedPosterAutoplayForOptions is already an effect key,
         * closing the popup relaunches this effect and starts the user's FULL
         * configured delay again.
         */
        if (suppressFocusedPosterAutoplayForOptions) {
            expandedCatalogFocusKey = null
            return@LaunchedEffect
        }

        val outgoingTrailerSelection =
            retainedHeroTrailerSelection
        val incomingSelection =
            focusedCatalogSelection

        /*
         * Official-beta-style trailer ownership:
         *
         * Destination backdrop changes are handled by the normal settled Hero
         * pipeline. Do not preload Backdrop B underneath Trailer A and do not
         * wait for a backdrop-ready callback before releasing A.
         *
         * Retained ownership remains only for:
         * - overlay preservation
         * - vertical focus movement before the destination has actually landed
         * - same-title ownership transfer between rows
         */
        val verticalMoveHasNotLanded =
            isVerticalRowsScrolling &&
                outgoingTrailerSelection != null &&
                (
                    incomingSelection == null ||
                        outgoingTrailerSelection.focusKey ==
                            incomingSelection.focusKey
                    )

        val navigationReachedNewSelection =
            trailerPlaybackTarget ==
                FocusedPosterTrailerPlaybackTarget.HERO_MEDIA &&
                outgoingTrailerSelection != null &&
                incomingSelection != null &&
                outgoingTrailerSelection.focusKey !=
                    incomingSelection.focusKey

        val navigationReachedSameItem =
            navigationReachedNewSelection &&
                outgoingTrailerSelection?.payload?.itemId ==
                    incomingSelection?.payload?.itemId

        when {
            verticalMoveHasNotLanded -> {
                heroTrailerHoldMuted = false
            }

            navigationReachedSameItem -> {
                retainedHeroTrailerSelection = incomingSelection
                heroTrailerHoldMuted = false
            }

            navigationReachedNewSelection -> {
                val outgoingFocusKey =
                    outgoingTrailerSelection?.focusKey
                val incomingFocusKey =
                    incomingSelection?.focusKey

                val outgoingWasTopRow =
                    outgoingFocusKey?.let {
                        it in canonicalTopRowFocusKeys
                    } == true
                val incomingIsTopRow =
                    incomingFocusKey?.let {
                        it in canonicalTopRowFocusKeys
                    } == true

                instantTopRowBackdropSwapFocusKey =
                    if (outgoingWasTopRow && incomingIsTopRow) {
                        incomingFocusKey
                    } else {
                        null
                    }

                retainedHeroTrailerSelection = null
                heroTrailerHoldMuted = false
            }

            outgoingTrailerSelection != null &&
                incomingSelection == null -> {
                retainedHeroTrailerSelection = null
                heroTrailerHoldMuted = false
            }
        }

        expandedCatalogFocusKey = null
        if (!shouldActivateFocusedPosterFlow) return@LaunchedEffect
        if (isVerticalRowsScrolling) return@LaunchedEffect

        val selection = focusedCatalogSelection ?: return@LaunchedEffect
        delay(uiState.focusedPosterBackdropExpandDelaySeconds.coerceAtLeast(0) * 1000L)

        /*
         * Do not trust only the value captured when this coroutine launched.
         * The popup may have opened while delay() was in progress.
         */
        if (latestSuppressFocusedPosterAutoplayForOptions.value) {
            expandedCatalogFocusKey = null
            return@LaunchedEffect
        }

        if (shouldActivateFocusedPosterFlow &&
            !isVerticalRowsScrolling &&
            focusedCatalogSelection?.focusKey == selection.focusKey
        ) {
            expandedCatalogFocusKey = selection.focusKey

            if (
                trailerPlaybackTarget ==
                    FocusedPosterTrailerPlaybackTarget.HERO_MEDIA
            ) {
                retainedHeroTrailerSelection = selection
                sharedTrailerPlayer?.let { player ->
                    player.volume =
                        if (
                            uiState
                                .focusedPosterBackdropTrailerMuted
                        ) {
                            0f
                        } else {
                            1f
                        }
                }
                heroTrailerHoldMuted = false
            }
        }
    }

    LaunchedEffect(
        focusedCatalogSelection?.focusKey,
        effectiveAutoplayEnabled,
        isVerticalRowsScrolling,
        isSidebarExpanded
    ) {
        /*
         * Cancel any pending network resolution while the sidebar is open.
         * Closing it starts a fresh request delay when one is still needed.
         */
        if (isSidebarExpanded) {
            return@LaunchedEffect
        }

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
        /*
         * Resolve independently of the user's autoplay/expansion delay.
         *
         * A short stable-focus guard prevents transient navigation from
         * starting trailer work, while the separate playback effect above
         * continues to honor the user's configured 1/2/3/... second delay.
         */
        delay(150L)
        if (focusedCatalogSelection?.focusKey != selection.focusKey) {
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

        // A platform switch can temporarily remove a row such as My List.
        // Keep only its tiny authoritative real-focus index when the user has
        // actually moved within that row. This prevents an older Details
        // navigation snapshot from becoming authoritative again when the row
        // returns, while still pruning all heavier row state below.
        uiCaches.lastActuallyFocusedIndexByRow.keys.removeAll { rowKey ->
            rowKey !in activeRowKeys &&
                rowKey !in uiCaches.userInteractedRows
        }

        itemFocusRequesters.keys.retainAll(activeRowKeys)
        rowListStates.keys.retainAll(activeRowKeys)
        loadMoreRequestedTotals.keys.retainAll(activeRowKeys)
        carouselRows.forEach { row ->
            val rowRequesters = itemFocusRequesters[row.key] ?: return@forEach
            val allowedKeys = activeItemKeysByRow[row.key] ?: emptySet()
            rowRequesters.keys.retainAll(allowedKeys)
        }

        // Only clear focused selection if the entire row is gone, not just because
        // the item isn't in activeCatalogItemIds yet (e.g. paginated items beyond #25
        // aren't in the truncated row until loadMore fires).
        if (focusedCatalogSelection?.payload?.itemId !in activeCatalogItemIds) {
            val focusedItemId = focusedCatalogSelection?.payload?.itemId
            val itemStillExistsInAnyRow = focusedItemId != null && carouselRows.any { row ->
                row.items.any { item ->
                    (item.payload as? ModernPayload.Catalog)?.itemId == focusedItemId
                }
            }
            if (!itemStillExistsInAnyRow) {
                focusedCatalogSelection = null
                expandedCatalogFocusKey = null
            }
        }

        carouselRows.forEach { row ->
            if (row.items.isNotEmpty() && row.key !in focusedItemByRow) {
                focusedItemByRow[row.key] = 0
            }

            val currentItemKeys =
                orderedItemKeysByRow[row.key] ?: emptyList()
            val previousItemKeys = uiCaches.previousItemKeysByRow.put(
                row.key,
                currentItemKeys
            )
            val rowOwnsFocus =
                focusHolder.activeRowKey == row.key &&
                    !isCarouselFocused

            val sameItemsReordered =
                previousItemKeys != null &&
                    previousItemKeys.size == currentItemKeys.size &&
                    previousItemKeys != currentItemKeys &&
                    previousItemKeys.toSet() == currentItemKeys.toSet()

            if (
                sameItemsReordered &&
                row.key != "continue_watching" &&
                row.items.isNotEmpty()
            ) {
                val keepIndex = (
                    focusedItemByRow[row.key]
                        ?: if (rowOwnsFocus) {
                            focusHolder.activeItemIndex
                        } else {
                            0
                        }
                ).coerceIn(0, row.items.lastIndex)

                if (rowOwnsFocus) {
                    /*
                     * If the row currently owns focus, preserve the existing
                     * atomic focus handoff. Bring-into-view remains suppressed
                     * so the reorder itself cannot animate the row.
                     */
                    focusHolder.activeItemIndex = keepIndex
                    activeItemIndex = keepIndex
                    focusedItemByRow[row.key] = keepIndex

                    pendingRowFocus.key = row.key
                    pendingRowFocus.index = keepIndex
                    pendingRowFocus.suppressBringIntoView = true
                    pendingRowFocus.nonce++
                } else {
                    /*
                     * LazyRow normally preserves its visible item by stable key
                     * when the same items are reordered. Shuffle is
                     * position-semantic, so keep the remembered numeric index.
                     *
                     * Override the old key anchor while the row is inactive so
                     * it is already positioned correctly before focus enters.
                     */
                    rowListStates[row.key]
                        ?.requestScrollToItem(keepIndex, 0)
                }
            }
        }

        uiCaches.previousItemKeysByRow.keys.retainAll(activeRowKeys)

        if (!restoredFromSavedState && focusState.hasSavedFocus) {
            val savedRowKey = when {
                focusState.focusedRowKey != null -> focusState.focusedRowKey
                focusState.focusedRowIndex == -1 && uiState.continueWatchingItems.isNotEmpty() -> "continue_watching"
                focusState.focusedRowIndex >= 0 -> visibleCatalogRows.getOrNull(focusState.focusedRowIndex)?.let { catalogRowKey(it) }
                else -> null
            }

            val resolvedRow =
                carouselRows.firstOrNull {
                    it.key == savedRowKey
                } ?: carouselRows.first()

            val continueWatchingChangedSinceSave =
                resolvedRow.key == "continue_watching" &&
                    focusState.continueWatchingOrderKeys.isNotEmpty() &&
                    currentContinueWatchingOrderKeys.isNotEmpty() &&
                    focusState.continueWatchingOrderKeys !=
                        currentContinueWatchingOrderKeys

            val resolvedIndex =
                if (continueWatchingChangedSinceSave) {
                    0
                } else {
                    focusState.focusedItemIndex
                        .coerceAtLeast(0)
                        .coerceAtMost(
                            (resolvedRow.items.size - 1)
                                .coerceAtLeast(0)
                        )
                }

            if (continueWatchingChangedSinceSave) {
                /*
                 * The row-level focusRestorer gives the last real focused
                 * index higher priority than the general focus cache.
                 * A CW reorder is explicitly position-semantic, so discard
                 * that pre-navigation slot before restoring index 0.
                 */
                uiCaches.lastActuallyFocusedIndexByRow.remove(
                    resolvedRow.key
                )

                rowListStates[resolvedRow.key]
                    ?.scrollToItem(0, 0)
                lastHandledContinueWatchingOrderKeys =
                    currentContinueWatchingOrderKeys
            }

            focusHolder.activeRowKey = resolvedRow.key
            focusHolder.activeItemIndex = resolvedIndex
            activeRowKey = resolvedRow.key
            activeItemIndex = resolvedIndex
            focusedItemByRow[resolvedRow.key] = resolvedIndex
            heroItem =
                resolvedRow.items
                    .getOrNull(resolvedIndex)
                    ?.heroPreview
                    ?: resolvedRow.items
                        .firstOrNull()
                        ?.heroPreview
            heroItemRowKey = resolvedRow.key

            pendingRowFocus.key = resolvedRow.key
            pendingRowFocus.index = resolvedIndex

            /*
             * PATCH_DETAILS_RETURN_SUPPRESS_BRING_INTO_VIEW
             *
             * The restored card already has an authoritative saved focus
             * target. Do not let requesting that focus start or resume a
             * horizontal bring-into-view animation while Home is becoming
             * visible again.
             */
            pendingRowFocus.suppressBringIntoView = true
            pendingRowFocus.nonce++
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
        val resolvedHeroPreview = resolvedActive.items.getOrNull(resolvedIndex)?.heroPreview
            ?: resolvedActive.items.firstOrNull()?.heroPreview
        heroItem = resolvedHeroPreview
        heroItemRowKey = resolvedActive.key

        // If the resolved hero item has no badge data yet, trigger enrichment immediately
        val resolvedMetaPreview = resolvedActive.items.getOrNull(resolvedIndex)?.metaPreview
        if (resolvedMetaPreview != null && resolvedHeroPreview?.ageRatingText == null) {
            onItemFocus(resolvedMetaPreview)
        }
        if (!focusState.hasSavedFocus && (!hadActiveRow || existingActive == null) && !isCarouselFocused) {
            pendingRowFocus.key = resolvedActive.key
            pendingRowFocus.index = resolvedIndex
            pendingRowFocus.nonce++
        }
    }

    /*
     * Continue Watching resets to its first item after a structural change.
     * Index 0 is the newest / most recently watched item.
     *
     * This effect is keyed only by CW's stable title sequence. Metadata,
     * progress, hero enrichment, and unrelated Home emissions do not run it.
     */
    LaunchedEffect(currentContinueWatchingOrderKeys) {
        val previousKeys =
            lastHandledContinueWatchingOrderKeys

        if (
            previousKeys.isEmpty() ||
            currentContinueWatchingOrderKeys.isEmpty()
        ) {
            lastHandledContinueWatchingOrderKeys =
                currentContinueWatchingOrderKeys
            return@LaunchedEffect
        }

        if (
            previousKeys ==
                currentContinueWatchingOrderKeys
        ) {
            return@LaunchedEffect
        }

        lastHandledContinueWatchingOrderKeys =
            currentContinueWatchingOrderKeys

        val continueWatchingRow =
            carouselRows.firstOrNull {
                it.key == "continue_watching"
            } ?: return@LaunchedEffect

        if (continueWatchingRow.items.isEmpty()) {
            return@LaunchedEffect
        }

        val targetIndex = 0

        uiCaches.lastActuallyFocusedIndexByRow.remove(
            continueWatchingRow.key
        )
        focusedItemByRow[
            continueWatchingRow.key
        ] = targetIndex

        rowListStates[
            continueWatchingRow.key
        ]?.scrollToItem(0, 0)

        val continueWatchingOwnsFocus =
            focusHolder.activeRowKey ==
                continueWatchingRow.key &&
                !isCarouselFocused

        if (continueWatchingOwnsFocus) {
            focusHolder.activeItemIndex =
                targetIndex
            activeItemIndex =
                targetIndex

            val landingPreview =
                continueWatchingRow.items[
                    targetIndex
                ].heroPreview

            heroItem = landingPreview
            heroItemRowKey =
                continueWatchingRow.key

            pendingRowFocus.key =
                continueWatchingRow.key
            pendingRowFocus.index =
                targetIndex
            pendingRowFocus.suppressBringIntoView =
                false
            pendingRowFocus.nonce++
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

    val activeRow by remember(carouselRows, rowByKey) {
        derivedStateOf {
            val activeKey = activeRowKey
            if (activeKey == null) {
                null
            } else {
                rowByKey[activeKey] ?: carouselRows.firstOrNull()
            }
        }
    }
    val clampedActiveItemIndex by remember(carouselRows, rowByKey) {
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
    val latestLandscapeCatalogKeys by rememberUpdatedState(uiState.landscapeCatalogKeys)
    val latestUseLandscapePosters by rememberUpdatedState(useLandscapePosters)
    val latestPosterCardWidthDp by rememberUpdatedState(uiState.posterCardWidthDp)
    val latestPosterCardHeightDp by rememberUpdatedState(uiState.posterCardHeightDp)
    val latestEffectiveExpandEnabled by rememberUpdatedState(effectiveExpandEnabled)

    /*
     * Directional image prefetch.
     *
     * Keep image work off the active vertical-scroll path.
     *
     * Once navigation has been idle for 240 ms, warm only the remembered
     * destination card in the single row immediately ahead of the user's
     * most recent vertical direction.
     *
     * This intentionally replaces the former four-row / six-card-per-row
     * warmup. Same-row metadata preloading remains a separate path.
     */
    LaunchedEffect(Unit) {
        var previousSettledRowIndex: Int? = null

        kotlinx.coroutines.flow.combine(
            snapshotFlow {
                Pair(
                    activeRowKey,
                    latestCarouselRows
                )
            },
            isFastScrollingRef,
            snapshotFlow {
                verticalRowListState.isScrollInProgress
            }
        ) { pair, fastScrolling, verticalScrolling ->
            Triple(
                pair,
                fastScrolling,
                verticalScrolling
            )
        }
            .debounce(240L)
            .collectLatest {
                (pair, fastScrolling, verticalScrolling) ->

                if (
                    fastScrolling ||
                    verticalScrolling
                ) {
                    return@collectLatest
                }

                val (rowKey, rows) =
                    pair

                if (rowKey == null) {
                    return@collectLatest
                }

                val focusedRowIndex =
                    rows.indexOfFirst {
                        it.key == rowKey
                    }

                if (focusedRowIndex < 0) {
                    return@collectLatest
                }

                val previousIndex =
                    previousSettledRowIndex

                val direction =
                    when {
                        previousIndex == null ->
                            1

                        focusedRowIndex > previousIndex ->
                            1

                        focusedRowIndex < previousIndex ->
                            -1

                        else ->
                            1
                    }

                previousSettledRowIndex =
                    focusedRowIndex

                val targetRow =
                    rows.getOrNull(
                        focusedRowIndex + direction
                    )
                        ?: return@collectLatest

                /*
                 * Continue Watching has its own specialized card/image
                 * behavior. Preserve the previous exclusion here.
                 */
                if (
                    targetRow.key ==
                        "continue_watching" ||
                    targetRow.items.isEmpty()
                ) {
                    return@collectLatest
                }

                val rememberedIndex =
                    (
                        uiCaches
                            .lastActuallyFocusedIndexByRow[
                                targetRow.key
                            ]
                            ?: uiCaches
                                .focusedItemByRow[
                                    targetRow.key
                                ]
                            ?: 0
                    ).coerceIn(
                        0,
                        (
                            targetRow.items.size - 1
                        ).coerceAtLeast(0)
                    )

                val targetItem =
                    targetRow.items
                        .getOrNull(rememberedIndex)
                        ?: return@collectLatest

                val imageUrl =
                    targetItem.imageUrl
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?: return@collectLatest

                val portraitWidth =
                    latestPosterCardWidthDp.dp

                val portraitHeight =
                    latestPosterCardHeightDp.dp

                if (
                    portraitWidth <= 0.dp ||
                    portraitHeight <= 0.dp
                ) {
                    return@collectLatest
                }

                val rowLandscape =
                    latestUseLandscapePosters ||
                        targetRow.key in
                            latestLandscapeCatalogKeys

                val cardWidth =
                    if (rowLandscape) {
                        portraitWidth *
                            1.24f *
                            1.34f
                    } else {
                        portraitWidth *
                            0.84f *
                            1.08f
                    }

                val cardHeight =
                    if (rowLandscape) {
                        cardWidth / 1.77f
                    } else {
                        portraitHeight *
                            0.84f *
                            1.08f
                    }

                /*
                 * Match ModernPosterCard's actual image request size.
                 */
                val requestWidth =
                    if (
                        latestEffectiveExpandEnabled
                    ) {
                        maxOf(
                            cardWidth,
                            cardHeight *
                                (16f / 9f)
                        )
                    } else {
                        cardWidth
                    }

                val requestWidthPx =
                    with(density) {
                        requestWidth.roundToPx()
                    }

                val requestHeightPx =
                    with(density) {
                        cardHeight.roundToPx()
                    }

                val imageLoader =
                    coil.Coil.imageLoader(context)

                runCatching {
                    imageLoader.execute(
                        coil.request.ImageRequest
                            .Builder(context)
                            .data(imageUrl)
                            .crossfade(false)
                            .size(
                                width =
                                    requestWidthPx,
                                height =
                                    requestHeightPx
                            )
                            .memoryCachePolicy(
                                coil.request
                                    .CachePolicy
                                    .ENABLED
                            )
                            .build()
                    )
                }
            }
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.flow.combine(
            // Key on raw primitive state (String?/Int), NOT the derived activeRow /
            // clampedActiveItemIndex objects: enrichment rebuilds recreate those
            // remember{derivedStateOf} instances, and snapshotFlow's subscription
            // dies with the old instance — the flow goes deaf until unrelated
            // traffic revives it (hero stuck on rapid nav within the enrichment
            // window). Primitives survive rebuilds; the body resolves the row.
            snapshotFlow { Pair(activeRowKey, activeItemIndex) },
            isFastScrollingRef
        ) { pair, scrolling -> Pair(pair, scrolling) }
            .debounce {
                heroFocusSettleDelayMsRef.get()
            }
            .collect { (pair, isScrolling) ->
                // collect, NOT collectLatest: with collectLatest, enrichment rebuilds
                // (which churn activeRow's identity) cancel the body mid-execution and
                // strand a stale heroItem — the hero sticks until unrelated traffic
                // rescues it. The body is a few map lookups and two state writes; it
                // must run to completion for every surviving debounced emission.
                if (isScrolling) return@collect
                if (heroTransitioningRef.get()) return@collect
                val (rowKey, index) = pair
                if (rowKey == null) return@collect
                // Read from latestCarouselRows so enrichment updates are picked up
                // without putting carouselRows in the flow (which caused debounce stomping)
                val currentRow = latestCarouselRows.firstOrNull { it.key == rowKey } ?: return@collect
                val clamped = index.coerceIn(0, (currentRow.items.size - 1).coerceAtLeast(0))
                val hero = currentRow.items.getOrNull(clamped)?.heroPreview
                if (hero == null) return@collect
                heroItem = hero
                heroItemRowKey = currentRow.key
            }
    }
    /*
     * Landscape metadata preloading is handled by the shared ViewModel
     * viewport scheduler. Avoid walking up to 50 items per row here.
     */

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
    LaunchedEffect(isFastScrolling) {
        if (isFastScrolling) {
            expandedCatalogFocusKey = null
            focusedCatalogSelection = null
        }
    }

    /*
     * Freeze the current stable Hero only while an ordinary vertical
     * LazyColumn movement is in progress. Once movement stops, release
     * immediately to the live destination Hero.
     *
     * Held-DPAD fast scrolling remains independently protected by
     * isFastScrolling / fastScrollLandingVisualPending, while rapid
     * horizontal navigation uses heroFrozenForRapidNav.
     */
    val rowsBoundaryHeroFrozenForRapidNavState =
        remember { mutableStateOf(false) }

    var heroFrozenForRapidNav by
        rowsBoundaryHeroFrozenForRapidNavState

    val rowsBoundaryHeroFrozenForSlideState =
        remember { mutableStateOf(false) }

    var heroFrozenForSlide by
        rowsBoundaryHeroFrozenForSlideState

    LaunchedEffect(verticalRowListState) {
        snapshotFlow {
            verticalRowListState.isScrollInProgress
        }
            .collectLatest { sliding ->
                if (sliding) {
                    if (
                        !heroFrozenForSlide &&
                        !isFastScrolling &&
                        !fastScrollLandingVisualPendingRef.get() &&
                        !heroFrozenForRapidNav
                    ) {
                        val currentRow =
                            latestActiveRow

                        val currentIndex =
                            latestActiveItemIndex

                        frozenHeroItem =
                            currentRow
                                ?.items
                                ?.getOrNull(currentIndex)
                                ?.heroPreview
                                ?: heroItem

                        frozenHeroItemRowKey =
                            currentRow?.key
                                ?: heroItemRowKey
                    }

                    heroFrozenForSlide =
                        true

                    return@collectLatest
                }

                /*
                 * activeCarouselItem is already the live presentation
                 * source used by resolvedHero, so release immediately.
                 */
                heroFrozenForSlide =
                    false
            }
    }

    // Rapid-nav hero freeze (horizontal analog of the slide freeze): while presses
    // arrive faster than the rapid threshold, hold the hero; one atomic update
    // fires after input settles. Single deliberate presses stay instant.
    val lastHeroFocusChangeAtMsRef = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    LaunchedEffect(heroFrozenForRapidNav) {
        if (!heroFrozenForRapidNav) {
            return@LaunchedEffect
        }

        while (true) {
            val settleDelayMs =
                heroFocusSettleDelayMsRef.get()
            val since =
                System.currentTimeMillis() -
                    lastHeroFocusChangeAtMsRef.get()

            if (since >= settleDelayMs) {
                break
            }

            delay(settleDelayMs - since)
        }

        heroFrozenForRapidNav = false
    }

    // Save focus state immediately before navigating away so it's available on back.
    // Once this explicit snapshot is taken, do not let the later disposal fallback
    // overwrite it with focus/scroll state mutated during the outgoing nav fade.
    val focusSnapshotSavedForNavigation = remember {
        java.util.concurrent.atomic.AtomicBoolean(false)
    }

    /*
     * PATCH_DETAILS_RETURN_FINAL_VIEWPORT_SNAPSHOT
     *
     * Navigation identity is frozen at Select time, while the horizontal
     * viewport is allowed to finish settling during the outgoing transition.
     * Disposal will refresh only the viewport coordinates while retaining
     * these authoritative navigation values.
     */
    val navigationSnapshotVerticalIndexRef = remember {
        java.util.concurrent.atomic.AtomicInteger(0)
    }
    val navigationSnapshotVerticalOffsetRef = remember {
        java.util.concurrent.atomic.AtomicInteger(0)
    }
    val navigationSnapshotFocusedRowIndexRef = remember {
        java.util.concurrent.atomic.AtomicInteger(0)
    }
    val navigationSnapshotFocusedItemIndexRef = remember {
        java.util.concurrent.atomic.AtomicInteger(0)
    }
    val navigationSnapshotFocusedRowKeyRef = remember {
        java.util.concurrent.atomic.AtomicReference<String?>(null)
    }
    val navigationSnapshotPlatformIdRef = remember {
        java.util.concurrent.atomic.AtomicReference("home")
    }

    val latestSelectedPlatformId by rememberUpdatedState(selectedPlatformId)
    val wrappedOnNavigateToDetail: (String, String, String) -> Unit = remember(onNavigateToDetail, onSaveFocusState) {
        { itemId, itemType, addonBaseUrl ->
            val row = latestActiveRow
            val focusedRowIndex = row?.globalRowIndex ?: 0
            val focusedRowKey = row?.key
            val catalogRowScrollStates = buildMap {
                latestCarouselRows
                    .filter {
                        it.globalRowIndex >= 0 ||
                            it.key == "continue_watching"
                    }
                    .forEach { rowState ->
                        val listState = rowListStates[rowState.key]
                        put(
                            rowState.key,
                            listState?.firstVisibleItemIndex
                                ?: focusedItemByRow[rowState.key]
                                ?: 0
                        )
                        put(
                            "${rowState.key}::offset",
                            listState?.firstVisibleItemScrollOffset ?: 0
                        )
                    }
            }
            val navigationVerticalIndex =
                latestVerticalRowListState.firstVisibleItemIndex
            val navigationVerticalOffset =
                latestVerticalRowListState.firstVisibleItemScrollOffset
            val navigationFocusedItemIndex =
                latestActiveItemIndex

            navigationSnapshotVerticalIndexRef.set(
                navigationVerticalIndex
            )
            navigationSnapshotVerticalOffsetRef.set(
                navigationVerticalOffset
            )
            navigationSnapshotFocusedRowIndexRef.set(
                focusedRowIndex
            )
            navigationSnapshotFocusedItemIndexRef.set(
                navigationFocusedItemIndex
            )
            navigationSnapshotFocusedRowKeyRef.set(
                focusedRowKey
            )
            navigationSnapshotPlatformIdRef.set(
                latestSelectedPlatformId
            )

            onSaveFocusState(
                navigationVerticalIndex,
                navigationVerticalOffset,
                focusedRowIndex,
                navigationFocusedItemIndex,
                catalogRowScrollStates,
                focusedRowKey,
                latestSelectedPlatformId
            )
            focusSnapshotSavedForNavigation.set(true)
            onNavigateToDetail(itemId, itemType, addonBaseUrl)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            /*
             * If Details navigation already captured focus identity, preserve
             * that identity exactly. The LazyRow is nevertheless allowed to
             * keep settling during the outgoing NavHost transition, so refresh
             * its viewport coordinates below at the later disposal point.
             *
             * For every other disposal path, preserve the existing behavior.
             */
            val navigationSnapshotSaved =
                focusSnapshotSavedForNavigation.get()

            val row = latestActiveRow

            val focusedRowIndex =
                if (navigationSnapshotSaved) {
                    navigationSnapshotFocusedRowIndexRef.get()
                } else {
                    row?.globalRowIndex ?: 0
                }

            val focusedRowKey =
                if (navigationSnapshotSaved) {
                    navigationSnapshotFocusedRowKeyRef.get()
                } else {
                    row?.key
                }

            val focusedItemIndex =
                if (navigationSnapshotSaved) {
                    navigationSnapshotFocusedItemIndexRef.get()
                } else {
                    latestActiveItemIndex
                }

            val verticalScrollIndex =
                if (navigationSnapshotSaved) {
                    navigationSnapshotVerticalIndexRef.get()
                } else {
                    latestVerticalRowListState.firstVisibleItemIndex
                }

            val verticalScrollOffset =
                if (navigationSnapshotSaved) {
                    navigationSnapshotVerticalOffsetRef.get()
                } else {
                    latestVerticalRowListState.firstVisibleItemScrollOffset
                }

            val savedPlatformId =
                if (navigationSnapshotSaved) {
                    navigationSnapshotPlatformIdRef.get()
                } else {
                    latestSelectedPlatformId
                }
            val catalogRowScrollStates = buildMap {
                latestCarouselRows
                    .filter {
                        it.globalRowIndex >= 0 ||
                            it.key == "continue_watching"
                    }
                    .forEach { rowState ->
                        val listState = rowListStates[rowState.key]
                        put(
                            rowState.key,
                            listState?.firstVisibleItemIndex
                                ?: focusedItemByRow[rowState.key]
                                ?: 0
                        )
                        put(
                            "${rowState.key}::offset",
                            listState?.firstVisibleItemScrollOffset ?: 0
                        )
                    }
            }

            onSaveFocusState(
                verticalScrollIndex,
                verticalScrollOffset,
                focusedRowIndex,
                focusedItemIndex,
                catalogRowScrollStates,
                focusedRowKey,
                savedPlatformId
            )
        }
    }

    // posterCardWidthDp starts at 0 until layout prefs pipeline fires — skip
    // rendering card-size-dependent UI until the real value arrives to avoid
    // a brief resize flash on cold launch.
    if (uiState.posterCardWidthDp == 0 || uiState.posterCardHeightDp == 0) return

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
    // CW poster style uses the same portrait geometry as Modern catalog posters.
    val portraitCatalogCardWidth =
        portraitBaseWidth * 0.84f * 1.08f
    val portraitCatalogCardHeight =
        portraitBaseHeight * 0.84f * 1.08f

    val continueWatchingScale = 1.34f
    val continueWatchingCardWidth =
        when (uiState.continueWatchingCardStyle) {
            ContinueWatchingCardStyle.POSTER ->
                portraitCatalogCardWidth

            ContinueWatchingCardStyle.WIDE ->
                portraitBaseWidth * 2.1f

            ContinueWatchingCardStyle.CARD ->
                portraitBaseWidth * 1.24f * continueWatchingScale
        }

    val continueWatchingCardHeight =
        when (uiState.continueWatchingCardStyle) {
            ContinueWatchingCardStyle.POSTER ->
                portraitCatalogCardHeight

            ContinueWatchingCardStyle.WIDE ->
                continueWatchingCardWidth * 0.4f

            ContinueWatchingCardStyle.CARD ->
                continueWatchingCardWidth / 1.77f
        }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val posterCardCornerRadius = remember(uiState.posterCardCornerRadiusDp) { uiState.posterCardCornerRadiusDp.dp }
        val rowHorizontalPadding = 52.dp

        // Use lastRealActiveRow when the active row is a skeleton (no items) so
        // heroBackdrop and heroItem persist from the last real focused item rather
        // than going blank while the user is parked on a loading row.
        val effectiveActiveRow = if ((activeRow?.items?.isEmpty() == true)) {
            carouselRows.lastOrNull { it.key != activeRow?.key && it.items.isNotEmpty() && it.enrichmentReady }
                ?: activeRow
        } else activeRow
        val activeCarouselItem = remember(effectiveActiveRow, clampedActiveItemIndex) {
            effectiveActiveRow?.items?.getOrNull(clampedActiveItemIndex)
                ?: effectiveActiveRow?.items?.firstOrNull()
        }
        val activeItemId = activeCarouselItem?.metaPreview?.id
        val enrichmentActive = enrichingItemId != null && enrichingItemId == activeItemId
        /*
         * Visible Hero presentation follows normal horizontal focus immediately,
         * matching the backdrop path and current official beta behavior.
         *
         * The settled/debounced heroItem remains useful for navigation protection,
         * but it must not delay metadata/logo changes after a deliberate left/right
         * move.
         *
         * Existing freeze modes remain authoritative:
         * - rapid / held horizontal D-pad
         * - fast vertical scrolling / landing
         * - normal vertical row slide
         *
         * During those states the previous stable Hero may remain on screen until
         * navigation settles, preserving the no-jank behavior.
         */
        val resolvedHero =
            if (
                isFastScrolling ||
                fastScrollLandingVisualPending ||
                heroFrozenForSlide ||
                heroFrozenForRapidNav
            ) {
                frozenHeroItem
                    ?: heroItem
                    ?: activeCarouselItem?.heroPreview
            } else {
                activeCarouselItem?.heroPreview
                    ?: heroItem
            }

        /*
         * Backdrop and visible Hero metadata intentionally share the same
         * presentation source:
         *
         * - normal horizontal focus -> current item immediately
         * - rapid/held D-pad        -> frozen Hero
         * - vertical movement       -> frozen Hero
         */
        val visualHero = resolvedHero

        // transitionHero: non-null during platform transition, blocks live resolvedHero updates.
        var isPlatformTransitioning by remember { mutableStateOf(false) }
        LaunchedEffect(isPlatformTransitioning) {
            heroTransitioningRef.set(isPlatformTransitioning)
        }
        // Inject cached MDB ratings into the hero preview when home screen ratings are enabled

        val activeRowFallbackBackdrop = remember(activeRow?.key, activeRow?.items?.size) {
            activeRow?.items?.firstNotNullOfOrNull { item ->
                item.heroPreview.backdrop?.takeIf { it.isNotBlank() }
            }
        }
        // Retains the last non-blank hero backdrop across recompositions so that a
        // momentary focus gap (e.g. returning from a detail screen) does NOT fall
        // back to the first row's item, which caused a stale-looking flash of the
        // wrong backdrop before real focus restored.
        val lastGoodBackdrop = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
        val lastGoodBackdropIsPosterFallback =
            androidx.compose.runtime.saveable.rememberSaveable {
                androidx.compose.runtime.mutableStateOf(false)
            }

        /*
         * Keep portrait-fallback identity beside the URL. This is deliberately
         * source-based rather than image-dimension based: no decode, network,
         * row scan, or other work is added to the focus/scroll path.
         */
        val heroBackdropSelection = remember(
            visualHero,
            activeRowFallbackBackdrop,
            carouselRows,
            lastGoodBackdrop.value,
            lastGoodBackdropIsPosterFallback.value
        ) {
            when {
                !visualHero?.backdrop.isNullOrBlank() -> {
                    val url = visualHero?.backdrop
                    url to
                        (!visualHero?.poster.isNullOrBlank() &&
                            url == visualHero?.poster)
                }

                !visualHero?.imageUrl.isNullOrBlank() -> {
                    val url = visualHero?.imageUrl
                    url to
                        (!visualHero?.poster.isNullOrBlank() &&
                            url == visualHero?.poster)
                }

                visualHero == null &&
                    !activeRowFallbackBackdrop.isNullOrBlank() ->
                    activeRowFallbackBackdrop to false

                visualHero == null &&
                    !lastGoodBackdrop.value.isNullOrBlank() ->
                    lastGoodBackdrop.value to
                        lastGoodBackdropIsPosterFallback.value

                visualHero == null &&
                    lastGoodBackdrop.value == null -> {
                    val firstPreview =
                        carouselRows.firstOrNull {
                            it.items.isNotEmpty()
                        }?.items?.firstOrNull()?.heroPreview

                    val url =
                        firstPreview?.let {
                            firstNonBlank(
                                it.backdrop,
                                it.imageUrl
                            )
                        }

                    url to
                        (
                            url != null &&
                                !firstPreview?.poster.isNullOrBlank() &&
                                url == firstPreview?.poster
                            )
                }

                else -> null to false
            }
        }

        val heroBackdrop = heroBackdropSelection.first
        val heroBackdropIsPosterFallback = heroBackdropSelection.second

        // Record the last non-blank resolved backdrop (only from a REAL focused
        // item, not the fallback itself, to avoid latching the first-item value).
        LaunchedEffect(
            visualHero?.backdrop,
            visualHero?.imageUrl,
            visualHero?.poster
        ) {
            val real =
                firstNonBlank(
                    visualHero?.backdrop,
                    visualHero?.imageUrl
                )

            if (real != null) {
                lastGoodBackdrop.value = real
                lastGoodBackdropIsPosterFallback.value =
                    !visualHero?.poster.isNullOrBlank() &&
                        real == visualHero?.poster
            }
        }

        val expandedFocusedSelection = remember(
            focusedCatalogSelection,
            expandedCatalogFocusKey
        ) {
            focusedCatalogSelection?.takeIf {
                it.focusKey == expandedCatalogFocusKey
            }
        }

        val heroTrailerSelection =
            if (
                trailerPlaybackTarget ==
                    FocusedPosterTrailerPlaybackTarget.HERO_MEDIA
            ) {
                retainedHeroTrailerSelection
            } else {
                expandedFocusedSelection
            }

        val heroTrailerUrl by derivedStateOf {
            heroTrailerSelection?.payload?.itemId?.let {
                trailerPreviewUrls[it]
            }
        }
        val heroTrailerAudioUrl by derivedStateOf {
            heroTrailerSelection?.payload?.itemId?.let {
                trailerPreviewAudioUrls[it]
            }
        }
        val expandedCatalogTrailerUrl = heroTrailerUrl
        val expandedCatalogTrailerAudioUrl = heroTrailerAudioUrl
        /*
         * Visibility is independent from vertical scrolling. During navigation,
         * retainedHeroTrailerSelection continues to identify Trailer A.
         */
        val shouldPlayHeroTrailer = remember(
            effectiveAutoplayEnabled,
            trailerPlaybackTarget,
            heroTrailerUrl,
            isSidebarExpanded,
            preserveSidebarTrailerPlayback
        ) {
            effectiveAutoplayEnabled &&
                (!isSidebarExpanded || preserveSidebarTrailerPlayback) &&
                trailerPlaybackTarget ==
                    FocusedPosterTrailerPlaybackTarget.HERO_MEDIA &&
                !heroTrailerUrl.isNullOrBlank()
        }
        /*
         * Single source of truth for whether the player should be RUNNING.
         * shouldPlayHeroTrailer controls visibility; this controls playback.
         * They differ during a hold (visible, paused) and when scrolling or
         * a non-preserving sidebar state hides the trailer. An already-playing
         * trailer may intentionally remain visible/running beneath the sidebar.
         */
        val heroTrailerShouldRun =
            shouldPlayHeroTrailer &&
                !heroTrailerHoldMuted
        var heroTrailerFirstFrameRendered by remember(heroTrailerUrl) { mutableStateOf(false) }
        val isHeroTrailerActivelyPlaying = shouldPlayHeroTrailer && heroTrailerFirstFrameRendered
        LaunchedEffect(isHeroTrailerActivelyPlaying) {
            homeHeroTrailerPlayingState.value =
                isHeroTrailerActivelyPlaying
            onHeroTrailerPlayingChanged(
                isHeroTrailerActivelyPlaying
            )
        }
        LaunchedEffect(shouldPlayHeroTrailer) {
            if (!shouldPlayHeroTrailer) heroTrailerFirstFrameRendered = false
        }
        /*
         * ONE authoritative crossfade. The trailer's own alpha ramp and its
         * AnimatedVisibility wrapper are both disabled (see TrailerPlayer),
         * so this progress value is the single source of truth:
         *   0f = backdrop fully opaque, trailer fully transparent
         *   1f = trailer fully opaque, backdrop fully transparent
         * Driven off first-frame so the trailer never appears as a paused
         * still, and never bleeds through a backdrop that is still opaque.
         */
        val heroTransitionTarget =
            if (
                shouldPlayHeroTrailer &&
                heroTrailerFirstFrameRendered
            ) {
                1f
            } else {
                0f
            }

        val heroTransitionProgress by animateFloatAsState(
            targetValue = heroTransitionTarget,
            animationSpec = tween(durationMillis = 480),
            label = "heroBackdropTrailerCrossfadeProgress",
            finishedListener = {
                instantTopRowBackdropSwapFocusKey = null
            }
        )

        val heroBackdropAlpha =
            1f - heroTransitionProgress
        val heroTrailerAlpha =
            heroTransitionProgress

        /*
         * This is the one backdrop-side presentation clock:
         *
         * backdrop + normal gradient + platform icons
         *
         * all return together as the trailer leaves.
         */
        androidx.compose.runtime.SideEffect {
            onHeroBackdropAlphaChanged(heroBackdropAlpha)
        }

        val heroGradientProgress =
            heroTransitionProgress

        val lbTrailerAlpha =
            heroTransitionProgress

        val catalogBottomPadding = 0.dp
        val heroToCatalogGap = 16.dp
        val rowTitleBottom = 14.dp
        val rowsViewportHeightFraction = if (useLandscapePosters) 0.49f else 0.52f
        val rowsViewportHeight = maxHeight * rowsViewportHeightFraction

        // The configurable centered hero belongs only to the aggregate +
        // full-width icon-row mode. Aggregate-off and half-width retain the
        // original 0.4.20 bottom-anchored, full-size hero.
        val effectiveFullWidthIconRowEnabled =
            aggregatePlatformsEnabled && fullWidthIconRowEnabled

        // Empty space runs from below the platform icon row to the top of the
        // catalog. Icons are rendered in a separate scope, so their bottom edge
        // is a tunable inset here — adjust heroRegionTopInset to move the hero
        // up/down within that space.
        val catalogRowHeaderHeight = 32.dp
        val heroRegionTopInset = 54.dp
        // Empty space between the icon row (top, at heroRegionTopInset) and the
        // catalog's TITLE (which sits at the top of the catalog viewport,
        // maxHeight - rowsViewportHeight from screen top). Hero centers in this.
        val heroRegionHeight = (maxHeight - rowsViewportHeight) - heroRegionTopInset
        val localDensity = LocalDensity.current
        val rowTitleLineHeight = MaterialTheme.typography.titleMedium.lineHeight
        val rowTitleHeight = with(localDensity) {
            runCatching { rowTitleLineHeight.toDp() }
                .getOrDefault(24.dp)
        }
        val heroBackdropHeight = computeHeroBackdropHeightDp(
            maxHeightDp = maxHeight,
            useLandscapePosters = useLandscapePosters,
            rowTitleHeightDp = rowTitleHeight
        )
        /*
         * Keep the Home canvas and hero gradients on the authoritative hero
         * trailer crossfade. This remains Nuvio background in expanded-card
         * mode and whenever no hero trailer pixels are visible.
         */
        val heroMediaBackgroundProgress =
            if (
                uiState.focusedPosterBackdropTrailerPlaybackTarget ==
                    FocusedPosterTrailerPlaybackTarget.HERO_MEDIA
            ) {
                heroTransitionProgress
            } else {
                0f
            }
        val bgColor =
            androidx.compose.ui.graphics.lerp(
                NuvioColors.Background,
                androidx.compose.ui.graphics.Color.Black,
                heroMediaBackgroundProgress
            )

        Box(modifier = Modifier.fillMaxSize().background(bgColor))
        val contentFocusRequester = LocalContentFocusRequester.current
        val carouselFocusRequester = LocalCarouselFocusRequester.current
        val rowFocusRestorerState = LocalRowFocusRestorer.current
        val focusRestorerRequester by remember(
            carouselRows,
            uiCaches,
            forceContinueWatchingRestoreToStart
        ) {
            derivedStateOf {
                val rowKey = activeRowKey
                if (rowKey != null) {
                    val row = carouselRows.firstOrNull { it.key == rowKey }
                    val focusedIndex =
                        if (
                            rowKey == "continue_watching" &&
                            forceContinueWatchingRestoreToStart
                        ) {
                            0
                        } else {
                            uiCaches.lastActuallyFocusedIndexByRow[rowKey]
                            ?: uiCaches.focusedItemByRow[rowKey]
                            ?: 0
                        }
                    val safeIndex = focusedIndex.coerceIn(0, ((row?.items?.size ?: 1) - 1).coerceAtLeast(0))
                    val itemKey = row?.items?.getOrNull(safeIndex)?.key
                    if (itemKey != null) {
                        val requester =
                            uiCaches.itemFocusRequesters[rowKey]?.get(itemKey)
                                ?: FocusRequester.Default

                        requester
                    } else FocusRequester.Default
                } else FocusRequester.Default
            }
        }
        LaunchedEffect(focusRestorerRequester) {
            rowFocusRestorerState.value = focusRestorerRequester
        }

        val heroMediaWidthPx = remember(maxWidth, localDensity) {
            with(localDensity) { (maxWidth * MODERN_HERO_MEDIA_WIDTH_FRACTION).roundToPx() }
        }
        val heroMediaHeightPx = remember(heroBackdropHeight, localDensity) {
            with(localDensity) { heroBackdropHeight.roundToPx() }
        }

        // Patch 15: focus-following backdrop prewarm. Warm the hero backdrops of
        // likely-next items (in-row neighbors + remembered-focus item of adjacent
        // rows) into Coil's memory cache the moment focus moves — during the slide,
        // while the hero is frozen. At un-freeze the incoming backdrop is cached,
        // so the backdrop's cache-check flips displayedFrame same-frame and the
        // crossfade starts immediately instead of waiting on network+decode.
        // Sized identically to the backdrop renderer so the cache key matches.
        val prewarmImageLoader = remember(context) { coil.Coil.imageLoader(context) }
        // Cold-start hero prewarm: the CW pre-render paints cards from disk cache
        // ~1s before the enrichment restore + debounced hero flow produce a
        // backdrop, leaving the hero region black. Fire the sized request for the
        // first CW item's backdrop the moment we can compute the hero size, so
        // decode overlaps that window instead of following it.
        LaunchedEffect(heroMediaWidthPx, heroMediaHeightPx, uiState.continueWatchingItems.firstOrNull()) {
            if (heroItem != null) return@LaunchedEffect
            val first = uiState.continueWatchingItems.firstOrNull() ?: return@LaunchedEffect
            val url = when (first) {
                is ContinueWatchingItem.InProgress -> first.progress.backdrop
                is ContinueWatchingItem.NextUp -> first.info.backdrop
            }?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
            prewarmImageLoader.enqueue(
                coil.request.ImageRequest.Builder(context)
                    .data(url)
                    .size(width = heroMediaWidthPx, height = heroMediaHeightPx)
                    .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                    .build()
            )
        }
        LaunchedEffect(Unit) {
            snapshotFlow { activeRowKey to activeItemIndex }
                .collect { (rowKey, index) ->
                    if (rowKey == null) return@collect
                    val rows = latestCarouselRows
                    val rowIdx = rows.indexOfFirst { it.key == rowKey }
                    val row = rows.getOrNull(rowIdx) ?: return@collect
                    val targets = buildList {
                        row.items.getOrNull(index - 1)?.let { add(it) }
                        row.items.getOrNull(index + 1)?.let { add(it) }
                        for (adj in intArrayOf(rowIdx - 1, rowIdx + 1)) {
                            rows.getOrNull(adj)?.let { r ->
                                if (r.items.isNotEmpty()) {
                                    val ri = (focusedItemByRow[r.key] ?: 0)
                                        .coerceIn(0, r.items.size - 1)
                                    r.items.getOrNull(ri)?.let { add(it) }
                                }
                            }
                        }
                    }
                    targets.asSequence()
                        .mapNotNull { it.heroPreview.backdrop?.takeIf { u -> u.isNotBlank() } }
                        .distinct()
                        .forEach { url ->
                            prewarmImageLoader.enqueue(
                                coil.request.ImageRequest.Builder(context)
                                    .data(url)
                                    .size(width = heroMediaWidthPx, height = heroMediaHeightPx)
                                    .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                                    .build()
                            )
                        }
                }
        }
        LaunchedEffect(heroMediaWidthPx, heroMediaHeightPx) {
            if (heroMediaWidthPx > 0 && heroMediaHeightPx > 0) {
                onBackdropPreloadSizeKnown(heroMediaWidthPx, heroMediaHeightPx)
            }
        }
        // Re-warm platform first backdrops from the POST-enrichment URLs (same
        // source as the renderer). The startup preload reads catalogsMap before
        // enrichment swaps most backdrop URLs, so its cache entries miss — this
        // keeps the real keys warm as enrichment lands. Coil dedupes requests,
        // so repeated emissions are cheap.
        LaunchedEffect(heroMediaWidthPx, heroMediaHeightPx) {
            if (heroMediaWidthPx <= 0 || heroMediaHeightPx <= 0) return@LaunchedEffect
            snapshotFlow {
                uiState.catalogRows
                    .filter { it.items.isNotEmpty() && inferPlatformId(it.catalogName) != null }
                    .groupBy { inferPlatformId(it.catalogName) }
                    .mapNotNull { (_, rows) -> rows.firstOrNull()?.items?.firstOrNull()?.backdropUrl }
                    .filter { it.isNotBlank() }
                    .toSet()
            }
                .distinctUntilChanged()
                .collect { urls ->
                    val loader = coil.Coil.imageLoader(context)
                    urls.forEach { url ->
                        loader.enqueue(
                            coil.request.ImageRequest.Builder(context)
                                .data(url)
                                .size(width = heroMediaWidthPx, height = heroMediaHeightPx)
                                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                                .build()
                        )
                    }
                }
        }
        val catalogSlideAlpha = remember { androidx.compose.animation.core.Animatable(1f) }
        val catalogSlideOffset = remember { androidx.compose.animation.core.Animatable(0f) }
        // Separate parallax animatable — never snaps, only smooth exit+enter arcs
        val backdropParallaxOffset = remember { androidx.compose.animation.core.Animatable(0f) }

        // Ghost layers for the platform crossfade: the outgoing hero backdrop and
        // catalog block are recorded once into GPU GraphicsLayers at transition
        // start (full node size, including off-screen overdraw), then slid/faded
        // out ON TOP while the real incoming content slides in underneath.
        val heroGhostLayer = rememberGraphicsLayer()
        val catalogGhostLayer = rememberGraphicsLayer()
        var ghostCaptureTick by remember { mutableStateOf(0) }
        val heroGhostCapturedTick = remember { java.util.concurrent.atomic.AtomicInteger(0) }
        val catalogGhostCapturedTick = remember { java.util.concurrent.atomic.AtomicInteger(0) }
        var ghostVisible by remember { mutableStateOf(false) }
        var heroGhostBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
        var catalogGhostBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
        val ghostAlpha = remember { androidx.compose.animation.core.Animatable(1f) }
        val ghostOffset = remember { androidx.compose.animation.core.Animatable(0f) }
        val ghostParallax = remember { androidx.compose.animation.core.Animatable(0f) }

        // Cinematic mode: trailers off OR target is expanded card → full-screen backdrop + detail-style gradient
        val cinematicHeroMode = !uiState.focusedPosterBackdropTrailerEnabled ||
            uiState.focusedPosterBackdropTrailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD

        /*
         * The scale passed into ModernHeroMediaLayer is final before the
         * incoming BackdropFrame is created. Eligible images therefore enter
         * already overscanned on their first visible frame.
         */
        val renderedBackdropIdentity = remember(heroBackdrop) {
            cinematicBackdropIdentity(heroBackdrop)
        }
        val shouldOverscanCinematicBackdrop =
            renderedBackdropIdentity != null &&
                renderedBackdropIdentity in canonicalTopRowBackdropKeys

        val heroMediaModifier = remember(heroBackdropHeight, cinematicHeroMode, maxHeight) {
            if (cinematicHeroMode) {
                Modifier
                    .align(Alignment.Center)
                    .requiredSize(maxWidth * 1.1f, maxHeight * 1.1f)
            } else {
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 56.dp)
                    .fillMaxWidth(MODERN_HERO_MEDIA_WIDTH_FRACTION)
                    .height(heroBackdropHeight)
            }
        }

        val compactHeroGradientLeftExtension =
            maxWidth * MODERN_HERO_MEDIA_WIDTH_FRACTION * 0.04f +
                8.dp

        // Capture the outer BoxWithConstraints width before entering nested
        // Box scopes, where Compose's DSL marker hides the implicit receiver.
        val compactHeroAvailableWidth = maxWidth

        val instantTopRowTrailerExitBackdropSwap =
            instantTopRowBackdropSwapFocusKey != null &&
                focusedCatalogSelection?.focusKey ==
                    instantTopRowBackdropSwapFocusKey &&
                heroTransitionTarget == 0f &&
                heroTransitionProgress > 0.001f

        if (
            cinematicHeroMode ||
            !uiState.heroTrailerAllowLetterboxing
        ) {
            ModernHeroMediaLayer(
                heroBackdrop = heroBackdrop,
                heroBackdropIsPosterFallback = heroBackdropIsPosterFallback,
                backdropCrossfadeDuration =
                    if (isPlatformTransitioning || instantTopRowTrailerExitBackdropSwap) 0 else 400,
                heroBackdropAlpha = heroBackdropAlpha,
                parallaxOffsetX = backdropParallaxOffset.value,
                cinematicMode = cinematicHeroMode,
                shouldPlayHeroTrailer = shouldPlayHeroTrailer && !uiState.heroTrailerAllowLetterboxing,
                heroTrailerUrl = heroTrailerUrl,
                heroTrailerAudioUrl = heroTrailerAudioUrl,
                heroTrailerAlpha = heroTrailerAlpha,
                muted =
                        uiState.focusedPosterBackdropTrailerMuted,
                isTrailerPlaying = heroTrailerShouldRun,
                externalPlayer = sharedTrailerPlayer,
                onTrailerEnded = {
                    expandedCatalogFocusKey = null
                    retainedHeroTrailerSelection = null
                },
                onFirstFrameRendered = { heroTrailerFirstFrameRendered = true },
                modifier = heroMediaModifier
                    .drawWithContent {
                        // While the ghost is showing, it fully REPLACES the live content
                        // in this node — drawing both stacked double-composites any
                        // semi-transparent pixels for a frame (visible brightness pop).
                        if (!(ghostVisible && heroGhostBitmap != null)) drawContent()
                        if (ghostCaptureTick != heroGhostCapturedTick.get()) {
                            heroGhostLayer.record { this@drawWithContent.drawContent() }
                            heroGhostCapturedTick.set(ghostCaptureTick)
                        }
                        val hBmp = heroGhostBitmap
                        if (ghostVisible && hBmp != null) {
                            // Full alpha: fading each ghost layer independently
                            // weakens the scrim's dimming mid-fade (a*g instead of g)
                            // and brightens the gradient region. Instead both ghosts
                            // stay pixel-correct and a black overlay in the catalog
                            // node (topmost) performs the fade-to-black.
                            drawImage(
                                image = hBmp,
                                dstOffset = androidx.compose.ui.unit.IntOffset(Math.round(ghostParallax.value), 0),
                                dstSize = androidx.compose.ui.unit.IntSize(hBmp.width, hBmp.height),
                                alpha = 1f,
                                filterQuality = androidx.compose.ui.graphics.FilterQuality.None
                            )
                        }
                    }
                    .graphicsLayer {
                        // Full alpha: the enter fade is done by a single black
                        // overlay over the whole composite (catalog node, topmost),
                        // so backdrop and catalog never fade independently and you
                        // never see through one layer to the next. translationX
                        // (parallax motion) is unaffected.
                        alpha = 1f
                        translationX = backdropParallaxOffset.value
                    },
                cinematicScale =
                    if (shouldOverscanCinematicBackdrop) 1.0f
                    else (1.0f / 1.1f),
                requestWidthPx = heroMediaWidthPx,
                requestHeightPx = heroMediaHeightPx
            )
        } else {
            /*
             * Compact HERO_MEDIA backdrop composite.
             *
             * Backdrop pixels + the normal backdrop gradient live under one
             * parent alpha. The media itself stays at alpha 1 internally.
             *
             * Therefore during trailer start:
             *
             *   [backdrop + backdrop gradient] * heroBackdropAlpha
             *
             * They cannot fade independently.
             *
             * Parallax remains on the backdrop child only so the stationary
             * gradient geometry does not move.
             */
            val backdropGradientInsideComposite =
                (
                    shouldPlayHeroTrailer &&
                        heroTransitionProgress < 0.999f
                ) ||
                    (
                        !shouldPlayHeroTrailer &&
                            heroTransitionProgress <= 0.001f
                    )

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 56.dp)
                    .width(
                        compactHeroAvailableWidth * MODERN_HERO_MEDIA_WIDTH_FRACTION +
                            compactHeroGradientLeftExtension
                    )
                    .height(heroBackdropHeight)
                    .graphicsLayer {
                        alpha = heroBackdropAlpha
                        compositingStrategy =
                            androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                    }
            ) {
                ModernHeroMediaLayer(
                    heroBackdrop = heroBackdrop,
                    heroBackdropIsPosterFallback =
                        heroBackdropIsPosterFallback,
                    backdropCrossfadeDuration =
                        if (isPlatformTransitioning || instantTopRowTrailerExitBackdropSwap) 0 else 400,

                    // The parent owns the backdrop + gradient fade.
                    heroBackdropAlpha = 1f,

                    parallaxOffsetX =
                        backdropParallaxOffset.value,
                    cinematicMode = false,

                    // Letterboxed trailer remains in its dedicated path below.
                    shouldPlayHeroTrailer = false,

                    heroTrailerUrl = heroTrailerUrl,
                    heroTrailerAudioUrl = heroTrailerAudioUrl,
                    heroTrailerAlpha = 0f,
                    muted =
                        uiState.focusedPosterBackdropTrailerMuted,
                    isTrailerPlaying = false,
                    externalPlayer = sharedTrailerPlayer,
                    onTrailerEnded = {
                        expandedCatalogFocusKey = null
                        retainedHeroTrailerSelection = null
                    },
                    onFirstFrameRendered = {},
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .width(
                            compactHeroAvailableWidth *
                                MODERN_HERO_MEDIA_WIDTH_FRACTION
                        )
                        .fillMaxHeight()
                        .drawWithContent {
                            if (
                                !(
                                    ghostVisible &&
                                        heroGhostBitmap != null
                                )
                            ) {
                                drawContent()
                            }

                            if (
                                ghostCaptureTick !=
                                    heroGhostCapturedTick.get()
                            ) {
                                heroGhostLayer.record {
                                    this@drawWithContent.drawContent()
                                }
                                heroGhostCapturedTick.set(
                                    ghostCaptureTick
                                )
                            }

                            val hBmp = heroGhostBitmap

                            if (
                                ghostVisible &&
                                    hBmp != null
                            ) {
                                drawImage(
                                    image = hBmp,
                                    dstOffset =
                                        androidx.compose.ui.unit.IntOffset(
                                            Math.round(
                                                ghostParallax.value
                                            ),
                                            0
                                        ),
                                    dstSize =
                                        androidx.compose.ui.unit.IntSize(
                                            hBmp.width,
                                            hBmp.height
                                        ),
                                    alpha = 1f,
                                    filterQuality =
                                        androidx.compose.ui.graphics.FilterQuality.None
                                )
                            }
                        }
                        .graphicsLayer {
                            alpha = 1f
                            translationX =
                                backdropParallaxOffset.value
                        },
                    cinematicScale =
                        if (shouldOverscanCinematicBackdrop) {
                            1.0f
                        } else {
                            1.0f / 1.1f
                        },
                    requestWidthPx = heroMediaWidthPx,
                    requestHeightPx = heroMediaHeightPx
                )

                if (backdropGradientInsideComposite) {
                    ModernHeroGradientLayer(
                        bgColor = bgColor,
                        allowLetterboxing = false,
                        trailerTransitionProgress = 0f,
                        modifier = Modifier.fillMaxSize(),
                        cinematicMode = false,
                        shouldPlayHeroTrailer = false,
                        compactContentStartOffset =
                            compactHeroGradientLeftExtension
                    )
                }
            }
        }
        /*
         * The fixed-seed dither pattern is generated once for the mask's
         * physical dimensions. Title changes, bgColor changes, and draw-cache
         * rebuilds reuse these same points instead of recreating Random,
         * lists, and Offset objects.
         *
         * This remains outside the trailer conditional so starting or stopping
         * a trailer does not destroy and regenerate the pattern.
         */
        val letterboxedTrailerEdgeGradientWidth = 76.dp
        val letterboxedTrailerEdgeDensity =
            androidx.compose.ui.platform.LocalDensity.current

        val letterboxedTrailerEdgeGradientWidthPx =
            with(letterboxedTrailerEdgeDensity) {
                letterboxedTrailerEdgeGradientWidth
                    .roundToPx()
                    .coerceAtLeast(1)
            }

        val letterboxedTrailerEdgeGradientHeightPx =
            with(letterboxedTrailerEdgeDensity) {
                heroBackdropHeight
                    .roundToPx()
                    .coerceAtLeast(1)
            }

        val letterboxedTrailerEdgeDitherBuckets =
            androidx.compose.runtime.remember(
                letterboxedTrailerEdgeGradientWidthPx,
                letterboxedTrailerEdgeGradientHeightPx
            ) {
                val random =
                    kotlin.random.Random(0x4E555649)

                val ditherStartX =
                    letterboxedTrailerEdgeGradientWidthPx * 0.48f

                val ditherWidth =
                    (
                        letterboxedTrailerEdgeGradientWidthPx -
                            ditherStartX
                        ).coerceAtLeast(1f)

                val buckets =
                    List(4) {
                        mutableListOf<
                            androidx.compose.ui.geometry.Offset
                        >()
                    }

                repeat(240) {
                    val normalizedX = random.nextFloat()
                    val remaining = 1f - normalizedX

                    val keepChance =
                        0.16f + remaining * 0.74f

                    if (random.nextFloat() <= keepChance) {
                        val x =
                            ditherStartX +
                                normalizedX * ditherWidth

                        val y =
                            random.nextFloat() *
                                letterboxedTrailerEdgeGradientHeightPx

                        val bucket = when {
                            remaining > 0.72f -> 0
                            remaining > 0.48f -> 1
                            remaining > 0.25f -> 2
                            else -> 3
                        }

                        buckets[bucket].add(
                            androidx.compose.ui.geometry.Offset(x, y)
                        )
                    }
                }

                buckets
            }

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
                    onEnded = {
                        expandedCatalogFocusKey = null
                        retainedHeroTrailerSelection = null
                    },
                    onFirstFrameRendered = { heroTrailerFirstFrameRendered = true },
                    muted =
                    uiState.focusedPosterBackdropTrailerMuted,
                    isPlaying = heroTrailerShouldRun,
                    cropToFill = true,
                    overscanZoom = 1f,
                    externalPlayer = sharedTrailerPlayer,
                    modifier = Modifier.fillMaxSize()
                )

                // Dedicated stationary trailer-edge concealment.
                //
                // This is positioned inside the actual 60%-width trailer
                // container, so its fade always begins at the trailer's real
                // left edge and is unaffected by backdrop parallax.
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(letterboxedTrailerEdgeGradientWidth)
                        .drawWithCache {
                            /*
                             * A compact nonlinear fade hides the trailer's hard
                             * left edge without covering as much of the video.
                             *
                             * Fixed-seed, background-colored dither points break
                             * up the faint transparent tail so its alpha bands do
                             * not read as evenly stepped vertical lines. All point
                             * positions are generated only when this draw cache is
                             * rebuilt, so the pattern never flickers or shimmers.
                             */
                            val fadeBrush =
                                androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    colorStops = arrayOf(
                                        0.00f to bgColor,
                                        0.09f to bgColor,
                                        0.22f to bgColor.copy(alpha = 0.93f),
                                        0.36f to bgColor.copy(alpha = 0.72f),
                                        0.50f to bgColor.copy(alpha = 0.46f),
                                        0.63f to bgColor.copy(alpha = 0.24f),
                                        0.74f to bgColor.copy(alpha = 0.11f),
                                        0.84f to bgColor.copy(alpha = 0.040f),
                                        0.92f to bgColor.copy(alpha = 0.014f),
                                        1.00f to androidx.compose.ui.graphics.Color.Transparent
                                    )
                                )

                            /*
                             * Point positions are remembered above. This cache
                             * now handles only the color-dependent gradient and
                             * draw commands.
                             */
                            val ditherAlphas = floatArrayOf(
                                0.060f,
                                0.040f,
                                0.025f,
                                0.013f
                            )

                            onDrawBehind {
                                drawRect(brush = fadeBrush)

                                letterboxedTrailerEdgeDitherBuckets.forEachIndexed { index, points ->
                                    if (points.isNotEmpty()) {
                                        drawPoints(
                                            points = points,
                                            pointMode =
                                                androidx.compose.ui.graphics.PointMode.Points,
                                            color = bgColor.copy(
                                                alpha = ditherAlphas[index]
                                            ),
                                            strokeWidth = 1.15f,
                                            cap =
                                                androidx.compose.ui.graphics.StrokeCap.Round
                                        )
                                    }
                                }
                            }
                        }
                )
}
        }
        if (
            cinematicHeroMode ||
            !uiState.heroTrailerAllowLetterboxing
        ) {
            /*
             * Independent Hero-gradient transition.
             *
             * BACKDROP -> TRAILER:
             * Keep the gradient in BACKDROP MODE for the entire fade. Its opacity
             * is driven by its own Animatable rather than heroTransitionProgress.
             * Only after the fade reaches zero do we switch geometry to the
             * full-strength trailer gradient.
             *
             * TRAILER -> BACKDROP:
             * Cancel any start fade and immediately restore the full-strength
             * backdrop gradient. This direction already looks correct.
             */
            val heroGradientFadeAlpha =
                remember(heroTrailerUrl) {
                    androidx.compose.animation.core.Animatable(1f)
                }

            var trailerGradientPresented by
                remember(heroTrailerUrl) {
                    mutableStateOf(false)
                }

            LaunchedEffect(
                heroTrailerUrl,
                uiState.heroTrailerAllowLetterboxing,
                shouldPlayHeroTrailer,
                heroTrailerFirstFrameRendered
            ) {
                if (
                    !uiState.heroTrailerAllowLetterboxing ||
                    !shouldPlayHeroTrailer ||
                    !heroTrailerFirstFrameRendered
                ) {
                    heroGradientFadeAlpha.stop()
                    trailerGradientPresented = false
                    heroGradientFadeAlpha.snapTo(1f)
                    return@LaunchedEffect
                }

                /*
                 * First rendered trailer frame:
                 * fade ONLY the still-backdrop gradient.
                 */
                trailerGradientPresented = false
                heroGradientFadeAlpha.snapTo(1f)

                heroGradientFadeAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec =
                        androidx.compose.animation.core.tween(
                            durationMillis = 480
                        )
                )

                /*
                 * If this effect reaches completion, trailer presentation is still
                 * valid. Swap geometry only while invisible, then restore full
                 * opacity immediately.
                 */
                trailerGradientPresented = true
                heroGradientFadeAlpha.snapTo(1f)
            }

            ModernHeroGradientLayer(
                bgColor = bgColor,
                allowLetterboxing = trailerGradientPresented,
                trailerTransitionProgress =
                    if (trailerGradientPresented) 1f else 0f,
                modifier =
                    if (cinematicHeroMode) {
                        heroMediaModifier.graphicsLayer {
                            translationX =
                                if (ghostVisible) {
                                    0f
                                } else {
                                    backdropParallaxOffset.value
                                }
                            alpha = heroGradientFadeAlpha.value
                        }
                    } else {
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 56.dp)
                            .width(
                                maxWidth * MODERN_HERO_MEDIA_WIDTH_FRACTION +
                                    compactHeroGradientLeftExtension
                            )
                            .height(heroBackdropHeight)
                            .graphicsLayer {
                                alpha = heroGradientFadeAlpha.value
                            }
                    },
                cinematicMode = cinematicHeroMode,
                shouldPlayHeroTrailer = trailerGradientPresented,
                compactContentStartOffset =
                    if (cinematicHeroMode) {
                        0.dp
                    } else {
                        compactHeroGradientLeftExtension
                    }
            )
        } else {
            /*
             * Compact letterboxed gradient presentation.
             *
             * ENTRY:
             * The normal backdrop gradient is INSIDE the backdrop composite
             * above and therefore fades with the backdrop as one unit.
             *
             * PLAYING:
             * Only the trailer gradient is presented.
             *
             * EXIT:
             * Restore the normal gradient immediately, preserving the
             * trailer-interruption behavior that already tested correctly.
             */
            val compactTrailerGradientPresented =
                shouldPlayHeroTrailer &&
                    heroTrailerFirstFrameRendered &&
                    heroTransitionProgress >= 0.999f

            val compactExitBackdropGradientPresented =
                !shouldPlayHeroTrailer &&
                    heroTransitionProgress > 0.001f

            when {
                compactTrailerGradientPresented -> {
                    ModernHeroGradientLayer(
                        bgColor = bgColor,
                        allowLetterboxing = true,
                        trailerTransitionProgress = 1f,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 56.dp)
                            .width(
                                maxWidth *
                                    MODERN_HERO_MEDIA_WIDTH_FRACTION +
                                    compactHeroGradientLeftExtension
                            )
                            .height(heroBackdropHeight),
                        cinematicMode = false,
                        shouldPlayHeroTrailer = true,
                        compactContentStartOffset =
                            compactHeroGradientLeftExtension
                    )
                }

                compactExitBackdropGradientPresented -> {
                    ModernHeroGradientLayer(
                        bgColor = bgColor,
                        allowLetterboxing = false,
                        trailerTransitionProgress = 0f,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 56.dp)
                            .width(
                                maxWidth *
                                    MODERN_HERO_MEDIA_WIDTH_FRACTION +
                                    compactHeroGradientLeftExtension
                            )
                            .height(heroBackdropHeight),
                        cinematicMode = false,
                        shouldPlayHeroTrailer = false,
                        compactContentStartOffset =
                            compactHeroGradientLeftExtension
                    )
                }
            }
        }

        if (carouselGradientAlpha > 0f) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height(80.dp)
                    .graphicsLayer {
                        alpha =
                            carouselGradientAlpha *
                                platformGradientRevealAlpha() *
                                heroBackdropAlpha
                    }
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
        val verticalRowBringIntoViewSpec = remember(localDensity, defaultBringIntoViewSpec) {
            val topInsetPx = with(localDensity) { MODERN_ROW_HEADER_FOCUS_INSET.toPx() }
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            object : BringIntoViewSpec {
                /*
                 * Match the official beta's Home motion.
                 *
                 * Keep Enhanced's row-alignment math below, but use the
                 * app/default BringIntoView animation instead of a custom
                 * stiff Home-only spring.
                 */
                override val scrollAnimationSpec: AnimationSpec<Float> =
                    defaultBringIntoViewSpec.scrollAnimationSpec

                override fun calculateScrollDistance(
                    offset: Float,
                    size: Float,
                    containerSize: Float
                ): Float {
                    // During fast scroll, don't bring focused item into view —
                    // it fights the drag and causes jank
                    if (isFastScrolling) return 0f
                    return offset - topInsetPx
                }
            }
        }

        // Slide+fade catalog rows on platform switch.
        // We keep a displayedPlatformId that lags behind selectedPlatformId —
        // the LazyColumn renders based on displayedPlatformId so the old rows
        // stay visible during the exit animation, then we flip to the new rows
        // and slide them in.

        val screenWidthPx = with(localDensity) {
            androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx()
        }
        val catalogSlideDistancePx = screenWidthPx * 0.15f
        // Travel distance for the catalog/hero slide animation. Kept separate from
        // catalogSlideDistancePx, which still drives the layout width expansion so
        // offscreen posters render fully regardless of how short the travel is.
        val catalogSlideTravelPx = screenWidthPx * 0.08f

        // Snapshot platformNavDirection into a ref so the transition coroutine reads
        // the direction at press time, not a stale 0 after the reset timer fires.
        val platformNavDirectionRef = remember { java.util.concurrent.atomic.AtomicInteger(0) }
        LaunchedEffect(platformNavDirection) {
            if (platformNavDirection != 0) platformNavDirectionRef.set(platformNavDirection)
        }

        // Shared transition logic — used by both fast and debounced modes.
        val latestHeroBackdrop by rememberUpdatedState(heroBackdrop)
        // Preload the incoming platform's hero backdrop while old content is
        // still on screen so the at-black wait is a memory-cache hit and the
        // black gap stays ~1 frame cold, warm, and after idle.
        val transitionPreloadScope = androidx.compose.runtime.rememberCoroutineScope()
        fun incomingBackdropUrl(target: String): String? = uiState.catalogRows
            .firstOrNull { it.items.isNotEmpty() && inferPlatformId(it.catalogName) == target }
            ?.items?.firstOrNull()
            ?.let { firstNonBlank(it.backdropUrl, it.landscapePoster, it.poster) }
        suspend fun preloadBackdrop(url: String?) {
            if (url.isNullOrBlank() || heroMediaWidthPx <= 0 || heroMediaHeightPx <= 0) return
            val request = coil.request.ImageRequest.Builder(context)
                .data(url)
                .size(width = heroMediaWidthPx, height = heroMediaHeightPx)
                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                .build()
            kotlinx.coroutines.withTimeoutOrNull(2_000L) {
                coil.Coil.imageLoader(context).execute(request)
            }
        }
        suspend fun runTransition(
            initialTarget: String,
            pollRetarget: (() -> String?)? = null
        ) {
            val originPlatform = catalogDisplayedPlatformId
            val safeParallaxMax = screenWidthPx * MODERN_HERO_MEDIA_WIDTH_FRACTION * 0.04f
            val navDir = platformNavDirectionRef.get()
            val exitDir = if (navDir >= 0) -1f else 1f
            val enterDir = -exitDir
            var preloadJob = transitionPreloadScope.launch { preloadBackdrop(incomingBackdropUrl(initialTarget)) }

            // QUIET GATE — no heavy work while presses are arriving, so the
            // platform icon focus animation never fights recomposition. Waits
            // for 150ms without a new press, conflating to the latest target.
            // Tunables for the quiet gate.
            val quietFirstMs = 250L      // single deliberate press
            val quietEscalatedMs = 400L  // once scrubbing is detected
            suspend fun awaitQuiet(from: String): String {
                var latest = from
                if (pollRetarget == null) return latest
                var quietMs = quietFirstMs
                var lastActivity = android.os.SystemClock.elapsedRealtime()
                while (true) {
                    kotlinx.coroutines.delay(50L)
                    val next = pollRetarget()
                    if (next != null) {
                        if (next != latest) {
                            preloadJob.cancel()
                            preloadJob = transitionPreloadScope.launch { preloadBackdrop(incomingBackdropUrl(next)) }
                            latest = next
                        }
                        lastActivity = android.os.SystemClock.elapsedRealtime()
                        quietMs = quietEscalatedMs
                        continue
                    }
                    if (android.os.SystemClock.elapsedRealtime() - lastActivity < quietMs) continue
                    // Physical key still down (e.g. inside the 250ms pre-repeat
                    // window of a hold): not quiet, keep waiting.
                    if (isPlatformDpadHeld()) continue
                    return latest
                }
            }
            var target = awaitQuiet(initialTarget)
            if (target == originPlatform) {
                // Scrubbed back to the starting platform before any work began:
                // nothing was captured or flipped — nothing to do.
                return
            }
            try {
                // CAPTURE — record the outgoing hero backdrop + catalog block into
                // ghost GraphicsLayers. Two frames so the draw pass that performs
                // the recording has definitely run before we flip content.
                ghostCaptureTick += 1
                withFrameNanos {}
                withFrameNanos {}
                heroGhostBitmap = heroGhostLayer.toImageBitmap()
                catalogGhostBitmap = catalogGhostLayer.toImageBitmap()
                ghostAlpha.snapTo(1f)
                ghostOffset.snapTo(0f)
                ghostParallax.snapTo(0f)
                ghostVisible = true
                isPlatformTransitioning = true

                // FLIP UNDER GHOST — the ghost bitmaps replace the live draw, so
                // the heavy recomposition of the incoming platform runs behind
                // the static old platform: never at black, never during presses.
                suspend fun flipTo(flipTarget: String) {
                    val incomingRows = uiState.catalogRows
                        .filter { it.items.isNotEmpty() && inferPlatformId(it.catalogName) == flipTarget }
                    displayedPlatformId = flipTarget
                    catalogDisplayedPlatformId = flipTarget
                    catalogSlideAlpha.snapTo(0f)
                    catalogSlideOffset.snapTo(enterDir * catalogSlideTravelPx)
                    backdropParallaxOffset.snapTo(enterDir * safeParallaxMax)
                    incomingRows.forEachIndexed { index, row ->
                        val firstItem = row.items.firstOrNull() ?: return@forEachIndexed
                        if (index == 0) onItemFocus(firstItem)
                        else onPreloadAdjacentItem(firstItem)
                    }
                    // Let recomposition + first (invisible) draw complete.
                    withFrameNanos {}
                    withFrameNanos {}
                }
                flipTo(target)
                preloadJob.join()
                preloadBackdrop(latestHeroBackdrop)

                // STRAGGLERS — presses that arrived during the flip re-enter the
                // quiet gate, then re-flip invisibly under the ghost.
                var straggler = pollRetarget?.invoke()
                while (straggler != null) {
                    val settled = awaitQuiet(straggler)
                    if (settled != target) {
                        preloadJob.cancel()
                        preloadJob = transitionPreloadScope.launch { preloadBackdrop(incomingBackdropUrl(settled)) }
                        flipTo(settled)
                        target = settled
                        preloadJob.join()
                        preloadBackdrop(latestHeroBackdrop)
                    }
                    straggler = pollRetarget?.invoke()
                }

                if (target == originPlatform) {
                    // Scrubbed back after flipping away: restore the origin
                    // content under the ghost, then drop the ghost with no
                    // animation — visually nothing ever happened.
                    flipTo(originPlatform)
                    catalogSlideAlpha.snapTo(1f)
                    catalogSlideOffset.snapTo(0f)
                    backdropParallaxOffset.snapTo(0f)
                } else {
                    // EXIT — ghost slides and fades to black. All heavy work is
                    // done, so the black that follows is only the swap (~1 frame).
                    coroutineScope {
                        launch { ghostAlpha.animateTo(0f, tween(150, easing = androidx.compose.animation.core.FastOutLinearInEasing)) }
                        launch { ghostOffset.animateTo(exitDir * catalogSlideTravelPx, tween(150, easing = androidx.compose.animation.core.FastOutLinearInEasing)) }
                        launch { ghostParallax.animateTo(exitDir * safeParallaxMax, tween(150, easing = androidx.compose.animation.core.FastOutLinearInEasing)) }
                    }
                    ghostVisible = false
                    // ENTER — new content slides in decelerating to a stop.
                    coroutineScope {
                        launch { catalogSlideAlpha.animateTo(1f, tween(250, easing = androidx.compose.animation.core.LinearEasing)) }
                        launch { catalogSlideOffset.animateTo(0f, tween(330, easing = androidx.compose.animation.core.LinearOutSlowInEasing)) }
                        launch { backdropParallaxOffset.animateTo(0f, tween(330, easing = androidx.compose.animation.core.LinearOutSlowInEasing)) }
                    }
                }
            } finally {
                ghostVisible = false
                heroGhostBitmap = null
                catalogGhostBitmap = null
                isPlatformTransitioning = false
            }
            // Now that the screen is fully visible, sync heroItem to the new platform's content
            val currentRow = latestCarouselRows.firstOrNull { it.key == latestActiveRow?.key } ?: latestActiveRow
            val currentIndex = latestActiveItemIndex
            val hero = currentRow?.items?.getOrNull(currentIndex)?.heroPreview
            if (hero != null) {
                heroItem = hero
                heroItemRowKey = currentRow.key
            }
        }
        // Press handling — two modes, restored to original semantics:
        // - Debounced (fast scroll OFF): every press waits for a 300ms quiet
        //   window; one uninterruptible transition per settled destination.
        // A single quiet-gated transition per settled destination: presses within
        // the quiet window conflate to the latest target; runTransition owns the
        // quiet gate, backdrop preload, and flip-under-ghost. (The former
        // fast-scroll mode routed through the same runTransition and behaved
        // identically once the quiet gate landed, so it was removed.)
        val platformChannel = remember { kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.CONFLATED) }
        LaunchedEffect(selectedPlatformId) {
            platformChannel.trySend(selectedPlatformId)
        }
        LaunchedEffect(aggregatePlatformsEnabled) {
            while (true) {
                val targetId = platformChannel.receive()
                if (!aggregatePlatformsEnabled || targetId == catalogDisplayedPlatformId) {
                    displayedPlatformId = targetId
                    catalogDisplayedPlatformId = targetId
                    continue
                }
                runTransition(targetId) { platformChannel.tryReceive().getOrNull() }
            }
        }

        /*
         * Stable rows-boundary inputs.
         *
         * Keep the list boundary independent from the broad HomeUiState and
         * HomeScreenFocusState objects. Collection identity changes only when
         * the corresponding row/look-up content actually changes.
         */
        val stableCarouselRowsForRowsBoundary =
            remember(carouselRows) {
                carouselRows.asStable()
            }

        val stableRowIndexByKeyForRowsBoundary =
            remember(rowIndexByKey) {
                rowIndexByKey.asStable()
            }

        val stableLandscapeCatalogKeysForRowsBoundary =
            remember(uiState.landscapeCatalogKeys) {
                uiState.landscapeCatalogKeys.asStable()
            }

        val stableCatalogRowScrollStatesForRowsBoundary =
            remember(focusState.catalogRowScrollStates) {
                focusState.catalogRowScrollStates.asStable()
            }

        val rowsBoundaryFocusedRowKey =
            focusState.focusedRowKey

        val rowsBoundaryPosterLabelsEnabled =
            uiState.posterLabelsEnabled

        val rowsBoundaryFocusedPosterBackdropTrailerMuted =
            uiState.focusedPosterBackdropTrailerMuted

        /*
         * Stable rows-boundary callbacks.
         *
         * Keep callback identity stable at the rows boundary while always
         * dispatching to the latest parent implementation.
         */
        val latestRowsOnCarouselOpenRequested =
            rememberUpdatedState(onCarouselOpenRequested)
        val rowsOnCarouselOpenRequested = remember {
            {
                latestRowsOnCarouselOpenRequested.value.invoke()
            }
        }

        val latestRowsOnContinueWatchingClick =
            rememberUpdatedState(onContinueWatchingClick)
        val rowsOnContinueWatchingClick = remember {
            { item: ContinueWatchingItem ->
                latestRowsOnContinueWatchingClick.value.invoke(item)
            }
        }

        val latestRowsIsCatalogItemWatched =
            rememberUpdatedState(isCatalogItemWatched)
        val rowsIsCatalogItemWatched = remember {
            { preview: MetaPreview ->
                latestRowsIsCatalogItemWatched.value.invoke(preview)
            }
        }

        val latestRowsOnCatalogItemLongPress =
            rememberUpdatedState(onCatalogItemLongPress)
        val rowsOnCatalogItemLongPress = remember {
            { preview: MetaPreview, rowKey: String ->
                latestRowsOnCatalogItemLongPress.value.invoke(
                    preview,
                    rowKey
                )
            }
        }

        val latestRowsOnItemFocus =
            rememberUpdatedState(gatedOnItemFocus)
        val rowsOnItemFocus = remember {
            { preview: MetaPreview ->
                latestRowsOnItemFocus.value.invoke(preview)
            }
        }

        val latestRowsOnPreloadAdjacentItem =
            rememberUpdatedState(gatedOnPreloadAdjacentItem)
        val rowsOnPreloadAdjacentItem = remember {
            { preview: MetaPreview ->
                latestRowsOnPreloadAdjacentItem.value.invoke(preview)
            }
        }

        val latestRowsOnCatalogSelectionFocused =
            rememberUpdatedState(gatedOnCatalogSelectionFocused)
        val rowsOnCatalogSelectionFocused = remember {
            { selection: FocusedCatalogSelection ->
                latestRowsOnCatalogSelectionFocused.value.invoke(
                    selection
                )
            }
        }

        val latestRowsOnNavigateToDetail =
            rememberUpdatedState(wrappedOnNavigateToDetail)
        val rowsOnNavigateToDetail = remember {
            { itemId: String, itemType: String, addonBaseUrl: String ->
                latestRowsOnNavigateToDetail.value.invoke(
                    itemId,
                    itemType,
                    addonBaseUrl
                )
            }
        }

        val latestRowsOnLoadMoreCatalog =
            rememberUpdatedState(onLoadMoreCatalog)
        val rowsOnLoadMoreCatalog = remember {
            { catalogId: String, catalogType: String, addonBaseUrl: String ->
                latestRowsOnLoadMoreCatalog.value.invoke(
                    catalogId,
                    catalogType,
                    addonBaseUrl
                )
            }
        }

        /*
         * Keep the rows LazyColumn behind a stable composition boundary so
         * Hero, platform and backdrop state changes do not broadly recompose
         * row content.
         */
        @Composable
        fun EnhancedModernHomeRowsListBoundary(
            modifier: Modifier,
            carouselRows: StableList<HeroCarouselRow>,
            rowIndexByKey: StableMap<String, Int>,
            landscapeCatalogKeys: StableSet<String>,
            focusStateFocusedRowKey: String?,
            focusStateCatalogRowScrollStates: StableMap<String, Int>,
            posterLabelsEnabled: Boolean,
            hideNewSeasonBadge: Boolean,
            focusedPosterBackdropTrailerMuted: Boolean,
            activeRowKeyState:
                androidx.compose.runtime.MutableState<String?>,
            activeItemIndexState:
                androidx.compose.runtime.MutableState<Int>,
            isFastScrollingState:
                androidx.compose.runtime.MutableState<Boolean>,
            fastScrollLandingVisualPendingState:
                androidx.compose.runtime.MutableState<Boolean>,
            focusedCatalogSelectionState:
                androidx.compose.runtime.MutableState<FocusedCatalogSelection?>,
            expandedCatalogFocusKeyState:
                androidx.compose.runtime.MutableState<String?>,
            expansionInteractionNonceState:
                androidx.compose.runtime.MutableState<Int>,
            heroFrozenForRapidNavState:
                androidx.compose.runtime.MutableState<Boolean>,
            heroFrozenForSlideState:
                androidx.compose.runtime.MutableState<Boolean>,
            heroItemState:
                androidx.compose.runtime.MutableState<HeroPreview?>,
            heroItemRowKeyState:
                androidx.compose.runtime.MutableState<String?>,
            frozenHeroItemState:
                androidx.compose.runtime.MutableState<HeroPreview?>,
            frozenHeroItemRowKeyState:
                androidx.compose.runtime.MutableState<String?>,
            optionsItemState:
                androidx.compose.runtime.MutableState<ContinueWatchingItem?>,
            fastScrollHeroCatchUpGenerationState:
                androidx.compose.runtime.MutableState<Int>,
            currentCarouselRowsState:
                androidx.compose.runtime.State<List<HeroCarouselRow>>,
            focusHolder: EnhancedHomeRowsFocusHolder,
            aggregatePlatformsEnabled: Boolean,
            doubleUpPlatformShortcutEnabled: Boolean,
            isVerticalRowsScrolling: Boolean,
            rowsViewportHeight: androidx.compose.ui.unit.Dp,
            catalogBottomPadding: androidx.compose.ui.unit.Dp,
            rowTitleBottom: androidx.compose.ui.unit.Dp,
            useLandscapePosters: Boolean,
            effectiveExpandEnabled: Boolean,
            effectiveAutoplayEnabled: Boolean,
            expandLandscapePostersEnabled: Boolean,
            trailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget,
            expandedCatalogTrailerUrl: String?,
            expandedCatalogTrailerAudioUrl: String?,
            fullyVisibleOverlayAlphaState:
                androidx.compose.runtime.State<Float>,
            myListSlotGeneration: Int,
            forceContinueWatchingRestoreToStart: Boolean,
            posterCardCornerRadius: androidx.compose.ui.unit.Dp,
            portraitBaseWidth: androidx.compose.ui.unit.Dp,
            portraitBaseHeight: androidx.compose.ui.unit.Dp,
            modernCatalogCardWidth: androidx.compose.ui.unit.Dp,
            modernCatalogCardHeight: androidx.compose.ui.unit.Dp,
            continueWatchingCardWidth: androidx.compose.ui.unit.Dp,
            continueWatchingCardHeight: androidx.compose.ui.unit.Dp,
            useThemeColorForNumbers: Boolean,
            verticalRowListState:
                androidx.compose.foundation.lazy.LazyListState,
            verticalRowBringIntoViewSpec: BringIntoViewSpec,
            contentFocusRequester: FocusRequester,
            focusRestorerRequester: FocusRequester,
            carouselFocusRequester: FocusRequester,
            uiCaches: ModernHomeUiCaches,
            pendingRowFocus: PendingRowFocusHolder,
            rowFocusRestorerState:
                androidx.compose.runtime.MutableState<FocusRequester>,
            catalogSlideAlpha:
                androidx.compose.animation.core.Animatable<
                    Float,
                    androidx.compose.animation.core.AnimationVector1D
                >,
            defaultBringIntoViewSpec: BringIntoViewSpec,
            onCarouselOpenRequested: () -> Unit,
            onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
            isCatalogItemWatched: (MetaPreview) -> Boolean,
            onCatalogItemLongPress: (MetaPreview, String) -> Unit,
            gatedOnItemFocus: (MetaPreview) -> Unit,
            gatedOnPreloadAdjacentItem: (MetaPreview) -> Unit,
            gatedOnCatalogSelectionFocused:
                (FocusedCatalogSelection) -> Unit,
            wrappedOnNavigateToDetail:
                (String, String, String) -> Unit,
            onLoadMoreCatalog:
                (String, String, String) -> Unit
        ) {
            /*
             * Rows-boundary hot-state handles.
             *
             * These delegated aliases preserve the exact names and behavior
             * of the pre-extraction implementation while making the state
             * dependency explicit at the rows composition boundary.
             */
            var activeRowKey by activeRowKeyState
            var activeItemIndex by activeItemIndexState
            var isFastScrolling by isFastScrollingState
            var fastScrollLandingVisualPending by
                fastScrollLandingVisualPendingState
            var focusedCatalogSelection by
                focusedCatalogSelectionState
            var expandedCatalogFocusKey by
                expandedCatalogFocusKeyState
            var expansionInteractionNonce by
                expansionInteractionNonceState
            var heroFrozenForRapidNav by
                heroFrozenForRapidNavState
            var heroFrozenForSlide by
                heroFrozenForSlideState
            var heroItem by heroItemState
            var heroItemRowKey by heroItemRowKeyState
            var frozenHeroItem by frozenHeroItemState
            var frozenHeroItemRowKey by frozenHeroItemRowKeyState
            var optionsItem by optionsItemState
            var fastScrollHeroCatchUpGeneration by
                fastScrollHeroCatchUpGenerationState
            val currentCarouselRows by currentCarouselRowsState

            val doubleUpScope =
                rememberCoroutineScope()

            val doubleUpDensity =
                LocalDensity.current

            val doubleUpInProgress =
                remember {
                    java.util.concurrent.atomic.AtomicBoolean(false)
                }

            val doubleUpLastReleaseMs =
                remember {
                    java.util.concurrent.atomic.AtomicLong(0L)
                }

            val doubleUpLastReleaseStartedBelowTop =
                remember {
                    java.util.concurrent.atomic.AtomicBoolean(false)
                }

            val doubleUpCurrentPressEligible =
                remember {
                    java.util.concurrent.atomic.AtomicBoolean(false)
                }

            val doubleUpCurrentPressStartedBelowTop =
                remember {
                    java.util.concurrent.atomic.AtomicBoolean(false)
                }

            /*
             * During a long double-Up return we temporarily remove only the
             * unseen middle rows.
             *
             * The top runway and the currently visible/lower segment keep
             * their original stable row keys. LazyColumn can therefore keep
             * the current viewport anchored while the middle disappears.
             *
             * There is no mid-scroll scrollToItem(), bitmap or fade.
             */
            var doubleUpCompressedRows by
                remember {
                    mutableStateOf<List<HeroCarouselRow>?>(null)
                }

            val doubleUpRenderedRows =
                doubleUpCompressedRows ?: carouselRows

            CompositionLocalProvider(
                LocalBringIntoViewSpec provides verticalRowBringIntoViewSpec
            ) {
            LazyColumn(
                state = verticalRowListState,
                modifier = modifier
                    .fillMaxWidth()
                    .height(rowsViewportHeight)
                    .padding(bottom = catalogBottomPadding)
                    .focusRequester(contentFocusRequester)
                    .focusRestorer { focusRestorerRequester }

                    .dpadVerticalFastScroll(
                        scrollableState = verticalRowListState,
                        verticalVelocityDpPerSec = 1200f,
                        onFastScrollingChanged = { scrolling ->
                            if (!scrolling) {
                                fastScrollLandingVisualPendingRef
                                    .set(true)

                                fastScrollLandingVisualPending =
                                    true
                            }

                            isFastScrollingRef.value =
                                scrolling
                        },
                        shouldHaltForward = {
                            val info = verticalRowListState.layoutInfo
                            val lastIdx = carouselRows.size - 1
                            val lastVisible = info.visibleItemsInfo.lastOrNull { it.index == lastIdx }
                            lastIdx >= 0 && lastVisible != null &&
                                lastVisible.offset + lastVisible.size <= info.viewportEndOffset
                        },
                        resolveVerticalLanding = { sign ->
                            val layoutInfo =
                                verticalRowListState.layoutInfo

                            val visibleItems =
                                layoutInfo.visibleItemsInfo

                            val lastIdx =
                                carouselRows.size - 1

                            val viewportEnd =
                                layoutInfo.viewportEndOffset

                            val lastRowAtBottom =
                                lastIdx >= 0 &&
                                    visibleItems
                                        .lastOrNull {
                                            it.index == lastIdx
                                        }
                                        ?.let {
                                            it.offset + it.size <=
                                                viewportEnd
                                        } == true

                            val upwardTopRow =
                                if (sign < 0) {
                                    visibleItems
                                        .firstOrNull()
                                        ?.takeIf {
                                            it.offset >
                                                -it.size / 2
                                        }
                                } else {
                                    null
                                }

                            val targetRowIndex =
                                when {
                                    lastRowAtBottom ->
                                        lastIdx

                                    upwardTopRow != null ->
                                        upwardTopRow.index

                                    else ->
                                        visibleItems
                                            .firstOrNull {
                                                it.offset >= 0
                                            }
                                            ?.index
                                            ?: visibleItems
                                                .firstOrNull()
                                                ?.index
                                            ?: verticalRowListState
                                                .firstVisibleItemIndex
                                }

                            val targetRow =
                                carouselRows
                                    .getOrNull(targetRowIndex)

                            if (targetRow == null) {
                                fastScrollLandingVisualPendingRef
                                    .set(false)

                                fastScrollLandingVisualPending =
                                    false

                                fastScrollHeroCatchUpGeneration++

                                null
                            } else {
                                val savedItemIndex =
                                    (
                                        uiCaches
                                            .focusedItemByRow[
                                                targetRow.key
                                            ]
                                            ?: 0
                                    ).coerceIn(
                                        0,
                                        (
                                            targetRow.items.size - 1
                                        ).coerceAtLeast(0)
                                    )

                                val destinationAlreadyFocused =
                                    focusHolder.activeRowKey ==
                                        targetRow.key &&
                                        focusHolder.activeItemIndex ==
                                            savedItemIndex

                                /*
                                 * Resolve the logical destination first. Real
                                 * focus is committed by the row-local handoff
                                 * below after fast scrolling has ended.
                                 *
                                 * No secondary vertical alignment animation is
                                 * performed here.
                                 */
                                activeRowKey =
                                    targetRow.key

                                activeItemIndex =
                                    savedItemIndex

                                /*
                                 * The saved horizontal destination card may not
                                 * be composed in the same turn that fast-scroll
                                 * landing resolves. Arm the existing row-local
                                 * focus handoff after scrolling has ended.
                                 *
                                 * This does not add work to the active
                                 * frame-driven fast-scroll path.
                                 */
                                if (
                                    !destinationAlreadyFocused &&
                                    targetRow.items.isNotEmpty()
                                ) {
                                    pendingRowFocus.key =
                                        targetRow.key

                                    pendingRowFocus.index =
                                        savedItemIndex

                                    pendingRowFocus
                                        .suppressBringIntoView =
                                        false

                                    pendingRowFocus.nonce++
                                }

                                if (destinationAlreadyFocused) {
                                    /*
                                     * A landing can occasionally resolve to the
                                     * card that already owns focus. There will
                                     * be no new onFocusChanged callback in that
                                     * case, so finish the landing here.
                                     */
                                    fastScrollLandingVisualPendingRef
                                        .set(false)

                                    fastScrollLandingVisualPending =
                                        false

                                    fastScrollHeroCatchUpGeneration++
                                }

                                if (
                                    targetRow.items.isEmpty() &&
                                    targetRow.isLoading
                                ) {
                                    /*
                                     * Enhanced keeps real focusable skeleton
                                     * cards. They do not yet participate in the
                                     * Phase-3A real-card self-claim path.
                                     */
                                    val skeletonRequester =
                                        uiCaches.requesterFor(
                                            targetRow.key,
                                            "skeleton_0"
                                        )

                                    runCatching {
                                        skeletonRequester
                                            .requestFocus()
                                    }

                                    "skeleton_0"
                                } else {
                                    targetRow.items
                                        .getOrNull(savedItemIndex)
                                        ?.key
                                        ?: "${targetRow.key}_$savedItemIndex"
                                }
                            }
                        }
                    )
                    .onPreviewKeyEvent { event ->
                        val native =
                            event.nativeKeyEvent

                        val keyCode =
                            native.keyCode

                        val isUp =
                            keyCode ==
                                android.view.KeyEvent.KEYCODE_DPAD_UP

                        val isDpad =
                            isUp ||
                                keyCode ==
                                    android.view.KeyEvent.KEYCODE_DPAD_DOWN ||
                                keyCode ==
                                    android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
                                keyCode ==
                                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT

                        /*
                         * The automated return owns D-pad navigation until
                         * the top/platform focus handoff is complete.
                         */
                        if (
                            doubleUpInProgress.get() &&
                            isDpad
                        ) {
                            return@onPreviewKeyEvent true
                        }

                        /*
                         * A different D-pad direction cancels an armed first
                         * Up tap.
                         */
                        if (
                            native.action ==
                                AndroidKeyEvent.ACTION_DOWN &&
                            isDpad &&
                            !isUp
                        ) {
                            doubleUpLastReleaseMs.set(0L)

                            doubleUpLastReleaseStartedBelowTop
                                .set(false)

                            doubleUpCurrentPressEligible
                                .set(false)

                            doubleUpCurrentPressStartedBelowTop
                                .set(false)
                        }

                        /*
                         * Only two distinct physical Up presses qualify.
                         * Held-key repeat events never count.
                         */
                        if (
                            doubleUpPlatformShortcutEnabled &&
                            aggregatePlatformsEnabled &&
                            native.action ==
                                AndroidKeyEvent.ACTION_DOWN &&
                            isUp
                        ) {
                            if (
                                native.repeatCount > 0
                            ) {
                                doubleUpLastReleaseMs.set(0L)

                                doubleUpLastReleaseStartedBelowTop
                                    .set(false)

                                doubleUpCurrentPressEligible
                                    .set(false)

                                doubleUpCurrentPressStartedBelowTop
                                    .set(false)
                            } else {
                                val firstRow =
                                    carouselRows.firstOrNull()

                                val startedBelowTop =
                                    firstRow != null &&
                                        focusHolder.activeRowKey !=
                                            firstRow.key

                                val now =
                                    android.os.SystemClock
                                        .elapsedRealtime()

                                val previousRelease =
                                    doubleUpLastReleaseMs
                                        .getAndSet(0L)

                                val previousStartedBelowTop =
                                    doubleUpLastReleaseStartedBelowTop
                                        .getAndSet(false)

                                val gap =
                                    now - previousRelease

                                val isDoubleUp =
                                    previousRelease > 0L &&
                                        previousStartedBelowTop &&
                                        gap >= 0L &&
                                        gap <=
                                            HOME_DOUBLE_UP_GAP_MS

                                doubleUpCurrentPressEligible
                                    .set(!isDoubleUp)

                                doubleUpCurrentPressStartedBelowTop
                                    .set(startedBelowTop)

                                if (
                                    isDoubleUp &&
                                    firstRow != null
                                ) {
                                    doubleUpCurrentPressEligible
                                        .set(false)

                                    doubleUpCurrentPressStartedBelowTop
                                        .set(false)

                                    doubleUpInProgress.set(true)

                                    suppressCatalogSelectionForDoubleUpRef
                                        .set(true)

                                    isFastScrollingRef.value =
                                        true

                                    fastScrollLandingVisualPendingRef
                                        .set(true)

                                    fastScrollLandingVisualPending =
                                        true

                                    focusedCatalogSelection =
                                        null

                                    expandedCatalogFocusKey =
                                        null

                                    doubleUpScope.launch {
                                        var handoffToCarousel =
                                            false

                                        try {
                                            val fullRowCount =
                                                carouselRows.size

                                            val layoutInfo =
                                                verticalRowListState
                                                    .layoutInfo

                                            val visibleItems =
                                                layoutInfo
                                                    .visibleItemsInfo

                                            val firstVisibleIndex =
                                                verticalRowListState
                                                    .firstVisibleItemIndex

                                            val lastVisibleIndex =
                                                visibleItems
                                                    .lastOrNull()
                                                    ?.index
                                                    ?: firstVisibleIndex

                                            val activeFullIndex =
                                                carouselRows
                                                    .indexOfFirst {
                                                        it.key ==
                                                            focusHolder
                                                                .activeRowKey
                                                    }
                                                    .takeIf {
                                                        it >= 0
                                                    }
                                                    ?: firstVisibleIndex

                                            val topKeepCount =
                                                minOf(
                                                    HOME_DOUBLE_UP_TOP_RUNWAY_ROWS,
                                                    fullRowCount
                                                )

                                            /*
                                             * Long trips only:
                                             *
                                             * Keep:
                                             * - top six rows
                                             * - three rows immediately above
                                             *   the current viewport
                                             * - every currently visible row
                                             * - one row just below the viewport
                                             *
                                             * Everything between those two
                                             * regions is removed BEFORE any
                                             * visible scrolling starts.
                                             */
                                            val tailStart =
                                                (
                                                    firstVisibleIndex -
                                                        HOME_DOUBLE_UP_LEAD_ROWS
                                                ).coerceAtLeast(
                                                    topKeepCount
                                                )

                                            val tailEnd =
                                                (
                                                    maxOf(
                                                        lastVisibleIndex,
                                                        activeFullIndex,
                                                        firstVisibleIndex
                                                    ) + 1
                                                ).coerceAtMost(
                                                    fullRowCount - 1
                                                )

                                            val hasMiddleToCompress =
                                                fullRowCount > 0 &&
                                                    tailStart >
                                                        topKeepCount &&
                                                    firstVisibleIndex >
                                                        topKeepCount +
                                                            HOME_DOUBLE_UP_LEAD_ROWS +
                                                            1

                                            if (
                                                hasMiddleToCompress
                                            ) {
                                                val compressedRows =
                                                    buildList {
                                                        addAll(
                                                            carouselRows
                                                                .take(
                                                                    topKeepCount
                                                                )
                                                        )

                                                        addAll(
                                                            carouselRows
                                                                .subList(
                                                                    tailStart,
                                                                    tailEnd + 1
                                                                )
                                                        )
                                                    }

                                                doubleUpCompressedRows =
                                                    compressedRows

                                                /*
                                                 * Let LazyColumn apply the
                                                 * keyed list change while the
                                                 * screen is stationary.
                                                 *
                                                 * The currently visible row
                                                 * keys remain present, so the
                                                 * viewport should stay visually
                                                 * anchored.
                                                 */
                                                withFrameNanos { }

                                                withFrameNanos { }
                                            }

                                            val velocityPxPerSecond =
                                                with(
                                                    doubleUpDensity
                                                ) {
                                                    HOME_DOUBLE_UP_VELOCITY_DP_PER_SEC
                                                        .dp
                                                        .toPx()
                                                }

                                            /*
                                             * ONE AND ONLY scroll mutation.
                                             *
                                             * There is no position reset or
                                             * second scroll session in the
                                             * middle.
                                             */
                                            verticalRowListState
                                                .scroll {
                                                    var previousFrame:
                                                        Long? = null

                                                    while (
                                                        verticalRowListState
                                                            .canScrollBackward
                                                    ) {
                                                        val frame =
                                                            withFrameNanos {
                                                                it
                                                            }

                                                        val lastFrame =
                                                            previousFrame

                                                        val dtSeconds =
                                                            if (
                                                                lastFrame ==
                                                                    null
                                                            ) {
                                                                1f / 60f
                                                            } else {
                                                                (
                                                                    (
                                                                        frame -
                                                                            lastFrame
                                                                    ) /
                                                                        1_000_000_000f
                                                                ).coerceIn(
                                                                    0f,
                                                                    0.048f
                                                                )
                                                            }

                                                        previousFrame =
                                                            frame

                                                        val requested =
                                                            -velocityPxPerSecond *
                                                                dtSeconds

                                                        val consumed =
                                                            scrollBy(
                                                                requested
                                                            )

                                                        if (
                                                            kotlin.math.abs(
                                                                consumed
                                                            ) < 0.5f &&
                                                            requested != 0f
                                                        ) {
                                                            break
                                                        }
                                                    }
                                                }

                                            /*
                                             * We are now visually at the real
                                             * first row. Restore all omitted
                                             * middle rows. They are inserted
                                             * BELOW the six-row top runway, so
                                             * the current top viewport does
                                             * not move.
                                             */
                                            doubleUpCompressedRows =
                                                null

                                            withFrameNanos { }

                                            verticalRowListState
                                                .requestScrollToItem(
                                                    0,
                                                    0
                                                )

                                            val savedItemIndex =
                                                (
                                                    uiCaches
                                                        .focusedItemByRow[
                                                            firstRow.key
                                                        ]
                                                        ?: 0
                                                ).coerceIn(
                                                    0,
                                                    (
                                                        firstRow.items.size -
                                                            1
                                                    ).coerceAtLeast(0)
                                                )

                                            focusHolder.activeRowKey =
                                                firstRow.key

                                            focusHolder.activeItemIndex =
                                                savedItemIndex

                                            activeRowKey =
                                                firstRow.key

                                            activeItemIndex =
                                                savedItemIndex

                                            withFrameNanos { }

                                            if (
                                                aggregatePlatformsEnabled
                                            ) {
                                                /*
                                                 * Do not transfer focus yet.
                                                 * Finish every piece of the
                                                 * custom fast-scroll lifecycle
                                                 * first, then use the same
                                                 * handoff as normal Up.
                                                 */
                                                handoffToCarousel =
                                                    true
                                            } else {
                                                if (
                                                    firstRow.items
                                                        .isNotEmpty()
                                                ) {
                                                    pendingRowFocus.key =
                                                        firstRow.key

                                                    pendingRowFocus.index =
                                                        savedItemIndex

                                                    pendingRowFocus
                                                        .suppressBringIntoView =
                                                        false

                                                    pendingRowFocus.nonce++
                                                } else if (
                                                    firstRow.isLoading
                                                ) {
                                                    withFrameNanos { }

                                                    runCatching {
                                                        uiCaches
                                                            .requesterFor(
                                                                firstRow.key,
                                                                "skeleton_0"
                                                            )
                                                            .requestFocus()
                                                    }
                                                }
                                            }
                                        } finally {
                                            /*
                                             * Always put the complete list
                                             * back, including cancellation or
                                             * unexpected focus loss.
                                             */
                                            doubleUpCompressedRows =
                                                null

                                            fastScrollLandingVisualPendingRef
                                                .set(false)

                                            fastScrollLandingVisualPending =
                                                false

                                            isFastScrollingRef.value =
                                                false

                                            doubleUpInProgress.set(false)

                                            if (
                                                handoffToCarousel
                                            ) {
                                                focusedCatalogSelection =
                                                    null

                                                onCarouselOpenRequested()

                                                runCatching {
                                                    carouselFocusRequester
                                                        .requestFocus()
                                                }

                                                /*
                                                 * Keep the synchronous guard
                                                 * armed through the handoff
                                                 * frame. Any queued row-focus
                                                 * or fast-scroll callback from
                                                 * the custom return therefore
                                                 * cannot reclaim catalog
                                                 * trailer ownership afterward.
                                                 */
                                                withFrameNanos { }
                                            }

                                            suppressCatalogSelectionForDoubleUpRef
                                                .set(false)
                                        }
                                    }

                                    return@onPreviewKeyEvent true
                                }
                            }
                        }

                        /*
                         * Arm the first tap only on its physical release.
                         */
                        if (
                            doubleUpPlatformShortcutEnabled &&
                            aggregatePlatformsEnabled &&
                            native.action ==
                                AndroidKeyEvent.ACTION_UP &&
                            isUp
                        ) {
                            val eligible =
                                doubleUpCurrentPressEligible
                                    .getAndSet(false)

                            val startedBelowTop =
                                doubleUpCurrentPressStartedBelowTop
                                    .getAndSet(false)

                            if (
                                eligible &&
                                startedBelowTop
                            ) {
                                doubleUpLastReleaseMs.set(
                                    android.os.SystemClock
                                        .elapsedRealtime()
                                )

                                doubleUpLastReleaseStartedBelowTop
                                    .set(true)
                            } else {
                                doubleUpLastReleaseMs.set(0L)

                                doubleUpLastReleaseStartedBelowTop
                                    .set(false)
                            }
                        }

                        /*
                         * Existing normal/held D-pad behavior.
                         */
                        if (
                            native.action ==
                                AndroidKeyEvent.ACTION_UP &&
                            isDpad
                        ) {
                            lastKeyUpTimeRef.set(
                                System.currentTimeMillis()
                            )

                            isFastScrollingRef.value =
                                false
                        }

                        if (
                            native.action ==
                                AndroidKeyEvent.ACTION_DOWN &&
                            native.repeatCount > 0 &&
                            isDpad
                        ) {
                            isFastScrollingRef.value =
                                true

                            val now =
                                System.currentTimeMillis()

                            if (
                                keyCode ==
                                    android.view.KeyEvent.KEYCODE_DPAD_UP ||
                                keyCode ==
                                    android.view.KeyEvent.KEYCODE_DPAD_DOWN
                            ) {
                                if (
                                    now -
                                        lastKeyRepeatTimeRef.get() <
                                        KEY_REPEAT_THROTTLE_MS
                                ) {
                                    return@onPreviewKeyEvent true
                                }

                                lastKeyRepeatTimeRef.set(now)
                            }
                        }

                        /*
                         * Existing normal Up-from-first-row behavior.
                         */
                        if (
                            native.action ==
                                AndroidKeyEvent.ACTION_DOWN &&
                            isUp
                        ) {
                            val isAtTopRow =
                                carouselRows
                                    .firstOrNull()
                                    ?.let {
                                        focusHolder.activeRowKey ==
                                            it.key
                                    } == true

                            if (isAtTopRow) {
                                if (
                                    isFastScrollingRef.value
                                ) {
                                    return@onPreviewKeyEvent true
                                }

                                if (
                                    aggregatePlatformsEnabled
                                ) {
                                    focusedCatalogSelection =
                                        null

                                    onCarouselOpenRequested()

                                    runCatching {
                                        carouselFocusRequester
                                            .requestFocus()
                                    }

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
                    items = doubleUpRenderedRows,
                    key = { _, row -> row.key },
                    contentType = { _, _ -> "modern_home_row" }
                ) { rowIndex, row ->
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
                    /*
                     * PATCH_CURRENT_ROW_SNAPSHOT_FOR_FOCUS_CALLBACK
                     *
                     * This lambda remains stable, but every lookup reads the
                     * latest row ordering through rememberUpdatedState.
                     */
                    val stableOnRowItemFocused = remember(Unit) {
                        { rowKey: String, index: Int, isContinueWatchingRow: Boolean, confirmedFocus: Boolean ->
                            /*
                             * Logical row focus updates also pass through this
                             * callback so hero/navigation state can stay fast.
                             * Only confirmedFocus may update state that is
                             * specifically used as confirmed restoration focus.
                             */

                            if (confirmedFocus) {
                                uiCaches.lastActuallyFocusedIndexByRow[
                                    rowKey
                                ] = index

                                /*
                                 * Publish the exact requester synchronously with
                                 * confirmed focus. The row-position cache maps
                                 * are not Compose snapshot state, so relying on
                                 * the derived global restorer alone can leave it
                                 * pointing at an older card in the same row.
                                 */
                                val confirmedRow =
                                    currentCarouselRows.firstOrNull {
                                        it.key == rowKey
                                    }
                                val confirmedItemKey =
                                    confirmedRow?.items
                                        ?.getOrNull(index)
                                        ?.key

                                if (confirmedItemKey != null) {
                                    val confirmedRequester =
                                        uiCaches.requesterFor(
                                            rowKey,
                                            confirmedItemKey
                                        )

                                    rowFocusRestorerState.value =
                                        confirmedRequester
                                }
                            }
                            /*
                             * Phase 3B: actual destination focus is now the
                             * complete fast-scroll landing signal.
                             *
                             * Keep fastScrollLandingVisualPending only until
                             * this callback so the Phase-4 hero work can still
                             * freeze the outgoing Hero during the handoff.
                             */
                            if (
                                confirmedFocus &&
                                !suppressCatalogSelectionForDoubleUpRef.get() &&
                                fastScrollLandingVisualPendingRef
                                    .compareAndSet(
                                        true,
                                        false
                                    )
                            ) {
                                fastScrollLandingVisualPending =
                                    false

                                fastScrollHeroCatchUpGeneration++
                            }

                            // If this row has no items (skeleton), clear focusedCatalogSelection
                            // so the autoplay debounce timer doesn't fire for the previously
                            // focused real item while the user is parked on a loading row.
                            val activeRow = currentCarouselRows.firstOrNull { it.key == rowKey }
                            if (activeRow != null && activeRow.items.isEmpty()) {
                                focusedCatalogSelection = null
                                expandedCatalogFocusKey = null
                            }
                            val rowBecameActive = focusHolder.activeRowKey != rowKey
                            val itemChanged = focusHolder.activeItemIndex != index
                            if (rowBecameActive || itemChanged) {
                                val now = System.currentTimeMillis()
                                val previousNavAt =
                                    lastHeroNavigationAtMsRef.get()
                                val timeSinceLastHeroNav =
                                    now - previousNavAt

                                /*
                                 * Official beta timing:
                                 *   <130 ms between focus changes -> 400 ms settle
                                 *   otherwise                       -> 450 ms settle
                                 */
                                heroFocusSettleDelayMsRef.set(
                                    if (
                                        previousNavAt != 0L &&
                                        timeSinceLastHeroNav in
                                            1 until
                                                MODERN_HERO_RAPID_NAV_THRESHOLD_MS
                                    ) {
                                        MODERN_HERO_RAPID_NAV_SETTLE_MS
                                    } else {
                                        MODERN_HERO_NORMAL_SETTLE_MS
                                    }
                                )

                                /*
                                 * Official beta only treats repeated movement
                                 * inside the SAME row as rapid horizontal nav.
                                 * Row changes must not activate this freeze.
                                 */
                                val rapidSameRowMove =
                                    !rowBecameActive &&
                                        itemChanged &&
                                        previousNavAt != 0L &&
                                        timeSinceLastHeroNav in 1..300L

                                if (rapidSameRowMove) {
                                    if (
                                        !heroFrozenForRapidNav &&
                                        !isFastScrolling &&
                                        !heroFrozenForSlide
                                    ) {
                                        val outgoingRow =
                                            currentCarouselRows.firstOrNull {
                                                it.key ==
                                                    focusHolder.activeRowKey
                                            }
                                        val outgoingHero =
                                            outgoingRow?.items
                                                ?.getOrNull(
                                                    focusHolder
                                                        .activeItemIndex
                                                )
                                                ?.heroPreview

                                        frozenHeroItem =
                                            outgoingHero ?: heroItem
                                        frozenHeroItemRowKey =
                                            focusHolder.activeRowKey
                                                ?: heroItemRowKey
                                    }

                                    heroFrozenForRapidNav = true
                                }

                                lastHeroNavigationAtMsRef.set(now)
                                lastHeroFocusChangeAtMsRef.set(now)

                                focusHolder.activeRowKey = rowKey
                                focusHolder.activeItemIndex = index
                                activeRowKey = rowKey
                                activeItemIndex = index
                            }
                            if (uiCaches.focusedItemByRow[rowKey] != index) {
                                uiCaches.focusedItemByRow[rowKey] = index
                                uiCaches.userInteractedRows.add(rowKey)
                            }
                            if (isContinueWatchingRow) {
                                if (lastFocusedContinueWatchingIndexRef.get() != index) {
                                    lastFocusedContinueWatchingIndexRef.set(index)
                                }
                                /*
                                 * Continue Watching does not publish a
                                 * FocusedCatalogSelection. The settled Hero
                                 * pipeline owns the destination backdrop, so
                                 * only clear catalog trailer ownership here.
                                 */
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
                        {
                            pendingRowFocus.key = null
                            pendingRowFocus.index = null
                            pendingRowFocus.suppressBringIntoView = false
                            Unit
                        }
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
                            val expandedKey = expandedCatalogFocusKey
                            expandedKey != null && (
                                row.items.any { (it.payload as? ModernPayload.Catalog)?.focusKey == expandedKey } ||
                                expandedKey.startsWith(row.key + "::")
                            )
                        }
                    }
                    ModernRowSection(
                        row = row,
                        myListSlotGeneration = myListSlotGeneration,
                        forceContinueWatchingRestoreToStart =
                            forceContinueWatchingRestoreToStart,
                        showHeavyOverlays = true,
                        heavyOverlayAlpha =
                            fullyVisibleOverlayAlphaState,
                        cardDepthAlpha =
                            fullyVisibleOverlayAlphaState,
                        rowTitleBottom = rowTitleBottom,
                        hideNewSeasonBadge =
                            hideNewSeasonBadge,
                        numberStyle = row.numberStyle,
                        isFirstRow = carouselRows.firstOrNull()?.key == row.key,
                        isSecondRow = carouselRows.getOrNull(1)?.key == row.key,
                        catalogSlideAnimatable = catalogSlideAlpha,
                        onRequestCarouselFocus = stableOnRequestCarouselFocus,
                        defaultBringIntoViewSpec = defaultBringIntoViewSpec,
                        focusStateCatalogRowScrollStates = focusStateCatalogRowScrollStates,
                        uiCaches = uiCaches,
                        pendingRowFocus = pendingRowFocus,
                        onPendingRowFocusCleared = stableOnPendingRowFocusCleared,
                        onRowItemFocused = stableOnRowItemFocused,
                        useLandscapePosters = useLandscapePosters || row.key in landscapeCatalogKeys,
                        heroMetadataLarge =
                            if (effectiveFullWidthIconRowEnabled) {
                                heroMetadataLarge
                            } else {
                                true
                            },
                        perCatalogLandscape = !useLandscapePosters && row.key in landscapeCatalogKeys,
                        showLabels = posterLabelsEnabled,
                        posterCardCornerRadius = posterCardCornerRadius,
                        focusedPosterBackdropTrailerMuted = focusedPosterBackdropTrailerMuted,
                        effectiveExpandEnabled = effectiveExpandEnabled,
                        effectiveAutoplayEnabled = effectiveAutoplayEnabled && row.items.isNotEmpty(),
                        expandLandscapePostersEnabled = expandLandscapePostersEnabled,
                        trailerPlaybackTarget = trailerPlaybackTarget,
                        expandedCatalogFocusKey = rowExpandedFocusKey,
                        expandedTrailerPreviewUrl = if (rowHasExpanded) expandedCatalogTrailerUrl else null,
                        expandedTrailerPreviewAudioUrl = if (rowHasExpanded) expandedCatalogTrailerAudioUrl else null,
                        modernCatalogCardWidth = if (useLandscapePosters || row.key in landscapeCatalogKeys) portraitBaseWidth * 1.24f * 1.34f else modernCatalogCardWidth,
                        modernCatalogCardHeight = if (useLandscapePosters || row.key in landscapeCatalogKeys) (portraitBaseWidth * 1.24f * 1.34f) / 1.77f else modernCatalogCardHeight,
                        landscapeTrailerExpandedHeight = portraitBaseHeight * 0.84f * 1.08f,
                        continueWatchingCardWidth = continueWatchingCardWidth,
                        continueWatchingCardHeight = continueWatchingCardHeight,
                        continueWatchingCardStyle = uiState.continueWatchingCardStyle,
                        onContinueWatchingClick = onContinueWatchingClick,
                        onContinueWatchingOptions = stableOnContinueWatchingOptions,
                        isCatalogItemWatched = isCatalogItemWatched,
                        onCatalogItemLongPress = onCatalogItemLongPress,
                        onItemFocus = gatedOnItemFocus,
                        onPreloadAdjacentItem = gatedOnPreloadAdjacentItem,
                        onCatalogSelectionFocused = gatedOnCatalogSelectionFocused,
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

        // Unified slide+fade wrapper — HeroTitleBlock and LazyColumn animate as one
        // block so recomposition at the flip point is invisible (happens at alpha=0).
        // Only driven by platform navigation, never by catalog row focus changes.
        // Expand layout width by max slide distance so LazyRows render the
        // off-screen card that slides into view during platform parallax transition.
        // BringIntoViewSpec in ModernHomeRows clamps containerSize to real screen width
        // so end padding / expansion scroll behavior is unaffected.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layout { measurable, constraints ->
                    val extraPx = catalogSlideDistancePx.toInt()
                    val widened = constraints.copy(
                        maxWidth = (constraints.maxWidth + extraPx).coerceAtMost(constraints.maxWidth * 2),
                        minWidth = constraints.minWidth
                    )
                    val placeable = measurable.measure(widened)
                    layout(constraints.maxWidth, placeable.height) {
                        placeable.placeRelativeWithLayer(0, 0)
                    }
                }
                .drawWithContent {
                    if (!(ghostVisible && catalogGhostBitmap != null)) drawContent()
                    if (ghostCaptureTick != catalogGhostCapturedTick.get()) {
                        catalogGhostLayer.record { this@drawWithContent.drawContent() }
                        catalogGhostCapturedTick.set(ghostCaptureTick)
                    }
                    val cBmp = catalogGhostBitmap
                    if (ghostVisible && cBmp != null) {
                        drawImage(
                            image = cBmp,
                            dstOffset = androidx.compose.ui.unit.IntOffset(Math.round(ghostOffset.value), 0),
                            dstSize = androidx.compose.ui.unit.IntSize(cBmp.width, cBmp.height),
                            alpha = 1f,
                            filterQuality = androidx.compose.ui.graphics.FilterQuality.None
                        )
                        // Fade-to-black overlay: (1-f) * correct composite, no
                        // mid-fade brightening. Icon row sits above this node.
                        val fade = 1f - ghostAlpha.value
                        if (fade > 0f) {
                            drawRect(
                                color = androidx.compose.ui.graphics.Color.Black,
                                alpha = fade.coerceIn(0f, 1f)
                            )
                        }
                    }
                    // ENTER fade-from-black: when the ghost is gone (enter
                    // phase), the live content is at full alpha; fade a single
                    // black rect over the whole composite from opaque to clear
                    // as catalogSlideAlpha ramps 0->1. This replaces the old
                    // per-node alpha fade so no inter-layer transparency shows.
                    if (!ghostVisible) {
                        val enterBlack = 1f - catalogSlideAlpha.value
                        if (enterBlack > 0f) {
                            drawRect(
                                color = androidx.compose.ui.graphics.Color.Black,
                                alpha = enterBlack.coerceIn(0f, 1f)
                            )
                        }
                    }
                }
                .graphicsLayer {
                    alpha = 1f
                    translationX = catalogSlideOffset.value
                }
        ) {
            HeroTitleBlock(
                preview = resolvedHero,
                enrichmentActive = enrichmentActive,
                portraitMode = !useLandscapePosters,
                selectedPlatformId = catalogDisplayedPlatformId,
                platformNavDirection = if (aggregatePlatformsEnabled && !enrichmentActive && !isPlatformTransitioning) platformNavDirection else 0,
                platformTransitionSnap = isPlatformTransitioning,
                fullWidthIconRowEnabled = effectiveFullWidthIconRowEnabled,
                heroMetadataLarge =
                    if (effectiveFullWidthIconRowEnabled) heroMetadataLarge else true,
                modifier = if (effectiveFullWidthIconRowEnabled) {
                    Modifier
                        // Full-width icon mode owns the newer centered hero region
                        // and its configurable Small/Large metadata scale.
                        .align(Alignment.TopStart)
                        .padding(
                            start = rowHorizontalPadding,
                            end = 48.dp,
                            top = heroRegionTopInset
                        )
                        .height(heroRegionHeight)
                        .wrapContentHeight(align = Alignment.CenterVertically)
                        /*
                         * Slightly raise the complete centered hero cluster.
                         * This applies equally to both Small and Large metadata
                         * modes without changing their internal dimensions.
                         */
                        .offset(
                            y =
                                if (uiState.hidePlatformIconsOnRowExitEnabled) {
                                    (-6).dp
                                } else {
                                    0.dp
                                }
                        )
                        .fillMaxWidth(MODERN_HERO_TEXT_WIDTH_FRACTION)
                } else {
                    Modifier
                        // Exact 0.4.20 placement: bottom-anchor the unscaled hero
                        // 16dp above the catalog viewport.
                        .align(Alignment.BottomStart)
                        .padding(
                            start = rowHorizontalPadding,
                            end = 48.dp,
                            bottom = catalogBottomPadding +
                                rowsViewportHeight +
                                heroToCatalogGap
                        )
                        .offset(y = 6.dp)
                        .fillMaxWidth(MODERN_HERO_TEXT_WIDTH_FRACTION)
                }
            )

            /*
             * Persistent Coming Soon pill.
             *
             * This is a sibling of HeroTitleBlock and the rows LazyColumn.
             * Its dimensions therefore cannot change catalog-row measurement
             * or move poster rows.
             *
             * resolvedHero is the authoritative visible Hero presentation,
             * so the shell survives title-to-title DPAD navigation instead of
             * being recreated for each ModernRowSection.
             */
            /*
             * Coming Soon presentation now lives one level above this content,
             * outside Home's Haze source.
             *
             * Modern Home remains authoritative for WHICH label belongs to the
             * currently presented Hero.
             */
            val currentComingSoonGlassText =
                resolvedHero
                    ?.comingSoonText
                    ?.takeIf { it.isNotBlank() }

            var retainedComingSoonGlassText by
                remember {
                    mutableStateOf<String?>(null)
                }

            val freezeComingSoonGlassForPopup =
                homePopupGlassEnvironment.catalogOptionsVisible ||
                    homePopupGlassEnvironment
                        .catalogOptionsFocusRestoreActive

            val latestOnComingSoonGlassTextChanged by
                rememberUpdatedState(
                    onComingSoonGlassTextChanged
                )

            LaunchedEffect(
                currentComingSoonGlassText,
                freezeComingSoonGlassForPopup
            ) {
                retainedComingSoonGlassText =
                    if (freezeComingSoonGlassForPopup) {
                        /*
                         * Popup focus can temporarily clear the active poster.
                         * Do not let that destroy the label/shell.
                         */
                        currentComingSoonGlassText
                            ?: retainedComingSoonGlassText
                    } else {
                        currentComingSoonGlassText
                    }

                latestOnComingSoonGlassTextChanged(
                    retainedComingSoonGlassText
                )
            }

            androidx.compose.runtime.DisposableEffect(Unit) {
                onDispose {
                    latestOnComingSoonGlassTextChanged(null)
                }
            }

            EnhancedModernHomeRowsListBoundary(
                modifier = Modifier.align(Alignment.BottomStart),
                carouselRows = stableCarouselRowsForRowsBoundary,
                rowIndexByKey = stableRowIndexByKeyForRowsBoundary,
                landscapeCatalogKeys =
                    stableLandscapeCatalogKeysForRowsBoundary,
                focusStateFocusedRowKey =
                    rowsBoundaryFocusedRowKey,
                focusStateCatalogRowScrollStates =
                    stableCatalogRowScrollStatesForRowsBoundary,
                posterLabelsEnabled =
                    rowsBoundaryPosterLabelsEnabled,
                hideNewSeasonBadge =
                    uiState.hideNewSeasonBadge,
                focusedPosterBackdropTrailerMuted =
                    rowsBoundaryFocusedPosterBackdropTrailerMuted,
                activeRowKeyState =
                    rowsBoundaryActiveRowKeyState,
                activeItemIndexState =
                    rowsBoundaryActiveItemIndexState,
                isFastScrollingState =
                    rowsBoundaryIsFastScrollingState,
                fastScrollLandingVisualPendingState =
                    rowsBoundaryFastScrollLandingVisualPendingState,
                focusedCatalogSelectionState =
                    rowsBoundaryFocusedCatalogSelectionState,
                expandedCatalogFocusKeyState =
                    rowsBoundaryExpandedCatalogFocusKeyState,
                expansionInteractionNonceState =
                    rowsBoundaryExpansionInteractionNonceState,
                heroFrozenForRapidNavState =
                    rowsBoundaryHeroFrozenForRapidNavState,
                heroFrozenForSlideState =
                    rowsBoundaryHeroFrozenForSlideState,
                heroItemState =
                    rowsBoundaryHeroItemState,
                heroItemRowKeyState =
                    rowsBoundaryHeroItemRowKeyState,
                frozenHeroItemState =
                    rowsBoundaryFrozenHeroItemState,
                frozenHeroItemRowKeyState =
                    rowsBoundaryFrozenHeroItemRowKeyState,
                optionsItemState =
                    rowsBoundaryOptionsItemState,
                fastScrollHeroCatchUpGenerationState =
                    rowsBoundaryFastScrollHeroCatchUpGenerationState,
                currentCarouselRowsState =
                    rowsBoundaryCurrentCarouselRowsState,
                focusHolder = focusHolder,
                aggregatePlatformsEnabled =
                    aggregatePlatformsEnabled,
                doubleUpPlatformShortcutEnabled =
                    uiState.doubleUpPlatformShortcutEnabled,
                isVerticalRowsScrolling =
                    isVerticalRowsScrolling,
                rowsViewportHeight =
                    rowsViewportHeight,
                catalogBottomPadding =
                    catalogBottomPadding,
                rowTitleBottom =
                    rowTitleBottom,
                useLandscapePosters =
                    useLandscapePosters,
                effectiveExpandEnabled =
                    effectiveExpandEnabled,
                effectiveAutoplayEnabled =
                    effectiveAutoplayEnabled,
                expandLandscapePostersEnabled =
                    uiState.expandLandscapePostersEnabled,
                trailerPlaybackTarget =
                    trailerPlaybackTarget,
                expandedCatalogTrailerUrl =
                    expandedCatalogTrailerUrl,
                expandedCatalogTrailerAudioUrl =
                    expandedCatalogTrailerAudioUrl,
                fullyVisibleOverlayAlphaState =
                    fullyVisibleOverlayAlphaState,
                myListSlotGeneration =
                    myListSlotGeneration,
                forceContinueWatchingRestoreToStart =
                    forceContinueWatchingRestoreToStart,
                posterCardCornerRadius =
                    posterCardCornerRadius,
                portraitBaseWidth =
                    portraitBaseWidth,
                portraitBaseHeight =
                    portraitBaseHeight,
                modernCatalogCardWidth =
                    modernCatalogCardWidth,
                modernCatalogCardHeight =
                    modernCatalogCardHeight,
                continueWatchingCardWidth =
                    continueWatchingCardWidth,
                continueWatchingCardHeight =
                    continueWatchingCardHeight,
                useThemeColorForNumbers =
                    useThemeColorForNumbers,
                verticalRowListState =
                    verticalRowListState,
                verticalRowBringIntoViewSpec =
                    verticalRowBringIntoViewSpec,
                contentFocusRequester =
                    contentFocusRequester,
                focusRestorerRequester =
                    focusRestorerRequester,
                carouselFocusRequester =
                    carouselFocusRequester,
                uiCaches =
                    uiCaches,
                pendingRowFocus =
                    pendingRowFocus,
                rowFocusRestorerState =
                    rowFocusRestorerState,
                catalogSlideAlpha =
                    catalogSlideAlpha,
                defaultBringIntoViewSpec =
                    defaultBringIntoViewSpec,
                onCarouselOpenRequested =
                    rowsOnCarouselOpenRequested,
                onContinueWatchingClick =
                    rowsOnContinueWatchingClick,
                isCatalogItemWatched =
                    rowsIsCatalogItemWatched,
                onCatalogItemLongPress =
                    rowsOnCatalogItemLongPress,
                gatedOnItemFocus =
                    rowsOnItemFocus,
                gatedOnPreloadAdjacentItem =
                    rowsOnPreloadAdjacentItem,
                gatedOnCatalogSelectionFocused =
                    rowsOnCatalogSelectionFocused,
                wrappedOnNavigateToDetail =
                    rowsOnNavigateToDetail,
                onLoadMoreCatalog =
                    rowsOnLoadMoreCatalog
            )

        } // end unified slide+fade Box
    }

    val selectedOptionsItem = optionsItem
    if (selectedOptionsItem != null) {
        ContinueWatchingOptionsDialog(
            item = selectedOptionsItem,
            onDismiss = { optionsItem = null },
            onRemove = {
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

internal fun shouldPreserveExpandedTrailerForPopup(
    playTrailerInExpandedCard: Boolean,
    trailerFirstFrameRendered: Boolean
): Boolean =
    playTrailerInExpandedCard && trailerFirstFrameRendered

internal fun shouldSuppressFocusedPosterAutoplayForPopup(
    popupVisible: Boolean,
    preservePlayingTrailer: Boolean
): Boolean =
    popupVisible && !preservePlayingTrailer
