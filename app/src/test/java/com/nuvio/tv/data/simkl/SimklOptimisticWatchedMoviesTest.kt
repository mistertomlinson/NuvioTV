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
}
