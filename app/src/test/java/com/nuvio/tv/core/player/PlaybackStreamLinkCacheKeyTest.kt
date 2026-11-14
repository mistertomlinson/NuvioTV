package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackStreamLinkCacheKeyTest {
    @Test
    fun `two different episodes always have different reuse keys`() {
        val one = playbackStreamLinkCacheKey("series", "tt1000000:2:1")
        val two = playbackStreamLinkCacheKey("series", "tt1000000:2:2")
        assertNotEquals(one, two)
        assertEquals("episode-v2|series|tt1000000:2:1", one)
        assertEquals("episode-v2|series|tt1000000:2:2", two)
    }

    @Test
    fun `episode cache no longer accepts keys written by older broken switch code`() {
        val key = playbackStreamLinkCacheKey("series", "tt1000000:2:1")
        assertNotEquals("series|tt1000000:2:1", key)
    }

    @Test
    fun `all episodic content types use the fresh namespace`() {
        for (type in listOf("series", "tv", "anime", "SERIES")) {
            val key = playbackStreamLinkCacheKey(type, "episode-id")
            assertEquals("episode-v2|${type.lowercase()}|episode-id", key)
        }
    }

    @Test
    fun `movie link reuse retains its existing key`() {
        assertEquals("movie|tt2000000", playbackStreamLinkCacheKey("movie", "tt2000000"))
    }

    @Test
    fun `missing stream identity cannot create a shared cache entry`() {
        assertNull(playbackStreamLinkCacheKey(null, "id"))
        assertNull(playbackStreamLinkCacheKey("series", null))
        assertNull(playbackStreamLinkCacheKey("series", " "))
    }
}
