package com.nuvio.tv.ui.screens.home

import org.junit.Assert.*
import org.junit.Test

/** The XL retains its observed peak speed while the ordinary row decelerates. */
class XlPeakCruiseTravelTest {
    private val frame = 16_000_000L

    @Test fun followsAccelerationThenKeepsPeakSpeedDuringOrdinaryDeceleration() {
        var state = XlPeakCruiseFrame()
        val ordinary = listOf(0f, 8f, 30f, 62f, 90f, 111f, 127f, 139f, 146f, 150f)
        val velocities = listOf(0f, 500f, 1375f, 2000f, 1750f, 1312f, 1000f, 750f, 437f, 250f)
        var firstCruise = -1
        ordinary.indices.forEach { i ->
            val old = state
            state = xlPeakCruiseStep(old, ordinary[i], velocities[i], i * frame, 150f, 460f)
            if (!old.cruising && state.cruising) firstCruise = i
            assertTrue("XL never reverses", state.xlPx >= old.xlPx)
            assertTrue("XL remains ahead of its follower", state.xlPx + 0.001f >= state.ordinaryPx)
            if (!state.cruising) assertEquals("Initial motion is pixel matched",
                state.ordinaryPx, state.xlPx, 0.001f)
        }
        assertTrue("Cruise starts before the regular row lands",
            firstCruise in 4 until ordinary.lastIndex)
        assertTrue(state.xlPx > state.ordinaryPx)
        assertEquals(2000f, state.peakPxPerSecond, 0.01f)
    }

    @Test fun speedContinuesAcrossLandingWithoutARestartedEase() {
        var state = XlPeakCruiseFrame(ordinaryPx = 150f, xlPx = 230f,
            peakPxPerSecond = 1800f, cruising = true, frameNanos = 1_000_000_000L)
        repeat(6) {
            val old = state
            state = xlPeakCruiseStep(old, 150f, 0f,
                old.frameNanos + frame, 150f, 460f)
            assertEquals("Constant XL speed across landing", 28.8f, state.xlPx - old.xlPx, 0.001f)
            assertEquals("Incoming row remains parked", 150f, state.ordinaryPx, 0f)
            assertEquals("Spacer movement cancels the extra XL travel",
                150f, state.xlPx - (state.xlPx - state.ordinaryPx), 0.001f)
        }
    }

    @Test fun headroomCannotOvershootItsMeasuredClearanceWhileOrdinaryIsStillMoving() {
        var state = XlPeakCruiseFrame(ordinaryPx = 80f, xlPx = 300f,
            peakPxPerSecond = 8000f, cruising = true, frameNanos = 1_000_000_000L)
        state = xlPeakCruiseStep(state, 95f, 250f, 1_016_000_000L, 150f, 460f)
        assertEquals("Maximum XL lead equals final clearance distance",
            95f + 310f, state.xlPx, 0f)
        val after = xlPeakCruiseStep(state, 108f, 200f, 1_032_000_000L, 150f, 460f)
        assertTrue("No backward travel", after.xlPx >= state.xlPx)
        assertTrue("Never exceed remaining headroom budget", after.xlPx <= 108f + 310f)
    }

    @Test fun stopsAtExactFinalClearanceWithoutOvershooting() {
        var state = XlPeakCruiseFrame(ordinaryPx = 150f, xlPx = 440f,
            peakPxPerSecond = 2400f, cruising = true, frameNanos = 1_000_000_000L)
        state = xlPeakCruiseStep(state, 150f, 0f, 1_016_000_000L, 150f, 460f)
        assertEquals(460f, state.xlPx, 0f)
        val again = xlPeakCruiseStep(state, 150f, 0f, 1_032_000_000L, 150f, 460f)
        assertEquals(state.xlPx, again.xlPx, 0f)
    }
}
