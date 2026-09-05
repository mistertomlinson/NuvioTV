package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPostPlayModelsTest {
    @Test
    fun `running analyzer suppresses fallback timing`() {
        val timing = CreditTimingUiState(status = CreditTimingStatus.RUNNING)

        assertEquals(false, authoritativeEndActionDecision(timing, positionMs = 9_999_999L))
    }

    @Test
    fun `final credit boundary is used instead of initial credits`() {
        val timing = CreditTimingUiState(
            status = CreditTimingStatus.COMPLETE,
            creditsStartMs = 6_297_575L,
            finalCreditsStartMs = 6_695_575L,
            hasPostCreditScenes = true
        )

        assertFalse(authoritativeEndActionDecision(timing, 6_297_575L)!!)
        assertTrue(authoritativeEndActionDecision(timing, 6_695_575L)!!)
    }

    @Test
    fun `fallback status delegates to legacy timing`() {
        val timing = CreditTimingUiState(status = CreditTimingStatus.FALLBACK)

        assertNull(authoritativeEndActionDecision(timing, positionMs = 1_000L))
    }
}
