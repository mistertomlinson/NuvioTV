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
import com.nuvio.tv.data.repository.TraktProgressService
import javax.inject.Inject

@OptIn(kotlinx.coroutines.FlowPreview::class)
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
    internal val traktProgressService: TraktProgressService,
    internal val cwEnrichmentCache: ContinueWatchingEnrichmentCache,
) : ViewModel() {
    companion object {
        internal const val TAG = "HomeViewModel"
        private const val CONTINUE_WATCHING_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_RECENT_PROGRESS_ITEMS = 300
        private const val MAX_NEXT_UP_LOOKUPS = 24
        private const val MAX_NEXT_UP_CONCURRENCY = 4
        private const val MAX_CATALOG_LOAD_CONCURRENCY = 4
        internal const val EXTERNAL_META_PREFETCH_FOCUS_DEBOUNCE_MS = 220L
        internal const val EXTERNAL_META_PREFETCH_ADJACENT_DEBOUNCE_MS = 120L
        internal const val MAX_POSTER_STATUS_OBSERVERS = 24
    }

    internal val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
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
    internal fun setEnrichingItemId(id: String?) { _enrichingItemId.value = id }

    internal val catalogsMap: MutableMap<String, CatalogRow> = Collections.synchronizedMap(LinkedHashMap())
    internal val catalogOrder = mutableListOf<String>()
    internal var addonsCache: List<Addon> = emptyList()
    internal var homeCatalogOrderKeys: List<String> = emptyList()
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
    internal val cwMetaCache: MutableMap<String, com.nuvio.tv.domain.model.Meta?> = Collections.synchronizedMap(mutableMapOf())
    internal var catalogLoadGeneration: Long = 0L
    internal var catalogsLoadInProgress: Boolean = false
    internal data class TruncatedRowCacheEntry(
        val sourceRow: CatalogRow,
        val truncatedRow: CatalogRow
    )
    internal val truncatedRowCache = mutableMapOf<String, TruncatedRowCacheEntry>()
    internal val trailerPreviewLoadingIds = mutableSetOf<String>()
    internal val trailerPreviewNegativeCache = mutableSetOf<String>()
    internal val trailerPreviewUrlsState = mutableStateMapOf<String, String>()
    internal val trailerPreviewAudioUrlsState = mutableStateMapOf<String, String>()
    internal var activeTrailerPreviewItemId: String? = null
    internal var trailerPreviewRequestVersion: Long = 0L
    internal var currentTmdbSettings: TmdbSettings = TmdbSettings()
    internal var heroEnrichmentJob: Job? = null
    internal var lastHeroEnrichmentSignature: String? = null
    internal var lastHeroEnrichedItems: List<MetaPreview> = emptyList()
    internal val prefetchedExternalMetaIds = Collections.synchronizedSet(mutableSetOf<String>())
    internal val externalMetaPrefetchInFlightIds = Collections.synchronizedSet(mutableSetOf<String>())
    internal var externalMetaPrefetchJob: Job? = null
    internal var pendingExternalMetaPrefetchItemId: String? = null
    internal val prefetchedTmdbIds = Collections.synchronizedSet(mutableSetOf<String>())
    internal val enrichmentCache: MutableMap<String, TmdbEnrichment> = Collections.synchronizedMap(LinkedHashMap())
    internal var tmdbEnrichFocusJob: Job? = null
    internal var proactiveEnrichJob: Job? = null
    internal var pendingTmdbEnrichItemId: String? = null
    internal var adjacentItemPrefetchJob: Job? = null
    internal var pendingAdjacentPrefetchItemId: String? = null
    internal val posterLibraryObserverJobs = mutableMapOf<String, Job>()
    internal val movieWatchedObserverJobs = mutableMapOf<String, Job>()
    internal var movieWatchedBatchJob: Job? = null
    internal var lastMovieWatchedItemKeys: Set<String> = emptySet()
    internal var activePosterListPickerInput: LibraryEntryInput? = null
    @Volatile
    internal var externalMetaPrefetchEnabled: Boolean = false
    @Volatile
    internal var startupGracePeriodActive: Boolean = true
    internal var startupAuthNoticeJob: Job? = null
    internal var cwPipelineJob: Job? = null
    internal var cwEnrichmentJob: Job? = null


    val trailerPreviewUrls: Map<String, String>
        get() = trailerPreviewUrlsState
    val trailerPreviewAudioUrls: Map<String, String>
        get() = trailerPreviewAudioUrlsState

    init {
        observeLayoutPreferences()
        observeExternalMetaPrefetchPreference()
        loadHomeCatalogOrderPreference()
        loadDisabledHomeCatalogPreference()
        loadNumberedHomeCatalogPreference()
        observeLibraryState()
        observeTmdbSettings()
        observeStartupAuthNotice()
        loadContinueWatching()
        observeInstalledAddons()
        viewModelScope.launch {
            var previousProfileId = profileManager.activeProfileId.value
            profileManager.activeProfileId.collect { newId ->
                if (newId != previousProfileId) {
                    previousProfileId = newId
                    traktProgressService.resetForProfileSwitch()
                    cwMetaCache.clear()
                    // Pre-render new profile's cache instantly before pipeline catches up
                    _uiState.update { it.copy(continueWatchingItems = emptyList()) }
                    // Don't restart pipeline — it already observes activeProfileId via combine()
                    // Just pre-render from the new profile's cache
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val cachedInProgress = runCatching { cwEnrichmentCache.getInProgressSnapshot(newId) }.getOrDefault(emptyList())
                        val cachedNextUp = runCatching { cwEnrichmentCache.getNextUpSnapshot(newId) }.getOrDefault(emptyList())
                        android.util.Log.d("CW_TIMING", "🔄 profile switch pre-render: inProgress=${cachedInProgress.size} nextUp=${cachedNextUp.size}")
                        if (cachedInProgress.isEmpty() && cachedNextUp.isEmpty()) return@launch
                        val inProgressItems = cachedInProgress.map { cached ->
                            ContinueWatchingItem.InProgress(
                                progress = com.nuvio.tv.domain.model.WatchProgress(
                                    contentId = cached.contentId, contentType = cached.contentType,
                                    name = cached.name, poster = cached.poster, backdrop = cached.backdrop,
                                    logo = cached.logo, videoId = cached.videoId, season = cached.season,
                                    episode = cached.episode, episodeTitle = cached.episodeTitle,
                                    position = cached.position, duration = cached.duration,
                                    lastWatched = cached.lastWatched, progressPercent = cached.progressPercent
                                ),
                                episodeThumbnail = cached.episodeThumbnail,
                                episodeDescription = cached.episodeDescription,
                                episodeImdbRating = cached.episodeImdbRating,
                                genres = cached.genres, releaseInfo = cached.releaseInfo
                            )
                        }
                        val nextUpItems = cachedNextUp.map { cached ->
                            ContinueWatchingItem.NextUp(info = NextUpInfo(
                                contentId = cached.contentId, contentType = cached.contentType,
                                name = cached.name, poster = cached.poster, backdrop = cached.backdrop,
                                logo = cached.logo, videoId = cached.videoId, season = cached.season,
                                episode = cached.episode, episodeTitle = cached.episodeTitle,
                                episodeDescription = cached.episodeDescription, thumbnail = cached.thumbnail,
                                released = cached.released, hasAired = cached.hasAired,
                                airDateLabel = cached.airDateLabel, lastWatched = cached.lastWatched,
                                imdbRating = cached.imdbRating, genres = cached.genres, releaseInfo = cached.releaseInfo
                            ))
                        }
                        val items = mergeContinueWatchingItems(inProgressItems = inProgressItems, nextUpItems = nextUpItems)
                        if (items.isNotEmpty()) {
                            _uiState.update { state ->
                                if (state.continueWatchingItems.isEmpty()) state.copy(continueWatchingItems = items) else state
                            }
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            delay(3000)
            startupGracePeriodActive = false
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
    private fun loadNumberedHomeCatalogPreference() = loadNumberedHomeCatalogPreferencePipeline()

    private fun observeTmdbSettings() = observeTmdbSettingsPipeline()

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
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val cachedInProgress = runCatching { cwEnrichmentCache.getInProgressSnapshot() }.getOrDefault(emptyList())
            val cachedNextUp = runCatching { cwEnrichmentCache.getNextUpSnapshot() }.getOrDefault(emptyList())
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
            val nextUpItems = cachedNextUp
                .filter { nextUpDismissKey(it.contentId) !in dismissedNextUp }
                .map { cached ->
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
                pendingCatalogLoads > 8 -> 200L
                pendingCatalogLoads > 3 -> 150L
                pendingCatalogLoads > 0 -> 100L
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

    fun saveCachedVisiblePlatformIds(ids: Set<String>) {
        viewModelScope.launch {
            layoutPreferenceDataStore.setCachedVisiblePlatformIds(ids)
        }
    }

    override fun onCleared() {
        startupAuthNoticeJob?.cancel()
        posterStatusReconcileJob?.cancel()
        movieWatchedBatchJob?.cancel()
        cancelInFlightCatalogLoads()
        posterLibraryObserverJobs.values.forEach { it.cancel() }
        movieWatchedObserverJobs.values.forEach { it.cancel() }
        posterLibraryObserverJobs.clear()
        movieWatchedObserverJobs.clear()
        super.onCleared()
    }
}

