package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchedSeriesBadgeRulesTest {
    @Test
    fun `ended and canceled statuses are terminal`() {
        assertTrue(isTerminalSeriesStatus("Ended"))
        assertTrue(isTerminalSeriesStatus("Canceled"))
        assertTrue(isTerminalSeriesStatus("Cancelled"))
        assertTrue(isTerminalSeriesStatus(" ended "))
    }

    @Test
    fun `returning and unknown statuses are not terminal`() {
        assertFalse(isTerminalSeriesStatus("Returning Series"))
        assertFalse(isTerminalSeriesStatus("In Production"))
        assertFalse(isTerminalSeriesStatus("Planned"))
        assertFalse(isTerminalSeriesStatus(null))
        assertFalse(isTerminalSeriesStatus(""))
    }

    @Test
    fun `season zero specials never block an ended series badge`() {
        val summary = summary(
            status = "Ended",
            videos = listOf(
                video(season = 0, episode = 1),
                video(season = 0, episode = 2),
                video(season = 1, episode = 1),
                video(season = 1, episode = 2)
            )
        )

        val regularEpisodes = summary.watchableEpisodes()
            .map { requireNotNull(it.season) to requireNotNull(it.episode) }
            .toSet()

        assertEquals(setOf(1 to 1, 1 to 2), regularEpisodes)
        assertTrue(
            shouldShowSeriesWatchedBadge(
                status = summary.status,
                releasedRegularEpisodes = regularEpisodes,
                watchedEpisodes = setOf(1 to 1, 1 to 2)
            )
        )
    }

    @Test
    fun `future regular episode does not block ended series badge`() {
        val summary = summary(
            status = "Ended",
            videos = listOf(
                video(season = 1, episode = 1),
                video(season = 1, episode = 2),
                video(
                    season = 1,
                    episode = 3,
                    released = "2999-01-01"
                )
            )
        )

        val released = summary.releasedRegularEpisodeCoordinates(
            today = java.time.LocalDate.of(2026, 10, 2)
        )

        assertEquals(setOf(1 to 1, 1 to 2), released)
        assertTrue(
            shouldShowSeriesWatchedBadge(
                status = summary.status,
                releasedRegularEpisodes = released,
                watchedEpisodes = setOf(1 to 1, 1 to 2)
            )
        )
    }

    @Test
    fun `explicitly unavailable regular episode does not block ended series badge`() {
        val summary = summary(
            status = "Ended",
            videos = listOf(
                video(season = 1, episode = 1),
                video(
                    season = 1,
                    episode = 2,
                    available = false
                )
            )
        )

        assertEquals(
            setOf(1 to 1),
            summary.releasedRegularEpisodeCoordinates()
        )
    }

    @Test
    fun `caught up returning series does not get watched badge`() {
        val released = setOf(1 to 1, 1 to 2, 2 to 1)

        assertFalse(
            shouldShowSeriesWatchedBadge(
                status = "Returning Series",
                releasedRegularEpisodes = released,
                watchedEpisodes = released
            )
        )
    }

    @Test
    fun `ended series still requires every released regular episode`() {
        val released = setOf(1 to 1, 1 to 2, 2 to 1)

        assertFalse(
            shouldShowSeriesWatchedBadge(
                status = "Ended",
                releasedRegularEpisodes = released,
                watchedEpisodes = setOf(1 to 1, 1 to 2)
            )
        )
    }

    @Test
    fun `ended series becomes eligible immediately when final regular episode is present`() {
        val released = setOf(1 to 1, 1 to 2, 2 to 1)

        assertTrue(
            shouldShowSeriesWatchedBadge(
                status = "Ended",
                releasedRegularEpisodes = released,
                watchedEpisodes = released
            )
        )
    }

    private fun summary(
        status: String?,
        videos: List<CwVideoSummary>
    ) = CwMetaSummary(
        id = "tt-test",
        name = "Test Series",
        poster = null,
        backdropUrl = null,
        logo = null,
        description = null,
        genres = emptyList(),
        releaseInfo = null,
        status = status,
        imdbRating = null,
        language = null,
        country = null,
        videos = videos
    )

    private fun video(
        season: Int,
        episode: Int,
        released: String? = "2020-01-01",
        available: Boolean? = true
    ) = CwVideoSummary(
        id = "s${season}e${episode}",
        title = "Episode $episode",
        released = released,
        thumbnail = null,
        season = season,
        episode = episode,
        overview = null,
        available = available
    )
}
