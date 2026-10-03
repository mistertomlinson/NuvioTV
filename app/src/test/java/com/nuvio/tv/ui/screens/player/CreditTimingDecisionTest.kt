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
    fun `running analyzer without timestamp uses eighty five percent only on exit`() {
        val timing = CreditTimingUiState(
            status = CreditTimingStatus.RUNNING
        )

        assertFalse(
            shouldTreatPlaybackAsCompleted(
                timing = timing,
                positionMs = 90_000L,
                durationMs = 100_000L,
                playbackEnded = false
            )
        )
        assertFalse(
            shouldTreatPlaybackAsCompleted(
                timing = timing,
                positionMs = 84_999L,
                durationMs = 100_000L,
                playbackEnded = false,
                allowPercentageFallback = true
            )
        )
        assertTrue(
            shouldTreatPlaybackAsCompleted(
                timing = timing,
                positionMs = 85_000L,
                durationMs = 100_000L,
                playbackEnded = false,
                allowPercentageFallback = true
            )
        )
    }

    @Test
    fun `not started analyzer without timestamp uses eighty five percent only on exit`() {
        val timing = CreditTimingUiState(
            status = CreditTimingStatus.NOT_STARTED
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
                positionMs = 85_000L,
                durationMs = 100_000L,
                playbackEnded = false,
                allowPercentageFallback = true
            )
        )
    }

    @Test
    fun `failed analyzer without timestamp uses eighty five percent only on exit`() {
        val timing = CreditTimingUiState(
            status = CreditTimingStatus.FALLBACK
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
                positionMs = 85_000L,
                durationMs = 100_000L,
                playbackEnded = false,
                allowPercentageFallback = true
            )
        )
    }

    @Test
    fun `initial credits timestamp beats eighty five percent while analyzer is running`() {
        val timing = CreditTimingUiState(
            status = CreditTimingStatus.RUNNING,
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
    fun `post credit final timestamp does not delay eighty five percent fallback without initial credits`() {
        val timing = CreditTimingUiState(
            status = CreditTimingStatus.RUNNING,
            finalCreditsStartMs = 98_000L,
            hasPostCreditScenes = true,
            postCreditScenes = listOf(
                PostCreditSceneTiming(
                    startMs = 92_000L,
                    endMs = 96_000L
                )
            )
        )

        assertTrue(
            shouldTreatPlaybackAsCompleted(
                timing = timing,
                positionMs = 85_000L,
                durationMs = 100_000L,
                playbackEnded = false,
                allowPercentageFallback = true
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
    fun `manual near end exit uses percentage while analyzer is running without timestamp`() {
        val state = PlayerUiState(
            contentType = "movie",
            creditTiming = CreditTimingUiState(
                status = CreditTimingStatus.RUNNING
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
    fun `two minute grace applies only when explicitly requested`() {
        val timing = CreditTimingUiState(
            status = CreditTimingStatus.COMPLETE,
            creditsStartMs = 600_000L,
            finalCreditsStartMs = 660_000L
        )

        assertFalse(
            shouldTreatPlaybackAsCompleted(
                timing = timing,
                positionMs = 480_000L,
                durationMs = 700_000L,
                playbackEnded = false
            )
        )
        assertTrue(
            shouldTreatPlaybackAsCompleted(
                timing = timing,
                positionMs = 480_000L,
                durationMs = 700_000L,
                playbackEnded = false,
                creditBoundaryGraceMs =
                    CREDIT_COMPLETION_EXIT_GRACE_MS
            )
        )
    }

    @Test
    fun `skip credits remains unavailable before exact initial credits`() {
        val timing = CreditTimingUiState(
            status = CreditTimingStatus.COMPLETE,
            creditsStartMs = 600_000L,
            finalCreditsStartMs = 700_000L,
            hasPostCreditScenes = true,
            postCreditScenes = listOf(
                PostCreditSceneTiming(
                    startMs = 650_000L,
                    endMs = 665_000L
                )
            )
        )

        assertNull(
            postCreditSkipTarget(
                timing = timing,
                positionMs = 599_999L
            )
        )
        assertEquals(
            650_000L,
            postCreditSkipTarget(
                timing = timing,
                positionMs = 600_000L
            )?.startMs
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
