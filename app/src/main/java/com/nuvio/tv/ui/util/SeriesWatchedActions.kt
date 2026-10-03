package com.nuvio.tv.ui.util

import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.format.DateTimeFormatter

/**
 * The canonical episode set used by the explicit series watched action.
 *
 * This deliberately matches the parent-series badge semantics:
 * - Season 0 specials never participate.
 * - Explicitly unavailable episodes never participate.
 * - Known future episodes never participate.
 * - Missing/unparseable release dates are treated as released, matching the
 *   existing badge behavior rather than silently hiding an episode forever.
 */
internal fun Meta.releasedRegularEpisodesForWatchedAction(
    today: LocalDate = LocalDate.now(ZoneId.systemDefault())
): List<Video> =
    videos.asSequence()
        .filter { (it.season ?: 0) > 0 }
        .filter { it.episode != null }
        .filter { it.available != false }
        .filter { video ->
            val releaseDate = video.released.toEpisodeReleaseDateOrNull()
            releaseDate == null || !releaseDate.isAfter(today)
        }
        .sortedWith(
            compareBy<Video>(
                { it.season ?: Int.MAX_VALUE },
                { it.episode ?: Int.MAX_VALUE }
            )
        )
        .toList()

internal fun Meta.isCaughtUpForWatchedAction(
    watchedEpisodes: Set<Pair<Int, Int>>,
    episodeProgressMap: Map<Pair<Int, Int>, WatchProgress> = emptyMap(),
    today: LocalDate = LocalDate.now(ZoneId.systemDefault())
): Boolean {
    val released = releasedRegularEpisodesForWatchedAction(today)
    if (released.isEmpty()) return false

    return released.all { video ->
        val season = video.season ?: return@all false
        val episode = video.episode ?: return@all false
        val key = season to episode
        key in watchedEpisodes ||
            episodeProgressMap[key]?.isCompleted() == true
    }
}

internal fun Meta.buildCompletedSeriesEpisodeProgress(
    parentContentId: String,
    video: Video,
    watchedAtEpochMs: Long = System.currentTimeMillis()
): WatchProgress {
    val runtimeMs =
        video.runtime
            ?.toLong()
            ?.times(60_000L)
            ?.takeIf { it > 0L }
            ?: 1L

    return WatchProgress(
        contentId = parentContentId,
        contentType = apiType,
        name = name,
        poster = poster,
        backdrop = video.thumbnail ?: backdropUrl,
        logo = logo,
        videoId = video.id,
        season = video.season,
        episode = video.episode,
        episodeTitle = video.title,
        position = runtimeMs,
        duration = runtimeMs,
        lastWatched = watchedAtEpochMs,
        progressPercent = 100f
    )
}

private fun String?.toEpisodeReleaseDateOrNull(): LocalDate? {
    val normalized = this
        ?.substringBefore('T')
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return null

    return try {
        LocalDate.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE)
    } catch (_: DateTimeParseException) {
        null
    }
}
