package com.nuvio.tv.ui.screens.home

import android.util.Log
import com.nuvio.tv.data.local.CachedNextUpItem
import com.nuvio.tv.data.local.CachedInProgressItem
import com.nuvio.tv.data.local.InProgressEnrichmentEntry
import com.nuvio.tv.core.homechannel.HomeScreenChannelWorker
import com.nuvio.tv.core.homechannel.HomeScreenChannelManager
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val CW_MAX_RECENT_PROGRESS_ITEMS = 300
private const val CW_MAX_NEXT_UP_LOOKUPS = 24
private const val CW_MAX_NEXT_UP_CONCURRENCY = 4


private data class ContinueWatchingSettingsSnapshot(
    val items: List<WatchProgress>,
    val daysCap: Int,
    val dismissedNextUp: Set<String>,
    val showUnairedNextUp: Boolean,
    val profileId: Int = 0
)

private data class NextUpTmdbData(
    val thumbnail: String?,
    val backdrop: String?,
    val poster: String?,
    val logo: String?,
    val name: String?,
    val episodeTitle: String?,
    val airDate: String?,
    val overview: String?,
    val showDescription: String?
)

private data class NextUpResolution(
    val episode: Video,
    val lastWatched: Long
)

internal fun HomeViewModel.loadContinueWatchingPipeline() {
    cwPipelineJob?.cancel()
    cwPipelineJob = viewModelScope.launch {
        combine(
            watchProgressRepository.allProgress,
            traktSettingsDataStore.continueWatchingDaysCap,
            traktSettingsDataStore.dismissedNextUpKeys,
            traktSettingsDataStore.showUnairedNextUp,
            profileManager.activeProfileId
        ) { items, daysCap, dismissedNextUp, showUnairedNextUp, profileId ->
            ContinueWatchingSettingsSnapshot(
                items = items,
                daysCap = daysCap,
                dismissedNextUp = dismissedNextUp,
                showUnairedNextUp = showUnairedNextUp,
                profileId = profileId
            )
        }.debounce(750L).collectLatest { snapshot ->
            val activeProfileId = snapshot.profileId
            val cycleStartMs = System.currentTimeMillis()
            android.util.Log.d("CW_TIMING", "▶ pipeline cycle start profileId=$activeProfileId items=${snapshot.items.size}")
            // Guard: if the profile changed during debounce, skip this stale emission
            if (activeProfileId != profileManager.activeProfileId.value) {
                android.util.Log.d("CW_TIMING", "⚠ stale emission skipped (active=${profileManager.activeProfileId.value})")
                return@collectLatest
            }
            // Only clear if empty — don't wipe cache pre-render before we have live data
            if (_uiState.value.continueWatchingItems.isEmpty()) {
                _uiState.update { it.copy(continueWatchingItems = emptyList()) }
            }
            val items = snapshot.items
            val daysCap = snapshot.daysCap
            val dismissedNextUp = snapshot.dismissedNextUp
            val showUnairedNextUp = snapshot.showUnairedNextUp
            val cutoffMs = if (daysCap == TraktSettingsDataStore.CONTINUE_WATCHING_DAYS_CAP_ALL) {
                null
            } else {
                val windowMs = daysCap.toLong() * 24L * 60L * 60L * 1000L
                System.currentTimeMillis() - windowMs
            }
            val recentItems = items
                .asSequence()
                .filter { progress -> cutoffMs == null || progress.lastWatched >= cutoffMs }
                .sortedByDescending { it.lastWatched }
                .take(CW_MAX_RECENT_PROGRESS_ITEMS)
                .toList()

            Log.d("HomeViewModel", "allProgress emitted=${items.size} recentWindow=${recentItems.size}")

            // Load both cache files in parallel to avoid sequential blocking
            val (cachedNextUpRaw, cachedInProgressRaw) = coroutineScope {
                val nextUpDeferred = async(Dispatchers.IO) {
                    runCatching { cwEnrichmentCache.getNextUpSnapshot(activeProfileId) }.getOrDefault(emptyList())
                }
                val inProgressDeferred = async(Dispatchers.IO) {
                    runCatching { cwEnrichmentCache.getInProgressSnapshot(activeProfileId) }.getOrDefault(emptyList())
                }
                nextUpDeferred.await() to inProgressDeferred.await()
            }
            android.util.Log.d("CW_TIMING", "📦 cache loaded: nextUp=${cachedNextUpRaw.size} inProgress=${cachedInProgressRaw.size} elapsed=${System.currentTimeMillis()-cycleStartMs}ms")
            val existingInProgressMap = _uiState.value.continueWatchingItems
                .filterIsInstance<ContinueWatchingItem.InProgress>()
                .associateBy { it.progress.contentId + "_" + it.progress.season + "_" + it.progress.episode }
            val inProgressOnly = buildList {
                deduplicateInProgress(
                    recentItems.filter { shouldTreatAsInProgressForContinueWatching(it) }
                ).forEach { progress ->
                    val key = progress.contentId + "_" + progress.season + "_" + progress.episode
                    val existing = existingInProgressMap[key]
                    // Patch blank name from enrichment cache so title shows immediately
                    val cachedForName = cachedInProgressRaw.firstOrNull { it.contentId == progress.contentId }
                    val progressWithName = if (progress.name.isBlank() && cachedForName?.name?.isNotBlank() == true) {
                        progress.copy(name = cachedForName.name)
                    } else progress
                    add(
                        ContinueWatchingItem.InProgress(
                            progress = progressWithName,
                            episodeDescription = existing?.episodeDescription,
                            episodeThumbnail = existing?.episodeThumbnail,
                            episodeImdbRating = existing?.episodeImdbRating,
                            genres = existing?.genres ?: emptyList(),
                            releaseInfo = existing?.releaseInfo
                        )
                    )
                }
            }

            Log.d("HomeViewModel", "inProgressOnly: ${inProgressOnly.size} items after filter+dedup")

            // Load profile-scoped cache instantly for optimistic UI
            // cache reads moved above

            val cachedNextUp = cachedNextUpRaw
                .filter { cached -> inProgressOnly.none { it.progress.contentId == cached.contentId } }
                .map { cached ->
                    ContinueWatchingItem.NextUp(NextUpInfo(
                        contentId = cached.contentId, contentType = cached.contentType,
                        name = cached.name, poster = cached.poster, backdrop = cached.backdrop,
                        logo = cached.logo, videoId = cached.videoId, season = cached.season,
                        episode = cached.episode, episodeTitle = cached.episodeTitle,
                        episodeDescription = cached.episodeDescription, thumbnail = cached.thumbnail,
                        released = cached.released, hasAired = cached.hasAired,
                        airDateLabel = cached.airDateLabel, lastWatched = cached.lastWatched,
                        imdbRating = cached.imdbRating, genres = cached.genres,
                        releaseInfo = cached.releaseInfo
                    ))
                }

            val cachedInProgressMap = cachedInProgressRaw.associateBy { it.contentId + "_" + it.season + "_" + it.episode }
            // Fallback: if live items haven't arrived yet but cache has data, use cache directly
            val effectiveInProgressOnly = if (inProgressOnly.isEmpty() && cachedInProgressRaw.isNotEmpty() && items.isEmpty()) {
                android.util.Log.d("CW_TIMING", "⚡ using cached inProgress as fallback (Trakt not loaded yet)")
                cachedInProgressRaw.map { cached ->
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
            } else inProgressOnly
            val inProgressWithCachedEnrichment = effectiveInProgressOnly.map { item ->
                val key = item.progress.contentId + "_" + item.progress.season + "_" + item.progress.episode
                val cached = cachedInProgressMap[key]
                if (cached != null) {
                    item.copy(
                        episodeDescription = cached.episodeDescription?.takeIf {
                            it.isNotBlank() &&
                            !it.contains("rate limit", ignoreCase = true) &&
                            !it.contains("too many requests", ignoreCase = true)
                        },
                        episodeThumbnail = cached.episodeThumbnail,
                        episodeImdbRating = cached.episodeImdbRating,
                        genres = cached.genres,
                        releaseInfo = cached.releaseInfo,
                        progress = cached.logo?.takeIf { it.isNotBlank() && item.progress.logo.isNullOrBlank() }
                            ?.let { logo -> item.progress.copy(logo = logo) } ?: item.progress
                    )
                } else item
            }

            android.util.Log.d("CW_TIMING", "🖼 rendering optimistic UI: inProgress=${inProgressWithCachedEnrichment.size} cachedNextUp=${cachedNextUp.size} elapsed=${System.currentTimeMillis()-cycleStartMs}ms")
            _uiState.update { state ->
                val immediateItems = if (inProgressWithCachedEnrichment.isNotEmpty() || cachedNextUp.isNotEmpty()) {
                    inProgressWithCachedEnrichment + cachedNextUp
                } else {
                    mergeContinueWatchingItems(
                        inProgressItems = inProgressOnly,
                        nextUpItems = emptyList()
                    )
                }
                if (state.continueWatchingItems == immediateItems) {
                    state
                } else {
                    state.copy(continueWatchingItems = immediateItems)
                }
            }

            // Then enrich Next Up and item details in background.
            // Collect pre-computed seeds — no per-series Trakt calls
            android.util.Log.d("CW_TIMING", "🌱 seeds collected, starting enrichment elapsed=${System.currentTimeMillis()-cycleStartMs}ms")
            val nextUpSeeds = emptyList<com.nuvio.tv.domain.model.WatchProgress>()
            android.util.Log.d("CW_TIMING", "🌱 nextUpSeeds=${nextUpSeeds.size} elapsed=${System.currentTimeMillis()-cycleStartMs}ms")
            cwEnrichmentJob?.cancel()
            cwEnrichmentJob = viewModelScope.launch {
                enrichContinueWatchingProgressively(
                    allProgress = recentItems,
                    nextUpSeeds = nextUpSeeds,
                    inProgressItems = inProgressWithCachedEnrichment,
                    dismissedNextUp = dismissedNextUp,
                    showUnairedNextUp = showUnairedNextUp
                )
                enrichInProgressEpisodeDetailsProgressively(inProgressWithCachedEnrichment, activeProfileId)
            }
        }
    }
}

private fun deduplicateInProgress(items: List<WatchProgress>): List<WatchProgress> {
    val (series, nonSeries) = items.partition { isSeriesTypeCW(it.contentType) }
    val latestPerShow = series
        .sortedByDescending { it.lastWatched }
        .distinctBy { it.contentId }
    return (nonSeries + latestPerShow).sortedByDescending { it.lastWatched }
}

private fun shouldTreatAsInProgressForContinueWatching(progress: WatchProgress): Boolean {
    if (progress.isInProgress()) return true
    if (progress.isCompleted()) return false

    // Rewatch edge case: a started replay can be below the default 2% "in progress"
    // threshold, but should still suppress Next Up and appear as resume.
    val hasStartedPlayback = progress.position > 0L || progress.progressPercent?.let { it > 0f } == true
    return hasStartedPlayback &&
        progress.source != WatchProgress.SOURCE_TRAKT_HISTORY &&
        progress.source != WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS
}

private fun shouldUseAsCompletedSeed(progress: WatchProgress): Boolean {
    if (!progress.isCompleted()) return false
    if (progress.source != WatchProgress.SOURCE_TRAKT_PLAYBACK) return true
    val explicitPercent = progress.progressPercent ?: return false
    return explicitPercent >= 95f
}

private fun shouldTreatAsActiveInProgressForNextUpSuppression(
    progress: WatchProgress,
    latestCompletedAt: Long?
): Boolean {
    if (!shouldTreatAsInProgressForContinueWatching(progress)) return false
    if (latestCompletedAt == null || latestCompletedAt == Long.MIN_VALUE) return true
    return progress.lastWatched >= latestCompletedAt
}

private suspend fun HomeViewModel.resolveCurrentEpisodeDescription(
    progress: WatchProgress,
    metaCache: MutableMap<String, Meta?>
): String? {
    val meta = resolveMetaForProgress(progress, metaCache) ?: return null
    if (isSeriesTypeCW(progress.contentType)) {
        val video = resolveVideoForProgress(progress, meta)
        val episodeOverview = video?.overview?.takeIf { it.isNotBlank() }
        if (episodeOverview != null) return episodeOverview
    }
    // For movies (or series with no per-episode overview), fall back to show/movie description
    return meta.description?.takeIf { it.isNotBlank() }
}

private suspend fun HomeViewModel.resolveCurrentEpisodeThumbnail(
    progress: WatchProgress,
    metaCache: MutableMap<String, Meta?>
): String? {
    if (!isSeriesTypeCW(progress.contentType)) return null
    val meta = resolveMetaForProgress(progress, metaCache) ?: return null
    val video = resolveVideoForProgress(progress, meta) ?: return null
    return video.thumbnail?.takeIf { it.isNotBlank() }
}

private suspend fun HomeViewModel.resolveCurrentEpisodeImdbRating(
    progress: WatchProgress,
    metaCache: MutableMap<String, Meta?>
): Float? {
    val meta = resolveMetaForProgress(progress, metaCache) ?: return null
    return meta.imdbRating
}

private suspend fun HomeViewModel.resolveCurrentGenres(
    progress: WatchProgress,
    metaCache: MutableMap<String, Meta?>
): List<String> {
    val meta = resolveMetaForProgress(progress, metaCache) ?: return emptyList()
    return meta.genres.take(3)
}

private suspend fun HomeViewModel.resolveCurrentReleaseInfo(
    progress: WatchProgress,
    metaCache: MutableMap<String, Meta?>
): String? {
    val meta = resolveMetaForProgress(progress, metaCache) ?: return null
    return meta.releaseInfo?.takeIf { it.isNotBlank() }
}

private suspend fun HomeViewModel.resolveCurrentLogo(
    progress: WatchProgress,
    metaCache: MutableMap<String, Meta?>
): String? {
    if (!progress.logo.isNullOrBlank()) return progress.logo
    val meta = resolveMetaForProgress(progress, metaCache) ?: return null
    return meta.logo?.takeIf { it.isNotBlank() }
}

private fun resolveVideoForProgress(progress: WatchProgress, meta: Meta): Video? {
    if (!isSeriesTypeCW(progress.contentType)) return null
    val videos = meta.videos.filter { it.season != null && it.episode != null && it.season != 0 }
    if (videos.isEmpty()) return null

    progress.videoId.takeIf { it.isNotBlank() }?.let { videoId ->
        videos.firstOrNull { it.id == videoId }?.let { return it }
    }

    val season = progress.season
    val episode = progress.episode
    if (season != null && episode != null) {
        videos.firstOrNull { it.season == season && it.episode == episode }?.let { return it }
    }

    return null
}

private suspend fun HomeViewModel.enrichContinueWatchingProgressively(
    allProgress: List<WatchProgress>,
    nextUpSeeds: List<WatchProgress>,
    inProgressItems: List<ContinueWatchingItem.InProgress>,
    dismissedNextUp: Set<String>,
    showUnairedNextUp: Boolean
) = coroutineScope {
    val latestCompletedByContent = allProgress
        .asSequence()
        .filter { isSeriesTypeCW(it.contentType) }
        .filter { it.contentId.isNotBlank() }
        .filter { shouldUseAsCompletedSeed(it) }
        .groupBy { it.contentId }
        .mapValues { (_, items) ->
            items.maxOfOrNull { it.lastWatched } ?: Long.MIN_VALUE
        }

    val inProgressIds = inProgressItems
        .map { it.progress }
        .filter { progress ->
            shouldTreatAsActiveInProgressForNextUpSuppression(
                progress = progress,
                latestCompletedAt = latestCompletedByContent[progress.contentId]
            )
        }
        .map { it.contentId }
        .toSet()

    // Use pre-computed seeds (no per-series Trakt API calls) with allProgress as fallback
    val latestCompletedBySeries = (if (nextUpSeeds.isNotEmpty()) nextUpSeeds else allProgress
        .filter { progress ->
            isSeriesTypeCW(progress.contentType) &&
                progress.season != null &&
                progress.episode != null &&
                progress.season != 0 &&
                shouldUseAsCompletedSeed(progress)
        }
        .groupBy { it.contentId }
        .mapNotNull { (_, items) ->
            items.maxWithOrNull(
                compareBy<WatchProgress>(
                    { it.lastWatched },
                    { it.season ?: -1 },
                    { it.episode ?: -1 }
                )
            )
        })
        .filter { it.contentId !in inProgressIds }
        .filter { progress -> nextUpDismissKey(progress.contentId) !in dismissedNextUp }
        .sortedByDescending { it.lastWatched }
        .take(CW_MAX_NEXT_UP_LOOKUPS)

    if (latestCompletedBySeries.isEmpty()) {
        _uiState.update { state ->
            val mergedItems = mergeContinueWatchingItems(
                inProgressItems = inProgressItems,
                nextUpItems = emptyList()
            )
            if (state.continueWatchingItems == mergedItems) {
                state
            } else {
                state.copy(continueWatchingItems = mergedItems)
            }
        }
        return@coroutineScope
    }

    val lookupSemaphore = Semaphore(CW_MAX_NEXT_UP_CONCURRENCY)
    val mergeMutex = Mutex()
    val nextUpByContent = linkedMapOf<String, ContinueWatchingItem.NextUp>()
    val metaCache = cwMetaCache
    var lastEmittedNextUpCount = 0

    val jobs = latestCompletedBySeries.map { progress ->
        launch(Dispatchers.IO) {
            lookupSemaphore.withPermit {
                val nextUp = buildNextUpItem(
                    progress = progress,
                    metaCache = metaCache,
                    showUnairedNextUp = showUnairedNextUp
                ) ?: return@withPermit
                mergeMutex.withLock {
                    nextUpByContent[progress.contentId] = nextUp
                    if (nextUpByContent.size - lastEmittedNextUpCount >= 1) {
                        val nextUpItems = nextUpByContent.values.toList()
                        _uiState.update {
                            val mergedItems = mergeContinueWatchingItems(
                                inProgressItems = inProgressItems,
                                nextUpItems = nextUpItems
                            )
                            if (it.continueWatchingItems == mergedItems) {
                                it
                            } else {
                                it.copy(continueWatchingItems = mergedItems)
                            }
                        }
                        lastEmittedNextUpCount = nextUpByContent.size
                    }
                }
            }
        }
    }
    jobs.joinAll()

    mergeMutex.withLock {
        if (nextUpByContent.size != lastEmittedNextUpCount) {
            val nextUpItems = nextUpByContent.values.toList()
            _uiState.update {
                val mergedItems = mergeContinueWatchingItems(
                    inProgressItems = inProgressItems,
                    nextUpItems = nextUpItems
                )
                if (it.continueWatchingItems == mergedItems) {
                    it
                } else {
                    it.copy(continueWatchingItems = mergedItems)
                }
            }
        }
    }
    // Channel refresh happens after enrichment completes (see enrichInProgressEpisodeDetailsProgressively)
}

private suspend fun HomeViewModel.enrichInProgressEpisodeDetailsProgressively(
    inProgressItems: List<ContinueWatchingItem.InProgress>,
    activeProfileId: Int = profileManager.activeProfileId.value
) = coroutineScope {
    if (inProgressItems.isEmpty()) return@coroutineScope

    val metaCache = cwMetaCache
    val enrichedByProgress = linkedMapOf<WatchProgress, ContinueWatchingItem.InProgress>()
    var lastAppliedCount = 0

    for (item in inProgressItems) {
        val tmdbSettingsSnapshot = currentTmdbSettings

        // Resolve addon meta fields
        val description = resolveCurrentEpisodeDescription(item.progress, metaCache)
        val thumbnail = resolveCurrentEpisodeThumbnail(item.progress, metaCache)
        val imdbRating = resolveCurrentEpisodeImdbRating(item.progress, metaCache)
        val genres = resolveCurrentGenres(item.progress, metaCache)
        val releaseInfo = resolveCurrentReleaseInfo(item.progress, metaCache)
        val logo = resolveCurrentLogo(item.progress, metaCache)

        // Resolve TMDB ID once — reused by both show and episode fetches below
        val meta = resolveMetaForProgress(item.progress, metaCache)
        val candidates = buildList {
            add(item.progress.contentId)
            meta?.id?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.map { it.trim() }.filter { it.isNotBlank() }.distinct()

        val resolvedTmdbId: String? = if (!tmdbSettingsSnapshot.enabled) null
        else runCatching {
            candidates.firstNotNullOfOrNull { id ->
                tmdbService.ensureTmdbId(id, item.progress.contentType)
            }
        }.getOrNull()

        // Show-level enrichment — check enrichmentCache first
        val tmdbShowEnrichment: com.nuvio.tv.core.tmdb.TmdbEnrichment? = if (!tmdbSettingsSnapshot.enabled) null
        else runCatching {
            candidates.firstNotNullOfOrNull { enrichmentCache[it] }
                ?: resolvedTmdbId?.let { id ->
                    tmdbMetadataService.fetchEnrichment(
                        tmdbId = id,
                        contentType = if (isSeriesTypeCW(item.progress.contentType))
                            com.nuvio.tv.domain.model.ContentType.SERIES
                        else
                            com.nuvio.tv.domain.model.ContentType.MOVIE,
                        language = tmdbSettingsSnapshot.language
                    )
                }
        }.getOrNull()

        // Episode overview — reuses resolvedTmdbId, no duplicate lookup
        val tmdbEpisodeOverview: String? = if (!tmdbSettingsSnapshot.enabled ||
            !isSeriesTypeCW(item.progress.contentType)) null
        else runCatching {
            val season = item.progress.season ?: return@runCatching null
            val episode = item.progress.episode ?: return@runCatching null
            resolvedTmdbId?.let { id ->
                tmdbMetadataService.fetchEpisodeEnrichment(
                    tmdbId = id,
                    seasonNumbers = listOf(season),
                    language = tmdbSettingsSnapshot.language
                )[season to episode]?.overview?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()

        val cleanAddonDesc = description?.takeIf {
            it.isNotBlank() &&
            !it.contains("rate limit", ignoreCase = true) &&
            !it.contains("too many requests", ignoreCase = true) &&
            it != item.progress.episodeTitle
        }
        val resolvedDescription = tmdbEpisodeOverview
            ?: cleanAddonDesc
            ?: tmdbShowEnrichment?.description
            ?: item.episodeDescription?.takeIf {
                it.isNotBlank() &&
                !it.contains("rate limit", ignoreCase = true) &&
                !it.contains("too many requests", ignoreCase = true)
            }
        val resolvedImdbRating = imdbRating
            ?: tmdbShowEnrichment?.rating?.toFloat()
            ?: item.episodeImdbRating
        val resolvedGenres = genres.ifEmpty {
            tmdbShowEnrichment?.genres?.take(3)?.takeIf { it.isNotEmpty() } ?: item.genres
        }
        val resolvedReleaseInfo = releaseInfo ?: tmdbShowEnrichment?.releaseInfo ?: item.releaseInfo
        val resolvedLogo = logo
            ?: tmdbShowEnrichment?.logo?.takeIf { it.isNotBlank() }
            ?: item.progress.logo?.takeIf { it.isNotBlank() }

        val enrichedItem = item.copy(
            episodeDescription = resolvedDescription,
            episodeThumbnail = thumbnail,
            episodeImdbRating = resolvedImdbRating,
            genres = resolvedGenres,
            releaseInfo = resolvedReleaseInfo,
            progress = if (resolvedLogo != null && item.progress.logo.isNullOrBlank())
                item.progress.copy(logo = resolvedLogo) else item.progress
        )

        if (enrichedItem != item) {
            enrichedByProgress[item.progress] = enrichedItem
            if (enrichedByProgress.size - lastAppliedCount >= 1) {
                applyInProgressEpisodeDetailEnrichment(enrichedByProgress)
                lastAppliedCount = enrichedByProgress.size
            }
        }
    }

    if (enrichedByProgress.isNotEmpty() && enrichedByProgress.size != lastAppliedCount) {
        applyInProgressEpisodeDetailEnrichment(enrichedByProgress)
    }

    android.util.Log.d("CW_TIMING", "💾 enrichment complete, saving cache for profileId=$activeProfileId")
    // Save NextUp cache for optimistic UI on next launch
    val nextUpSnap = _uiState.value.continueWatchingItems
        .filterIsInstance<ContinueWatchingItem.NextUp>()
        .map { nu -> val info = nu.info
            CachedNextUpItem(
                contentId = info.contentId, contentType = info.contentType, name = info.name,
                poster = info.poster, backdrop = info.backdrop, logo = info.logo,
                videoId = info.videoId, season = info.season, episode = info.episode,
                episodeTitle = info.episodeTitle, episodeDescription = info.episodeDescription,
                thumbnail = info.thumbnail,
                released = info.released, hasAired = info.hasAired, airDateLabel = info.airDateLabel,
                lastWatched = info.lastWatched, imdbRating = info.imdbRating, genres = info.genres,
                releaseInfo = info.releaseInfo, sortTimestamp = info.lastWatched
            )
        }
    if (nextUpSnap.isNotEmpty()) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { cwEnrichmentCache.saveNextUpSnapshot(nextUpSnap, force = true, profileId = activeProfileId) }
        }
    }
    // Save InProgress enrichment cache so thumbnails/descriptions survive ViewModel recreation
    val ipSnap = _uiState.value.continueWatchingItems
        .filterIsInstance<ContinueWatchingItem.InProgress>()
        .filter { it.episodeThumbnail != null || it.episodeDescription != null || it.genres.isNotEmpty() || it.releaseInfo != null || it.episodeImdbRating != null }
        .map { item -> val p = item.progress
            val cleanDesc = item.episodeDescription?.takeIf {
                it.isNotBlank() &&
                !it.contains("rate limit", ignoreCase = true) &&
                !it.contains("too many requests", ignoreCase = true)
            }
            CachedInProgressItem(
                contentId = p.contentId, contentType = p.contentType, name = p.name,
                poster = p.poster, backdrop = p.backdrop, logo = p.logo,
                videoId = p.videoId, season = p.season, episode = p.episode,
                episodeTitle = p.episodeTitle, position = p.position, duration = p.duration,
                lastWatched = p.lastWatched, progressPercent = p.progressPercent,
                episodeThumbnail = item.episodeThumbnail,
                episodeDescription = cleanDesc, episodeImdbRating = item.episodeImdbRating,
                genres = item.genres, releaseInfo = item.releaseInfo
            )
        }
    if (ipSnap.isNotEmpty()) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { cwEnrichmentCache.saveInProgressSnapshot(ipSnap, force = true, profileId = activeProfileId) }
        }
    }
    // Refresh home screen channel after enrichment (logos now available)
    val finalItems = _uiState.value.continueWatchingItems
    if (finalItems.isNotEmpty()) {
        homeScreenChannelManager.refreshFromItems(finalItems)
    }
}

