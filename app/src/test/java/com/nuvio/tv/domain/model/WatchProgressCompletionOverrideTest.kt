package com.nuvio.tv.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressCompletionOverrideTest {

    @Test
    fun `legacy progress still uses eighty five percent fallback`() {
        val progress = progress(
            position = 85_000L,
            duration = 100_000L,
            completionOverride = null
        )

        assertTrue(progress.isCompleted())
        assertFalse(progress.isInProgress())
    }

    @Test
    fun `explicit in progress remains resumable above eighty five percent`() {
        val progress = progress(
            position = 90_000L,
            duration = 100_000L,
            completionOverride = false
        )

        assertFalse(progress.isCompleted())
        assertTrue(progress.isInProgress())
    }

    @Test
    fun `explicit completion wins even before generic percentage threshold`() {
        val progress = progress(
            position = 50_000L,
            duration = 100_000L,
            completionOverride = true
        )

        assertTrue(progress.isCompleted())
        assertFalse(progress.isInProgress())
    }

    private fun progress(
        position: Long,
        duration: Long,
        completionOverride: Boolean?
    ) = WatchProgress(
        contentId = "tt-test",
        contentType = "series",
        name = "Test",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "tt-test:1:1",
        season = 1,
        episode = 1,
        episodeTitle = "Episode",
        position = position,
        duration = duration,
        lastWatched = 1L,
        completionOverride = completionOverride
    )
}
