package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingProviderId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimklTrackingHistoryWriter @Inject constructor(
    private val service: SimklMutationService,
    private val syncRepository: SimklSyncRepository,
    private val profileManager: ProfileManager
) : TrackingHistoryWriter {
    override val providerId = TrackingProviderId.SIMKL

    override suspend fun addToHistory(
        profileId: Int,
        items: Collection<TrackingHistoryItem>
    ): TrackingMutationResult {
        if (profileId != profileManager.activeProfileId.value) return TrackingMutationResult(0)
        syncRepository.ensureLoaded()
        val snapshot = syncRepository.state.value.snapshot
        return service.addToHistory(
            items.map { item ->
                val enriched = snapshot.enrichMediaReference(item.media)
                item.copy(media = enriched.resolveAnimeEpisodeForSimkl())
            }
        )
    }

    override suspend fun removeFromHistory(
        profileId: Int,
        items: Collection<TrackingMediaReference>
    ): TrackingMutationResult {
        if (profileId != profileManager.activeProfileId.value) return TrackingMutationResult(0)
        syncRepository.ensureLoaded()
        val snapshot = syncRepository.state.value.snapshot
        val enrichedItems = items.map { ref ->
            snapshot.enrichMediaReference(ref).resolveAnimeEpisodeForSimkl()
        }
        val result = service.removeFromHistory(enrichedItems)

        /*
         * Simkl keeps a show's list status separate from episode history.
         * Removing the final watched episode therefore leaves a zero-progress
         * show stranded in "Watching" unless the parent show is removed too.
         *
         * The episode-removal receipt is committed synchronously, so inspect
         * the updated snapshot instead of making an extra network read. Only
         * clean up shows that are still explicitly Watching and now have no
         * watched episodes. Partially watched shows and every other list status
         * are left untouched.
         *
         * Simkl's parent-show removal also removes a rating, if one exists.
         * That is intentional here: Simkl automatically puts a rated TV show
         * back into Watching, so preserving the rating would recreate the stale
         * Watching state the user just explicitly cleared by unwatching the
         * final episode.
         */
        val cleanupTargets =
            syncRepository
                .state
                .value
                .snapshot
                .zeroHistoryWatchingCleanupTargets(enrichedItems)

        if (cleanupTargets.isNotEmpty()) {
            service.removeFromList(cleanupTargets)
        }

        return result
    }
}

/**
 * Finds parent shows that were touched by an episode-history removal and are
 * now stale zero-progress Watching entries.
 */
internal fun SimklSyncSnapshot.zeroHistoryWatchingCleanupTargets(
    touchedItems: Collection<TrackingMediaReference>
): List<TrackingMediaReference> =
    touchedItems
        .asSequence()
        .filter { reference ->
            reference.kind != TrackingMediaKind.MOVIE && reference.episode != null
        }
        .mapNotNull { reference ->
            val enriched = enrichMediaReference(reference)
            val entry = entries.firstOrNull { candidate ->
                candidate.matchesTrackingReference(enriched)
            } ?: return@mapNotNull null

            if (entry.status != SimklListStatus.WATCHING) return@mapNotNull null
            if (entry.hasWatchedEpisodeHistory()) return@mapNotNull null

            enriched.copy(episode = null)
        }
        .distinctBy(TrackingMediaReference::stableKey)
        .toList()

private fun SimklLibraryEntry.hasWatchedEpisodeHistory(): Boolean =
    watchedEpisodesCount > 0 ||
        seasons.any { season ->
            season.episodes.any { episode -> episode.watchedAt != null }
        }

private fun SimklLibraryEntry.matchesTrackingReference(
    reference: TrackingMediaReference
): Boolean {
    val candidate = media?.toTrackingExternalIds() ?: return false
    val target = reference.ids

    return (candidate.simkl != null && candidate.simkl == target.simkl) ||
        (!candidate.imdb.isNullOrBlank() && candidate.imdb.equals(target.imdb, ignoreCase = true)) ||
        (candidate.tmdb != null && candidate.tmdb == target.tmdb) ||
        (!candidate.tvdb.isNullOrBlank() && candidate.tvdb.equals(target.tvdb, ignoreCase = true)) ||
        (candidate.mal != null && candidate.mal == target.mal) ||
        (candidate.anidb != null && candidate.anidb == target.anidb) ||
        (candidate.anilist != null && candidate.anilist == target.anilist) ||
        (candidate.kitsu != null && candidate.kitsu == target.kitsu)
}
