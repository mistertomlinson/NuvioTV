package com.nuvio.tv.data.trailer

import android.net.Uri
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.net.URI
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class InAppYouTubeExtractorLazyClientTest {
    private val network = FakeYouTubeNetwork()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        mockkStatic(Uri::class)
        every { Uri.encode(any<String>()) } answers { firstArg<String>() }
        every { Uri.parse(any<String>()) } answers {
            val raw = firstArg<String>()
            val parsed = URI(raw)
            mockk<Uri> {
                every { host } returns parsed.host
                every { pathSegments } returns parsed.path.split('/')
                    .filter(String::isNotBlank)
                every { getQueryParameter("v") } returns
                    raw.substringAfter("v=", "")
                        .substringBefore('&')
                        .takeIf(String::isNotBlank)
                every { getQueryParameter("n") } returns null
            }
        }
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
        unmockkStatic(Log::class)
    }

    private fun extractor(
        resolver: suspend (String) -> String? = { it }
    ): InAppYouTubeExtractor = InAppYouTubeExtractor(
        httpClient = OkHttpClient.Builder()
            .addInterceptor(network)
            .build(),
        urlResolver = resolver
    )

    @Test
    fun `visionos success makes no Android iOS or HLS request`() = runTest {
        network.responses["101"] = adaptive("visionos")

        val source = extractor().extractPlaybackSource(VIDEO_URL)

        assertNotNull(source)
        assertEquals("https://cdn.example/v-visionos", source?.videoUrl)
        assertEquals("https://cdn.example/a-visionos", source?.audioUrl)
        assertEquals(listOf("watch", "101"), network.calls)
    }

    @Test
    fun `missing visionos adaptive pair queries Android but not iOS`() = runTest {
        network.responses["101"] = unplayable()
        network.responses["3"] = adaptive("android")

        val source = extractor().extractPlaybackSource(VIDEO_URL)

        assertEquals("https://cdn.example/v-android", source?.videoUrl)
        assertEquals("https://cdn.example/a-android", source?.audioUrl)
        assertEquals(listOf("watch", "101", "3"), network.calls)
    }

    @Test
    fun `failed visionos video tries Android instead of returning HLS`() = runTest {
        network.responses["101"] = adaptive("visionos")
        network.responses["3"] = adaptive("android")

        val source = extractor { url ->
            if (url.endsWith("/v-visionos")) null else url
        }.extractPlaybackSource(VIDEO_URL)

        assertEquals("https://cdn.example/v-android", source?.videoUrl)
        assertEquals("https://cdn.example/a-visionos", source?.audioUrl)
        assertEquals(listOf("watch", "101", "3"), network.calls)
    }

    @Test
    fun `failed visionos audio queries Android rather than returning silent video`() = runTest {
        network.responses["101"] = adaptive("visionos")
        network.responses["3"] = adaptive("android")

        val source = extractor { url ->
            if (url.endsWith("/a-visionos")) null else url
        }.extractPlaybackSource(VIDEO_URL)

        assertEquals("https://cdn.example/v-visionos", source?.videoUrl)
        assertEquals("https://cdn.example/a-android", source?.audioUrl)
        assertEquals(listOf("watch", "101", "3"), network.calls)
    }

    @Test
    fun `HLS is downloaded only after adaptive clients are exhausted`() = runTest {
        network.responses["101"] = hlsOnly()
        network.responses["3"] = unplayable()
        network.responses["5"] = unplayable()

        val source = extractor().extractPlaybackSource(VIDEO_URL)

        assertEquals("https://cdn.example/master.m3u8", source?.videoUrl)
        assertEquals(listOf("watch", "101", "3", "5", "hls"), network.calls)
    }

    @Test
    fun `HLS 429 opens cooldown and prevents another request`() = runTest {
        network.responses["101"] = hlsOnly()
        network.responses["3"] = unplayable()
        network.responses["5"] = unplayable()
        network.hlsStatus = 429

        val subject = extractor()

        assertNull(subject.extractPlaybackSource(VIDEO_URL))
        assertEquals(listOf("watch", "101", "3", "5", "hls"), network.calls)
        assertEquals(true, subject.isRateLimited())

        val count = network.calls.size
        assertNull(subject.extractPlaybackSource(VIDEO_URL))
        assertEquals(count, network.calls.size)
        assertFalse(network.calls.isEmpty())
    }

    private fun adaptive(name: String) = """
        {
          "playabilityStatus":{"status":"OK"},
          "streamingData":{
            "hlsManifestUrl":"https://cdn.example/master.m3u8",
            "adaptiveFormats":[
              {
                "url":"https://cdn.example/v-$name",
                "mimeType":"video/mp4",
                "height":1080,
                "fps":30,
                "bitrate":4000000
              },
              {
                "url":"https://cdn.example/a-$name",
                "mimeType":"audio/mp4",
                "bitrate":128000,
                "audioSampleRate":48000
              }
            ]
          }
        }
    """.trimIndent()

    private fun hlsOnly() = """
        {
          "playabilityStatus":{"status":"OK"},
          "streamingData":{
            "hlsManifestUrl":"https://cdn.example/master.m3u8"
          }
        }
    """.trimIndent()

    private fun unplayable() =
        """{"playabilityStatus":{"status":"UNPLAYABLE"}}"""

    private class FakeYouTubeNetwork : Interceptor {
        val calls = mutableListOf<String>()
        val responses = mutableMapOf<String, String>()
        var hlsStatus = 200

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val key = when {
                request.url.encodedPath == "/watch" -> "watch"
                request.url.encodedPath == "/youtubei/v1/player" ->
                    request.header("x-youtube-client-name") ?: "unknown"
                else -> "hls"
            }

            calls += key

            val status = if (key == "hls") hlsStatus else 200
            val body = when (key) {
                "watch" ->
                    """{"INNERTUBE_API_KEY":"test-key","VISITOR_DATA":"test-visitor"}"""
                "hls" -> """
                    #EXTM3U
                    #EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080
                    https://cdn.example/variant.m3u8
                """.trimIndent()
                else -> responses[key]
                    ?: """{"playabilityStatus":{"status":"UNPLAYABLE"}}"""
            }

            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(if (status == 200) "OK" else "Too Many Requests")
                .body(body.toResponseBody())
                .build()
        }
    }

    private companion object {
        const val VIDEO_URL =
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    }
}
