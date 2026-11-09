package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.domain.rewatch.RewatchEpisode
import com.nuvio.tv.domain.rewatch.RewatchPlaybackWriteRoute
import com.nuvio.tv.domain.rewatch.RewatchSession
import com.nuvio.tv.domain.rewatch.completeRewatchEpisode
import com.nuvio.tv.domain.rewatch.recordRewatchProgress
import com.nuvio.tv.domain.rewatch.resolveRewatchPlaybackWriteRoute

/**
 * Activate only an explicitly existing replay session. Do not automatically
 * infer that playing an already-watched episode should start a rewatch.
 * The identity must match before initializePlayer can send any scrobble.
 */
internal suspend fun PlayerRuntimeController.prepareExistingRewatchPlayback() {
    activeRewatchSession = null
    activeRewatchProfileId = null

    if (contentType?.lowercase() !in setOf("series", "tv")) return
    val content = contentId?.trim()?.takeIf(String::isNotBlank) ?: return
    val season = currentSeason?.takeIf { it > 0 } ?: return
    val episode = currentEpisode?.takeIf { it > 0 } ?: return

    val selectedProfile = profileManager.activeProfileId.value
    val sessions = try {
        rewatchSessionStore.load(selectedProfile)
    } catch (error: Exception) {
        Log.w("PlayerRewatch", "Cannot read replay state", error)
        return
    }

    if (profileManager.activeProfileId.value != selectedProfile) return

    val currentEpisodeKey = RewatchEpisode(season, episode)
    val chosen = sessions.firstOrNull { session ->
        resolveRewatchPlaybackWriteRoute(
            session = session,
            ownerKey = session.ownerKey,
            canonicalSeriesId = content,
            episode = currentEpisodeKey
        ) == RewatchPlaybackWriteRoute.REWATCH_SESSION_ONLY
    } ?: return

    activeRewatchProfileId = selectedProfile
    activeRewatchSession = chosen
}

/** This routing decision precedes *all* playback scrobble and progress writes. */
internal fun PlayerRuntimeController.isExistingRewatchPlayback(): Boolean {
    val session = activeRewatchSession ?: return false
    val content = contentId ?: return false
    val season = currentSeason ?: return false
    val episode = currentEpisode ?: return false
    if (season <= 0 || episode <= 0) return false
    return resolveRewatchPlaybackWriteRoute(
        session = session,
        ownerKey = session.ownerKey,
        canonicalSeriesId = content,
        episode = RewatchEpisode(season, episode)
    ) == RewatchPlaybackWriteRoute.REWATCH_SESSION_ONLY
}

/**
 * A replay is not an ordinary Simkl scrobble or watched-history mutation.
 * Missing or partial metadata is saved as AWAITING_METADATA. A missing
 * successor only marks FINISHED when terminal series metadata independently
 * confirms that the current episode is the last of a complete ordered list.
 *
 * This method uses a captured episode/session identity from the caller.
 */
internal suspend fun PlayerRuntimeController.saveExistingRewatchPosition(
    profileId: Int,
    sessionId: String,
    episode: RewatchEpisode,
    positionMs: Long,
    durationMs: Long,
    completed: Boolean,
    updatedAtEpochMs: Long,
    verifiedCatalog: RewatchCompletionCatalog
): Boolean {
    if (profileManager.activeProfileId.value != profileId) return false

    val session = rewatchSessionStore.load(profileId)
        .firstOrNull { it.sessionId == sessionId && it.episode == episode }
        ?: return false

    val progressed = recordRewatchProgress(
        session = session,
        episode = episode,
        positionMs = positionMs,
        durationMs = durationMs,
        updatedAtEpochMs = updatedAtEpochMs
    )

    val updated: RewatchSession =
        if (!completed) {
            progressed
        } else {
            completeRewatchEpisode(
                session = progressed,
                completedEpisode = episode,
                orderedPlayableEpisodes = verifiedCatalog.orderedEpisodes,
                metadataComplete = verifiedCatalog.metadataComplete,
                updatedAtEpochMs = updatedAtEpochMs
            )
        }

    return rewatchSessionStore.upsertForActiveProfile(
        expectedProfileId = profileId,
        session = updated
    )
}
