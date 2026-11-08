package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.ui.screens.home.normalizeHomeSeriesWatchedIds
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchWatchedBadgeLookupTest {
    @Test
    fun `search tmdb result with imdb ID matches completed series from Simkl`() {
        val watched = normalizeHomeSeriesWatchedIds(setOf("tt13356646"))
        assertTrue(
            isSearchSeriesWatched(
                itemId = "tmdb:119279",
                imdbId = "tt13356646",
                normalizedWatchedSeriesIds = watched
            )
        )
    }

    @Test
    fun `search imdb prefixed series matches raw completed ID`() {
        val watched = normalizeHomeSeriesWatchedIds(setOf("tt13356646"))
        assertTrue(
            isSearchSeriesWatched(
                itemId = "imdb:tt13356646",
                imdbId = null,
                normalizedWatchedSeriesIds = watched
            )
        )
    }

    @Test
    fun `discover result compares normalized IMDb casing and whitespace`() {
        val watched = normalizeHomeSeriesWatchedIds(setOf("IMDB:TT13356646"))
        assertTrue(
            isSearchSeriesWatched(
                itemId = "  TMDB:119279  ",
                imdbId = " TT13356646 ",
                normalizedWatchedSeriesIds = watched
            )
        )
    }

    @Test
    fun `direct TMDB match works without IMDb metadata`() {
        val watched = normalizeHomeSeriesWatchedIds(setOf("tmdb:119279"))
        assertTrue(
            isSearchSeriesWatched(
                itemId = "tmdb:119279",
                imdbId = null,
                normalizedWatchedSeriesIds = watched
            )
        )
    }

    @Test
    fun `unrelated series cannot inherit an existing completed badge`() {
        val watched = normalizeHomeSeriesWatchedIds(setOf("tt13356646", "tmdb:119279"))
        assertFalse(
            isSearchSeriesWatched(
                itemId = "tmdb:9000",
                imdbId = "tt0009000",
                normalizedWatchedSeriesIds = watched
            )
        )
    }

    @Test
    fun `no watched history means no checkmark`() {
        assertFalse(
            isSearchSeriesWatched(
                itemId = "tmdb:119279",
                imdbId = "tt13356646",
                normalizedWatchedSeriesIds = emptySet()
            )
        )
    }
}
