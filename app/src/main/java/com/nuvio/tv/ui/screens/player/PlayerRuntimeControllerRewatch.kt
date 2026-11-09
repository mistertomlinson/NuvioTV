package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.domain.rewatch.RewatchActivationDecision
import com.nuvio.tv.domain.rewatch.decideRewatchActivation
import com.nuvio.tv.domain.rewatch.startRewatchSession
import kotlinx.coroutines.flow.first
import com.nuvio.tv.domain.rewatch.RewatchEpisode
import com.nuvio.tv.domain.rewatch.RewatchPlaybackWriteRoute
import com.nuvio.tv.domain.rewatch.RewatchSession
import com.nuvio.tv.domain.rewatch.completeRewatchEpisode
import com.nuvio.tv.domain.rewatch.recordRewatchProgress
import com.nuvio.tv.domain.rewatch.resolvePendingRewatchSuccessor
import com.nuvio.tv.domain.rewatch.resolveRewatchPlaybackWriteRoute

/**
 * Resume an established replay, or begin a new one only when the active
 * provider has positively confirmed that this exact episode was watched.
 * Never replace an unfinished series replay with a different episode.
 *
 * Detection happens before initializePlayer so replayed episodes do not
 * produce a new Simkl scrobble or mutate original watched history.
 */
