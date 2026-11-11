package com.nuvio.tv.domain.rewatch

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RewatchCommittedSaveHandoffTest {
    private val device = "simkl-device:replay-only"
    private val identity = RewatchCommitIdentity(2, "12003", device)
    private val initial = requireNotNull(startRewatchSession(
        ownerKey = "local-profile:2",
        canonicalSeriesId = "imdb:tt0386676",
        sessionId = "office-session",
        episode = RewatchEpisode(3, 17),
        wasAlreadyWatched = true,
        updatedAtEpochMs = 100
    ))

    private class MemoryStorage : RewatchOutboxStorage {
        private val saved = mutableMapOf<Int, String>()
        override suspend fun read(profileId: Int): String? = saved[profileId]
        override suspend fun write(profileId: Int, payload: String) {
            saved[profileId] = payload
        }
    }

    @Test fun rejectedLocalSaveNeverEnqueues() = runBlocking {
        val storage = MemoryStorage()
        val outbox = RewatchSyncOutbox(storage) { _, _, _ -> true }
        val sink = RewatchCommittedOutboxSink(
            currentIdentity = { identity },
            checkpoint = { _, _ -> RewatchRemoteCheckpoint.ConfirmedEmpty },
            outbox = outbox,
            eventId = { "event-one" }
        )
        notifyCommittedRewatchSave(sink, false, 2, { 2 }, null, initial)
        assertEquals(0, outbox.pendingCount(2, "12003", device))
    }

    @Test fun confirmedLocalSaveQueuesOfflineWithoutSendingNetworkRequest() = runBlocking {
        val storage = MemoryStorage()
        val outbox = RewatchSyncOutbox(storage) { p, a, d ->
            p == identity.profileId && a == identity.simklAccountId &&
                d == identity.deviceId
        }
        val sink = RewatchCommittedOutboxSink(
            currentIdentity = { identity },
            checkpoint = { _, _ -> RewatchRemoteCheckpoint.ConfirmedEmpty },
            outbox = outbox,
            eventId = { "event-one" }
        )
        notifyCommittedRewatchSave(sink, true, 2, { 2 }, null, initial)
        assertEquals(1, outbox.pendingCount(2, "12003", device))
        var observed = ""
        val result = outbox.drain(2, "12003", device) { event ->
            observed = event.payload
            RewatchOutboxSubmitResult.Unavailable
        }
        assertEquals(RewatchOutboxDrainResult.UNAVAILABLE, result)
        assertTrue(observed.contains("IMPORT_SNAPSHOT"))
        assertEquals(1, outbox.pendingCount(2, "12003", device))
    }

    @Test fun noVerifiedCheckpointNeverQueuesEvenAfterSuccessfulSave() = runBlocking {
        val outbox = RewatchSyncOutbox(MemoryStorage()) { _, _, _ -> true }
        val sink = RewatchCommittedOutboxSink(
            currentIdentity = { identity },
            checkpoint = { _, _ -> RewatchRemoteCheckpoint.Unknown },
            outbox = outbox,
            eventId = { "event-one" }
        )
        notifyCommittedRewatchSave(sink, true, 2, { 2 }, null, initial)
        assertEquals(0, outbox.pendingCount(2, "12003", device))
    }

    @Test fun profileSwitchBetweenSaveAndHandoffCannotAppendOldAccountEvent() = runBlocking {
        val outbox = RewatchSyncOutbox(MemoryStorage()) { _, _, _ -> true }
        val sink = RewatchCommittedOutboxSink(
            currentIdentity = { identity },
            checkpoint = { _, _ -> RewatchRemoteCheckpoint.ConfirmedEmpty },
            outbox = outbox,
            eventId = { "event-one" }
        )
        notifyCommittedRewatchSave(sink, true, 2, { 3 }, null, initial)
        assertEquals(0, outbox.pendingCount(2, "12003", device))
        notifyCommittedRewatchSave(sink, true, 3, { 3 }, null, initial)
        assertEquals(0, outbox.pendingCount(2, "12003", device))
    }

    @Test fun identitySwitchDuringPlanningNeverEnqueues() = runBlocking {
        var current: RewatchCommitIdentity? = identity
        val outbox = RewatchSyncOutbox(MemoryStorage()) { _, _, _ -> true }
        val sink = RewatchCommittedOutboxSink(
            currentIdentity = { current },
            checkpoint = { _, _ ->
                current = identity.copy(simklAccountId = "99221")
                RewatchRemoteCheckpoint.ConfirmedEmpty
            },
            outbox = outbox,
            eventId = { "event-one" }
        )
        notifyCommittedRewatchSave(sink, true, 2, { 2 }, null, initial)
        assertEquals(0, outbox.pendingCount(2, "12003", device))
    }

    @Test fun failedSyncSinkCannotChangeSuccessfulLocalSaveOutcome() = runBlocking {
        var calls = 0
        notifyCommittedRewatchSave(
            sink = RewatchCommittedSaveSink { _, _, _ ->
                calls++
                error("offline outbox")
            },
            accepted = true,
            profileId = 2,
            activeProfileId = { 2 },
            before = null,
            after = initial
        )
        assertEquals(1, calls)
    }

    @Test fun localEpisodeCompletionCreatesVerifiedSuccessorEventNotProviderScrobble() = runBlocking {
        val outbox = RewatchSyncOutbox(MemoryStorage()) { _, _, _ -> true }
        val sink = RewatchCommittedOutboxSink(
            currentIdentity = { identity },
            checkpoint = { _, _ -> RewatchRemoteCheckpoint.Synced(2, initial.sessionId) },
            outbox = outbox,
            eventId = { "episode-complete" }
        )
        val next = initial.copy(
            episode = RewatchEpisode(3, 18),
            lastCompletedEpisode = RewatchEpisode(3, 17),
            phase = RewatchPhase.NEXT_UP,
            updatedAtEpochMs = 200
        )
        notifyCommittedRewatchSave(sink, true, 2, { 2 }, initial, next)
        assertEquals(1, outbox.pendingCount(2, "12003", device))
        outbox.drain(2, "12003", device) { event ->
            assertTrue(event.payload.contains("COMPLETE_EPISODE"))
            assertTrue(event.payload.contains("NEXT_UP"))
            assertTrue(!event.payload.contains("ownerKey"))
            RewatchOutboxSubmitResult.Conflict
        }
        assertEquals(1, outbox.pendingCount(2, "12003", device))
    }
}
