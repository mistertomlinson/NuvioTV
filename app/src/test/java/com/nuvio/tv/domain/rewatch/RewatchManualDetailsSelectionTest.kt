package com.nuvio.tv.domain.rewatch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewatchManualDetailsSelectionTest {
    private val selected = RewatchEpisode(5, 19)
    private val series = "tt0386676"

    @Test fun detailsClickIsSingleUseAndProfileScoped() {
        RewatchManualDetailsSelection.arm(2, series, selected, 10_000L)
        assertFalse(RewatchManualDetailsSelection.consume(
            1, series, selected, 11_000L
        ))
        assertFalse(RewatchManualDetailsSelection.consume(
            2, series, RewatchEpisode(5, 18), 11_000L
        ))
        assertTrue(RewatchManualDetailsSelection.consume(
            2, series, selected, 11_000L
        ))
        assertFalse(RewatchManualDetailsSelection.consume(
            2, series, selected, 11_000L
        ))
    }

    @Test fun expiredProofNeverAuthorizesAutoplay() {
        RewatchManualDetailsSelection.arm(2, series, selected, 20_000L)
        assertFalse(RewatchManualDetailsSelection.consume(
            2, series, selected, 141_000L
        ))
    }
}
