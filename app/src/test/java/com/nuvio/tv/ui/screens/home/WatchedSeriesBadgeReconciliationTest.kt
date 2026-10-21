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
}
