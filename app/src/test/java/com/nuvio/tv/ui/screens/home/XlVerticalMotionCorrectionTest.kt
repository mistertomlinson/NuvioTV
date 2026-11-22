package com.nuvio.tv.ui.screens.home

import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XlVerticalMotionCorrectionTest {
    @Test fun subpixelCorrectionKeepsTheIncomingLegContinuousAcrossRoundedFrames() {
        val visibleDistancePx = 23f
        for (step in 0..120) {
            val fraction = step / 120f
            val exact = visibleDistancePx * fraction
            val snapped = exact.roundToInt().toFloat()
            val correction = compactIncomingFrameCorrectionPx(visibleDistancePx, fraction)
            assertEquals("frame $step", exact, snapped - correction, 0.00001f)
        }
    }

    @Test fun correctionStaysWithinHalfAPixelEvenOnReversedTravel() {
        for (distance in listOf(-23f, -1f, 0f, 1f, 23f, 77f)) {
            for (step in 0..120) {
                val correction = compactIncomingFrameCorrectionPx(distance, step / 120f)
                assertTrue("distance=$distance step=$step", abs(correction) <= 0.500001f)
            }
        }
    }

    @Test fun correctionIsZeroAtRestAndDoesNotExtrapolate() {
        for (distance in listOf(-23f, 0f, 23f)) {
            assertEquals(0f, compactIncomingFrameCorrectionPx(distance, 0f), 0.00001f)
            assertEquals(0f, compactIncomingFrameCorrectionPx(distance, 1f), 0.00001f)
            assertEquals(0f, compactIncomingFrameCorrectionPx(distance, -1f), 0.00001f)
            assertEquals(0f, compactIncomingFrameCorrectionPx(distance, 2f), 0.00001f)
        }
    }
}
