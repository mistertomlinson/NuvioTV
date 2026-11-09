package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.rewatch.RewatchEpisode
import com.nuvio.tv.domain.rewatch.RewatchPhase
import com.nuvio.tv.domain.rewatch.RewatchSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewatchAutoplaySaveBarrierTest {
    private val outgoingEpisode = RewatchEpisode(3, 5)
    private val nextEpisode = RewatchEpisode(3, 6)

    private fun activeSession() = RewatchSession(
        ownerKey = "local-profile:2",
        canonicalSeriesId = "tt0386676",
        sessionId = "rewatch-session",
        episode = outgoingEpisode,
        positionMs = 125_000L,
        durationMs = 240_000L,
        lastCompletedEpisode = RewatchEpisode(3, 4),
        phase = RewatchPhase.PLAYING,
        updatedAtEpochMs = 500L
    )

    @Test
    fun `completion updates the flag only while its own episode is active`() {
        assertTrue(shouldMarkCurrentRewatchEpisodeCompleted(
            activeSession = activeSession(),
            capturedSessionId = "rewatch-session",
            capturedEpisode = outgoingEpisode,
            currentSeason = 3,
            currentEpisode = 5
        ))
    }

    @Test
    fun `old episode completion never completes the successor after autoplay`() {
        assertFalse(shouldMarkCurrentRewatchEpisodeCompleted(
            activeSession = activeSession(),
            capturedSessionId = "rewatch-session",
            capturedEpisode = outgoingEpisode,
            currentSeason = nextEpisode.season,
            currentEpisode = nextEpisode.episode
        ))
    }

    @Test
    fun `different replay session or already advanced cursor cannot be marked`() {
        assertFalse(shouldMarkCurrentRewatchEpisodeCompleted(
            activeSession = activeSession().copy(sessionId = "new-rewatch-session"),
            capturedSessionId = "rewatch-session",
            capturedEpisode = outgoingEpisode,
            currentSeason = 3,
            currentEpisode = 5
        ))
        assertFalse(shouldMarkCurrentRewatchEpisodeCompleted(
            activeSession = activeSession().copy(episode = nextEpisode),
            capturedSessionId = "rewatch-session",
            capturedEpisode = outgoingEpisode,
            currentSeason = 3,
            currentEpisode = 5
        ))
    }

    @Test
    fun `no active session must not update a newer player completion flag`() {
        assertFalse(shouldMarkCurrentRewatchEpisodeCompleted(
            activeSession = null,
            capturedSessionId = "rewatch-session",
            capturedEpisode = outgoingEpisode,
            currentSeason = 3,
            currentEpisode = 5
        ))
    }
}
