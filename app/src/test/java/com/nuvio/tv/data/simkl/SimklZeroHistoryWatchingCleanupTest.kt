package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResult
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

    @Test
    fun `removing one episode from Completed series downgrades parent but keeps remaining history`() {
        val firstEpisode = episodeReference(number = 1)
        val initial = snapshot(
            status = SimklListStatus.COMPLETED,
            watchedEpisodesCount = 2,
            watchedAt = "2026-10-21T16:00:00Z",
            watchedEpisodeTimes = listOf(
                "2026-10-21T15:00:00Z",
                "2026-10-21T16:00:00Z"
            )
        )

        val updated = initial.applyMutationReceipt(
            receipt = removalReceipt(firstEpisode),
            committedAtEpochMs = 1_761_063_600_000L
        )

        val entry = updated.entries.single()
        assertEquals(SimklListStatus.WATCHING, entry.status)
        assertEquals(1, entry.watchedEpisodesCount)
        assertTrue(updated.zeroHistoryWatchingCleanupTargets(listOf(firstEpisode)).isEmpty())
    }

    @Test
    fun `removing every episode from Completed series makes parent eligible for cleanup`() {
        val firstEpisode = episodeReference(number = 1)
        val secondEpisode = episodeReference(number = 2)
        val initial = snapshot(
            status = SimklListStatus.COMPLETED,
            watchedEpisodesCount = 2,
            watchedAt = "2026-10-21T16:00:00Z",
            watchedEpisodeTimes = listOf(
                "2026-10-21T15:00:00Z",
                "2026-10-21T16:00:00Z"
            )
        )

        val updated = initial.applyMutationReceipt(
            receipt = removalReceipt(firstEpisode, secondEpisode),
            committedAtEpochMs = 1_761_063_600_000L
        )

        val entry = updated.entries.single()
        assertEquals(SimklListStatus.WATCHING, entry.status)
        assertEquals(0, entry.watchedEpisodesCount)
        val targets = updated.zeroHistoryWatchingCleanupTargets(listOf(firstEpisode, secondEpisode))
        assertEquals(1, targets.size)
        assertNull(targets.single().episode)
    }

    private fun snapshot(
        status: SimklListStatus,
        watchedEpisodesCount: Int,
        watchedAt: String?,
        rating: Int? = null,
        watchedEpisodeTimes: List<String?> = listOf(watchedAt)
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
                        episodes = watchedEpisodeTimes.mapIndexed { index, episodeWatchedAt ->
                            SimklEpisode(
                                number = index + 1,
                                watchedAt = episodeWatchedAt
                            )
                        }
                    )
                )
            )
        )
    )

    private fun episodeReference(number: Int = 1): TrackingMediaReference = TrackingMediaReference(
        kind = TrackingMediaKind.SHOW,
        ids = TrackingExternalIds(tmdb = 987654),
        episode = TrackingEpisode(season = 1, number = number)
    )

    private fun removalReceipt(vararg references: TrackingMediaReference): SimklMutationReceipt =
        SimklMutationReceipt(
            result = TrackingMutationResult(attemptedCount = references.size),
            mutation = SimklCommittedMutation.RemoveFromHistory(
                items = references.toList(),
                deleted = SimklDeletedCounts(episodes = references.size)
            )
        )
}
