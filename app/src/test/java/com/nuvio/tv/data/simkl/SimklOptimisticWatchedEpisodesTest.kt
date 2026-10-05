package com.nuvio.tv.data.simkl

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimklOptimisticWatchedEpisodesTest {

    @Test
    fun `alias unwatch removes canonical next up seed immediately`() {
        val routeId = "tmdb:987654"

        val siblings =
            mapOf(
                routeId to
                    setOf(
                        CONTENT_ID,
                        "simkl:123456"
                    ),
                CONTENT_ID to
                    setOf(
                        routeId,
                        "simkl:123456"
                    ),
                "simkl:123456" to
                    setOf(
                        routeId,
                        CONTENT_ID
                    )
            )

        val keys =
            simklOptimisticEpisodeAliasKeys(
                contentId = routeId,
                season = 4,
                episode = 1,
                showIdSiblings = siblings
            )

        /*
         * Prove the route-ID mutation reaches the canonical ID used by
         * Simkl's watched/Next Up projection.
         */
        assertTrue(
            simklOptimisticEpisodeKey(
                CONTENT_ID,
                4,
                1
            ) in keys
        )

        val overrides =
            keys.map { key ->
                SimklOptimisticEpisodeOverride(
                    key = key,
                    watched = false,
                    progress = null,
                    updatedAtEpochMs = 6_000L
                )
            }

        val result =
            buildSimklNextUpWithEpisodeOverrides(
                remoteEntries =
                    listOf(
                        progress(
                            episode = 1,
                            lastWatched = 5_000L
                        )
                    ),
                overrides = overrides,
                preferFurthestEpisode = true
            )

        /*
         * Brand-new series:
         * watched E1 -> E2 Next Up
         * unwatch E1 -> no watched seed remains,
         * therefore the series must leave CW.
         */
        assertTrue(result.isEmpty())
    }

    @Test
    fun `successive unmarks promote the previous watched episode`() {
        val remote = (1..7).map { episode ->
            progress(episode = episode, lastWatched = episode * 1_000L)
        }
        val overrides = listOf(
            unwatchedOverride(episode = 7, updatedAt = 10_000L),
            unwatchedOverride(episode = 6, updatedAt = 11_000L),
            unwatchedOverride(episode = 5, updatedAt = 12_000L)
        )

        val result = buildSimklNextUpWithEpisodeOverrides(
            remoteEntries = remote,
            overrides = overrides,
            preferFurthestEpisode = true
        )

        assertEquals(1, result.size)
        assertEquals(4, result.single().episode)
        assertEquals(12_000L, result.single().lastWatched)
    }

    @Test
    fun `watched replay does not retimestamp historical furthest seed`() {
        val remote = listOf(
            progress(
                episode = 8,
                lastWatched = 8_000L
            )
        )
        val replayedEpisodeSix =
            progress(
                episode = 6,
                lastWatched = 12_000L
            )

        val result =
            buildSimklNextUpWithEpisodeOverrides(
                remoteEntries = remote,
                overrides = listOf(
                    SimklOptimisticEpisodeOverride(
                        key =
                            simklOptimisticEpisodeKey(
                                CONTENT_ID,
                                4,
                                6
                            ),
                        watched = true,
                        progress =
                            replayedEpisodeSix,
                        updatedAtEpochMs =
                            12_001L
                    )
                ),
                preferFurthestEpisode = true
            )

        assertEquals(1, result.size)
        assertEquals(8, result.single().episode)

        /*
         * Critical: historical E8 keeps its real old timestamp.
         * Repository-level Player replay arbitration can therefore
         * see that the new E6 completion is actually newer.
         */
        assertEquals(
            8_000L,
            result.single().lastWatched
        )
    }

    @Test
    fun `optimistic unwatch immediately removes episode progress`() {
        val remote = mapOf(
            (4 to 4) to progress(episode = 4, lastWatched = 4_000L),
            (4 to 5) to progress(episode = 5, lastWatched = 5_000L)
        )

        val result = applySimklEpisodeOverridesToProgress(
            contentId = CONTENT_ID,
            remoteEntries = remote,
            overrides = listOf(
                unwatchedOverride(episode = 5, updatedAt = 6_000L)
            )
        )

        assertTrue((4 to 4) in result)
        assertFalse((4 to 5) in result)
    }

    @Test
    fun `optimistic completion immediately adds episode progress`() {
        val completed = progress(episode = 5, lastWatched = 6_000L)
        val key = simklOptimisticEpisodeKey(CONTENT_ID, 4, 5)

        val result = applySimklEpisodeOverridesToProgress(
            contentId = CONTENT_ID,
            remoteEntries = mapOf(
                (4 to 4) to progress(episode = 4, lastWatched = 4_000L)
            ),
            overrides = listOf(
                SimklOptimisticEpisodeOverride(
                    key = key,
                    watched = true,
                    progress = completed,
                    updatedAtEpochMs = 6_000L
                )
            )
        )

        assertTrue((4 to 5) in result)
        assertEquals(completed, result[4 to 5])
    }

    @Test
    fun `watched episode snapshot honors optimistic tombstones`() {
        val result = applySimklEpisodeOverridesToWatchedEpisodes(
            remoteEntries = mapOf(
                CONTENT_ID to setOf(4 to 4, 4 to 5)
            ),
            overrides = listOf(
                unwatchedOverride(episode = 5, updatedAt = 6_000L)
            )
        )

        assertEquals(setOf(4 to 4), result[CONTENT_ID])
    }

    private fun unwatchedOverride(
        episode: Int,
        updatedAt: Long
    ): SimklOptimisticEpisodeOverride {
        val key = simklOptimisticEpisodeKey(CONTENT_ID, 4, episode)
        return SimklOptimisticEpisodeOverride(
            key = key,
            watched = false,
            progress = null,
            updatedAtEpochMs = updatedAt
        )
    }

    private fun progress(
        episode: Int,
        lastWatched: Long
    ): WatchProgress = WatchProgress(
        contentId = CONTENT_ID,
        contentType = "series",
        name = "Icons Unearthed",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "$CONTENT_ID:4:$episode",
        season = 4,
        episode = episode,
        episodeTitle = null,
        position = 1L,
        duration = 1L,
        lastWatched = lastWatched,
        progressPercent = 100f,
        source = WatchProgress.SOURCE_SIMKL_PLAYBACK
    )

    private companion object {
        const val CONTENT_ID = "tt21267394"
    }
}
