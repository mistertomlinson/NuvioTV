package com.nuvio.tv.ui.screens.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroPlayActionReadinessTest {
    @Test
    fun `series Play stays hidden while next episode and history are unresolved`() {
        assertFalse(
            shouldRevealHeroPlayAction(
                isSeries = true,
                isComingSoon = false,
                hasResolvedNextToWatch = false
            )
        )
    }

    @Test
    fun `series Play is revealed once the resolved next action is ready`() {
        assertTrue(
            shouldRevealHeroPlayAction(
                isSeries = true,
                isComingSoon = false,
                hasResolvedNextToWatch = true
            )
        )
    }

    @Test
    fun `movie Play remains immediate`() {
        assertTrue(
            shouldRevealHeroPlayAction(
                isSeries = false,
                isComingSoon = false,
                hasResolvedNextToWatch = false
            )
        )
    }

    @Test
    fun `Coming Soon reminder remains immediate for a series`() {
        assertTrue(
            shouldRevealHeroPlayAction(
                isSeries = true,
                isComingSoon = true,
                hasResolvedNextToWatch = false
            )
        )
    }
}
