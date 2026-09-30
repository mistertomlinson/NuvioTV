package com.nuvio.tv.data.repository

import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SimklStalePlaybackSuppressionTest {

    @Test
    fun `completed episode suppresses older stale Simkl playback after restart`() {
        /*
         * Simulate the exact failure:
         *
         * E8 was completed at t=200.
         * Simkl still has an old near-finished playback at t=100.
         *
         * Even if Simkl reports a percentage below the application's
         * completion threshold, the durable completion is authoritative.
         */
        val stalePlayback =
            progress(
                episode = 8,
                percent = 84f,
                lastWatched = 100L,
                source =
                    WatchProgress.SOURCE_SIMKL_PLAYBACK
            )

        val result =
            suppressStaleSimklPlaybackAlreadyWatched(
                providerId =
                    TrackingProviderId.SIMKL,
                providerEntries =
                    listOf(stalePlayback),
                watchedItems =
                    listOf(
                        watched(
                            episode = 8,
                            watchedAt = 200L
                        )
                    )
            )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `newer Simkl replay survives older completed history`() {
        val newerReplay =
            progress(
                episode = 8,
                percent = 25f,
                lastWatched = 300L,
                source =
                    WatchProgress.SOURCE_SIMKL_PLAYBACK
            )

        val result =
            suppressStaleSimklPlaybackAlreadyWatched(
                providerId =
                    TrackingProviderId.SIMKL,
                providerEntries =
                    listOf(newerReplay),
                watchedItems =
                    listOf(
                        watched(
                            episode = 8,
                            watchedAt = 200L
                        )
                    )
            )

        assertEquals(1, result.size)
        assertSame(
            newerReplay,
            result.single()
        )
    }

    @Test
    fun `watched timestamp for another episode cannot suppress playback`() {
        val episodeEight =
            progress(
                episode = 8,
                percent = 50f,
                lastWatched = 100L,
                source =
                    WatchProgress.SOURCE_SIMKL_PLAYBACK
            )

        val result =
            suppressStaleSimklPlaybackAlreadyWatched(
                providerId =
                    TrackingProviderId.SIMKL,
                providerEntries =
                    listOf(episodeEight),
                watchedItems =
                    listOf(
                        watched(
                            episode = 7,
                            watchedAt = 200L
                        )
                    )
            )

        assertSame(
            episodeEight,
            result.single()
        )
    }

    @Test
    fun `Simkl durable progress is never removed by watched suppression`() {
        val durable =
            progress(
                episode = 8,
                percent = 50f,
                lastWatched = 100L,
                source =
                    WatchProgress.SOURCE_SIMKL_DURABLE
            )

        val result =
            suppressStaleSimklPlaybackAlreadyWatched(
                providerId =
                    TrackingProviderId.SIMKL,
                providerEntries =
                    listOf(durable),
                watchedItems =
                    listOf(
                        watched(
                            episode = 8,
                            watchedAt = 200L
                        )
                    )
            )

        assertSame(
            durable,
            result.single()
        )
    }

    @Test
    fun `completed Simkl history is never suppressed`() {
        val completedHistory =
            progress(
                episode = 7,
                percent = 100f,
                lastWatched = 200L,
                source =
                    WatchProgress.SOURCE_SIMKL_PLAYBACK
            )

        val result =
            suppressStaleSimklPlaybackAlreadyWatched(
                providerId =
                    TrackingProviderId.SIMKL,
                providerEntries =
                    listOf(completedHistory),
                watchedItems =
                    listOf(
                        watched(
                            episode = 7,
                            watchedAt = 200L
                        )
                    )
            )

        assertEquals(1, result.size)
        assertSame(
            completedHistory,
            result.single()
        )
    }

    @Test
    fun `old partial episode cannot replace newer completed state`() {
        val stalePartial =
            progress(
                episode = 7,
                percent = 42f,
                lastWatched = 100L,
                source =
                    WatchProgress.SOURCE_SIMKL_PLAYBACK
            )

        val result =
            suppressStaleSimklPlaybackAlreadyWatched(
                providerId =
                    TrackingProviderId.SIMKL,
                providerEntries =
                    listOf(stalePartial),
                watchedItems =
                    listOf(
                        watched(
                            episode = 7,
                            watchedAt = 200L
                        )
                    )
            )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `new partial replay can replace older completed state`() {
        val freshReplay =
            progress(
                episode = 7,
                percent = 42f,
                lastWatched = 300L,
                source =
                    WatchProgress.SOURCE_SIMKL_PLAYBACK
            )

        val result =
            suppressStaleSimklPlaybackAlreadyWatched(
                providerId =
                    TrackingProviderId.SIMKL,
                providerEntries =
                    listOf(freshReplay),
                watchedItems =
                    listOf(
                        watched(
                            episode = 7,
                            watchedAt = 200L
                        )
                    )
            )

        assertEquals(1, result.size)
        assertSame(
            freshReplay,
            result.single()
        )
    }

    @Test
    fun `Trakt progress is completely unaffected`() {
        val traktPlayback =
            progress(
                episode = 8,
                percent = 50f,
                lastWatched = 100L,
                source =
                    WatchProgress.SOURCE_TRAKT_PLAYBACK
            )

        val result =
            suppressStaleSimklPlaybackAlreadyWatched(
                providerId =
                    TrackingProviderId.TRAKT,
                providerEntries =
                    listOf(traktPlayback),
                watchedItems =
                    listOf(
                        watched(
                            episode = 8,
                            watchedAt = 200L
                        )
                    )
            )

        assertSame(
            traktPlayback,
            result.single()
        )
    }

    private fun progress(
        episode: Int,
        percent: Float,
        lastWatched: Long,
        source: String
    ) = WatchProgress(
        contentId = CONTENT_ID,
        contentType = "series",
        name = "Series",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "$CONTENT_ID:1:$episode",
        season = 1,
        episode = episode,
        episodeTitle = "Episode $episode",
        position = percent.toLong(),
        duration = 100L,
        lastWatched = lastWatched,
        progressPercent = percent,
        source = source
    )

    private fun watched(
        episode: Int,
        watchedAt: Long
    ) = WatchedItem(
        contentId = CONTENT_ID,
        contentType = "series",
        title = "Series",
        season = 1,
        episode = episode,
        watchedAt = watchedAt
    )

    private companion object {
        const val CONTENT_ID =
            "tt-restart-cw-test"
    }
}
