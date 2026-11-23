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

    @Test fun allOrdinaryRowsAfterTheCompactFollowerUseTheSameTranslation() {
        val motion = XlVerticalMotion(1, "regular", 0.dp, 300.dp, 350f,
            itemAnchor = XlScrollItemAnchor(3, 0), incrementalLandscapeFollower = true)
        val offset = -167f
        assertEquals(0f, compactOrdinarySuffixTranslationPx(motion, 2, -1, offset), 0f)
        assertEquals(offset, compactOrdinarySuffixTranslationPx(motion, 3, 2, offset), 0f)
        assertEquals(offset, compactOrdinarySuffixTranslationPx(motion, 4, 2, offset), 0f)
        assertEquals(offset, compactOrdinarySuffixTranslationPx(motion, 5, 2, offset), 0f)
        assertEquals(0f, compactOrdinarySuffixTranslationPx(motion, 6, 5, offset), 0f)
        assertEquals(0f, compactOrdinarySuffixTranslationPx(
            motion.copy(incrementalLandscapeFollower = false), 4, 2, offset), 0f)
    }

    @Test fun sharedTranslationKeepsOrdinaryRowSpacingAtEverySpringFrame() {
        val motion = XlVerticalMotion(1, "regular", 0.dp, 300.dp, 350f,
            itemAnchor = XlScrollItemAnchor(3, 0), incrementalLandscapeFollower = true)
        val measuredC = 500f
        val measuredD = 684f
        for (frame in 0..120) {
            val offset = compactFixedFollowerOffsetPx(frame * 300 / 120, 300)
            val c = measuredC + compactOrdinarySuffixTranslationPx(motion, 3, 2, offset)
            val d = measuredD + compactOrdinarySuffixTranslationPx(motion, 4, 2, offset)
            assertEquals("Regular rows preserve physical measured spacing",
                measuredD - measuredC, d - c, 0f)
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
