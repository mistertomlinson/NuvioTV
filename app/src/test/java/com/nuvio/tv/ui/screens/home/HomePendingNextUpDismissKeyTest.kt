package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePendingNextUpDismissKeyTest {

    @Test
    fun `same seed with different target does not collide`() {
        val rewatchTarget =
            nextUpPendingDismissKey(
                contentId = "series:123",
                seedSeason = 1,
                seedEpisode = 1,
                targetSeason = 1,
                targetEpisode = 2
            )

        val upcomingSeasonTarget =
            nextUpPendingDismissKey(
                contentId = "series:123",
                seedSeason = 1,
                seedEpisode = 1,
                targetSeason = 2,
                targetEpisode = 1
            )

        assertNotEquals(
            rewatchTarget,
            upcomingSeasonTarget
        )
    }

    @Test
    fun `identical seed and target produces identical key`() {
        val first =
            nextUpPendingDismissKey(
                "series:123",
                1,
                1,
                1,
                2
            )

        val second =
            nextUpPendingDismissKey(
                "series:123",
                1,
                1,
                1,
                2
            )

        assertEquals(first, second)
    }

    @Test
    fun `fresh seed prefix covers targets from only that seed`() {
        val prefix =
            nextUpPendingDismissSeedPrefix(
                "series:123",
                1,
                1
            )

        val rewatchTarget =
            nextUpPendingDismissKey(
                "series:123",
                1,
                1,
                1,
                2
            )

        val newSeasonTarget =
            nextUpPendingDismissKey(
                "series:123",
                1,
                1,
                2,
                1
            )

        val otherSeed =
            nextUpPendingDismissKey(
                "series:123",
                1,
                2,
                1,
                3
            )

        assertTrue(rewatchTarget.startsWith(prefix))
        assertTrue(newSeasonTarget.startsWith(prefix))
        assertTrue(!otherSeed.startsWith(prefix))
    }
}
