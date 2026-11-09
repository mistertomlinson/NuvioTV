package com.nuvio.tv.domain.rewatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RewatchResumePolicyTest {
    private val episode = RewatchEpisode(3, 5)

    private fun session(
        phase: RewatchPhase,
        positionMs: Long = 420_000L
    ) = RewatchSession(
        ownerKey = "wife",
        canonicalSeriesId = "imdb:tt0386676",
        sessionId = "rewatch-session",
        episode = episode,
        positionMs = positionMs,
        durationMs = 1_200_000L,
        lastCompletedEpisode = RewatchEpisode(3, 4),
        phase = phase,
        updatedAtEpochMs = 11_000L
    )

    private fun resume(
        session: RewatchSession?,
        owner: String = "wife",
        id: String = "imdb:tt0386676",
        requested: RewatchEpisode = episode
    ) = resolveRewatchResumePosition(session, owner, id, requested)

    @Test
    fun `in progress replay resumes its own cursor instead of provider history`() {
        assertEquals(420_000L, resume(session(RewatchPhase.PLAYING)))
    }

    @Test
    fun `queued replay ignores old remote resume position and begins at zero`() {
        assertEquals(0L, resume(session(RewatchPhase.NEXT_UP)))
        assertEquals(0L, resume(session(RewatchPhase.AWAITING_METADATA)))
    }

    @Test
    fun `finished or absent replay leaves ordinary resume unchanged`() {
        assertNull(resume(null))
        assertNull(resume(session(RewatchPhase.FINISHED)))
    }

    @Test
    fun `different profiles shows and episodes never override normal resume`() {
        val current = session(RewatchPhase.PLAYING)
        assertNull(resume(current, owner = "other-profile"))
        assertNull(resume(current, id = "imdb:some-other-show"))
        assertNull(resume(current, requested = RewatchEpisode(3, 6)))
    }

    @Test
    fun `canonical ID case and blank whitespace are harmless`() {
        assertEquals(
            420_000L,
            resume(session(RewatchPhase.PLAYING), id = " IMDB:TT0386676 ")
        )
    }

    @Test
    fun `invalid replay position never yields a negative seek`() {
        assertEquals(0L, resume(session(RewatchPhase.PLAYING, -100L)))
    }
}
