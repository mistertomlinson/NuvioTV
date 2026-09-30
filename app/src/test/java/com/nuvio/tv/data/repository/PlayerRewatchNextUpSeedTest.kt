package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerRewatchNextUpSeedTest {

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
                maxAgeMs = 180_000L
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
