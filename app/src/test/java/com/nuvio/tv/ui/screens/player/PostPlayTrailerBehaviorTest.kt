package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostPlayTrailerBehaviorTest {
    @Test
    fun `trailer action requires a resolved playable source`() {
        assertFalse(shouldShowPostPlayTrailerAction(recommendation(), isTrailerPlaying = false))
        assertTrue(
            shouldShowPostPlayTrailerAction(
                recommendation(trailerVideoUrl = "https://example.com/trailer.m3u8"),
                isTrailerPlaying = false
            )
        )
    }

    @Test
    fun `trailer action hides during trailer playback`() {
        assertFalse(
            shouldShowPostPlayTrailerAction(
                recommendation(trailerVideoUrl = "https://example.com/trailer.m3u8"),
                isTrailerPlaying = true
            )
        )
    }

    private fun recommendation(trailerVideoUrl: String? = null) = PostPlayRecommendation(
        id = "tmdb:123",
        contentType = "movie",
        title = "Recommended Movie",
        backdrop = null,
        poster = null,
        description = null,
        releaseInfo = "2026",
        genres = emptyList(),
        trailerVideoUrl = trailerVideoUrl
    )
}
