package com.nuvio.tv.ui.screens.home

import org.junit.Assert.*
import org.junit.Test

/** Geometry of the linked ordinary-arrival leg and the independent XL-clearance leg. */
class XlTwoStageExitTravelTest {
    @Test fun matchedTravelAndClearanceKeepTheIncomingRowAtItsAnchor() {
        val fromGap = 40
        val toGap = 340
        val legs = checkNotNull(xlTwoStageExitTravel(620f, fromGap, toGap))
        assertEquals(320f, legs.arrivalPx, 0f)
        assertEquals(300f, legs.clearancePx, 0f)
        // Phase 1: both the ordinary row and the XL list advance equally.
        for (step in 0..10) {
            val progress = step / 10f
            val travel = legs.arrivalPx * progress
            val gap = fromGap.toFloat()
            assertEquals(travel, travel - (gap - fromGap), 0f)
        }
        // Phase 2: every extra pixel of XL scroll is canceled for the
        // ordinary follower by an equal increase in its preceding spacer.
        for (step in 0..10) {
            val extra = legs.clearancePx * step / 10f
            val xlTravel = legs.arrivalPx + extra
            val gap = fromGap + extra
            assertEquals("Incoming remains at the same screen position",
                legs.arrivalPx, xlTravel - (gap - fromGap), 0.0001f)
        }
        assertEquals(620f, legs.arrivalPx + legs.clearancePx, 0f)
    }

    @Test fun neverSplitAZeroOrOppositeClearance() {
        assertNull(xlTwoStageExitTravel(200f, 40, 40))
        assertNull(xlTwoStageExitTravel(200f, 340, 40))
        assertNull(xlTwoStageExitTravel(100f, 40, 340))
    }
}
