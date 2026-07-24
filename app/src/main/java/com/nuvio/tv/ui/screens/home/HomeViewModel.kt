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
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.HomeEnrichmentDiskCache
import com.nuvio.tv.data.repository.TraktLibraryService
import com.nuvio.tv.data.repository.TraktScrobbleService
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.data.repository.TraktScrobbleItem
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.repository.TraktProgressService
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
    internal val traktLibraryService: TraktLibraryService,
    internal val traktProgressService: TraktProgressService,
    internal val cwEnrichmentCache: ContinueWatchingEnrichmentCache,
    internal val collectionsDataStore: com.nuvio.tv.data.local.CollectionsDataStore,
    internal val mdbListSettingsDataStore: com.nuvio.tv.data.local.MDBListSettingsDataStore,
    internal val mdbListRepository: com.nuvio.tv.data.repository.MDBListRepository,
    internal val watchedSeriesStateHolder: com.nuvio.tv.data.local.WatchedSeriesStateHolder,
    internal val homeEnrichmentDiskCache: HomeEnrichmentDiskCache,
    internal val myListDiskCache: MyListDiskCache,
    internal val traktScrobbleService: TraktScrobbleService,
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

        modernHomePriorityRowKeys = normalized
        homeEnrichmentPlanSignature = null

        /*
         * Rebuild the pending enrichment order around the new viewport.
         * Existing cached/completed items are filtered out by the pipeline.
         */
        scheduleUpdateCatalogRows()
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

    /*
     * Titles whose proactive enrichment attempt completed without a usable
     * result. They count toward row readiness so legitimate missing metadata
     * cannot hold an otherwise prepared row.
     *
     * Focus-driven enrichment remains free to retry these titles later.
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
            launch {
                traktLibraryService.watchlistChangedSignal.collect { change ->
// Apply immediate optimistic update to ML row — covers detail screen add/remove
                    val currentMlRow = catalogsMap[MY_LIST_CATALOG_KEY]
                    if (change.added) {
                        // Add item to ML row if not already present
                        val alreadyPresent = currentMlRow?.items?.any { it.id == change.item.itemId } == true
                        if (!alreadyPresent) {
                            val newItem = com.nuvio.tv.domain.model.MetaPreview(
                                id = change.item.itemId,
                                type = com.nuvio.tv.domain.model.ContentType.fromString(change.item.itemType),
                                rawType = change.item.itemType,
                                name = change.item.title,
                                poster = change.item.poster,
                                posterShape = change.item.posterShape ?: com.nuvio.tv.domain.model.PosterShape.POSTER,
                                background = change.item.background,
                                logo = change.item.logo,
                                description = change.item.description,
                                releaseInfo = change.item.releaseInfo,
                                imdbRating = change.item.imdbRating,
                                genres = change.item.genres
                            )
                            val cached = enrichmentCache[newItem.id]
                            val enrichedItem = if (cached != null) {
                                newItem.copy(
                                    poster = newItem.poster ?: cached.poster,
                                    background = newItem.background ?: cached.backdrop,
                                    logo = cached.logo ?: newItem.logo,
                                    landscapePoster = cached.detailBackdrop ?: newItem.landscapePoster
                                )
                            } else newItem
                            if (currentMlRow != null) {
                                catalogsMap[MY_LIST_CATALOG_KEY] = currentMlRow.copy(
                                    items = listOf(enrichedItem) + currentMlRow.items
                                )
                            } else {
                                catalogsMap[MY_LIST_CATALOG_KEY] = com.nuvio.tv.domain.model.CatalogRow(
                                    addonId = MY_LIST_ADDON_ID,
                                    addonName = "Built-In",
                                    addonBaseUrl = "",
                                    catalogId = MY_LIST_CATALOG_ID,
                                    catalogName = "My List",
                                    type = com.nuvio.tv.domain.model.ContentType.UNKNOWN,
                                    rawType = "mixed",
                                    items = listOf(enrichedItem),
                                    isLoading = false,
                                    hasMore = false,
                                    supportsSkip = false
                                )
                                if (MY_LIST_CATALOG_KEY !in catalogOrder) {
                                    catalogOrder.add(0, MY_LIST_CATALOG_KEY)
                                }
                            }
                        }
                    } else {
                        // Remove item from ML row
                        if (currentMlRow != null) {
                            val updatedItems = currentMlRow.items.filter { it.id != change.item.itemId }
                            if (updatedItems.isEmpty()) {
                                catalogsMap.remove(MY_LIST_CATALOG_KEY)
                            } else {
                                catalogsMap[MY_LIST_CATALOG_KEY] = currentMlRow.copy(items = updatedItems)
                            }
                        }
                    }
                    scheduleUpdateCatalogRows()
                }
            }
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
                    traktLibraryService.resetSnapshot()
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

    fun showHomeMessage(message: String, isError: Boolean = false) {
        _uiState.update { it.copy(userMessage = HomeUserMessage(message, isError)) }
        viewModelScope.launch {
            kotlinx.coroutines.delay(2500)
            _uiState.update { state ->
                if (state.userMessage?.message == message) state.copy(userMessage = null)
                else state
            }
        }
    }

    fun isInWatchlist(itemId: String, itemType: String): Boolean {
        if (_uiState.value.librarySourceMode != com.nuvio.tv.domain.model.LibrarySourceMode.TRAKT) return false
        return traktLibraryService.isInWatchlistSync(itemId, itemType)
    }

    internal fun observeMyList() {
        myListJob?.cancel()
        myListJob = viewModelScope.launch {
            profileManager.activeProfileId
                .collectLatest { profileId ->
                    // Load the new profile's cache from disk first (fast ~10-50ms)
                    val cached = myListDiskCache.load(profileId)

                    // Build the new cached row (or null if no cache)
                    val newCachedRow = if (cached.isNotEmpty()) {
                        val cachedItems = cached.map { c ->
                            com.nuvio.tv.domain.model.MetaPreview(
                                id = c.id,
                                type = com.nuvio.tv.domain.model.ContentType.fromString(c.type),
                                rawType = c.type,
                                name = c.name,
                                poster = c.poster,
                                posterShape = com.nuvio.tv.domain.model.PosterShape.POSTER,
                                background = c.background,
                                logo = c.logo,
                                description = c.description,
                                releaseInfo = c.releaseInfo,
                                imdbRating = c.imdbRating,
                                genres = c.genres,
                                status = c.status,
                                ageRating = c.ageRating,
                                runtime = c.runtime,
                                country = c.country,
                                language = c.language
                            )
                        }
                        com.nuvio.tv.domain.model.CatalogRow(
                            addonId = MY_LIST_ADDON_ID,
                            addonName = "Built-In",
                            addonBaseUrl = "",
                            catalogId = MY_LIST_CATALOG_ID,
                            catalogName = "My List",
                            type = com.nuvio.tv.domain.model.ContentType.UNKNOWN,
                            rawType = "mixed",
                            items = cachedItems,
                            isLoading = true,
                            hasMore = false,
                            supportsSkip = false
                        )
                    } else null

                    // Update catalogsMap
                    catalogsMap.remove(MY_LIST_CATALOG_KEY)
                    if (newCachedRow != null) {
                        catalogsMap[MY_LIST_CATALOG_KEY] = newCachedRow
                        if (MY_LIST_CATALOG_KEY !in catalogOrder) {
                            catalogOrder.add(0, MY_LIST_CATALOG_KEY)
                        }
                    }

                    // Bypass debounce — swap old/new profile row in uiState directly
                    // so the correct profile's data renders in a single frame.
                    _uiState.update { state ->
                        val withoutMyList = state.catalogRows.filter { it.addonId != MY_LIST_ADDON_ID }
                        val newRows = if (newCachedRow != null) {
                            val insertIdx = state.catalogRows.indexOfFirst { it.addonId == MY_LIST_ADDON_ID }
                            if (insertIdx >= 0) {
                                withoutMyList.toMutableList().also { it.add(insertIdx, newCachedRow) }
                            } else {
                                listOf(newCachedRow) + withoutMyList
                            }
                        } else {
                            withoutMyList
                        }
                        state.copy(catalogRows = newRows)
                    }
                    scheduleUpdateCatalogRows()

                    // Fetch live watchlist directly — bypasses shared snapshotState
                    // so there is zero risk of serving another profile's cached data
                    val entries = runCatching {
                        // fetchWatchlistEntries sorts by traktRank ASC (1=newest on Trakt)
                        // Re-sort by listedAt DESC to show most recently added first
                        traktLibraryService.fetchWatchlistEntries()
                            .sortedByDescending { it.listedAt }
                    }.getOrNull()

                    // snapshotState is kept in sync via performOptimisticMutation in toggleWatchlist
                    // No need to call refreshNow() here — it causes race conditions with Trakt API eventual consistency

                    if (entries == null) {
                        // Network failed — keep showing cache, mark as not loading
                        catalogsMap[MY_LIST_CATALOG_KEY]?.let {
                            catalogsMap[MY_LIST_CATALOG_KEY] = it.copy(isLoading = false)
                        }
                        scheduleUpdateCatalogRows()
                        return@collectLatest
                    }

                    if (entries.isEmpty()) {
                        // Only remove row if we have no cache either.
                        // Empty fetch could mean network not ready or Trakt not authed.
                        // Never clear disk cache based on an empty fetch result.
                        if (cached.isEmpty()) {
                            catalogsMap.remove(MY_LIST_CATALOG_KEY)
                            scheduleUpdateCatalogRows()
                        } else {
                            // Cache exists but fetch returned empty — Trakt may not be ready yet.
                            // Mark row as not loading so cache stays visible, then retry after delay.
                            catalogsMap[MY_LIST_CATALOG_KEY]?.let {
                                catalogsMap[MY_LIST_CATALOG_KEY] = it.copy(isLoading = false)
                            }
                            scheduleUpdateCatalogRows()
                            viewModelScope.launch {
                                delay(3000)
                                val retryEntries = runCatching {
                                    traktLibraryService.fetchWatchlistEntries()
                                        .sortedByDescending { it.listedAt }
                                }.getOrNull()
                                if (!retryEntries.isNullOrEmpty()) {
                                    observeMyList()
                                }
                            }
                        }
                        return@collectLatest
                    }

                    // Merge live entries with cached images to prevent poster flash
                    val cachedImageById = cached.associate { c ->
                        c.id to Triple(c.poster, c.background, c.logo)
                    }
                    val items = entries.map { entry ->
                        val preview = entry.toMetaPreview()
                        val (cachedPoster, cachedBg, cachedLogo) =
                            cachedImageById[preview.id] ?: Triple(null, null, null)
                        preview.copy(
                            poster = preview.poster ?: cachedPoster,
                            background = preview.background ?: cachedBg,
                            logo = preview.logo ?: cachedLogo
                        )
                    }

                    // Ensure enrichment cache is populated before applying to ML items —
                    // it may still be loading from disk asynchronously after profile switch.
                    if (enrichmentCache.isEmpty()) {
                        val restored = homeEnrichmentDiskCache.loadAll()
                        if (restored.isNotEmpty()) enrichmentCache.putAll(restored)
                    }
                    // Apply enrichment cache immediately so newly added items
                    // don't flash backdrop while waiting for TMDB enrichment to re-apply.
                    // Apply unconditionally — don't gate on currentTmdbSettings since
                    // settings may not be loaded yet when observeMyList() runs after toggle.
                    val enrichedItems = items.map { item ->
                        // Trakt-sourced items are keyed by IMDb id (tt...) while the enrichment
                        // cache is keyed by the catalog id (tmdb:...) — try the cross-mapped key
                        // before giving up, otherwise Trakt My List items never get enriched.
                        val cached = enrichmentCache[item.id]
                            ?: item.id.takeIf { it.startsWith("tt") }
                                ?.let { tmdbService.getCachedTmdbId(it) }
                                ?.let { enrichmentCache["tmdb:" + it] }
                            ?: return@map item
                        item.copy(
                            poster = item.poster ?: cached.poster,
                            background = item.background ?: cached.backdrop,
                            logo = cached.logo ?: cached.fallbackLogoUrl ?: item.logo,
                            landscapePoster = cached.detailBackdrop ?: item.landscapePoster,
                            name = cached.localizedTitle ?: item.name,
                            description = cached.description ?: item.description,
                            genres = if (cached.genres.isNotEmpty()) cached.genres else item.genres,
                            imdbRating = cached.rating?.toFloat() ?: item.imdbRating,
                            ageRating = cached.ageRating ?: item.ageRating,
                            status = cached.status ?: item.status,
                            runtime = cached.runtimeMinutes?.toString() ?: item.runtime
                        )
                    }

                    catalogsMap[MY_LIST_CATALOG_KEY] = com.nuvio.tv.domain.model.CatalogRow(
                        addonId = MY_LIST_ADDON_ID,
                        addonName = "Built-In",
                        addonBaseUrl = "",
                        catalogId = MY_LIST_CATALOG_ID,
                        catalogName = "My List",
                        type = com.nuvio.tv.domain.model.ContentType.UNKNOWN,
                        rawType = "mixed",
                        items = enrichedItems,
                        isLoading = false,
                        hasMore = false,
                        supportsSkip = false
                    )
                    if (MY_LIST_CATALOG_KEY !in catalogOrder) {
                        catalogOrder.add(0, MY_LIST_CATALOG_KEY)
                    }
                    scheduleUpdateCatalogRows()

                    // Save to disk only when we have posters — use enrichedItems so
                    // TMDB poster URLs are persisted, not raw Trakt data with poster=null
                    val hasImages = enrichedItems.any { it.poster != null }
                    if (hasImages) {
                        val toCache = enrichedItems.map { item ->
                            com.nuvio.tv.data.local.CachedMyListItem(
                                id = item.id,
                                type = item.rawType,
                                name = item.name,
                                poster = item.poster,
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
                        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            myListDiskCache.save(profileId, toCache)
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
                val parsedIds = parseContentIds(itemId)
                val imdbId = state.watchedRatingImdbId ?: parsedIds.imdb
                val traktIds = TraktIdsDto(imdb = imdbId, tmdb = parsedIds.tmdb)
                val scrobbleItem = TraktScrobbleItem.Movie(
                    title = state.watchedRatingTitle,
                    year = state.watchedRatingYear,
                    ids = traktIds
                )
                traktScrobbleService.postRating(item = scrobbleItem, rating = rating)
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
                if (traktSettingsDataStore.isHiddenMigrationDone(preRenderProfileId)) return@launch
                val keys = traktSettingsDataStore.dismissedNextUpKeys.first()
                val contentIds = keys.map { it.substringBefore("|").trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                android.util.Log.d("HiddenMigration", "migrating " + contentIds.size + " dismissed shows to Trakt hidden list (profile=" + preRenderProfileId + ")")
                contentIds.forEach { contentId ->
                    runCatching { traktProgressService.hideShowFromProgress(contentId) }
                        .onFailure { android.util.Log.w("HiddenMigration", "hide failed for " + contentId, it) }
                    kotlinx.coroutines.delay(250L)
                }
                traktSettingsDataStore.setHiddenMigrationDone(preRenderProfileId)
                android.util.Log.d("HiddenMigration", "migration complete")
            }.onFailure { android.util.Log.w("HiddenMigration", "migration aborted", it) }
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
        HomeScrollDiagnostics.recordCatalogSchedule()

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

