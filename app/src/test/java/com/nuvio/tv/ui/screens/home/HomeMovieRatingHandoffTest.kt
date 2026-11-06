package com.nuvio.tv.ui.screens.home

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeMovieRatingHandoffTest {
    @Test
    fun `rating prompt appears before remote history completes`() = runBlocking {
        val events = mutableListOf<String>()
        val historyRelease = CompletableDeferred<Unit>()

        val action = async {
            completeMovieWithEarlyRatingHandoff(
                isRatingAvailable = {
                    events += "provider checked"
                    true
                },
                showRatingPrompt = {
                    events += "prompt shown"
                },
                persistCompletion = {
                    events += "history started"
                    historyRelease.await()
                    events += "history completed"
                }
            )
        }

        withTimeout(2_000L) {
            while ("history started" !in events) yield()
        }
        assertEquals(
            listOf("provider checked", "prompt shown", "history started"),
            events
        )
        assertFalse(action.isCompleted)

        historyRelease.complete(Unit)
        action.await()
        assertEquals("history completed", events.last())
    }

    @Test
    fun `unavailable rating provider still marks movie watched without prompt`() = runBlocking {
        var shown = false
        var persisted = false

        completeMovieWithEarlyRatingHandoff(
            isRatingAvailable = { false },
            showRatingPrompt = { shown = true },
            persistCompletion = { persisted = true }
        )

        assertFalse(shown)
        assertTrue(persisted)
    }

    @Test
    fun `rating eligibility lookup failure does not suppress watched mutation`() = runBlocking {
        var shown = false
        var persisted = false

        completeMovieWithEarlyRatingHandoff(
            isRatingAvailable = { throw IllegalStateException("auth unavailable") },
            showRatingPrompt = { shown = true },
            persistCompletion = { persisted = true }
        )

        assertFalse(shown)
        assertTrue(persisted)
    }

    @Test
    fun `coroutine cancellation is not swallowed by eligibility fallback`() = runBlocking {
        var persisted = false

        val result = runCatching {
            completeMovieWithEarlyRatingHandoff(
                isRatingAvailable = { throw CancellationException("cancelled") },
                showRatingPrompt = { error("should not show") },
                persistCompletion = { persisted = true }
            )
        }

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertFalse(persisted)
    }

    @Test
    fun `history write failure still reaches the caller for status rollback`() = runBlocking {
        var prompted = false

        val result = runCatching {
            completeMovieWithEarlyRatingHandoff(
                isRatingAvailable = { true },
                showRatingPrompt = { prompted = true },
                persistCompletion = { throw IllegalStateException("writer rejected") }
            )
        }

        assertTrue(prompted)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }
}
