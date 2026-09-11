package com.nuvio.tv.data.simkl

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SimklLibraryIdentityTest {

    @Test
    fun `projection collapses TMDB shell into richer canonical item`() {
        val projection = SimklSyncSnapshot(
            entries = listOf(tmdbShell(), canonicalItem())
        ).toSimklLibraryProjection()

        assertEquals(1, projection.items.size)
        val item = projection.items.single()
        assertEquals("tt1156398", item.id)
        assertEquals("tt1156398", item.imdbId)
        assertEquals(19_908, item.tmdbId)
        assertEquals(76_730L, item.simklId)
        assertNotNull(item.poster)
    }

    @Test
    fun `delta replaces differently keyed shell that shares TMDB identity`() {
        val merged = mergeSimklDelta(
            current = listOf(tmdbShell()),
            delta = SimklAllItemsResponse(movies = listOf(canonicalItem()))
        )

        assertEquals(1, merged.size)
        val media = merged.single().media!!
        assertEquals("tt1156398", media.ids.idValue("imdb"))
        assertEquals("19908", media.ids.idValue("tmdb"))
        assertEquals("76730", media.ids.simklIdValue())
    }

    private fun tmdbShell() = SimklLibraryEntry(
        mediaType = SimklMediaType.MOVIES,
        status = SimklListStatus.COMPLETED,
        movie = SimklMedia(
            title = "Zombieland",
            ids = mapOf("tmdb" to JsonPrimitive(19_908))
        )
    )

    private fun canonicalItem() = SimklLibraryEntry(
        mediaType = SimklMediaType.MOVIES,
        status = SimklListStatus.COMPLETED,
        localPosterUrl = "https://image.test/zombieland.webp",
        movie = SimklMedia(
            title = "Zombieland",
            year = 2009,
            ids = mapOf(
                "simkl" to JsonPrimitive(76_730),
                "imdb" to JsonPrimitive("tt1156398"),
                "tmdb" to JsonPrimitive(19_908)
            )
        )
    )
}