internal suspend fun PlayerRuntimeController.prepareExistingRewatchPlayback() {
    activeRewatchSession = null
    activeRewatchProfileId = null

    if (contentType?.lowercase() !in setOf("series", "tv")) return
    val content = contentId?.trim()?.takeIf(String::isNotBlank) ?: return
    val season = currentSeason?.takeIf { it > 0 } ?: return
    val episode = currentEpisode?.takeIf { it > 0 } ?: return
    if (!profileManager.activeProfileReady.value) return

    val selectedProfile = profileManager.activeProfileId.value
    val ownerKey = "local-profile:$selectedProfile"
    val currentEpisodeKey = RewatchEpisode(season, episode)
    val sessions = try {
        rewatchSessionStore.load(selectedProfile)
    } catch (error: Exception) {
        Log.w("PlayerRewatch", "Cannot read replay state", error)
        return
    }
    if (profileManager.activeProfileId.value != selectedProfile) return

    // Existing replay recognition never waits on a tracking provider.
    val stored = sessions.firstOrNull { session ->
        session.ownerKey == ownerKey &&
            session.canonicalSeriesId.trim().equals(content, ignoreCase = true)
    }
    if (stored != null && stored.phase != com.nuvio.tv.domain.rewatch.RewatchPhase.FINISHED) {
        if (stored.episode == currentEpisodeKey) {
            activeRewatchProfileId = selectedProfile
            activeRewatchSession = stored
        }
        return
    }

    // A missing or not-yet-loaded watched projection must NOT be treated as
    // an affirmative replay. Bound the lookup so ordinary first-play startup
    // cannot be blocked indefinitely by Simkl.
    val alreadyWatched = try {
        kotlinx.coroutines.withTimeoutOrNull(900L) {
            watchProgressRepository.isWatched(
                contentId = content,
                videoId = currentVideoId,
                season = season,
                episode = episode
            ).first()
        } == true
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.w("PlayerRewatch", "Could not verify prior watched episode", error)
        false
    }

    if (profileManager.activeProfileId.value != selectedProfile || !alreadyWatched) return
    if (decideRewatchActivation(
            storedSessions = sessions,
            ownerKey = ownerKey,
            canonicalSeriesId = content,
            episode = currentEpisodeKey,
            wasAlreadyWatched = true
        ) != RewatchActivationDecision.START_NEW
    ) return

    val started = startRewatchSession(
        ownerKey = ownerKey,
        canonicalSeriesId = content,
        sessionId = java.util.UUID.randomUUID().toString(),
        episode = currentEpisodeKey,
        wasAlreadyWatched = true,
        updatedAtEpochMs = System.currentTimeMillis()
    ) ?: return

    val saved = try {
        rewatchSessionStore.upsertForActiveProfile(
            expectedProfileId = selectedProfile,
            session = started,
            replaceSession = stored?.phase ==
                com.nuvio.tv.domain.rewatch.RewatchPhase.FINISHED
        )
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.w("PlayerRewatch", "Cannot begin durable rewatch session", error)
        false
    }

    if (saved && profileManager.activeProfileId.value == selectedProfile) {
        activeRewatchProfileId = selectedProfile
        activeRewatchSession = started
    }
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

/**
 * Repairs a completed episode whose metadata arrived after the completion
 * callback. It does not assume that the last *known* episode is a finale.
 */
internal suspend fun PlayerRuntimeController.resolvePendingRewatchAfterMetadata() {
    if (!hasResolvedMetaDetails) return
    if (contentType?.lowercase() !in setOf("series", "tv")) return

    val selectedProfile = profileManager.activeProfileId.value
    val content = contentId?.trim()?.takeIf { it.isNotBlank() } ?: return
    val sessions = rewatchSessionStore.load(selectedProfile)
    if (profileManager.activeProfileId.value != selectedProfile) return

    val pending = sessions.firstOrNull {
        it.canonicalSeriesId.trim().equals(content, ignoreCase = true) &&
            it.phase == com.nuvio.tv.domain.rewatch.RewatchPhase.AWAITING_METADATA &&
            it.lastCompletedEpisode != null
    } ?: return

    val completed = pending.lastCompletedEpisode ?: return
    val catalog = verifiedRewatchCompletionCatalog(
        videos = metaVideos,
        completed = completed,
        detailsResolved = true,
        seriesStatus = resolvedRewatchSeriesStatus
    )
    if (!catalog.metadataComplete) return

    val advanced = resolvePendingRewatchSuccessor(
        session = pending,
        orderedPlayableEpisodes = catalog.orderedEpisodes,
        metadataComplete = true
    )
    if (advanced == pending) return

    rewatchSessionStore.upsertForActiveProfile(
        expectedProfileId = selectedProfile,
        session = advanced
    )
}

/**
 * Completion saves can finish after a stream switch. The old episode must
 * never set the new episode's mutable completion flag.
 */
internal fun shouldMarkCurrentRewatchEpisodeCompleted(
    activeSession: RewatchSession?,
    capturedSessionId: String,
    capturedEpisode: RewatchEpisode,
    currentSeason: Int?,
    currentEpisode: Int?
): Boolean =
    activeSession?.sessionId == capturedSessionId &&
        activeSession.episode == capturedEpisode &&
        currentSeason == capturedEpisode.season &&
        currentEpisode == capturedEpisode.episode

/**
 * Flushes all queued replay writes before startup inspects the durable cursor.
 * Each write joins its predecessor, so awaiting the tail also awaits earlier
 * periodic and completion snapshots (including the outgoing stream flush).
 */
internal suspend fun PlayerRuntimeController.awaitPendingRewatchSaves() {
    while (true) {
        val pending = pendingRewatchSaveJob ?: return
        pending.join()
        if (pendingRewatchSaveJob === pending) {
            pendingRewatchSaveJob = null
            return
        }
    }
}


/**
 * Captured before switchToEpisodeStream replaces the outgoing episode.
 * The provider must never receive an ordinary scrobble for this successor
 * while its replay cursor still points at the previous episode.
 */
internal data class RewatchAutoplayHandoff(
    val sessionId: String,
    val successor: RewatchEpisode
)

internal fun rewatchAutoplayHandoffReady(
    handoff: RewatchAutoplayHandoff,
    loadedSession: RewatchSession?,
    currentEpisode: RewatchEpisode?
): Boolean =
    loadedSession?.sessionId == handoff.sessionId &&
        loadedSession.episode == handoff.successor &&
        currentEpisode == handoff.successor &&
        loadedSession.phase in setOf(
            com.nuvio.tv.domain.rewatch.RewatchPhase.PLAYING,
            com.nuvio.tv.domain.rewatch.RewatchPhase.NEXT_UP
        )