private fun HomeViewModel.applyInProgressEpisodeDetailEnrichment(
    replacements: Map<WatchProgress, ContinueWatchingItem.InProgress>
) {
    if (replacements.isEmpty()) return

    _uiState.update { state ->
        var changed = false
        val updatedItems = state.continueWatchingItems.map { item ->
            if (item is ContinueWatchingItem.InProgress) {
                val replacement = replacements[item.progress]
                if (replacement != null && replacement != item) {
                    changed = true
                    replacement
                } else {
                    item
                }
            } else {
                item
            }
        }

        if (changed) {
            state.copy(continueWatchingItems = updatedItems)
        } else {
            state
        }
    }
}

internal fun mergeContinueWatchingItems(
    inProgressItems: List<ContinueWatchingItem.InProgress>,
    nextUpItems: List<ContinueWatchingItem.NextUp>
): List<ContinueWatchingItem> {
    val inProgressSeriesIds = inProgressItems
        .asSequence()
        .map { it.progress }
        .filter { isSeriesTypeCW(it.contentType) }
        .map { it.contentId }
        .filter { it.isNotBlank() }
        .toSet()

    val filteredNextUpItems = nextUpItems.filter { item ->
        item.info.contentId !in inProgressSeriesIds
    }

    val combined = mutableListOf<Pair<Long, ContinueWatchingItem>>()
    inProgressItems.forEach { combined.add(it.progress.lastWatched to it) }
    filteredNextUpItems.forEach { combined.add(it.info.lastWatched to it) }

    return combined
        .sortedByDescending { it.first }
        .map { it.second }
}

