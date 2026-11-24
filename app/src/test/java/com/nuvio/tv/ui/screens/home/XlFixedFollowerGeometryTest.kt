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
                    val offset = xlFixedFollowerOffsetPx(animatedGap, to)
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
            itemAnchor = XlScrollItemAnchor(3, 0), fixedFollowerMeasurement = true)
        val offset = -167f
        assertEquals(0f, xlFixedOrdinarySuffixTranslationPx(motion, 2, -1, offset), 0f)
        assertEquals(offset, xlFixedOrdinarySuffixTranslationPx(motion, 3, 2, offset), 0f)
        assertEquals(offset, xlFixedOrdinarySuffixTranslationPx(motion, 4, 2, offset), 0f)
        assertEquals(offset, xlFixedOrdinarySuffixTranslationPx(motion, 5, 2, offset), 0f)
        assertEquals(0f, xlFixedOrdinarySuffixTranslationPx(motion, 6, 5, offset), 0f)
        assertEquals(0f, xlFixedOrdinarySuffixTranslationPx(
            motion.copy(fixedFollowerMeasurement = false), 4, 2, offset), 0f)
    }

    @Test fun sharedTranslationKeepsOrdinaryRowSpacingAtEverySpringFrame() {
        val motion = XlVerticalMotion(1, "regular", 0.dp, 300.dp, 350f,
            itemAnchor = XlScrollItemAnchor(3, 0), fixedFollowerMeasurement = true)
        val measuredC = 500f
        val measuredD = 684f
        for (frame in 0..120) {
            val offset = xlFixedFollowerOffsetPx(frame * 300 / 120, 300)
            val c = measuredC + xlFixedOrdinarySuffixTranslationPx(motion, 3, 2, offset)
            val d = measuredD + xlFixedOrdinarySuffixTranslationPx(motion, 4, 2, offset)
            assertEquals("Regular rows preserve physical measured spacing",
                measuredD - measuredC, d - c, 0f)
        }
    }

    @Test fun interruptedExitRestoresTheActualVisibleGapWithoutJumping() {
        val scene = XlSceneState()
        scene.density = 1f
        scene.headroomValues["regular"] = 300.dp
        val motion = XlVerticalMotion(1, "regular", 0.dp, 300.dp, 350f,
            fixedFollowerMeasurement = true)
        scene.verticalMotion = motion
        scene.fixedFollowerTranslationPx = -120f
        scene.cancelVerticalMotion()
        assertEquals(180.dp, scene.headroomValues["regular"])
        assertEquals(0f, scene.fixedFollowerTranslationPx, 0f)
        assertEquals(null, scene.verticalMotion)
    }

    @Test fun summaryAndCompactExitsCanShareIdenticalFixedFollowerGeometry() {
        for (compact in listOf(true, false)) {
            val motion = XlVerticalMotion(3, "regular", 30.dp, 270.dp, 430f,
                compact = compact, itemAnchor = XlScrollItemAnchor(3, 0),
                fixedFollowerMeasurement = true, followerEndExclusiveIndex = 6)
            for (frame in 0..100) {
                val fraction = frame / 100f
                val totalTravel = (motion.scrollPx * fraction).roundToInt()
                val visibleTravel = ((motion.scrollPx - 240f) * fraction).roundToInt()
                val virtualGap = if (compact) 30 + totalTravel - visibleTravel
                    else 30 + (240f * fraction).roundToInt()
                val translation = xlFixedFollowerOffsetPx(virtualGap, 270)
                val expected = 700 - totalTravel + virtualGap
                val actual = 700 - totalTravel + 270 +
                    xlFixedOrdinarySuffixTranslationPx(motion, 3, 2, translation)
                assertEquals("Both modes must paint the exact original path", expected.toFloat(), actual, 0f)
                assertEquals(translation,
                    xlFixedOrdinarySuffixTranslationPx(motion, 5, 2, translation), 0f)
                assertEquals(0f,
                    xlFixedOrdinarySuffixTranslationPx(motion, 6, 5, translation), 0f)
            }
        }
    }

    @Test fun summaryMotionCancellationRestoresVirtualGapWithoutAVisibleJump() {
        val scene = XlSceneState().apply { density = 1f }
        scene.headroomValues["regular"] = 270.dp
        scene.verticalMotion = XlVerticalMotion(1, "regular", 30.dp, 270.dp, 430f,
            compact = false, itemAnchor = XlScrollItemAnchor(3, 0),
            fixedFollowerMeasurement = true)
        scene.fixedFollowerTranslationPx = -126f
        scene.cancelVerticalMotion()
        assertEquals(144.dp, scene.headroomValues["regular"])
        assertEquals(0f, scene.fixedFollowerTranslationPx, 0f)
        assertEquals(null, scene.verticalMotion)
    }
}
