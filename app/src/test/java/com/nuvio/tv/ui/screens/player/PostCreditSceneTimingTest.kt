package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PostCreditSceneTimingTest {

    private fun timing(
        vararg scenes: PostCreditSceneTiming
    ) = CreditTimingUiState(
        status = CreditTimingStatus.COMPLETE,
        creditsStartMs = 100_000L,
        finalCreditsStartMs = 300_000L,
        hasPostCreditScenes = scenes.isNotEmpty(),
        postCreditScenes = scenes.toList()
    )

    @Test
    fun `first post credit scene always gets skip opportunity`() {
        val first = PostCreditSceneTiming(
            startMs = 140_000L,
            endMs = 160_000L
        )

        assertEquals(
            first,
            postCreditSkipTarget(
                timing = timing(first),
                positionMs = 110_000L
            )
        )
    }

    @Test
    fun `no skip target before credits begin`() {
        val first = PostCreditSceneTiming(
            startMs = 140_000L,
            endMs = 160_000L
        )

        assertNull(
            postCreditSkipTarget(
                timing = timing(first),
                positionMs = 99_999L
            )
        )
    }

    @Test
    fun `no skip button while post credit scene is playing`() {
        val first = PostCreditSceneTiming(
            startMs = 140_000L,
            endMs = 160_000L
        )

        assertNull(
            postCreditSkipTarget(
                timing = timing(first),
                positionMs = 150_000L
            )
        )
    }

    @Test
    fun `second scene within ten seconds is continuous`() {
        val first = PostCreditSceneTiming(
            startMs = 140_000L,
            endMs = 160_000L
        )
        val second = PostCreditSceneTiming(
            startMs = 170_000L,
            endMs = 190_000L
        )

        assertNull(
            postCreditSkipTarget(
                timing = timing(first, second),
                positionMs = 165_000L
            )
        )
    }

    @Test
    fun `second scene more than ten seconds later gets new skip opportunity`() {
        val first = PostCreditSceneTiming(
            startMs = 140_000L,
            endMs = 160_000L
        )
        val second = PostCreditSceneTiming(
            startMs = 170_001L,
            endMs = 190_000L
        )

        assertEquals(
            second,
            postCreditSkipTarget(
                timing = timing(first, second),
                positionMs = 165_000L
            )
        )
    }

    @Test
    fun `multiple close scenes remain one continuous cluster`() {
        val first = PostCreditSceneTiming(140_000L, 160_000L)
        val second = PostCreditSceneTiming(168_000L, 180_000L)
        val third = PostCreditSceneTiming(189_000L, 205_000L)

        assertNull(
            postCreditSkipTarget(
                timing = timing(first, second, third),
                positionMs = 164_000L
            )
        )

        assertNull(
            postCreditSkipTarget(
                timing = timing(first, second, third),
                positionMs = 184_000L
            )
        )
    }
}
