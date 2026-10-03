package com.nuvio.tv.data.repository

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.WatchProgressSyncService
import com.nuvio.tv.core.sync.WatchedItemsSyncService
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingHistoryWriterRegistry
import com.nuvio.tv.core.tracking.TrackingProgressProviderRegistry
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.effectiveWatchProgressSource
import com.nuvio.tv.core.tracking.mergeProgressProjectionWithRetainedLocal
import com.nuvio.tv.core.tracking.mergeWatchedEpisodeProjection
import com.nuvio.tv.core.tracking.providerId
import com.nuvio.tv.core.tracking.buildTrackingMediaReference
import android.util.Log
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOf

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class WatchProgressRepositoryImpl @Inject constructor(
    private val watchProgressPreferences: WatchProgressPreferences,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val trackingProgressProviders: TrackingProgressProviderRegistry,
    private val trackingHistoryWriters: TrackingHistoryWriterRegistry,
    private val profileManager: ProfileManager,
    private val watchProgressSyncService: WatchProgressSyncService,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val watchedSeriesStateHolder: WatchedSeriesStateHolder,
    private val watchedItemsSyncService: WatchedItemsSyncService,
    private val authManager: AuthManager,
    private val metaRepository: MetaRepository
) : WatchProgressRepository {
    companion object {
        private const val TAG = "WatchProgressRepo"
        private const val OPTIMISTIC_NEXT_UP_SEED_WINDOW_MS = 3 * 60_000L
    }

    private data class EpisodeMetadata(
        val title: String?,
        val thumbnail: String?
    )

    private data class ContentMetadata(
        val name: String?,
        val poster: String?,
        val backdrop: String?,
        val logo: String?,
        val episodes: Map<Pair<Int, Int>, EpisodeMetadata>
    )

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null
    private var watchedItemsSyncJob: Job? = null
    var isSyncingFromRemote = false
    private val optimisticContinueWatchingUpdates = MutableSharedFlow<WatchProgress>(
        replay = 1,
        extraBufferCapacity = 16
    )

    /*
     * Short-lived Next Up seeds created specifically by actual Player
     * completion. These are intentionally separate from ordinary watched
     * history so replay progression can temporarily override an older
     * "furthest watched" seed without changing the provider's normal policy.
     *
     * Profile-scoped to prevent cross-profile leakage.
     */
    private val playerCompletedNextUpSeedsByProfile =
        MutableStateFlow<Map<Int, Map<String, WatchProgress>>>(emptyMap())

    /*
     * This SharedFlow is a CW wake signal, not an authoritative progress store.
     * Stamp only the emitted copy at emission time so Home's 2-second replay
     * guard measures signal freshness rather than how long normalization or
     * local persistence took. The real WatchProgress timestamp is untouched.
     */
    private fun emitOptimisticContinueWatchingUpdate(progress: WatchProgress) {
        optimisticContinueWatchingUpdates.tryEmit(
            progress.copy(lastWatched = System.currentTimeMillis())
        )
    }

    var hasCompletedInitialPull = false
    var hasCompletedInitialWatchedItemsPull = false

    private val metadataState = MutableStateFlow<Map<String, ContentMetadata>>(emptyMap())
    private val metadataMutex = Mutex()
    private val inFlightMetadataKeys = mutableSetOf<String>()
    private val metadataHydrationLimit = 30

    private fun triggerRemoteSync() {
        if (isSyncingFromRemote) return
        if (!hasCompletedInitialPull) return
        if (!authManager.isAuthenticated) return
        syncJob?.cancel()
        syncJob = syncScope.launch {
            delay(2000)
            watchProgressSyncService.pushToRemote()
        }
    }

    private fun triggerWatchedItemsSync() {
        if (isSyncingFromRemote) return
        if (!hasCompletedInitialWatchedItemsPull) return
        if (!authManager.isAuthenticated) return
        watchedItemsSyncJob?.cancel()
        watchedItemsSyncJob = syncScope.launch {
            delay(2000)
            watchedItemsSyncService.pushToRemote()
        }
    }

    private fun hydrateMetadata(progressList: List<WatchProgress>) {
        val sorted = progressList.sortedByDescending { it.lastWatched }
        val uniqueByContent = linkedMapOf<String, WatchProgress>()
        sorted.forEach { progress ->
            if (uniqueByContent.size < metadataHydrationLimit) {
                uniqueByContent.putIfAbsent(progress.contentId, progress)
            }
        }

        uniqueByContent.values.forEach { progress ->
            val contentId = progress.contentId
            if (contentId.isBlank()) return@forEach
            if (metadataState.value.containsKey(contentId)) return@forEach

            syncScope.launch {
                val shouldFetch = metadataMutex.withLock {
                    if (metadataState.value.containsKey(contentId)) return@withLock false
                    if (inFlightMetadataKeys.contains(contentId)) return@withLock false
                    inFlightMetadataKeys.add(contentId)
                    true
                }
                if (!shouldFetch) return@launch

                try {
                    val metadata = fetchContentMetadata(
                        contentId = contentId,
                        contentType = progress.contentType
                    ) ?: return@launch
                    metadataState.update { current ->
                        current + (contentId to metadata)
                    }
                } finally {
                    metadataMutex.withLock {
                        inFlightMetadataKeys.remove(contentId)
                    }
                }
            }
        }
    }

    private suspend fun fetchContentMetadata(
        contentId: String,
        contentType: String
    ): ContentMetadata? {
        val typeCandidates = buildList {
            val normalized = contentType.lowercase()
            if (normalized.isNotBlank()) add(normalized)
            if (normalized in listOf("series", "tv")) {
                add("series")
                add("tv")
            } else {
                add("movie")
            }
        }.distinct()

        val idCandidates = buildList {
            add(contentId)
            if (contentId.startsWith("tmdb:")) add(contentId.substringAfter(':'))
            if (contentId.startsWith("trakt:")) add(contentId.substringAfter(':'))
        }.distinct()

        for (type in typeCandidates) {
            for (candidateId in idCandidates) {
                val result = withTimeoutOrNull(3500) {
                    metaRepository.getMetaFromPrimaryAddon(type = type, id = candidateId)
                        .first { it !is NetworkResult.Loading }
                } ?: continue

                val meta = (result as? NetworkResult.Success)?.data ?: continue
                val episodes = meta.videos
                    .mapNotNull { video ->
                        val season = video.season ?: return@mapNotNull null
                        val episode = video.episode ?: return@mapNotNull null
                        (season to episode) to EpisodeMetadata(
                            title = video.title,
                            thumbnail = video.thumbnail
                        )
                    }
                    .toMap()

                return ContentMetadata(
                    name = meta.name,
                    poster = meta.poster,
                    backdrop = meta.background,
                    logo = meta.logo,
                    episodes = episodes
                )
            }
        }
        return null
    }

    private fun enrichWithMetadata(
        progress: WatchProgress,
        metadataMap: Map<String, ContentMetadata>
    ): WatchProgress {
        val metadata = metadataMap[progress.contentId] ?: return progress
        val episodeMeta = if (progress.season != null && progress.episode != null) {
            metadata.episodes[progress.season to progress.episode]
        } else {
            null
        }
        val shouldOverrideName = progress.name.isBlank() || progress.name == progress.contentId
        val backdrop = progress.backdrop
            ?: metadata.backdrop
            ?: episodeMeta?.thumbnail

        return progress.copy(
            name = if (shouldOverrideName) metadata.name ?: progress.name else progress.name,
            poster = progress.poster ?: metadata.poster,
            backdrop = backdrop,
            logo = progress.logo ?: metadata.logo,
            episodeTitle = progress.episodeTitle ?: episodeMeta?.title
        )
    }

    private val progressProviderConnections = combine(
        trackingProgressProviders.providers().map { provider ->
            provider.isAuthenticated.map { authenticated ->
                provider.providerId to authenticated
            }
        }
    ) { states ->
        states.toMap()
    }

    @Volatile
    private var activeProgressProviderId: TrackingProviderId? = null

    init {
        syncScope.launch {
            activeProgressProviderFlow().collect { provider ->
                activeProgressProviderId = provider?.providerId
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun activeProgressProviderFlow(): Flow<TrackingProgressProvider?> {
        return combine(
            traktSettingsDataStore.watchProgressSource,
            progressProviderConnections
        ) { requestedSource, connections ->
            effectiveWatchProgressSource(requestedSource) { providerId ->
                connections[providerId] == true
            }.providerId?.let(trackingProgressProviders::provider)
        }
            .debounce { provider -> if (provider == null) 300L else 0L }
            .distinctUntilChanged()
    }

    private suspend fun activeProgressProvider(): TrackingProgressProvider? =
        activeProgressProviderFlow().first()

    private fun progressProjectionKey(progress: WatchProgress): String =
        "${progress.contentId}_${progress.season}_${progress.episode}"

    private fun providerAllProgressFlow(
        provider: TrackingProgressProvider
    ): Flow<List<WatchProgress>> {
        return combine(
            provider.allProgress.onStart { emit(emptyList()) },
            watchProgressPreferences.allProgress.onStart { emit(emptyList()) },
            watchedItemsPreferences.allItems.onStart { emit(emptyList()) },
            metadataState
        ) { providerItems, localItems, watchedItems, metadataMap ->
            /*
             * Simkl can retain an old playback session even after the exact
             * episode has been completed.
             *
             * The Player already persists the completed episode and its
             * timestamp in WatchedItemsPreferences. Use that durable state
             * to reject only stale Simkl playback records.
             *
             * A genuinely newer replay remains valid because its playback
             * timestamp is newer than the prior watched timestamp.
             */
            val effectiveProviderItems =
                suppressStaleSimklPlaybackAlreadyWatched(
                    providerId = provider.providerId,
                    providerEntries = providerItems,
                    watchedItems = watchedItems
                )

            hydrateMetadata(effectiveProviderItems)

            val localByKey = localItems.associateBy(::progressProjectionKey)
            mergeProgressProjectionWithRetainedLocal(
                providerEntries = effectiveProviderItems,
                localEntries = localItems,
                retainsLocalProgress = provider::retainsLocalProgress
            ).map { item ->
                val enriched = enrichWithMetadata(item, metadataMap)
                val local = localByKey[progressProjectionKey(enriched)]

                enriched.copy(
                    duration = if (enriched.duration <= 0L && local?.duration?.let { it > 0L } == true) {
                        local.duration
                    } else {
                        enriched.duration
                    },
                    position = if (enriched.position <= 0L && local?.position?.let { it > 0L } == true) {
                        local.position
                    } else {
                        enriched.position
                    }
                )
            }.sortedByDescending(WatchProgress::lastWatched)
        }.distinctUntilChanged()
    }

    private fun localAllProgressFlow(): Flow<List<WatchProgress>> {
        return combine(
            watchProgressPreferences.allProgress,
            metadataState
        ) { items, metadataMap ->
            hydrateMetadata(items)
            items.map { item -> enrichWithMetadata(item, metadataMap) }
                .sortedByDescending(WatchProgress::lastWatched)
        }.distinctUntilChanged()
    }

    override val allProgress: Flow<List<WatchProgress>>
        get() = activeProgressProviderFlow()
            .flatMapLatest { provider ->
                provider?.let(::providerAllProgressFlow) ?: localAllProgressFlow()
            }

    override val continueWatching: Flow<List<WatchProgress>>
        get() = allProgress.map { items ->
            items.filter(WatchProgress::isInProgress)
        }

    override fun getProgress(contentId: String): Flow<WatchProgress?> {
        return activeProgressProviderFlow()
            .flatMapLatest { provider ->
                if (provider != null) {
                    provider.allProgress.map { items ->
                        items
                            .filter { item ->
                                item.contentId.equals(contentId, ignoreCase = true)
                            }
                            .maxByOrNull(WatchProgress::lastWatched)
                    }
                } else {
                    watchProgressPreferences.getProgress(contentId)
                }
            }
    }

    override fun getEpisodeProgress(
        contentId: String,
        season: Int,
        episode: Int
    ): Flow<WatchProgress?> {
        return activeProgressProviderFlow()
            .flatMapLatest { provider ->
                if (provider != null) {
                    combine(
                        provider.episodeProgress(contentId),
                        watchedItemsPreferences.allItems
                    ) { providerMap, watchedItems ->
                        suppressStaleSimklPlaybackAlreadyWatched(
                            providerId = provider.providerId,
                            providerEntries =
                                providerMap.values.toList(),
                            watchedItems = watchedItems
                        )
                            .firstOrNull { progress ->
                                progress.season == season &&
                                    progress.episode == episode
                            }
                    }
                } else {
                    watchProgressPreferences.getEpisodeProgress(
                        contentId,
                        season,
                        episode
                    )
                }
            }
    }

    override fun observeNextUpSeeds(): Flow<List<WatchProgress>> {
        return activeProgressProviderFlow()
            .flatMapLatest { provider ->
                if (provider != null) {
                    combine(
                        provider.nextUpSeeds,
                        playerCompletedNextUpSeedsByProfile,
                        profileManager.activeProfileId
                    ) {
                        providerSeeds,
                        playerSeedsByProfile,
                        profileId ->

                        mergeProviderNextUpSeedsWithPlayerCompletions(
                            providerSeeds = providerSeeds,
                            playerCompletionSeeds =
                                playerSeedsByProfile[
                                    profileId
                                ].orEmpty().values,
                            nowEpochMs =
                                System.currentTimeMillis(),
                            maxAgeMs =
                                OPTIMISTIC_NEXT_UP_SEED_WINDOW_MS
                        )
                    }
                } else {
                    watchedItemsPreferences.allItems.map { items ->
                        items
                            .filter { item ->
                                (item.contentType.equals("series", ignoreCase = true) ||
                                    item.contentType.equals("tv", ignoreCase = true)) &&
                                    item.season != null &&
                                    item.episode != null &&
                                    item.season != 0 &&
                                    !isMalformedNextUpSeedContentId(item.contentId)
                            }
                            .groupBy(WatchedItem::contentId)
                            .mapNotNull { (_, episodes) ->
                                val latest = episodes.maxWithOrNull(
                                    compareBy<WatchedItem> { it.watchedAt }
                                        .thenBy { it.season ?: 0 }
                                        .thenBy { it.episode ?: 0 }
                                ) ?: return@mapNotNull null

                                WatchProgress(
                                    contentId = latest.contentId,
                                    contentType = latest.contentType,
                                    name = latest.title,
                                    poster = null,
                                    backdrop = null,
                                    logo = null,
                                    videoId = latest.contentId,
                                    season = latest.season,
                                    episode = latest.episode,
                                    episodeTitle = null,
                                    position = 1L,
                                    duration = 1L,
                                    lastWatched = latest.watchedAt,
                                    progressPercent = 100f,
                                    source = WatchProgress.SOURCE_LOCAL
                                )
                            }
                    }
                }
            }
            .distinctUntilChanged()
    }

    private fun isMalformedNextUpSeedContentId(contentId: String?): Boolean {
        val trimmed = contentId?.trim().orEmpty()
        if (trimmed.isEmpty()) return true

        return when (trimmed.lowercase()) {
            "tmdb", "imdb", "trakt", "tmdb:", "imdb:", "trakt:" -> true
            else -> false
        }
    }

    override fun getAllEpisodeProgress(
        contentId: String
    ): Flow<Map<Pair<Int, Int>, WatchProgress>> {
        return activeProgressProviderFlow()
            .flatMapLatest { provider ->
                if (provider != null) {
                    combine(
                        provider.episodeProgress(contentId)
                            .onStart { emit(emptyMap()) },
                        provider.allProgress.map { items ->
                            items.filter { progress ->
                                progress.contentId.equals(
                                    contentId,
                                    ignoreCase = true
                                ) &&
                                    progress.season != null &&
                                    progress.episode != null
                            }
                        },
                        watchedItemsPreferences.allItems
                    ) {
                        providerMap,
                        liveEpisodes,
                        watchedItems ->

                        val effectiveProviderEpisodes =
                            suppressStaleSimklPlaybackAlreadyWatched(
                                providerId =
                                    provider.providerId,
                                providerEntries =
                                    providerMap.values.toList(),
                                watchedItems =
                                    watchedItems
                            )

                        val effectiveLiveEpisodes =
                            suppressStaleSimklPlaybackAlreadyWatched(
                                providerId =
                                    provider.providerId,
                                providerEntries =
                                    liveEpisodes,
                                watchedItems =
                                    watchedItems
                            )

                        buildMap {
                            effectiveProviderEpisodes.forEach {
                                progress ->
                                val season =
                                    progress.season
                                        ?: return@forEach
                                val episode =
                                    progress.episode
                                        ?: return@forEach

                                this[
                                    season to episode
                                ] = progress
                            }

                            /*
                             * A genuinely newer active playback remains
                             * authoritative over completed history.
                             */
                            effectiveLiveEpisodes.forEach {
                                progress ->
                                val season =
                                    progress.season
                                        ?: return@forEach
                                val episode =
                                    progress.episode
                                        ?: return@forEach

                                this[
                                    season to episode
                                ] = progress
                            }
                        }
                    }.distinctUntilChanged()
                } else {
                    watchProgressPreferences
                        .getAllEpisodeProgress(
                            contentId
                        )
                }
            }
    }

    @OptIn(FlowPreview::class)
    override fun observeWatchedMovieIds(): Flow<Set<String>> {
        return activeProgressProviderFlow()
            .flatMapLatest { provider ->
                if (provider != null) {
                    provider.watchedMovieIds
                } else {
                    combine(
                        watchProgressPreferences.allProgress,
                        watchedItemsPreferences.allItems
                    ) { progressList, watchedItems ->
                        val completedIds = progressList
                            .filter(WatchProgress::isCompleted)
                            .map(WatchProgress::contentId)
                            .toSet()

                        val watchedItemIds = watchedItems
                            .filter { item ->
                                item.season == null && item.episode == null
                            }
                            .map(WatchedItem::contentId)
                            .toSet()

                        completedIds + watchedItemIds
                    }.debounce(500)
                }
            }
            .distinctUntilChanged()
    }

    override fun isWatched(
        contentId: String,
        videoId: String?,
        season: Int?,
        episode: Int?
    ): Flow<Boolean> {
        return activeProgressProviderFlow()
            .flatMapLatest { provider ->
                if (provider != null) {
                    provider.isWatched(contentId, videoId, season, episode)
                } else {
                    val progressFlow =
                        if (season != null && episode != null) {
                            watchProgressPreferences.getEpisodeProgress(
                                contentId,
                                season,
                                episode
                            )
                        } else {
                            watchProgressPreferences.getProgress(contentId)
                        }

                    combine(
                        progressFlow,
                        watchedItemsPreferences.isWatched(
                            contentId,
                            season,
                            episode
                        )
                    ) { progressEntry, itemWatched ->
                        progressEntry?.isCompleted() == true || itemWatched
                    }
                }
            }
            .distinctUntilChanged()
    }
    override suspend fun saveProgress(
        progress: WatchProgress,
        syncRemote: Boolean
    ) {
        val provider =
            activeProgressProviderId
                ?.let(trackingProgressProviders::provider)
                ?: activeProgressProvider()

        // Always retain a durable local copy. The selected provider receives
        // only its own optimistic projection; no write is broadcast.
        provider?.applyOptimisticProgress(
            progress = progress,
            quiet = !syncRemote
        )

        watchProgressPreferences.saveProgress(progress)
        provider?.persistDurableProgress(progress)

        val isSeriesEpisode =
            (
                progress.contentType.equals("series", ignoreCase = true) ||
                    progress.contentType.equals("tv", ignoreCase = true)
                ) &&
                progress.season != null &&
                progress.episode != null &&
                progress.season != 0

        if (progress.isCompleted()) {
            watchedItemsPreferences.markAsWatched(
                WatchedItem(
                    contentId = progress.contentId,
                    contentType = progress.contentType,
                    title = progress.name,
                    season = progress.season,
                    episode = progress.episode,
                    watchedAt = progress.lastWatched
                )
            )

            if (provider != null && isSeriesEpisode) {
                // Prevent the completed episode from remaining in Continue
                // Watching while its selected provider settles.
                provider.applyOptimisticRemoval(
                    contentId = progress.contentId,
                    videoId = progress.videoId,
                    season = progress.season,
                    episode = progress.episode
                )
            }
        }

        // Final/switch/exit saves wake CW only after every local/provider state
        // used by the Home projection is already settled. Periodic saves keep
        // using the normal debounced flow path.
        if (syncRemote) {
            emitOptimisticContinueWatchingUpdate(progress)
        }

        if (provider != null) {
            return
        }

        // Nuvio Sync is the active destination only when no external
        // progress provider is selected.
        if (syncRemote && authManager.isAuthenticated) {
            syncScope.launch {
                watchProgressSyncService
                    .pushSingleToRemote(progressKey(progress), progress)
                    .onFailure { error ->
                        Log.w(
                            TAG,
                            "Failed single progress push; " +
                                "falling back to full sync next cycle",
                            error
                        )
                    }
            }
        }

        if (progress.isCompleted()) {
            triggerWatchedItemsSync()
        }
    }
    override suspend fun removeProgress(
        contentId: String,
        season: Int?,
        episode: Int?
    ) {
        val provider = activeProgressProvider()

        val remoteDeleteKeys =
            if (provider == null) {
                resolveRemoteDeleteKeys(contentId, season, episode)
            } else {
                emptyList()
            }

        if (provider != null) {
            provider.applyOptimisticRemoval(
                contentId = contentId,
                videoId = null,
                season = season,
                episode = episode
            )
            provider.removeProgress(contentId, season, episode)
        }

        watchProgressPreferences.removeProgress(
            contentId,
            season,
            episode
        )

        if (provider != null) {
            return
        }

        if (
            authManager.isAuthenticated &&
            remoteDeleteKeys.isNotEmpty()
        ) {
            watchProgressSyncService
                .deleteFromRemote(remoteDeleteKeys)
                .onFailure { error ->
                    Log.w(
                        TAG,
                        "removeProgress remote delete failed; " +
                            "relying on push sync",
                        error
                    )
                }
        }

        triggerRemoteSync()
    }

    override suspend fun consumePlayerRewatchNextUp(
        contentId: String,
        seedSeason: Int?,
        seedEpisode: Int?,
        nextSeason: Int?,
        nextEpisode: Int?
    ): Boolean {
        val resolvedSeedSeason =
            seedSeason ?: return false
        val resolvedSeedEpisode =
            seedEpisode ?: return false
        val resolvedNextSeason =
            nextSeason ?: return false
        val resolvedNextEpisode =
            nextEpisode ?: return false

        /*
         * Rewatch progression is only injected into provider-backed Next Up.
         * Local/Nuvio Sync continues using its existing dismissal semantics.
         */
        activeProgressProvider()
            ?: return false

        val profileId =
            profileManager.activeProfileId.value
        val contentKey =
            rewatchSeedContentKey(contentId)

        val playerSeed =
            playerCompletedNextUpSeedsByProfile
                .value[profileId]
                .orEmpty()[contentKey]
                ?: return false

        /*
         * Use the already-established watched projection rather than making
         * provider-specific assumptions. This includes the active provider
         * plus retained local watched episodes where applicable.
         */
        val watchedEpisodesByContent =
            runCatching {
                getWatchedShowEpisodes()
            }.getOrDefault(emptyMap())

        val nextEpisodeWasAlreadyWatched =
            watchedEpisodesByContent
                .entries
                .firstOrNull { (watchedContentId, _) ->
                    watchedContentId.equals(
                        contentId,
                        ignoreCase = true
                    )
                }
                ?.value
                ?.contains(
                    resolvedNextSeason to
                        resolvedNextEpisode
                ) == true

        val shouldConsume =
            isPlayerRewatchNextUpDismissal(
                playerSeed = playerSeed,
                contentId = contentId,
                seedSeason = resolvedSeedSeason,
                seedEpisode = resolvedSeedEpisode,
                nextSeason = resolvedNextSeason,
                nextEpisode = resolvedNextEpisode,
                nextEpisodeWasAlreadyWatched =
                    nextEpisodeWasAlreadyWatched,
                nowEpochMs =
                    System.currentTimeMillis(),
                maxAgeMs =
                    OPTIMISTIC_NEXT_UP_SEED_WINDOW_MS
            )

        if (!shouldConsume) {
            return false
        }

        /*
         * Consume only the short-lived Player seed.
         *
         * Critically, do NOT call provider.dismissNextUp(). For Trakt that
         * operation maps to hideShowFromProgress(), which would hide the
         * entire show rather than simply ending this rewatch sequence.
         */
        playerCompletedNextUpSeedsByProfile.update {
            current ->
            val profileSeeds =
                current[profileId].orEmpty()

            val updatedProfileSeeds =
                profileSeeds - contentKey

            if (updatedProfileSeeds.isEmpty()) {
                current - profileId
            } else {
                current + (
                    profileId to
                        updatedProfileSeeds
                    )
            }
        }

        return true
    }

    override suspend fun dismissNextUp(
        contentId: String,
        season: Int?,
        episode: Int?
    ): Boolean = dismissNextUpWithProvider(
        provider = activeProgressProvider(),
        contentId = contentId,
        season = season,
        episode = episode
    )

    override suspend fun removeFromHistory(
        contentId: String,
        videoId: String?,
        season: Int?,
        episode: Int?
    ) {
        val provider = activeProgressProvider()

        val remoteDeleteKeys =
            if (provider == null) {
                resolveRemoteDeleteKeys(contentId, season, episode)
            } else {
                emptyList()
            }

        if (provider != null) {
            val writer = trackingHistoryWriters.writer(
                provider.providerId
            ) ?: throw IllegalStateException(
                "No history writer registered for ${provider.providerId}"
            )

            val media = buildTrackingMediaReference(
                contentType =
                    if (season != null && episode != null) {
                        "series"
                    } else {
                        "movie"
                    },
                parentMetaId = contentId,
                videoId = videoId,
                seasonNumber = season,
                episodeNumber = episode
            )

            provider.applyOptimisticRemoval(
                contentId = contentId,
                videoId = videoId,
                season = season,
                episode = episode
            )
            try {
                writer.removeFromHistory(
                    profileId = profileManager.activeProfileId.value,
                    items = listOf(media)
                )
            } catch (error: Throwable) {
                provider.clearOptimisticRemoval(
                    contentId = contentId,
                    videoId = videoId,
                    season = season,
                    episode = episode
                )
                throw error
            }
        }

        watchProgressPreferences.removeProgress(
            contentId,
            season,
            episode
        )
        watchedItemsPreferences.unmarkAsWatched(
            contentId,
            season,
            episode
        )

        if (season != null && episode != null) {
            invalidateSeriesWatchedBadge(setOf(contentId))
        }

        val playerSeedProfileId =
            profileManager.activeProfileId.value
        val playerSeedContentKey =
            rewatchSeedContentKey(contentId)

        playerCompletedNextUpSeedsByProfile.update {
            current ->
            val profileSeeds =
                current[playerSeedProfileId].orEmpty()
            val updatedProfileSeeds =
                profileSeeds - playerSeedContentKey

            if (updatedProfileSeeds.isEmpty()) {
                current - playerSeedProfileId
            } else {
                current + (
                    playerSeedProfileId to
                        updatedProfileSeeds
                    )
            }
        }

        if (provider != null) {
            return
        }

        if (
            authManager.isAuthenticated &&
            remoteDeleteKeys.isNotEmpty()
        ) {
            watchProgressSyncService
                .deleteFromRemote(remoteDeleteKeys)
                .onFailure { error ->
                    Log.w(
                        TAG,
                        "removeFromHistory remote delete failed; " +
                            "relying on push sync",
                        error
                    )
                }
        }

        triggerRemoteSync()
        triggerWatchedItemsSync()
    }
    override suspend fun markAsCompleted(
        progress: WatchProgress,
        broadcastTrackingHistory: Boolean
    ) {
        val provider =
            activeProgressProviderId
                ?.let(trackingProgressProviders::provider)
                ?: activeProgressProvider()
        val now = System.currentTimeMillis()
        val duration = progress.duration.takeIf { it > 0L } ?: 1L

        val completed = progress.copy(
            position = duration,
            duration = duration,
            progressPercent = 100f,
            lastWatched = now
        )

        if (provider != null) {
            // Install the provider-local watched/completed projection first so
            // badges and Next Up see the completed episode before CW refreshes.
            provider.applyOptimisticProgress(
                progress = completed,
                quiet = false
            )

            // Explicit user "mark watched" actions should keep their immediate
            // optimistic CW/Next Up response even if tracking-history persistence
            // takes longer. Player completion passes broadcastTrackingHistory=false
            // and therefore waits for the authoritative local state below.
            if (broadcastTrackingHistory) {
                emitOptimisticContinueWatchingUpdate(completed)
            }

            if (broadcastTrackingHistory) {
                val writer = trackingHistoryWriters.writer(
                    provider.providerId
                ) ?: throw IllegalStateException(
                    "No history writer registered for ${provider.providerId}"
                )

                val media = buildTrackingMediaReference(
                    contentType = completed.contentType,
                    parentMetaId = completed.contentId,
                    videoId = completed.videoId,
                    title = completed.name,
                    seasonNumber = completed.season,
                    episodeNumber = completed.episode,
                    episodeTitle = completed.episodeTitle,
                    posterUrl = completed.poster
                )

                runCatching {
                    writer.addToHistory(
                        profileId = profileManager.activeProfileId.value,
                        items = listOf(
                            TrackingHistoryItem(
                                media = media,
                                watchedAtEpochMs = now
                            )
                        )
                    )
                }.onFailure {
                    provider.applyOptimisticRemoval(
                        contentId = completed.contentId,
                        videoId = completed.videoId,
                        season = completed.season,
                        episode = completed.episode
                    )
                    throw it
                }
            }

            provider.persistDurableProgress(completed)
        }

        watchProgressPreferences.markAsCompleted(completed)
        watchedItemsPreferences.markAsWatched(
            WatchedItem(
                contentId = completed.contentId,
                contentType = completed.contentType,
                title = completed.name,
                season = completed.season,
                episode = completed.episode,
                watchedAt = now
            )
        )

        val isPlayerCompletedSeriesEpisode =
            !broadcastTrackingHistory &&
                (
                    completed.contentType.equals(
                        "series",
                        ignoreCase = true
                    ) ||
                        completed.contentType.equals(
                            "tv",
                            ignoreCase = true
                        )
                    ) &&
                completed.season != null &&
                completed.episode != null &&
                completed.season != 0

        if (isPlayerCompletedSeriesEpisode) {
            val profileId =
                profileManager.activeProfileId.value
            val contentKey =
                rewatchSeedContentKey(
                    completed.contentId
                )

            playerCompletedNextUpSeedsByProfile.update {
                current ->
                val profileSeeds =
                    current[profileId].orEmpty()

                current + (
                    profileId to (
                        profileSeeds + (
                            contentKey to completed
                            )
                        )
                    )
            }
        }

        // Player completion (broadcastTrackingHistory=false) waits until provider
        // and local completed/watched state are fully settled before waking CW.
        // Provider-backed explicit "mark watched" actions already emitted above.
        if (provider == null || !broadcastTrackingHistory) {
            emitOptimisticContinueWatchingUpdate(completed)
        }

        if (provider == null) {
            triggerRemoteSync()
            triggerWatchedItemsSync()
        }
    }
    override suspend fun clearAll() {
        activeProgressProvider()?.clearOptimistic()
        playerCompletedNextUpSeedsByProfile.value =
            emptyMap()
        watchProgressPreferences.clearAll()
    }


    override fun getAiredEpisodeOrder(
        contentId: String
    ): Flow<List<Pair<Int, Int>>> {
        return activeProgressProviderFlow()
            .flatMapLatest { provider ->
                provider?.airedEpisodeOrder(contentId) ?: flowOf(emptyList())
            }
            .distinctUntilChanged()
    }

    override fun observeRemoteProgressLoaded(): Flow<Boolean> {
        return activeProgressProviderFlow()
            .flatMapLatest { provider ->
                provider?.remoteProgressLoaded ?: flowOf(true)
            }
            .distinctUntilChanged()
    }

    override suspend fun prepareNextUpSeed(
        progress: WatchProgress
    ): WatchProgress {
        return activeProgressProvider()?.prepareNextUpSeed(progress) ?: progress
    }

    override fun observeOptimisticContinueWatchingUpdates(): Flow<WatchProgress> {
        return optimisticContinueWatchingUpdates
    }

    override suspend fun getWatchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>> {
        val provider = activeProgressProvider()
        if (provider != null) {
            return mergeWatchedEpisodeProjection(
                providerEpisodes = provider.watchedShowEpisodes(),
                localItems = watchedItemsPreferences.getAllItems(),
                retainsLocalWatchedEpisode = provider::retainsLocalWatchedEpisode
            )
        }

        return watchedItemsPreferences.allItems.first()
            .filter { item ->
                item.season != null && item.episode != null
            }
            .groupBy(WatchedItem::contentId)
            .mapValues { (_, items) ->
                items.map { item ->
                    requireNotNull(item.season) to requireNotNull(item.episode)
                }.toSet()
            }
    }

    override suspend fun getShowIdSiblings(): Map<String, Set<String>> {
        return activeProgressProvider()?.showIdSiblings().orEmpty()
    }

    override fun isWatchedByVideoId(
        videoId: String,
        episode: Int
    ): Boolean {
        val providerId = activeProgressProviderId ?: return false
        return trackingProgressProviders
            .provider(providerId)
            ?.isWatchedByVideoId(videoId, episode)
            ?: false
    }

    override suspend fun saveProgressBatch(progressList: List<WatchProgress>, syncRemote: Boolean) {
        progressList.forEach { saveProgress(it, syncRemote) }
    }

    override suspend fun markAsCompletedBatch(
        progressList: List<WatchProgress>
    ) {
        if (progressList.isEmpty()) return

        val distinct = progressList
            .distinctBy { progress ->
                Triple(
                    progress.contentId.trim().lowercase(),
                    progress.season,
                    progress.episode
                )
            }
        val now = System.currentTimeMillis()
        val completed = distinct.mapIndexed { index, progress ->
            val duration = progress.duration.takeIf { it > 0L } ?: 1L
            progress.copy(
                position = duration,
                duration = duration,
                progressPercent = 100f,
                // Keep ordering deterministic while still treating this as one
                // user action.
                lastWatched = now + index
            )
        }

        val provider =
            activeProgressProviderId
                ?.let(trackingProgressProviders::provider)
                ?: activeProgressProvider()

        if (provider != null) {
            val writer = trackingHistoryWriters.writer(
                provider.providerId
            ) ?: throw IllegalStateException(
                "No history writer registered for ${provider.providerId}"
            )

            completed.forEach { progress ->
                provider.applyOptimisticProgress(
                    progress = progress,
                    quiet = true
                )
            }

            val historyItems = completed.map { progress ->
                TrackingHistoryItem(
                    media = buildTrackingMediaReference(
                        contentType = progress.contentType,
                        parentMetaId = progress.contentId,
                        videoId = progress.videoId,
                        title = progress.name,
                        seasonNumber = progress.season,
                        episodeNumber = progress.episode,
                        episodeTitle = progress.episodeTitle,
                        posterUrl = progress.poster
                    ),
                    watchedAtEpochMs = progress.lastWatched
                )
            }

            try {
                val result = writer.addToHistory(
                    profileId = profileManager.activeProfileId.value,
                    items = historyItems
                )
                check(result.isComplete) {
                    "Tracking provider could not match " +
                        "${result.notFoundCount} of " +
                        "${result.attemptedCount} watched episodes"
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                completed.forEach { progress ->
                    provider.applyOptimisticRemoval(
                        contentId = progress.contentId,
                        videoId = progress.videoId,
                        season = progress.season,
                        episode = progress.episode
                    )
                }
                throw error
            }

            provider.persistDurableProgressBatch(completed)
        }

        val watchedItems = completed.map { progress ->
            WatchedItem(
                contentId = progress.contentId,
                contentType = progress.contentType,
                title = progress.name,
                season = progress.season,
                episode = progress.episode,
                watchedAt = progress.lastWatched
            )
        }
        watchProgressPreferences.markAsCompletedBatch(completed)
        watchedItemsPreferences.markAsWatchedBatch(watchedItems)

        /*
         * One wake-up is enough for CW/Next Up/badges to recompute from the
         * completed batch. Avoid N separate Home recomputations for long shows.
         */
        completed.maxWithOrNull(
            compareBy<WatchProgress>(
                { it.season ?: Int.MIN_VALUE },
                { it.episode ?: Int.MIN_VALUE }
            )
        )?.let(::emitOptimisticContinueWatchingUpdate)

        if (provider == null) {
            triggerRemoteSync()
            triggerWatchedItemsSync()
        }
    }

    override suspend fun removeFromHistoryBatch(
        contentId: String,
        videoId: String?,
        episodes: List<Pair<Int, Int>>
    ) {
        episodes.forEach { (season, episode) ->
            removeFromHistory(contentId, videoId, season, episode)
        }
    }

    override suspend fun removeFromHistoryBatch(
        progressList: List<WatchProgress>
    ) {
        if (progressList.isEmpty()) return

        val distinct = progressList.distinctBy { progress ->
            Triple(
                progress.contentId.trim().lowercase(),
                progress.season,
                progress.episode
            )
        }
        val provider = activeProgressProvider()
        val remoteDeleteKeys =
            if (provider == null) {
                distinct.flatMap { progress ->
                    resolveRemoteDeleteKeys(
                        contentId = progress.contentId,
                        season = progress.season,
                        episode = progress.episode
                    )
                }.distinct()
            } else {
                emptyList()
            }

        if (provider != null) {
            val writer = trackingHistoryWriters.writer(
                provider.providerId
            ) ?: throw IllegalStateException(
                "No history writer registered for ${provider.providerId}"
            )

            val media = distinct.map { progress ->
                buildTrackingMediaReference(
                    contentType = progress.contentType,
                    parentMetaId = progress.contentId,
                    videoId = progress.videoId,
                    title = progress.name,
                    seasonNumber = progress.season,
                    episodeNumber = progress.episode,
                    episodeTitle = progress.episodeTitle,
                    posterUrl = progress.poster
                )
            }

            distinct.forEach { progress ->
                provider.applyOptimisticRemoval(
                    contentId = progress.contentId,
                    videoId = progress.videoId,
                    season = progress.season,
                    episode = progress.episode
                )
            }

            try {
                val result = writer.removeFromHistory(
                    profileId = profileManager.activeProfileId.value,
                    items = media
                )
                check(result.isComplete) {
                    "Tracking provider could not match " +
                        "${result.notFoundCount} of " +
                        "${result.attemptedCount} watched episodes"
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                distinct.forEach { progress ->
                    provider.clearOptimisticRemoval(
                        contentId = progress.contentId,
                        videoId = progress.videoId,
                        season = progress.season,
                        episode = progress.episode
                    )
                }
                throw error
            }
        }

        watchProgressPreferences.removeProgressBatch(distinct)
        watchedItemsPreferences.unmarkAsWatchedBatch(
            distinct.map { progress ->
                WatchedItem(
                    contentId = progress.contentId,
                    contentType = progress.contentType,
                    title = progress.name,
                    season = progress.season,
                    episode = progress.episode,
                    watchedAt = progress.lastWatched
                )
            }
        )

        invalidateSeriesWatchedBadge(
            distinct.asSequence()
                .filter {
                    it.season != null &&
                        it.episode != null
                }
                .map { it.contentId }
                .toSet()
        )

        val profileId = profileManager.activeProfileId.value
        val removedContentKeys = distinct
            .map { rewatchSeedContentKey(it.contentId) }
            .toSet()
        playerCompletedNextUpSeedsByProfile.update { current ->
            val profileSeeds = current[profileId].orEmpty()
            val updatedProfileSeeds =
                profileSeeds.filterKeys { key -> key !in removedContentKeys }

            if (updatedProfileSeeds.isEmpty()) {
                current - profileId
            } else {
                current + (profileId to updatedProfileSeeds)
            }
        }

        if (provider != null) return

        if (
            authManager.isAuthenticated &&
            remoteDeleteKeys.isNotEmpty()
        ) {
            watchProgressSyncService
                .deleteFromRemote(remoteDeleteKeys)
                .onFailure { error ->
                    Log.w(
                        TAG,
                        "removeFromHistoryBatch remote delete failed; " +
                            "relying on push sync",
                        error
                    )
                }
        }

        triggerRemoteSync()
        triggerWatchedItemsSync()
    }

    override fun isDroppedShow(contentId: String): Boolean {
        val providerId = activeProgressProviderId ?: return false
        return trackingProgressProviders
            .provider(providerId)
            ?.isHiddenFromProgress(contentId)
            ?: false
    }

    override fun hasActiveTrackingProgressProvider(): Boolean =
        activeProgressProviderId != null

    override fun activeProviderOwnsCompletedHistoryProjection(): Boolean =
        activeProgressProviderId
            ?.let(trackingProgressProviders::provider)
            ?.ownsCompletedHistoryProjection == true

    override fun activeProviderContinueWatchingCutoffEpochMs(
        daysCap: Int,
        nowEpochMs: Long
    ): Long? {
        return activeProgressProviderId
            ?.let(trackingProgressProviders::provider)
            ?.continueWatchingCutoffEpochMs(daysCap, nowEpochMs)
    }

    override fun shouldUseAsNextUpSeed(
        progress: WatchProgress,
        nowEpochMs: Long
    ): Boolean {
        return activeProgressProviderId
            ?.let(trackingProgressProviders::provider)
            ?.shouldUseAsNextUpSeed(progress, nowEpochMs)
            ?: progress.isCompleted()
    }

    override suspend fun normalizeParentContentId(
        parentContentId: String,
        videoId: String?
    ): String {
        val provider =
            activeProgressProviderId
                ?.let(trackingProgressProviders::provider)
                ?: activeProgressProvider()

        return provider
            ?.normalizeParentContentId(parentContentId, videoId)
            ?: parentContentId
    }

    private suspend fun invalidateSeriesWatchedBadge(
        contentIds: Set<String>
    ) {
        if (contentIds.isEmpty()) return

        val siblings = try {
            activeProgressProvider()
                ?.showIdSiblings()
                .orEmpty()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            emptyMap()
        }

        val aliases = linkedSetOf<String>()
        val queue = ArrayDeque<String>()

        fun enqueue(raw: String?) {
            val id = raw?.trim()?.takeIf(String::isNotBlank) ?: return

            fun add(candidate: String) {
                if (aliases.add(candidate)) {
                    queue.addLast(candidate)
                }
            }

            add(id)

            if (id.startsWith("imdb:", ignoreCase = true)) {
                id.substringAfter(':')
                    .takeIf(String::isNotBlank)
                    ?.let(::add)
            } else if (id.startsWith("tt", ignoreCase = true)) {
                add("imdb:$id")
            }
        }

        contentIds.forEach(::enqueue)

        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            siblings[id]
                .orEmpty()
                .asSequence()
                .filter { it != "__ambiguous__" }
                .forEach(::enqueue)
        }

        watchedSeriesStateHolder.invalidate(aliases)
    }

    private fun progressKey(progress: WatchProgress): String {
        return if (progress.season != null && progress.episode != null) {
            "${progress.contentId}_s${progress.season}e${progress.episode}"
        } else {
            progress.contentId
        }
    }

    private suspend fun resolveRemoteDeleteKeys(
        contentId: String,
        season: Int?,
        episode: Int?
    ): List<String> {
        val rawEntries = watchProgressPreferences.getAllRawEntries()
        val keys = if (season != null && episode != null) {
            listOf("${contentId}_s${season}e${episode}", contentId)
        } else {
            val matchingLocalKeys = rawEntries
                .keys
                .filter { key ->
                    key == contentId || key.startsWith("${contentId}_")
                }
            matchingLocalKeys + contentId
        }
        val resolvedKeys = keys
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return resolvedKeys
    }

}

private fun rewatchSeedContentKey(
    contentId: String
): String =
    contentId.trim().lowercase()

internal fun isPlayerRewatchNextUpDismissal(
    playerSeed: WatchProgress?,
    contentId: String,
    seedSeason: Int,
    seedEpisode: Int,
    nextSeason: Int,
    nextEpisode: Int,
    nextEpisodeWasAlreadyWatched: Boolean,
    nowEpochMs: Long,
    maxAgeMs: Long
): Boolean {
    val seed = playerSeed ?: return false

    if (
        !seed.contentId.equals(
            contentId,
            ignoreCase = true
        )
    ) {
        return false
    }

    if (
        seed.season != seedSeason ||
        seed.episode != seedEpisode
    ) {
        return false
    }

    if (
        !seed.contentType.equals(
            "series",
            ignoreCase = true
        ) &&
        !seed.contentType.equals(
            "tv",
            ignoreCase = true
        )
    ) {
        return false
    }

    if (!seed.isCompleted()) {
        return false
    }

    val seedAgeMs =
        nowEpochMs - seed.lastWatched

    if (seedAgeMs !in 0L..maxAgeMs) {
        return false
    }

    val successorIsLater =
        nextSeason > seedSeason ||
            (
                nextSeason == seedSeason &&
                    nextEpisode > seedEpisode
                )

    if (!successorIsLater) {
        return false
    }

    /*
     * This is the critical discriminator:
     *
     * normal first-run E6 -> E7:
     *     E7 is not watched
     *
     * completed-series replay E6 -> E7:
     *     E7 was already watched before this replay
     */
    return nextEpisodeWasAlreadyWatched
}

internal fun mergeProviderNextUpSeedsWithPlayerCompletions(
    providerSeeds: List<WatchProgress>,
    playerCompletionSeeds: Collection<WatchProgress>,
    nowEpochMs: Long,
    maxAgeMs: Long
): List<WatchProgress> {
    if (
        providerSeeds.isEmpty() ||
        playerCompletionSeeds.isEmpty()
    ) {
        return providerSeeds
    }

    val recentPlayerSeedByContent =
        playerCompletionSeeds
            .asSequence()
            .filter { progress ->
                (
                    progress.contentType.equals(
                        "series",
                        ignoreCase = true
                    ) ||
                        progress.contentType.equals(
                            "tv",
                            ignoreCase = true
                        )
                    ) &&
                    progress.season != null &&
                    progress.episode != null &&
                    progress.season != 0 &&
                    progress.isCompleted()
            }
            .filter { progress ->
                val ageMs =
                    nowEpochMs -
                        progress.lastWatched
                ageMs in 0L..maxAgeMs
            }
            .groupBy { progress ->
                rewatchSeedContentKey(
                    progress.contentId
                )
            }
            .mapValues { (_, candidates) ->
                candidates.maxWithOrNull(
                    compareBy<WatchProgress>(
                        WatchProgress::lastWatched
                    )
                        .thenBy {
                            it.season ?: -1
                        }
                        .thenBy {
                            it.episode ?: -1
                        }
                )!!
            }

    /*
     * Deliberately map over providerSeeds instead of appending
     * Player-only shows. A dropped/dismissed/hidden show remains
     * excluded exactly as the provider requested.
     */
    return providerSeeds
        .map { providerSeed ->
            val playerSeed =
                recentPlayerSeedByContent[
                    rewatchSeedContentKey(
                        providerSeed.contentId
                    )
                ]

            if (
                playerSeed != null &&
                playerSeed.lastWatched >=
                    providerSeed.lastWatched
            ) {
                playerSeed
            } else {
                providerSeed
            }
        }
        .sortedByDescending(
            WatchProgress::lastWatched
        )
}


internal fun suppressStaleSimklPlaybackAlreadyWatched(
    providerId: TrackingProviderId,
    providerEntries: List<WatchProgress>,
    watchedItems: List<WatchedItem>
): List<WatchProgress> {
    if (providerId != TrackingProviderId.SIMKL) {
        return providerEntries
    }

    val latestWatchedAtByEpisode =
        watchedItems
            .asSequence()
            .filter { item ->
                item.season != null &&
                    item.episode != null
            }
            .groupBy { item ->
                Triple(
                    item.contentId
                        .trim()
                        .lowercase(),
                    requireNotNull(item.season),
                    requireNotNull(item.episode)
                )
            }
            .mapValues { (_, items) ->
                items.maxOf(WatchedItem::watchedAt)
            }

    if (latestWatchedAtByEpisode.isEmpty()) {
        return providerEntries
    }

    return providerEntries.filterNot { progress ->
        /*
         * Scope this repair strictly to remote Simkl playback.
         *
         * Do not filter:
         * - local progress
         * - Simkl durable progress
         * - Trakt progress
         * - completed history seeds
         */
        if (
            progress.source !=
                WatchProgress.SOURCE_SIMKL_PLAYBACK ||
            progress.isCompleted()
        ) {
            return@filterNot false
        }

        val season =
            progress.season
                ?: return@filterNot false
        val episode =
            progress.episode
                ?: return@filterNot false

        val watchedAt =
            latestWatchedAtByEpisode[
                Triple(
                    progress.contentId
                        .trim()
                        .lowercase(),
                    season,
                    episode
                )
            ] ?: return@filterNot false

        /*
         * Completion at or after the playback timestamp means this remote
         * playback session predates the completed state and is stale.
         *
         * A new replay has a later playback timestamp and survives.
         */
        watchedAt >= progress.lastWatched
    }
}


internal suspend fun runProtectedNextUpDismissal(
    isPlayerRewatch: Boolean,
    persistNormalDismissal: suspend () -> Unit,
    dismissThroughProvider: suspend () -> Unit
): Boolean {
    if (isPlayerRewatch) {
        return true
    }

    persistNormalDismissal()
    dismissThroughProvider()
    return false
}


internal suspend fun dismissNextUpWithProvider(
    provider: TrackingProgressProvider?,
    contentId: String,
    season: Int?,
    episode: Int?
): Boolean {
    provider ?: return false
    provider.dismissNextUp(
        contentId = contentId,
        season = season,
        episode = episode
    )
    return true
}
