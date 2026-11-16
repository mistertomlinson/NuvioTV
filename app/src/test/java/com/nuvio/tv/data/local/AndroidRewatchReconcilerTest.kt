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
import com.nuvio.tv.domain.rewatch.RewatchArchivedProgressProof
import com.nuvio.tv.domain.rewatch.RewatchForegroundAccess
import com.nuvio.tv.domain.rewatch.RewatchForegroundSynchronizer
import com.nuvio.tv.domain.rewatch.RewatchForegroundResult
import com.nuvio.tv.domain.rewatch.RewatchOutboxDrainResult
import com.nuvio.tv.domain.rewatch.RewatchOutboxSubmitResult
import com.nuvio.tv.domain.rewatch.recordRewatchProgress
import com.nuvio.tv.domain.rewatch.startRewatchSession
import com.nuvio.tv.domain.rewatch.RewatchOutboxEnvelope
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.ui.screens.detail.resolveRewatchDetailsTarget
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
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
            archivedProgress: RewatchArchivedProgressProof?,
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
                remote, pending, nowEpochMs,
                archivedProgress = archivedProgress
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
        val setIdentity: (RewatchCommitIdentity?) -> Unit,
        val storage: MemoryOutboxStorage
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
        val storage = MemoryOutboxStorage()
        val queue = RewatchSyncOutbox(storage) { p, a, d ->
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
        return Fixture(local, queue, reconciler, { active }, { active = it }, storage)
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

    @Test fun pausedAndroidProgressConflictYieldsToIpadSelectionThroughForegroundSync() = runBlocking {
        val paused = initial.copy(episode = RewatchEpisode(6, 4))
        val ipad = paused.copy(episode = RewatchEpisode(5, 17),
            positionMs = 31_948, updatedAtEpochMs = 0)
        val f = fixture(start = paused, remote = verified(ipad, 1044))
        val latest = paused.copy(positionMs = 51_000, updatedAtEpochMs = 200)
        f.local.localSave(latest)
        assertTrue(f.queue.enqueueCommittedTransition(2, "12003", identity.deviceId,
            paused, latest, RewatchRemoteCheckpoint.Synced(5, paused.sessionId), "paused-save"))
        var attemptedPosts = 0
        val access = object : RewatchForegroundAccess {
            override suspend fun connect() = identity
            override fun stillCurrent(identity: RewatchCommitIdentity) = identity == this@AndroidRewatchReconcilerTest.identity
            override suspend fun remoteSeriesIds() = listOf(paused.canonicalSeriesId)
            override suspend fun observeLocal(profileId: Int) = f.local.observe(profileId)
            override suspend fun applyRemote(seriesId: String) =
                f.reconcile.pullSeries(seriesId) == RewatchPullResult.CLEAN_VERIFIED
            override suspend fun checkpoint(seriesId: String) = verified(ipad, 1044)
            override suspend fun hasPending(identity: RewatchCommitIdentity, seriesId: String) =
                f.queue.hasPendingForSeries(2, "12003", identity.deviceId, seriesId)
            override suspend fun enqueueImport(identity: RewatchCommitIdentity, session: RewatchSession) = false
            override suspend fun upload() = f.queue.drain(2, "12003", identity.deviceId) {
                attemptedPosts++
                RewatchOutboxSubmitResult.Conflict
            }
            override suspend fun recoverUploadConflict() =
                f.reconcile.pullSeries(paused.canonicalSeriesId) == RewatchPullResult.REMOTE_APPLIED
        }
        assertEquals(RewatchForegroundResult.COMPLETED,
            RewatchForegroundSynchronizer(access).reconcile())
        assertEquals(1, attemptedPosts) // Never POST the old episode at the new revision.
        assertEquals(0, f.queue.pendingCount(2, "12003", identity.deviceId))
        val adopted = f.local.sessions.single()
        assertEquals(ipad.episode, adopted.episode)
        assertEquals(31_948L, adopted.positionMs)
        assertEquals(1044L, f.local.ledger.cleanBySeries.values.single().revision)
        // A late save from the still-paused S6E4 player cannot undo S5E17.
        assertEquals(adopted, recordRewatchProgress(adopted, paused.episode,
            51_000, paused.durationMs, 600))
    }

    @Test fun handoffReceiptRepairsCrashBetweenArchiveAndLocalApply() = runBlocking {
        val f = fixture()
        val latest = initial.copy(positionMs = 51_000, updatedAtEpochMs = 200)
        f.local.localSave(latest)
        assertTrue(f.queue.enqueueCommittedTransition(2, "12003", identity.deviceId,
            initial, latest, RewatchRemoteCheckpoint.Synced(5, initial.sessionId), "paused"))
        val observed = f.local.observe(2)
        assertTrue(f.queue.archiveSupersededPlayback(identity, latest, observed.generation,
            verified(), { true }))
        assertEquals(RewatchPullResult.REMOTE_APPLIED,
            f.reconcile.pullSeries(initial.canonicalSeriesId))
        assertEquals(next.episode, f.local.sessions.single().episode)
    }

    @Test fun localSaveAfterArchiveInvalidatesReceiptEvenIfCursorReturnsToSameValues() = runBlocking {
        val f = fixture()
        val latest = initial.copy(positionMs = 51_000, updatedAtEpochMs = 200)
        f.local.localSave(latest)
        assertTrue(f.queue.enqueueCommittedTransition(2, "12003", identity.deviceId,
            initial, latest, RewatchRemoteCheckpoint.Synced(5, initial.sessionId), "paused"))
        assertTrue(f.queue.archiveSupersededPlayback(identity, latest,
            f.local.ledger.generation, verified(), { true }))
        f.local.localSave(latest.copy(positionMs = 90_000, updatedAtEpochMs = 201))
        f.local.localSave(latest.copy(updatedAtEpochMs = 202))
        assertEquals(RewatchPullResult.LOCAL_CONFLICT,
            f.reconcile.pullSeries(initial.canonicalSeriesId))
        assertEquals(51_000L, f.local.sessions.single().positionMs)
    }

    @Test fun savingAnotherSeriesDoesNotPermanentlyInvalidateOfficeCleanProof() = runBlocking {
        val f = fixture()
        f.local.localSave(initial.copy(canonicalSeriesId = "imdb:tt-other",
            sessionId = "other-show", updatedAtEpochMs = 200))
        assertEquals(RewatchPullResult.REMOTE_APPLIED,
            f.reconcile.pullSeries(initial.canonicalSeriesId))
        assertEquals(next.episode, f.local.sessions.first { it.canonicalSeriesId == initial.canonicalSeriesId }.episode)
        assertEquals(2, f.local.sessions.size)
    }

    @Test fun reported1040CompletionFetches1044CursorAndRetargetsDetailsWithoutPlayback() = runBlocking {
        val before = initial.copy(canonicalSeriesId = "tt0386676", episode = RewatchEpisode(6, 4),
            positionMs = 1_200_000, durationMs = 1_298_966)
        val nextUp = before.copy(episode = RewatchEpisode(6, 5), positionMs = 0, durationMs = 0,
            phase = RewatchPhase.NEXT_UP, lastCompletedEpisode = before.episode, updatedAtEpochMs = 200)
        val ipad = before.copy(episode = RewatchEpisode(5, 17), positionMs = 31_948,
            lastCompletedEpisode = null, updatedAtEpochMs = 0)
        val f = fixture(start = nextUp, baseline = false, remote = verified(ipad, 1044))
        f.local.ledger = RewatchLocalSyncLedger(generation = 1045)
        assertTrue(f.queue.recordVerifiedCleanBaseline(2, "12003", identity.deviceId,
            before, verified(before, 1040)))
        assertTrue(f.queue.enqueueCommittedTransition(2, "12003", identity.deviceId,
            before, nextUp, RewatchRemoteCheckpoint.Synced(1040, before.sessionId), "complete-s6e4"))
        val original = Json.decodeFromString<RewatchOutboxEnvelope>(f.storage.read(2)!!).pending
        var posted = 0
        val access = object : RewatchForegroundAccess {
            override suspend fun connect() = identity
            override fun stillCurrent(identity: RewatchCommitIdentity) = f.getIdentity() == identity
            override suspend fun remoteSeriesIds() = listOf(before.canonicalSeriesId)
            override suspend fun observeLocal(profileId: Int) = f.local.observe(profileId)
            override suspend fun applyRemote(seriesId: String) =
                f.reconcile.pullSeries(seriesId) == RewatchPullResult.CLEAN_VERIFIED
            override suspend fun checkpoint(seriesId: String) = verified(ipad, 1044)
            override suspend fun hasPending(identity: RewatchCommitIdentity, seriesId: String) =
                f.queue.hasPendingForSeries(2, "12003", identity.deviceId, seriesId)
            override suspend fun enqueueImport(identity: RewatchCommitIdentity, session: RewatchSession) = false
            override suspend fun upload() = f.queue.drain(2, "12003", identity.deviceId) {
                posted++
                RewatchOutboxSubmitResult.Conflict
            }
            override suspend fun recoverUploadConflict() =
                f.reconcile.pullSeries(before.canonicalSeriesId) == RewatchPullResult.REMOTE_APPLIED
        }
        assertEquals(RewatchForegroundResult.COMPLETED, RewatchForegroundSynchronizer(access).reconcile())
        val adopted = f.local.sessions.single()
        assertEquals(ipad.episode, adopted.episode)
        assertEquals(31_948L, adopted.positionMs)
        assertEquals(1, posted) // Rejected original only; no rebased completion or reposition.
        val disk = Json.decodeFromString<RewatchOutboxEnvelope>(f.storage.read(2)!!)
        assertTrue(disk.pending.isEmpty())
        assertEquals(original, disk.recoveryArchive)
        assertEquals(1044L, disk.acknowledged.values.single().revision)
        assertEquals(1046L, f.local.ledger.generation)
        assertEquals(1044L, f.local.ledger.cleanBySeries.values.single().revision)
        fun video(season: Int, episode: Int) = Video(id = "tt0386676:$season:$episode",
            title = "Episode", released = null, thumbnail = null,
            season = season, episode = episode, overview = null)
        val target = resolveRewatchDetailsTarget(2, ProfileRewatchSessions(2, f.local.sessions),
            setOf(before.canonicalSeriesId), listOf(video(5, 17), video(6, 5)))
        assertEquals("tt0386676:5:17", target?.video?.id)
        assertEquals(31_948L, target?.session?.positionMs)
        // Even exiting the previous S6E4 player cannot restore its completed cursor.
        assertEquals(adopted, recordRewatchProgress(adopted, before.episode,
            before.positionMs, before.durationMs, 1000))
        // Ordinary playback after recovery must use the new server revision.
        assertTrue(f.queue.enqueueCommittedTransition(2, "12003", identity.deviceId,
            adopted, adopted.copy(positionMs = 50_000, updatedAtEpochMs = 1100),
            RewatchRemoteCheckpoint.Unknown, "ordinary-progress-after-recovery"))
        val resumed = Json.decodeFromString<RewatchOutboxEnvelope>(f.storage.read(2)!!)
        assertEquals(1044L, resumed.pending.single().expectedRevision)
        assertEquals(original, resumed.recoveryArchive)
    }
}
