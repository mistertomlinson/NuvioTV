package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingListStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimklMyListRemovalTest {

    @Test
    fun `fully watched series leaves Plan to Watch as Completed`() {
        val entry = seriesEntry(
            watchedEpisodes = 7,
            totalEpisodes = 7,
            notAiredEpisodes = 0
        )

        assertEquals(
            TrackingListStatus.COMPLETED,
            entry.historyPreservingStatusAfterPlanToWatchRemoval()
        )
    }

    @Test
    fun `partially watched series leaves Plan to Watch as Watching`() {
        val entry = seriesEntry(
            watchedEpisodes = 3,
            totalEpisodes = 7,
            notAiredEpisodes = 0
        )

        assertEquals(
            TrackingListStatus.WATCHING,
            entry.historyPreservingStatusAfterPlanToWatchRemoval()
        )
    }

    @Test
    fun `caught up ongoing series leaves Plan to Watch as Watching`() {
        val entry = seriesEntry(
            watchedEpisodes = 7,
            totalEpisodes = 8,
            notAiredEpisodes = 1
        )

        assertEquals(
            TrackingListStatus.WATCHING,
            entry.historyPreservingStatusAfterPlanToWatchRemoval()
        )
    }

    @Test
    fun `watched movie leaves Plan to Watch as Completed`() {
        val entry = SimklLibraryEntry(
            mediaType = SimklMediaType.MOVIES,
            status = SimklListStatus.PLAN_TO_WATCH,
            lastWatchedAt = "2026-10-03T12:00:00Z",
            watchedEpisodesCount = 0
        )

        assertEquals(
            TrackingListStatus.COMPLETED,
            entry.historyPreservingStatusAfterPlanToWatchRemoval()
        )
    }

    @Test
    fun `rating only item does not invent a watched status`() {
        val entry = SimklLibraryEntry(
            mediaType = SimklMediaType.SHOWS,
            status = SimklListStatus.PLAN_TO_WATCH,
            userRating = 8,
            userRatedAt = "2026-10-03T12:00:00Z"
        )

        assertNull(entry.historyPreservingStatusAfterPlanToWatchRemoval())
    }

    @Test
    fun `unwatched Plan to Watch item remains removable`() {
        val entry = seriesEntry(
            watchedEpisodes = 0,
            totalEpisodes = 7,
            notAiredEpisodes = 0,
            hasLastWatched = false
        )

        assertNull(entry.historyPreservingStatusAfterPlanToWatchRemoval())
    }

    private fun seriesEntry(
        watchedEpisodes: Int,
        totalEpisodes: Int,
        notAiredEpisodes: Int,
        hasLastWatched: Boolean = watchedEpisodes > 0
    ) = SimklLibraryEntry(
        mediaType = SimklMediaType.SHOWS,
        status = SimklListStatus.PLAN_TO_WATCH,
        lastWatchedAt = if (hasLastWatched) "2026-10-03T12:00:00Z" else null,
        watchedEpisodesCount = watchedEpisodes,
        totalEpisodesCount = totalEpisodes,
        notAiredEpisodesCount = notAiredEpisodes
    )
}