private suspend fun HomeViewModel.buildNextUpItem(
    progress: WatchProgress,
    metaCache: MutableMap<String, Meta?>,
    showUnairedNextUp: Boolean,
    allProgressByContentId: Map<String, List<WatchProgress>> = emptyMap()
): ContinueWatchingItem.NextUp? {
    val meta = resolveMetaForProgress(progress, metaCache) ?: return null
    val nextUp = findNextUpEpisodeFromProgressMap(
        contentId = progress.contentId,
        meta = meta,
        showUnairedNextUp = showUnairedNextUp,
        preloadedProgress = allProgressByContentId[progress.contentId]
    ) ?: findNextUpEpisodeFromLatestProgress(
        progress = progress,
        meta = meta,
        showUnairedNextUp = showUnairedNextUp
    ) ?: return null
    val video = nextUp.episode
    val nextSeason = requireNotNull(video.season)
    val nextEpisodeNumber = requireNotNull(video.episode)

    val existingPoster = meta.poster.normalizeImageUrl()
    val existingBackdrop = meta.backdropUrl.normalizeImageUrl()
    val existingLogo = meta.logo.normalizeImageUrl()
    val existingThumbnail = video.thumbnail.normalizeImageUrl()
    val tmdbData = if (
        existingThumbnail == null ||
        existingBackdrop == null ||
        existingPoster == null ||
        existingLogo == null ||
        video.overview.isNullOrBlank() ||
        video.title.isNullOrBlank()
    ) {
        resolveNextUpTmdbData(
            progress = progress,
            meta = meta,
            season = nextSeason,
            episode = nextEpisodeNumber
        )
    } else {
        null
    }
    val released = video.released?.trim()?.takeIf { it.isNotEmpty() }
        ?: tmdbData?.airDate
    val releaseDate = parseEpisodeReleaseDate(released)
    val todayLocal = LocalDate.now(ZoneId.systemDefault())
    val hasAired = releaseDate?.let { !it.isAfter(todayLocal) } ?: true
    val info = NextUpInfo(
        contentId = progress.contentId,
        contentType = progress.contentType,
        name = tmdbData?.name ?: meta.name,
        poster = existingPoster ?: tmdbData?.poster,
        backdrop = existingBackdrop ?: tmdbData?.backdrop,
        logo = tmdbData?.logo ?: existingLogo,
        videoId = video.id,
        season = nextSeason,
        episode = nextEpisodeNumber,
        episodeTitle = tmdbData?.episodeTitle ?: video.title,
        episodeDescription = tmdbData?.overview ?: video.overview?.takeIf { it.isNotBlank() },
        thumbnail = existingThumbnail ?: tmdbData?.thumbnail,
        released = released,
        hasAired = hasAired,
        airDateLabel = if (hasAired) {
            null
        } else {
            formatEpisodeAirDateLabel(releaseDate)
        },
        lastWatched = nextUp.lastWatched,
        imdbRating = meta.imdbRating,
        genres = meta.genres.take(3),
        releaseInfo = meta.releaseInfo?.takeIf { it.isNotBlank() }
    )
    return ContinueWatchingItem.NextUp(info)
}

