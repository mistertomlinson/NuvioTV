package com.nuvio.tv.ui.screens.home

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.data.repository.runProtectedNextUpDismissal
import com.nuvio.tv.domain.model.normalizeLanguageCode
import com.nuvio.tv.domain.model.countryToLanguageCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

private const val CW_MAX_RECENT_PROGRESS_ITEMS = 300
private const val CW_MAX_NEXT_UP_LOOKUPS = 32
private const val CW_MAX_NEXT_UP_CONCURRENCY = 4
private const val CW_MAX_ENRICHMENT_CONCURRENCY = 4
private const val CW_PROGRESS_DEBOUNCE_MS = 500L

private data class ProgressSnapshot(
    val items: List<WatchProgress>,
    val nextUpSeeds: List<WatchProgress>,
    val hasLoadedRemoteProgress: Boolean,
    val profileId: Int
)

private data class ContinueWatchingSettingsSnapshot(
    val items: List<WatchProgress>,
    val nextUpSeeds: List<WatchProgress>,
    val daysCap: Int,
    val dismissedNextUp: Set<String>,
    val showUnairedNextUp: Boolean,
    val watchedItemsVersion: Int,  // triggers re-evaluation when watched items change
    val latestWatchedMovieAtByContentId: Map<String, Long>,
    val hasLoadedRemoteProgress: Boolean,
    val profileId: Int
)

/**
 * Lightweight projection of [Meta] for CW pipeline caching.
 * Drops cast, crew, trailers, streams, release dates, and other heavy fields
 * that are never read by continue-watching or badge evaluation.
 */
internal data class CwMetaSummary(
    val id: String,
    val name: String,
    val poster: String?,
    val backdropUrl: String?,
    val logo: String?,
    val description: String?,
    val genres: List<String>,
    val releaseInfo: String?,
    val imdbRating: Float?,
    val language: String?,
    val country: String?,
    val videos: List<CwVideoSummary>,
    val status: String? = null
) {
    fun watchableEpisodes(): List<CwVideoSummary> {
        val today = java.time.LocalDate.now()
        val candidates = videos.filter { (it.season ?: 0) > 0 }
        val unavailableSeasons = candidates.groupBy { it.season }
            .filter { (_, eps) ->
                val first = eps.minByOrNull { it.episode ?: Int.MAX_VALUE } ?: return@filter false
                // Exclude if explicitly marked unavailable
                if (first.available == false) return@filter true
                // Exclude if release date is in the future
                val released = first.released?.substringBefore('T')?.trim()
                if (!released.isNullOrBlank()) {
                    try {
                        return@filter java.time.LocalDate.parse(
                            released,
                            java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
                        ).isAfter(today)
                    } catch (_: java.time.format.DateTimeParseException) { }
                }
                false
            }.keys
        return if (unavailableSeasons.isEmpty()) candidates
        else candidates.filter { it.season !in unavailableSeasons }
    }

    /**
     * Returns the start-of-day (00:00 UTC) epochMs of the earliest upcoming season's
     * first episode release date, or null if no upcoming seasons are known.
     * Uses start-of-day so revalidation triggers right after midnight, not at
     * the exact broadcast time.
     */
    fun releasedRegularEpisodeCoordinates(
        today: LocalDate = LocalDate.now(ZoneId.systemDefault())
    ): Set<Pair<Int, Int>> =
        videos.asSequence()
            .filter { (it.season ?: 0) > 0 }
            .filter { it.available != false }
            .filter { video ->
                val releaseDate = parseEpisodeReleaseDate(video.released)
                releaseDate == null || !releaseDate.isAfter(today)
            }
            .mapNotNull { video ->
                val season = video.season ?: return@mapNotNull null
                val episode = video.episode ?: return@mapNotNull null
                season to episode
            }
            .toSet()

    fun earliestUpcomingSeasonMs(): Long? {
        val today = java.time.LocalDate.now()
        val candidates = videos.filter { (it.season ?: 0) > 0 }
        return candidates.groupBy { it.season }
            .mapNotNull { (_, eps) ->
                val first = eps.minByOrNull { it.episode ?: Int.MAX_VALUE } ?: return@mapNotNull null
                if (first.available == false) return@mapNotNull null
                val released = first.released?.substringBefore('T')?.trim()
                if (released.isNullOrBlank()) return@mapNotNull null
                try {
                    val date = java.time.LocalDate.parse(
                        released,
                        java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
                    )
                    if (date.isAfter(today)) {
                        date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                    } else null
                } catch (_: java.time.format.DateTimeParseException) { null }
            }
            .minOrNull()
    }
}

internal data class CwVideoSummary(
    val id: String,
    val title: String?,
    val released: String?,
    val thumbnail: String?,
    val season: Int?,
    val episode: Int?,
    val overview: String?,
    val available: Boolean? = null
)

internal fun isTerminalSeriesStatus(status: String?): Boolean =
    when (status?.trim()?.lowercase(Locale.ROOT)) {
        "ended", "canceled", "cancelled" -> true
        else -> false
    }

internal fun shouldShowSeriesWatchedBadge(
    status: String?,
    releasedRegularEpisodes: Set<Pair<Int, Int>>,
    watchedEpisodes: Set<Pair<Int, Int>>
): Boolean =
    releasedRegularEpisodes.isNotEmpty() &&
        isTerminalSeriesStatus(status) &&
        releasedRegularEpisodes.all { it in watchedEpisodes }

private fun HomeViewModel.cachedBadgeSeriesStatus(
    contentId: String,
    summary: CwMetaSummary?
): String? {
    summary?.status
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }

    enrichmentCache[contentId]
        ?.status
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }

    if (contentId.startsWith("tt")) {
        tmdbService.cachedTmdbId(contentId)?.let { tmdbId ->
            enrichmentCache["tmdb:$tmdbId"]
                ?.status
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
    }

    return null
}

