package com.nuvio.tv.ui.screens.home

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Test

/** The compact follower should paint the same path without a changing spacer. */
class XlFixedFollowerGeometryTest {
    @Test fun fixedGapPlusDrawTranslationPreservesOriginalSpringTrajectory() {
        for (from in listOf(0, 24, 90)) {
            for (to in listOf(100, 180, 330)) {
                val visibleDistance = 38
                val fullScroll = visibleDistance + to - from
                for (frame in 0..120) {
                    val fraction = frame / 120f
                    val travel = (fullScroll * fraction).roundToInt()
                    val incomingTravel = (visibleDistance * fraction).roundToInt()
                    val animatedGap = from + travel - incomingTravel
                    val drawOffset = compactFixedFollowerOffsetPx(animatedGap, to)
                    val originalPaintedTop = 300 - travel + animatedGap
                    val fixedPaintedTop = 300 - travel + to + drawOffset
                    assertEquals("from=$from to=$to frame=$frame",
                        originalPaintedTop.toFloat(), fixedPaintedTop, 0f)
                }
            }
        }
    }

    @Test fun drawTranslationBeginsAtGapDifferenceAndEndsAtZero() {
        val from = 0
        val to = 310
        val visible = 44
        val fullScroll = visible + to - from
        assertEquals((from - to).toFloat(), compactFixedFollowerOffsetPx(from, to), 0f)
        assertEquals(0f, compactFixedFollowerOffsetPx(from + fullScroll - visible, to), 0f)
    }
}