private suspend fun HomeViewModel.findNextUpEpisodeFromProgressMap(
    contentId: String,
    meta: Meta,
    showUnairedNextUp: Boolean,
    preloadedProgress: List<WatchProgress>? = null
): NextUpResolution? {
    val episodes = meta.videos
        .filter { it.season != null && it.episode != null && it.season != 0 }
        .sortedWith(compareBy<Video> { it.season }.thenBy { it.episode })
    if (episodes.isEmpty()) return null

    val progressMap: Map<Pair<Int, Int>, WatchProgress> = if (!preloadedProgress.isNullOrEmpty()) {
        preloadedProgress
            .filter { it.season != null && it.episode != null }
            .associateBy { (it.season ?: 0) to (it.episode ?: 0) }
    } else {
        runCatching {
            withTimeoutOrNull(2_500L) {
                watchProgressRepository.getAllEpisodeProgress(contentId)
                    .first { it.isNotEmpty() }
            } ?: watchProgressRepository.getAllEpisodeProgress(contentId).firstOrNull().orEmpty()
        }.getOrElse {
            Log.w(HomeViewModel.TAG, "findNextUpEpisodeFromProgressMap failed for $contentId: ${it.message}")
            emptyMap()
        }
    }
    if (progressMap.isEmpty()) return null

    val watchedEpisodesMap = runCatching {
        watchedItemsPreferences.getWatchedEpisodesWithTimestamps(contentId).first()
    }.getOrElse { emptyMap() }
    val watchedEpisodes = watchedEpisodesMap.keys

    val completedProgress = progressMap.values
        .filter {
            val season = it.season
            val episode = it.episode
            season != null &&
                episode != null &&
                season != 0 &&
                it.isCompleted()
        }

    val latestWatchedSeason = watchedEpisodes.maxByOrNull { (s, e) -> s * 10000 + e }
    val completedSeasonEpisode = completedProgress.maxWithOrNull(
        compareBy<WatchProgress>({ it.season ?: -1 }, { it.episode ?: -1 }, { it.lastWatched })
    )

    val furthestSeason: Int
    val furthestEpisode: Int
    val furthestLastWatched: Long

    if (completedSeasonEpisode != null && latestWatchedSeason != null) {
        val progKey = (completedSeasonEpisode.season ?: 0) * 10000 + (completedSeasonEpisode.episode ?: 0)
        val watchedKey = latestWatchedSeason.first * 10000 + latestWatchedSeason.second
        if (watchedKey > progKey) {
            furthestSeason = latestWatchedSeason.first
            furthestEpisode = latestWatchedSeason.second
            furthestLastWatched = watchedEpisodesMap[latestWatchedSeason] ?: completedSeasonEpisode.lastWatched
        } else {
            furthestSeason = completedSeasonEpisode.season ?: return null
            furthestEpisode = completedSeasonEpisode.episode ?: return null
            furthestLastWatched = maxOf(
                completedSeasonEpisode.lastWatched,
                watchedEpisodesMap.values.maxOrNull() ?: 0L
            )
        }
    } else if (latestWatchedSeason != null) {
        furthestSeason = latestWatchedSeason.first
        furthestEpisode = latestWatchedSeason.second
        furthestLastWatched = watchedEpisodesMap[latestWatchedSeason] ?: System.currentTimeMillis()
    } else if (completedSeasonEpisode != null) {
        furthestSeason = completedSeasonEpisode.season ?: return null
        furthestEpisode = completedSeasonEpisode.episode ?: return null
        furthestLastWatched = completedSeasonEpisode.lastWatched
    } else {
        return null
    }

    val furthestIndex = episodes.indexOfFirst {
        it.season == furthestSeason && it.episode == furthestEpisode
    }
    if (furthestIndex < 0) return null

    val nextEpisode = episodes
        .drop(furthestIndex + 1)
        .firstOrNull { candidate ->
            val season = candidate.season ?: return@firstOrNull false
            val episode = candidate.episode ?: return@firstOrNull false
            val candidateProgress = progressMap[season to episode]
            candidateProgress?.isCompleted() != true &&
                (season to episode) !in watchedEpisodes
        }
        ?: return null

    val nextSeason = nextEpisode.season ?: return null
    val nextEpisodeNumber = nextEpisode.episode ?: return null
    val nextEpisodeProgress = progressMap[nextSeason to nextEpisodeNumber]
    if (nextEpisodeProgress != null && shouldTreatAsInProgressForContinueWatching(nextEpisodeProgress)) {
        return null
    }
    if (!shouldIncludeNextUpEpisode(nextEpisode, showUnairedNextUp)) return null

    val lastWatched = maxOf(
        completedProgress.maxOfOrNull { it.lastWatched } ?: 0L,
        furthestLastWatched,
        watchedEpisodesMap.values.maxOrNull() ?: 0L
    )
    return NextUpResolution(
        episode = nextEpisode,
        lastWatched = lastWatched
    )
}

