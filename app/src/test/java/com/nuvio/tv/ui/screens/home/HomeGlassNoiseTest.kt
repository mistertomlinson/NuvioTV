package com.nuvio.tv.ui.screens.home

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeGlassNoiseTest {
    // Model Haze 0.7.3's withNoise cutoff and Bitmap.withAlpha Paint alpha.
    private fun renderedAlpha(factor: Float): Int =
        if (factor < 0.005f) 0 else (factor * 255f).roundToInt().coerceIn(0, 255)

    @Test fun `fade keeps the original rendered noise opacity`() {
        for (step in 0..10_000) {
            val progress = step / 10_000f
            assertEquals(
                "Opacity at $progress",
                renderedAlpha(0.025f * progress),
                renderedAlpha(homeGlassNoiseFactor(progress))
            )
        }
    }

    @Test fun `noise stays enabled at the library cutoff and clamps fade endpoints`() {
        for (progress in listOf(-1f, 0f, 0.19999f, 0.2f, 0.20001f, 1f, 2f)) {
            val original = 0.025f * progress.coerceIn(0f, 1f)
            assertEquals(renderedAlpha(original), renderedAlpha(homeGlassNoiseFactor(progress)))
        }
        assertEquals(1, renderedAlpha(homeGlassNoiseFactor(0.2f)))
        assertEquals(6, renderedAlpha(homeGlassNoiseFactor(1f)))
    }

    @Test fun `equivalent pixels share a bounded set of noise cache keys`() {
        val factors = (0..10_000).map { homeGlassNoiseFactor(it / 10_000f) }
        assertTrue(factors.distinct().size <= 7)
        factors.groupBy(::renderedAlpha).values.forEach { equivalent ->
            assertEquals(1, equivalent.distinct().size)
        }
    }
}
