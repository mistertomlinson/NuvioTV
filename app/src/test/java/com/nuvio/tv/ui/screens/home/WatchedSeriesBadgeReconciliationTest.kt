package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchedSeriesBadgeReconciliationTest {
    @Test
    fun `authoritative zero history disqualifies persisted badge`() {
        assertEquals(
            setOf("tt-staircase"),
            authoritativeMissingWatchedSeriesBadgeIds(
                currentBadgeIds = setOf("tt-staircase"),
                watchedHistoryIds = emptySet(),
                historyAuthoritative = true
            )
        )
    }

    @Test
    fun `provider loading never clears persisted badge`() {
        assertTrue(
            authoritativeMissingWatchedSeriesBadgeIds(
                currentBadgeIds = setOf("tt-staircase"),
                watchedHistoryIds = emptySet(),
                historyAuthoritative = false
            ).isEmpty()
        )
    }

    @Test
    fun `series still present in authoritative history is preserved`() {
        assertTrue(
            authoritativeMissingWatchedSeriesBadgeIds(
                currentBadgeIds = setOf("tt-staircase"),
                watchedHistoryIds = setOf("tt-staircase"),
                historyAuthoritative = true
            ).isEmpty()
        )
    }

    @Test
    fun `only badges absent from authoritative history are disqualified`() {
        assertEquals(
            setOf("tt-removed"),
            authoritativeMissingWatchedSeriesBadgeIds(
                currentBadgeIds = setOf("tt-kept", "tt-removed"),
                watchedHistoryIds = setOf("tt-kept", "tt-other"),
                historyAuthoritative = true
            )
        )
    }

    @Test
    fun `completed ended show retains checkmark during replay despite incomplete history`() {
        assertEquals(
            setOf("tt-ended"),
            reconcileCompletedSeriesBadges(
                existingBadges = setOf("tt-ended"),
                newlyVerifiedFullyWatched = emptySet(),
                validatedMissingEpisodes = setOf("tt-ended"),
                validatedNonTerminal = emptySet(),
                missingFromWatchedHistory = emptySet(),
                activeReplayIds = setOf("tt-ended")
            )
        )
    }

    @Test
    fun `completed show replay tolerates temporarily missing provider history`() {
        assertEquals(
            setOf("tt-ended"),
            reconcileCompletedSeriesBadges(
                existingBadges = setOf("tt-ended"),
                newlyVerifiedFullyWatched = emptySet(),
                validatedMissingEpisodes = emptySet(),
                validatedNonTerminal = emptySet(),
                missingFromWatchedHistory = setOf("tt-ended"),
                activeReplayIds = setOf("tt-ended")
            )
        )
    }

    @Test
    fun `replaying an unfinished series never creates a new watched badge`() {
        assertTrue(
            reconcileCompletedSeriesBadges(
                existingBadges = emptySet(),
                newlyVerifiedFullyWatched = emptySet(),
                validatedMissingEpisodes = setOf("tt-unfinished"),
                validatedNonTerminal = emptySet(),
                missingFromWatchedHistory = emptySet(),
                activeReplayIds = setOf("tt-unfinished")
            ).isEmpty()
        )
    }

    @Test
    fun `a revived series still loses its badge during replay`() {
        assertTrue(
            reconcileCompletedSeriesBadges(
                existingBadges = setOf("tt-revived"),
                newlyVerifiedFullyWatched = emptySet(),
                validatedMissingEpisodes = emptySet(),
                validatedNonTerminal = setOf("tt-revived"),
                missingFromWatchedHistory = emptySet(),
                activeReplayIds = setOf("tt-revived")
            ).isEmpty()
        )
    }

    @Test
    fun `explicit unwatch invalidation remains authoritative`() {
        // The repository's explicit unwatch operation already invalidated
        // this show's persisted badge. A still-active replay cannot add it.
        assertTrue(
            reconcileCompletedSeriesBadges(
                existingBadges = emptySet(),
                newlyVerifiedFullyWatched = emptySet(),
                validatedMissingEpisodes = setOf("tt-unwatched"),
                validatedNonTerminal = emptySet(),
                missingFromWatchedHistory = setOf("tt-unwatched"),
                activeReplayIds = setOf("tt-unwatched")
            ).isEmpty()
        )
    }

    @Test
    fun `badge disappears when replay stops and provider history is incomplete`() {
        assertTrue(
            reconcileCompletedSeriesBadges(
                existingBadges = setOf("tt-ended"),
                newlyVerifiedFullyWatched = emptySet(),
                validatedMissingEpisodes = setOf("tt-ended"),
                validatedNonTerminal = emptySet(),
                missingFromWatchedHistory = emptySet(),
                activeReplayIds = emptySet()
            ).isEmpty()
        )
    }

    @Test
    fun `only actively replayed completed show is protected`() {
        assertEquals(
            setOf("tt-replay"),
            reconcileCompletedSeriesBadges(
                existingBadges = setOf("tt-replay", "tt-removed"),
                newlyVerifiedFullyWatched = emptySet(),
                validatedMissingEpisodes = setOf("tt-replay", "tt-removed"),
                validatedNonTerminal = emptySet(),
                missingFromWatchedHistory = emptySet(),
                activeReplayIds = setOf("tt-replay")
            )
        )
    }


}