private suspend fun HomeViewModel.findNextUpEpisodeFromLatestProgress(
    progress: WatchProgress,
    meta: Meta,
    showUnairedNextUp: Boolean
): NextUpResolution? {
    val episodes = meta.videos
        .filter { it.season != null && it.episode != null && it.season != 0 }
        .sortedWith(compareBy<Video> { it.season }.thenBy { it.episode })
    if (episodes.isEmpty()) return null

    val currentSeason = progress.season ?: return null
    val currentEpisode = progress.episode ?: return null

    val watchedEpisodesMap = runCatching {
        watchedItemsPreferences.getWatchedEpisodesWithTimestamps(progress.contentId).first()
    }.getOrElse { emptyMap() }
    val watchedEpisodes = watchedEpisodesMap.keys

    val currentIndex = episodes.indexOfFirst {
        it.season == currentSeason && it.episode == currentEpisode
    }
    if (currentIndex < 0) return null

    val nextEpisode = episodes
        .drop(currentIndex + 1)
        .firstOrNull { candidate ->
            val s = candidate.season ?: return@firstOrNull false
            val e = candidate.episode ?: return@firstOrNull false
            (s to e) !in watchedEpisodes
        }
        ?: return null

    if (!shouldIncludeNextUpEpisode(nextEpisode, showUnairedNextUp)) return null

    val latestWatchedAt = watchedEpisodesMap.values.maxOrNull() ?: 0L
    return NextUpResolution(
        episode = nextEpisode,
        lastWatched = maxOf(progress.lastWatched, latestWatchedAt)
    )
}

