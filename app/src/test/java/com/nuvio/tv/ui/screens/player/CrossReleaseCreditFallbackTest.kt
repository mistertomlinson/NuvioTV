package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossReleaseCreditFallbackTest {

    private val scenes = listOf(
        PostCreditSceneTiming(
            startMs = 120_000L,
            endMs = 140_000L
        ),
        PostCreditSceneTiming(
            startMs = 180_000L,
            endMs = 200_000L
        )
    )

    @Test
    fun `running cross release fallback offers first post credit scene`() {
        val timing = CreditTimingUiState(
            status = CreditTimingStatus.RUNNING,
            creditsStartMs = 100_000L,
            finalCreditsStartMs = 220_000L,
            hasPostCreditScenes = true,
            postCreditScenes = scenes
        )

        assertEquals(
            120_000L,
            postCreditSkipTarget(
                timing = timing,
                positionMs = 105_000L
            )?.startMs
        )
    }

    @Test
    fun `failed fresh analysis preserves cached skip credits behavior`() {
        val timing = CreditTimingUiState(
            status = CreditTimingStatus.FALLBACK,
            creditsStartMs = 100_000L,
            finalCreditsStartMs = 220_000L,
            hasPostCreditScenes = true,
            postCreditScenes = scenes
        )

        assertEquals(
            180_000L,
            postCreditSkipTarget(
                timing = timing,
                positionMs = 150_000L,
                skippedSceneStarts = setOf(120_000L)
            )?.startMs
        )

        assertFalse(
            authoritativeEndActionDecision(
                timing = timing,
                positionMs = 219_999L
            )!!
        )
        assertTrue(
            authoritativeEndActionDecision(
                timing = timing,
                positionMs = 220_000L
            )!!
        )
    }

    @Test
    fun `running analyzer without cached scenes does not invent skip target`() {
        val timing = CreditTimingUiState(
            status = CreditTimingStatus.RUNNING,
            creditsStartMs = 100_000L,
            finalCreditsStartMs = 220_000L
        )

        assertNull(
            postCreditSkipTarget(
                timing = timing,
                positionMs = 105_000L
            )
        )
    }

    @Test
    fun `fallback while inside cached scene shows no skip target`() {
        val timing = CreditTimingUiState(
            status = CreditTimingStatus.FALLBACK,
            creditsStartMs = 100_000L,
            finalCreditsStartMs = 220_000L,
            hasPostCreditScenes = true,
            postCreditScenes = scenes
        )

        assertNull(
            postCreditSkipTarget(
                timing = timing,
                positionMs = 125_000L
            )
        )
    }
}
