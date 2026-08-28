package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimklRefreshPolicyTest {

    @Test
    fun `automatic refresh remains throttled inside interval`() {
        assertFalse(
            shouldRunSimklRefresh(
                intent = TrackingRefreshIntent.AUTOMATIC,
                lastCheckedAtEpochMs = 1_000L,
                nowEpochMs = 2_000L,
                hasError = false,
                automaticIntervalMs = 10_000L
            )
        )
    }

    @Test
    fun `foreground refresh bypasses automatic interval`() {
        assertTrue(
            shouldRunSimklRefresh(
                intent = TrackingRefreshIntent.FOREGROUND,
                lastCheckedAtEpochMs = 1_000L,
                nowEpochMs = 2_000L,
                hasError = false,
                automaticIntervalMs = 10_000L
            )
        )
    }
}
