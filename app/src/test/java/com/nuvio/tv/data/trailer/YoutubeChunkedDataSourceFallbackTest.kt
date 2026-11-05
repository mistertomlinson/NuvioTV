package com.nuvio.tv.data.trailer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeChunkedDataSourceFallbackTest {
    private val normalChunk = 10L * 1024L * 1024L

    @Test
    fun `audio HTTP 403 can retry once with smaller chunks`() {
        assertTrue(shouldRetrySmallAudioChunk(403, "audio/webm", false, normalChunk))
        assertTrue(shouldRetrySmallAudioChunk(403, "audio/mp4", false, normalChunk))
    }

    @Test
    fun `video HTTP 403 must not use audio fallback`() {
        assertFalse(shouldRetrySmallAudioChunk(403, "video/mp4", false, normalChunk))
    }

    @Test
    fun `non-403 audio failures do not trigger smaller chunks`() {
        assertFalse(shouldRetrySmallAudioChunk(404, "audio/webm", false, normalChunk))
        assertFalse(shouldRetrySmallAudioChunk(429, "audio/webm", false, normalChunk))
        assertFalse(shouldRetrySmallAudioChunk(206, "audio/webm", false, normalChunk))
    }

    @Test
    fun `small chunk retry does not loop if 403 persists`() {
        assertFalse(shouldRetrySmallAudioChunk(403, "audio/webm", true, normalChunk))
    }

    @Test
    fun `existing 64 KiB audio requests do not need fallback`() {
        assertFalse(shouldRetrySmallAudioChunk(403, "audio/webm", false, 64L * 1024L))
    }

    @Test
    fun `unknown stream format does not trigger audio fallback`() {
        assertFalse(shouldRetrySmallAudioChunk(403, null, false, normalChunk))
    }
}
