package com.nuvio.tv.core.tracking

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingNextUpStartupRecoveryTest {

    @Test
    fun `stale provider seed recovers through series finale`() {
        val seed = progress(
            contentId = "tt1234567",
            season = 5,
            episode = 10,
            lastWatched = 100L
        )
        val watched = (1..10).map { episode ->
            watched(
                contentId = "tt1234567",
                season = 6,
                episode = episode,
                watchedAt = 100L + episode * 100L
            )
        }

        val result = recoverProviderNextUpSeedsFromWatchedHistory(
            providerSeeds = listOf(seed),
            watchedItems = watched,
            showIdSiblings = emptyMap()
        ).single()

        assertEquals(6, result.season)
        assertEquals(10, result.episode)
        assertEquals(1_100L, result.lastWatched)
        assertEquals("tt1234567", result.contentId)
    }

    @Test
    fun `partial binge advances to furthest durable completion not latest timestamp`() {
        val seed = progress(episode = 1, lastWatched = 100L)
        val watched = listOf(
            watched(episode = 2, watchedAt = 200L),
            watched(episode = 3, watchedAt = 300L),
            watched(episode = 5, watchedAt = 500L),
            watched(episode = 4, watchedAt = 600L)
        )

        val result = recoverProviderNextUpSeedsFromWatchedHistory(
            listOf(seed), watched, emptyMap()
        ).single()

        assertEquals(5, result.episode)
        assertEquals(500L, result.lastWatched)
    }

    @Test
    fun `historical watched episodes cannot hijack newer rewatch seed`() {
        val seed = progress(episode = 6, lastWatched = 5_000L)
        val watched = listOf(
            watched(episode = 7, watchedAt = 1_000L),
            watched(episode = 8, watchedAt = 2_000L)
        )

        val result = recoverProviderNextUpSeedsFromWatchedHistory(
            listOf(seed), watched, emptyMap()
        ).single()

        assertEquals(seed, result)
    }

    @Test
    fun `newer replay of earlier episode cannot regress furthest recovery`() {
        val seed = progress(episode = 5, lastWatched = 100L)
        val watched = listOf(
            watched(episode = 8, watchedAt = 200L),
            watched(episode = 2, watchedAt = 300L)
        )

        val result = recoverProviderNextUpSeedsFromWatchedHistory(
            listOf(seed), watched, emptyMap()
        ).single()

        assertEquals(8, result.episode)
        assertEquals(200L, result.lastWatched)
    }

    @Test
    fun `sibling external id recovers seed while preserving provider identity`() {
        val seed = progress(
            contentId = "tt1234567",
            episode = 1,
            lastWatched = 100L
        )
        val watched = listOf(
            watched(
                contentId = "tmdb:7654",
                episode = 8,
                watchedAt = 800L
            )
        )
        val siblings = mapOf(
            "tt1234567" to setOf("tmdb:7654"),
            "tmdb:7654" to setOf("tt1234567")
        )

        val result = recoverProviderNextUpSeedsFromWatchedHistory(
            listOf(seed), watched, siblings
        ).single()

        assertEquals("tt1234567", result.contentId)
        assertEquals(8, result.episode)
    }

    @Test
    fun `raw and prefixed imdb identities recover without sibling map`() {
        val seed = progress(
            contentId = "tt1234567",
            episode = 1,
            lastWatched = 100L
        )
        val watched = listOf(
            watched(
                contentId = "imdb:tt1234567",
                episode = 4,
                watchedAt = 400L
            )
        )

        val result = recoverProviderNextUpSeedsFromWatchedHistory(
            listOf(seed), watched, emptyMap()
        ).single()

        assertEquals(4, result.episode)
    }

    @Test
    fun `unrelated watched history is ignored`() {
        val seed = progress(
            contentId = "tt1234567",
            episode = 1,
            lastWatched = 100L
        )
        val watched = listOf(
            watched(
                contentId = "tt9999999",
                episode = 9,
                watchedAt = 900L
            )
        )

        val result = recoverProviderNextUpSeedsFromWatchedHistory(
            listOf(seed), watched, emptyMap()
        ).single()

        assertEquals(seed, result)
    }

    @Test
    fun `ambiguous sibling group cannot cross recover shows`() {
        val seed = progress(
            contentId = "tt1234567",
            episode = 1,
            lastWatched = 100L
        )
        val watched = listOf(
            watched(
                contentId = "tmdb:7654",
                episode = 9,
                watchedAt = 900L
            )
        )
        val siblings = mapOf(
            "tt1234567" to setOf("tmdb:7654", "__ambiguous__")
        )

        val result = recoverProviderNextUpSeedsFromWatchedHistory(
            listOf(seed), watched, siblings
        ).single()

        assertEquals(seed, result)
    }

    private fun progress(
        contentId: String = "tt-recovery-test",
        season: Int = 6,
        episode: Int,
        lastWatched: Long
    ) = WatchProgress(
        contentId = contentId,
        contentType = "series",
        name = "Recovery Test",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "$contentId:$season:$episode",
        season = season,
        episode = episode,
        episodeTitle = "Episode $episode",
        position = 100L,
        duration = 100L,
        lastWatched = lastWatched,
        progressPercent = 100f,
        source = WatchProgress.SOURCE_LOCAL
    )

    private fun watched(
        contentId: String = "tt-recovery-test",
        season: Int = 6,
        episode: Int,
        watchedAt: Long
    ) = WatchedItem(
        contentId = contentId,
        contentType = "series",
        title = "Recovery Test",
        season = season,
        episode = episode,
        watchedAt = watchedAt
    )
}
