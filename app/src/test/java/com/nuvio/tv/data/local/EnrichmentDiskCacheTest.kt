package com.nuvio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.nuvio.tv.core.tmdb.TmdbEnrichment
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EnrichmentDiskCacheTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var context: Context

    @Before fun setUp() {
        context = mockk()
        every { context.filesDir } returns temp.root
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    private fun enrichment(title: String) = TmdbEnrichment(
        localizedTitle = title, description = null, genres = emptyList(),
        backdrop = null, logo = null, poster = null, directorMembers = emptyList(),
        writerMembers = emptyList(), castMembers = emptyList(), releaseInfo = null,
        rating = null, runtimeMinutes = null, director = emptyList(), writer = emptyList(),
        productionCompanies = emptyList(), networks = emptyList(), ageRating = null,
        status = null, countries = null, language = null, collectionId = null, collectionName = null
    )

    @Test fun `Home repairs retain other titles and settled external ratings across restart`() = runTest {
        val cache = HomeEnrichmentDiskCache(context)
        cache.saveAll(
            mapOf("a" to enrichment("first"), "b" to enrichment("second")),
            mapOf("a" to HomeExternalMetaState(true, 8.5f), "b" to HomeExternalMetaState(true, null))
        )
        cache.saveEntry("a", enrichment("repaired"))
        val restored = HomeEnrichmentDiskCache(context)
        assertEquals(mapOf("a" to enrichment("repaired"), "b" to enrichment("second")), restored.loadAll())
        assertEquals(mapOf("a" to HomeExternalMetaState(true, 8.5f), "b" to HomeExternalMetaState(true, null)), restored.loadExternalMetaStates())
        restored.saveAll(restored.loadAll())
        assertEquals(HomeExternalMetaState(true, 8.5f), HomeEnrichmentDiskCache(context).loadExternalMetaStates()["a"])
    }

    @Test fun `settled null rating clears old rating without dropping title`() = runTest {
        val cache = HomeEnrichmentDiskCache(context)
        val values = mapOf("a" to enrichment("first"))
        cache.saveAll(values, mapOf("a" to HomeExternalMetaState(true, 8f)))
        cache.saveAll(values, mapOf("a" to HomeExternalMetaState(true, null)))
        val restored = HomeEnrichmentDiskCache(context)
        assertEquals(values, restored.loadAll())
        assertEquals(HomeExternalMetaState(true, null), restored.loadExternalMetaStates()["a"])
    }

    @Test fun `unchanged Home snapshot preserves timestamp and bytes`() = runTest {
        val cache = HomeEnrichmentDiskCache(context)
        val values = mapOf("a" to enrichment("first"))
        cache.saveAll(values)
        val file = File(temp.root, "home_enrichment/cache_v2.json")
        val before = file.readText()
        cache.saveAll(values)
        cache.saveEntry("a", values.getValue("a"))
        assertEquals(before, file.readText())
    }

    @Test fun `TMDB expiry and entry limit remain intact`() = runTest {
        val file = File(temp.root, "tmdb_enrichment/cache_v3.json")
        file.parentFile.mkdirs()
        val now = System.currentTimeMillis()
        val old = now - 8L * 24 * 60 * 60 * 1000
        file.writeText(Gson().toJson(mapOf(
            "old" to mapOf("enrichment" to enrichment("old"), "cachedAtMs" to old),
            "fresh" to mapOf("enrichment" to enrichment("fresh"), "cachedAtMs" to now)
        )))
        val cache = TmdbEnrichmentDiskCache(context)
        assertEquals(setOf("fresh"), cache.loadAll().keys)
        cache.saveAll((1..2001).associate { "$it" to enrichment("title $it") })
        assertEquals(2000, TmdbEnrichmentDiskCache(context).loadAll().size)
    }

    @Test fun `mapping expiry and entry limit remain intact`() = runTest {
        val file = File(temp.root, "imdb_tmdb_mapping/cache.json")
        file.parentFile.mkdirs()
        val old = System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000
        file.writeText("""{"tt1":{"tmdbId":1,"cachedAtMs":$old}}""")
        val cache = ImdbTmdbMappingCache(context)
        assertTrue(cache.loadAll().isEmpty())
        cache.saveAll((1..5001).associate { "tt$it" to it })
        assertEquals(5000, ImdbTmdbMappingCache(context).loadAll().size)
    }

    @Test fun `legacy minified mapping fields remain readable`() = runTest {
        val file = File(temp.root, "imdb_tmdb_mapping/cache.json")
        file.parentFile.mkdirs()
        val now = System.currentTimeMillis()
        file.writeText("""{"tt1":{"a":123,"b":$now}}""")
        val cache = ImdbTmdbMappingCache(context)
        assertEquals(mapOf("tt1" to 123), cache.loadAll())
        cache.saveAll(mapOf("tt1" to 123, "tt2" to 456))
        assertEquals(mapOf("tt1" to 123, "tt2" to 456), ImdbTmdbMappingCache(context).loadAll())
        assertTrue(file.readText().contains("tmdbId"))
    }

    @Test fun `corrupt Home cache recovers on next successful save`() = runTest {
        val file = File(temp.root, "home_enrichment/cache_v2.json")
        file.parentFile.mkdirs()
        file.writeText("{broken")
        val cache = HomeEnrichmentDiskCache(context)
        assertTrue(cache.loadAll().isEmpty())
        cache.saveEntry("a", enrichment("recovered"))
        assertEquals(enrichment("recovered"), HomeEnrichmentDiskCache(context).loadAll()["a"])
    }
}
