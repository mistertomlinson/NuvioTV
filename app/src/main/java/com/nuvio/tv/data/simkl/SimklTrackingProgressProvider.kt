package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.selectPreferredTrackingNextUpSeeds
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.WatchProgress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update

@Singleton
class SimklTrackingProgressProvider @Inject constructor(
    private val profileManager: ProfileManager,
    private val syncRepository: SimklSyncRepository,
    private val apiClient: SimklApiClient,
    private val authStorage: SimklAuthStorage,
    private val layoutPreferences: LayoutPreferenceDataStore,
    private val durableProgressStore: SimklDurableProgressStore,
    private val progressDismissalStore: SimklProgressDismissalStore
) : TrackingProgressProvider {
    override val providerId = TrackingProviderId.SIMKL
    override val projectionProfileId =
        syncRepository.projectionProfileId

    /*
     * These were previously singleton/global. A completed replay on one
     * profile could therefore alter another profile's Next Up projection.
     */
    private val optimisticMovieWatchedOverrides =
        MutableStateFlow<
            Map<Int, Map<String, Boolean>>
        >(emptyMap())

    private val optimisticEpisodeWatchedOverrides =
        MutableStateFlow<
            Map<
                Int,
                Map<
                    SimklOptimisticEpisodeKey,
                    SimklOptimisticEpisodeOverride
                >
            >
        >(emptyMap())

    // Current local playback must be visible immediately even before the
    // durable store or Simkl playback projection settles.
    // Keep only the latest local playback entry for each title.
    private val optimisticPlaybackProgress =
        MutableStateFlow<Map<Int, Map<String, WatchProgress>>>(emptyMap())
    override val isAuthenticated = authStorage.state.map { state -> state.isAuthenticated }
        .distinctUntilChanged()
    override val allProgress = combine(
        syncRepository.projection,
        durableProgressStore.allProgress,
        progressDismissalStore.dismissedAtByKey,
        optimisticPlaybackProgress,
        profileManager.activeProfileId
    ) { projection, durableEntries, dismissedAtByKey, optimisticEntriesByProfile, activeProfileId ->
        val optimisticEntries =
            optimisticEntriesByProfile[activeProfileId].orEmpty()

        // Completed local entries act as short-lived tombstones. They are not
        // rendered as progress, but they prevent stale Simkl playback for the
        // exact completed episode from briefly resurrecting in Continue Watching.
        val completedOptimisticEntries =
            optimisticEntries.values.filter(WatchProgress::isCompleted)

        val effectiveOptimisticEntries =
            optimisticEntries.values.filter { optimistic ->
                !optimistic.isCompleted() &&
                    projection.progress.none { remote ->
                        remote.contentId.equals(
                            optimistic.contentId,
                            ignoreCase = true
                        ) && remote.lastWatched >= optimistic.lastWatched
                    }
            }

        val optimisticContentIds =
            effectiveOptimisticEntries
                .mapTo(mutableSetOf()) { progress ->
                    progress.contentId.trim().lowercase()
                }

        val effectiveRemoteEntries =
            projection.progress.filterNot { remote ->
                val replacedByActivePlayback =
                    remote.contentId.trim().lowercase() in optimisticContentIds

                val completedLocally =
                    completedOptimisticEntries.any { completed ->
                        completed.contentId.equals(
                            remote.contentId,
                            ignoreCase = true
                        ) &&
                            completed.season == remote.season &&
                            completed.episode == remote.episode
                    }

                replacedByActivePlayback || completedLocally
            } + effectiveOptimisticEntries

        filterSimklDismissedProgress(
            entries = mergeSimklProgressWithDurable(
                remoteEntries = effectiveRemoteEntries,
                durableEntries = durableEntries,
                isWatched = projection::isWatchedAtOrAfter
            ),
            dismissedAtByKey = dismissedAtByKey
        )
    }.onStart { syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC) }
        .distinctUntilChanged()
    override val remoteProgressLoaded = syncRepository.state.map { state ->
        state.hasLoaded && state.errorMessage == null
    }.distinctUntilChanged()
    override val nextUpSeeds = combine(
        syncRepository.projection,
        layoutPreferences.nextUpFromFurthestEpisode,
        progressDismissalStore.dismissedAtByKey,
        optimisticEpisodeWatchedOverrides,
        profileManager.activeProfileId
    ) {
        projection,
        preferFurthestEpisode,
        dismissedAtByKey,
        episodeOverridesByProfile,
        activeProfileId ->

        val episodeOverrides =
            episodeOverridesByProfile[
                activeProfileId
            ].orEmpty()

        filterSimklDismissedProgress(
            entries = buildSimklNextUpWithEpisodeOverrides(
                remoteEntries = projection.watched.items
                    .asSequence()
                    .filter { item ->
                        item.season != null &&
                            item.episode != null &&
                            item.season != 0 &&
                            !projection.isHidden(item.contentId)
                    }
                    .map { item -> item.toSimklCompletedProgress() }
                    .toList(),
                overrides = episodeOverrides.values,
                preferFurthestEpisode = preferFurthestEpisode
            ),
            dismissedAtByKey = dismissedAtByKey
        )
    }.distinctUntilChanged()
    override val watchedMovieIds = combine(
        syncRepository.projection,
        optimisticMovieWatchedOverrides,
        profileManager.activeProfileId
    ) { projection, overridesByProfile, activeProfileId ->
        applySimklWatchedMovieOverrides(
            remoteIds = projection.watchedMovieIds,
            overrides =
                overridesByProfile[
                    activeProfileId
                ].orEmpty()
        )
    }.distinctUntilChanged()
    override val watchedItems = syncRepository.projection.map { projection ->
        projection.watched.items
    }.onStart { syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC) }
        .distinctUntilChanged()

    override fun episodeProgress(
        contentId: String
    ): Flow<Map<Pair<Int, Int>, WatchProgress>> = combine(
        syncRepository.projection,
        durableProgressStore.episodeProgress(contentId),
        progressDismissalStore.dismissedAtByKey,
        optimisticEpisodeWatchedOverrides,
        profileManager.activeProfileId
    ) {
        projection,
        durableEntries,
        dismissedAtByKey,
        episodeOverridesByProfile,
        activeProfileId ->

        val episodeOverrides =
            episodeOverridesByProfile[
                activeProfileId
            ].orEmpty()

        val resolved =
            mergeSimklEpisodeProgressWithDurable(
            remoteEntries = projection.episodeProgress(contentId),
            durableEntries = durableEntries,
            isWatched = projection::isWatchedAtOrAfter
        ).filterValues { progress ->
            !isSimklProgressDismissed(
                progress = progress,
                dismissedAtByKey = dismissedAtByKey
            )
        }

        applySimklEpisodeOverridesToProgress(
            contentId = contentId,
            remoteEntries = resolved,
            overrides = episodeOverrides.values
        )
    }.onStart { syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC) }
        .distinctUntilChanged()

    override fun airedEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>> = flowOf(emptyList())

    override fun isWatched(
        contentId: String,
        videoId: String?,
        season: Int?,
        episode: Int?
    ): Flow<Boolean> = combine(
        syncRepository.projection,
        optimisticEpisodeWatchedOverrides,
        profileManager.activeProfileId
    ) {
        projection,
        episodeOverridesByProfile,
        activeProfileId ->

        val episodeOverrides =
            episodeOverridesByProfile[
                activeProfileId
            ].orEmpty()

        if (season != null && episode != null) {
            episodeOverrides[
                simklOptimisticEpisodeKey(contentId, season, episode)
            ]?.watched ?: projection.isWatched(
                contentId,
                videoId,
                season,
                episode
            )
        } else {
            projection.isWatched(contentId, videoId, season, episode)
        }
    }.onStart { syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC) }
        .distinctUntilChanged()

    override suspend fun watchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>> {
        syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC)
        val profileId =
            profileManager.activeProfileId.value

        return applySimklEpisodeOverridesToWatchedEpisodes(
            remoteEntries =
                syncRepository
                    .projection
                    .value
                    .watchedShowEpisodes,
            overrides =
                optimisticEpisodeWatchedOverrides
                    .value[profileId]
                    .orEmpty()
                    .values
        )
    }

    override suspend fun showIdSiblings(): Map<String, Set<String>> {
        syncRepository.ensureLoaded()
        return syncRepository.projection.value.showIdSiblings
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) =
        syncRepository.refresh(intent)

    override suspend fun persistDurableProgress(progress: WatchProgress) {
        if (progress.isCompleted()) {
            /*
             * Movies have no Next Up successor. Persist a dismissal tombstone
             * so an older Simkl playback session cannot resurrect the completed
             * movie in Continue Watching after a process restart.
             *
             * Episodes intentionally do NOT get this dismissal here: their
             * completed progress is used to advance Next Up, and suppressing
             * that seed previously prevented the successor from appearing.
             */
            if (progress.season == null && progress.episode == null) {
                progressDismissalStore.dismiss(
                    contentId = progress.contentId,
                    season = null,
                    episode = null,
                    dismissedAtEpochMs = progress.lastWatched
                )
            }

            durableProgressStore.removeProgress(
                contentId = progress.contentId,
                season = progress.season,
                episode = progress.episode
            )
            return
        }

        /*
         * A genuinely newer playback session is a new rewatch. Once its
         * progress timestamp passes the completion tombstone, allow it back
         * into Continue Watching normally.
         */
        progressDismissalStore.clearForNewerProgress(progress)
        durableProgressStore.persist(progress)
    }

    override suspend fun persistDurableProgressBatch(
        progressList: List<WatchProgress>
    ) {
        if (progressList.isEmpty()) return
        val completed = progressList.filter { progress ->
            progress.isCompleted()
        }
        if (completed.isNotEmpty()) {
            durableProgressStore.removeProgressBatch(completed)
        }
        progressList
            .filterNot { progress -> progress.isCompleted() }
            .forEach { progress -> persistDurableProgress(progress) }
    }

    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) {
        progressDismissalStore.dismiss(
            contentId = contentId,
            season = season,
            episode = episode
        )
        durableProgressStore.removeProgress(contentId, season, episode)
        syncRepository.ensureLoaded()
        val sessions = syncRepository.state.value.snapshot.playback.filter { session ->
            val media = session.media ?: return@filter false
            val sameContent = media.canonicalContentId().equals(contentId, ignoreCase = true) ||
                syncRepository.state.value.snapshot.entries.any { entry ->
                    entry.media == media && entry.matchesSimklContentId(contentId)
                }
            val sessionSeason = session.episode?.tvdbSeason ?: session.episode?.season
            val sessionEpisode = session.episode?.tvdbNumber ?: session.episode?.number
            sameContent && (season == null || episode == null ||
                sessionSeason == season && sessionEpisode == episode)
        }
        val removed = linkedSetOf<Long>()
        sessions.mapNotNull(SimklPlaybackSession::id).forEach { sessionId ->
            try {
                apiClient.execute(
                    SimklApiRequest(
                        method = SimklHttpMethod.DELETE,
                        path = "/sync/playback/$sessionId"
                    )
                )
                removed += sessionId
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                Unit
            }
        }
        syncRepository.removePlaybackSessions(removed)
    }

    override suspend fun dismissNextUp(
        contentId: String,
        season: Int?,
        episode: Int?
    ) {
        progressDismissalStore.dismiss(
            contentId = contentId,
            season = season,
            episode = episode
        )
    }

    override fun applyOptimisticProgress(progress: WatchProgress, quiet: Boolean) {
        if (!progress.isCompleted()) {
            val key =
                "${progress.contentId.trim()}|${progress.season ?: -1}|${progress.episode ?: -1}"

            val profileId = profileManager.activeProfileId.value
            optimisticPlaybackProgress.update { current ->
                val profileEntries = current[profileId].orEmpty()
                val updatedProfileEntries =
                    profileEntries
                        .filterValues { existing ->
                            !existing.contentId.equals(
                                progress.contentId,
                                ignoreCase = true
                            )
                        } + (key to progress)

                current + (profileId to updatedProfileEntries)
            }
            return
        }

        // Keep the completed episode as a profile-scoped tombstone until the
        // provider catches up or another playback for this title replaces it.
        // This prevents stale Simkl playback (for example "1 min left") from
        // reappearing after the episode has already been marked watched.
        val profileId = profileManager.activeProfileId.value
        val completedKey =
            "${progress.contentId.trim()}|${progress.season ?: -1}|${progress.episode ?: -1}"

        optimisticPlaybackProgress.update { current ->
            val profileEntries = current[profileId].orEmpty()
            val updatedProfileEntries =
                profileEntries
                    .filterValues { existing ->
                        !existing.contentId.equals(
                            progress.contentId,
                            ignoreCase = true
                        )
                    } + (completedKey to progress)

            current + (profileId to updatedProfileEntries)
        }

        val season = progress.season
        val episode = progress.episode
        if (season != null && episode != null) {
            val key = simklOptimisticEpisodeKey(
                progress.contentId,
                season,
                episode
            )
            optimisticEpisodeWatchedOverrides.update { current ->
                val profileOverrides =
                    current[profileId].orEmpty()

                current + (
                    profileId to (
                        profileOverrides + (
                            key to SimklOptimisticEpisodeOverride(
                                key = key,
                                watched = true,
                                progress = progress,
                                updatedAtEpochMs =
                                    System.currentTimeMillis()
                            )
                        )
                    )
                )
            }
            return
        }

        val watchedIds = optimisticSimklMovieIds(progress.contentId, progress.videoId)
        optimisticMovieWatchedOverrides.update { current ->
            val profileOverrides =
                current[profileId].orEmpty()

            current + (
                profileId to (
                    profileOverrides +
                        watchedIds.associateWith { true }
                )
            )
        }
    }

    override fun applyOptimisticRemoval(
        contentId: String,
        videoId: String?,
        season: Int?,
        episode: Int?
    ) {
        val profileId = profileManager.activeProfileId.value

        // Marking an episode unwatched must also hide any stale Simkl playback
        // record for that episode. Otherwise an old near-complete playback
        // session can immediately resurrect as a Resume card.
        //
        // Keep a completed local tombstone only for projection suppression.
        // nextUpSeeds separately sees the watched=false override below, so the
        // episode can return as Next Up with no stale progress bar.
        val remoteProgressForRemoval =
            if (season != null && episode != null) {
                syncRepository.projection.value.progress.firstOrNull { existing ->
                    existing.contentId.equals(contentId, ignoreCase = true) &&
                        existing.season == season &&
                        existing.episode == episode
                }
            } else {
                null
            }

        optimisticPlaybackProgress.update { current ->
            val profileEntries = current[profileId].orEmpty()

            val matchingLocalEntry =
                profileEntries.values.firstOrNull { existing ->
                    existing.contentId.equals(contentId, ignoreCase = true) &&
                        (
                            season == null ||
                                episode == null ||
                                (
                                    existing.season == season &&
                                        existing.episode == episode
                                    )
                            )
                }

            val withoutRemovedEntry =
                profileEntries.filterValues { existing ->
                    val sameContent =
                        existing.contentId.equals(contentId, ignoreCase = true)
                    val sameEpisode =
                        season == null ||
                            episode == null ||
                            (
                                existing.season == season &&
                                    existing.episode == episode
                                )

                    !(sameContent && sameEpisode)
                }

            val tombstoneSource =
                if (season != null && episode != null) {
                    matchingLocalEntry ?: remoteProgressForRemoval
                } else {
                    null
                }

            val updatedProfileEntries =
                if (tombstoneSource != null) {
                    val completedDuration =
                        tombstoneSource.duration
                            .takeIf { it > 0L }
                            ?: tombstoneSource.position.coerceAtLeast(1L)

                    val tombstone =
                        tombstoneSource.copy(
                            position = completedDuration,
                            duration = completedDuration,
                            progressPercent = 100f,
                            lastWatched = System.currentTimeMillis()
                        )

                    val tombstoneKey =
                        "${tombstone.contentId.trim()}|${tombstone.season ?: -1}|${tombstone.episode ?: -1}"

                    withoutRemovedEntry + (tombstoneKey to tombstone)
                } else {
                    withoutRemovedEntry
                }

            if (updatedProfileEntries.isEmpty()) {
                current - profileId
            } else {
                current + (profileId to updatedProfileEntries)
            }
        }

        if (season != null && episode != null) {
            val key = simklOptimisticEpisodeKey(contentId, season, episode)
            optimisticEpisodeWatchedOverrides.update { current ->
                val profileOverrides =
                    current[profileId].orEmpty()

                current + (
                    profileId to (
                        profileOverrides + (
                            key to SimklOptimisticEpisodeOverride(
                                key = key,
                                watched = false,
                                progress = null,
                                updatedAtEpochMs =
                                    System.currentTimeMillis()
                            )
                        )
                    )
                )
            }
            return
        }

        if (season != null || episode != null) return
        val watchedIds = optimisticSimklMovieIds(contentId, videoId)
        optimisticMovieWatchedOverrides.update { current ->
            val profileOverrides =
                current[profileId].orEmpty()

            current + (
                profileId to (
                    profileOverrides +
                        watchedIds.associateWith { false }
                )
            )
        }
    }

    override fun clearOptimisticRemoval(
        contentId: String,
        videoId: String?,
        season: Int?,
        episode: Int?
    ) {
        val profileId =
            profileManager.activeProfileId.value

        if (season != null && episode != null) {
            val key = simklOptimisticEpisodeKey(contentId, season, episode)
            optimisticEpisodeWatchedOverrides.update { current ->
                val remaining =
                    current[profileId].orEmpty() - key

                if (remaining.isEmpty()) {
                    current - profileId
                } else {
                    current + (
                        profileId to remaining
                    )
                }
            }
            return
        }

        val watchedIds = optimisticSimklMovieIds(contentId, videoId)
        optimisticMovieWatchedOverrides.update { current ->
            val remaining =
                current[profileId].orEmpty() - watchedIds

            if (remaining.isEmpty()) {
                current - profileId
            } else {
                current + (
                    profileId to remaining
                )
            }
        }
    }

    override fun clearOptimisticProgress(
        contentId: String,
        season: Int?,
        episode: Int?
    ) {
        val resolvedSeason = season ?: return
        val resolvedEpisode = episode ?: return
        val profileId =
            profileManager.activeProfileId.value

        val episodeKey =
            simklOptimisticEpisodeKey(
                contentId,
                resolvedSeason,
                resolvedEpisode
            )

        optimisticEpisodeWatchedOverrides.update { current ->
            val remaining =
                current[profileId]
                    .orEmpty() - episodeKey

            if (remaining.isEmpty()) {
                current - profileId
            } else {
                current + (
                    profileId to remaining
                )
            }
        }

        optimisticPlaybackProgress.update { current ->
            val remaining =
                current[profileId]
                    .orEmpty()
                    .filterValues { existing ->
                        !(
                            existing.contentId.equals(
                                contentId,
                                ignoreCase = true
                            ) &&
                                existing.season ==
                                    resolvedSeason &&
                                existing.episode ==
                                    resolvedEpisode
                            )
                    }

            if (remaining.isEmpty()) {
                current - profileId
            } else {
                current + (
                    profileId to remaining
                )
            }
        }
    }

    override fun clearOptimistic() {
        optimisticMovieWatchedOverrides.value = emptyMap()
        optimisticEpisodeWatchedOverrides.value = emptyMap()
        optimisticPlaybackProgress.value = emptyMap()
    }

    override fun isHiddenFromProgress(contentId: String): Boolean =
        syncRepository.projection.value.isHidden(contentId)

    override fun isWatchedByVideoId(videoId: String, episode: Int): Boolean =
        syncRepository.projection.value.isWatchedByVideoId(videoId, episode)

    override suspend fun prepareNextUpSeed(progress: WatchProgress): WatchProgress = progress
}

