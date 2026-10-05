package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetaDetailsRewatchNextUpTest {

    @Test
    fun `fresh completed rewatch seed returns already watched immediate successor`() {
        val now = 10_000L
        val seed = completedProgress(
            episode = 2,
            lastWatched = now - 1_000L
        )

        val successor = resolveFreshRewatchSuccessor(
            contentIds = setOf("series-1"),
            episodes = episodes(1, 2, 3, 4),
            watchedEpisodes = setOf(
                1 to 1,
                1 to 2,
                1 to 3
            ),
            fallbackProgressMap = emptyMap(),
            nextUpSeeds = listOf(seed),
            nowEpochMs = now,
            maxAgeMs = 5_000L
        )

        assertEquals(3, successor?.episode)
    }

    @Test
    fun `fresh normal completion does not use rewatch override when successor is unwatched`() {
        val now = 10_000L
        val seed = completedProgress(
            episode = 2,
            lastWatched = now - 1_000L
        )

        val successor = resolveFreshRewatchSuccessor(
            contentIds = setOf("series-1"),
            episodes = episodes(1, 2, 3, 4),
            watchedEpisodes = setOf(
                1 to 1,
                1 to 2
            ),
            fallbackProgressMap = emptyMap(),
            nextUpSeeds = listOf(seed),
            nowEpochMs = now,
            maxAgeMs = 5_000L
        )

        assertNull(successor)
    }

    @Test
    fun `stale completed seed does not override Details next to watch`() {
        val now = 10_000L
        val seed = completedProgress(
            episode = 2,
            lastWatched = 1_000L
        )

        val successor = resolveFreshRewatchSuccessor(
            contentIds = setOf("series-1"),
            episodes = episodes(1, 2, 3),
            watchedEpisodes = setOf(
                1 to 1,
                1 to 2,
                1 to 3
            ),
            fallbackProgressMap = emptyMap(),
            nextUpSeeds = listOf(seed),
            nowEpochMs = now,
            maxAgeMs = 5_000L
        )

        assertNull(successor)
    }

    @Test
    fun `seed from another series cannot affect Details`() {
        val now = 10_000L
        val seed = completedProgress(
            contentId = "other-series",
            episode = 2,
            lastWatched = now - 1_000L
        )

        val successor = resolveFreshRewatchSuccessor(
            contentIds = setOf("series-1"),
            episodes = episodes(1, 2, 3),
            watchedEpisodes = setOf(
                1 to 1,
                1 to 2,
                1 to 3
            ),
            fallbackProgressMap = emptyMap(),
            nextUpSeeds = listOf(seed),
            nowEpochMs = now,
            maxAgeMs = 5_000L
        )

        assertNull(successor)
    }

    private fun episodes(vararg numbers: Int): List<Video> =
        numbers.map { episode ->
            Video(
                id = "series-1:1:$episode",
                title = "Episode $episode",
                released = "2026-01-01",
                thumbnail = null,
                season = 1,
                episode = episode,
                overview = null,
                available = true
            )
        }

    private fun completedProgress(
        contentId: String = "series-1",
        episode: Int,
        lastWatched: Long
    ) = WatchProgress(
        contentId = contentId,
        contentType = "series",
        name = "Series",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "$contentId:1:$episode",
        season = 1,
        episode = episode,
        episodeTitle = "Episode $episode",
        position = 100L,
        duration = 100L,
        lastWatched = lastWatched,
        progressPercent = 100f,
        completionOverride = true
    )
}
