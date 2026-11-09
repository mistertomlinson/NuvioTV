package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.rewatch.RewatchPhase
import com.nuvio.tv.domain.rewatch.RewatchSession
import com.nuvio.tv.ui.screens.player.PlayerNextEpisodeRules

/** The existing provider history is never modified to render a replay. */
internal const val CW_REWATCH_PROGRESS_SOURCE = "rewatch_session"

internal fun projectRewatchContinueWatchingItem(
    session: RewatchSession,
    meta: CwMetaSummary?,
    providerSeed: WatchProgress? = null
): ContinueWatchingItem? {
    if (session.phase !in setOf(RewatchPhase.PLAYING, RewatchPhase.NEXT_UP)) return null
    if (meta == null) return null
    val video = meta.videos.singleOrNull {
        it.season == session.episode.season &&
            it.episode == session.episode.episode
    } ?: return null
    if (video.id.isBlank() ||
        video.available == false ||
        !PlayerNextEpisodeRules.hasEpisodeAired(video.released)
    ) return null

    val contentId = session.canonicalSeriesId
    val episode = session.episode
    val lastWatched = session.updatedAtEpochMs

    return when (session.phase) {
        RewatchPhase.PLAYING -> ContinueWatchingItem.InProgress(
            progress = WatchProgress(
                contentId = contentId,
                contentType = "series",
                name = meta.name.ifBlank { providerSeed?.name ?: contentId },
                poster = meta.poster ?: providerSeed?.poster,
                backdrop = meta.backdropUrl ?: providerSeed?.backdrop,
                logo = meta.logo ?: providerSeed?.logo,
                videoId = video.id,
                season = episode.season,
                episode = episode.episode,
                episodeTitle = video.title,
                position = session.positionMs.coerceAtLeast(0L),
                duration = session.durationMs.coerceAtLeast(0L),
                lastWatched = lastWatched,
                source = CW_REWATCH_PROGRESS_SOURCE,
                completionOverride = false
            ),
            episodeDescription = video.overview,
            episodeThumbnail = video.thumbnail,
            genres = meta.genres,
            releaseInfo = meta.releaseInfo,
            contentLanguage = meta.language
        )
        RewatchPhase.NEXT_UP -> ContinueWatchingItem.NextUp(
            info = NextUpInfo(
                contentId = contentId,
                contentType = "series",
                name = meta.name.ifBlank { providerSeed?.name ?: contentId },
                poster = meta.poster ?: providerSeed?.poster,
                backdrop = meta.backdropUrl ?: providerSeed?.backdrop,
                logo = meta.logo ?: providerSeed?.logo,
                videoId = video.id,
                season = episode.season,
                episode = episode.episode,
                episodeTitle = video.title,
                episodeDescription = video.overview,
                thumbnail = video.thumbnail,
                released = video.released,
                hasAired = true,
                lastWatched = lastWatched,
                sortTimestamp = lastWatched,
                seedSeason = session.lastCompletedEpisode?.season,
                seedEpisode = session.lastCompletedEpisode?.episode,
                genres = meta.genres,
                releaseInfo = meta.releaseInfo,
                contentLanguage = meta.language,
                isRewatch = true
            )
        )
        RewatchPhase.FINISHED, RewatchPhase.AWAITING_METADATA -> null
    }
}

/**
 * A matching durable session takes precedence over Simkl's original watched
 * history, while other series and movie cards retain their original order.
 * Only materialized, episode-verified replay cards replace provider cards.
 */
internal fun mergeRewatchContinueWatchingCards(
    normalItems: List<ContinueWatchingItem>,
    replayCards: List<ContinueWatchingItem>
): List<ContinueWatchingItem> {
    if (replayCards.isEmpty()) return normalItems
    fun contentId(item: ContinueWatchingItem): String = when (item) {
        is ContinueWatchingItem.NextUp -> item.info.contentId
        is ContinueWatchingItem.InProgress -> item.progress.contentId
    }
    fun canonical(value: String): String = value.trim().lowercase()
    val replacementById = replayCards.associateBy { canonical(contentId(it)) }
    val merged = normalItems.filterNot {
        canonical(contentId(it)) in replacementById
    }
    return merged + replacementById.values
}


/** A profile switch cannot combine an old replay cursor with new-profile CW. */
internal fun canUseRewatchProfileSnapshot(
    requestedProfileId: Int,
    replaySnapshotProfileId: Int
): Boolean = requestedProfileId > 0 && requestedProfileId == replaySnapshotProfileId