internal data class SimklOptimisticEpisodeKey(
    val contentId: String,
    val season: Int,
    val episode: Int
)

internal data class SimklOptimisticEpisodeOverride(
    val key: SimklOptimisticEpisodeKey,
    val watched: Boolean,
    val progress: WatchProgress?,
    val updatedAtEpochMs: Long
)

internal fun simklOptimisticEpisodeKey(
    contentId: String,
    season: Int,
    episode: Int
): SimklOptimisticEpisodeKey = SimklOptimisticEpisodeKey(
    contentId = contentId.trim().lowercase(),
    season = season,
    episode = episode
)

internal fun buildSimklNextUpWithEpisodeOverrides(
    remoteEntries: List<WatchProgress>,
    overrides: Collection<SimklOptimisticEpisodeOverride>,
    preferFurthestEpisode: Boolean
): List<WatchProgress> {
    val merged = linkedMapOf<SimklOptimisticEpisodeKey, WatchProgress>()

    remoteEntries.forEach { progress ->
            val season = progress.season ?: return@forEach
            val episode = progress.episode ?: return@forEach
            merged[
                simklOptimisticEpisodeKey(
                    progress.contentId,
                    season,
                    episode
                )
            ] = progress
        }

    overrides.sortedBy(SimklOptimisticEpisodeOverride::updatedAtEpochMs)
        .forEach { override ->
            if (override.watched) {
                override.progress?.let { progress ->
                    merged[override.key] = progress
                }
            } else {
                merged.remove(override.key)
            }
        }

    val selected = selectPreferredTrackingNextUpSeeds(
        merged.values.toList(),
        preferFurthestEpisode
    )

    /*
     * Only an explicit UNWATCH should retimestamp the promoted fallback
     * seed. A watched=true completion already carries its own playback
     * timestamp.
     *
     * Retimestamping historical E8 with a newly replayed E6 completion
     * incorrectly makes E8 look like the newest activity and prevents
     * the Player-rewatch seed from advancing CW to E7.
     */
    val latestUnwatchMutationByContent =
        overrides
            .filter { override ->
                !override.watched
            }
            .groupBy { override ->
                override.key.contentId
            }
            .mapValues { (_, values) ->
                values.maxOf(
                    SimklOptimisticEpisodeOverride::
                        updatedAtEpochMs
                )
            }

    return selected.map { progress ->
        val mutationAt =
            latestUnwatchMutationByContent[
                progress.contentId
                    .trim()
                    .lowercase()
            ]
        if (mutationAt != null && mutationAt > progress.lastWatched) {
            progress.copy(lastWatched = mutationAt)
        } else {
            progress
        }
    }
}

