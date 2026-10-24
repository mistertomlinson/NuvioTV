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
    fun `first post credit scene gets skip opportunity when more than timeout away`() {
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
    fun `first post credit scene inside timeout never gets skip opportunity`() {
        val first = PostCreditSceneTiming(
            startMs = 109_999L,
            endMs = 130_000L
        )

        assertNull(
            postCreditSkipTarget(
                timing = timing(first),
                positionMs = 100_000L
            )
        )
    }

    @Test
    fun `first post credit scene exactly one timeout away never gets skip opportunity`() {
        val first = PostCreditSceneTiming(
            startMs = 110_000L,
            endMs = 130_000L
        )

        assertNull(
            postCreditSkipTarget(
                timing = timing(first),
                positionMs = 100_000L
            )
        )
    }

    @Test
    fun `first post credit scene one millisecond beyond timeout gets skip opportunity`() {
        val first = PostCreditSceneTiming(
            startMs = 110_001L,
            endMs = 130_000L
        )

        assertEquals(
            first,
            postCreditSkipTarget(
                timing = timing(first),
                positionMs = 100_000L
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
                positionMs = 160_000L
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
    @Test
    fun `cached timing is suppressed once playback is inside final ten seconds`() {
        val scene = PostCreditSceneTiming(
            startMs = 140_000L,
            endMs = 160_000L
        )

        val state = CreditTimingUiState(
            status = CreditTimingStatus.COMPLETE,
            creditsStartMs = 100_000L,
            hasPostCreditScenes = true,
            postCreditScenes = listOf(scene)
        )

        // The full analyzer gap is forty seconds, but a cached result can be
        // consumed late. At exactly ten seconds remaining there must be no UI.
        assertNull(
            postCreditSkipTarget(
                timing = state,
                positionMs = 130_000L
            )
        )

        assertNull(
            postCreditSkipTarget(
                timing = state,
                positionMs = 130_001L
            )
        )

        assertEquals(
            scene,
            postCreditSkipTarget(
                timing = state,
                positionMs = 129_999L
            )
        )
    }

    @Test
    fun `later separated scene still requires more than ten seconds remaining`() {
        val first = PostCreditSceneTiming(
            startMs = 120_000L,
            endMs = 130_000L
        )
        val second = PostCreditSceneTiming(
            startMs = 160_000L,
            endMs = 175_000L
        )
        val state = CreditTimingUiState(
            status = CreditTimingStatus.COMPLETE,
            creditsStartMs = 100_000L,
            hasPostCreditScenes = true,
            postCreditScenes = listOf(first, second)
        )

        assertNull(
            postCreditSkipTarget(
                timing = state,
                positionMs = 150_000L,
                skippedSceneStarts = setOf(first.startMs)
            )
        )
        assertEquals(
            second,
            postCreditSkipTarget(
                timing = state,
                positionMs = 149_999L,
                skippedSceneStarts = setOf(first.startMs)
            )
        )
    }

}
