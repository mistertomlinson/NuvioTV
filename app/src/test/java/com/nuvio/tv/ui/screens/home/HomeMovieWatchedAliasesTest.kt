package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeMovieWatchedAliasesTest {
    @Test
    fun `imdb completion matches movie stored under catalog id`() {
        val lookupIds = homeMovieWatchedLookupIds(
            itemId = "tmdb:123",
            imdbId = "tt7654321"
        )
        val watchedIds = normalizeHomeMovieWatchedIds(setOf("imdb:tt7654321"))

        assertTrue(lookupIds.any(watchedIds::contains))
    }

    @Test
    fun `movie ids match without case sensitivity`() {
        val lookupIds = homeMovieWatchedLookupIds(
            itemId = "IMDB:TT7654321",
            imdbId = null
        )
        val watchedIds = normalizeHomeMovieWatchedIds(setOf("tt7654321"))

        assertTrue(lookupIds.any(watchedIds::contains))
    }

    @Test
    fun `unrelated movie remains unwatched`() {
        val lookupIds = homeMovieWatchedLookupIds(
            itemId = "tmdb:123",
            imdbId = "tt7654321"
        )
        val watchedIds = normalizeHomeMovieWatchedIds(setOf("tt0000001"))

        assertFalse(lookupIds.any(watchedIds::contains))
    }
}
