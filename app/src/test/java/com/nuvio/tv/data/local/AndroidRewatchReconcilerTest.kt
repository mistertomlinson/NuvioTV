package com.nuvio.tv.data.local

import com.nuvio.tv.domain.rewatch.RewatchCommitIdentity
import com.nuvio.tv.domain.rewatch.RewatchEpisode
import com.nuvio.tv.domain.rewatch.RewatchPhase
import com.nuvio.tv.domain.rewatch.RewatchRemoteCheckpoint
import com.nuvio.tv.domain.rewatch.RewatchRemoteLookup
import com.nuvio.tv.domain.rewatch.RewatchSession
import com.nuvio.tv.domain.rewatch.RewatchOutboxStorage
import com.nuvio.tv.domain.rewatch.RewatchQueuedEvent
import com.nuvio.tv.domain.rewatch.RewatchSyncOutbox
import com.nuvio.tv.domain.rewatch.startRewatchSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidRewatchReconcilerTest {
    private val identity = RewatchCommitIdentity(2, "12003", "simkl-device:tv-one")
    private val initial = requireNotNull(startRewatchSession(
        ownerKey = "local-profile:2", canonicalSeriesId = "imdb:tt0386676",
        sessionId = "office-session", episode = RewatchEpisode(3, 18),
        wasAlreadyWatched = true, updatedAtEpochMs = 100
    )).copy(positionMs = 50_000, durationMs = 1_500_000)
    private val next = initial.copy(
        episode = RewatchEpisode(3, 19), phase = RewatchPhase.NEXT_UP,
        positionMs = 0, durationMs = 0,
        lastCompletedEpisode = RewatchEpisode(3, 18),
        updatedAtEpochMs = 0
    )
    private fun verified(session: RewatchSession = next, revision: Long = 6L) =
        RewatchRemoteLookup.Verified(
            RewatchRemoteCheckpoint.Synced(revision, session.sessionId),
            session.copy(updatedAtEpochMs = 0)
        )

    private class MemoryOutboxStorage : RewatchOutboxStorage {
        private val saved = mutableMapOf<Int, String>()
        override suspend fun read(profileId: Int) = saved[profileId]
        override suspend fun write(profileId: Int, payload: String) {
            saved[profileId] = payload
        }
    }

    private class FakeLocal(
        start: RewatchSession? = null,
        serverVerifiedAtStart: RewatchRemoteLookup? = null
    ) : RewatchAtomicLocalAccess {
        var sessions = if (start == null) emptyList() else listOf(start)
        var ledger = RewatchLocalSyncLedger(generation = 2L)
        var applyCalls = 0
        init {
            if (start != null && serverVerifiedAtStart != null) {
                ledger = requireNotNull(certifyRewatchLocalClean(
                    sessions, ledger, 2, "12003", "simkl-device:tv-one",
                    start, ledger.generation, serverVerifiedAtStart, false
                ))
            }
        }
        override suspend fun observe(profileId: Int) =
            RewatchObservedLocalSync(profileId, sessions.toList(), ledger.generation)

        override suspend fun certify(
            identity: RewatchCommitIdentity,
            expectedLocal: RewatchSession,
            expectedGeneration: Long,
            remote: RewatchRemoteLookup,
            pending: Boolean,
            stillCurrent: () -> Boolean
        ): Boolean {
            if (!stillCurrent()) return false
            val changed = certifyRewatchLocalClean(
                sessions, ledger, identity.profileId,
                identity.simklAccountId, identity.deviceId,
                expectedLocal, expectedGeneration, remote, pending
            ) ?: return false
            if (!stillCurrent()) return false
            ledger = changed
            return true
        }

        override suspend fun apply(
            identity: RewatchCommitIdentity,
            canonicalSeriesId: String,
            observed: RewatchObservedLocalSync,
            remote: RewatchRemoteLookup,
            pending: Boolean,
            nowEpochMs: Long,
            stillCurrent: () -> Boolean
        ): Boolean {
            applyCalls++
            if (!stillCurrent()) return false
            val current = observed.sessions.firstOrNull {
                it.canonicalSeriesId.trim().equals(canonicalSeriesId, ignoreCase = true)
            }
            val result = mergeRewatchRemoteWithLedger(
                sessions, ledger, identity.profileId,
                identity.simklAccountId, identity.deviceId,
                current, observed.generation, canonicalSeriesId,
                remote, pending, nowEpochMs
            ) ?: return false
            if (!stillCurrent()) return false
            sessions = result.first
            ledger = result.second
            return true
        }

        fun localSave(replay: RewatchSession) {
            val index = sessions.indexOfFirst {
                it.canonicalSeriesId == replay.canonicalSeriesId
            }
            sessions = if (index == -1) sessions + replay
                else sessions.toMutableList().apply { set(index, replay) }
            ledger = onLocalRewatchCommitted(ledger, replay)
        }
    }

    private data class Fixture(
        val local: FakeLocal,
        val queue: RewatchSyncOutbox,
        val reconcile: AndroidRewatchReconciler,
        val getIdentity: () -> RewatchCommitIdentity?,
        val setIdentity: (RewatchCommitIdentity?) -> Unit
    )

    private fun fixture(
        start: RewatchSession? = initial,
        baseline: Boolean = true,
        remote: RewatchRemoteLookup = verified(),
        duringFetch: (() -> Unit)? = null
    ): Fixture {
        var active: RewatchCommitIdentity? = identity
        val local = FakeLocal(start,
            if (baseline && start != null) verified(start, 5L) else null)
        val queue = RewatchSyncOutbox(MemoryOutboxStorage()) { p, a, d ->
            active?.profileId == p && active?.simklAccountId == a &&
                active?.deviceId == d
        }
        val reconciler = AndroidRewatchReconciler(
            ensureIdentity = { active },
            currentIdentity = { active },
            fetchSeries = {
                duringFetch?.invoke()
                remote
            },
            local = local,
            outbox = queue,
            nowEpochMs = { 500L }
        )
        return Fixture(local, queue, reconciler, { active }, { active = it })
    }

    @Test fun newerIpadNextUpIsAppliedAndPersistedRevisionIsUpdated() = runBlocking {
        val f = fixture()
        assertEquals(RewatchPullResult.REMOTE_APPLIED,
            f.reconcile.pullSeries("imdb:tt0386676"))
        assertEquals(RewatchEpisode(3, 19), f.local.sessions.single().episode)
        assertEquals(6L, f.queue.acknowledgedCheckpoint(
            2, "12003", identity.deviceId, "imdb:tt0386676"
        )?.revision)
        assertEquals(6L, f.local.ledger.cleanBySeries.values.single().revision)
        assertEquals(3L, f.local.ledger.generation)
        assertEquals(1, f.local.applyCalls)
    }

    @Test fun observedCleanReplayMatchingServerOnlyCertifiesWithoutMutation() = runBlocking {
        val f = fixture(remote = verified(initial, 5L), baseline = false)
        assertEquals(RewatchPullResult.CLEAN_VERIFIED,
            f.reconcile.pullSeries("imdb:tt0386676"))
        assertEquals(initial, f.local.sessions.single())
        assertEquals(0, f.local.applyCalls)
        assertEquals(5L, f.queue.acknowledgedCheckpoint(
            2, "12003", identity.deviceId, initial.canonicalSeriesId
        )?.revision)
    }

    @Test fun lateNetworkResponseCannotOverwritePlayerSaveDuringGet() = runBlocking {
        var local: FakeLocal? = null
        val f = fixture(duringFetch = {
            local!!.localSave(initial.copy(positionMs = 80_000, updatedAtEpochMs = 200))
        })
        local = f.local
        assertEquals(RewatchPullResult.LOCAL_CONFLICT,
            f.reconcile.pullSeries(initial.canonicalSeriesId))
        assertEquals(80_000L, f.local.sessions.single().positionMs)
        assertEquals(null, f.queue.acknowledgedCheckpoint(
            2, "12003", identity.deviceId, initial.canonicalSeriesId
        ))
    }

    @Test fun pendingOfflineEventStopsReconciliationWithoutLosingEvent() = runBlocking {
        val f = fixture()
        val item = RewatchQueuedEvent(
            eventId = "offline", deviceId = identity.deviceId,
            canonicalSeriesId = initial.canonicalSeriesId,
            sessionId = initial.sessionId, expectedRevision = 5,
            payload = """{"type":"PROGRESS"}"""
        )
        assertTrue(f.queue.enqueue(
            2, "12003", identity.deviceId, item
        ))
        assertEquals(RewatchPullResult.LOCAL_PENDING,
            f.reconcile.pullSeries(initial.canonicalSeriesId))
        assertEquals(initial, f.local.sessions.single())
        assertEquals(1, f.queue.pendingCount(2, "12003", identity.deviceId))
    }

    @Test fun differentSimklProfileDuringGetCannotApplyRemote() = runBlocking {
        lateinit var f: Fixture
        f = fixture(duringFetch = {
            f.setIdentity(identity.copy(profileId = 3, simklAccountId = "99221"))
        })
        assertEquals(RewatchPullResult.PROFILE_CHANGED,
            f.reconcile.pullSeries(initial.canonicalSeriesId))
        assertEquals(initial, f.local.sessions.single())
    }

    @Test fun freshDeviceCanImportNextUpAndCertifyAcknowledgedServerRevision() = runBlocking {
        val f = fixture(start = null, baseline = false)
        assertEquals(RewatchPullResult.REMOTE_APPLIED,
            f.reconcile.pullSeries(initial.canonicalSeriesId))
        assertEquals(RewatchEpisode(3, 19), f.local.sessions.single().episode)
        assertEquals(6L, f.queue.acknowledgedCheckpoint(
            2, "12003", identity.deviceId, initial.canonicalSeriesId
        )?.revision)
    }

    @Test fun malformedOrUnavailableRemoteCannotEraseLocal() = runBlocking {
        val f = fixture(remote = RewatchRemoteLookup.Invalid)
        assertEquals(RewatchPullResult.NO_VERIFIED_REMOTE,
            f.reconcile.pullSeries(initial.canonicalSeriesId))
        assertEquals(initial, f.local.sessions.single())
    }

    @Test fun emptyRemoteCannotDeletePreviousLocalReplay() = runBlocking {
        val f = fixture(remote = RewatchRemoteLookup.Verified(
            RewatchRemoteCheckpoint.ConfirmedEmpty, null
        ))
        assertEquals(RewatchPullResult.REMOTE_EMPTY,
            f.reconcile.pullSeries(initial.canonicalSeriesId))
        assertEquals(initial, f.local.sessions.single())
    }

    @Test fun unprovenPriorReplayStateCannotBeOverwritten() = runBlocking {
        val f = fixture(baseline = false)
        assertEquals(RewatchPullResult.LOCAL_CONFLICT,
            f.reconcile.pullSeries(initial.canonicalSeriesId))
        assertEquals(initial, f.local.sessions.single())
    }

    @Test fun secondReadIsIdempotentAfterApplyingNextUp() = runBlocking {
        val f = fixture()
        assertEquals(RewatchPullResult.REMOTE_APPLIED,
            f.reconcile.pullSeries(initial.canonicalSeriesId))
        assertEquals(RewatchPullResult.CLEAN_VERIFIED,
            f.reconcile.pullSeries(initial.canonicalSeriesId))
        assertEquals(1, f.local.applyCalls)
    }
}
