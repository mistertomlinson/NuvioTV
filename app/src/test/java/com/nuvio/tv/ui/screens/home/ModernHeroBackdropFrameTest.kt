package com.nuvio.tv.ui.screens.home

import org.junit.Assert.*
import org.junit.Test

class ModernHeroBackdropFrameTest {
    @Test fun `blur never adopts the regular backdrop adaptive scale or fallback alignment`() {
        val first = modernHeroBackdropFrame("landscape", 1.1f, true, true)
        val settled = modernHeroBackdropFrame("landscape", 1f / 1.1f, false, true)
        assertEquals(first, settled)
        assertEquals(1f, first.scale, 0f)
        assertFalse(first.isPosterFallback)
    }

    @Test fun `late measurement cannot start another blurred crossfade`() {
        val first = modernHeroBackdropFrame("landscape", 1.1f, false, true).apply {
            sourceWidthPx = 576; sourceHeightPx = 324; sourceCinematic = false
        }
        val settled = modernHeroBackdropFrame("landscape", 0.95f, true, true).apply {
            sourceWidthPx = 1056; sourceHeightPx = 594; sourceCinematic = true
        }
        assertEquals(first, settled)
        assertEquals(first.hashCode(), settled.hashCode())
    }

    @Test fun `ordinary artwork retains its original scale and poster alignment`() {
        val first = modernHeroBackdropFrame("fanart", 1.1f, true, false)
        assertEquals(1.1f, first.scale, 0f)
        assertTrue(first.isPosterFallback)
        assertNotEquals(first, modernHeroBackdropFrame("fanart", 1f, false, false))
    }

    @Test fun `changing the title or blur variant still starts a new image transition`() {
        val first = modernHeroBackdropFrame("A", 1f, false, true)
        assertNotEquals(first, modernHeroBackdropFrame("B", 1f, false, true))
        assertNotEquals(first, modernHeroBackdropFrame("A", 1f, false, false))
    }
}
