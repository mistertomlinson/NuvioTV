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

    @Test
    fun `known successor can begin only after the durable cursor advances`() {
        val outgoing = activeSession()
        val requirement = RewatchAutoplayHandoff(
            sessionId = outgoing.sessionId,
            successor = nextEpisode
        )
        assertFalse(rewatchAutoplayHandoffReady(
            requirement, outgoing, nextEpisode
        ))
        val queued = outgoing.copy(
            episode = nextEpisode,
            phase = RewatchPhase.NEXT_UP,
            positionMs = 0L
        )
        assertTrue(rewatchAutoplayHandoffReady(
            requirement, queued, nextEpisode
        ))
    }

    @Test
    fun `failed or stale handoff never falls back to ordinary Simkl playback`() {
        val requirement = RewatchAutoplayHandoff(
            sessionId = "rewatch-session",
            successor = nextEpisode
        )
        assertFalse(rewatchAutoplayHandoffReady(requirement, null, nextEpisode))
        assertFalse(rewatchAutoplayHandoffReady(
            requirement,
            activeSession().copy(
                episode = nextEpisode,
                sessionId = "other-rewatch"
            ),
            nextEpisode
        ))
        assertFalse(rewatchAutoplayHandoffReady(
            requirement,
            activeSession().copy(
                episode = nextEpisode,
                phase = RewatchPhase.AWAITING_METADATA
            ),
            nextEpisode
        ))
        assertFalse(rewatchAutoplayHandoffReady(
            requirement,
            activeSession().copy(
                episode = nextEpisode,
                phase = RewatchPhase.FINISHED
            ),
            nextEpisode
        ))
    }

    @Test
    fun `known successor cannot approve playback of a different episode`() {
        val requirement = RewatchAutoplayHandoff(
            sessionId = "rewatch-session",
            successor = nextEpisode
        )
        val saved = activeSession().copy(
            episode = nextEpisode,
            phase = RewatchPhase.NEXT_UP
        )
        assertFalse(rewatchAutoplayHandoffReady(
            requirement, saved, RewatchEpisode(3, 7)
        ))
    }

    @Test
    fun `automatic known successor requires durable handoff`() {
        assertTrue(shouldProtectAutomaticRewatchHandoff(
            automaticAdvance = true,
            outgoingReplayActive = true,
            destination = nextEpisode,
            knownSuccessor = nextEpisode
        ))
    }

    @Test
    fun `manual skip and unrelated selection do not inherit autoplay barrier`() {
        assertFalse(shouldProtectAutomaticRewatchHandoff(
            automaticAdvance = false,
            outgoingReplayActive = true,
            destination = nextEpisode,
            knownSuccessor = nextEpisode
        ))
        assertFalse(shouldProtectAutomaticRewatchHandoff(
            automaticAdvance = true,
            outgoingReplayActive = true,
            destination = RewatchEpisode(3, 7),
            knownSuccessor = nextEpisode
        ))
        assertFalse(shouldProtectAutomaticRewatchHandoff(
            automaticAdvance = true,
            outgoingReplayActive = false,
            destination = nextEpisode,
            knownSuccessor = nextEpisode
        ))
    }
}
