package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.data.local.ProfileRewatchSessions
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.rewatch.RewatchPhase
import com.nuvio.tv.domain.rewatch.RewatchSession

/** A verified local replay cursor plus exactly one playable metadata video.
 *
 * Details must derive its hero label AND playback target from the same
 * profile-scoped replay snapshot used by Continue Watching. Simkl/provider
 * history remains unmodified; a failed match falls back to the existing
 * normal first-watch selection behavior.
 */
internal data class RewatchDetailsTarget(
    val session: RewatchSession,
    val video: Video
)

private fun detailsContentKey(value: String): String {
    val key = value.trim().lowercase()
    val bare = key.removePrefix("imdb:")
    return if (Regex("tt[0-9]{7,9}").matches(bare)) bare else key
}

internal fun resolveRewatchDetailsTarget(
    requestedProfileId: Int,
    snapshot: ProfileRewatchSessions?,
    contentIds: Set<String>,
    playableEpisodes: List<Video>
): RewatchDetailsTarget? {
    if (requestedProfileId <= 0 ||
        snapshot == null ||
        snapshot.profileId != requestedProfileId ||
        contentIds.isEmpty()) return null

    val ids = contentIds.map(::detailsContentKey)
        .filter { it.isNotBlank() }
        .toSet()
    if (ids.isEmpty()) return null

    val matches = snapshot.sessions.filter { session ->
        session.ownerKey == "local-profile:$requestedProfileId" &&
            detailsContentKey(session.canonicalSeriesId) in ids
    }
    // Never guess a replay when duplicate aliases or a corrupt profile
    // make the relevant local cursor ambiguous.
    val session = matches.singleOrNull() ?: return null
    if (session.phase !in setOf(RewatchPhase.PLAYING, RewatchPhase.NEXT_UP) ||
        session.episode.season <= 0 || session.episode.episode <= 0 ||
        session.positionMs < 0L || session.durationMs < 0L ||
        (session.phase == RewatchPhase.NEXT_UP &&
            (session.positionMs != 0L || session.durationMs != 0L))
    ) return null

    val video = playableEpisodes.filter { episode ->
        episode.season == session.episode.season &&
            episode.episode == session.episode.episode &&
            episode.available != false &&
            episode.id.isNotBlank()
    }.singleOrNull() ?: return null

    return RewatchDetailsTarget(session, video)
}
