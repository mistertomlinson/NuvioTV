package com.nuvio.tv.ui.util

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesWatchedActionsTest {

    private val today = LocalDate.of(2026, 10, 3)

    @Test
    fun `released regular episodes exclude specials future and unavailable`() {
        val meta = meta(
            videos = listOf(
                video(season = 0, episode = 1, released = "2020-01-01"),
                video(season = 1, episode = 1, released = "2026-10-01"),
                video(season = 1, episode = 2, released = "2026-10-04"),
                video(
                    season = 1,
                    episode = 3,
                    released = "2026-10-01",
                    available = false
                ),
                video(season = 2, episode = 1, released = null)
            )
        )

        val coordinates = meta
            .releasedRegularEpisodesForWatchedAction(today)
            .map { requireNotNull(it.season) to requireNotNull(it.episode) }

        assertEquals(
            listOf(1 to 1, 2 to 1),
            coordinates
        )
    }

    @Test
    fun `caught up ignores unwatched specials and future episodes`() {
        val meta = meta(
            videos = listOf(
                video(season = 0, episode = 1, released = "2020-01-01"),
                video(season = 1, episode = 1, released = "2026-10-01"),
                video(season = 1, episode = 2, released = "2026-10-02"),
                video(season = 1, episode = 3, released = "2026-10-10")
            )
        )

        assertTrue(
            meta.isCaughtUpForWatchedAction(
                watchedEpisodes = setOf(1 to 1, 1 to 2),
                today = today
            )
        )
    }

    @Test
    fun `missing one released regular episode is not caught up`() {
        val meta = meta(
            videos = listOf(
                video(season = 1, episode = 1, released = "2026-10-01"),
                video(season = 1, episode = 2, released = "2026-10-02")
            )
        )

        assertFalse(
            meta.isCaughtUpForWatchedAction(
                watchedEpisodes = setOf(1 to 1),
                today = today
            )
        )
    }

    @Test
    fun `series with no released regular episodes is not caught up`() {
        val meta = meta(
            videos = listOf(
                video(season = 0, episode = 1, released = "2020-01-01"),
                video(season = 1, episode = 1, released = "2026-10-04")
            )
        )

        assertFalse(
            meta.isCaughtUpForWatchedAction(
                watchedEpisodes = emptySet(),
                today = today
            )
        )
    }

    private fun meta(videos: List<Video>) = Meta(
        id = "tt-test",
        type = ContentType.SERIES,
        rawType = "series",
        name = "Test Series",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = "2026",
        status = "Returning Series",
        imdbRating = null,
        genres = emptyList(),
        runtime = null,
        director = emptyList(),
        cast = emptyList(),
        videos = videos,
        country = null,
        awards = null,
        language = null,
        links = emptyList()
    )

    private fun video(
        season: Int,
        episode: Int,
        released: String?,
        available: Boolean? = true
    ) = Video(
        id = "s${season}e${episode}",
        title = "Episode $episode",
        released = released,
        thumbnail = null,
        season = season,
        episode = episode,
        overview = null,
        runtime = 45,
        available = available
    )
}
