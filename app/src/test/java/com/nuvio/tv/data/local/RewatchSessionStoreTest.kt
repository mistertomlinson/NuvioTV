package com.nuvio.tv.data.local

import com.google.gson.Gson
import com.nuvio.tv.domain.rewatch.RewatchEpisode
import com.nuvio.tv.domain.rewatch.RewatchPhase
import com.nuvio.tv.domain.rewatch.RewatchSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RewatchSessionStoreTest {
    private fun session(
        owner: String = "user-A",
        series: String = "imdb:tt0386676",
        id: String = "rewatch-1",
        episode: Int = 5,
        updated: Long = 1000L
    ) = RewatchSession(
        ownerKey = owner,
        canonicalSeriesId = series,
        sessionId = id,
        episode = RewatchEpisode(3, episode),
        positionMs = 100_000L,
        durationMs = 1_200_000L,
        lastCompletedEpisode = RewatchEpisode(3, episode - 1),
        phase = RewatchPhase.PLAYING,
        updatedAtEpochMs = updated
    )

    @Test
    fun `new replay is added without touching other shows`() {
        val original = listOf(session(series = "imdb:other"))
        val updated = mergeRewatchSessionUpdate(original, session())
        assertEquals(2, updated?.size)
        assertEquals(original.first(), updated?.first())
    }

    @Test
    fun `same series identity is normalized across case and whitespace`() {
        val first = session(series = "imdb:tt0386676")
        val updated = mergeRewatchSessionUpdate(
            listOf(first),
            session(series = "  IMDB:TT0386676  ", episode = 6, updated = 2000L)
        )
        assertEquals(1, updated?.size)
        assertEquals(6, updated?.single()?.episode?.episode)
    }

    @Test
    fun `a stale callback cannot rewind the current replay`() {
        val previous = session(updated = 2000L, episode = 6)
        assertNull(
            mergeRewatchSessionUpdate(
                listOf(previous),
                session(updated = 1000L, episode = 5)
            )
        )
    }

    @Test
    fun `different replay sessions require an explicit restart`() {
        val old = session(id = "rewatch-1")
        val incoming = session(id = "rewatch-2", updated = 2000L)
        assertNull(mergeRewatchSessionUpdate(listOf(old), incoming))
        assertEquals(
            incoming,
            mergeRewatchSessionUpdate(listOf(old), incoming, replaceSession = true)?.single()
        )
    }

    @Test
    fun `different owners never update each other's series`() {
        val previous = session(owner = "owner-A")
        assertNull(
            mergeRewatchSessionUpdate(
                listOf(previous),
                session(owner = "owner-B", updated = 2000L),
                replaceSession = true
            )
        )
    }

    @Test
    fun `JSON round trip retains exact session and playback position`() {
        val entries = listOf(session(), session(series = "tmdb:other", episode = 2))
        val loaded = decodeRewatchSessions(Gson().toJson(entries))
        assertEquals(entries, loaded)
    }

    @Test
    fun `invalid or duplicate payload fails closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeRewatchSessions("null")
        }
        assertThrows(Exception::class.java) {
            decodeRewatchSessions("{invalid")
        }
        val duplicate = listOf(session(), session(series = " IMDB:TT0386676 "))
        assertThrows(IllegalArgumentException::class.java) {
            decodeRewatchSessions(Gson().toJson(duplicate))
        }
    }

    @Test
    fun `no session is an empty independent rewatch store`() {
        assertTrue(decodeRewatchSessions("[]").isEmpty())
        assertFalse(mergeRewatchSessionUpdate(emptyList(), session()).isNullOrEmpty())
    }
}
