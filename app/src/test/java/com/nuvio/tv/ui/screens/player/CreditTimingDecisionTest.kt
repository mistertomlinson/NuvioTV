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
    fun `completion waits for initial credits even above eighty five percent`() {
        val timing = CreditTimingUiState(
            status = CreditTimingStatus.COMPLETE,
            creditsStartMs = 95_000L,
            finalCreditsStartMs = 120_000L
        )

        assertFalse(
            shouldTreatPlaybackAsCompleted(
                timing = timing,
                positionMs = 90_000L,
                durationMs = 100_000L,
                playbackEnded = false
            )
        )
        assertTrue(
            shouldTreatPlaybackAsCompleted(
                timing = timing,
                positionMs = 95_000L,
                durationMs = 100_000L,
                playbackEnded = false
            )
        )
    }

    @Test
    fun `initial credits complete title without requiring post credit scenes`() {
        val timing = CreditTimingUiState(
            status = CreditTimingStatus.COMPLETE,
            creditsStartMs = 80_000L,
            finalCreditsStartMs = 120_000L,
            hasPostCreditScenes = true,
            postCreditScenes = listOf(
                PostCreditSceneTiming(
                    startMs = 95_000L,
                    endMs = 105_000L
                )
            )
        )

        assertTrue(
            shouldTreatPlaybackAsCompleted(
                timing = timing,
                positionMs = 80_000L,
                durationMs = 130_000L,
                playbackEnded = false
            )
        )
    }

    @Test
    fun `running analyzer without timestamp never uses percentage completion`() {
        val timing = CreditTimingUiState(
            status = CreditTimingStatus.RUNNING
        )

        assertFalse(
            shouldTreatPlaybackAsCompleted(
                timing = timing,
                positionMs = 99_000L,
                durationMs = 100_000L,
                playbackEnded = false
            )
        )
    }

    @Test
    fun `eighty five percent is used only after analyzer fallback`() {
        val timing = CreditTimingUiState(
            status = CreditTimingStatus.FALLBACK
        )

        assertFalse(
            shouldTreatPlaybackAsCompleted(
                timing = timing,
                positionMs = 84_999L,
                durationMs = 100_000L,
                playbackEnded = false
            )
        )
        assertTrue(
            shouldTreatPlaybackAsCompleted(
                timing = timing,
                positionMs = 85_000L,
                durationMs = 100_000L,
                playbackEnded = false
            )
        )
    }

    @Test
    fun `fallback timestamp still beats percentage`() {
        val timing = CreditTimingUiState(
            status = CreditTimingStatus.FALLBACK,
            creditsStartMs = 95_000L
        )

        assertFalse(
            shouldTreatPlaybackAsCompleted(
                timing = timing,
                positionMs = 90_000L,
                durationMs = 100_000L,
                playbackEnded = false
            )
        )
        assertTrue(
            shouldTreatPlaybackAsCompleted(
                timing = timing,
                positionMs = 95_000L,
                durationMs = 100_000L,
                playbackEnded = false
            )
        )
    }

    @Test
    fun `natural playback end always completes`() {
        assertTrue(
            shouldTreatPlaybackAsCompleted(
                timing = CreditTimingUiState(
                    status = CreditTimingStatus.RUNNING
                ),
                positionMs = 50_000L,
                durationMs = 100_000L,
                playbackEnded = true
            )
        )
    }

    @Test
    fun `manual near end exit does not use percentage while analyzer is running`() {
        val state = PlayerUiState(
            contentType = "movie",
            creditTiming = CreditTimingUiState(
                status = CreditTimingStatus.RUNNING
            )
        )

        assertFalse(
            shouldStartManualEndAction(
                state = state,
                positionMs = 90_000L,
                durationMs = 100_000L
            )
        )
    }

    @Test
    fun `manual near end exit uses percentage after analyzer fallback`() {
        val state = PlayerUiState(
            contentType = "movie",
            creditTiming = CreditTimingUiState(
                status = CreditTimingStatus.FALLBACK
            )
        )

        assertTrue(
            shouldStartManualEndAction(
                state = state,
                positionMs = 90_000L,
                durationMs = 100_000L
            )
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
