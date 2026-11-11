package com.nuvio.tv.domain.rewatch

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class RewatchSyncOutboxTest {
    private suspend inline fun <reified T : Throwable> assertSuspendFails(
        crossinline block: suspend () -> Unit
    ) {
        var failure: Throwable? = null
        try { block() } catch (error: Throwable) { failure = error }
        assertTrue("Expected ${T::class.java.simpleName}, got ${failure?.javaClass?.simpleName}",
            failure is T)
    }

    private class FakeStorage : RewatchOutboxStorage {
        val values = mutableMapOf<Int, String>()
        override suspend fun read(profileId: Int): String? = values[profileId]
        override suspend fun write(profileId: Int, payload: String) {
            values[profileId] = payload
        }
    }
    private fun event(id: String, deviceId: String = "simkl-device:a",
        revision: Long = 0) = RewatchQueuedEvent(
        eventId = id,
        deviceId = deviceId,
        canonicalSeriesId = "imdb:tt0386676",
        sessionId = "office-session",
        expectedRevision = revision,
        payload = "{\"type\":\"START\"}"
    )
    private fun outbox(storage: FakeStorage,
        current: (Int, String, String) -> Boolean = { _, _, _ -> true }) =
        RewatchSyncOutbox(storage, current)

    @Test
    fun offlineEventIsPersistedBeforeNetworkAndSurvivesRecreation() = runBlocking {
        val storage = FakeStorage()
        assertTrue(outbox(storage).enqueue(2, "12003", "simkl-device:a", event("one")))
        val rebuilt = outbox(storage)
        assertEquals(1, rebuilt.pendingCount(2, "12003", "simkl-device:a"))
        var observedPersisted = false
        assertEquals(RewatchOutboxDrainResult.DRAINED,
            rebuilt.drain(2, "12003", "simkl-device:a") {
                observedPersisted = storage.values[2]?.contains("one") == true
                RewatchOutboxSubmitResult.Accepted(1)
            }
        )
        assertTrue(observedPersisted)
        assertEquals(0, rebuilt.pendingCount(2, "12003", "simkl-device:a"))
    }

    @Test
    fun networkFailurePreservesCursorForRetry() = runBlocking {
        val storage = FakeStorage()
        val queue = outbox(storage)
        queue.enqueue(2, "12003", "simkl-device:a", event("one"))
        assertEquals(RewatchOutboxDrainResult.UNAVAILABLE,
            queue.drain(2, "12003", "simkl-device:a") {
                throw IllegalStateException("offline")
            })
        assertEquals(1, queue.pendingCount(2, "12003", "simkl-device:a"))
    }

    @Test
    fun revisionConflictDoesNotSilentlyOverwriteOrDiscard() = runBlocking {
        val storage = FakeStorage()
        val queue = outbox(storage)
        queue.enqueue(2, "12003", "simkl-device:a", event("one"))
        assertEquals(RewatchOutboxDrainResult.CONFLICT,
            queue.drain(2, "12003", "simkl-device:a") {
                RewatchOutboxSubmitResult.Conflict
            })
        assertEquals(1, queue.pendingCount(2, "12003", "simkl-device:a"))
    }

    @Test
    fun duplicateEventIdIsIdempotentButDifferentPayloadFailsSafe() = runBlocking {
        val storage = FakeStorage()
        val queue = outbox(storage)
        assertTrue(queue.enqueue(2, "12003", "simkl-device:a", event("one")))
        assertTrue(queue.enqueue(2, "12003", "simkl-device:a", event("one")))
        assertEquals(1, queue.pendingCount(2, "12003", "simkl-device:a"))
        assertFalse(queue.enqueue(2, "12003", "simkl-device:a",
            event("one").copy(payload = "{}")))
    }

    @Test
    fun accountAndDeviceRebindingNeverReplaysAnotherUsersEvents() = runBlocking {
        val storage = FakeStorage()
        val queue = outbox(storage)
        queue.enqueue(2, "12003", "simkl-device:a", event("one"))
        assertSuspendFails<IllegalArgumentException> {
            queue.pendingCount(2, "99221", "simkl-device:a")
        }
        assertSuspendFails<IllegalArgumentException> {
            queue.pendingCount(2, "12003", "simkl-device:b")
        }
        assertEquals(1, queue.pendingCount(2, "12003", "simkl-device:a"))
    }

    @Test
    fun profileSwitchDuringUploadRetainsEventWithoutDeletingIt() = runBlocking {
        val storage = FakeStorage()
        var account = "12003"
        val current: (Int, String, String) -> Boolean = { _, id, _ -> id == account }
        val queue = outbox(storage, current)
        queue.enqueue(2, "12003", "simkl-device:a", event("one"))
        assertEquals(RewatchOutboxDrainResult.PROFILE_CHANGED,
            queue.drain(2, "12003", "simkl-device:a") {
                account = "99221"
                RewatchOutboxSubmitResult.Accepted(1)
            })
        assertEquals(1, outbox(storage).pendingCount(2, "12003", "simkl-device:a"))
    }

    @Test
    fun uploadedEventIsRemovedOnlyAfterValidHigherServerRevision() = runBlocking {
        val storage = FakeStorage()
        val queue = outbox(storage)
        queue.enqueue(2, "12003", "simkl-device:a", event("one"))
        assertSuspendFails<IllegalArgumentException> {
            queue.drain(2, "12003", "simkl-device:a") {
                RewatchOutboxSubmitResult.Accepted(0)
            }
        }
        assertEquals(1, queue.pendingCount(2, "12003", "simkl-device:a"))
    }

    @Test
    fun malformedStorageFailsClosedWithoutResettingOriginalPayload() = runBlocking {
        val storage = FakeStorage()
        storage.values[2] = "{\"schemaVersion\":9}"
        assertSuspendFails<Exception> {
            outbox(storage).pendingCount(2, "12003", "simkl-device:a")
        }
        assertEquals("{\"schemaVersion\":9}", storage.values[2])
    }

    @Test
    fun unauthorizedPreservesQueuedEvents() = runBlocking {
        val storage = FakeStorage()
        val queue = outbox(storage)
        queue.enqueue(2, "12003", "simkl-device:a", event("one"))
        assertEquals(RewatchOutboxDrainResult.UNAUTHORIZED,
            queue.drain(2, "12003", "simkl-device:a") {
                RewatchOutboxSubmitResult.Unauthorized
            })
        assertEquals(1, queue.pendingCount(2, "12003", "simkl-device:a"))
    }

    @Test
    fun acknowledgedRevisionSurvivesRestartAndSeedsNextOfflineProgress() = runBlocking {
        val storage = FakeStorage()
        val initial = requireNotNull(startRewatchSession(
            ownerKey = "local-profile:2",
            canonicalSeriesId = "imdb:tt0386676",
            sessionId = "office-session",
            episode = RewatchEpisode(3, 17),
            wasAlreadyWatched = true,
            updatedAtEpochMs = 1
        ))
        val first = outbox(storage)
        assertTrue(first.enqueueCommittedTransition(
            2, "12003", "simkl-device:a",
            before = null,
            after = initial,
            checkpoint = RewatchRemoteCheckpoint.ConfirmedEmpty,
            eventId = "initial-import"
        ))
        assertEquals(RewatchOutboxDrainResult.DRAINED,
            first.drain(2, "12003", "simkl-device:a") { event ->
                RewatchOutboxSubmitResult.Accepted(event.expectedRevision + 1)
            })
        assertEquals(RewatchAcknowledgedCheckpoint(1, "office-session"),
            outbox(storage).acknowledgedCheckpoint(
                2, "12003", "simkl-device:a", "IMDB:tt0386676"
            ))
        val progressed = initial.copy(
            positionMs = 15_000, durationMs = 1_000_000,
            updatedAtEpochMs = 2
        )
        val restarted = outbox(storage)
        // Authenticated remote data can be unavailable after process restart.
        // A persisted acknowledgment remains valid as the local lower bound.
        assertTrue(restarted.enqueueCommittedTransition(
            2, "12003", "simkl-device:a",
            before = initial,
            after = progressed,
            checkpoint = RewatchRemoteCheckpoint.Unknown,
            eventId = "second-progress"
        ))
        assertEquals(1, restarted.pendingCount(2, "12003", "simkl-device:a"))
        assertEquals(RewatchOutboxDrainResult.UNAVAILABLE,
            restarted.drain(2, "12003", "simkl-device:a") { item ->
                assertEquals(1L, item.expectedRevision)
                RewatchOutboxSubmitResult.Unavailable
            })
    }

    @Test
    fun remoteRevisionAheadOrDifferentSessionStopsNewLocalEvents() = runBlocking {
        val storage = FakeStorage()
        val q = outbox(storage)
        q.enqueue(2, "12003", "simkl-device:a", event("seed"))
        assertEquals(RewatchOutboxDrainResult.DRAINED,
            q.drain(2, "12003", "simkl-device:a") {
                RewatchOutboxSubmitResult.Accepted(1)
            })
        val initial = requireNotNull(startRewatchSession(
            ownerKey = "local-profile:2", canonicalSeriesId = "imdb:tt0386676",
            sessionId = "office-session", episode = RewatchEpisode(3, 17),
            wasAlreadyWatched = true, updatedAtEpochMs = 1
        ))
        val progress = initial.copy(positionMs = 6000, updatedAtEpochMs = 2)
        val checkpoints = listOf(
            RewatchRemoteCheckpoint.Synced(2, initial.sessionId),
            RewatchRemoteCheckpoint.Synced(1, "different-session"),
            RewatchRemoteCheckpoint.ConfirmedEmpty
        )
        for ((index, checkpoint) in checkpoints.withIndex()) {
            assertFalse(q.enqueueCommittedTransition(
                2, "12003", "simkl-device:a", initial, progress,
                checkpoint, "blocked-$index"
            ))
        }
        assertEquals(0, q.pendingCount(2, "12003", "simkl-device:a"))
    }

    @Test
    fun staleSameSessionServerGetCannotRewindAcknowledgedRevision() = runBlocking {
        val storage = FakeStorage()
        val q = outbox(storage)
        q.enqueue(2, "12003", "simkl-device:a", event("seed", revision = 0))
        assertEquals(RewatchOutboxDrainResult.DRAINED,
            q.drain(2, "12003", "simkl-device:a") {
                RewatchOutboxSubmitResult.Accepted(1)
            })
        val initial = requireNotNull(startRewatchSession(
            ownerKey = "local-profile:2", canonicalSeriesId = "imdb:tt0386676",
            sessionId = "office-session", episode = RewatchEpisode(3, 17),
            wasAlreadyWatched = true, updatedAtEpochMs = 1
        ))
        val progress = initial.copy(positionMs = 6000, updatedAtEpochMs = 2)
        assertTrue(q.enqueueCommittedTransition(
            2, "12003", "simkl-device:a", initial, progress,
            RewatchRemoteCheckpoint.Synced(1, "office-session"),
            "next-progress"
        ))
        assertEquals(RewatchOutboxDrainResult.UNAVAILABLE,
            q.drain(2, "12003", "simkl-device:a") { item ->
                assertEquals(1L, item.expectedRevision)
                RewatchOutboxSubmitResult.Unavailable
            })
    }

    @Test
    fun skippedAcknowledgmentRevisionNeverDiscardsOfflineEvent() = runBlocking {
        val storage = FakeStorage()
        val q = outbox(storage)
        q.enqueue(2, "12003", "simkl-device:a", event("seed", revision = 0))
        assertSuspendFails<IllegalArgumentException> {
            q.drain(2, "12003", "simkl-device:a") {
                RewatchOutboxSubmitResult.Accepted(3)
            }
        }
        assertEquals(1, q.pendingCount(2, "12003", "simkl-device:a"))
        assertEquals(null, q.acknowledgedCheckpoint(
            2, "12003", "simkl-device:a", "imdb:tt0386676"
        ))
    }

    @Test
    fun corruptedAckDatabaseCannotBeSilentlyReset() = runBlocking {
        val storage = FakeStorage()
        storage.values[2] = """{"schemaVersion":1,"simklAccountId":"12003",
            "deviceId":"simkl-device:a","pending":[],"acknowledged":{
            "imdb:tt0386676":{"revision":-1,"sessionId":"office-session"}}}"""
        assertSuspendFails<IllegalArgumentException> {
            outbox(storage).pendingCount(2, "12003", "simkl-device:a")
        }
        assertTrue(storage.values[2]!!.contains("\"revision\":-1"))
    }

    @Test
    fun legacyOutboxWithoutAckFieldRemainsReadable() = runBlocking {
        val storage = FakeStorage()
        storage.values[2] = """{"schemaVersion":1,"simklAccountId":"12003",
            "deviceId":"simkl-device:a","pending":[]}"""
        assertEquals(0, outbox(storage).pendingCount(2, "12003", "simkl-device:a"))
        assertEquals(null, outbox(storage).acknowledgedCheckpoint(
            2, "12003", "simkl-device:a", "imdb:tt0386676"
        ))
    }

    private fun cleanReplay() = requireNotNull(startRewatchSession(
        ownerKey = "local-profile:2",
        canonicalSeriesId = "imdb:tt0386676",
        sessionId = "office-clean",
        episode = RewatchEpisode(3, 18),
        wasAlreadyWatched = true,
        updatedAtEpochMs = 200L
    )).copy(positionMs = 120_000L, durationMs = 1_800_000L)

    private fun verifiedClean(replay: RewatchSession, revision: Long = 4L) =
        RewatchRemoteLookup.Verified(
            RewatchRemoteCheckpoint.Synced(revision, replay.sessionId),
            replay.copy(updatedAtEpochMs = 0L)
        )

    @Test
    fun authenticatedExactMatchIsDurablyStoredAcrossAppRestart() = runBlocking {
        val storage = FakeStorage()
        val local = cleanReplay()
        val original = outbox(storage)
        assertTrue(original.recordVerifiedCleanBaseline(
            2, "12003", "simkl-device:a", local, verifiedClean(local)
        ))
        val restored = outbox(storage).cleanBaseline(
            2, "12003", "simkl-device:a", " IMDB:TT0386676 "
        )
        assertEquals(4L, restored?.revision)
        assertTrue(restored?.matches(local) == true)
        assertEquals(0L, restored?.toSession()?.updatedAtEpochMs)
    }

    @Test
    fun falseRemoteMatchNeverCreatesCleanMarker() = runBlocking {
        val storage = FakeStorage()
        val local = cleanReplay()
        val changed = local.copy(positionMs = local.positionMs + 30_000L)
        assertFalse(outbox(storage).recordVerifiedCleanBaseline(
            2, "12003", "simkl-device:a", local, verifiedClean(changed)
        ))
        assertEquals(null, outbox(storage).cleanBaseline(
            2, "12003", "simkl-device:a", local.canonicalSeriesId
        ))
    }

    @Test
    fun pendingReplayWritesCannotBeMarkedClean() = runBlocking {
        val storage = FakeStorage()
        val queue = outbox(storage)
        val local = cleanReplay()
        queue.enqueue(2, "12003", "simkl-device:a", event("pending"))
        assertFalse(queue.recordVerifiedCleanBaseline(
            2, "12003", "simkl-device:a", local, verifiedClean(local)
        ))
        assertEquals(null, queue.cleanBaseline(
            2, "12003", "simkl-device:a", local.canonicalSeriesId
        ))
    }

    @Test
    fun unknownOrEmptyVpsResponsesCannotCertifyCleanState() = runBlocking {
        val storage = FakeStorage()
        val queue = outbox(storage)
        val local = cleanReplay()
        val inputs = listOf(
            RewatchRemoteLookup.Invalid,
            RewatchRemoteLookup.Verified(
                RewatchRemoteCheckpoint.ConfirmedEmpty, null
            ),
            RewatchRemoteLookup.Verified(
                RewatchRemoteCheckpoint.Synced(4, local.sessionId), null
            )
        )
        for (lookup in inputs) {
            assertFalse(queue.recordVerifiedCleanBaseline(
                2, "12003", "simkl-device:a", local, lookup
            ))
        }
    }

    @Test
    fun profileSwitchAndOlderRemoteResponseCannotReplaceBaseline() = runBlocking {
        val storage = FakeStorage()
        var current = 2
        val queue = outbox(storage) { profile, _, _ -> profile == current }
        val local = cleanReplay()
        assertTrue(queue.recordVerifiedCleanBaseline(
            2, "12003", "simkl-device:a", local, verifiedClean(local, 7L)
        ))
        assertFalse(queue.recordVerifiedCleanBaseline(
            2, "12003", "simkl-device:a", local, verifiedClean(local, 6L)
        ))
        current = 3
        assertFalse(queue.recordVerifiedCleanBaseline(
            2, "12003", "simkl-device:a", local, verifiedClean(local, 8L)
        ))
        current = 2
        assertEquals(7L, queue.cleanBaseline(
            2, "12003", "simkl-device:a", local.canonicalSeriesId
        )?.revision)
    }

    @Test
    fun corruptCleanMarkerFailsWithoutDeletingOutboxData() = runBlocking {
        val storage = FakeStorage()
        val queue = outbox(storage)
        val local = cleanReplay()
        assertTrue(queue.recordVerifiedCleanBaseline(
            2, "12003", "simkl-device:a", local, verifiedClean(local)
        ))
        val original = requireNotNull(storage.values[2])
        storage.values[2] = original.replace(
            "\"ownerKey\":\"local-profile:2\"",
            "\"ownerKey\":\"local-profile:3\""
        )
        assertSuspendFails<IllegalArgumentException> {
            queue.cleanBaseline(2, "12003", "simkl-device:a", local.canonicalSeriesId)
        }
        assertEquals(storage.values[2], original.replace(
            "\"ownerKey\":\"local-profile:2\"",
            "\"ownerKey\":\"local-profile:3\""
        ))
    }

    @Test
    fun verifiedMatchedGetAdvancesAcknowledgmentWithoutPendingPost() = runBlocking {
        val storage = FakeStorage()
        val queue = outbox(storage)
        val local = cleanReplay()
        assertTrue(queue.recordVerifiedCleanBaseline(
            2, "12003", "simkl-device:a", local, verifiedClean(local, 9)
        ))
        val afterRestart = outbox(storage)
        assertEquals(
            RewatchAcknowledgedCheckpoint(9, local.sessionId),
            afterRestart.acknowledgedCheckpoint(
                2, "12003", "simkl-device:a", local.canonicalSeriesId
            )
        )
        val progress = local.copy(
            positionMs = local.positionMs + 1_000,
            updatedAtEpochMs = local.updatedAtEpochMs + 1
        )
        assertTrue(afterRestart.enqueueCommittedTransition(
            2, "12003", "simkl-device:a", local, progress,
            RewatchRemoteCheckpoint.Unknown, "after-get"
        ))
        assertEquals(RewatchOutboxDrainResult.UNAVAILABLE,
            afterRestart.drain(2, "12003", "simkl-device:a") { event ->
                assertEquals(9L, event.expectedRevision)
                RewatchOutboxSubmitResult.Unavailable
            })
    }

    @Test
    fun olderRemoteGetNeverRollsBackHigherAcknowledgedRevision() = runBlocking {
        val storage = FakeStorage()
        val queue = outbox(storage)
        val local = cleanReplay()
        assertTrue(queue.recordVerifiedCleanBaseline(
            2, "12003", "simkl-device:a", local, verifiedClean(local, 9)
        ))
        assertFalse(queue.recordVerifiedCleanBaseline(
            2, "12003", "simkl-device:a", local, verifiedClean(local, 8)
        ))
        assertEquals(9L, outbox(storage).acknowledgedCheckpoint(
            2, "12003", "simkl-device:a", local.canonicalSeriesId
        )?.revision)
    }

}
