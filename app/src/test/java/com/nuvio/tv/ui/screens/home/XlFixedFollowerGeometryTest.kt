package com.nuvio.tv.ui.screens.home

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

/** The compact follower should paint exactly the old spring path at constant measured height. */
class XlFixedFollowerGeometryTest {
    @Test fun fixedHeadroomPlusTranslationMatchesMovingGapAtEverySpringFrame() {
        for (from in listOf(0, 24, 90)) {
            for (to in listOf(100, 180, 330)) {
                val visibleDistance = 38
                val fullScroll = visibleDistance + to - from
                for (frame in 0..120) {
                    val fraction = frame / 120f
                    val scroll = (fullScroll * fraction).roundToInt()
                    val incomingTravel = (visibleDistance * fraction).roundToInt()
                    val animatedGap = from + scroll - incomingTravel
                    val offset = compactFixedFollowerOffsetPx(animatedGap, to)
                    val originalTop = 300 - scroll + animatedGap
                    val fixedTop = 300 - scroll + to + offset
                    assertEquals("from=$from to=$to frame=$frame",
                        originalTop.toFloat(), fixedTop, 0f)
                }
            }
        }
    }

    @Test fun interruptedExitRestoresTheActualVisibleGapWithoutJumping() {
        val scene = XlSceneState()
        scene.density = 1f
        scene.headroomValues["regular"] = 300.dp
        val motion = XlVerticalMotion(1, "regular", 0.dp, 300.dp, 350f,
            incrementalLandscapeFollower = true)
        scene.verticalMotion = motion
        scene.compactIncomingLandscapeTranslationPx = -120f
        scene.cancelVerticalMotion()
        assertEquals(180.dp, scene.headroomValues["regular"])
        assertEquals(0f, scene.compactIncomingLandscapeTranslationPx, 0f)
        assertEquals(null, scene.verticalMotion)
    }
}
