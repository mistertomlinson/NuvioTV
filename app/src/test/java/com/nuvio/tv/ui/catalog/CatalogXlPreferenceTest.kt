package com.nuvio.tv.ui.catalog

import org.junit.Assert.*
import org.junit.Test

class CatalogXlPreferenceTest {
    @Test fun `ordinary rows keep independent XL settings`() {
        assertEquals("addon_movie_top", xlCatalogPreferenceKey("addon_movie_top"))
        assertNotEquals(xlCatalogPreferenceKey("addon_movie_top"), xlCatalogPreferenceKey("addon_series_top"))
    }
    @Test fun `new seasonal rows inherit the existing seasonal XL setting`() {
        assertEquals(SEASONAL_SPOTLIGHT_ORDER_ANCHOR, xlCatalogPreferenceKey("community.seasonalspotlight_movie_seasonalspotlight.slot01"))
        assertEquals(SEASONAL_SPOTLIGHT_ORDER_ANCHOR, xlCatalogPreferenceKey("community.seasonalspotlight_series_seasonalspotlight.slot05"))
        assertEquals(SEASONAL_SPOTLIGHT_ORDER_ANCHOR, xlCatalogPreferenceKey(SEASONAL_SPOTLIGHT_ORDER_ANCHOR))
    }
    @Test fun `changing Watchly seeds retains XL but does not change the other media type`() {
        val movie = xlCatalogPreferenceKey("com.bimal.watchly_movie_watchly.watched.seed1")
        assertEquals(movie, xlCatalogPreferenceKey("com.bimal.watchly_movie_watchly.watched.seed2"))
        assertNotEquals(movie, xlCatalogPreferenceKey("com.bimal.watchly_series_watchly.watched.seed1"))
        assertEquals(movie, xlCatalogPreferenceKey(movie))
    }
}
