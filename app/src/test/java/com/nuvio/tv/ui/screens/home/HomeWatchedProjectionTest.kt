package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeWatchedProjectionTest {

    @Test
    fun movie_samePosterKeyWithNewLookupIdentity_restartsProjection() {
        val previous =
            mapOf(
                "movie:example" to
                    setOf("example")
            )

        val current =
            mapOf(
                "movie:example" to
                    setOf(
                        "example",
                        "tt1234567",
                        "imdb:tt1234567"
                    )
            )

        assertTrue(
            shouldRestartHomeWatchedProjection(
                previous = previous,
                current = current,
                observerActive = true
            )
        )
    }

    @Test
    fun series_samePosterKeyWithNewLookupIdentity_restartsProjection() {
        val previous =
            mapOf(
                "series:example" to
                    setOf("example")
            )

        val current =
            mapOf(
                "series:example" to
                    setOf(
                        "example",
                        "tt7654321",
                        "imdb:tt7654321"
                    )
            )

        assertTrue(
            shouldRestartHomeWatchedProjection(
                previous = previous,
                current = current,
                observerActive = true
            )
        )
    }

    @Test
    fun identicalProjectionWithLiveObserver_doesNotRestart() {
        val projection =
            mapOf(
                "movie:tt1234567" to
                    setOf(
                        "tt1234567",
                        "imdb:tt1234567"
                    )
            )

        assertFalse(
            shouldRestartHomeWatchedProjection(
                previous = projection,
                current = projection,
                observerActive = true
            )
        )
    }

    @Test
    fun nonEmptyProjectionWithDeadObserver_restarts() {
        val projection =
            mapOf(
                "series:tt7654321" to
                    setOf(
                        "tt7654321",
                        "imdb:tt7654321"
                    )
            )

        assertTrue(
            shouldRestartHomeWatchedProjection(
                previous = projection,
                current = projection,
                observerActive = false
            )
        )
    }

    @Test
    fun emptyProjectionWithDeadObserver_doesNotCreateUselessObserver() {
        assertFalse(
            shouldRestartHomeWatchedProjection(
                previous = emptyMap(),
                current = emptyMap(),
                observerActive = false
            )
        )
    }
}
