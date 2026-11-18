package com.nuvio.tv.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTraceTest {
    @Test
    fun `link fingerprints are deterministic and don't expose the URL`() {
        val url = "https://private.example/video?token=secret"
        val hash = PlaybackTrace.fingerprint(url)
        assertNotNull(hash)
        assertEquals(hash, PlaybackTrace.fingerprint(url))
        assertNotEquals(hash, PlaybackTrace.fingerprint(url + "&different=1"))
        assertFalse(hash!!.contains("token"))
        assertEquals(20, hash.length)
    }

    @Test
    fun `redacts raw URL and authorization fields`() {
        assertEquals("<redacted>", PlaybackTrace.scrub("url", "https://private.example/secret"))
        assertEquals("<redacted>", PlaybackTrace.scrub("authorization", "Bearer key"))
        assertEquals("<redacted>", PlaybackTrace.scrub("apiKey", "key"))
        assertEquals("<redacted>", PlaybackTrace.scrub("magnet", "magnet:?xt=urn:btih:secret"))
    }

    @Test
    fun `preserves link fingerprint but strips URL embedded in release text`() {
        assertEquals("ab12cd", PlaybackTrace.scrub("linkHash", "ab12cd"))
        val text = PlaybackTrace.scrub(
            "name",
            "The.Office.S06E09.mkv https://private.example/video?token=bad"
        ).toString()
        assertTrue(text.contains("The.Office.S06E09"))
        assertFalse(text.contains("private.example"))
        assertFalse(text.contains("token=bad"))
    }

    @Test
    fun `preserves useful episode and file identifiers`() {
        assertEquals(9, PlaybackTrace.scrub("episode", 9))
        assertEquals("S06E10-Amazon Redux.avi", PlaybackTrace.scrub("filename", "S06E10-Amazon Redux.avi"))
    }
}