private suspend fun HomeViewModel.resolveMetaForProgress(
    progress: WatchProgress,
    metaCache: MutableMap<String, Meta?>
): Meta? {
    val cacheKey = "${progress.contentType}:${progress.contentId}"
    synchronized(metaCache) {
        if (metaCache.containsKey(cacheKey)) {
            return metaCache[cacheKey]
        }
    }

    val idCandidates = buildList {
        add(progress.contentId)
        if (progress.contentId.startsWith("tmdb:")) add(progress.contentId.substringAfter(':'))
        if (progress.contentId.startsWith("trakt:")) add(progress.contentId.substringAfter(':'))
    }.distinct()

    val typeCandidates = listOf(progress.contentType, "series", "tv").distinct()
    val resolved = run {
        var meta: Meta? = null
        for (type in typeCandidates) {
            for (candidateId in idCandidates) {
                val result = withTimeoutOrNull(2500) {
                    metaRepository.getMetaFromPrimaryAddon(
                        type = type,
                        id = candidateId
                    ).first { it !is NetworkResult.Loading }
                } ?: continue
                meta = (result as? NetworkResult.Success)?.data
                if (meta != null) break
            }
            if (meta != null) break
        }
        meta
    }

    synchronized(metaCache) {
        metaCache[cacheKey] = resolved
    }
    return resolved
}

