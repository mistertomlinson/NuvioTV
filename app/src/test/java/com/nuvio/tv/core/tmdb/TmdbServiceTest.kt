package com.nuvio.tv.core.tmdb

import android.util.Log
import com.nuvio.tv.data.local.ImdbTmdbMappingCache
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbExternalIdsResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class TmdbServiceTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `blank TMDB imdb id is not returned or cached`() = runTest {
        val tmdbApi = mockk<TmdbApi>()
        val mappingCache = mockk<ImdbTmdbMappingCache>(relaxed = true)
        val service = TmdbService(tmdbApi, mappingCache)

        coEvery {
            tmdbApi.getTvExternalIds(87012, any())
        } returns Response.success(
            TmdbExternalIdsResponse(id = 87012, imdbId = "   ")
        )

        assertNull(service.tmdbToImdb(87012, "series"))
        assertNull(service.getCachedImdbId(87012))
    }

    @Test
    fun `blank pre-cached mapping is ignored`() {
        val service = TmdbService(
            tmdbApi = mockk(relaxed = true),
            imdbTmdbMappingCache = mockk(relaxed = true)
        )

        service.preCacheMapping("", 87012)

        assertNull(service.getCachedImdbId(87012))
        assertNull(service.getCachedTmdbId(""))
    }

    @Test
    fun `valid TMDB imdb id is normalized and cached`() = runTest {
        val tmdbApi = mockk<TmdbApi>()
        val mappingCache = mockk<ImdbTmdbMappingCache>(relaxed = true)
        val service = TmdbService(tmdbApi, mappingCache)

        coEvery {
            tmdbApi.getTvExternalIds(87012, any())
        } returns Response.success(
            TmdbExternalIdsResponse(id = 87012, imdbId = " tt1877368 ")
        )

        assertEquals("tt1877368", service.tmdbToImdb(87012, "series"))
        assertEquals("tt1877368", service.tmdbToImdb(87012, "series"))
        coVerify(exactly = 1) { tmdbApi.getTvExternalIds(87012, any()) }
    }
    @Test
    fun `concurrent lookups wait for disk restoration instead of fetching again`() = runTest {
        val tmdbApi = mockk<TmdbApi>()
        val mappingCache = mockk<ImdbTmdbMappingCache>()
        coEvery { mappingCache.loadAll() } coAnswers {
            delay(100)
            (1..32).associate { "tt$it" to it }
        }
        val service = TmdbService(tmdbApi, mappingCache)
        val results = (1..32).map { id ->
            async { service.imdbToTmdb("tt$id", "movie") }
        }.awaitAll()
        assertEquals((1..32).toList(), results)
        coVerify(exactly = 1) { mappingCache.loadAll() }
        coVerify(exactly = 0) { tmdbApi.findByExternalId(any(), any(), any()) }
    }

    @Test
    fun `cancelled restoration is retried by the next lookup`() = runTest {
        val mappingCache = mockk<ImdbTmdbMappingCache>()
        var attempts = 0
        coEvery { mappingCache.loadAll() } coAnswers {
            attempts++
            if (attempts == 1) throw CancellationException("cancelled load")
            mapOf("tt1" to 123)
        }
        val service = TmdbService(mockk(), mappingCache)
        try {
            service.imdbToTmdb("tt1", "movie")
            org.junit.Assert.fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(123, service.imdbToTmdb("tt1", "movie"))
        assertEquals(2, attempts)
    }

}
