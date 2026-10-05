package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimklZeroHistoryWatchingCleanupTest {

    @Test
    fun `last unwatched episode removes zero-progress Watching parent`() {
        val snapshot = snapshot(
            status = SimklListStatus.WATCHING,
            watchedEpisodesCount = 0,
            watchedAt = null
        )

        val targets = snapshot.zeroHistoryWatchingCleanupTargets(listOf(episodeReference()))

        assertEquals(1, targets.size)
        assertNull(targets.single().episode)
    }

    @Test
    fun `remaining watched episodes keep show in Watching`() {
        val snapshot = snapshot(
            status = SimklListStatus.WATCHING,
            watchedEpisodesCount = 1,
            watchedAt = "2026-10-05T20:00:00Z"
        )

        val targets = snapshot.zeroHistoryWatchingCleanupTargets(listOf(episodeReference()))

        assertTrue(targets.isEmpty())
    }

    @Test
    fun `other list statuses are never removed automatically`() {
        val snapshot = snapshot(
            status = SimklListStatus.PLAN_TO_WATCH,
            watchedEpisodesCount = 0,
            watchedAt = null
        )

        val targets = snapshot.zeroHistoryWatchingCleanupTargets(listOf(episodeReference()))

        assertTrue(targets.isEmpty())
    }

    @Test
    fun `rated zero-progress Watching show is still removed`() {
        val snapshot = snapshot(
            status = SimklListStatus.WATCHING,
            watchedEpisodesCount = 0,
            watchedAt = null,
            rating = 8
        )

        val targets = snapshot.zeroHistoryWatchingCleanupTargets(listOf(episodeReference()))

        assertEquals(1, targets.size)
        assertNull(targets.single().episode)
    }

    private fun snapshot(
        status: SimklListStatus,
        watchedEpisodesCount: Int,
        watchedAt: String?,
        rating: Int? = null
    ): SimklSyncSnapshot = SimklSyncSnapshot(
        isInitialized = true,
        entries = listOf(
            SimklLibraryEntry(
                mediaType = SimklMediaType.SHOWS,
                status = status,
                userRating = rating,
                watchedEpisodesCount = watchedEpisodesCount,
                show = SimklMedia(
                    title = "Cleanup Test",
                    ids = mapOf(
                        "simkl" to JsonPrimitive(123456),
                        "tmdb" to JsonPrimitive(987654)
                    )
                ),
                seasons = listOf(
                    SimklSeason(
                        number = 1,
                        episodes = listOf(
                            SimklEpisode(
                                number = 1,
                                watchedAt = watchedAt
                            )
                        )
                    )
                )
            )
        )
    )

    private fun episodeReference(): TrackingMediaReference = TrackingMediaReference(
        kind = TrackingMediaKind.SHOW,
        ids = TrackingExternalIds(tmdb = 987654),
        episode = TrackingEpisode(season = 1, number = 1)
    )
}
