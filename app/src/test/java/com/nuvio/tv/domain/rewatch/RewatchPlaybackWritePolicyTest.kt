package com.nuvio.tv.domain.rewatch

import org.junit.Assert.assertEquals
import org.junit.Test

class RewatchPlaybackWritePolicyTest {
    private val episode = RewatchEpisode(3, 5)

    private fun session(phase: RewatchPhase = RewatchPhase.PLAYING) =
        RewatchSession(
            ownerKey = "wife",
            canonicalSeriesId = "imdb:tt0386676",
            sessionId = "replay-1",
            episode = episode,
            positionMs = 123_000L,
            durationMs = 1_200_000L,
            lastCompletedEpisode = RewatchEpisode(3, 4),
            phase = phase,
            updatedAtEpochMs = 1_000L
        )

    private fun route(
        candidate: RewatchSession?,
        owner: String = "wife",
        id: String = "imdb:tt0386676",
        requestedEpisode: RewatchEpisode = episode
    ) = resolveRewatchPlaybackWriteRoute(
        candidate, owner, id, requestedEpisode
    )

    @Test
    fun `all established replay phases use replay-only writes`() {
        for (phase in listOf(
            RewatchPhase.PLAYING,
            RewatchPhase.NEXT_UP,
            RewatchPhase.AWAITING_METADATA
        )) {
            assertEquals(
                RewatchPlaybackWriteRoute.REWATCH_SESSION_ONLY,
                route(session(phase))
            )
        }
    }

    @Test
    fun `no replay session uses ordinary provider tracking`() {
        assertEquals(
            RewatchPlaybackWriteRoute.NORMAL_TRACKING,
            route(null)
        )
    }

    @Test
    fun `finished replay uses ordinary provider tracking`() {
        assertEquals(
            RewatchPlaybackWriteRoute.NORMAL_TRACKING,
            route(session(RewatchPhase.FINISHED))
        )
    }

    @Test
    fun `other profile never inherits replay suppressions`() {
        assertEquals(
            RewatchPlaybackWriteRoute.NORMAL_TRACKING,
            route(session(), owner = "husband")
        )
    }

    @Test
    fun `unrelated series and other episodes never inherit replay suppression`() {
        assertEquals(
            RewatchPlaybackWriteRoute.NORMAL_TRACKING,
            route(session(), id = "imdb:other")
        )
        assertEquals(
            RewatchPlaybackWriteRoute.NORMAL_TRACKING,
            route(session(), requestedEpisode = RewatchEpisode(3, 6))
        )
    }

    @Test
    fun `same canonical ID matches case and whitespace only`() {
        assertEquals(
            RewatchPlaybackWriteRoute.REWATCH_SESSION_ONLY,
            route(session(), id = " IMDB:TT0386676 ")
        )
        assertEquals(
            RewatchPlaybackWriteRoute.NORMAL_TRACKING,
            route(session(), id = "tmdb:some-other-alias")
        )
    }
}