private fun isSeriesTypeCW(type: String?): Boolean {
    return type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true)
}

private fun shouldIncludeNextUpEpisode(
    nextEpisode: Video,
    showUnairedNextUp: Boolean
): Boolean {
    if (showUnairedNextUp) return true
    val releaseDate = parseEpisodeReleaseDate(nextEpisode.released)
        ?: return true
    val todayLocal = LocalDate.now(ZoneId.systemDefault())
    return !releaseDate.isAfter(todayLocal)
}

private fun parseEpisodeReleaseDate(raw: String?): LocalDate? {
    if (raw.isNullOrBlank()) return null
    val value = raw.trim()
    val zone = ZoneId.systemDefault()

    return runCatching {
        Instant.parse(value).atZone(zone).toLocalDate()
    }.getOrNull() ?: runCatching {
        OffsetDateTime.parse(value).toInstant().atZone(zone).toLocalDate()
    }.getOrNull() ?: runCatching {
        LocalDateTime.parse(value).toLocalDate()
    }.getOrNull() ?: runCatching {
        LocalDate.parse(value)
    }.getOrNull() ?: runCatching {
        val datePortion = Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b").find(value)?.value
            ?: return@runCatching null
        LocalDate.parse(datePortion)
    }.getOrNull()
}

private suspend fun HomeViewModel.resolveNextUpTmdbData(
    progress: WatchProgress,
    meta: Meta,
    season: Int,
    episode: Int
): NextUpTmdbData? {
    if (!currentTmdbSettings.enabled) return null
    val tmdbId = resolveTmdbIdForNextUp(progress, meta) ?: return null
    val language = currentTmdbSettings.language

    val episodeMeta = runCatching {
        tmdbMetadataService
            .fetchEpisodeEnrichment(
                tmdbId = tmdbId,
                seasonNumbers = listOf(season),
                language = language
            )[season to episode]
    }.getOrNull()

    val showMeta = runCatching {
        tmdbMetadataService.fetchEnrichment(
            tmdbId = tmdbId,
            contentType = ContentType.SERIES,
            language = language
        )
    }.getOrNull()

    val fallback = NextUpTmdbData(
        thumbnail = episodeMeta?.thumbnail.normalizeImageUrl(),
        backdrop = showMeta?.backdrop.normalizeImageUrl(),
        poster = showMeta?.poster.normalizeImageUrl(),
        logo = showMeta?.logo.normalizeImageUrl(),
        name = showMeta?.localizedTitle?.trim()?.takeIf { it.isNotEmpty() },
        episodeTitle = episodeMeta?.title?.trim()?.takeIf { it.isNotEmpty() },
        airDate = episodeMeta?.airDate?.trim()?.takeIf { it.isNotEmpty() },
        overview = episodeMeta?.overview?.trim()?.takeIf { it.isNotEmpty() },
        showDescription = showMeta?.description?.trim()?.takeIf { it.isNotEmpty() }
    )

    return if (
        fallback.thumbnail == null &&
        fallback.backdrop == null &&
        fallback.poster == null &&
        fallback.airDate == null &&
        fallback.overview == null
    ) {
        null
    } else {
        fallback
    }
}

