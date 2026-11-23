package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Test

class XlHeroBackdropAblationTest {
    @Test fun hidesHeroMediaOnlyDuringCompactDownwardMotion() {
        assertEquals(0f, xlCompactExitHeroMediaAlpha(true, true), 0f)
        assertEquals(1f, xlCompactExitHeroMediaAlpha(true, false), 0f)
        assertEquals(1f, xlCompactExitHeroMediaAlpha(false, true), 0f)
        assertEquals(1f, xlCompactExitHeroMediaAlpha(false, false), 0f)
    }
}
