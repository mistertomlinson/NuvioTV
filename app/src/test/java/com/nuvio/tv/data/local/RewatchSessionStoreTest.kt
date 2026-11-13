package com.nuvio.tv.data.local

import com.google.gson.Gson
import com.nuvio.tv.domain.rewatch.RewatchEpisode
import com.nuvio.tv.domain.rewatch.RewatchPhase
import com.nuvio.tv.domain.rewatch.RewatchSession
import com.nuvio.tv.domain.rewatch.RewatchRemoteCheckpoint
import com.nuvio.tv.domain.rewatch.RewatchRemoteLookup
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
    fun `legacy minified sideload session remains readable`() {
        // Observed R8 mappings from installed sideload: RewatchSession
        // a..i and RewatchEpisode a,b. New Gson writes the stable names.
        val legacy = """[{"a":"local-profile:2","b":"tt0386676","c":"old-office","d":{"a":5,"b":16},"e":100000,"f":1200000,"g":null,"h":"PLAYING","i":1000}]"""
        val restored = decodeRewatchSessions(legacy).single()
        assertEquals("local-profile:2", restored.ownerKey)
        assertEquals("tt0386676", restored.canonicalSeriesId)
        assertEquals(RewatchEpisode(5, 16), restored.episode)
        assertEquals(100_000L, restored.positionMs)
        assertEquals(RewatchPhase.PLAYING, restored.phase)
        assertTrue(Gson().toJson(restored).contains("\"canonicalSeriesId\""))
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

    private fun verifiedRemoteReplay(
        local: RewatchSession,
        episode: Int = 19,
        revision: Long = 4
    ) = RewatchRemoteLookup.Verified(
        checkpoint = RewatchRemoteCheckpoint.Synced(revision, local.sessionId),
        remoteSession = local.copy(
            episode = RewatchEpisode(3, episode),
            phase = RewatchPhase.NEXT_UP,
            positionMs = 0L,
            durationMs = 0L,
            lastCompletedEpisode = RewatchEpisode(3, episode - 1),
            updatedAtEpochMs = 0L
        )
    )

    private fun applyRemote(
        existing: List<RewatchSession>,
        expected: RewatchSession?,
        baseline: RewatchSession?,
        remote: RewatchRemoteLookup,
        pending: Boolean = false,
        unpublished: Boolean = false,
        appliedAt: Long = 3000L
    ) = mergeVerifiedRemoteRewatchSnapshot(
        existing = existing,
        profileId = 2,
        canonicalSeriesId = "imdb:tt0386676",
        expectedLocal = expected,
        remoteLookup = remote,
        lastAppliedRevision = 3,
        lastKnownCleanLocalSession = baseline,
        hasUnacknowledgedLocalEvents = pending,
        hasUnpublishedLocalChanges = unpublished,
        appliedAtEpochMs = appliedAt
    )

    @Test
    fun `verified newer iPad next up replaces only clean replay and keeps other titles`() {
        val old = session(owner = "local-profile:2", episode = 18)
        val another = session(owner = "local-profile:2", series = "imdb:other")
        val result = applyRemote(
            existing = listOf(another, old),
            expected = old,
            baseline = old,
            remote = verifiedRemoteReplay(old)
        )
        assertEquals(another, result?.first())
        assertEquals(RewatchEpisode(3, 19), result?.last()?.episode)
        assertEquals(RewatchPhase.NEXT_UP, result?.last()?.phase)
        assertEquals(3000L, result?.last()?.updatedAtEpochMs)
    }

    @Test
    fun `intervening player progress cannot be overwritten by late remote GET`() {
        val old = session(owner = "local-profile:2", episode = 18)
        val changed = old.copy(positionMs = old.positionMs + 30_000, updatedAtEpochMs = 2500)
        assertNull(applyRemote(
            existing = listOf(changed),
            expected = old,
            baseline = old,
            remote = verifiedRemoteReplay(old)
        ))
    }

    @Test
    fun `newer local replay session cannot be replaced by older remote session`() {
        val old = session(owner = "local-profile:2", episode = 18)
        val restarted = old.copy(sessionId = "new-session", updatedAtEpochMs = 2500)
        assertNull(applyRemote(
            existing = listOf(restarted), expected = old,
            baseline = old, remote = verifiedRemoteReplay(old)
        ))
    }

    @Test
    fun `unacknowledged or unpublished edits block remote replacement`() {
        val old = session(owner = "local-profile:2", episode = 18)
        assertNull(applyRemote(
            listOf(old), old, old, verifiedRemoteReplay(old),
            pending = true
        ))
        assertNull(applyRemote(
            listOf(old), old, old, verifiedRemoteReplay(old),
            unpublished = true
        ))
        assertNull(applyRemote(
            listOf(old), old, null, verifiedRemoteReplay(old)
        ))
    }

    @Test
    fun `fresh profile can import verified replay without original provider history`() {
        val old = session(owner = "local-profile:2", episode = 18)
        val result = applyRemote(
            existing = emptyList(), expected = null, baseline = null,
            remote = verifiedRemoteReplay(old)
        )
        assertEquals(1, result?.size)
        assertEquals(RewatchEpisode(3, 19), result?.single()?.episode)
    }

    @Test
    fun `wrong profile and invalid remote checkpoint cannot mutate replay state`() {
        val old = session(owner = "local-profile:2", episode = 18)
        val bad = verifiedRemoteReplay(old).copy(
            remoteSession = old.copy(ownerKey = "local-profile:3")
        )
        assertNull(applyRemote(listOf(old), old, old, bad))
        assertNull(applyRemote(
            listOf(old), old, old, RewatchRemoteLookup.Invalid
        ))
    }

    @Test
    fun `stale observed empty slot cannot overwrite newly created replay`() {
        val old = session(owner = "local-profile:2", episode = 18)
        assertNull(applyRemote(
            listOf(old), null, null, verifiedRemoteReplay(old)
        ))
    }

}