private suspend fun HomeViewModel.resolveTmdbIdForNextUp(
    progress: WatchProgress,
    meta: Meta
): String? {
    val candidates = buildList {
        add(progress.contentId)
        add(meta.id)
        add(progress.videoId)
        if (progress.contentId.startsWith("trakt:")) add(progress.contentId.substringAfter(':'))
        if (meta.id.startsWith("trakt:")) add(meta.id.substringAfter(':'))
    }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

    for (candidate in candidates) {
        tmdbService.ensureTmdbId(candidate, progress.contentType)?.let { return it }
    }
    return null
}

private fun formatEpisodeAirDateLabel(releaseDate: LocalDate): String {
    val todayLocal = LocalDate.now(ZoneId.systemDefault())
    val formatter = if (releaseDate.year == todayLocal.year) {
        DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    } else {
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
    }
    return releaseDate.format(formatter)
}

private fun String?.normalizeImageUrl(): String? = this
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

internal fun nextUpDismissKey(contentId: String): String {
    return contentId.trim()
}

internal fun HomeViewModel.removeContinueWatchingPipeline(
    contentId: String,
    season: Int? = null,
    episode: Int? = null,
    isNextUp: Boolean = false
) {
    if (isNextUp) {
        val dismissKey = nextUpDismissKey(contentId)
        _uiState.update { state ->
            state.copy(
                continueWatchingItems = state.continueWatchingItems.filterNot { item ->
                    when (item) {
                        is ContinueWatchingItem.NextUp ->
                            nextUpDismissKey(item.info.contentId) == dismissKey
                        is ContinueWatchingItem.InProgress -> false
                    }
                }
            )
        }
        viewModelScope.launch {
            traktSettingsDataStore.addDismissedNextUpKey(dismissKey)
        }
        return
    }
    viewModelScope.launch {
        val targetSeason = if (isNextUp) season else null
        val targetEpisode = if (isNextUp) episode else null
        Log.d(
            HomeViewModel.TAG,
            "removeContinueWatching requested contentId=$contentId season=$season episode=$episode isNextUp=$isNextUp targetSeason=$targetSeason targetEpisode=$targetEpisode"
        )
        watchProgressRepository.removeProgress(
            contentId = contentId,
            season = targetSeason,
            episode = targetEpisode
        )
    }
}
