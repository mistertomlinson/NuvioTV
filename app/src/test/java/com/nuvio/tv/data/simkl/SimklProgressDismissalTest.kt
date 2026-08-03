package com.nuvio.tv.data.simkl

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SimklProgressDismissalTest {

    @Test
    fun `episode dismissal suppresses matching stale playback only`() {
        val dismissed = progress(
            contentId = "tt0000001",
            season = 1,
            episode = 2,
            lastWatched = 100L
        )
        val otherEpisode = progress(
            contentId = "tt0000001",
            season = 1,
            episode = 3,
            lastWatched = 100L
        )
        val tombstones = mapOf(
            simklProgressDismissalKey(
                contentId = dismissed.contentId,
                season = dismissed.season,
                episode = dismissed.episode
            ) to 200L
        )

        val result = filterSimklDismissedProgress(
            entries = listOf(dismissed, otherEpisode),
            dismissedAtByKey = tombstones
        )

        assertEquals(1, result.size)
        assertSame(otherEpisode, result.single())
    }

    @Test
    fun `whole content dismissal suppresses every stale episode`() {
        val first = progress(
            contentId = "tt0000002",
            season = 1,
            episode = 2,
            lastWatched = 100L
        )
        val second = progress(
            contentId = "tt0000002",
            season = 2,
            episode = 1,
            lastWatched = 150L
        )
        val tombstones = mapOf(
            simklProgressDismissalKey(
                contentId = first.contentId,
                season = null,
                episode = null
            ) to 200L
        )

        assertTrue(
            filterSimklDismissedProgress(
                entries = listOf(first, second),
                dismissedAtByKey = tombstones
            ).isEmpty()
        )
    }

    @Test
    fun `newer playback after dismissal is visible again`() {
        val newer = progress(
            contentId = "tt0000003",
            season = 1,
            episode = 4,
            lastWatched = 300L
        )
        val tombstones = mapOf(
            simklProgressDismissalKey(
                contentId = newer.contentId,
                season = newer.season,
                episode = newer.episode
            ) to 200L
        )

        assertFalse(
            isSimklProgressDismissed(
                progress = newer,
                dismissedAtByKey = tombstones
            )
        )
    }

    @Test
    fun `newer episode playback clears title and episode tombstones`() {
        val playback = progress(
            contentId = "tt0000005",
            season = 2,
            episode = 3,
            lastWatched = 300L
        )
        val titleKey = simklProgressDismissalKey(
            contentId = playback.contentId,
            season = null,
            episode = null
        )
        val episodeKey = simklProgressDismissalKey(
            contentId = playback.contentId,
            season = playback.season,
            episode = playback.episode
        )
        val unrelatedKey = simklProgressDismissalKey(
            contentId = "tt9999999",
            season = null,
            episode = null
        )

        val result = clearSimklDismissalsForNewerProgress(
            dismissedAtByKey = mapOf(
                titleKey to 100L,
                episodeKey to 200L,
                unrelatedKey to 400L
            ),
            progress = playback
        )

        assertFalse(titleKey in result)
        assertFalse(episodeKey in result)
        assertEquals(400L, result[unrelatedKey])
    }

    @Test
    fun `dismissal matching is case insensitive`() {
        val playback = progress(
            contentId = "TT0000004",
            lastWatched = 100L
        )
        val tombstones = mapOf(
            simklProgressDismissalKey(
                contentId = "tt0000004",
                season = null,
                episode = null
            ) to 200L
        )

        assertTrue(
            isSimklProgressDismissed(
                progress = playback,
                dismissedAtByKey = tombstones
            )
        )
    }

    private fun progress(
        contentId: String,
        season: Int? = null,
        episode: Int? = null,
        lastWatched: Long
    ): WatchProgress = WatchProgress(
        contentId = contentId,
        contentType = if (season == null) "movie" else "series",
        name = "Test",
        poster = null,
        backdrop = null,
        logo = null,
        videoId =
            if (season == null) contentId
            else "$contentId:$season:$episode",
        season = season,
        episode = episode,
        episodeTitle = null,
        position = 50L,
        duration = 100L,
        lastWatched = lastWatched,
        progressPercent = 50f,
        source = WatchProgress.SOURCE_SIMKL_PLAYBACK
    )
}
