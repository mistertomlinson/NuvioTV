package com.nuvio.tv.data.trailer

import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbVideoResult
import com.nuvio.tv.data.remote.api.TmdbVideosResponse
import com.nuvio.tv.data.remote.api.TrailerApi
import com.nuvio.tv.data.remote.api.TrailerResponse
import com.nuvio.tv.domain.model.TmdbSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class TrailerServiceRateLimitTest {
    private val trailerApi = mockk<TrailerApi>()
    private val tmdbApi = mockk<TmdbApi>()
    private val extractor = mockk<InAppYouTubeExtractor>()
    private val service = TrailerService(
        trailerApi = trailerApi,
        tmdbApi = tmdbApi,
        inAppYouTubeExtractor = extractor,
        tmdbSettingsDataStore = mockk<TmdbSettingsDataStore> {
            every { settings } returns MutableStateFlow(TmdbSettings(language = "en"))
        },
        tmdbService = mockk<TmdbService> { every { apiKey() } returns "tmdb-key" }
    )

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        coEvery { trailerApi.getTrailer(any(), any(), any()) } returns
            Response.success(TrailerResponse(url = null))
        coEvery { tmdbApi.getMovieVideos(any(), any(), any()) } returns Response.success(
            TmdbVideosResponse(id = 123, results = (0 until 8).map { trailer(it) })
        )
        every { extractor.isRateLimited() } returns false
        coEvery { extractor.searchForTrailerVideoId(any(), any(), any(), any()) } returns null
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `stops candidates and skips search fallback once youtube rate limits`() = runTest {
        var rateLimited = false
        every { extractor.isRateLimited() } answers { rateLimited }
        coEvery { extractor.extractPlaybackSource(any()) } answers {
            rateLimited = true
            null
        }

        assertNull(lookup())

        coVerify(exactly = 1) { extractor.extractPlaybackSource(any()) }
        coVerify(exactly = 0) {
            extractor.searchForTrailerVideoId(any(), any(), any(), any())
        }
    }

    @Test
    fun `tries only best three tmdb candidates when they all fail`() = runTest {
        coEvery { extractor.extractPlaybackSource(any()) } returns null

        assertNull(lookup())

        coVerify(exactly = 3) { extractor.extractPlaybackSource(any()) }
        coVerify(exactly = 0) {
            extractor.searchForTrailerVideoId(any(), any(), any(), any())
        }
    }

    @Test
    fun `rate limited miss is not cached as no trailer`() = runTest {
        var rateLimited = false
        every { extractor.isRateLimited() } answers { rateLimited }
        var calls = 0
        coEvery { extractor.extractPlaybackSource(any()) } answers {
            calls++
            if (calls == 1) {
                rateLimited = true
                null
            } else {
                TrailerPlaybackSource(videoUrl = "https://cdn.example/trailer.mp4")
            }
        }

        assertNull(lookup())
        rateLimited = false
        assertEquals("https://cdn.example/trailer.mp4", lookup()?.videoUrl)
    }

    @Test
    fun `normal TMDB miss never searches YouTube by title`() = runTest {
        coEvery { tmdbApi.getMovieVideos(any(), any(), any()) } returns
            Response.success(TmdbVideosResponse(id = 123, results = emptyList()))

        assertNull(lookup())
        coVerify(exactly = 0) {
            extractor.searchForTrailerVideoId(any(), any(), any(), any())
        }
        coVerify(exactly = 0) { extractor.extractPlaybackSource(any()) }
        coVerify(exactly = 0) { trailerApi.getTrailer(any(), any(), any()) }
    }

    @Test
    fun `TMDB linked trailer still uses in-app YouTube extraction`() = runTest {
        coEvery { extractor.extractPlaybackSource(any()) } returns
            TrailerPlaybackSource(videoUrl = "https://cdn.example/tmdb-trailer.mp4")

        assertEquals("https://cdn.example/tmdb-trailer.mp4", lookup()?.videoUrl)
        coVerify(exactly = 1) {
            extractor.extractPlaybackSource(
                "https://www.youtube.com/watch?v=" + trailer(0).key
            )
        }
        coVerify(exactly = 0) {
            extractor.searchForTrailerVideoId(any(), any(), any(), any())
        }
    }

    @Test
    fun `explicit addon trailer ID still resolves without title search`() = runTest {
        coEvery { extractor.extractPlaybackSource(VIDEO_URL) } returns
            TrailerPlaybackSource(videoUrl = "https://cdn.example/addon-trailer.mp4")

        val source = service.getTrailerPlaybackSourceFromYouTubeUrl(
            youtubeUrl = VIDEO_URL,
            title = "Mixtape",
            year = "2023"
        )

        assertEquals("https://cdn.example/addon-trailer.mp4", source?.videoUrl)
        coVerify(exactly = 1) { extractor.extractPlaybackSource(VIDEO_URL) }
        coVerify(exactly = 0) {
            extractor.searchForTrailerVideoId(any(), any(), any(), any())
        }
    }

    @Test
    fun `known addon ID still uses backend resolver if in-app extraction fails`() = runTest {
        coEvery { extractor.extractPlaybackSource(VIDEO_URL) } returns null
        coEvery { trailerApi.getTrailer(VIDEO_URL, "Mixtape", "2023") } returns
            Response.success(TrailerResponse(url = "https://cdn.example/backend-trailer.mp4"))

        val source = service.getTrailerPlaybackSourceFromYouTubeUrl(
            youtubeUrl = VIDEO_URL,
            title = "Mixtape",
            year = "2023"
        )

        assertEquals("https://cdn.example/backend-trailer.mp4", source?.videoUrl)
        coVerify(exactly = 1) { trailerApi.getTrailer(VIDEO_URL, "Mixtape", "2023") }
        coVerify(exactly = 0) {
            extractor.searchForTrailerVideoId(any(), any(), any(), any())
        }
    }

    @Test
    fun `concurrent requests for same youtube id extract once`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { extractor.extractPlaybackSource(any()) } coAnswers {
            started.complete(Unit)
            release.await()
            TrailerPlaybackSource(videoUrl = "https://cdn.example/trailer.mp4")
        }

        val first = async { service.getTrailerPlaybackSourceFromYouTubeUrl(VIDEO_URL) }
        started.await()
        val second = async { service.getTrailerPlaybackSourceFromYouTubeUrl(VIDEO_URL) }
        waitForSecondCaller()
        release.complete(Unit)

        assertEquals("https://cdn.example/trailer.mp4", first.await()?.videoUrl)
        assertEquals("https://cdn.example/trailer.mp4", second.await()?.videoUrl)
        coVerify(exactly = 1) { extractor.extractPlaybackSource(any()) }
    }

    @Test
    fun `waiting caller takes over if extracting caller is cancelled`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { extractor.extractPlaybackSource(any()) } coAnswers {
            calls++
            if (calls == 1) {
                firstStarted.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
            TrailerPlaybackSource(videoUrl = "https://cdn.example/trailer.mp4")
        }

        val first = async { service.getTrailerPlaybackSourceFromYouTubeUrl(VIDEO_URL) }
        firstStarted.await()
        val second = async { service.getTrailerPlaybackSourceFromYouTubeUrl(VIDEO_URL) }
        waitForSecondCaller()
        first.cancel()

        val result = second.await()
        assertNotNull(result)
        assertEquals("https://cdn.example/trailer.mp4", result?.videoUrl)
    }

    private suspend fun waitForSecondCaller() {
        withContext(Dispatchers.Default) { delay(200) }
    }

    private suspend fun lookup(): TrailerPlaybackSource? = service.getTrailerPlaybackSource(
        title = "Some Movie",
        year = "2024",
        tmdbId = "123",
        type = "movie"
    )

    private fun trailer(index: Int) = TmdbVideoResult(
        iso6391 = "en",
        name = "Trailer $index",
        key = "trailer%04d".format(index).padEnd(11, 'x'),
        site = "YouTube",
        type = "Trailer",
        official = true
    )

    private companion object {
        const val VIDEO_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    }
}
