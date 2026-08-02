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
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
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
            metadataState
        ) { providerItems, localItems, metadataMap ->
            hydrateMetadata(providerItems)

            val localByKey = localItems.associateBy(::progressProjectionKey)
            mergeProgressProjectionWithRetainedLocal(
                providerEntries = providerItems,
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
                    provider.episodeProgress(contentId)
                        .map { progress -> progress[season to episode] }
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
                    provider.nextUpSeeds
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
                                progress.contentId.equals(contentId, ignoreCase = true) &&
                                    progress.season != null &&
                                    progress.episode != null
                            }
                        }
                    ) { providerMap, liveEpisodes ->
                        providerMap.toMutableMap().apply {
                            liveEpisodes.forEach { progress ->
                                val season = progress.season ?: return@forEach
                                val episode = progress.episode ?: return@forEach
                                this[season to episode] = progress
                            }
                        }
                    }.distinctUntilChanged()
                } else {
                    watchProgressPreferences.getAllEpisodeProgress(contentId)
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
                        val completedIds = mutableSetOf<String>()
                        val replayingIds = mutableSetOf<String>()

                        progressList.forEach { progress ->
                            if (progress.isCompleted()) {
                                completedIds += progress.contentId
                            } else if (
                                progress.position > 0L ||
                                progress.progressPercent?.let { it > 0f } == true
                            ) {
                                replayingIds += progress.contentId
                            }
                        }

                        val watchedItemIds = watchedItems
                            .filter { item ->
                                item.season == null && item.episode == null
                            }
                            .map(WatchedItem::contentId)
                            .toSet()

                        (completedIds + watchedItemIds) - replayingIds
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
                        val hasStartedReplay = progressEntry?.let { entry ->
                            !entry.isCompleted() &&
                                (
                                    entry.position > 0L ||
                                        entry.progressPercent?.let { it > 0f } == true
                                    )
                        } == true

                        if (hasStartedReplay) {
                            false
                        } else {
                            progressEntry?.isCompleted() == true || itemWatched
                        }
                    }
                }
            }
            .distinctUntilChanged()
    }
    override suspend fun saveProgress(
        progress: WatchProgress,
        syncRemote: Boolean
    ) {
        val provider = activeProgressProvider()

        // Always retain a durable local copy. The selected provider receives
        // only its own optimistic projection; no write is broadcast.
        provider?.applyOptimisticProgress(
            progress = progress,
            quiet = false
        )
        watchProgressPreferences.saveProgress(progress)

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
                    season = progress.season,
                    episode = progress.episode
                )
            }
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

            writer.removeFromHistory(
                profileId = profileManager.activeProfileId.value,
                items = listOf(media)
            )
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
        progress: WatchProgress
    ) {
        val provider = activeProgressProvider()
        val now = System.currentTimeMillis()
        val duration = progress.duration.takeIf { it > 0L } ?: 1L

        val completed = progress.copy(
            position = duration,
            duration = duration,
            progressPercent = 100f,
            lastWatched = now
        )

        if (provider != null) {
            provider.applyOptimisticProgress(
                progress = completed,
                quiet = false
            )

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
                episodeTitle = completed.episodeTitle
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
                    season = completed.season,
                    episode = completed.episode
                )
                throw it
            }
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

        if (provider == null) {
            triggerRemoteSync()
            triggerWatchedItemsSync()
        }
    }
    override suspend fun clearAll() {
        activeProgressProvider()?.clearOptimistic()
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

    override suspend fun saveProgressBatch(progressList: List<WatchProgress>, syncRemote: Boolean) {
        progressList.forEach { saveProgress(it, syncRemote) }
    }

    override suspend fun markAsCompletedBatch(progressList: List<WatchProgress>) {
        progressList.forEach { markAsCompleted(it) }
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
