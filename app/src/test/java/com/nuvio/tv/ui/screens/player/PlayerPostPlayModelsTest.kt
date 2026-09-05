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
    fun `running analyzer uses cross release estimate when available`() {
        val timing = CreditTimingUiState(
            status = CreditTimingStatus.RUNNING,
            creditsStartMs = 6_300_000L,
            finalCreditsStartMs = 6_700_000L,
            hasPostCreditScenes = true
        )

        assertFalse(authoritativeEndActionDecision(timing, 6_699_999L)!!)
        assertTrue(authoritativeEndActionDecision(timing, 6_700_000L)!!)
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

    @Test
    fun `failed exact analysis retains cross release estimate`() {
        val timing = CreditTimingUiState(
            status = CreditTimingStatus.FALLBACK,
            finalCreditsStartMs = 6_700_000L
        )

        assertFalse(authoritativeEndActionDecision(timing, 6_699_999L)!!)
        assertTrue(authoritativeEndActionDecision(timing, 6_700_000L)!!)
    }

    @Test
    fun `content key is stable across episode releases`() {
        val first = buildCreditAnalyzerContentKey(
            contentType = "series",
            contentId = "tmdb:123",
            videoId = "release-one",
            season = 1,
            episode = 4
        )
        val second = buildCreditAnalyzerContentKey(
            contentType = "tv",
            contentId = "TMDB:123",
            videoId = "release-two",
            season = 1,
            episode = 4
        )

        assertEquals(first, second)
        assertEquals(64, first?.length)
    }

    @Test
    fun `content key separates episodes`() {
        val first = buildCreditAnalyzerContentKey(
            contentType = "series",
            contentId = "tmdb:123",
            videoId = null,
            season = 1,
            episode = 4
        )
        val next = buildCreditAnalyzerContentKey(
            contentType = "series",
            contentId = "tmdb:123",
            videoId = null,
            season = 1,
            episode = 5
        )

        assertTrue(first != next)
    }
}
