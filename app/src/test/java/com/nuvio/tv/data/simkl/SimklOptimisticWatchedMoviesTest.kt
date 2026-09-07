package com.nuvio.tv.data.simkl

import org.junit.Assert.assertEquals
import org.junit.Test

class SimklOptimisticWatchedMoviesTest {
    @Test
    fun `optimistic completion adds movie immediately`() {
        val result = applySimklWatchedMovieOverrides(
            remoteIds = setOf("movie:existing"),
            overrides = mapOf("movie:new" to true)
        )

        assertEquals(setOf("movie:existing", "movie:new"), result)
    }

    @Test
    fun `optimistic removal removes movie immediately`() {
        val result = applySimklWatchedMovieOverrides(
            remoteIds = setOf("movie:keep", "movie:remove"),
            overrides = mapOf("movie:remove" to false)
        )

        assertEquals(setOf("movie:keep"), result)
    }

    @Test
    fun `unrelated override does not remove remote movies`() {
        val result = applySimklWatchedMovieOverrides(
            remoteIds = setOf("movie:keep"),
            overrides = mapOf("movie:other" to false)
        )

        assertEquals(setOf("movie:keep"), result)
    }

    @Test
    fun `optimistic completion publishes catalog and imdb aliases`() {
        val result = optimisticSimklMovieIds(
            contentId = "tmdb:123",
            videoId = "tt7654321"
        )

        assertEquals(
            setOf("tmdb:123", "tt7654321", "imdb:tt7654321"),
            result
        )
    }

    @Test
    fun `prefixed imdb id also publishes raw alias`() {
        val result = optimisticSimklMovieIds(
            contentId = "imdb:tt7654321",
            videoId = null
        )

        assertEquals(setOf("imdb:tt7654321", "tt7654321"), result)
    }

    @Test
    fun `optimistic removal clears catalog and imdb aliases`() {
        val aliases = optimisticSimklMovieIds(
            contentId = "tmdb:123",
            videoId = "tt7654321"
        )
        val result = applySimklWatchedMovieOverrides(
            remoteIds = aliases,
            overrides = aliases.associateWith { false }
        )

        assertEquals(emptySet<String>(), result)
    }
}
