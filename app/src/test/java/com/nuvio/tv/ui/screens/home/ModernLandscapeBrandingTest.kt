package com.nuvio.tv.ui.screens.home

import org.junit.Assert.*
import org.junit.Test

class ModernLandscapeBrandingTest {
    @Test fun `XL retains its title logo after the trailer paints its first frame`() {
        assertFalse(hideLandscapeBrandingForTrailer(true, true, true, true))
    }

    @Test fun `regular per-row landscape still hides its chrome only after the video actually paints`() {
        assertTrue(hideLandscapeBrandingForTrailer(true, false, true, true))
        assertFalse(hideLandscapeBrandingForTrailer(true, false, true, false))
        assertFalse(hideLandscapeBrandingForTrailer(true, false, false, true))
    }

    @Test fun `universal landscape retains its previous behavior`() {
        assertFalse(hideLandscapeBrandingForTrailer(false, false, true, true))
    }
}
