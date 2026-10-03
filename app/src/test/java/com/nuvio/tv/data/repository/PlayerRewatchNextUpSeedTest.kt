package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRewatchNextUpSeedTest {

    @Test
    fun `watched successor identifies exact fresh player rewatch`() {
        val seed =
            progress(
                episode = 6,
                lastWatched = 10_000L
            )

        assertTrue(
            isPlayerRewatchNextUpDismissal(
                playerSeed = seed,
                contentId =
                    "tt-rewatch-test",
                seedSeason = 1,
                seedEpisode = 6,
                nextSeason = 1,
                nextEpisode = 7,
                nextEpisodeWasAlreadyWatched =
                    true,
                nowEpochMs = 10_100L,
                maxAgeMs = 180_000L
            )
        )
    }

    @Test
    fun `unwatched successor remains normal next up`() {
        val seed =
            progress(
                episode = 6,
                lastWatched = 10_000L
            )

        assertFalse(
            isPlayerRewatchNextUpDismissal(
                playerSeed = seed,
                contentId =
                    "tt-rewatch-test",
                seedSeason = 1,
                seedEpisode = 6,
                nextSeason = 1,
                nextEpisode = 7,
                nextEpisodeWasAlreadyWatched =
                    false,
                nowEpochMs = 10_100L,
                maxAgeMs = 180_000L
            )
        )
    }

    @Test
    fun `wrong player seed cannot bypass provider dismissal`() {
        val seed =
            progress(
                episode = 5,
                lastWatched = 10_000L
            )

        assertFalse(
            isPlayerRewatchNextUpDismissal(
                playerSeed = seed,
                contentId =
                    "tt-rewatch-test",
                seedSeason = 1,
                seedEpisode = 6,
                nextSeason = 1,
                nextEpisode = 7,
                nextEpisodeWasAlreadyWatched =
                    true,
                nowEpochMs = 10_100L,
                maxAgeMs = 180_000L
            )
        )
    }

    @Test
    fun `stale player seed cannot bypass provider dismissal`() {
        val seed =
            progress(
                episode = 6,
                lastWatched = 10_000L
            )

        assertFalse(
            isPlayerRewatchNextUpDismissal(
                playerSeed = seed,
                contentId =
                    "tt-rewatch-test",
                seedSeason = 1,
                seedEpisode = 6,
                nextSeason = 1,
                nextEpisode = 7,
                nextEpisodeWasAlreadyWatched =
                    true,
                nowEpochMs = 200_001L,
                maxAgeMs = 180_000L
            )
        )
    }

    @Test
    fun `recent player replay completion overrides older furthest seed`() {
        val providerEpisodeEight =
            progress(
                episode = 8,
                lastWatched = 1_000L
            )
        val replayedEpisodeSix =
            progress(
                episode = 6,
                lastWatched = 10_000L
            )

        val result =
            mergeProviderNextUpSeedsWithPlayerCompletions(
                providerSeeds =
                    listOf(providerEpisodeEight),
                playerCompletionSeeds =
                    listOf(replayedEpisodeSix),
                nowEpochMs = 10_100L,
                maxAgeMs = 180_000L
            )

        assertEquals(1, result.size)
        assertEquals(6, result.single().episode)
    }

    @Test
    fun `newer provider completion beats older replay seed`() {
        val providerEpisodeEight =
            progress(
                episode = 8,
                lastWatched = 20_000L
            )
        val replayedEpisodeSix =
            progress(
                episode = 6,
                lastWatched = 10_000L
            )

        val result =
            mergeProviderNextUpSeedsWithPlayerCompletions(
                providerSeeds =
                    listOf(providerEpisodeEight),
                playerCompletionSeeds =
                    listOf(replayedEpisodeSix),
                nowEpochMs = 20_100L,
                maxAgeMs = 180_000L
            )

        assertEquals(8, result.single().episode)
    }

    @Test
    fun `expired replay seed cannot override provider history`() {
        val providerEpisodeEight =
            progress(
                episode = 8,
                lastWatched = 1_000L
            )
        val replayedEpisodeSix =
            progress(
                episode = 6,
                lastWatched = 10_000L
            )

        val result =
            mergeProviderNextUpSeedsWithPlayerCompletions(
                providerSeeds =
                    listOf(providerEpisodeEight),
                playerCompletionSeeds =
                    listOf(replayedEpisodeSix),
                nowEpochMs = 200_001L,
                maxAgeMs = 180_000L
            )

        assertEquals(8, result.single().episode)
    }

    @Test
    fun `fresh player completion bridges empty provider seed list`() {
        val completedEpisodeSix =
            progress(
                episode = 6,
                lastWatched = 10_000L
            )

        val result =
            mergeProviderNextUpSeedsWithPlayerCompletions(
                providerSeeds = emptyList(),
                playerCompletionSeeds =
                    listOf(completedEpisodeSix),
                nowEpochMs = 10_100L,
                maxAgeMs = 180_000L
            )

        assertEquals(1, result.size)
        assertEquals(6, result.single().episode)
    }

    @Test
    fun `player seed cannot resurrect provider excluded show`() {
        val replayedEpisodeSix =
            progress(
                episode = 6,
                lastWatched = 10_000L
            )

        val result =
            mergeProviderNextUpSeedsWithPlayerCompletions(
                providerSeeds = emptyList(),
                playerCompletionSeeds =
                    listOf(replayedEpisodeSix),
                nowEpochMs = 10_100L,
                maxAgeMs = 180_000L,
                excludedPlayerOnlyContentIds =
                    setOf("tt-rewatch-test")
            )

        assertEquals(emptyList<WatchProgress>(), result)
    }

    private fun progress(
        episode: Int,
        lastWatched: Long
    ) = WatchProgress(
        contentId = "tt-rewatch-test",
        contentType = "series",
        name = "Rewatch Test",
        poster = null,
        backdrop = null,
        logo = null,
        videoId =
            "tt-rewatch-test:1:$episode",
        season = 1,
        episode = episode,
        episodeTitle = "Episode $episode",
        position = 100L,
        duration = 100L,
        lastWatched = lastWatched,
        progressPercent = 100f,
        source = WatchProgress.SOURCE_LOCAL
    )
}