internal fun applySimklEpisodeOverridesToProgress(
    contentId: String,
    remoteEntries: Map<Pair<Int, Int>, WatchProgress>,
    overrides: Collection<SimklOptimisticEpisodeOverride>
): Map<Pair<Int, Int>, WatchProgress> {
    val normalizedContentId = contentId.trim().lowercase()
    return remoteEntries.toMutableMap().apply {
        overrides
            .filter { override ->
                override.key.contentId == normalizedContentId
            }
            .sortedBy(SimklOptimisticEpisodeOverride::updatedAtEpochMs)
            .forEach { override ->
                val coordinates = override.key.season to override.key.episode
                if (override.watched) {
                    override.progress?.let { progress ->
                        this[coordinates] = progress
                    }
                } else {
                    remove(coordinates)
                }
            }
    }
}

internal fun applySimklEpisodeOverridesToWatchedEpisodes(
    remoteEntries: Map<String, Set<Pair<Int, Int>>>,
    overrides: Collection<SimklOptimisticEpisodeOverride>
): Map<String, Set<Pair<Int, Int>>> {
    val merged = remoteEntries
        .mapValues { (_, episodes) -> episodes.toMutableSet() }
        .toMutableMap()

    overrides.sortedBy(SimklOptimisticEpisodeOverride::updatedAtEpochMs)
        .forEach { override ->
            val existingKey = merged.keys.firstOrNull { contentId ->
                contentId.equals(override.key.contentId, ignoreCase = true)
            }
            val contentKey = existingKey
                ?: override.progress?.contentId
                ?: override.key.contentId
            val episodes = merged.getOrPut(contentKey) { linkedSetOf() }
            val coordinates = override.key.season to override.key.episode
            if (override.watched) {
                episodes.add(coordinates)
            } else {
                episodes.remove(coordinates)
            }
            if (episodes.isEmpty()) merged.remove(contentKey)
        }

    return merged.mapValues { (_, episodes) -> episodes.toSet() }
}

internal fun applySimklWatchedMovieOverrides(
    remoteIds: Set<String>,
    overrides: Map<String, Boolean>
): Set<String> = remoteIds.toMutableSet().apply {
    overrides.forEach { (contentId, watched) ->
        if (watched) add(contentId) else remove(contentId)
    }
}

internal fun optimisticSimklMovieIds(
    contentId: String,
    videoId: String?
): Set<String> = buildSet {
    listOfNotNull(contentId, videoId)
        .map(String::trim)
        .filter(String::isNotBlank)
        .forEach { id ->
            add(id)
            if (id.startsWith("imdb:", ignoreCase = true)) {
                id.substringAfter(':').takeIf(String::isNotBlank)?.let(::add)
            } else if (id.startsWith("tt", ignoreCase = true)) {
                add("imdb:$id")
            }
        }
}

internal fun checkWatchedByVideoId(
    snapshot: SimklSyncSnapshot,
    videoId: String,
    episode: Int
): Boolean = SimklSnapshotProjection.create(snapshot).isWatchedByVideoId(videoId, episode)
