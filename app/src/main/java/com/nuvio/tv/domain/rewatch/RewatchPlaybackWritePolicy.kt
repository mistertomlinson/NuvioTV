package com.nuvio.tv.domain.rewatch

/**
 * A replay has its own progress and completion state: never use the tracking
 * provider's ordinary watched-history or scrobble endpoints to advance it.
 *
 * Callers must resolve the series identity before making this decision.
 * This policy is deliberately not wired into the Player yet.
 */
enum class RewatchPlaybackWriteRoute {
    NORMAL_TRACKING,
    REWATCH_SESSION_ONLY
}

fun resolveRewatchPlaybackWriteRoute(
    session: RewatchSession?,
    ownerKey: String,
    canonicalSeriesId: String,
    episode: RewatchEpisode
): RewatchPlaybackWriteRoute {
    if (session == null || session.phase == RewatchPhase.FINISHED) {
        return RewatchPlaybackWriteRoute.NORMAL_TRACKING
    }

    // An old completion callback must not change another person's replay.
    if (session.ownerKey != ownerKey) {
        return RewatchPlaybackWriteRoute.NORMAL_TRACKING
    }

    // Alias resolution belongs to the caller. Never guess IDs by title.
    if (!session.canonicalSeriesId.trim().equals(
            canonicalSeriesId.trim(), ignoreCase = true
        )
    ) {
        return RewatchPlaybackWriteRoute.NORMAL_TRACKING
    }

    if (session.episode != episode) {
        return RewatchPlaybackWriteRoute.NORMAL_TRACKING
    }

    // Includes the metadata-wait state: missing metadata must not turn a
    // previously established rewatch into an ordinary Simkl history write.
    return RewatchPlaybackWriteRoute.REWATCH_SESSION_ONLY
}


/**
 * A matched rewatch session is authoritative over ordinary provider playback.
 *
 * null means "no replay": keep the normal resume path unchanged.
 * 0 means "replay next episode from the beginning": do not read provider
 * playback positions even if the episode was already watched on Simkl.
 * Positive positions are resumed exclusively from the replay cursor.
 */
fun resolveRewatchResumePosition(
    session: RewatchSession?,
    ownerKey: String,
    canonicalSeriesId: String,
    episode: RewatchEpisode
): Long? {
    if (resolveRewatchPlaybackWriteRoute(
            session = session,
            ownerKey = ownerKey,
            canonicalSeriesId = canonicalSeriesId,
            episode = episode
        ) != RewatchPlaybackWriteRoute.REWATCH_SESSION_ONLY
    ) return null

    return if (session?.phase == RewatchPhase.PLAYING) {
        session.positionMs.coerceAtLeast(0L)
    } else {
        0L
    }
}