private suspend fun HomeViewModel.resolveBadgeSeriesStatus(
    contentId: String,
    contentType: String,
    summary: CwMetaSummary?
): String? {
    cachedBadgeSeriesStatus(contentId, summary)?.let { return it }

    if (
        !currentTmdbSettings.enabled ||
        !currentTmdbSettings.useDetails
    ) {
        return null
    }

    /*
     * Home already repairs missing status through TMDB because addon/basic
     * metadata can legitimately omit it.  Badge-only metadata must follow the
     * same contract or an ended show with complete watched history can never
     * qualify for its checkmark.
     *
     * This runs only on the existing badge IO worker and only when status is
     * missing.  Nothing is added to composition, focus, or the scroll path.
     */
    val repairedStatus =
        try {
            withTimeoutOrNull(4_000L) {
                val tmdbId =
                    tmdbService.ensureTmdbId(
                        contentId,
                        contentType
                    ) ?: return@withTimeoutOrNull null

                tmdbMetadataService.fetchFreshStatus(
                    tmdbId = tmdbId,
                    contentType = ContentType.SERIES,
                    language = currentTmdbSettings.language
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }

    return repairedStatus
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

/**
 * A nullable next-up result is authoritative only when metadata contains the
 * completed seed episode. Missing, empty, or mismatched metadata is
 * inconclusive and must be retried.
 */
internal fun hasResolvedNextUpSeed(
    meta: CwMetaSummary?,
    seedSeason: Int?,
    seedEpisode: Int?
): Boolean {
    if (meta == null || seedSeason == null || seedEpisode == null || seedSeason == 0) {
        return false
    }
    return meta.videos.any { video ->
        video.season == seedSeason && video.episode == seedEpisode
    }
}

private fun HomeViewModel.hasResolvedNextUpSeed(progress: WatchProgress): Boolean {
    val meta = synchronized(cwMetaCache) {
        cwMetaCache["${progress.contentType}:${progress.contentId}"]
            ?: cwMetaCache["series:${progress.contentId}"]
            ?: cwMetaCache["tv:${progress.contentId}"]
    }
    return hasResolvedNextUpSeed(meta, progress.season, progress.episode)
}

internal fun canReuseCachedNextUpEpisodeFields(
    cachedSeason: Int?,
    cachedEpisode: Int?,
    freshSeason: Int,
    freshEpisode: Int
): Boolean {
    return cachedSeason == freshSeason && cachedEpisode == freshEpisode
}

private fun Meta.toCwSummary(): CwMetaSummary = CwMetaSummary(
    id = id,
    name = name,
    poster = poster,
    backdropUrl = backdropUrl,
    logo = logo,
    description = description,
    genres = genres,
    releaseInfo = releaseInfo,
    status = status,
    imdbRating = imdbRating,
    language = language,
    country = country,
    videos = videos.map { v ->
        CwVideoSummary(
            id = v.id,
            title = v.title,
            released = v.released,
            thumbnail = v.thumbnail,
            season = v.season,
            episode = v.episode,
            overview = v.overview,
            available = v.available
        )
    }
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
    val showDescription: String?,
    val rating: Double?,
    val contentLanguage: String? = null
)

internal data class NextUpResolution(
    val season: Int,
    val episode: Int,
    val videoId: String,
    val episodeTitle: String?,
    val released: String?,
    val hasAired: Boolean,
    val airDateLabel: String?,
    val lastWatched: Long
)

private data class NextUpReleaseState(
    val sortTimestamp: Long,
    val releaseTimestamp: Long?,
    val isReleaseAlert: Boolean,
    val isNewSeasonRelease: Boolean
)

private class CwDebugSession {
    fun markPhase(value: String) = Unit
    fun logStart(
        snapshot: ContinueWatchingSettingsSnapshot,
        recentItemsCount: Int,
        recentSeedsCount: Int,
        cutoffMs: Long?
    ) = Unit
    fun recordInProgressCount(count: Int) = Unit
    fun recordNextUpBuildComplete(count: Int, elapsedMs: Long) = Unit
    fun recordLightweightRendered(count: Int, elapsedMs: Long) = Unit
    fun recordInitialRendered(count: Int, elapsedMs: Long) = Unit
    fun recordPartialRendered(count: Int, elapsedMs: Long) = Unit
    fun recordEnrichmentDelay(delayMs: Long) = Unit
    fun recordEnrichmentComplete(elapsedMs: Long, changed: Boolean) = Unit
    fun recordMetaCacheHit(progress: WatchProgress) = Unit
    fun recordMetaAttempt(
        progress: WatchProgress,
        type: String,
        candidateId: String,
        elapsedMs: Long,
        outcome: String
    ) = Unit
    fun recordMetaResolveFinished(
        progress: WatchProgress,
        elapsedMs: Long,
        success: Boolean,
        attempts: Int
    ) = Unit
    fun recordMetaTimeout() = Unit
    fun recordMetaError() = Unit
    fun recordTmdbIdLookup(progress: WatchProgress, candidateCount: Int, resolved: Boolean, elapsedMs: Long) = Unit
    fun recordTmdbIdCacheHit(progress: WatchProgress, resolved: Boolean) = Unit
    fun recordTmdbCall(kind: String, elapsedMs: Long, success: Boolean) = Unit
    fun recordNextUpAttempt(progress: WatchProgress) = Unit
    fun recordNextUpResult(progress: WatchProgress, reason: String, elapsedMs: Long, resolved: Boolean) = Unit
    fun recordNextUpCacheHit(progress: WatchProgress, resolved: Boolean, showUnairedNextUp: Boolean) = Unit
    fun logSummary(cancelled: Boolean = false) = Unit
}

// Canonical ordering applied at every point the CW list is committed to state.
// The async pipeline recomputes this list from several combined Flows and can settle
// through multiple emissions in quick succession (enrichment arriving, next-up seeds
// resolving, etc.) — without a single consistent sort, ties/near-ties can land in a
// different relative order on each pass, which shifts item positions in the LazyRow
// enough to push items in/out of the composed viewport range and cause a visible
// flicker (dispose + recreate) even though the underlying item set hasn't changed.
private fun List<ContinueWatchingItem>.stableCwOrdered(): List<ContinueWatchingItem> {
    return sortedWith(
        compareByDescending<ContinueWatchingItem> { item ->
            when (item) {
                is ContinueWatchingItem.InProgress -> item.progress.lastWatched
                is ContinueWatchingItem.NextUp -> item.info.sortTimestamp
            }
        }.thenBy { item ->
            when (item) {
                is ContinueWatchingItem.InProgress -> item.progress.contentId
                is ContinueWatchingItem.NextUp -> item.info.contentId
            }
        }
    )
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
internal fun HomeViewModel.loadContinueWatchingPipeline() {
    cwPipelineJob?.cancel()
    cwPipelineJob = viewModelScope.launch {
        var immediatePlaybackRefreshAtMs = Long.MIN_VALUE

        launch {
            watchProgressRepository
                .observeOptimisticContinueWatchingUpdates()
                .collectLatest { progress ->
                    val ageMs =
                        System.currentTimeMillis() - progress.lastWatched

                    // SharedFlow has replay=1. Ignore an old replayed event when
                    // this pipeline is recreated; only a genuinely fresh player
                    // handoff gets the no-debounce path.
                    if (ageMs in 0L..2_000L) {
                        immediatePlaybackRefreshAtMs =
                            SystemClock.elapsedRealtime()

                        // If a direct Player -> Home return was armed before the
                        // final playback save, bind this genuinely fresh optimistic
                        // handoff to that return generation. A replayed/stale event
                        // never reaches this branch because of the age check above.
                        val requestedReturnGeneration =
                            playerReturnCwRequestedGeneration
                        if (
                            requestedReturnGeneration >
                                playerReturnCwSettledGeneration.value
                        ) {
                            playerReturnCwActiveGeneration =
                                requestedReturnGeneration
                        }

                        cwPipelineRefreshTrigger.value =
                            cwPipelineRefreshTrigger.value + 1
                    }
                }
        }

        combine(
            combine(
                watchProgressRepository.allProgress,
                watchProgressRepository.observeNextUpSeeds(),
                watchProgressRepository.observeRemoteProgressLoaded(),
                profileManager.activeProfileId
            ) { items, nextUpSeeds, hasLoadedRemoteProgress, profileId ->
                ProgressSnapshot(
                    items = items,
                    nextUpSeeds = nextUpSeeds,
                    hasLoadedRemoteProgress = hasLoadedRemoteProgress,
                    profileId = profileId
                )
            },
            combine(
                traktSettingsDataStore.continueWatchingDaysCap,
                traktSettingsDataStore.dismissedNextUpKeys,
                traktSettingsDataStore.showUnairedNextUp
            ) { daysCap, dismissedNextUp, showUnairedNextUp ->
                Triple(daysCap, dismissedNextUp, showUnairedNextUp)
            },
            watchedItemsPreferences.allItems.map { watchedItems ->
                val latestWatchedMovieAtByContentId =
                    watchedItems
                        .asSequence()
                        .filter { item ->
                            item.season == null &&
                                item.episode == null
                        }
                        .groupBy { it.contentId }
                        .mapValues { (_, items) ->
                            items.maxOf { it.watchedAt }
                        }

                watchedItems.size to latestWatchedMovieAtByContentId
            },
            cwPipelineRefreshTrigger
        ) { progressSnapshot, settingsSnapshot, watchedItemsSnapshot, _ ->
            val (items, nextUpSeeds, hasLoadedRemoteProgress, profileId) =
                progressSnapshot
            val (daysCap, dismissedNextUp, showUnairedNextUp) =
                settingsSnapshot
            val (
                watchedItemsSize,
                latestWatchedMovieAtByContentId
            ) = watchedItemsSnapshot

            ContinueWatchingSettingsSnapshot(
                items = items,
                nextUpSeeds = nextUpSeeds,
                daysCap = daysCap,
                dismissedNextUp = dismissedNextUp,
                showUnairedNextUp = showUnairedNextUp,
                watchedItemsVersion = watchedItemsSize,
                latestWatchedMovieAtByContentId =
                    latestWatchedMovieAtByContentId,
                hasLoadedRemoteProgress = hasLoadedRemoteProgress,
                profileId = profileId
            )
        }.debounce {
            val immediateAgeMs =
                SystemClock.elapsedRealtime() - immediatePlaybackRefreshAtMs

            if (immediateAgeMs in 0L..2_000L) {
                0L
            } else {
                CW_PROGRESS_DEBOUNCE_MS
            }
        }.collectLatest { snapshot ->
            val debug = CwDebugSession()
            try {
                debug.markPhase("filter-snapshot")
                val cycleStartMs = SystemClock.elapsedRealtime()
                val useTrackingProvider =
                    watchProgressRepository.hasActiveTrackingProgressProvider()
                val items = snapshot.items
                val nextUpSeeds = snapshot.nextUpSeeds
                val daysCap = snapshot.daysCap
                val dismissedNextUp = snapshot.dismissedNextUp
                val showUnairedNextUp = snapshot.showUnairedNextUp
                val latestWatchedMovieAtByContentId =
                    snapshot.latestWatchedMovieAtByContentId
                val cycleProfileId = snapshot.profileId

                val authoritativeProfileCycle =
                    !useTrackingProvider ||
                        snapshot.hasLoadedRemoteProgress

                val initialProfilePresentation =
                    _uiState.value.homeLoadSessionId > 0L &&
                        !_uiState.value.continueWatchingFreshReady &&
                        authoritativeProfileCycle

                var olderNextUpDiscoveryJob:
                    kotlinx.coroutines.Job? = null

                val cutoffMs =
                    watchProgressRepository.activeProviderContinueWatchingCutoffEpochMs(
                        daysCap = daysCap,
                        nowEpochMs = System.currentTimeMillis()
                    )
                val recentItems = items
                    .asSequence()
                    .filter { progress -> cutoffMs == null || progress.lastWatched >= cutoffMs }
                    .sortedByDescending { it.lastWatched }
                    .take(CW_MAX_RECENT_PROGRESS_ITEMS)
                    .toList()
                val recentNextUpSeeds = nextUpSeeds
                    .asSequence()
                    .filter { progress -> cutoffMs == null || progress.lastWatched >= cutoffMs }
                    .sortedByDescending { it.lastWatched }
                    .take(CW_MAX_RECENT_PROGRESS_ITEMS)
                    .toList()
                // All series that still have at least one watched-episode seed.
                // Used to drop cached next-up items for series whose episodes
                // have been fully unmarked as watched.
                val activeSeedContentIds = nextUpSeeds
                    .mapTo(mutableSetOf()) { it.contentId }
                // Do not evict valid cached rows until the selected remote
                // provider has conclusively completed its initial load.
                if (snapshot.hasLoadedRemoteProgress) {
                    synchronized(discoveredOlderNextUpItems) {
                        discoveredOlderNextUpItems.removeAll {
                            it.info.contentId !in activeSeedContentIds
                        }
                    }
                    synchronized(cwEnrichedNextUpOverlay) {
                        cwEnrichedNextUpOverlay.keys.removeAll {
                            it !in activeSeedContentIds
                        }
                    }
                }

                debug.logStart(
                    snapshot = snapshot,
                    recentItemsCount = recentItems.size,
                    recentSeedsCount = recentNextUpSeeds.size,
                    cutoffMs = cutoffMs
                )

                // Load cached CW snapshots for instant render before Trakt responds
                val (cachedNextUp, cachedInProgress) = coroutineScope {
                    val nextUpDeferred = async(Dispatchers.IO) {
                        runCatching { cwEnrichmentCache.getNextUpSnapshot(cycleProfileId) }.getOrDefault(emptyList())
                    }
                    val inProgressDeferred = async(Dispatchers.IO) {
                        runCatching { cwEnrichmentCache.getInProgressSnapshot(cycleProfileId) }.getOrDefault(emptyList())
                    }
                    nextUpDeferred.await() to inProgressDeferred.await()
                }
                // Build enrichment lookup from cached snapshots (replaces old CwEnrichmentEntry)
                val cachedEnrichmentFromInProgress = cachedInProgress.associateBy { it.contentId }
                val cachedEnrichmentFromNextUp = cachedNextUp.associateBy { it.contentId }

                // Seed the in-memory enrichment overlay from disk cache on first cycle
                // so that fresh builds use enriched titles/thumbnails from the start.
                if (cwEnrichedNextUpOverlay.isEmpty() && cachedNextUp.isNotEmpty()) {
                    cachedNextUp.forEach { cached ->
                        cwEnrichedNextUpOverlay[cached.contentId] = NextUpInfo(
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
                            releaseInfo = cached.releaseInfo,
                            sortTimestamp = recomputeSortTimestamp(cached.lastWatched, cached.releaseTimestamp, cached.isReleaseAlert),
                            releaseTimestamp = cached.releaseTimestamp,
                            isReleaseAlert = cached.isReleaseAlert,
                            isNewSeasonRelease = cached.isNewSeasonRelease,
                            seedSeason = cached.seedSeason,
                            seedEpisode = cached.seedEpisode,
                            contentLanguage = cached.contentLanguage
                        )
                    }
                }
                /*
                 * A provider may temporarily retain an older playback session
                 * after a later episode has already completed locally.
                 *
                 * Example:
                 *   remote playback: episode 5
                 *   local autoplay:  episodes 6 -> 7 -> 8 completed
                 *
                 * The newer completed seed is authoritative for CW ordering.
                 * Do not allow the older in-progress projection to survive in
                 * the visible Continue Watching row while the provider catches up.
                 *
                 * A genuine later rewatch remains valid because its lastWatched
                 * timestamp will be newer than the completion seed.
                 */
                val latestCompletedAtByContent =
                    latestCompletedAtByContentForSuppression(
                        allProgress = recentItems,
                        nextUpSeeds = recentNextUpSeeds,
                        isCompletedSeed = ::shouldUseAsCompletedSeed
                    )

                val inProgressOnly = buildList {
                    val liveInProgress = deduplicateInProgress(
                        recentItems.filter { progress ->
                            shouldTreatAsActiveInProgressForNextUpSuppression(
                                progress = progress,
                                latestCompletedAt =
                                    latestCompletedAtByContent[
                                        progress.contentId
                                    ]
                            )
                        }
                    )
                    if (liveInProgress.isNotEmpty()) {
                        liveInProgress.forEach { progress ->
                            val cached = cachedEnrichmentFromInProgress[progress.contentId]
                            val displayProgress = if (cached != null && (cached.backdrop != null || cached.poster != null || cached.logo != null || cached.name.isNotBlank())) {
                                progress.copy(
                                    backdrop = cached.backdrop ?: progress.backdrop,
                                    poster = cached.poster ?: progress.poster,
                                    logo = cached.logo ?: progress.logo,
                                    name = cached.name.takeIf { it.isNotBlank() } ?: progress.name
                                )
                            } else {
                                progress
                            }
                            add(
                                ContinueWatchingItem.InProgress(
                                    progress = displayProgress,
                                    episodeThumbnail = cached?.episodeThumbnail,
                                    episodeDescription = cached?.episodeDescription,
                                    episodeImdbRating = cached?.episodeImdbRating,
                                    genres = cached?.genres ?: emptyList(),
                                    releaseInfo = cached?.releaseInfo,
                                    contentLanguage = cached?.contentLanguage
                                )
                            )
                        }
                    }
                    // Keep the last cached projection visible while either
                    // remote provider is still loading.
                    if (
                        liveInProgress.isEmpty() &&
                        useTrackingProvider &&
                        cachedInProgress.isNotEmpty() &&
                        items.isEmpty() &&
                        !snapshot.hasLoadedRemoteProgress
                    ) {
                        cachedInProgress.forEach { cached ->
                            /*
                             * A provider can still be loading when playback has
                             * already completed a movie locally. Do not restore
                             * that stale cached CW card.
                             *
                             * Compare timestamps rather than merely checking
                             * whether the movie has ever been watched. This
                             * preserves a legitimate rewatch: old watched
                             * history remains older than the newer cached
                             * in-progress snapshot until the rewatch itself
                             * actually completes.
                             */
                            val isMovie =
                                cached.season == null &&
                                    cached.episode == null

                            val latestWatchedAt =
                                latestWatchedMovieAtByContentId[
                                    cached.contentId
                                ]

                            val completedSinceCachedSnapshot =
                                isMovie &&
                                    latestWatchedAt != null &&
                                    latestWatchedAt >=
                                        cached.lastWatched

                            if (completedSinceCachedSnapshot) {
                                return@forEach
                            }

                            add(
                                ContinueWatchingItem.InProgress(
                                    progress = WatchProgress(
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
                            )
                        }
                    }
                }
                debug.recordInProgressCount(inProgressOnly.size)

                debug.markPhase("render-in-progress")
                // Render in-progress items + cached next-up immediately
                val currentSeedByContentId = nextUpSeeds
                    .filter { it.season != null && it.episode != null }
                    .associateBy(
                        keySelector = { it.contentId },
                        valueTransform = { it.season!! to it.episode!! }
                    )
                val cachedNextUpItems = cachedNextUp.mapNotNull { cached ->
                    // Skip if this show is already in-progress (suppression)
                    if (inProgressOnly.any { it.progress.contentId == cached.contentId }) return@mapNotNull null
                    // Skip dismissed items
                    if (nextUpDismissKey(cached.contentId, cached.seedSeason, cached.seedEpisode) in dismissedNextUp) return@mapNotNull null
                    // Respect "show unaired" setting
                    if (!cached.hasAired && !showUnairedNextUp) return@mapNotNull null
                    // Drop if the series no longer has any watched-episode seeds
                    // (e.g. user unmarked all episodes as watched).
                    if (snapshot.hasLoadedRemoteProgress && cached.contentId !in activeSeedContentIds) return@mapNotNull null
                    /*
                     * The cached card was derived from seedSeason/seedEpisode.
                     * If playback has since completed a later episode, publishing
                     * this snapshot would briefly resurrect the old Next Up card.
                     */
                    val currentSeed = currentSeedByContentId[cached.contentId]
                    if (
                        currentSeed != null &&
                        cached.seedSeason != null &&
                        cached.seedEpisode != null
                    ) {
                        val (currentSeason, currentEpisode) = currentSeed
                        val seedAdvanced =
                            currentSeason > cached.seedSeason ||
                                (
                                    currentSeason == cached.seedSeason &&
                                        currentEpisode > cached.seedEpisode
                                    )
                        if (seedAdvanced) return@mapNotNull null
                    }
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
                            releaseInfo = cached.releaseInfo,
                            sortTimestamp = recomputeSortTimestamp(cached.lastWatched, cached.releaseTimestamp, cached.isReleaseAlert),
                            releaseTimestamp = cached.releaseTimestamp,
                            isReleaseAlert = cached.isReleaseAlert,
                            isNewSeasonRelease = cached.isNewSeasonRelease,
                            seedSeason = cached.seedSeason,
                            seedEpisode = cached.seedEpisode
                        )
                    )
                }
                if (inProgressOnly.isNotEmpty() || cachedNextUpItems.isNotEmpty()) {
                    val initialItems = applyContinueWatchingEnrichmentOverlay(
                        mergeContinueWatchingItems(
                            inProgressItems = inProgressOnly,
                            nextUpItems = cachedNextUpItems
                        )
                    )
                    _uiState.update { state ->
                        if (state.continueWatchingItems == initialItems && state.continueWatchingEnrichmentReady) {
                            state
                        } else {
                            state.copy(continueWatchingItems = initialItems.stableCwOrdered(), continueWatchingEnrichmentReady = true)
                        }
                    }
                    _initialCwResolved.value = true
                    debug.recordInitialRendered(
                        count = initialItems.size,
                        elapsedMs = SystemClock.elapsedRealtime() - cycleStartMs
                    )
                    // Persist in-progress snapshot early so force-close doesn't lose items
                    if (inProgressOnly.isNotEmpty()) {
                        val ipSnapProfileId = profileManager.activeProfileId.value
                        viewModelScope.launch(Dispatchers.IO) {
                            val brokenUrls = com.nuvio.tv.ui.components.brokenImageUrls
                            val ipSnap = inProgressOnly.map { item ->
                                com.nuvio.tv.data.local.CachedInProgressItem(
                                    contentId = item.progress.contentId, contentType = item.progress.contentType,
                                    name = item.progress.name, poster = item.progress.poster,
                                    backdrop = item.progress.backdrop, logo = item.progress.logo,
                                    videoId = item.progress.videoId, season = item.progress.season,
                                    episode = item.progress.episode, episodeTitle = item.progress.episodeTitle,
                                    position = item.progress.position, duration = item.progress.duration,
                                    lastWatched = item.progress.lastWatched, progressPercent = item.progress.progressPercent,
                                    episodeThumbnail = item.episodeThumbnail?.takeIf { it !in brokenUrls },
                                    episodeDescription = item.episodeDescription,
                                    episodeImdbRating = item.episodeImdbRating,
                                    genres = item.genres, releaseInfo = item.releaseInfo,
                                    contentLanguage = item.contentLanguage
                                )
                            }
                            runCatching { cwEnrichmentCache.saveInProgressSnapshot(ipSnap, profileId = ipSnapProfileId) }
                        }
                    }
                }

                debug.markPhase("build-next-up")
                val nextUpStartMs = SystemClock.elapsedRealtime()
                val publishedPartialNextUpCount = AtomicInteger(0)
                val partialPublishMutex = Mutex()
                val nextUpItems = buildLightweightNextUpItems(
                    allProgress = recentItems,
                    nextUpSeeds = recentNextUpSeeds,
                    inProgressItems = inProgressOnly,
                    dismissedNextUp = dismissedNextUp,
                    showUnairedNextUp = showUnairedNextUp,
                    debug = debug,
                    onPartialUpdate = { partialNextUpItems ->
                        partialPublishMutex.withLock {
                            val partialCount = partialNextUpItems.size
                            if (partialCount > publishedPartialNextUpCount.get()) {
                                publishedPartialNextUpCount.set(partialCount)
                                val freshIds = partialNextUpItems.map { it.info.contentId }.toSet()
                                val cachedPartialNextUp = partialNextUpItems.map { nextUp ->
                                    val cached = cachedEnrichmentFromNextUp[nextUp.info.contentId]
                                    if (cached != null) {
                                        val sameEpisode = canReuseCachedNextUpEpisodeFields(
                                            cachedSeason = cached.season,
                                            cachedEpisode = cached.episode,
                                            freshSeason = nextUp.info.season,
                                            freshEpisode = nextUp.info.episode
                                        )
                                        nextUp.copy(info = nextUp.info.copy(
                                            // Show-level presentation may safely come from cache.
                                            backdrop = cached.backdrop ?: nextUp.info.backdrop,
                                            poster = cached.poster ?: nextUp.info.poster,
                                            logo = cached.logo ?: nextUp.info.logo,
                                            name = cached.name.takeIf { it.isNotBlank() } ?: nextUp.info.name,
                                            contentLanguage = cached.contentLanguage ?: nextUp.info.contentLanguage,
                                            // Episode-specific presentation is reusable only for
                                            // the exact same season and episode.
                                            thumbnail = if (sameEpisode) {
                                                cached.thumbnail ?: nextUp.info.thumbnail
                                            } else {
                                                nextUp.info.thumbnail
                                            },
                                            airDateLabel = if (sameEpisode) {
                                                cached.airDateLabel ?: nextUp.info.airDateLabel
                                            } else {
                                                nextUp.info.airDateLabel
                                            },
                                            released = if (sameEpisode) {
                                                cached.released ?: nextUp.info.released
                                            } else {
                                                nextUp.info.released
                                            },
                                            season = nextUp.info.season,
                                            episode = nextUp.info.episode,
                                            episodeTitle = if (sameEpisode) {
                                                cached.episodeTitle ?: nextUp.info.episodeTitle
                                            } else {
                                                nextUp.info.episodeTitle
                                            },
                                            sortTimestamp = nextUp.info.sortTimestamp
                                        ))
                                    } else nextUp
                                }
                                // Keep cached next-up items for series not yet processed
                                // by the fresh pipeline so they don't disappear mid-build.
                                val retainedCached = cachedNextUpItems.filter {
                                    it.info.contentId !in freshIds
                                }
                                val partialItems = applyContinueWatchingEnrichmentOverlay(
                                    mergeContinueWatchingItems(
                                        inProgressItems = inProgressOnly,
                                        nextUpItems = cachedPartialNextUp + retainedCached
                                    )
                                )
                                _uiState.update { state ->
                                    if (state.continueWatchingItems == partialItems) {
                                        state
                                    } else {
                                        state.copy(continueWatchingItems = partialItems.stableCwOrdered())
                                    }
                                }
                                debug.recordPartialRendered(
                                    count = partialItems.size,
                                    elapsedMs = SystemClock.elapsedRealtime() - cycleStartMs
                                )
                            }
                        }
                    }
                )
                debug.recordNextUpBuildComplete(
                    count = nextUpItems.size,
                    elapsedMs = SystemClock.elapsedRealtime() - nextUpStartMs
                )

                // Badge evaluation is handled exclusively by publishBadgeUpdate below,
                // which uses getWatchedShowEpisodes() as the single source of truth.
                // No seed-based heuristics here.
                val allWatchedItems = watchedItemsPreferences.allItems.first()
                // --- Async badge evaluation ---
                // Resolve meta for all series with watched episodes and evaluate badges.
                // Uses getWatchedShowEpisodes() as the single source of truth.
                launch(Dispatchers.IO) {
                    val allWatchedEpisodes = watchProgressRepository.getWatchedShowEpisodes()

                    // Skip badge evaluation if watched episodes haven't changed since
                    // last cycle (e.g. position save triggered pipeline restart).
                    val currentKeys = allWatchedEpisodes.keys
                    val staleValidationIds =
                        fullyWatchedSeriesIds.filterStaleIds(currentKeys)
                    if (
                        currentKeys == cwLastBadgeEpisodeKeys &&
                        staleValidationIds.isEmpty()
                    ) {
                        // Keys and metadata validation are unchanged — just re-run
                        // the cheap cached calculation so optimistic episode writes
                        // can add/remove a badge immediately.
                        publishBadgeUpdate(allWatchedEpisodes)
                        return@launch
                    }
                    cwLastBadgeEpisodeKeys = currentKeys.toSet()

                    val showIdSiblings = watchProgressRepository.getShowIdSiblings()

                    // Deduplicate IDs using Trakt's sibling mapping (IMDB ↔ TMDB from
                    // the same show). Resolve meta once per show, then cross-cache the
                    // result under all sibling IDs. When multiple TMDB shows share the
                    // same IMDB (e.g. Trakt season splits), they have separate Trakt
                    // entries with distinct sibling sets, so they won't collide.
                    val resolvableIds = allWatchedEpisodes.keys.filter { contentId ->
                        if (contentId.startsWith("trakt:")) return@filter false
                        val cacheKey = "series:$contentId"
                        val cacheMissing = synchronized(cwBadgeEpisodeCache) {
                            !cwBadgeEpisodeCache.containsKey(cacheKey) &&
                                !cwBadgeEpisodeCache.containsKey("tv:$contentId")
                        }
                        val validationStale =
                            contentId in staleValidationIds
                        cacheMissing || validationStale
                    }
                    // Build groups from sibling map: cluster IDs that belong to the same show.
                    val visited = mutableSetOf<String>()
                    // IDs with ambiguous siblings (shared IMDB across multiple shows)
                    // must not be pulled into other groups via cross-caching.
                    val ambiguousIds = showIdSiblings.entries
                        .filter { "__ambiguous__" in it.value }
                        .map { it.key }
                        .toSet()
                    val idGroups = mutableListOf<List<String>>()
                    for (id in resolvableIds) {
                        if (id in visited) continue
                        val siblings = showIdSiblings[id]
                        val group = if (siblings != null && "__ambiguous__" !in siblings) {
                            val cluster = (siblings + id)
                                .filter { it in resolvableIds && !it.startsWith("trakt:") && it !in ambiguousIds }
                            if (cluster.isEmpty()) listOf(id)
                            else cluster.sortedBy { if (it.startsWith("tt")) 0 else 1 }
                        } else {
                            listOf(id)
                        }
                        visited.addAll(group)
                        idGroups.add(group)
                    }
                    val staleGroups = idGroups.filter { group ->
                        fullyWatchedSeriesIds.filterStaleIds(setOf(group.first())).isNotEmpty()
                    }
                    // Split into first-time (never validated) vs revalidation (expired deadline).
                    val (firstTimeGroups, revalidationGroups) = staleGroups.partition { group ->
                        !fullyWatchedSeriesIds.hasBeenValidated(group.first())
                    }

                    // First-time: resolve as fast as possible so badges appear quickly.
                    if (firstTimeGroups.isNotEmpty()) {
                        val metaSemaphore = Semaphore(2)
                        firstTimeGroups.map { group ->
                            async {
                                metaSemaphore.withPermit {
                                    resolveBadgeGroup(group)
                                }
                            }
                        }.awaitAll()
                    }

                    // Revalidation: process gently to avoid CPU/memory spikes.
                    if (revalidationGroups.isNotEmpty()) {
                        for (group in revalidationGroups) {
                            resolveBadgeGroup(group, forceRefresh = true)
                            kotlinx.coroutines.yield()
                        }
                    }

                    // Single badge evaluation after all meta is resolved.
                    publishBadgeUpdate(allWatchedEpisodes)
                }

                // --- CW next-up injection ---
                // Discover next-up items for older seeds and inject release alerts into CW.
                // Hidden (dropped) shows are already filtered out by observeWatchedShowSeeds().
                if (true) {
                    val recentSeedContentIds = recentNextUpSeeds
                        .filter { isSeriesTypeCW(it.contentType) && it.season != null && it.episode != null }
                        .map { it.contentId }
                        .toSet()
                    val allSeedContentIds = nextUpSeeds
                        .filter { isSeriesTypeCW(it.contentType) && it.season != null && it.episode != null }
                        .map { it.contentId }
                        .toSet()
                    // Include seeds that were in the recent window but didn't fit
                    // into CW_MAX_NEXT_UP_LOOKUPS — they were never processed by
                    // buildLightweightNextUpItems and need async resolution.
                    val processedContentIds = synchronized(cwLastProcessedNextUpContentIds) {
                        cwLastProcessedNextUpContentIds.toSet()
                    }
                    val olderSeedContentIds = allSeedContentIds - processedContentIds
                    val uncachedOlderSeedIds = olderSeedContentIds.filter { contentId ->
                        // Skip series validated recently — no new episodes expected within TTL.
                        if (fullyWatchedSeriesIds.isSeriesValidationFresh(contentId)) return@filter false
                        // Skip series already in the disk cache snapshot — they don't need
                        // re-resolution on every app launch.
                        if (cachedNextUp.any { it.contentId == contentId }) return@filter false
                        synchronized(cwNextUpResolutionCache) {
                            cwNextUpResolutionCache.keys.none { it.startsWith("$contentId|") }
                        }
                    }.toSet()
                    if (uncachedOlderSeedIds.isNotEmpty()) {
                        val seedsFromNextUp = nextUpSeeds
                            .filter { it.contentId in uncachedOlderSeedIds }
                            .filter { isSeriesTypeCW(it.contentType) && it.season != null && it.episode != null && it.season != 0 }
                            .filter { shouldUseAsCompletedSeed(it) }
                        val seedsFromWatchedItems = uncachedOlderSeedIds
                            .filter { contentId -> seedsFromNextUp.none { it.contentId == contentId } }
                            .mapNotNull { contentId ->
                                val latestEpisode = allWatchedItems
                                    .filter { it.contentId == contentId && it.season != null && it.episode != null }
                                    .maxWithOrNull(compareBy({ it.season }, { it.episode }))
                                    ?: return@mapNotNull null
                                WatchProgress(
                                    contentId = contentId,
                                    contentType = "series",
                                    name = latestEpisode.title,
                                    poster = null, backdrop = null, logo = null,
                                    videoId = contentId,
                                    season = latestEpisode.season,
                                    episode = latestEpisode.episode,
                                    episodeTitle = null,
                                    position = 1L, duration = 1L,
                                    lastWatched = latestEpisode.watchedAt,
                                    progressPercent = 100f
                                )
                            }
                        val uncachedSeeds = (seedsFromNextUp + seedsFromWatchedItems)
                            .groupBy { it.contentId }
                            .mapNotNull { (_, items) -> choosePreferredNextUpSeed(items) }
                        if (uncachedSeeds.isNotEmpty()) {
                            olderNextUpDiscoveryJob =
                                launch(Dispatchers.IO) {
                                // Process sequentially with yielding to avoid CPU/GC spikes.
                                // Emit partial updates every few resolved items so user sees
                                // new CW entries appearing progressively.
                                val discoveredNextUpItems = mutableListOf<ContinueWatchingItem.NextUp>()
                                var resolvedSinceLastEmit = 0
                                for (seed in uncachedSeeds) {
                                    // Re-check freshness — badge pipeline may have validated
                                    // this series while we were processing earlier seeds.
                                    if (fullyWatchedSeriesIds.isSeriesValidationFresh(seed.contentId)) {
                                        kotlinx.coroutines.yield()
                                        continue
                                    }
                                    val preparedSeed =
                                        watchProgressRepository.prepareNextUpSeed(seed)
                                    val item = buildNextUpItem(
                                        progress = preparedSeed,
                                        showUnairedNextUp = showUnairedNextUp
                                    )
                                    if (item != null) {
                                        discoveredNextUpItems.add(item)
                                        resolvedSinceLastEmit++
                                        if (resolvedSinceLastEmit >= 3) {
                                            resolvedSinceLastEmit = 0
                                            // Partial emit: inject discovered items into UI
                                            val partialToInject =
                                                if (cutoffMs != null) {
                                                    discoveredNextUpItems.filter { item ->
                                                        item.info.sortTimestamp >= cutoffMs ||
                                                            item.info.isReleaseAlert
                                                    }
                                                } else {
                                                    discoveredNextUpItems.toList()
                                                }
                                            if (partialToInject.isNotEmpty()) {
                                                synchronized(discoveredOlderNextUpItems) {
                                                    discoveredOlderNextUpItems.removeAll { old ->
                                                        partialToInject.any { it.info.contentId == old.info.contentId }
                                                    }
                                                    discoveredOlderNextUpItems.addAll(partialToInject)
                                                }
                                                _uiState.update { state ->
                                                    val existingContentIds = state.continueWatchingItems
                                                        .map {
                                                            when (it) {
                                                                is ContinueWatchingItem.NextUp -> it.info.contentId
                                                                is ContinueWatchingItem.InProgress -> it.progress.contentId
                                                            }
                                                        }
                                                        .toSet()
                                                    val newItems = partialToInject.filter {
                                                        it.info.contentId !in existingContentIds &&
                                                            nextUpDismissKey(it.info.contentId, it.info.seedSeason, it.info.seedEpisode) !in dismissedNextUp
                                                    }
                                                    if (newItems.isEmpty()) return@update state
                                                    val merged = (state.continueWatchingItems + newItems)
                                                        .sortedByDescending { item ->
                                                            when (item) {
                                                                is ContinueWatchingItem.InProgress -> item.progress.lastWatched
                                                                is ContinueWatchingItem.NextUp -> item.info.sortTimestamp
                                                            }
                                                        }
                                                    state.copy(continueWatchingItems = merged.stableCwOrdered())
                                                }
                                            }
                                        }
                                    } else if (hasResolvedNextUpSeed(seed)) {
                                        // No next-up — validate only when metadata contains
                                        // the completed seed episode.
                                        val nextSeasonMs = cwBadgeNextSeasonMs[seed.contentId]
                                        val deadline = nextSeasonMs
                                            ?: (System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000)
                                        fullyWatchedSeriesIds.updateWithValidation(
                                            fullyWatchedSeriesIds.fullyWatchedSeriesIds.value,
                                            setOf(seed.contentId),
                                            mapOf(seed.contentId to deadline)
                                        )
                                    }
                                    kotlinx.coroutines.yield()
                                }

                                // Re-run badge evaluation with episode caches populated
                                // by buildNextUpItem — picks up fully-watched series
                                // discovered during async inject and persists their
                                // deadlines so they're skipped on next launch.
                                val asyncWatchedEpisodes = watchProgressRepository.getWatchedShowEpisodes()
                                publishBadgeUpdate(asyncWatchedEpisodes)

                                if (discoveredNextUpItems.isNotEmpty()) {
                                    // Respect the active provider's age
                                    // window while retaining release alerts.
                                    val itemsToInject =
                                        if (cutoffMs != null) {
                                            discoveredNextUpItems.filter { item ->
                                                item.info.sortTimestamp >= cutoffMs ||
                                                    item.info.isReleaseAlert
                                            }
                                        } else {
                                            discoveredNextUpItems
                                        }
                                    synchronized(discoveredOlderNextUpItems) {
                                        discoveredOlderNextUpItems.removeAll { old ->
                                            itemsToInject.any { it.info.contentId == old.info.contentId }
                                        }
                                        discoveredOlderNextUpItems.addAll(itemsToInject)
                                    }
                                    _uiState.update { state ->
                                        if (profileManager.activeProfileId.value != cycleProfileId) {
                                            return@update state
                                        }
                                        val existingContentIds = state.continueWatchingItems
                                            .map {
                                                when (it) {
                                                    is ContinueWatchingItem.NextUp -> it.info.contentId
                                                    is ContinueWatchingItem.InProgress -> it.progress.contentId
                                                }
                                            }
                                            .toSet()
                                        val newItems = itemsToInject.filter {
                                            it.info.contentId !in existingContentIds &&
                                                nextUpDismissKey(it.info.contentId, it.info.seedSeason, it.info.seedEpisode) !in dismissedNextUp
                                        }
                                        if (newItems.isEmpty()) return@update state
                                        val merged = (state.continueWatchingItems + newItems)
                                            .sortedByDescending { item ->
                                                when (item) {
                                                    is ContinueWatchingItem.InProgress -> item.progress.lastWatched
                                                    is ContinueWatchingItem.NextUp -> item.info.sortTimestamp
                                                }
                                            }
                                        state.copy(continueWatchingItems = merged.stableCwOrdered())
                                    }
                                    // Persist only the state owned by this CW cycle.
                                    val saveProfileId = cycleProfileId
                                    if (profileManager.activeProfileId.value == saveProfileId) {
                                        val saveItems = _uiState.value.continueWatchingItems.toList()
                                        viewModelScope.launch(Dispatchers.IO) {
                                            val currentItems = saveItems
                                        val brokenUrls = com.nuvio.tv.ui.components.brokenImageUrls
                                        val nextUpSnap = currentItems.mapNotNull { item ->
                                            val nu = item as? ContinueWatchingItem.NextUp ?: return@mapNotNull null
                                            val info = nu.info
                                            com.nuvio.tv.data.local.CachedNextUpItem(
                                                contentId = info.contentId, contentType = info.contentType, name = info.name,
                                                poster = info.poster, backdrop = info.backdrop, logo = info.logo,
                                                videoId = info.videoId, season = info.season, episode = info.episode,
                                                episodeTitle = info.episodeTitle, episodeDescription = info.episodeDescription,
                                                thumbnail = info.thumbnail?.takeIf { it !in brokenUrls },
                                                released = info.released, hasAired = info.hasAired, airDateLabel = info.airDateLabel,
                                                lastWatched = info.lastWatched, imdbRating = info.imdbRating, genres = info.genres,
                                                releaseInfo = info.releaseInfo, sortTimestamp = info.sortTimestamp,
                                                releaseTimestamp = info.releaseTimestamp, isReleaseAlert = info.isReleaseAlert,
                                                isNewSeasonRelease = info.isNewSeasonRelease, seedSeason = info.seedSeason,
                                                seedEpisode = info.seedEpisode, contentLanguage = info.contentLanguage
                                            )
                                        }
                                            runCatching {
                                                cwEnrichmentCache.saveNextUpSnapshot(
                                                    nextUpSnap,
                                                    profileId = saveProfileId
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (
                    initialProfilePresentation &&
                    olderNextUpDiscoveryJob != null
                ) {
                    val olderDiscoveryFinished =
                        withTimeoutOrNull(1_500L) {
                            olderNextUpDiscoveryJob?.join()
                            true
                        } == true

                    if (!olderDiscoveryFinished) {
                        olderNextUpDiscoveryJob?.cancel()
                        olderNextUpDiscoveryJob?.join()
                    }
                }

                debug.markPhase("merge-lightweight")
                // Include previously discovered older next-up items so they survive collectLatest restarts.
                val persistedOlderItems = synchronized(discoveredOlderNextUpItems) {
                    discoveredOlderNextUpItems.toList()
                }
                // Preserve cached next-up items from disk until async inject re-verifies them.
                // Drop items whose series no longer has any watched-episode seeds.
                // When seeds haven't loaded yet, keep all cached items.
                val cachedOlderNextUp = cachedNextUp
                    .filter { !snapshot.hasLoadedRemoteProgress || it.contentId in activeSeedContentIds }
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
                                releaseInfo = cached.releaseInfo,
                                sortTimestamp = recomputeSortTimestamp(cached.lastWatched, cached.releaseTimestamp, cached.isReleaseAlert),
                                releaseTimestamp = cached.releaseTimestamp,
                                isReleaseAlert = cached.isReleaseAlert,
                                isNewSeasonRelease = cached.isNewSeasonRelease,
                                seedSeason = cached.seedSeason,
                                seedEpisode = cached.seedEpisode
                            )
                        )
                    }
                val recentIds = nextUpItems.map { it.info.contentId }.toSet()
                val inProgressIds = inProgressOnly.map { it.progress.contentId }.toSet()
                // Exclude cached older items for series that the fresh pipeline evaluated
                // but didn't produce a next-up for (e.g. fully watched series).
                val rejectedByFreshPipeline = synchronized(cwLastProcessedNextUpContentIds) {
                    cwLastProcessedNextUpContentIds.toSet()
                } - recentIds
                val olderToInclude = (persistedOlderItems + cachedOlderNextUp)
                    .distinctBy { it.info.contentId }
                    .filter {
                        val isCachedFromDisk = cachedOlderNextUp.any { c -> c.info.contentId == it.info.contentId }
                        val pass =
                            (!snapshot.hasLoadedRemoteProgress || it.info.contentId in activeSeedContentIds || isCachedFromDisk) &&
                            it.info.contentId !in recentIds &&
                            it.info.contentId !in inProgressIds &&
                            // Reject items the fresh pipeline evaluated but produced no
                            // next-up for (e.g. fully watched series).  Cached-from-disk
                            // items survive only until the fresh pipeline processes their
                            // seed — once rejected there, they are removed immediately.
                            it.info.contentId !in rejectedByFreshPipeline &&
                            // Respect "show unaired" setting for all items including cached.
                            (it.info.hasAired || showUnairedNextUp) &&
                            nextUpDismissKey(it.info.contentId, it.info.seedSeason, it.info.seedEpisode) !in dismissedNextUp &&
                            !watchProgressRepository.isDroppedShow(it.info.contentId)
                        pass
                    }
                val allNextUpItems = nextUpItems + olderToInclude
                val normalItems = applyContinueWatchingEnrichmentOverlay(
                    mergeContinueWatchingItems(
                        inProgressItems = inProgressOnly,
                        nextUpItems = allNextUpItems.map { nextUp ->
                            val cached = cachedEnrichmentFromNextUp[nextUp.info.contentId]
                            if (cached != null) {
                                nextUp.copy(info = nextUp.info.copy(
                                    thumbnail = cached.thumbnail ?: nextUp.info.thumbnail,
                                    backdrop = cached.backdrop ?: nextUp.info.backdrop,
                                    poster = cached.poster ?: nextUp.info.poster,
                                    logo = cached.logo ?: nextUp.info.logo,
                                    name = cached.name.takeIf { it.isNotBlank() } ?: nextUp.info.name,
                                    episodeDescription = cached.episodeDescription ?: nextUp.info.episodeDescription,
                                    imdbRating = cached.imdbRating ?: nextUp.info.imdbRating,
                                    genres = cached.genres.ifEmpty { nextUp.info.genres },
                                    releaseInfo = cached.releaseInfo ?: nextUp.info.releaseInfo,
                                    contentLanguage = cached.contentLanguage ?: nextUp.info.contentLanguage
                                ))
                            } else nextUp
                        }
                    )
                )

                val orderedNormalItems =
                    normalItems.stableCwOrdered()

                _uiState.update { state ->
                    /*
                     * A cancelled cycle from the previous profile must never
                     * publish into the newly-selected profile.
                     */
                    if (
                        profileManager.activeProfileId.value !=
                            cycleProfileId
                    ) {
                        return@update state
                    }

                    // Do not replace a valid cached projection with an
                    // inconclusive empty result while a provider is loading.
                    if (
                        normalItems.isEmpty() &&
                        useTrackingProvider &&
                        !snapshot.hasLoadedRemoteProgress &&
                        items.isEmpty() &&
                        state.continueWatchingItems.isNotEmpty()
                    ) {
                        state
                    } else if (
                        state.continueWatchingItems ==
                            orderedNormalItems
                    ) {
                        state
                    } else {
                        state.copy(
                            continueWatchingItems =
                                orderedNormalItems
                        )
                    }
                }
                debug.recordLightweightRendered(
                    count = normalItems.size,
                    elapsedMs = SystemClock.elapsedRealtime() - cycleStartMs
                )
                // Signal that the first CW cycle completed (items or confirmed empty).
                if (!_initialCwResolved.value) {
                    val hasRealData =
                        normalItems.isNotEmpty() ||
                            !useTrackingProvider ||
                            snapshot.hasLoadedRemoteProgress
                    if (hasRealData) {
                        _initialCwResolved.value = true
                    }
                }

                // Save lightweight CW snapshot to disk immediately so cache stays fresh
                // even if enrichment is cancelled by collectLatest.
                val enrichmentSnapshotProfileId = cycleProfileId
                if (profileManager.activeProfileId.value == enrichmentSnapshotProfileId) {
                    val enrichmentSnapshotItems = normalItems.toList()
                    viewModelScope.launch(Dispatchers.IO) {
                        val currentItems = enrichmentSnapshotItems
                    val brokenUrls = com.nuvio.tv.ui.components.brokenImageUrls
                    val nextUpSnap = currentItems.mapNotNull { item ->
                        val nu = item as? ContinueWatchingItem.NextUp ?: return@mapNotNull null
                        val info = nu.info
                        com.nuvio.tv.data.local.CachedNextUpItem(
                            contentId = info.contentId, contentType = info.contentType, name = info.name,
                            poster = info.poster, backdrop = info.backdrop, logo = info.logo,
                            videoId = info.videoId, season = info.season, episode = info.episode,
                            episodeTitle = info.episodeTitle, episodeDescription = info.episodeDescription,
                            thumbnail = info.thumbnail?.takeIf { it !in brokenUrls },
                            released = info.released, hasAired = info.hasAired, airDateLabel = info.airDateLabel,
                            lastWatched = info.lastWatched, imdbRating = info.imdbRating, genres = info.genres,
                            releaseInfo = info.releaseInfo, sortTimestamp = info.sortTimestamp,
                            releaseTimestamp = info.releaseTimestamp, isReleaseAlert = info.isReleaseAlert,
                            isNewSeasonRelease = info.isNewSeasonRelease, seedSeason = info.seedSeason,
                            seedEpisode = info.seedEpisode, contentLanguage = info.contentLanguage
                        )
                    }
                    val ipSnap = currentItems.mapNotNull { item ->
                        val ip = item as? ContinueWatchingItem.InProgress ?: return@mapNotNull null
                        val p = ip.progress
                        com.nuvio.tv.data.local.CachedInProgressItem(
                            contentId = p.contentId, contentType = p.contentType, name = p.name,
                            poster = p.poster, backdrop = p.backdrop, logo = p.logo,
                            videoId = p.videoId, season = p.season, episode = p.episode,
                            episodeTitle = p.episodeTitle, position = p.position, duration = p.duration,
                            lastWatched = p.lastWatched, progressPercent = p.progressPercent,
                            episodeThumbnail = ip.episodeThumbnail?.takeIf { it !in brokenUrls },
                            episodeDescription = ip.episodeDescription, episodeImdbRating = ip.episodeImdbRating,
                            genres = ip.genres, releaseInfo = ip.releaseInfo,
                            contentLanguage = ip.contentLanguage
                        )
                    }
                        runCatching {
                            cwEnrichmentCache.saveNextUpSnapshot(
                                nextUpSnap,
                                force = true,
                                profileId = enrichmentSnapshotProfileId
                            )
                        }
                        runCatching {
                            cwEnrichmentCache.saveInProgressSnapshot(
                                ipSnap,
                                force = true,
                                profileId = enrichmentSnapshotProfileId
                            )
                        }
                    }
                }

                // Refresh home screen channel with lightweight data immediately.
                // This ensures the channel is updated even if enrichment is cancelled
                // by collectLatest restarting the pipeline (e.g. Trakt data arriving).
                // Enrichment will call refreshFromItems again with richer data if it completes.
                if (normalItems.isNotEmpty()) {
                    val lwProfileId = profileManager.activeProfileId.value
                    val lwProfileName = profileManager.activeProfile?.name ?: "Profile $lwProfileId"
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching { homeScreenChannelManager.refreshFromItems(normalItems, lwProfileId, lwProfileName) }
                    }
                }

                // Rich metadata only runs after the final lightweight CW list is visible.
                // If TMDB enrichment is enabled for CW, skip grace period to avoid
                // visible flash of addon data being replaced by TMDB data.
                debug.markPhase("enrichment-grace")
                val tmdbEnrichCw = currentTmdbSettings.enabled && currentTmdbSettings.enrichContinueWatching
                val enrichmentDelayMs = if (tmdbEnrichCw) 0L else remainingContinueWatchingEnrichmentGraceMs()
                debug.recordEnrichmentDelay(enrichmentDelayMs)
                if (enrichmentDelayMs > 0L) {
                    delay(enrichmentDelayMs)
                }

                debug.markPhase("enrich-visible-items")
                val enrichStartMs = SystemClock.elapsedRealtime()
                val changed = enrichVisibleContinueWatchingItems(
                    finalItems = normalItems,
                    debug = debug
                )
                debug.recordEnrichmentComplete(
                    elapsedMs = SystemClock.elapsedRealtime() - enrichStartMs,
                    changed = changed
                )

                /*
                 * Profile-switch presentation boundary:
                 *
                 * 1. remote tracking state is authoritative
                 * 2. bounded older Next Up discovery has had its chance
                 * 3. the resulting CW list has been merged
                 * 4. visible cards have received final enrichment/artwork
                 *
                 * Only now may the Home curtain reveal this profile.
                 */
                if (authoritativeProfileCycle) {
                    _uiState.update { state ->
                        if (
                            profileManager.activeProfileId.value !=
                                cycleProfileId ||
                            state.continueWatchingFreshReady
                        ) {
                            state
                        } else {
                            state.copy(
                                continueWatchingFreshReady = true
                            )
                        }
                    }
                }

                // Only a CW cycle that survives collectLatest cancellation all the
                // way through enrichment may settle a Player-return transaction.
                // Intermediate/cancelled cycles intentionally leave it active so
                // the next recomputation continues owning the same generation.
                val completedReturnGeneration =
                    playerReturnCwActiveGeneration

                if (completedReturnGeneration > 0L) {
                    settlePlayerReturnCwTransaction(
                        completedReturnGeneration
                    )
                }

                debug.markPhase("completed")
                debug.logSummary()
            } catch (cancelled: CancellationException) {
                debug.logSummary(cancelled = true)
                throw cancelled
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
    if (progress.isInProgress()) {
        return true
    }
    if (progress.isCompleted()) {
        return false
    }
    // Rewatch edge case: a started replay can be below the default 2% "in progress"
    // threshold, but should still suppress Next Up and appear as resume.
    val hasStartedPlayback = progress.position > 0L || progress.progressPercent?.let { it > 0f } == true
    val result = hasStartedPlayback &&
        progress.source != WatchProgress.SOURCE_TRAKT_HISTORY &&
        progress.source != WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS
    return result
}

private fun HomeViewModel.shouldUseAsCompletedSeed(
    progress: WatchProgress
): Boolean {
    if (isMalformedNextUpSeedContentId(progress.contentId)) return false
    return watchProgressRepository.shouldUseAsNextUpSeed(
        progress = progress,
        nowEpochMs = System.currentTimeMillis()
    )
}

internal fun latestCompletedAtByContentForSuppression(
    allProgress: List<WatchProgress>,
    nextUpSeeds: List<WatchProgress>,
    isCompletedSeed: (WatchProgress) -> Boolean
): Map<String, Long> {
    return (allProgress.asSequence() + nextUpSeeds.asSequence())
        .filter { isSeriesTypeCW(it.contentType) }
        .filter { it.contentId.isNotBlank() }
        .filter(isCompletedSeed)
        .groupBy { it.contentId }
        .mapValues { (_, items) ->
            items.maxOfOrNull { it.lastWatched } ?: Long.MIN_VALUE
        }
}

internal fun shouldTreatAsActiveInProgressForNextUpSuppression(
    progress: WatchProgress,
    latestCompletedAt: Long?
): Boolean {
    if (!shouldTreatAsInProgressForContinueWatching(progress)) return false
    if (latestCompletedAt == null || latestCompletedAt == Long.MIN_VALUE) return true
    return progress.lastWatched >= latestCompletedAt
}

private fun logNextUpDecision(message: String) = Unit

private fun nextUpSeedSourceRank(progress: WatchProgress): Int {
    return when (progress.source) {
        WatchProgress.SOURCE_TRAKT_PLAYBACK -> 0
        WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS -> 0
        WatchProgress.SOURCE_TRAKT_HISTORY -> 1
        WatchProgress.SOURCE_LOCAL -> 2
        else -> 4
    }
}

private fun isMalformedNextUpSeedContentId(contentId: String?): Boolean {
    val trimmed = contentId?.trim().orEmpty()
    if (trimmed.isEmpty()) return true
    return when (trimmed.lowercase(Locale.US)) {
        "tmdb", "imdb", "trakt", "tmdb:", "imdb:", "trakt:" -> true
        else -> false
    }
}

private fun choosePreferredNextUpSeed(items: List<WatchProgress>): WatchProgress? {
    if (items.isEmpty()) return null
    val bestRank = items.minOf(::nextUpSeedSourceRank)
    return items
        .asSequence()
        .filter { nextUpSeedSourceRank(it) == bestRank }
        .maxWithOrNull(
            compareBy<WatchProgress>(
                { it.season ?: -1 },
                { it.episode ?: -1 },
                { it.lastWatched }
            )
        )
}

private suspend fun HomeViewModel.resolveCurrentEpisodeDescription(
    progress: WatchProgress,
    meta: CwMetaSummary,
    video: CwVideoSummary?,
    debug: CwDebugSession? = null
): String? {
    if (isSeriesTypeCW(progress.contentType)) {
        if (video != null) {
            val season = video.season
            val episode = video.episode
            val episodeOverview = video.overview?.takeIf { it.isNotBlank() }
            if (episodeOverview != null) return episodeOverview
            if (season != null && episode != null && currentTmdbSettings.enabled) {
                val tmdbId = resolveTmdbIdForNextUp(progress, meta, debug)
                if (tmdbId != null) {
                    val tmdbStartedAtMs = SystemClock.elapsedRealtime()
                    val tmdbOverview = runCatching {
                        tmdbMetadataService.fetchEpisodeEnrichment(
                            tmdbId = tmdbId,
                            seasonNumbers = listOf(season),
                            language = currentTmdbSettings.language
                        )[season to episode]?.overview
                    }.getOrNull()
                    debug?.recordTmdbCall(
                        kind = "current-episode-description",
                        elapsedMs = SystemClock.elapsedRealtime() - tmdbStartedAtMs,
                        success = !tmdbOverview.isNullOrBlank()
                    )
                    if (!tmdbOverview.isNullOrBlank()) return tmdbOverview
                }
            }
        }
    }
    return meta.description?.takeIf { it.isNotBlank() }
}

private fun resolveVideoForProgress(progress: WatchProgress, meta: CwMetaSummary): CwVideoSummary? {
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

private suspend fun HomeViewModel.buildLightweightNextUpItems(
    allProgress: List<WatchProgress>,
    nextUpSeeds: List<WatchProgress>,
    inProgressItems: List<ContinueWatchingItem.InProgress>,
    dismissedNextUp: Set<String>,
    showUnairedNextUp: Boolean,
    debug: CwDebugSession? = null,
    onPartialUpdate: suspend (List<ContinueWatchingItem.NextUp>) -> Unit = {}
): List<ContinueWatchingItem.NextUp> = coroutineScope {
    val latestCompletedByContent = latestCompletedAtByContentForSuppression(
        allProgress = allProgress,
        nextUpSeeds = nextUpSeeds,
        isCompletedSeed = ::shouldUseAsCompletedSeed
    )

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

    val latestCompletedBySeries = nextUpSeeds
        .filter { progress ->
            isSeriesTypeCW(progress.contentType) &&
                progress.season != null &&
                progress.episode != null &&
                progress.season != 0 &&
                shouldUseAsCompletedSeed(progress)
        }
        .groupBy { it.contentId }
        .mapNotNull { (_, items) ->
            choosePreferredNextUpSeed(items)
        }
        .filter { it.contentId !in inProgressIds }
        .filter { progress ->
            nextUpDismissKey(progress.contentId, progress.season, progress.episode) !in dismissedNextUp
        }
        .sortedByDescending { it.lastWatched }
        // Skip seeds validated as "no next-up" ONLY if the seed hasn't changed.
        // The cache key includes season+episode, so a changed seed (user watched
        // a new episode) produces a cache miss and is always processed.
        .filter { progress ->
            val cacheKey = buildNextUpSeedCacheKey(progress, showUnairedNextUp)
            val inCache = synchronized(cwNextUpResolutionCache) {
                cwNextUpResolutionCache.containsKey(cacheKey)
            }
            if (!inCache) return@filter true // cache miss — seed changed or first time
            val cachedValue = synchronized(cwNextUpResolutionCache) {
                cwNextUpResolutionCache[cacheKey]
            }
            if (cachedValue != null) return@filter true // positive hit — has next-up
            // Negative hit (no next-up) — skip if TTL is fresh
            !fullyWatchedSeriesIds.isSeriesValidationFresh(progress.contentId)
        }
        .take(CW_MAX_NEXT_UP_LOOKUPS)

    if (latestCompletedBySeries.isEmpty()) {
        return@coroutineScope emptyList()
    }

    val lookupSemaphore = Semaphore(CW_MAX_NEXT_UP_CONCURRENCY)
    val mergeMutex = Mutex()
    val nextUpByContent = linkedMapOf<String, ContinueWatchingItem.NextUp>()
    val processedContentIds = Collections.synchronizedSet(mutableSetOf<String>())
    val resolvedSinceLastPublish = java.util.concurrent.atomic.AtomicInteger(0)
    // Batch partial updates: publish every N resolved items instead of after each one.
    val partialPublishBatchSize = (latestCompletedBySeries.size / 3).coerceIn(2, 8)

    val jobs = latestCompletedBySeries.map { progress ->
        launch(Dispatchers.IO) {
            lookupSemaphore.withPermit {
                /*
                 * A completed lookup with no successor is still authoritative.
                 * Mark it as processed so an older cached Next Up card cannot
                 * be merged back after the series finale.
                 */
                processedContentIds.add(progress.contentId)
                val nextUp = buildNextUpItem(
                    progress = progress,
                    showUnairedNextUp = showUnairedNextUp,
                    debug = debug
                ) ?: run {
                    val seedResolved = hasResolvedNextUpSeed(progress)
                    if (!seedResolved) {
                        processedContentIds.remove(progress.contentId)
                    }
                    logNextUpDecision(
                        "drop contentId=${progress.contentId} name=${progress.name} " +
                            "reason=buildNextUpItem-null seedResolved=$seedResolved"
                    )
                    return@withPermit
                }
                val shouldPublish: Boolean
                val partialItems = mergeMutex.withLock {
                    nextUpByContent[progress.contentId] = nextUp
                    val count = resolvedSinceLastPublish.incrementAndGet()
                    shouldPublish = count >= partialPublishBatchSize
                    if (shouldPublish) resolvedSinceLastPublish.set(0)
                    if (shouldPublish) nextUpByContent.values.toList() else emptyList()
                }
                if (shouldPublish) {
                    onPartialUpdate(partialItems)
                }
            }
        }
    }
    jobs.joinAll()

    // Store which contentIds were evaluated so olderToInclude can skip fully-watched series.
    synchronized(cwLastProcessedNextUpContentIds) {
        cwLastProcessedNextUpContentIds.clear()
        cwLastProcessedNextUpContentIds.addAll(processedContentIds)
    }

    nextUpByContent.values.toList()
}

private suspend fun HomeViewModel.enrichVisibleContinueWatchingItems(
    finalItems: List<ContinueWatchingItem>,
    debug: CwDebugSession? = null
): Boolean = coroutineScope {
    if (finalItems.isEmpty()) return@coroutineScope false

    val metaCache = cwMetaCache
    val enrichmentSemaphore = Semaphore(CW_MAX_ENRICHMENT_CONCURRENCY)
    val enrichedItems = finalItems
        .mapIndexed { index, item ->
            async(Dispatchers.IO) {
                enrichmentSemaphore.withPermit {
                    index to when (item) {
                        is ContinueWatchingItem.InProgress -> enrichInProgressItem(item, metaCache, debug)
                        is ContinueWatchingItem.NextUp -> enrichNextUpItem(item, metaCache, debug)
                    }
                }
            }
        }
        .awaitAll()
        .sortedBy { it.first }
        .map { it.second }

    if (enrichedItems == finalItems) return@coroutineScope false

    // Save enriched next-up info to in-memory overlay so the next CW cycle's
    // cached/partial/normal emissions use enriched data from the start,
    // preventing title/thumbnail flickering between addon and TMDB values.
    enrichedItems.forEach { item ->
        when (item) {
            is ContinueWatchingItem.NextUp -> {
                cwEnrichedNextUpOverlay[item.info.contentId] = item.info
            }
            is ContinueWatchingItem.InProgress -> {
                cwEnrichedInProgressOverlay[item.progress.contentId] = item
            }
        }
    }

    _uiState.update { state ->
        val updatedItems: List<ContinueWatchingItem> = if (state.continueWatchingItems == enrichedItems) state.continueWatchingItems else enrichedItems
        val updatedReady: Boolean = true
        state.copy(continueWatchingItems = updatedItems.stableCwOrdered(), continueWatchingEnrichmentReady = updatedReady)
    }
    persistLocalContinueWatchingMetadata(
        originalItems = finalItems,
        enrichedItems = enrichedItems
    )
    // Refresh home screen channel after enrichment (logos/thumbnails now available).
    // Always use the full uiState item list — not just enrichedItems — so the channel
    // reflects all current CW items, not only those that changed in this enrichment pass.
    val channelItems = _uiState.value.continueWatchingItems
    if (channelItems.isNotEmpty()) {
        val profileId = profileManager.activeProfileId.value
        val profileName = profileManager.activeProfile?.name ?: "Profile $profileId"
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { homeScreenChannelManager.refreshFromItems(channelItems, profileId, profileName) }
        }
    }
    true
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
    filteredNextUpItems.forEach { combined.add(it.info.sortTimestamp to it) }

    val seen = mutableSetOf<String>()
    val result = combined
        .sortedByDescending { it.first }
        .map { it.second }
        .filter { item ->
            val contentId = when (item) {
                is ContinueWatchingItem.InProgress -> item.progress.contentId
                is ContinueWatchingItem.NextUp -> item.info.contentId
            }
            contentId.isBlank() || seen.add(contentId)
        }

    return result
}

private suspend fun HomeViewModel.buildNextUpItem(
    progress: WatchProgress,
    showUnairedNextUp: Boolean,
    debug: CwDebugSession? = null
): ContinueWatchingItem.NextUp? {
    debug?.recordNextUpAttempt(progress)
    val nextUp = findNextUpEpisodeFromMetaSeed(
        progress = progress,
        showUnairedNextUp = showUnairedNextUp,
        debug = debug
    ) ?: run {
        // Populate badge episode cache from meta that was already resolved by
        // findNextUpEpisodeFromMetaSeed — avoids duplicate meta fetch in badge pipeline.
        val cachedMeta = synchronized(cwMetaCache) {
            cwMetaCache["${progress.contentType}:${progress.contentId}"]
                ?: cwMetaCache["series:${progress.contentId}"]
                ?: cwMetaCache["tv:${progress.contentId}"]
        }
        val seedResolved = hasResolvedNextUpSeed(
            cachedMeta,
            progress.season,
            progress.episode
        )
        if (cachedMeta != null) {
            val episodes = cachedMeta.releasedRegularEpisodeCoordinates()
            val cacheKey = "series:${progress.contentId}"
            synchronized(cwBadgeEpisodeCache) {
                if (!cwBadgeEpisodeCache.containsKey(cacheKey)) {
                    cwBadgeEpisodeCache[cacheKey] = episodes
                }
            }
            cachedBadgeSeriesStatus(
                contentId = progress.contentId,
                summary = cachedMeta
            )?.let { status ->
                synchronized(cwBadgeSeriesStatusCache) {
                    cwBadgeSeriesStatusCache[cacheKey] = status
                }
            }
            val nextSeasonMs = cachedMeta.earliestUpcomingSeasonMs()
            if (nextSeasonMs != null) {
                cwBadgeNextSeasonMs[progress.contentId] = nextSeasonMs
            } else {
                cwBadgeNextSeasonMs.remove(progress.contentId)
            }
        }
        if (seedResolved) {
            // The seed exists in resolved metadata, so no eligible successor is
            // authoritative. Missing or mismatched metadata remains retryable.
            val nextSeasonMs = cwBadgeNextSeasonMs[progress.contentId]
            val deadline = nextSeasonMs
                ?: (System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000)
            fullyWatchedSeriesIds.updateWithValidation(
                fullyWatchedSeriesIds.fullyWatchedSeriesIds.value,
                setOf(progress.contentId),
                mapOf(progress.contentId to deadline)
            )
        }
        return null
    }
    val seedMeta = resolveMetaForProgress(progress, cwMetaCache, debug)

    val name = progress.name.trim().takeIf { it.isNotEmpty() }
        ?: seedMeta?.name
        ?: progress.contentId
    val releaseState = resolveNextUpReleaseState(
        seedProgress = progress,
        nextSeason = nextUp.season,
        nextReleased = nextUp.released,
        hasAired = nextUp.hasAired
    )
    // Suppress release alert if the next episode is already in local watched state
    val nextEpisodeAlreadyWatched = runCatching {
        watchedItemsPreferences.getWatchedEpisodesForContent(progress.contentId)
            .first()
            .contains(nextUp.season to nextUp.episode)
    }.getOrDefault(false)
    val effectiveReleaseState = if (nextEpisodeAlreadyWatched) {
        releaseState.copy(isReleaseAlert = false, isNewSeasonRelease = false)
    } else {
        releaseState
    }
    val nextUpVideo = seedMeta?.videos?.firstOrNull {
        it.season == nextUp.season && it.episode == nextUp.episode
    }
    val info = NextUpInfo(
        contentId = progress.contentId,
        contentType = progress.contentType,
        name = name,
        poster = progress.poster.normalizeImageUrl() ?: seedMeta?.poster.normalizeImageUrl(),
        backdrop = progress.backdrop.normalizeImageUrl() ?: seedMeta?.backdropUrl.normalizeImageUrl(),
        logo = progress.logo.normalizeImageUrl() ?: seedMeta?.logo.normalizeImageUrl(),
        videoId = nextUp.videoId,
        season = nextUp.season,
        episode = nextUp.episode,
        episodeTitle = nextUp.episodeTitle ?: nextUpVideo?.title,
        episodeDescription = nextUpVideo?.overview,
        thumbnail = nextUpVideo?.thumbnail.normalizeImageUrl(),
        released = nextUp.released,
        hasAired = nextUp.hasAired,
        airDateLabel = nextUp.airDateLabel,
        lastWatched = nextUp.lastWatched,
        imdbRating = null,
        genres = emptyList(),
        releaseInfo = null,
        sortTimestamp = effectiveReleaseState.sortTimestamp,
        releaseTimestamp = effectiveReleaseState.releaseTimestamp,
        isReleaseAlert = effectiveReleaseState.isReleaseAlert,
        isNewSeasonRelease = effectiveReleaseState.isNewSeasonRelease,
        seedSeason = progress.season,
        seedEpisode = progress.episode
    )
    logNextUpDecision(
        "built contentId=${progress.contentId} name=${progress.name} next=${nextUp.season}x${nextUp.episode} " +
            "videoId=${nextUp.videoId} lastWatched=${nextUp.lastWatched}"
    )
    return ContinueWatchingItem.NextUp(info)
}

private suspend fun HomeViewModel.enrichInProgressItem(
    item: ContinueWatchingItem.InProgress,
    metaCache: MutableMap<String, CwMetaSummary?>,
    debug: CwDebugSession? = null
): ContinueWatchingItem.InProgress = coroutineScope {
    val shouldEnrichTmdb = currentTmdbSettings.enabled && currentTmdbSettings.enrichContinueWatching

    // Start TMDB ID resolve early (cache hit = instant, cache miss = network)
    val tmdbIdDeferred = if (shouldEnrichTmdb) {
        async(Dispatchers.IO) {
            val cacheKey = "${item.progress.contentType}:${item.progress.contentId}"
            synchronized(cwTmdbIdCache) { cwTmdbIdCache[cacheKey] }
                ?: runCatching { tmdbService.ensureTmdbId(item.progress.contentId, item.progress.contentType) }.getOrNull()
        }
    } else null

    val meta = resolveMetaForProgress(item.progress, metaCache, debug)
    if (meta == null) {
        return@coroutineScope item
    }
    val video = resolveVideoForProgress(item.progress, meta)
    val genres = meta.genres.take(3)
    val releaseInfo = meta.releaseInfo?.takeIf { it.isNotBlank() }
    val tmdbData = if (shouldEnrichTmdb) {
        // Use early-resolved TMDB ID if available, otherwise fall back to full resolve
        val earlyTmdbId = tmdbIdDeferred?.await()
        if (earlyTmdbId != null) {
            // Cache the early result
            val cacheKey = "${item.progress.contentType}:${item.progress.contentId}"
            synchronized(cwTmdbIdCache) { cwTmdbIdCache[cacheKey] = earlyTmdbId }
        }
        resolveContinueWatchingTmdbData(
            progress = item.progress,
            meta = meta,
            season = item.progress.season ?: 1,
            episode = item.progress.episode ?: 1,
            debug = debug
        )
    } else null
    val imdbRating = meta.imdbRating ?: item.episodeImdbRating
    val settings = currentTmdbSettings
    item.copy(
        progress = item.progress.copy(
            name = if (settings.useBasicInfo) tmdbData?.name ?: meta.name else meta.name,
            poster = item.progress.poster ?: meta.poster.normalizeImageUrl() ?: if (settings.useArtwork) tmdbData?.poster.normalizeImageUrl() else null,
            backdrop = if (settings.useArtwork) tmdbData?.backdrop.normalizeImageUrl() ?: meta.backdropUrl.normalizeImageUrl() ?: item.progress.backdrop else meta.backdropUrl.normalizeImageUrl() ?: item.progress.backdrop,
            logo = if (settings.useArtwork) tmdbData?.logo.normalizeImageUrl() ?: meta.logo.normalizeImageUrl() ?: item.progress.logo else meta.logo.normalizeImageUrl() ?: item.progress.logo,
            episodeTitle = if (settings.useEpisodes) tmdbData?.episodeTitle
                ?: video?.title?.takeIf { it.isNotBlank() }
                ?: item.progress.episodeTitle
            else video?.title?.takeIf { it.isNotBlank() } ?: item.progress.episodeTitle
        ),
        episodeDescription = if (settings.useEpisodes) tmdbData?.overview
            ?: video?.overview?.takeIf { it.isNotBlank() }
            ?: item.episodeDescription
        else video?.overview?.takeIf { it.isNotBlank() } ?: item.episodeDescription,
        episodeThumbnail = if (settings.useEpisodes) tmdbData?.thumbnail ?: video?.thumbnail.normalizeImageUrl() ?: item.episodeThumbnail else video?.thumbnail.normalizeImageUrl() ?: item.episodeThumbnail,
        episodeImdbRating = imdbRating,
        genres = genres,
        releaseInfo = releaseInfo,
        contentLanguage = tmdbData?.contentLanguage
            ?: normalizeLanguageCode(meta.language)
            ?: countryToLanguageCode(meta.country)
            ?: item.contentLanguage
    )
}

private suspend fun HomeViewModel.enrichNextUpItem(
    item: ContinueWatchingItem.NextUp,
    metaCache: MutableMap<String, CwMetaSummary?>,
    debug: CwDebugSession? = null
): ContinueWatchingItem.NextUp = coroutineScope {
    val progressSeed = item.info.toProgressSeed()
    val shouldEnrichTmdb = currentTmdbSettings.enabled && currentTmdbSettings.enrichContinueWatching

    // Start TMDB ID resolve early (cache hit = instant, cache miss = network)
    val tmdbIdDeferred = if (shouldEnrichTmdb) {
        async(Dispatchers.IO) {
            val cacheKey = "${progressSeed.contentType}:${progressSeed.contentId}"
            synchronized(cwTmdbIdCache) { cwTmdbIdCache[cacheKey] }
                ?: runCatching { tmdbService.ensureTmdbId(progressSeed.contentId, progressSeed.contentType) }.getOrNull()
        }
    } else null

    val meta = resolveMetaForProgress(progressSeed, metaCache, debug) ?: return@coroutineScope item
    val video = resolveNextUpVideoFromMeta(progressSeed, meta)

    val tmdbData = if (shouldEnrichTmdb) {
        val earlyTmdbId = tmdbIdDeferred?.await()
        if (earlyTmdbId != null) {
            val cacheKey = "${progressSeed.contentType}:${progressSeed.contentId}"
            synchronized(cwTmdbIdCache) { cwTmdbIdCache[cacheKey] = earlyTmdbId }
        }
        resolveContinueWatchingTmdbData(
            progress = progressSeed,
            meta = meta,
            season = video?.season ?: item.info.season,
            episode = video?.episode ?: item.info.episode,
            debug = debug
        )
    } else {
        null
    }
    // Normalize air dates to plain YYYY-MM-DD to avoid timezone boundary issues
    // where e.g. "2025-05-22T00:00:00.000Z" shifts to May 21 in local time.
    val dateOnlyRegex = Regex("""\d{4}-\d{2}-\d{2}""")
    val normalizedTmdbAirDate = tmdbData?.airDate?.trim()?.let { raw ->
        dateOnlyRegex.find(raw)?.value ?: raw
    }
    val released = (if (currentTmdbSettings.useReleaseDates) normalizedTmdbAirDate else null)
        ?: video?.released?.trim()?.let { raw ->
            dateOnlyRegex.find(raw)?.value ?: raw
        }?.takeIf { it.isNotEmpty() }
        ?: item.info.released
    val releaseDate = parseEpisodeReleaseDate(released)
    val todayLocal = LocalDate.now(ZoneId.systemDefault())
    val hasAired = releaseDate?.let { !it.isAfter(todayLocal) } ?: item.info.hasAired
    val releaseState = resolveNextUpReleaseState(
        seedProgress = progressSeed,
        nextSeason = video?.season ?: item.info.season,
        nextReleased = released,
        hasAired = hasAired
    )
    val nextEpisodeAlreadyWatchedEnrich = runCatching {
        val nextSeason = video?.season ?: item.info.season
        val nextEpisode = video?.episode ?: item.info.episode
        watchedItemsPreferences.getWatchedEpisodesForContent(progressSeed.contentId)
            .first()
            .contains(nextSeason to nextEpisode)
    }.getOrDefault(false)
    val effectiveReleaseState = if (nextEpisodeAlreadyWatchedEnrich) {
        releaseState.copy(isReleaseAlert = false, isNewSeasonRelease = false)
    } else {
        releaseState
    }

    val settings = currentTmdbSettings
    val enrichedInfo = item.info.copy(
        name = if (settings.useBasicInfo) tmdbData?.name ?: meta.name else meta.name,
        poster = item.info.poster ?: meta.poster.normalizeImageUrl() ?: if (settings.useArtwork) tmdbData?.poster else null,
        backdrop = if (settings.useArtwork) tmdbData?.backdrop ?: meta.backdropUrl.normalizeImageUrl() ?: item.info.backdrop else meta.backdropUrl.normalizeImageUrl() ?: item.info.backdrop,
        logo = if (settings.useArtwork) tmdbData?.logo ?: meta.logo.normalizeImageUrl() ?: item.info.logo else meta.logo.normalizeImageUrl() ?: item.info.logo,
        season = video?.season ?: item.info.season,
        episode = video?.episode ?: item.info.episode,
        videoId = video?.id?.takeIf { it.isNotBlank() } ?: item.info.videoId,
        episodeTitle = if (settings.useEpisodes) tmdbData?.episodeTitle
            ?: video?.title?.takeIf { it.isNotBlank() }
            ?: item.info.episodeTitle
        else video?.title?.takeIf { it.isNotBlank() } ?: item.info.episodeTitle,
        episodeDescription = if (settings.useEpisodes) tmdbData?.overview
            ?: video?.overview?.takeIf { it.isNotBlank() }
            ?: item.info.episodeDescription
        else video?.overview?.takeIf { it.isNotBlank() } ?: item.info.episodeDescription,
        thumbnail = if (settings.useEpisodes) tmdbData?.thumbnail ?: video?.thumbnail.normalizeImageUrl() ?: item.info.thumbnail else video?.thumbnail.normalizeImageUrl() ?: item.info.thumbnail,
        released = released,
        hasAired = hasAired,
        airDateLabel = if (hasAired || releaseDate == null) null else formatEpisodeAirDateLabel(releaseDate),
        imdbRating = meta.imdbRating ?: item.info.imdbRating,
        genres = meta.genres.take(3).ifEmpty { item.info.genres },
        releaseInfo = meta.releaseInfo?.takeIf { it.isNotBlank() } ?: item.info.releaseInfo,
        sortTimestamp = item.info.sortTimestamp,
        releaseTimestamp = effectiveReleaseState.releaseTimestamp,
        isReleaseAlert = effectiveReleaseState.isReleaseAlert,
        isNewSeasonRelease = effectiveReleaseState.isNewSeasonRelease,
        contentLanguage = tmdbData?.contentLanguage
            ?: normalizeLanguageCode(meta.language)
            ?: countryToLanguageCode(meta.country)
            ?: item.info.contentLanguage
    )
    item.copy(info = enrichedInfo)
}

private suspend fun HomeViewModel.findNextUpEpisodeFromMetaSeed(
    progress: WatchProgress,
    showUnairedNextUp: Boolean,
    debug: CwDebugSession? = null
): NextUpResolution? {
    val startedAtMs = SystemClock.elapsedRealtime()
    val cacheKey = buildNextUpSeedCacheKey(progress, showUnairedNextUp)
    synchronized(cwNextUpResolutionCache) {
        if (cwNextUpResolutionCache.containsKey(cacheKey)) {
            val cached = cwNextUpResolutionCache[cacheKey]
            if (cached != null) {
                debug?.recordNextUpCacheHit(
                    progress = progress,
                    resolved = true,
                    showUnairedNextUp = showUnairedNextUp
                )
                return cached
            }
            // Negative cache entry — check TTL
            val negativeCachedAt = cwNextUpNegativeCacheTimestamps[cacheKey]
            if (negativeCachedAt != null &&
                SystemClock.elapsedRealtime() - negativeCachedAt < CW_META_NEGATIVE_CACHE_TTL_MS
            ) {
                debug?.recordNextUpCacheHit(
                    progress = progress,
                    resolved = false,
                    showUnairedNextUp = showUnairedNextUp
                )
                return null
            }
            // TTL expired — retry
            cwNextUpResolutionCache.remove(cacheKey)
            cwNextUpNegativeCacheTimestamps.remove(cacheKey)
        }
    }
    val contentId = progress.contentId
    val season = progress.season
    val episode = progress.episode
    if (season == null || episode == null || season == 0) {
        debug?.recordNextUpResult(
            progress = progress,
            reason = "missing-seed-season-episode",
            elapsedMs = SystemClock.elapsedRealtime() - startedAtMs,
            resolved = false
        )
        logNextUpDecision(
            "drop contentId=$contentId name=${progress.name} reason=missing-seed-season-episode " +
                "seed=${progress.season}x${progress.episode}"
        )
        synchronized(cwNextUpResolutionCache) {
            cwNextUpResolutionCache.remove(cacheKey)
            cwNextUpNegativeCacheTimestamps.remove(cacheKey)
        }
        return null
    }

    val meta = resolveMetaForProgress(progress, cwMetaCache, debug) ?: run {
        debug?.recordNextUpResult(
            progress = progress,
            reason = "no-meta-for-seed",
            elapsedMs = SystemClock.elapsedRealtime() - startedAtMs,
            resolved = false
        )
        logNextUpDecision("drop contentId=$contentId name=${progress.name} reason=no-meta-for-seed")
        synchronized(cwNextUpResolutionCache) {
            cwNextUpResolutionCache.remove(cacheKey)
            cwNextUpNegativeCacheTimestamps.remove(cacheKey)
        }
        return null
    }
    val nextVideo = resolveNextUpVideoFromMeta(progress, meta, showUnairedNextUp) ?: run {
        debug?.recordNextUpResult(
            progress = progress,
            reason = "no-next-video-after-seed",
            elapsedMs = SystemClock.elapsedRealtime() - startedAtMs,
            resolved = false
        )
        val seedResolved = hasResolvedNextUpSeed(meta, season, episode)
        synchronized(cwNextUpResolutionCache) {
            if (seedResolved) {
                cwNextUpResolutionCache[cacheKey] = null
                cwNextUpNegativeCacheTimestamps[cacheKey] = SystemClock.elapsedRealtime()
            } else {
                cwNextUpResolutionCache.remove(cacheKey)
                cwNextUpNegativeCacheTimestamps.remove(cacheKey)
            }
        }
        return null
    }

    val nextSeason = nextVideo.season ?: return null
    val nextEpisode = nextVideo.episode ?: return null
    val resolution = NextUpResolution(
        season = nextSeason,
        episode = nextEpisode,
        videoId = nextVideo.id.takeIf { it.isNotBlank() }
            ?: buildLightweightEpisodeVideoId(
                contentId,
                nextSeason,
                nextEpisode
            ),
        episodeTitle = nextVideo.title?.takeIf { it.isNotBlank() },
        released = nextVideo.released?.trim()?.takeIf { it.isNotBlank() },
        hasAired = run {
            val rawReleased = nextVideo.released?.trim()?.takeIf { it.isNotBlank() }
            val normalizedReleased = rawReleased?.let { raw ->
                Regex("""\d{4}-\d{2}-\d{2}""").find(raw)?.value ?: raw
            }
            normalizedReleased?.let(::parseEpisodeReleaseDate)?.let { !it.isAfter(LocalDate.now(ZoneId.systemDefault())) } ?: true
        },
        airDateLabel = run {
            val rawReleased = nextVideo.released?.trim()?.takeIf { it.isNotBlank() }
            val normalizedReleased = rawReleased?.let { raw ->
                Regex("""\d{4}-\d{2}-\d{2}""").find(raw)?.value ?: raw
            }
            normalizedReleased?.let(::parseEpisodeReleaseDate)?.takeIf { it.isAfter(LocalDate.now(ZoneId.systemDefault())) }?.let(::formatEpisodeAirDateLabel)
        },
        lastWatched = progress.lastWatched
    )
    debug?.recordNextUpResult(
        progress = progress,
        reason = "resolved",
        elapsedMs = SystemClock.elapsedRealtime() - startedAtMs,
        resolved = true
    )
    synchronized(cwNextUpResolutionCache) {
        cwNextUpResolutionCache[cacheKey] = resolution
    }
    return resolution
}

private fun resolveNextUpVideoFromMeta(
    progress: WatchProgress,
    meta: CwMetaSummary
): CwVideoSummary? = resolveNextUpVideoFromMeta(progress, meta, showUnairedNextUp = true)

private const val CW_NEXT_UP_NEW_SEASON_UNAIRED_WINDOW_DAYS = 7

private fun resolveNextUpVideoFromMeta(
    progress: WatchProgress,
    meta: CwMetaSummary,
    showUnairedNextUp: Boolean
): CwVideoSummary? {
    val episodes = meta.videos
        .filter { video ->
            val season = video.season
            val episode = video.episode
            season != null && episode != null && season != 0
        }
        .sortedWith(compareBy<CwVideoSummary>({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE }))

    if (episodes.isEmpty()) return null

    val seedSeason = progress.season
    val seedEpisode = progress.episode
    if (seedSeason == null || seedEpisode == null) return null

    val watchedIndex = episodes.indexOfFirst { it.season == seedSeason && it.episode == seedEpisode }
    if (watchedIndex < 0) {
        logNextUpDecision(
            "drop contentId=${progress.contentId} name=${progress.name} reason=seed-not-found-in-meta seed=${seedSeason}x${seedEpisode}"
        )
        return null
    }

    val todayLocal = LocalDate.now(ZoneId.systemDefault())
    val nextVideo = episodes.drop(watchedIndex + 1).firstOrNull { video ->
        val releaseDate = parseEpisodeReleaseDate(video.released)
        val isSeasonRollover = video.season != seedSeason
        if (isSeasonRollover) {
            if (releaseDate == null) {
                logNextUpDecision(
                    "skip contentId=${progress.contentId} name=${progress.name} reason=unaired-next-season-missing-date " +
                        "seed=${seedSeason}x${seedEpisode} next=${video.season}x${video.episode}"
                )
                return@firstOrNull false
            }
            if (!releaseDate.isAfter(todayLocal)) {
                return@firstOrNull true
            }
            // Match mobile: show unaired next-season episodes within 7-day window
            if (showUnairedNextUp) {
                val daysUntil = java.time.temporal.ChronoUnit.DAYS.between(todayLocal, releaseDate)
                if (daysUntil <= CW_NEXT_UP_NEW_SEASON_UNAIRED_WINDOW_DAYS) {
                    return@firstOrNull true
                }
            }
            return@firstOrNull false
        }

        val isUnaired = releaseDate?.isAfter(todayLocal) == true
        if (!isUnaired) {
            return@firstOrNull true
        }
        if (!showUnairedNextUp) {
            return@firstOrNull false
        }
        true
    }

    if (nextVideo == null) {
        logNextUpDecision(
            "drop contentId=${progress.contentId} name=${progress.name} reason=no-next-video-after-seed seed=${seedSeason}x${seedEpisode} showUnaired=$showUnairedNextUp"
        )
        return null
    }

    return nextVideo
}

private const val CW_META_NEGATIVE_CACHE_TTL_MS = 5 * 60_000L

private suspend fun HomeViewModel.resolveMetaForProgress(
    progress: WatchProgress,
    metaCache: MutableMap<String, CwMetaSummary?>,
    debug: CwDebugSession? = null
): CwMetaSummary? {
    val startedAtMs = SystemClock.elapsedRealtime()
    val cacheKey = "${progress.contentType}:${progress.contentId}"
    synchronized(metaCache) {
        if (metaCache.containsKey(cacheKey)) {
            val cached = metaCache[cacheKey]
            if (cached != null) {
                debug?.recordMetaCacheHit(progress)
                return cached
            }
            val negativeCachedAt = cwMetaNegativeCacheTimestamps[cacheKey]
            if (negativeCachedAt != null &&
                SystemClock.elapsedRealtime() - negativeCachedAt < CW_META_NEGATIVE_CACHE_TTL_MS
            ) {
                debug?.recordMetaCacheHit(progress)
                return null
            }
            metaCache.remove(cacheKey)
            cwMetaNegativeCacheTimestamps.remove(cacheKey)
        }
    }

    val idCandidates = buildList {
        add(progress.contentId)
        if (progress.contentId.startsWith("tmdb:")) add(progress.contentId.substringAfter(':'))
    }.distinct()

    val typeCandidates = listOf(progress.contentType, "series", "tv").distinct()
    val useAllAddons = externalMetaPrefetchEnabled
    val resolved = run {
        var summary: CwMetaSummary? = null
        var attempts = 0
        for (type in typeCandidates) {
            for (candidateId in idCandidates) {
                attempts += 1
                val attemptStartedAtMs = SystemClock.elapsedRealtime()
                val result = withTimeoutOrNull(2_500L) {
                    if (useAllAddons) {
                        metaRepository.getMetaFromAllAddons(
                            type = type,
                            id = candidateId
                        ).first { it !is NetworkResult.Loading }
                    } else {
                        metaRepository.getMetaFromPrimaryAddon(
                            type = type,
                            id = candidateId
                        ).first { it !is NetworkResult.Loading }
                    }
                }
                val attemptElapsedMs = SystemClock.elapsedRealtime() - attemptStartedAtMs
                if (result == null) {
                    debug?.recordMetaTimeout()
                    debug?.recordMetaAttempt(
                        progress = progress,
                        type = type,
                        candidateId = candidateId,
                        elapsedMs = attemptElapsedMs,
                        outcome = "timeout"
                    )
                    continue
                }
                when (result) {
                    is NetworkResult.Success<*> -> {
                        debug?.recordMetaAttempt(
                            progress = progress,
                            type = type,
                            candidateId = candidateId,
                            elapsedMs = attemptElapsedMs,
                            outcome = "success"
                        )
                    }
                    is NetworkResult.Error -> {
                        debug?.recordMetaError()
                        debug?.recordMetaAttempt(
                            progress = progress,
                            type = type,
                            candidateId = candidateId,
                            elapsedMs = attemptElapsedMs,
                            outcome = "error:${result.code ?: "unknown"}"
                        )
                    }
                    NetworkResult.Loading -> Unit
                }
                summary = ((result as? NetworkResult.Success<*>)?.data as? Meta)?.toCwSummary()
                if (summary != null) break
            }
            if (summary != null) break
        }
        debug?.recordMetaResolveFinished(
            progress = progress,
            elapsedMs = SystemClock.elapsedRealtime() - startedAtMs,
            success = summary != null,
            attempts = attempts
        )
        summary
    }

    synchronized(metaCache) {
        metaCache[cacheKey] = resolved
        if (resolved == null) {
            cwMetaNegativeCacheTimestamps[cacheKey] = SystemClock.elapsedRealtime()
        } else {
            cwMetaNegativeCacheTimestamps.remove(cacheKey)
        }
    }
    return resolved
}

/**
 * Resolves badge episodes for a group of sibling IDs (same show).
 * Resolves only the primary ID, then cross-caches under all siblings.
 */
private suspend fun HomeViewModel.resolveBadgeGroup(
    group: List<String>,
    forceRefresh: Boolean = false
) {
    val primaryId = group.first()
    val alreadyCached = !forceRefresh && synchronized(cwBadgeEpisodeCache) {
        cwBadgeEpisodeCache.containsKey("series:$primaryId") ||
            cwBadgeEpisodeCache.containsKey("tv:$primaryId")
    }
    if (!alreadyCached) {
        val episodes = resolveBadgeEpisodes(
            contentId = primaryId,
            contentType = "series",
            forceRefresh = forceRefresh
        )
        if (episodes == null) {
        } else {
        }
        if (group.size > 1) {
            val primaryStatus = synchronized(cwBadgeSeriesStatusCache) {
                if (cwBadgeSeriesStatusCache.containsKey("series:$primaryId")) {
                    cwBadgeSeriesStatusCache["series:$primaryId"]
                } else {
                    cwBadgeSeriesStatusCache["tv:$primaryId"]
                }
            }
            synchronized(cwBadgeEpisodeCache) {
                for (siblingId in group.drop(1)) {
                    if (
                        forceRefresh ||
                        !cwBadgeEpisodeCache.containsKey("series:$siblingId")
                    ) {
                        cwBadgeEpisodeCache["series:$siblingId"] = episodes
                    }
                }
            }
            synchronized(cwBadgeSeriesStatusCache) {
                for (siblingId in group.drop(1)) {
                    if (
                        forceRefresh ||
                        !cwBadgeSeriesStatusCache.containsKey("series:$siblingId")
                    ) {
                        cwBadgeSeriesStatusCache["series:$siblingId"] = primaryStatus
                    }
                }
            }
        }
    } else {
    }
}

/**
 * Lightweight badge-only resolve: fetches meta and extracts only aired (season, episode) pairs.
 * Does NOT populate cwMetaCache — keeps memory minimal for badge evaluation of many series.
 */
private suspend fun HomeViewModel.resolveBadgeEpisodes(
    contentId: String,
    contentType: String,
    forceRefresh: Boolean = false
): Set<Pair<Int, Int>>? {
    val cacheKey = "$contentType:$contentId"
    if (!forceRefresh) {
        synchronized(cwBadgeEpisodeCache) {
            if (cwBadgeEpisodeCache.containsKey(cacheKey)) {
                return cwBadgeEpisodeCache[cacheKey]
            }
        }
    }
    // First-time resolves can reuse CW metadata. TTL revalidation deliberately
    // bypasses it so status changes such as Ended -> Returning are refreshed.
    val existingSummary = if (!forceRefresh) {
        synchronized(cwMetaCache) {
            cwMetaCache[cacheKey]
                ?: cwMetaCache["series:$contentId"]
                ?: cwMetaCache["tv:$contentId"]
        }
    } else {
        null
    }
    if (existingSummary != null) {
        val episodes = existingSummary.releasedRegularEpisodeCoordinates()
        val nextSeasonMs = existingSummary.earliestUpcomingSeasonMs()
        if (nextSeasonMs != null) {
            cwBadgeNextSeasonMs[contentId] = nextSeasonMs
        } else {
            cwBadgeNextSeasonMs.remove(contentId)
        }
        synchronized(cwBadgeEpisodeCache) { cwBadgeEpisodeCache[cacheKey] = episodes }
        val resolvedStatus =
            resolveBadgeSeriesStatus(
                contentId = contentId,
                contentType = contentType,
                summary = existingSummary
            )
        synchronized(cwBadgeSeriesStatusCache) {
            cwBadgeSeriesStatusCache[cacheKey] = resolvedStatus
        }
        return episodes
    }

    // Only IMDB (tt*) and TMDB IDs are resolvable by addons — skip trakt: entirely.
    if (contentId.startsWith("trakt:")) {
        synchronized(cwBadgeEpisodeCache) { cwBadgeEpisodeCache[cacheKey] = null }
        synchronized(cwBadgeSeriesStatusCache) { cwBadgeSeriesStatusCache[cacheKey] = null }
        return null
    }
    val idCandidates = buildList {
        add(contentId)
        if (contentId.startsWith("tmdb:")) add(contentId.substringAfter(':'))
    }.distinct()
    val typeCandidates = listOf(contentType, "series", "tv").distinct()
    val useAllAddons = externalMetaPrefetchEnabled

    for (type in typeCandidates) {
        for (candidateId in idCandidates) {
            val result = withTimeoutOrNull(2_500L) {
                if (useAllAddons) {
                    metaRepository.getMetaFromAllAddons(type = type, id = candidateId)
                        .first { it !is NetworkResult.Loading }
                } else {
                    metaRepository.getMetaFromPrimaryAddon(type = type, id = candidateId)
                        .first { it !is NetworkResult.Loading }
                }
            } ?: continue
            val meta = (result as? NetworkResult.Success<*>)?.data as? Meta ?: continue
            val summary = meta.toCwSummary()
            val episodes = summary.releasedRegularEpisodeCoordinates()
            // Record upcoming season date for smart TTL scheduling. Clear an
            // older deadline when refreshed metadata no longer has one.
            val nextSeasonMs = summary.earliestUpcomingSeasonMs()
            if (nextSeasonMs != null) {
                cwBadgeNextSeasonMs[contentId] = nextSeasonMs
            } else {
                cwBadgeNextSeasonMs.remove(contentId)
            }
            synchronized(cwBadgeEpisodeCache) { cwBadgeEpisodeCache[cacheKey] = episodes }
            val resolvedStatus =
                resolveBadgeSeriesStatus(
                    contentId = contentId,
                    contentType = contentType,
                    summary = summary
                )
            synchronized(cwBadgeSeriesStatusCache) {
                cwBadgeSeriesStatusCache[cacheKey] = resolvedStatus
            }
            return episodes
        }
    }
    synchronized(cwBadgeEpisodeCache) { cwBadgeEpisodeCache[cacheKey] = null }
    synchronized(cwBadgeSeriesStatusCache) { cwBadgeSeriesStatusCache[cacheKey] = null }
    return null
}

private fun buildLightweightEpisodeVideoId(
    contentId: String,
    season: Int,
    episode: Int
): String = "$contentId:$season:$episode"

private fun buildNextUpSeedCacheKey(
    progress: WatchProgress,
    showUnairedNextUp: Boolean
): String {
    return buildString {
        append(progress.contentId.trim())
        append("|")
        append(progress.season ?: -1)
        append("|")
        append(progress.episode ?: -1)
        append("|unaired=")
        append(showUnairedNextUp)
    }
}

private fun HomeViewModel.persistLocalContinueWatchingMetadata(
    originalItems: List<ContinueWatchingItem>,
    enrichedItems: List<ContinueWatchingItem>
) {
    val localItems = enrichedItems.indices.mapNotNull { index ->
        val original = originalItems.getOrNull(index) as? ContinueWatchingItem.InProgress ?: return@mapNotNull null
        val enriched = enrichedItems.getOrNull(index) as? ContinueWatchingItem.InProgress ?: return@mapNotNull null
        enriched.progress.takeIf { it != original.progress }
    }

    // Use the full UI state for cache snapshots so async-injected items are included.
    val currentUiItems = _uiState.value.continueWatchingItems

    // Build next-up snapshot for cache
    val brokenUrls = com.nuvio.tv.ui.components.brokenImageUrls
    val nextUpSnapshot = currentUiItems.mapNotNull { item ->
        val nextUp = item as? ContinueWatchingItem.NextUp ?: return@mapNotNull null
        val info = nextUp.info
        com.nuvio.tv.data.local.CachedNextUpItem(
            contentId = info.contentId,
            contentType = info.contentType,
            name = info.name,
            poster = info.poster,
            backdrop = info.backdrop,
            logo = info.logo,
            videoId = info.videoId,
            season = info.season,
            episode = info.episode,
            episodeTitle = info.episodeTitle,
            episodeDescription = info.episodeDescription,
            thumbnail = info.thumbnail?.takeIf { it !in brokenUrls },
            released = info.released,
            hasAired = info.hasAired,
            airDateLabel = info.airDateLabel,
            lastWatched = info.lastWatched,
            imdbRating = info.imdbRating,
            genres = info.genres,
            releaseInfo = info.releaseInfo,
            sortTimestamp = info.sortTimestamp,
            releaseTimestamp = info.releaseTimestamp,
            isReleaseAlert = info.isReleaseAlert,
            isNewSeasonRelease = info.isNewSeasonRelease,
            seedSeason = info.seedSeason,
            seedEpisode = info.seedEpisode,
            contentLanguage = info.contentLanguage
        )
    }

    // Build in-progress snapshot for cache
    val inProgressSnapshot = currentUiItems.mapNotNull { item ->
        val ip = item as? ContinueWatchingItem.InProgress ?: return@mapNotNull null
        val p = ip.progress
        com.nuvio.tv.data.local.CachedInProgressItem(
            contentId = p.contentId,
            contentType = p.contentType,
            name = p.name,
            poster = p.poster,
            backdrop = p.backdrop,
            logo = p.logo,
            videoId = p.videoId,
            season = p.season,
            episode = p.episode,
            episodeTitle = p.episodeTitle,
            position = p.position,
            duration = p.duration,
            lastWatched = p.lastWatched,
            progressPercent = p.progressPercent,
            episodeThumbnail = ip.episodeThumbnail?.takeIf { it !in brokenUrls },
            episodeDescription = ip.episodeDescription,
            episodeImdbRating = ip.episodeImdbRating,
            genres = ip.genres,
            releaseInfo = ip.releaseInfo,
            contentLanguage = ip.contentLanguage
        )
    }

    // Capture profile ID NOW before launching coroutine — if a profile switch
    // happens before the IO dispatcher executes, activeProfileId.value would
    // return the NEW profile's ID, causing profile 1's data to overwrite
    // profile 2's cache file.
    val snapshotProfileId = profileManager.activeProfileId.value
    viewModelScope.launch(Dispatchers.IO) {
        if (nextUpSnapshot.isNotEmpty()) {
            runCatching { cwEnrichmentCache.saveNextUpSnapshot(nextUpSnapshot, force = true, profileId = snapshotProfileId) }
        }
        if (inProgressSnapshot.isNotEmpty()) {
            runCatching { cwEnrichmentCache.saveInProgressSnapshot(inProgressSnapshot, force = true, profileId = snapshotProfileId) }
        }
        val persistable = localItems.filter { it.hasRenderableMetadata() }
        if (persistable.isEmpty()) return@launch
        runCatching {
            watchProgressRepository.saveProgressBatch(persistable, syncRemote = false)
        }
    }
}

private fun WatchProgress.hasRenderableMetadata(): Boolean {
    return name.isNotBlank() || poster != null || backdrop != null || logo != null || episodeTitle != null
}

private fun NextUpInfo.toProgressSeed(): WatchProgress {
    return WatchProgress(
        contentId = contentId,
        contentType = contentType,
        name = name,
        poster = poster,
        backdrop = backdrop,
        logo = logo,
        videoId = videoId,
        season = seedSeason ?: season,
        episode = seedEpisode ?: episode,
        episodeTitle = episodeTitle,
        position = 1L,
        duration = 1L,
        lastWatched = lastWatched
    )
}

private fun isSeriesTypeCW(type: String?): Boolean {
    return type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true)
}

/** Applies enriched overlay from the previous enrichment cycle to avoid
 *  flickering between addon meta and TMDB-enriched values during fresh builds. */
private fun HomeViewModel.applyContinueWatchingEnrichmentOverlay(
    items: List<ContinueWatchingItem>
): List<ContinueWatchingItem> {
    if (cwEnrichedNextUpOverlay.isEmpty() && cwEnrichedInProgressOverlay.isEmpty()) return items
    return items.map { item ->
        when (item) {
            is ContinueWatchingItem.NextUp -> {
                val overlay = cwEnrichedNextUpOverlay[item.info.contentId] ?: return@map item
                if (
                    overlay.season != item.info.season ||
                    overlay.episode != item.info.episode
                ) {
                    cwEnrichedNextUpOverlay.remove(item.info.contentId)
                    return@map item
                }
                item.copy(info = item.info.copy(
                    name = overlay.name.takeIf { it.isNotBlank() } ?: item.info.name,
                    episodeTitle = overlay.episodeTitle ?: item.info.episodeTitle,
                    episodeDescription = overlay.episodeDescription ?: item.info.episodeDescription,
                    thumbnail = overlay.thumbnail ?: item.info.thumbnail,
                    poster = overlay.poster ?: item.info.poster,
                    backdrop = overlay.backdrop ?: item.info.backdrop,
                    logo = overlay.logo ?: item.info.logo,
                    imdbRating = overlay.imdbRating ?: item.info.imdbRating,
                    genres = overlay.genres.ifEmpty { item.info.genres },
                    releaseInfo = overlay.releaseInfo ?: item.info.releaseInfo,
                    isReleaseAlert = overlay.isReleaseAlert,
                    isNewSeasonRelease = overlay.isNewSeasonRelease,
                    releaseTimestamp = overlay.releaseTimestamp ?: item.info.releaseTimestamp,
                    sortTimestamp = overlay.sortTimestamp.takeIf { it > 0L } ?: item.info.sortTimestamp,
                    contentLanguage = overlay.contentLanguage ?: item.info.contentLanguage
                ))
            }
            is ContinueWatchingItem.InProgress -> {
                val overlay = cwEnrichedInProgressOverlay[item.progress.contentId] ?: return@map item
                if (overlay.progress.season != item.progress.season || overlay.progress.episode != item.progress.episode) return@map item
                item.copy(
                    progress = item.progress.copy(
                        name = overlay.progress.name.takeIf { it.isNotBlank() } ?: item.progress.name,
                        poster = overlay.progress.poster ?: item.progress.poster,
                        backdrop = overlay.progress.backdrop ?: item.progress.backdrop,
                        logo = overlay.progress.logo ?: item.progress.logo,
                        episodeTitle = overlay.progress.episodeTitle ?: item.progress.episodeTitle
                    ),
                    episodeThumbnail = overlay.episodeThumbnail ?: item.episodeThumbnail,
                    episodeDescription = overlay.episodeDescription ?: item.episodeDescription,
                    episodeImdbRating = overlay.episodeImdbRating ?: item.episodeImdbRating,
                    genres = overlay.genres.ifEmpty { item.genres },
                    releaseInfo = overlay.releaseInfo ?: item.releaseInfo,
                    contentLanguage = overlay.contentLanguage ?: item.contentLanguage
                )
            }
        }
    }
}

private fun HomeViewModel.publishBadgeUpdate(
    allWatchedEpisodes: Map<String, Set<Pair<Int, Int>>>
) {
    val validatedNotFullyWatched = mutableSetOf<String>()
    val validatedNonTerminal = mutableSetOf<String>()
    val updatedFullyWatched = allWatchedEpisodes.keys
        .filter { contentId ->
            val cacheKey = "series:$contentId"
            val releasedRegularEpisodes = synchronized(cwBadgeEpisodeCache) {
                cwBadgeEpisodeCache[cacheKey] ?: cwBadgeEpisodeCache["tv:$contentId"]
            } ?: return@filter false
            if (releasedRegularEpisodes.isEmpty()) return@filter false

            val watched = allWatchedEpisodes[contentId] ?: return@filter false
            val allRegularEpisodesWatched =
                releasedRegularEpisodes.all { it in watched }

            if (!allRegularEpisodesWatched) {
                if (watched.isNotEmpty()) {
                    validatedNotFullyWatched.add(contentId)
                }
                return@filter false
            }

            /*
             * Prefer a live CW metadata summary when one exists.  That lets a
             * revived show lose its badge as soon as refreshed metadata says it
             * is returning, without waiting for the badge-only cache TTL.
             */
            val liveSummary = synchronized(cwMetaCache) {
                cwMetaCache[cacheKey] ?: cwMetaCache["tv:$contentId"]
            }
            val liveStatus =
                cachedBadgeSeriesStatus(
                    contentId = contentId,
                    summary = liveSummary
                )
            val seriesStatus = liveStatus ?: synchronized(cwBadgeSeriesStatusCache) {
                if (cwBadgeSeriesStatusCache.containsKey(cacheKey)) {
                    cwBadgeSeriesStatusCache[cacheKey]
                } else {
                    cwBadgeSeriesStatusCache["tv:$contentId"]
                }
            }

            val shouldShowBadge = shouldShowSeriesWatchedBadge(
                status = seriesStatus,
                releasedRegularEpisodes = releasedRegularEpisodes,
                watchedEpisodes = watched
            )
            if (!shouldShowBadge) {
                /*
                 * All currently released regular episodes are watched, but the
                 * show is not terminal (or status is unknown).  Keep this
                 * revalidation finite so Ended/Returning status changes can
                 * add or remove the badge without a force-stop.
                 */
                validatedNonTerminal.add(contentId)
            }
            shouldShowBadge
        }
        .toSet()

    // Expand IDs: for each IMDB ID, also include the cached TMDB alias so
    // catalogs using either identity receive the same badge state.
    val expandedFullyWatched = buildSet {
        addAll(updatedFullyWatched)
        for (contentId in updatedFullyWatched) {
            if (contentId.startsWith("tt")) {
                tmdbService.cachedTmdbId(contentId)?.let { tmdbId ->
                    add("tmdb:$tmdbId")
                }
            }
        }
    }
    val expandedNotFullyWatched = buildSet {
        addAll(validatedNotFullyWatched)
        for (contentId in validatedNotFullyWatched) {
            if (contentId.startsWith("tt")) {
                tmdbService.cachedTmdbId(contentId)?.let { tmdbId ->
                    add("tmdb:$tmdbId")
                }
            }
        }
    }
    val expandedNonTerminal = buildSet {
        addAll(validatedNonTerminal)
        for (contentId in validatedNonTerminal) {
            if (contentId.startsWith("tt")) {
                tmdbService.cachedTmdbId(contentId)?.let { tmdbId ->
                    add("tmdb:$tmdbId")
                }
            }
        }
    }

    // Merge with persisted badges — don't remove badges we have not
    // revalidated.  Do remove badges when a released regular episode is
    // missing OR when refreshed metadata says the show is no longer terminal.
    val disqualified = expandedNotFullyWatched + expandedNonTerminal
    val current = fullyWatchedSeriesIds.fullyWatchedSeriesIds.value
    val merged = (current - disqualified) + expandedFullyWatched

    /*
     * Revalidation policy:
     * - terminal + fully watched: finite TTL so a later revival can remove ✓;
     * - non-terminal + caught up: finite TTL so becoming Ended can add ✓;
     * - missing watched episodes: no metadata recheck until watched history
     *   changes, which already republishes this calculation immediately.
     */
    val allValidatedIds =
        expandedFullyWatched + expandedNotFullyWatched + expandedNonTerminal
    val revalidateAt = buildMap {
        for (contentId in expandedFullyWatched) {
            cwBadgeNextSeasonMs[contentId]?.let { put(contentId, it) }
        }
        for (contentId in expandedNonTerminal) {
            cwBadgeNextSeasonMs[contentId]?.let { put(contentId, it) }
        }
        for (contentId in expandedNotFullyWatched) {
            put(contentId, Long.MAX_VALUE)
        }
    }
    fullyWatchedSeriesIds.updateWithValidation(merged, allValidatedIds, revalidateAt)
}

private fun parseEpisodeReleaseDate(raw: String?): LocalDate? {
    if (raw.isNullOrBlank()) return null
    val value = raw.trim()
    val zone = ZoneOffset.UTC

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

private fun parseEpisodeReleaseInstant(raw: String?): Instant? {
    if (raw.isNullOrBlank()) return null
    val value = raw.trim()
    val zone = ZoneOffset.UTC

    return runCatching {
        Instant.parse(value)
    }.getOrNull() ?: runCatching {
        OffsetDateTime.parse(value).toInstant()
    }.getOrNull() ?: runCatching {
        LocalDateTime.parse(value).atZone(zone).toInstant()
    }.getOrNull() ?: runCatching {
        LocalDate.parse(value).atStartOfDay(zone).toInstant()
    }.getOrNull() ?: runCatching {
        val datePortion = Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b").find(value)?.value
            ?: return@runCatching null
        LocalDate.parse(datePortion).atStartOfDay(zone).toInstant()
    }.getOrNull()
}

private suspend fun HomeViewModel.resolveContinueWatchingTmdbData(
    progress: WatchProgress,
    meta: CwMetaSummary,
    season: Int,
    episode: Int,
    debug: CwDebugSession? = null
): NextUpTmdbData? {
    if (!currentTmdbSettings.enabled) return null
    val tmdbId = resolveTmdbIdForNextUp(progress, meta, debug) ?: return null
    val language = currentTmdbSettings.language

    if (!isSeriesTypeCW(progress.contentType)) {
        val startedAtMs = SystemClock.elapsedRealtime()
        val mdbEnabled = currentMdbListSettings.enabled && currentMdbListSettings.apiKey.isNotBlank()
        val (movieMeta, mdbImdbRating) = coroutineScope {
            val movieDeferred = async {
                runCatching {
                    tmdbMetadataService.fetchEnrichment(
                        tmdbId = tmdbId,
                        contentType = ContentType.MOVIE,
                        language = language
                    )
                }.getOrNull()
            }
            val mdbDeferred = if (mdbEnabled) async {
                runCatching { mdbListRepository.getImdbRatingForItem(progress.contentId, progress.contentType) }.getOrNull()
            } else null
            movieDeferred.await() to mdbDeferred?.await()
        }
        debug?.recordTmdbCall(
            kind = "in-progress-movie-enrichment",
            elapsedMs = SystemClock.elapsedRealtime() - startedAtMs,
            success = movieMeta != null
        )
        return movieMeta?.let {
            NextUpTmdbData(
                // Use detailBackdrop as thumbnail for movies in CW — it's a different
                // image from the main backdrop shown behind the row, so it avoids
                // showing the same image twice. Fall back to poster if not available.
                thumbnail = it.detailBackdrop.normalizeImageUrl() ?: it.poster.normalizeImageUrl(),
                backdrop = it.backdrop.normalizeImageUrl(),
                poster = it.poster.normalizeImageUrl(),
                logo = it.logo.normalizeImageUrl(),
                name = it.localizedTitle?.trim()?.takeIf { t -> t.isNotEmpty() },
                episodeTitle = null,
                airDate = null,
                overview = it.description?.trim()?.takeIf { t -> t.isNotEmpty() },
                showDescription = null,
                rating = mdbImdbRating ?: it.rating,
                contentLanguage = it.language
            )
        }
    }

    val episodeStartedAtMs = SystemClock.elapsedRealtime()
    val mdbEnabled = currentMdbListSettings.enabled && currentMdbListSettings.apiKey.isNotBlank()

    val (episodeMeta, showMeta, mdbImdbRating) = coroutineScope {
        val episodeDeferred = async {
            runCatching {
                tmdbMetadataService.fetchEpisodeEnrichment(
                    tmdbId = tmdbId,
                    seasonNumbers = listOf(season),
                    language = language
                )[season to episode]
            }.getOrNull()
        }
        val showDeferred = async {
            runCatching {
                tmdbMetadataService.fetchEnrichment(
                    tmdbId = tmdbId,
                    contentType = ContentType.SERIES,
                    language = language
                )
            }.getOrNull()
        }
        val mdbDeferred = if (mdbEnabled) async {
            runCatching { mdbListRepository.getImdbRatingForItem(progress.contentId, progress.contentType) }.getOrNull()
        } else null
        Triple(episodeDeferred.await(), showDeferred.await(), mdbDeferred?.await())
    }

    debug?.recordTmdbCall(
        kind = "next-up-episode-enrichment",
        elapsedMs = SystemClock.elapsedRealtime() - episodeStartedAtMs,
        success = episodeMeta != null
    )
    debug?.recordTmdbCall(
        kind = "next-up-show-enrichment",
        elapsedMs = SystemClock.elapsedRealtime() - episodeStartedAtMs,
        success = showMeta != null
    )
    val fallback = NextUpTmdbData(
        thumbnail = episodeMeta?.thumbnail.normalizeImageUrl(),
        backdrop = showMeta?.backdrop.normalizeImageUrl(),
        poster = showMeta?.poster.normalizeImageUrl(),
        logo = showMeta?.logo.normalizeImageUrl(),
        name = showMeta?.localizedTitle?.trim()?.takeIf { it.isNotEmpty() },
        episodeTitle = episodeMeta?.title?.trim()?.takeIf { it.isNotEmpty() },
        airDate = episodeMeta?.airDate?.trim()?.takeIf { it.isNotEmpty() },
        overview = episodeMeta?.overview?.trim()?.takeIf { it.isNotEmpty() },
        showDescription = showMeta?.description?.trim()?.takeIf { it.isNotEmpty() },
        rating = mdbImdbRating ?: showMeta?.rating,
        contentLanguage = showMeta?.language
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
    meta: CwMetaSummary,
    debug: CwDebugSession? = null
): String? {
    val startedAtMs = SystemClock.elapsedRealtime()
    val cacheKey = "${progress.contentType}:${progress.contentId}"
    synchronized(cwTmdbIdCache) {
        if (cwTmdbIdCache.containsKey(cacheKey)) {
            val cached = cwTmdbIdCache[cacheKey]
            debug?.recordTmdbIdCacheHit(progress, resolved = cached != null)
            return cached
        }
    }
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
        tmdbService.ensureTmdbId(candidate, progress.contentType)?.let {
            synchronized(cwTmdbIdCache) {
                cwTmdbIdCache[cacheKey] = it
            }
            debug?.recordTmdbIdLookup(
                progress = progress,
                candidateCount = candidates.size,
                resolved = true,
                elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
            )
            return it
        }
    }
    synchronized(cwTmdbIdCache) {
        cwTmdbIdCache[cacheKey] = null
    }
    debug?.recordTmdbIdLookup(
        progress = progress,
        candidateCount = candidates.size,
        resolved = false,
        elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
    )
    return null
}

private fun shouldFetchNextUpTmdbFallback(
    item: ContinueWatchingItem.NextUp,
    meta: CwMetaSummary,
    video: CwVideoSummary?
): Boolean {
    val hasName = !(item.info.name.isBlank() && meta.name.isNullOrBlank())
    val hasPoster = item.info.poster != null || meta.poster.normalizeImageUrl() != null
    val hasBackdrop = item.info.backdrop != null || meta.backdropUrl.normalizeImageUrl() != null
    val hasLogo = item.info.logo != null || meta.logo.normalizeImageUrl() != null
    val hasEpisodeTitle = item.info.episodeTitle != null || video?.title?.takeIf { it.isNotBlank() } != null
    val hasEpisodeDescription = item.info.episodeDescription != null || video?.overview?.takeIf { it.isNotBlank() } != null
    val hasThumbnail = item.info.thumbnail != null || video?.thumbnail.normalizeImageUrl() != null
    val hasReleaseDate = item.info.released != null || video?.released?.trim()?.takeIf { it.isNotEmpty() } != null
    return !(hasName && hasPoster && hasBackdrop && hasLogo && hasEpisodeTitle && hasEpisodeDescription && hasThumbnail && hasReleaseDate)
}

private fun formatEpisodeAirDateLabel(releaseDate: LocalDate): String {
    val todayLocal = LocalDate.now(ZoneId.systemDefault())
    val locale = Locale.getDefault()
    val skeleton = if (releaseDate.year == todayLocal.year) "dMMM" else "dMMMy"
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
    return DateTimeFormatter.ofPattern(pattern, locale).format(releaseDate)
}

// Cached snapshots can have a stale sortTimestamp if they were written before
// a season's actual release date passed (e.g. seeded when isReleaseAlert was still
// false, defaulting sortTimestamp to lastWatched) — always recompute from the
// underlying releaseTimestamp/isReleaseAlert rather than trusting a stored value,
// so a season that has since become available correctly sorts by its real release
// date instead of silently keeping whatever position it had before release.
private fun recomputeSortTimestamp(lastWatched: Long, releaseTimestamp: Long?, isReleaseAlert: Boolean): Long {
    if (!isReleaseAlert || releaseTimestamp == null) return lastWatched
    val localDate = Instant.ofEpochMilli(releaseTimestamp)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
    return localDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

private fun resolveNextUpReleaseState(
    seedProgress: WatchProgress,
    nextSeason: Int,
    nextReleased: String?,
    hasAired: Boolean
): NextUpReleaseState {
    val releaseTimestamp = parseEpisodeReleaseInstant(nextReleased)?.toEpochMilli()
    val nowMs = System.currentTimeMillis()
    val isReleaseAlert = hasAired &&
        releaseTimestamp != null &&
        releaseTimestamp > seedProgress.lastWatched

    // Use midnight of the release date for sorting instead of the full
    // timestamp.  Meta sources sometimes report a future hour on the
    // current day which would pin the alert above freshly-watched items.
    val releaseDateMidnight = releaseTimestamp?.let { ts ->
        val localDate = Instant.ofEpochMilli(ts)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        localDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    return NextUpReleaseState(
        sortTimestamp = if (isReleaseAlert) releaseDateMidnight ?: releaseTimestamp!! else seedProgress.lastWatched,
        releaseTimestamp = releaseTimestamp,
        isReleaseAlert = isReleaseAlert,
        isNewSeasonRelease = isReleaseAlert && seedProgress.season != null && nextSeason != seedProgress.season
    )
}

private fun String?.normalizeImageUrl(): String? = this
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

internal fun nextUpDismissKey(
    contentId: String,
    season: Int?,
    episode: Int?
): String {
    return buildString {
        append(contentId.trim())
        append("|")
        append(season ?: -1)
        append("|")
        append(episode ?: -1)
    }
}

internal fun HomeViewModel.removeContinueWatchingPipeline(
    contentId: String,
    season: Int? = null,
    episode: Int? = null,
    isNextUp: Boolean = false
) {
    if (isNextUp) {
        val dismissKey =
            nextUpDismissKey(
                contentId,
                season,
                episode
            )

        /*
         * Capture the displayed successor before optimistically removing it.
         *
         * For a rewatch:
         *   seed = E6
         *   displayed successor = E7
         *
         * The repository can then verify that E7 was already watched and that
         * this exact E6 seed came from the Player.
         */
        val nextUpTarget =
            _uiState.value
                .continueWatchingItems
                .filterIsInstance<
                    ContinueWatchingItem.NextUp
                >()
                .firstOrNull { item ->
                    nextUpDismissKey(
                        item.info.contentId,
                        item.info.seedSeason,
                        item.info.seedEpisode
                    ) == dismissKey
                }

        val nextSeason =
            nextUpTarget?.info?.season
        val nextEpisode =
            nextUpTarget?.info?.episode

        _uiState.update { state ->
            state.copy(
                continueWatchingItems = state.continueWatchingItems.filterNot { item ->
                    when (item) {
                        is ContinueWatchingItem.NextUp ->
                            nextUpDismissKey(
                                item.info.contentId,
                                item.info.seedSeason,
                                item.info.seedEpisode
                            ) == dismissKey
                        is ContinueWatchingItem.InProgress -> false
                    }
                }
            )
        }
        viewModelScope.launch {
            val playerRewatch =
                runCatching {
                    watchProgressRepository
                        .consumePlayerRewatchNextUp(
                            contentId = contentId,
                            seedSeason = season,
                            seedEpisode = episode,
                            nextSeason = nextSeason,
                            nextEpisode = nextEpisode
                        )
                }.onFailure { error ->
                    android.util.Log.w(
                        HomeViewModel.TAG,
                        "Failed to classify Player rewatch dismissal",
                        error
                    )
                }.getOrDefault(false)

            runCatching {
                runProtectedNextUpDismissal(
                    isPlayerRewatch =
                        playerRewatch,
                    persistNormalDismissal = {
                        traktSettingsDataStore
                            .addDismissedNextUpKey(
                                dismissKey
                            )
                    },
                    dismissThroughProvider = {
                        watchProgressRepository
                            .dismissNextUp(
                                contentId =
                                    contentId,
                                season =
                                    season,
                                episode =
                                    episode
                            )
                    }
                )
            }.onFailure { error ->
                android.util.Log.w(
                    HomeViewModel.TAG,
                    "Failed to dismiss Next Up",
                    error
                )
            }

            if (playerRewatch) {
                /*
                 * Do not write the persistent dismissal key for a one-off
                 * rewatch. Clear only the matching cached card so it cannot
                 * flash back after process restart.
                 */
                cwEnrichedNextUpOverlay
                    .remove(contentId)

                synchronized(
                    discoveredOlderNextUpItems
                ) {
                    discoveredOlderNextUpItems
                        .removeAll { item ->
                            nextUpDismissKey(
                                item.info.contentId,
                                item.info.seedSeason,
                                item.info.seedEpisode
                            ) == dismissKey
                        }
                }

                val profileId =
                    profileManager.activeProfileId.value

                runCatching {
                    val cached =
                        cwEnrichmentCache
                            .getNextUpSnapshot(
                                profileId
                            )

                    val filtered =
                        cached.filterNot { item ->
                            nextUpDismissKey(
                                item.contentId,
                                item.seedSeason,
                                item.seedEpisode
                            ) == dismissKey
                        }

                    if (
                        filtered.size !=
                        cached.size
                    ) {
                        cwEnrichmentCache
                            .saveNextUpSnapshot(
                                items =
                                    filtered,
                                force =
                                    true,
                                profileId =
                                    profileId
                            )
                    }
                }.onFailure { error ->
                    android.util.Log.w(
                        HomeViewModel.TAG,
                        "Failed to clear rewatch Next Up cache",
                        error
                    )
                }
            }
        }
        // Refresh channel immediately with the already-filtered list
        val channelItemsAfterDismiss = _uiState.value.continueWatchingItems
        viewModelScope.launch(Dispatchers.IO) {
            val profileId = profileManager.activeProfileId.value
            val profileName = profileManager.activeProfile?.name ?: "Profile $profileId"
            runCatching { homeScreenChannelManager.refreshFromItems(channelItemsAfterDismiss, profileId, profileName) }
        }
        return
    }
    viewModelScope.launch {
        // Optimistic UI: remove the item from the CW list immediately
        // so the user sees instant feedback while the DataStore write propagates.
        // Compute filtered list first so we can pass it directly to the channel refresh
        // without racing against the StateFlow update committing.
        val filteredItems = _uiState.value.continueWatchingItems.filterNot { item ->
            when (item) {
                is ContinueWatchingItem.InProgress -> item.progress.contentId == contentId
                is ContinueWatchingItem.NextUp -> item.info.contentId == contentId
            }
        }
        _uiState.update { state ->
            state.copy(continueWatchingItems = filteredItems.stableCwOrdered())
        }
        // Refresh channel immediately with the already-filtered list
        val profileId = profileManager.activeProfileId.value
        val profileName = profileManager.activeProfile?.name ?: "Profile $profileId"
        launch(Dispatchers.IO) {
            runCatching { homeScreenChannelManager.refreshFromItems(filteredItems, profileId, profileName) }
        }
        val targetSeason = if (isNextUp) season else null
        val targetEpisode = if (isNextUp) episode else null
        watchProgressRepository.removeProgress(
            contentId = contentId,
            season = targetSeason,
            episode = targetEpisode
        )
    }
}
