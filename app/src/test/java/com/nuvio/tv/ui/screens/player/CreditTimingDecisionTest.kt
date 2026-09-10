package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreditTimingDecisionTest {

    private val detectedCredits = CreditTimingUiState(
        status = CreditTimingStatus.COMPLETE,
        finalCreditsStartMs = 10_000L
    )

    @Test
    fun `default end action begins exactly at detected credits`() {
        assertFalse(
            authoritativeEndActionDecision(
                timing = detectedCredits,
                positionMs = 9_999L
            ) == true
        )
        assertTrue(
            authoritativeEndActionDecision(
                timing = detectedCredits,
                positionMs = 10_000L
            ) == true
        )
    }

    @Test
    fun `next episode lead begins three seconds before detected credits`() {
        assertFalse(
            authoritativeEndActionDecision(
                timing = detectedCredits,
                positionMs = 6_999L,
                leadTimeMs = NEXT_EPISODE_CREDIT_LEAD_MS
            ) == true
        )
        assertTrue(
            authoritativeEndActionDecision(
                timing = detectedCredits,
                positionMs = 7_000L,
                leadTimeMs = NEXT_EPISODE_CREDIT_LEAD_MS
            ) == true
        )
    }
}
