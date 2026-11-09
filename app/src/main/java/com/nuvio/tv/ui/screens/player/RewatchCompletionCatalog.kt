package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.rewatch.RewatchEpisode

/**
 * The player needs different evidence to advance to an aired successor than
 * it needs to mark a whole series replay FINISHED.
 *
 * Never infer that a show ended simply because a single metadata response
 * has no next episode. Doing so would silently discard rewatch CW cards.
 */
internal data class RewatchCompletionCatalog(
    val orderedEpisodes: List<RewatchEpisode>?,
    val metadataComplete: Boolean
)

internal fun verifiedRewatchCompletionCatalog(
    videos: List<Video>,
    completed: RewatchEpisode,
    detailsResolved: Boolean,
    seriesStatus: String?
): RewatchCompletionCatalog {
    val waiting = RewatchCompletionCatalog(null, metadataComplete = false)
    if (!detailsResolved || videos.isEmpty()) return waiting

    val regularEpisodes = videos.filter {
        (it.season ?: 0) > 0 && (it.episode ?: 0) > 0
    }.sortedWith(compareBy<Video> { it.season }.thenBy { it.episode })

    if (regularEpisodes.isEmpty()) return waiting

    val identities = regularEpisodes.map {
        RewatchEpisode(it.season!!, it.episode!!)
    }
    if (identities.distinct().size != identities.size) return waiting

    val index = identities.indexOf(completed)
    if (index == -1) return waiting

    val next = regularEpisodes.getOrNull(index + 1)
    if (next != null) {
        // A next episode is positive evidence even if this is an ongoing show.
        // An unaired successor cannot become a playable Next Up card.
        if (!PlayerNextEpisodeRules.hasEpisodeAired(next.released)) return waiting
        return RewatchCompletionCatalog(
            orderedEpisodes = listOf(completed, identities[index + 1]),
            metadataComplete = true
        )
    }

    // No next episode is not evidence that the series has finished.
    val terminal = seriesStatus?.trim()?.lowercase() in
        setOf("ended", "canceled", "cancelled")
    if (!terminal) return waiting

    // Refuse truncated lists with interior season gaps. An absent future
    // season cannot be proven from a single catalog; terminal status is
    // required in addition to this structural check.
    val contiguousBySeason = identities.groupBy { it.season }
        .values.all { episodes ->
            val numbers = episodes.map { it.episode }.sorted()
            numbers == (1..numbers.size).toList()
        }
    if (!contiguousBySeason) return waiting

    return RewatchCompletionCatalog(
        orderedEpisodes = identities,
        metadataComplete = true
    )
}
