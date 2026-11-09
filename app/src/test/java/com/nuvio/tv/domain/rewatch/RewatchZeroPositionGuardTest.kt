package com.nuvio.tv.domain.rewatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RewatchZeroPositionGuardTest {
    private val e1 = RewatchEpisode(1, 1)
    private val e2 = RewatchEpisode(1, 2)
    private val e3 = RewatchEpisode(1, 3)
    private val ordered = listOf(e1, e2, e3)

    private fun started() = requireNotNull(
        startRewatchSession(
            ownerKey = "wife",
            canonicalSeriesId = "imdb:tt0386676",
            sessionId = "rewatch-1",
            episode = e1,
            wasAlreadyWatched = true,
            updatedAtEpochMs = 1000L
        )
    )

    @Test
    fun `next up zero position is not enough to enable completion`() {
        val queued = completeRewatchEpisode(
            started(), e1, ordered, true, 2000L
        )
        val zero = recordRewatchProgress(
            queued, e2, 0L, 120_000L, 3000L
        )
        assertSame(queued, zero)
        assertEquals(
            RewatchPhase.NEXT_UP,
            completeRewatchEpisode(zero, e2, ordered, true, 4000L).phase
        )
    }

    @Test
    fun `meaningful playback activates the queued successor before it advances`() {
        val queued = completeRewatchEpisode(
            started(), e1, ordered, true, 2000L
        )
        val playing = recordRewatchProgress(
            queued, e2, 1200L, 120_000L, 3000L
        )
        assertEquals(RewatchPhase.PLAYING, playing.phase)
        val next = completeRewatchEpisode(
            playing, e2, ordered, true, 4000L
        )
        assertEquals(e3, next.episode)
        assertEquals(RewatchPhase.NEXT_UP, next.phase)
    }
}
