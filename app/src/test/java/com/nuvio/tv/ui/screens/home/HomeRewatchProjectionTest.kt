package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.rewatch.RewatchEpisode
import com.nuvio.tv.domain.rewatch.RewatchPhase
import com.nuvio.tv.domain.rewatch.RewatchSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRewatchProjectionTest {
    private fun replay(
        phase: RewatchPhase = RewatchPhase.NEXT_UP,
        episode: RewatchEpisode = RewatchEpisode(3, 6),
        position: Long = 0L
    ) = RewatchSession(
        ownerKey = "local-profile:2",
        canonicalSeriesId = "tt0386676",
        sessionId = "wife-rewatch-1",
        episode = episode,
        positionMs = position,
        durationMs = 1_000_000L,
        lastCompletedEpisode = RewatchEpisode(3, 5),
        phase = phase,
        updatedAtEpochMs = 3_000L
    )

    private fun metadata(
        episodes: List<CwVideoSummary> = listOf(
            CwVideoSummary("tt0386676:3:6", "Office Episode", "2006-01-01",
                "episode.jpg", 3, 6, "Description")
        )
    ) = CwMetaSummary(
        id = "tt0386676",
        name = "The Office",
        poster = "poster.jpg",
        backdropUrl = "backdrop.jpg",
        logo = null,
        description = null,
        genres = listOf("Comedy"),
        releaseInfo = "2005",
        imdbRating = null,
        language = "en",
        country = "US",
        videos = episodes,
        status = "Ended"
    )

    private fun unrelated() = ContinueWatchingItem.InProgress(
        progress = WatchProgress(
            contentId = "tt0123456",
            contentType = "movie",
            name = "Another Movie",
            poster = null, backdrop = null, logo = null,
            videoId = "tt0123456",
            season = null, episode = null, episodeTitle = null,
            position = 6000L, duration = 90000L, lastWatched = 10_000L
        )
    )

    @Test
    fun `queued replay makes exact watched successor a next up card`() {
        val item = projectRewatchContinueWatchingItem(
            replay(), metadata()
        ) as ContinueWatchingItem.NextUp
        assertEquals(3, item.info.season)
        assertEquals(6, item.info.episode)
        assertEquals("tt0386676:3:6", item.info.videoId)
        assertEquals(5, item.info.seedEpisode)
        assertEquals("The Office", item.info.name)
        assertTrue(item.info.isRewatch)
    }

    @Test
    fun `in progress replay has its own resume source not Simkl history`() {
        val item = projectRewatchContinueWatchingItem(
            replay(RewatchPhase.PLAYING, position = 87_000L),
            metadata()
        ) as ContinueWatchingItem.InProgress
        assertEquals(CW_REWATCH_PROGRESS_SOURCE, item.progress.source)
        assertEquals(87_000L, item.progress.position)
        assertEquals(false, item.progress.completionOverride)
    }

    @Test
    fun `missing or invalid metadata never fabricates a playback video ID`() {
        assertNull(projectRewatchContinueWatchingItem(replay(), null))
        assertNull(projectRewatchContinueWatchingItem(replay(), metadata(emptyList())))
        val duplicated = metadata(
            listOf(
                CwVideoSummary("a", "a", null, null, 3, 6, null),
                CwVideoSummary("b", "b", null, null, 3, 6, null)
            )
        )
        assertNull(projectRewatchContinueWatchingItem(replay(), duplicated))
    }

    @Test
    fun `finished or unresolved replay is never presented as a next up`() {
        assertNull(projectRewatchContinueWatchingItem(
            replay(RewatchPhase.FINISHED), metadata()
        ))
        assertNull(projectRewatchContinueWatchingItem(
            replay(RewatchPhase.AWAITING_METADATA), metadata()
        ))
    }

    @Test
    fun `replay only replaces the same series while preserving other cards`() {
        val old = ContinueWatchingItem.NextUp(
            NextUpInfo(
                contentId = "tt0386676", contentType = "series",
                name = "The Office", poster = null, backdrop = null, logo = null,
                videoId = "tt0386676:9:23",
                season = 9, episode = 23, episodeTitle = null, thumbnail = null,
                lastWatched = 2_000L
            )
        )
        val replayCard = requireNotNull(
            projectRewatchContinueWatchingItem(replay(), metadata())
        )
        val movie = unrelated()
        val merged = mergeRewatchContinueWatchingCards(
            listOf(old, movie), listOf(replayCard)
        )
        assertEquals(2, merged.size)
        assertEquals(movie, merged.first())
        assertEquals(replayCard, merged.last())
    }

    @Test
    fun `cross-profile replay snapshot is never attached to current Home cycle`() {
        assertTrue(canUseRewatchProfileSnapshot(2, 2))
        assertEquals(false, canUseRewatchProfileSnapshot(1, 2))
        assertEquals(false, canUseRewatchProfileSnapshot(2, 1))
        assertEquals(false, canUseRewatchProfileSnapshot(0, 0))
    }

    @Test
    fun `without verified replay card provider rows remain intact`() {
        val original = listOf(unrelated())
        assertEquals(original, mergeRewatchContinueWatchingCards(original, emptyList()))
    }
}
