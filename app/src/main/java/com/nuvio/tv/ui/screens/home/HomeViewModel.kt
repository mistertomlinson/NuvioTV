package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.player.StreamAutoPlayPolicy
import com.nuvio.tv.core.tmdb.TmdbEnrichment
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StartupAuthNotice
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.core.homechannel.HomeScreenChannelWorker
import com.nuvio.tv.core.homechannel.HomeScreenChannelManager
import com.nuvio.tv.data.trailer.TrailerService
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import androidx.compose.ui.unit.dp
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.Collections
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.buildTrackingMediaReference
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.HomeEnrichmentDiskCache
import com.nuvio.tv.data.repository.TrackingRatingCoordinator
import com.nuvio.tv.data.repository.parseContentIds
import javax.inject.Inject
import android.os.SystemClock
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.MyListDiskCache
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.repository.MDBListRepository
import com.nuvio.tv.domain.model.MDBListSettings
import com.nuvio.tv.domain.model.Collection
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flowOn

@Volatile private var homeViewModelActiveInstanceId: Int = -1

@OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    internal val addonRepository: AddonRepository,
    internal val catalogRepository: CatalogRepository,
    internal val watchProgressRepository: WatchProgressRepository,
    internal val libraryRepository: LibraryRepository,
    internal val metaRepository: MetaRepository,
    internal val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    internal val playerSettingsDataStore: PlayerSettingsDataStore,
    internal val tmdbSettingsDataStore: TmdbSettingsDataStore,
    internal val traktSettingsDataStore: TraktSettingsDataStore,
    internal val authSessionNoticeDataStore: AuthSessionNoticeDataStore,
    internal val tmdbService: TmdbService,
    internal val tmdbMetadataService: TmdbMetadataService,
    internal val trailerService: TrailerService,
    internal val watchedItemsPreferences: WatchedItemsPreferences,
    internal val watchProgressPreferences: com.nuvio.tv.data.local.WatchProgressPreferences,
    @ApplicationContext internal val appContext: Context,
    internal val homeScreenChannelManager: HomeScreenChannelManager,
    internal val profileManager: ProfileManager,
    internal val cwEnrichmentCache: ContinueWatchingEnrichmentCache,
    internal val collectionsDataStore: com.nuvio.tv.data.local.CollectionsDataStore,
    internal val mdbListSettingsDataStore: com.nuvio.tv.data.local.MDBListSettingsDataStore,
    internal val mdbListRepository: com.nuvio.tv.data.repository.MDBListRepository,
    internal val watchedSeriesStateHolder: com.nuvio.tv.data.local.WatchedSeriesStateHolder,
    internal val homeEnrichmentDiskCache: HomeEnrichmentDiskCache,
    internal val myListDiskCache: MyListDiskCache,
    internal val trackingRatingCoordinator: TrackingRatingCoordinator,
    internal val homeTrailerPlayerHolder: com.nuvio.tv.ui.components.HomeTrailerPlayerHolder,
) : ViewModel() {
    companion object {
        @Volatile internal var activeInstanceId: Int = -1
        internal const val TAG = "HomeViewModel"
        internal const val MY_LIST_ADDON_ID = "nuvio.mylist"
        internal const val MY_LIST_CATALOG_ID = "mylist"
        internal const val MY_LIST_CATALOG_KEY = "nuvio.mylist_mixed_mylist"
        internal const val STARTUP_GRACE_PERIOD_MS = 3_000L
        internal const val CONTINUE_WATCHING_ENRICHMENT_GRACE_PERIOD_MS = 1_000L
        private const val CONTINUE_WATCHING_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_RECENT_PROGRESS_ITEMS = 300
        private const val MAX_NEXT_UP_LOOKUPS = 24
        private const val MAX_NEXT_UP_CONCURRENCY = 6
        private const val MAX_CATALOG_LOAD_CONCURRENCY = 6
        internal const val EXTERNAL_META_PREFETCH_FOCUS_DEBOUNCE_MS = 220L
        internal const val EXTERNAL_META_PREFETCH_ADJACENT_DEBOUNCE_MS = 120L
        internal const val MAX_POSTER_STATUS_OBSERVERS = 24
    }

    internal val _uiState = MutableStateFlow(HomeUiState(posterCardWidthDp = 0, posterCardHeightDp = 0))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    // Addon-signaled landscape keys: rows where the addon itself sends posterShape=LANDSCAPE.
    // Kept separate from user prefs so settings changes never wipe them out.
    internal val addonSignaledLandscapeKeys = mutableSetOf<String>()
    /** True once the CW pipeline has completed its first emission (items or empty). */
    internal val _initialCwResolved = MutableStateFlow(false)
    val initialCwResolved: StateFlow<Boolean> = _initialCwResolved.asStateFlow()
    val effectiveAutoplayEnabled = playerSettingsDataStore.playerSettings
        .map(StreamAutoPlayPolicy::isEffectivelyEnabled)
        .distinctUntilChanged()
    internal val _fullCatalogRows = MutableStateFlow<List<CatalogRow>>(emptyList())
    val fullCatalogRows: StateFlow<List<CatalogRow>> = _fullCatalogRows.asStateFlow()

    private val _focusState = MutableStateFlow(HomeScreenFocusState())
    val focusState: StateFlow<HomeScreenFocusState> = _focusState.asStateFlow()

    private val _gridFocusState = MutableStateFlow(HomeScreenFocusState())
    val gridFocusState: StateFlow<HomeScreenFocusState> = _gridFocusState.asStateFlow()

    internal val _loadingCatalogs = MutableStateFlow<Set<String>>(emptySet())
    val loadingCatalogs: StateFlow<Set<String>> = _loadingCatalogs.asStateFlow()

    internal val _enrichingItemId = MutableStateFlow<String?>(null)
    val enrichingItemId: StateFlow<String?> = _enrichingItemId.asStateFlow()

    // True once all platform-screen first-item backdrops have been preloaded into Coil.
    // The loading gate in HomeScreen waits on this before showing content.
    internal val _platformBackdropsPreloaded = MutableStateFlow(false)
    val platformBackdropsPreloaded: StateFlow<Boolean> = _platformBackdropsPreloaded.asStateFlow()
    // Backdrop render dimensions from Compose — used to match Coil cache keys.
    @Volatile internal var backdropPreloadWidthPx: Int = 0
    @Volatile internal var backdropPreloadHeightPx: Int = 0
    fun setBackdropPreloadSize(widthPx: Int, heightPx: Int) {
        backdropPreloadWidthPx = widthPx
        backdropPreloadHeightPx = heightPx
    }

    // True once the first hero backdrop is in Coil's memory cache (or we gave up).
    internal val _heroBackdropWarm = MutableStateFlow(false)
    val heroBackdropWarm: StateFlow<Boolean> = _heroBackdropWarm.asStateFlow()
    @Volatile private var heroWarmStarted = false

    /*
     * Compute the hero request size from display metrics instead of waiting for
     * Compose to report it. triggerPlatformPreloadIfReady blocks on
     * backdropPreloadWidthPx/HeightPx, which were only ever set from
     * ModernHomeContent -- and that cannot compose until the loading gate opens,
     * so no preload could ever finish before release. Seeding the size here
     * breaks that cycle; Compose still calls setBackdropPreloadSize afterward
     * and overwrites with the authoritative value.
     */
    fun seedBackdropPreloadSizeFromDisplay(useLandscapePosters: Boolean) {
        if (backdropPreloadWidthPx > 0 && backdropPreloadHeightPx > 0) return
        val metrics = appContext.resources.displayMetrics
        val density = androidx.compose.ui.unit.Density(
            density = metrics.density,
            fontScale = 1f
        )
        val widthDp = (metrics.widthPixels / metrics.density).dp
        val heightDp = (metrics.heightPixels / metrics.density).dp
        val heroHeightDp = computeHeroBackdropHeightDp(
            maxHeightDp = heightDp,
            useLandscapePosters = useLandscapePosters,
            rowTitleHeightDp = MODERN_ROW_TITLE_HEIGHT_FALLBACK
        )
        with(density) {
            backdropPreloadWidthPx = (widthDp * MODERN_HERO_MEDIA_WIDTH_FRACTION).roundToPx()
            backdropPreloadHeightPx = heroHeightDp.roundToPx()
        }
    }

    /*
     * Warm the first hero backdrop into Coil before the gate releases. The
     * renderer (ModernHeroMediaLayer) checks the memory cache and flips
     * instantly on a hit, which is why profile switches fade correctly and cold
     * launches snap. 2.5s bound: on a slow network we release anyway and get
     * today's behaviour rather than a hang.
     */
    fun warmFirstHeroBackdrop(useLandscapePosters: Boolean) {
        if (heroWarmStarted) return
        heroWarmStarted = true
        viewModelScope.launch {
            seedBackdropPreloadSizeFromDisplay(useLandscapePosters)
            val url = kotlinx.coroutines.withTimeoutOrNull(2_000L) {
                _uiState.first { it.heroItems.isNotEmpty() }
                    .heroItems.firstOrNull()?.backdropUrl?.takeIf { it.isNotBlank() }
            }
            if (url == null) {
                _heroBackdropWarm.value = true
                return@launch
            }
            val w = backdropPreloadWidthPx
            val h = backdropPreloadHeightPx
            /*
             * Time the preload itself rather than trying to detect "cache was
             * cleared" directly - there's no OS signal for that. A call that
             * resolves near-instantly means the image was already in memory
             * (profile switch, back-nav, warm process). One slow enough to
             * need real disk or network I/O means it wasn't, which in
             * practice means a cold process start or a cleared cache. Either
             * way the visible screen is busier settling in, so a longer
             * curtain fade suits it. Heuristic, not exact: a warm cache on a
             * badly congested network could occasionally read as cold - worst
             * case is a slightly longer fade than strictly necessary, never a
             * wrong image or a stuck gate.
             */
            kotlinx.coroutines.withTimeoutOrNull(2_500L) {
                runCatching {
                    coil.Coil.imageLoader(appContext).execute(
                        coil.request.ImageRequest.Builder(appContext)
                            .data(url)
                            .apply { if (w > 0 && h > 0) size(width = w, height = h) }
                            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                            .build()
                    )
                }
            }
            _heroBackdropWarm.value = true
        }
    }
    // Platform catalog tracking — keys identified at skeleton-seed time, decremented
    // as each platform catalog resolves (success or error).
    internal val pendingPlatformCatalogKeys = Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile internal var platformPreloadTriggered = false
    @Volatile internal var platformPreloadInProgress = false
    internal fun setEnrichingItemId(id: String?) { _enrichingItemId.value = id }

    // True once the enrichment disk cache has been restored into memory after a
    // pipeline reset. The row-readiness gate defers promotions until then, so
    // readiness is never judged against a half-restored cache (uneven metadata
    // after profile switches). Rows stay gated (shimmer) those extra ~tens of ms.
    @Volatile internal var enrichmentRestoreComplete: Boolean = true

    // Items currently exposed to Home/Compose/enrichment.
    internal val catalogsMap: MutableMap<String, CatalogRow> = Collections.synchronizedMap(LinkedHashMap())

    // Complete server pages retained only for releasing items to Home in
    // 25-item windows. Nothing should render or enrich directly from this map.
    internal val catalogSourceRows: MutableMap<String, CatalogRow> =
        Collections.synchronizedMap(LinkedHashMap())

    internal val catalogOrder = mutableListOf<String>()
    internal var addonsCache: List<Addon> = emptyList()

    fun forceReloadCatalogs() {
        scheduleCatalogPipeline(addonsCache, forceReload = true)
    }

    fun setHomeHeroTrailerPlaying(playing: Boolean) {
        homeHeroTrailerPlaying = playing
    }

    fun prioritizeModernHomeRows(rowKeys: List<String>) {
        val normalized = rowKeys.distinct()

        if (modernHomePriorityRowKeys == normalized) {
            return
        }

        /*
         * Viewport priority is advisory. Record the latest rows for the next
         * naturally-created enrichment plan, but never invalidate or rebuild
         * active work merely because the user navigated vertically or changed
         * streaming platforms.
         */
        modernHomePriorityRowKeys = normalized
    }

    suspend fun preloadCachedHomeViewport() {
        kotlinx.coroutines.withContext(
            kotlinx.coroutines.Dispatchers.IO
        ) {
            kotlinx.coroutines.withTimeoutOrNull(900L) {
                val state = _uiState.value

                val populatedRows = state.catalogRows
                    .filter { it.items.isNotEmpty() }

                if (populatedRows.isEmpty()) {
                    return@withTimeoutOrNull
                }

                val savedFocus = _focusState.value

                /*
                 * Modern Home's indexes include Continue Watching when that
                 * row exists, but catalogRows contains catalog rows only.
                 */
                val continueWatchingOffset =
                    if (
                        state.continueWatchingItems.isNotEmpty()
                    ) {
                        1
                    } else {
                        0
                    }

                val focusedKeyIndex =
                    savedFocus.focusedRowKey
                        ?.let { savedRowKey ->
                            populatedRows.indexOfFirst { row ->
                                row.key() == savedRowKey
                            }
                        }
                        ?.takeIf { it >= 0 }

                val focusedIndexFallback =
                    (
                        savedFocus.focusedRowIndex -
                            continueWatchingOffset
                        )
                        .takeIf {
                            it in populatedRows.indices
                        }

                val verticalIndexFallback =
                    (
                        savedFocus.verticalScrollIndex -
                            continueWatchingOffset
                        )
                        .takeIf {
                            it in populatedRows.indices
                        }

                val primaryRowIndex =
                    focusedKeyIndex
                        ?: focusedIndexFallback
                        ?: verticalIndexFallback
                        ?: 0

                val primaryRow =
                    populatedRows[primaryRowIndex]

                val primaryRowKey =
                    primaryRow.key()

                val savedFocusedItemIndex =
                    if (
                        savedFocus.focusedRowKey ==
                        primaryRowKey
                    ) {
                        savedFocus.focusedItemIndex
                    } else {
                        savedFocus
                            .catalogRowScrollStates[
                                primaryRowKey
                            ]
                            ?: 0
                    }

                val focusedItemIndex =
                    savedFocusedItemIndex.coerceIn(
                        0,
                        primaryRow.items.lastIndex
                    )

                val savedVisibleStartIndex =
                    savedFocus
                        .catalogRowScrollStates[
                            primaryRowKey
                        ]
                        ?: (
                            focusedItemIndex - 2
                            ).coerceAtLeast(0)

                val visibleStartIndex =
                    savedVisibleStartIndex.coerceIn(
                        0,
                        primaryRow.items.lastIndex
                    )

                val focusedItem =
                    primaryRow.items[focusedItemIndex]

                val adjacentRows = buildList {
                    populatedRows
                        .getOrNull(primaryRowIndex - 1)
                        ?.let { add(it) }

                    populatedRows
                        .getOrNull(primaryRowIndex + 1)
                        ?.let { add(it) }
                }

                val metrics =
                    appContext.resources.displayMetrics

                val posterWidthPx =
                    (
                        state.posterCardWidthDp *
                            metrics.density
                        )
                        .toInt()
                        .coerceAtLeast(1)

                val posterHeightPx =
                    (
                        state.posterCardHeightDp *
                            metrics.density
                        )
                        .toInt()
                        .coerceAtLeast(1)

                val screenWidthPx =
                    metrics.widthPixels.coerceAtLeast(1)

                val screenHeightPx =
                    metrics.heightPixels.coerceAtLeast(1)

                fun cardImageUrl(
                    row: CatalogRow,
                    item: MetaPreview
                ): String? {
                    val landscape =
                        state.modernLandscapePostersEnabled ||
                            row.key() in
                            state.landscapeCatalogKeys

                    return if (landscape) {
                        item.landscapePoster
                            ?.takeIf { it.isNotBlank() }
                            ?: item.background
                                ?.takeIf { it.isNotBlank() }
                            ?: item.poster
                                ?.takeIf { it.isNotBlank() }
                    } else {
                        item.poster
                            ?.takeIf { it.isNotBlank() }
                            ?: item.landscapePoster
                                ?.takeIf { it.isNotBlank() }
                            ?: item.background
                                ?.takeIf { it.isNotBlank() }
                    }
                }

                val requests = buildList {
                    focusedItem.background
                        ?.takeIf { it.isNotBlank() }
                        ?.let { url ->
                            add(
                                Triple(
                                    url,
                                    screenWidthPx,
                                    screenHeightPx
                                )
                            )
                        }

                    focusedItem.logo
                        ?.takeIf { it.isNotBlank() }
                        ?.let { url ->
                            add(
                                Triple(
                                    url,
                                    screenWidthPx / 2,
                                    screenHeightPx / 3
                                )
                            )
                        }

                    /*
                     * Predecode the horizontally visible portion of the
                     * remembered row instead of always starting at card one.
                     */
                    primaryRow.items
                        .drop(visibleStartIndex)
                        .take(8)
                        .forEach { item ->
                            cardImageUrl(
                                primaryRow,
                                item
                            )?.let { url ->
                                add(
                                    Triple(
                                        url,
                                        posterWidthPx,
                                        posterHeightPx
                                    )
                                )
                            }
                        }

                    adjacentRows.forEach { row ->
                        val rowKey = row.key()

                        val adjacentStartIndex =
                            (
                                savedFocus
                                    .catalogRowScrollStates[
                                        rowKey
                                    ]
                                    ?: 0
                                )
                                .coerceIn(
                                    0,
                                    row.items.lastIndex
                                )

                        row.items
                            .drop(adjacentStartIndex)
                            .take(4)
                            .forEach { item ->
                                cardImageUrl(
                                    row,
                                    item
                                )?.let { url ->
                                    add(
                                        Triple(
                                            url,
                                            posterWidthPx,
                                            posterHeightPx
                                        )
                                    )
                                }
                            }
                    }
                }
                    .distinctBy { it.first }

                val loader =
                    coil.Coil.imageLoader(appContext)

                /*
                 * Decode at most four cached viewport images concurrently.
                 * Batches remain small so image work cannot overwhelm launch,
                 * enrichment, or trailer playback.
                 */
                requests
                    .chunked(4)
                    .forEach { batch ->
                        coroutineScope {
                            batch.map {
                                (
                                    url,
                                    width,
                                    height
                                ) ->
                                async {
                                    val request =
                                        coil.request.ImageRequest
                                            .Builder(appContext)
                                            .data(url)
                                            .size(
                                                width = width,
                                                height = height
                                            )
                                            .memoryCachePolicy(
                                                coil.request.CachePolicy.ENABLED
                                            )
                                            .diskCachePolicy(
                                                coil.request.CachePolicy.ENABLED
                                            )
                                            .build()

                                    try {
                                        loader.execute(request)
                                    } catch (
                                        cancellation:
                                            kotlinx.coroutines.CancellationException
                                    ) {
                                        throw cancellation
                                    } catch (_: Exception) {
                                        /*
                                         * A stale image cannot delay Home or
                                         * affect the loader animation cycle.
                                         */
                                    }
                                }
                            }.awaitAll()
                        }
                    }
            }
        }
    }
    internal var homeCatalogOrderKeys: List<String> = emptyList()
    internal var shuffledCatalogKeys: Set<String> = emptySet()
    internal var lastShuffleTimestampMs: Long = 0L
    internal var disabledHomeCatalogKeys: Set<String> = emptySet()
    internal var _numberedCatalogKeysSet = MutableStateFlow<Set<String>>(emptySet())
    val numberedHomeCatalogKeys: Set<String>
        get() = _numberedCatalogKeysSet.value
    internal var _outlineNumberedCatalogKeysSet = MutableStateFlow<Set<String>>(emptySet())
    val outlineNumberedHomeCatalogKeys: Set<String>
        get() = _outlineNumberedCatalogKeysSet.value
    internal var _useThemeColorForNumbers = MutableStateFlow<Boolean>(false)
    val useThemeColorForNumbers: Boolean
        get() = _useThemeColorForNumbers.value
    internal var currentHeroCatalogKeys: List<String> = emptyList()
    internal var catalogUpdateJob: Job? = null
    internal var hasRenderedFirstCatalog = false
    internal val catalogLoadSemaphore = Semaphore(MAX_CATALOG_LOAD_CONCURRENCY)
    internal var pendingCatalogLoads = 0
    internal val activeCatalogLoadJobs = mutableSetOf<Job>()
    internal var activeCatalogLoadSignature: String? = null
    private val instanceId = System.identityHashCode(this)
    internal val isActiveInstance get() = instanceId == homeViewModelActiveInstanceId
    internal val catalogPipelineMutex = kotlinx.coroutines.sync.Mutex()
    internal var catalogPipelineDebounceJob: Job? = null
    internal val catalogReloadTrigger = kotlinx.coroutines.flow.MutableSharedFlow<Pair<List<com.nuvio.tv.domain.model.Addon>, Boolean>>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    internal var diskCacheRestored: Boolean = false
    internal val cwMetaCache = Collections.synchronizedMap(mutableMapOf<String, CwMetaSummary?>())
    internal val cwMetaNegativeCacheTimestamps = Collections.synchronizedMap(mutableMapOf<String, Long>())
    internal val cwBadgeEpisodeCache = Collections.synchronizedMap(mutableMapOf<String, Set<Pair<Int, Int>>?>())
    internal val cwBadgeNextSeasonMs = Collections.synchronizedMap(mutableMapOf<String, Long>())
    @Volatile
    internal var cwLastBadgeEpisodeKeys: Set<String> = emptySet()
    internal val cwTmdbIdCache = Collections.synchronizedMap(mutableMapOf<String, String?>())
    internal val cwNextUpResolutionCache = Collections.synchronizedMap(mutableMapOf<String, NextUpResolution?>())
    internal val cwNextUpNegativeCacheTimestamps = Collections.synchronizedMap(mutableMapOf<String, Long>())
    internal val discoveredOlderNextUpItems = Collections.synchronizedList(mutableListOf<ContinueWatchingItem.NextUp>())
    internal val cwLastProcessedNextUpContentIds = Collections.synchronizedSet(mutableSetOf<String>())
    internal val cwEnrichedNextUpOverlay = Collections.synchronizedMap(mutableMapOf<String, NextUpInfo>())
    internal val cwEnrichedInProgressOverlay = Collections.synchronizedMap(mutableMapOf<String, ContinueWatchingItem.InProgress>())
    internal val cwPipelineRefreshTrigger = kotlinx.coroutines.flow.MutableStateFlow(0)
    internal var cwPipelineJob: kotlinx.coroutines.Job? = null
    internal val fullyWatchedSeriesIds get() = watchedSeriesStateHolder
    internal var catalogLoadGeneration: Long = 0L
    internal var catalogsLoadInProgress: Boolean = false
    internal val trailerPreviewLoadingIds = mutableSetOf<String>()
    internal val trailerPreviewNegativeCache = mutableSetOf<String>()
    internal val trailerPreviewUrlsState = mutableStateMapOf<String, String>()
    internal val trailerPreviewAudioUrlsState = mutableStateMapOf<String, String>()
    internal var activeTrailerPreviewItemId: String? = null
    internal var trailerPreviewRequestVersion: Long = 0L
    internal var currentTmdbSettings: TmdbSettings = TmdbSettings()
    internal var currentMdbListSettings: com.nuvio.tv.domain.model.MDBListSettings = com.nuvio.tv.domain.model.MDBListSettings()
    internal var heroEnrichmentJob: Job? = null
    internal var lastHeroEnrichmentSignature: String? = null
    internal var lastHeroEnrichedItems: List<MetaPreview> = emptyList()
    internal val prefetchedExternalMetaIds = Collections.synchronizedSet(mutableSetOf<String>())
    internal val externalMetaPrefetchInFlightIds = Collections.synchronizedSet(mutableSetOf<String>())
    internal var externalMetaPrefetchJob: Job? = null
    internal var pendingExternalMetaPrefetchItemId: String? = null
    internal val prefetchedTmdbIds = Collections.synchronizedSet(mutableSetOf<String>())
    internal val tmdbStatusRepairAttemptedIds =
        Collections.synchronizedSet(mutableSetOf<String>())

    /*
     * Titles whose proactive Home enrichment reached a terminal result.
     *
     * Terminal means the TMDB attempt and any required external metadata
     * fallback have both returned. The sources may have supplied complete
     * metadata or legitimately supplied no result.
     *
     * Row readiness depends on completion rather than individual fields being
     * non-null, so unavailable metadata cannot hold a row forever.
     *
     * Focus-driven enrichment remains free to retry missing data later.
     */
    internal val homeEnrichmentAttemptedIds =
        Collections.synchronizedSet(
            mutableSetOf<String>()
        )

    internal val enrichmentCache: MutableMap<String, TmdbEnrichment> = Collections.synchronizedMap(LinkedHashMap())
    internal var tmdbEnrichFocusJob: Job? = null
    internal var trailerPreviewDebounceJob: Job? = null
    internal var proactiveEnrichJob: Job? = null

    /*
     * Trailer playback suppresses only lower-priority proactive enrichment.
     * Focus, hero, and already-started work continue normally.
     */
    @Volatile
    internal var homeHeroTrailerPlaying: Boolean = false

    /*
     * Catalog-row keys currently visible in Modern Home, including one
     * adjacent row above and below the actual LazyColumn viewport.
     */
    @Volatile
    internal var modernHomePriorityRowKeys: List<String> = emptyList()

    /*
     * Identity of the currently scheduled proactive enrichment plan.
     * Individual metadata updates do not alter this signature, so they
     * cannot repeatedly cancel and recreate the same work.
     */
    @Volatile
    internal var homeEnrichmentPlanSignature: String? = null
    internal var pendingTmdbEnrichItemId: String? = null
    internal var adjacentItemPrefetchJob: Job? = null
    internal var pendingAdjacentPrefetchItemId: String? = null
    internal val posterLibraryObserverJobs = mutableMapOf<String, Job>()
    internal val movieWatchedObserverJobs = mutableMapOf<String, Job>()
    internal var movieWatchedBatchJob: Job? = null
    internal var seriesWatchedJob: Job? = null
    internal var lastMovieWatchedItemKeys: Set<String> = emptySet()
    internal var activePosterListPickerInput: LibraryEntryInput? = null
    @Volatile
    internal var externalMetaPrefetchEnabled: Boolean = false
    internal val startupStartedAtMs: Long = SystemClock.elapsedRealtime()
    @Volatile
    internal var startupGracePeriodActive: Boolean = true
    internal var startupAuthNoticeJob: Job? = null

    val trailerPreviewUrls: Map<String, String>
        get() = trailerPreviewUrlsState
    val trailerPreviewAudioUrls: Map<String, String>
        get() = trailerPreviewAudioUrlsState

    init {
        homeViewModelActiveInstanceId = instanceId
        observeStartupAuthNotice()
        viewModelScope.launch {
            profileManager.activeProfileReady.first { it }
            watchedSeriesStateHolder.loadFromDisk()
            observeLayoutPreferences()
            observeExternalMetaPrefetchPreference()
            loadHomeCatalogOrderPreference()
            loadDisabledHomeCatalogPreference()
            loadShuffleHomeCatalogPreference()
            loadNumberedHomeCatalogPreference()
            observeLibraryState()
            observeMyList()
            observeTmdbSettings()
            observeMdbListSettings()
            observeBlurUnwatchedEpisodes()
            observeMemoryOnlyVerticalScroll()
            observeProgressSourceChanges()
            loadContinueWatching()
            observeInstalledAddons()
            launch {
                catalogReloadTrigger
                    .debounce(300)
                    .collect { (addons, force) ->
                        loadAllCatalogsPipeline(addons, force)
                    }
            }

            var previousProfileId = profileManager.activeProfileId.value
            profileManager.activeProfileId.collect { newId ->
                if (newId != previousProfileId) {
                    previousProfileId = newId
                    val activeProf = profileManager.activeProfile
                    android.util.Log.d("NuvioProfile", "Switched to profile $newId name=${activeProf?.name} usesPrimaryPlugins=${activeProf?.usesPrimaryPlugins} isPrimary=${activeProf?.isPrimary}")
                    cwMetaCache.clear()
                    cwMetaNegativeCacheTimestamps.clear()
                    cwBadgeEpisodeCache.clear()
                    cwBadgeNextSeasonMs.clear()
                    cwTmdbIdCache.clear()
                    cwNextUpResolutionCache.clear()
                    cwNextUpNegativeCacheTimestamps.clear()
                    discoveredOlderNextUpItems.clear()
                    cwLastProcessedNextUpContentIds.clear()
                    cwEnrichedNextUpOverlay.clear()
                    cwEnrichedInProgressOverlay.clear()
                    cwLastBadgeEpisodeKeys = emptySet()
                    _uiState.update {
                        it.copy(continueWatchingItems = emptyList(), layoutPreferencesReady = false)
                    }
                    loadContinueWatching()
                    watchedSeriesStateHolder.update(emptySet())
                    _uiState.update { it.copy(movieWatchedStatus = emptyMap()) }
                    clearFocusState()
                    // Disk cache is intentionally preserved on profile switch.
                    // TTL handles staleness; cache allows instant restore on return.
                }
            }
        }
        viewModelScope.launch {
            delay(STARTUP_GRACE_PERIOD_MS)
            startupGracePeriodActive = false
        }
        viewModelScope.launch {
            var lastSeen = cwEnrichmentCache.cacheCleared.value
            cwEnrichmentCache.cacheCleared.collect { version ->
                if (version != lastSeen) {
                    lastSeen = version
                    cwMetaCache.clear()
                    cwMetaNegativeCacheTimestamps.clear()
                    cwBadgeEpisodeCache.clear()
                    cwBadgeNextSeasonMs.clear()
                    cwTmdbIdCache.clear()
                    cwNextUpResolutionCache.clear()
                    cwNextUpNegativeCacheTimestamps.clear()
                    discoveredOlderNextUpItems.clear()
                    cwLastProcessedNextUpContentIds.clear()
                    cwEnrichedNextUpOverlay.clear()
                    cwEnrichedInProgressOverlay.clear()
                    cwLastBadgeEpisodeKeys = emptySet()
                    _uiState.update { it.copy(continueWatchingItems = emptyList(), continueWatchingEnrichmentReady = false) }
                    cwPipelineRefreshTrigger.value++
                }
            }
        }
    }

    private fun observeLayoutPreferences() = observeLayoutPreferencesPipeline()

    private fun observeExternalMetaPrefetchPreference() = observeExternalMetaPrefetchPreferencePipeline()

    fun requestTrailerPreview(item: MetaPreview) = requestTrailerPreviewPipeline(item)

    fun requestTrailerPreview(
        itemId: String,
        title: String,
        releaseInfo: String?,
        apiType: String
    ) = requestTrailerPreviewPipeline(
        itemId = itemId,
        title = title,
        releaseInfo = releaseInfo,
        apiType = apiType
    )

    fun onItemFocus(item: MetaPreview) = onItemFocusPipeline(item)

    fun preloadAdjacentItem(item: MetaPreview) = preloadAdjacentItemPipeline(item)

    private fun loadHomeCatalogOrderPreference() = loadHomeCatalogOrderPreferencePipeline()

    private fun loadDisabledHomeCatalogPreference() = loadDisabledHomeCatalogPreferencePipeline()
    private fun loadShuffleHomeCatalogPreference() = loadShuffleHomeCatalogPreferencePipeline()
    private fun loadNumberedHomeCatalogPreference() = loadNumberedHomeCatalogPreferencePipeline()

    private fun observeTmdbSettings() = observeTmdbSettingsPipeline()

    private fun observeMdbListSettings() {
        viewModelScope.launch {
            mdbListSettingsDataStore.settings
                .distinctUntilChanged()
                .collectLatest { settings ->
                    currentMdbListSettings = settings
                }
        }
    }

    private fun observeBlurUnwatchedEpisodes() {
        viewModelScope.launch {
            layoutPreferenceDataStore.blurContinueWatchingNextUp
                .distinctUntilChanged()
                .collect { enabled ->
                    _uiState.update { it.copy(blurUnwatchedEpisodes = enabled) }
                }
        }
    }

    private fun observeMemoryOnlyVerticalScroll() {
        viewModelScope.launch {
            layoutPreferenceDataStore.memoryOnlyVerticalScroll
                .distinctUntilChanged()
                .collect { enabled ->
                    _uiState.update { it.copy(memoryOnlyVerticalScroll = enabled) }
                }
        }
    }

    private fun observeProgressSourceChanges() {
        viewModelScope.launch {
            var previousSource: com.nuvio.tv.data.local.WatchProgressSource? = null
            traktSettingsDataStore.watchProgressSource
                .distinctUntilChanged()
                .collect { source ->
                    if (previousSource != null && previousSource != source) {
                        cwMetaCache.clear()
                        cwEnrichedNextUpOverlay.clear()
                        cwEnrichedInProgressOverlay.clear()
                        discoveredOlderNextUpItems.clear()
                        cwLastProcessedNextUpContentIds.clear()
                        _uiState.update { it.copy(continueWatchingItems = emptyList()) }
                        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { cwEnrichmentCache.saveNextUpSnapshot(emptyList(), force = true) }
                            runCatching { cwEnrichmentCache.saveInProgressSnapshot(emptyList(), force = true) }
                        }
                        loadContinueWatching()
                    }
                    previousSource = source
                }
        }
    }

    internal fun remainingStartupGraceMs(nowMs: Long = SystemClock.elapsedRealtime()): Long {
        if (!startupGracePeriodActive) return 0L
        return (STARTUP_GRACE_PERIOD_MS - (nowMs - startupStartedAtMs)).coerceAtLeast(0L)
    }

    internal fun remainingContinueWatchingEnrichmentGraceMs(
        nowMs: Long = SystemClock.elapsedRealtime()
    ): Long {
        return (CONTINUE_WATCHING_ENRICHMENT_GRACE_PERIOD_MS - (nowMs - startupStartedAtMs))
            .coerceAtLeast(0L)
    }

    private fun observeStartupAuthNotice() {
        viewModelScope.launch {
            authSessionNoticeDataStore.pendingNotice.collect { notice ->
                if (notice == null) return@collect
                _uiState.update { state ->
                    if (state.startupAuthNotice == notice) state else state.copy(startupAuthNotice = notice)
                }
                startupAuthNoticeJob?.cancel()
                startupAuthNoticeJob = viewModelScope.launch {
                    delay(3200)
                    clearStartupAuthNotice(notice)
                }
                authSessionNoticeDataStore.consumeNotice(notice)
            }
        }
    }

    private fun clearStartupAuthNotice(notice: StartupAuthNotice) {
        _uiState.update { state ->
            if (state.startupAuthNotice == notice) {
                state.copy(startupAuthNotice = null)
            } else {
                state
            }
        }
    }

    private var myListJob: kotlinx.coroutines.Job? = null

    @Volatile
    private var currentMyListEntries:
        List<com.nuvio.tv.domain.model.LibraryEntry> = emptyList()

    fun showHomeMessage(message: String, isError: Boolean = false) {
        _uiState.update {
            it.copy(userMessage = HomeUserMessage(message, isError))
        }
        viewModelScope.launch {
            kotlinx.coroutines.delay(2500)
            _uiState.update { state ->
                if (state.userMessage?.message == message) {
                    state.copy(userMessage = null)
                } else {
                    state
                }
            }
        }
    }

    fun isInWatchlist(itemId: String, itemType: String): Boolean =
        currentMyListEntries.any { entry ->
            entry.matchesMyListIdentity(itemId, itemType)
        }

    private fun com.nuvio.tv.domain.model.LibraryEntry.matchesMyListIdentity(
        itemId: String,
        itemType: String
    ): Boolean {
        fun normalizedType(value: String): String =
            when (value.trim().lowercase()) {
                "tv", "show", "anime" -> "series"
                else -> value.trim().lowercase()
            }

        if (normalizedType(type) != normalizedType(itemType)) return false
        if (id.equals(itemId.trim(), ignoreCase = true)) return true

        val candidate = parseContentIds(itemId)
        val entryIds = parseContentIds(id)

        if (
            candidate.imdb != null &&
            (
                candidate.imdb.equals(imdbId, ignoreCase = true) ||
                    candidate.imdb.equals(entryIds.imdb, ignoreCase = true)
            )
        ) {
            return true
        }

        if (
            candidate.tmdb != null &&
            (candidate.tmdb == tmdbId || candidate.tmdb == entryIds.tmdb)
        ) {
            return true
        }

        if (
            candidate.trakt != null &&
            (candidate.trakt == traktId || candidate.trakt == entryIds.trakt)
        ) {
            return true
        }

        val normalizedId = itemId.trim()
        return simklId != null &&
            (
                normalizedId.equals("simkl:$simklId", ignoreCase = true) ||
                    normalizedId == simklId.toString()
            )
    }

    private fun cachedMyListRow(
        cached: List<com.nuvio.tv.data.local.CachedMyListItem>,
        isLoading: Boolean
    ): com.nuvio.tv.domain.model.CatalogRow? {
        if (cached.isEmpty()) return null

        val items = cached.map { item ->
            com.nuvio.tv.domain.model.MetaPreview(
                id = item.id,
                type = com.nuvio.tv.domain.model.ContentType.fromString(
                    item.type
                ),
                rawType = item.type,
                name = item.name,
                poster = item.poster,
                posterShape =
                    com.nuvio.tv.domain.model.PosterShape.POSTER,
                background = item.background,
                logo = item.logo,
                description = item.description,
                releaseInfo = item.releaseInfo,
                imdbRating = item.imdbRating,
                genres = item.genres,
                status = item.status,
                ageRating = item.ageRating,
                runtime = item.runtime,
                country = item.country,
                language = item.language
            )
        }

        return com.nuvio.tv.domain.model.CatalogRow(
            addonId = MY_LIST_ADDON_ID,
            addonName = "Built-In",
            addonBaseUrl = "",
            catalogId = MY_LIST_CATALOG_ID,
            catalogName = "My List",
            type = com.nuvio.tv.domain.model.ContentType.UNKNOWN,
            rawType = "mixed",
            items = items,
            isLoading = isLoading,
            hasMore = false,
            supportsSkip = false
        )
    }

    private fun replaceMyListRow(
        row: com.nuvio.tv.domain.model.CatalogRow?
    ) {
        catalogsMap.remove(MY_LIST_CATALOG_KEY)

        if (row != null) {
            catalogsMap[MY_LIST_CATALOG_KEY] = row
            if (MY_LIST_CATALOG_KEY !in catalogOrder) {
                catalogOrder.add(0, MY_LIST_CATALOG_KEY)
            }
        }

        _uiState.update { state ->
            val oldIndex = state.catalogRows.indexOfFirst {
                it.addonId == MY_LIST_ADDON_ID
            }
            val withoutMyList = state.catalogRows.filter {
                it.addonId != MY_LIST_ADDON_ID
            }
            val updatedRows = when {
                row == null -> withoutMyList
                oldIndex >= 0 -> withoutMyList.toMutableList().also {
                    it.add(oldIndex.coerceAtMost(it.size), row)
                }
                else -> listOf(row) + withoutMyList
            }

            if (updatedRows == state.catalogRows) {
                state
            } else {
                state.copy(catalogRows = updatedRows)
            }
        }

        scheduleUpdateCatalogRows()
    }

    internal fun observeMyList() {
        myListJob?.cancel()
        myListJob = viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                profileManager.activeProfileId,
                libraryRepository.sourceMode
            ) { profileId, sourceMode ->
                profileId to sourceMode
            }
                .distinctUntilChanged()
                .collectLatest source@{ (profileId, sourceMode) ->
                    currentMyListEntries = emptyList()

                    val cached = myListDiskCache.load(
                        profileId = profileId,
                        sourceMode = sourceMode
                    )
                    val cachedRow = cachedMyListRow(
                        cached = cached,
                        isLoading = true
                    )
                    replaceMyListRow(cachedRow)

                    var hasPublishedLiveItems = false

                    libraryRepository.watchlistItems
                        .distinctUntilChanged()
                        .flowOn(kotlinx.coroutines.Dispatchers.Default)
                        .collectLatest items@{ liveEntries ->
                            val entries = liveEntries
                                .sortedByDescending { it.listedAt }
                            currentMyListEntries = entries

                            if (entries.isEmpty()) {
                                if (
                                    hasPublishedLiveItems ||
                                    cached.isEmpty()
                                ) {
                                    replaceMyListRow(null)
                                    myListDiskCache.clear(
                                        profileId = profileId,
                                        sourceMode = sourceMode
                                    )
                                } else {
                                    replaceMyListRow(
                                        cachedRow?.copy(isLoading = false)
                                    )
                                }
                                return@items
                            }

                            hasPublishedLiveItems = true

                            val priorImages =
                                catalogsMap[MY_LIST_CATALOG_KEY]
                                    ?.items
                                    .orEmpty()
                                    .associate { item ->
                                        item.id to Triple(
                                            item.poster,
                                            item.background,
                                            item.logo
                                        )
                                    }

                            val items = entries.map { entry ->
                                val preview = entry.toMetaPreview()
                                val cachedImages =
                                    priorImages[preview.id]
                                        ?: Triple(null, null, null)

                                preview.copy(
                                    poster =
                                        preview.poster
                                            ?: cachedImages.first,
                                    background =
                                        preview.background
                                            ?: cachedImages.second,
                                    logo =
                                        preview.logo
                                            ?: cachedImages.third
                                )
                            }

                            if (enrichmentCache.isEmpty()) {
                                val restored =
                                    homeEnrichmentDiskCache.loadAll()
                                if (restored.isNotEmpty()) {
                                    enrichmentCache.putAll(restored)
                                }
                            }

                            val enrichedItems = items.map { item ->
                                val enrichment =
                                    enrichmentCache[item.id]
                                        ?: item.id
                                            .takeIf {
                                                it.startsWith("tt")
                                            }
                                            ?.let {
                                                tmdbService
                                                    .getCachedTmdbId(it)
                                            }
                                            ?.let {
                                                enrichmentCache[
                                                    "tmdb:$it"
                                                ]
                                            }
                                        ?: return@map item

                                item.copy(
                                    poster =
                                        item.poster
                                            ?: enrichment.poster,
                                    background =
                                        item.background
                                            ?: enrichment.backdrop,
                                    logo =
                                        enrichment.logo
                                            ?: enrichment
                                                .fallbackLogoUrl
                                            ?: item.logo,
                                    landscapePoster =
                                        enrichment.detailBackdrop
                                            ?: item.landscapePoster,
                                    name =
                                        enrichment.localizedTitle
                                            ?: item.name,
                                    description =
                                        enrichment.description
                                            ?: item.description,
                                    genres =
                                        if (
                                            enrichment.genres
                                                .isNotEmpty()
                                        ) {
                                            enrichment.genres
                                        } else {
                                            item.genres
                                        },
                                    imdbRating =
                                        enrichment.rating?.toFloat()
                                            ?: item.imdbRating,
                                    ageRating =
                                        enrichment.ageRating
                                            ?: item.ageRating,
                                    status =
                                        enrichment.status
                                            ?: item.status,
                                    runtime =
                                        enrichment.runtimeMinutes
                                            ?.toString()
                                            ?: item.runtime
                                )
                            }

                            replaceMyListRow(
                                com.nuvio.tv.domain.model.CatalogRow(
                                    addonId = MY_LIST_ADDON_ID,
                                    addonName = "Built-In",
                                    addonBaseUrl = "",
                                    catalogId = MY_LIST_CATALOG_ID,
                                    catalogName = "My List",
                                    type =
                                        com.nuvio.tv.domain.model
                                            .ContentType.UNKNOWN,
                                    rawType = "mixed",
                                    items = enrichedItems,
                                    isLoading = false,
                                    hasMore = false,
                                    supportsSkip = false
                                )
                            )

                            if (
                                enrichedItems.any {
                                    it.poster != null
                                }
                            ) {
                                val cacheItems = enrichedItems.map {
                                    item ->
                                    com.nuvio.tv.data.local
                                        .CachedMyListItem(
                                            id = item.id,
                                            type = item.rawType,
                                            name = item.name,
                                            poster = item.poster,
                                            background =
                                                item.background,
                                            logo = item.logo,
                                            description =
                                                item.description,
                                            releaseInfo =
                                                item.releaseInfo,
                                            imdbRating =
                                                item.imdbRating,
                                            genres = item.genres,
                                            status = item.status,
                                            ageRating =
                                                item.ageRating,
                                            runtime = item.runtime,
                                            country = item.country,
                                            language = item.language
                                        )
                                }

                                myListDiskCache.save(
                                    profileId = profileId,
                                    sourceMode = sourceMode,
                                    items = cacheItems
                                )
                            }
                        }
                }
        }
    }


    fun dismissWatchedRating() {
        _uiState.update { it.copy(showWatchedRatingOverlay = false) }
    }

    fun submitWatchedRating(rating: Int) {
        val state = _uiState.value
        val itemId = state.watchedRatingItemId ?: return
        val itemType = state.watchedRatingItemType ?: return
        _uiState.update { it.copy(showWatchedRatingOverlay = false) }
        viewModelScope.launch {
            runCatching {
                val media = buildTrackingMediaReference(
                    contentType = itemType,
                    parentMetaId = itemId,
                    videoId = state.watchedRatingImdbId,
                    title = state.watchedRatingTitle,
                    releaseInfo = state.watchedRatingYear?.toString()
                )
                trackingRatingCoordinator.submit(media = media, rating = rating)
            }.onFailure { error ->
                android.util.Log.w(TAG, "Failed to submit watched rating: ${error.message}")
            }
        }
    }

    fun onEvent(event: HomeEvent) {
        when (event) {
            is HomeEvent.OnItemClick -> navigateToDetail(event.itemId, event.itemType)
            is HomeEvent.OnLoadMoreCatalog -> loadMoreCatalogItems(event.catalogId, event.addonId, event.type)
            is HomeEvent.OnRemoveContinueWatching -> removeContinueWatching(
                contentId = event.contentId,
                season = event.season,
                episode = event.episode,
                isNextUp = event.isNextUp
            )
            HomeEvent.OnRetry -> viewModelScope.launch { loadAllCatalogs(addonsCache, forceReload = true) }
        }
    }

    private fun loadContinueWatching() {
        // Pre-render cached CW instantly before pipeline starts — user sees content
        // immediately on launch without waiting for Trakt/allProgress to respond.
        // Capture profileId synchronously on the calling thread to avoid a race where
        // the IO coroutine reads activeProfileId.value before the DataStore write commits.
        val preRenderProfileId = profileManager.activeProfileId.value
        // One-time migration: POST server-side hides for all historical local
        // NextUp dismissals, so they survive app-data clears (and clean up
        // trakt.tv's up-next). Non-aggressive: only keys the user explicitly
        // dismissed. Idempotent server-side; gated per profile.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val selectedSource = traktSettingsDataStore.watchProgressSource.first()
                if (
                    selectedSource !=
                    com.nuvio.tv.data.local.WatchProgressSource.TRAKT
                ) {
                    return@launch
                }
                if (
                    traktSettingsDataStore.isHiddenMigrationDone(
                        preRenderProfileId
                    )
                ) {
                    return@launch
                }

                val keys = traktSettingsDataStore.dismissedNextUpKeys.first()
                val contentIds = keys
                    .map { key -> key.substringBefore("|").trim() }
                    .filter(String::isNotBlank)
                    .distinct()

                android.util.Log.d(
                    "HiddenMigration",
                    "migrating ${contentIds.size} dismissed shows through " +
                        "the selected Trakt provider " +
                        "(profile=$preRenderProfileId)"
                )

                var migrationComplete = true
                contentIds.forEach { contentId ->
                    val migrated = runCatching {
                        watchProgressRepository.dismissNextUp(
                            contentId = contentId,
                            season = null,
                            episode = null
                        )
                    }.onFailure { error ->
                        android.util.Log.w(
                            "HiddenMigration",
                            "hide failed for $contentId",
                            error
                        )
                    }.getOrDefault(false)

                    if (!migrated) {
                        migrationComplete = false
                    }
                    kotlinx.coroutines.delay(250L)
                }

                if (migrationComplete) {
                    traktSettingsDataStore.setHiddenMigrationDone(
                        preRenderProfileId
                    )
                    android.util.Log.d(
                        "HiddenMigration",
                        "migration complete"
                    )
                }
            }.onFailure { error ->
                android.util.Log.w(
                    "HiddenMigration",
                    "migration aborted",
                    error
                )
            }
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val cachedInProgress = runCatching { cwEnrichmentCache.getInProgressSnapshot(preRenderProfileId) }.getOrElse { emptyList<com.nuvio.tv.data.local.CachedInProgressItem>() }
            val cachedNextUp = runCatching { cwEnrichmentCache.getNextUpSnapshot(preRenderProfileId) }.getOrElse { emptyList<com.nuvio.tv.data.local.CachedNextUpItem>() }
            android.util.Log.d("CW_TIMING", "💾 pre-render: inProgress=${cachedInProgress.size} nextUp=${cachedNextUp.size}")
            if (cachedInProgress.isEmpty() && cachedNextUp.isEmpty()) {
                android.util.Log.d("CW_TIMING", "💾 pre-render: cache empty, skipping")
                return@launch
            }
            // Use a short timeout for dismissed keys — if DataStore isn't ready yet,
            // show all cached items. The pipeline will filter dismissed items shortly after.
            val dismissedNextUp = withTimeoutOrNull(200L) {
                traktSettingsDataStore.dismissedNextUpKeys.first()
            } ?: emptySet()
            val inProgressItems = cachedInProgress.map { cached ->
                ContinueWatchingItem.InProgress(
                    progress = com.nuvio.tv.domain.model.WatchProgress(
                        contentId = cached.contentId,
                        contentType = cached.contentType,
                        name = cached.name,
                        poster = cached.poster,
                        backdrop = cached.backdrop,
                        logo = cached.logo,
                        videoId = cached.videoId,
                        season = cached.season,
                        episode = cached.episode,
                        episodeTitle = cached.episodeTitle,
                        position = cached.position,
                        duration = cached.duration,
                        lastWatched = cached.lastWatched,
                        progressPercent = cached.progressPercent
                    ),
                    episodeThumbnail = cached.episodeThumbnail,
                    episodeDescription = cached.episodeDescription,
                    episodeImdbRating = cached.episodeImdbRating,
                    genres = cached.genres,
                    releaseInfo = cached.releaseInfo
                )
            }
            val filteredNextUp = cachedNextUp.filter { nextUpDismissKey(it.contentId, it.season, it.episode) !in dismissedNextUp }
            val nextUpItems = filteredNextUp.map { cached: com.nuvio.tv.data.local.CachedNextUpItem ->
                ContinueWatchingItem.NextUp(
                    info = NextUpInfo(
                        contentId = cached.contentId,
                        contentType = cached.contentType,
                        name = cached.name,
                        poster = cached.poster,
                        backdrop = cached.backdrop,
                        logo = cached.logo,
                        videoId = cached.videoId,
                        season = cached.season,
                        episode = cached.episode,
                        episodeTitle = cached.episodeTitle,
                        episodeDescription = cached.episodeDescription,
                        thumbnail = cached.thumbnail,
                        released = cached.released,
                        hasAired = cached.hasAired,
                        airDateLabel = cached.airDateLabel,
                        lastWatched = cached.lastWatched,
                        imdbRating = cached.imdbRating,
                        genres = cached.genres,
                        releaseInfo = cached.releaseInfo
                    )
                )
            }
            val items = mergeContinueWatchingItems(
                inProgressItems = inProgressItems,
                nextUpItems = nextUpItems
            )
            if (items.isNotEmpty()) {
                _uiState.update { state ->
                    if (state.continueWatchingItems.isEmpty()) {
                        state.copy(continueWatchingItems = items)
                    } else state
                }
            }
        }
        loadContinueWatchingPipeline()
    }

    private fun removeContinueWatching(
        contentId: String,
        season: Int? = null,
        episode: Int? = null,
        isNextUp: Boolean = false
    ) = removeContinueWatchingPipeline(
        contentId = contentId,
        season = season,
        episode = episode,
        isNextUp = isNextUp
    )

    private fun observeInstalledAddons() = observeInstalledAddonsPipeline()

    private suspend fun loadAllCatalogs(addons: List<Addon>, forceReload: Boolean = false) =
        loadAllCatalogsPipeline(addons, forceReload)

    private fun loadCatalog(addon: Addon, catalog: CatalogDescriptor, generation: Long) =
        loadCatalogPipeline(addon, catalog, generation)


    private fun loadMoreCatalogItems(catalogId: String, addonId: String, type: String) =
        loadMoreCatalogItemsPipeline(catalogId, addonId, type)

    internal fun scheduleUpdateCatalogRows() {
        catalogUpdateJob?.cancel()
        catalogUpdateJob = viewModelScope.launch {
            val debounceMs = when {
                !hasRenderedFirstCatalog && catalogsMap.isNotEmpty() -> {
                    hasRenderedFirstCatalog = true
                    50L
                }
                pendingCatalogLoads > 8 -> 1000L
                pendingCatalogLoads > 3 -> 500L
                pendingCatalogLoads > 0 -> 300L
                else -> 50L
            }
            delay(debounceMs)
            updateCatalogRows()
        }
    }

    private suspend fun updateCatalogRows() = updateCatalogRowsPipeline()

    internal var posterStatusReconcileJob: Job? = null

    private fun schedulePosterStatusReconcile(rows: List<CatalogRow>) =
        schedulePosterStatusReconcilePipeline(rows)

    private fun reconcilePosterStatusObservers(rows: List<CatalogRow>) =
        reconcilePosterStatusObserversPipeline(rows)

    private fun navigateToDetail(itemId: String, itemType: String) {
        _uiState.update { it.copy(selectedItemId = itemId) }
    }

    private suspend fun enrichHeroItems(
        items: List<MetaPreview>,
        settings: TmdbSettings
    ): List<MetaPreview> = enrichHeroItemsPipeline(items, settings)

    private fun replaceGridHeroItems(
        gridItems: List<GridItem>,
        heroItems: List<MetaPreview>
    ): List<GridItem> = replaceGridHeroItemsPipeline(gridItems, heroItems)

    private fun heroEnrichmentSignature(items: List<MetaPreview>, settings: TmdbSettings): String =
        heroEnrichmentSignaturePipeline(items, settings)

    fun saveFocusState(
        verticalScrollIndex: Int,
        verticalScrollOffset: Int,
        focusedRowIndex: Int,
        focusedItemIndex: Int,
        catalogRowScrollStates: Map<String, Int>,
        focusedRowKey: String? = null,
        selectedPlatformId: String = "home"
    ) {
        val nextState = HomeScreenFocusState(
            verticalScrollIndex = verticalScrollIndex,
            verticalScrollOffset = verticalScrollOffset,
            focusedRowIndex = focusedRowIndex,
            focusedItemIndex = focusedItemIndex,
            catalogRowScrollStates = catalogRowScrollStates,
            focusedRowKey = focusedRowKey,
            selectedPlatformId = selectedPlatformId,
            hasSavedFocus = true
        )
        if (_focusState.value == nextState) return
        _focusState.value = nextState
    }

    fun clearTrailerUrlCacheIfStale(backgroundedAtMs: Long) {
        val staleThresholdMs = 5 * 60 * 60 * 1000L // 5 hours
        if (backgroundedAtMs > 0 && System.currentTimeMillis() - backgroundedAtMs >= staleThresholdMs) {
            trailerPreviewUrlsState.clear()
            trailerPreviewAudioUrlsState.clear()
        }
    }

    fun clearFocusState() {
        _focusState.value = HomeScreenFocusState()
    }

    fun saveGridFocusState(
        verticalScrollIndex: Int,
        verticalScrollOffset: Int,
        focusedRowIndex: Int = 0,
        focusedItemIndex: Int = 0
    ) {
        _gridFocusState.value = HomeScreenFocusState(
            verticalScrollIndex = verticalScrollIndex,
            verticalScrollOffset = verticalScrollOffset,
            focusedRowIndex = focusedRowIndex,
            focusedItemIndex = focusedItemIndex
        )
    }

    fun getCachedVisiblePlatformIds() = layoutPreferenceDataStore.cachedVisiblePlatformIds

    fun releasePlatformBackdropsGate() {
        // Don't release if a real preload is in progress — it will release when done.
        if (platformPreloadInProgress) return
        _platformBackdropsPreloaded.value = true
    }

    fun preloadPlatformBackdrops(urls: List<String>) {
        if (_platformBackdropsPreloaded.value) return
        if (urls.isEmpty()) {
            _platformBackdropsPreloaded.value = true
            return
        }
        platformPreloadInProgress = true
        viewModelScope.launch {
            val loader = coil.Coil.imageLoader(appContext)
            val widthPx = backdropPreloadWidthPx
            val heightPx = backdropPreloadHeightPx
            val jobs = urls.map { url ->
                launch {
                    try {
                        val req = coil.request.ImageRequest.Builder(appContext)
                            .data(url)
                            .apply {
                                if (widthPx > 0 && heightPx > 0) {
                                    size(width = widthPx, height = heightPx)
                                }
                            }
                            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                            .build()
                        loader.execute(req)
                    } catch (_: Exception) {}
                }
            }
            // Release the gate after 3s OR when all loads finish — whichever
            // comes first — but NEVER cancel the loads. Cancelling left slow
            // backdrops permanently uncached on fresh launches, so the first
            // visit to those platforms paid full network+decode at transition
            // time (variable black). Stragglers now finish in the background.
            kotlinx.coroutines.withTimeoutOrNull(3_000L) {
                jobs.forEach { it.join() }
            }
            platformPreloadInProgress = false
            _platformBackdropsPreloaded.value = true
            scheduleUpdateCatalogRows()
        }
    }

    fun saveCachedVisiblePlatformIds(ids: Set<String>) {
        viewModelScope.launch {
            layoutPreferenceDataStore.setCachedVisiblePlatformIds(ids)
        }
    }

    override fun onCleared() {
        if (homeViewModelActiveInstanceId == System.identityHashCode(this)) homeViewModelActiveInstanceId = -1
        startupAuthNoticeJob?.cancel()
        posterStatusReconcileJob?.cancel()
        movieWatchedBatchJob?.cancel()
        seriesWatchedJob?.cancel()
        cancelInFlightCatalogLoads()
        posterLibraryObserverJobs.values.forEach { it.cancel() }
        movieWatchedObserverJobs.values.forEach { it.cancel() }
        posterLibraryObserverJobs.clear()
        movieWatchedObserverJobs.clear()
        super.onCleared()
    }
}

