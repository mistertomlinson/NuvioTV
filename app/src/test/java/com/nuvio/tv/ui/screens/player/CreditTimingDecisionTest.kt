package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `explicit lead remains opt in`() {
        assertFalse(
            authoritativeEndActionDecision(
                timing = detectedCredits,
                positionMs = 6_999L,
                leadTimeMs = 3_000L
            ) == true
        )
        assertTrue(
            authoritativeEndActionDecision(
                timing = detectedCredits,
                positionMs = 7_000L,
                leadTimeMs = 3_000L
            ) == true
        )
    }

    @Test
    fun `consumed post credit scene is not offered again after rewind`() {
        val timing = CreditTimingUiState(
            status = CreditTimingStatus.COMPLETE,
            creditsStartMs = 100_000L,
            finalCreditsStartMs = 200_000L,
            hasPostCreditScenes = true,
            postCreditScenes = listOf(
                PostCreditSceneTiming(
                    startMs = 150_000L,
                    endMs = 160_000L
                )
            )
        )

        assertEquals(
            150_000L,
            postCreditSkipTarget(
                timing = timing,
                positionMs = 120_000L
            )?.startMs
        )

        assertNull(
            postCreditSkipTarget(
                timing = timing,
                positionMs = 120_000L,
                skippedSceneStarts = setOf(150_000L)
            )
        )
    }

    @Test
    fun `consuming one post credit scene does not suppress later separated scene`() {
        val timing = CreditTimingUiState(
            status = CreditTimingStatus.COMPLETE,
            creditsStartMs = 100_000L,
            finalCreditsStartMs = 240_000L,
            hasPostCreditScenes = true,
            postCreditScenes = listOf(
                PostCreditSceneTiming(
                    startMs = 150_000L,
                    endMs = 160_000L
                ),
                PostCreditSceneTiming(
                    startMs = 190_000L,
                    endMs = 200_000L
                )
            )
        )

        assertEquals(
            190_000L,
            postCreditSkipTarget(
                timing = timing,
                positionMs = 170_001L,
                skippedSceneStarts = setOf(150_000L)
            )?.startMs
        )
    }

}
