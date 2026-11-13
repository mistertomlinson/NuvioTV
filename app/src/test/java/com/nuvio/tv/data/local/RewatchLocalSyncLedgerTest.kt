package com.nuvio.tv.data.local

import com.google.gson.Gson
import com.nuvio.tv.domain.rewatch.RewatchEpisode
import com.nuvio.tv.domain.rewatch.RewatchPhase
import com.nuvio.tv.domain.rewatch.RewatchRemoteCheckpoint
import com.nuvio.tv.domain.rewatch.RewatchRemoteLookup
import com.nuvio.tv.domain.rewatch.RewatchSession
import com.nuvio.tv.domain.rewatch.startRewatchSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RewatchLocalSyncLedgerTest {
    private val account = "12003"
    private val device = "simkl-device:android-one"
    private val original = requireNotNull(startRewatchSession(
        ownerKey = "local-profile:2",
        canonicalSeriesId = "imdb:tt0386676",
        sessionId = "office-session",
        episode = RewatchEpisode(3, 18),
        wasAlreadyWatched = true,
        updatedAtEpochMs = 100
    )).copy(positionMs = 50_000, durationMs = 1_500_000)
    private val nextUp = original.copy(
        episode = RewatchEpisode(3, 19),
        positionMs = 0,
        durationMs = 0,
        lastCompletedEpisode = RewatchEpisode(3, 18),
        phase = RewatchPhase.NEXT_UP,
        updatedAtEpochMs = 0
    )

    private fun verified(
        session: RewatchSession = original,
        revision: Long = 5
    ) = RewatchRemoteLookup.Verified(
        RewatchRemoteCheckpoint.Synced(revision, session.sessionId),
        session.copy(updatedAtEpochMs = 0)
    )

    private fun clean(
        local: RewatchSession = original,
        remote: RewatchRemoteLookup = verified(),
        generation: Long = 3
    ): RewatchLocalSyncLedger {
        val previous = RewatchLocalSyncLedger(generation = generation)
        return requireNotNull(certifyRewatchLocalClean(
            listOf(local), previous, 2, account, device,
            local, generation, remote, false
        ))
    }

    private fun apply(
        sessions: List<RewatchSession> = listOf(original),
        ledger: RewatchLocalSyncLedger = clean(),
        local: RewatchSession? = original,
        generation: Long = ledger.generation,
        remote: RewatchRemoteLookup = verified(nextUp, 6),
        simklAccount: String = account,
        pending: Boolean = false
    ) = mergeRewatchRemoteWithLedger(
        sessions = sessions, ledger = ledger, profileId = 2,
        simklAccountId = simklAccount, deviceId = device,
        expectedLocal = local, expectedGeneration = generation,
        canonicalSeriesId = original.canonicalSeriesId,
        remoteLookup = remote, pendingOrUnpublished = pending,
        appliedAtEpochMs = 800
    )

    @Test fun exactAuthenticatedGetCertifiesCleanAndSurvivesSerialization() {
        val ledger = clean()
        val restored = decodeRewatchLocalSyncLedger(Gson().toJson(ledger), 2)
        assertEquals(ledger, restored)
        assertEquals(5L, restored.cleanBySeries.values.single().revision)
        assertEquals(3L, restored.cleanBySeries.values.single().generation)
        assertTrue(sameRewatchCursor(
            original, restored.cleanBySeries.values.single().cursor
        ))
    }

    @Test fun localReplayCommitInvalidatesProofInSameLedgerMutation() {
        val ledger = clean()
        val changed = onLocalRewatchCommitted(ledger,
            original.copy(positionMs = 70_000, updatedAtEpochMs = 200))
        assertEquals(4L, changed.generation)
        assertTrue(changed.cleanBySeries.isEmpty())
        assertNull(apply(ledger = changed, generation = changed.generation))
    }

    @Test fun verifiedNewerRemoteNextUpAtomicallyUpdatesCursorAndCleanProof() {
        val updated = requireNotNull(apply())
        assertEquals(RewatchEpisode(3, 19), updated.first.single().episode)
        assertEquals(RewatchPhase.NEXT_UP, updated.first.single().phase)
        assertEquals(4L, updated.second.generation)
        val proof = updated.second.cleanBySeries.values.single()
        assertEquals(6L, proof.revision)
        assertEquals(4L, proof.generation)
        assertTrue(proof.cursor == updated.first.single())
    }

    @Test fun laterLocalWriteRejectsRemoteResponseEvenIfSessionReturnedToSameValue() {
        // Progress may move forward and later back via a seek. Exact equality
        // of the cursor alone is insufficient; the durable generation matters.
        val afterAba = onLocalRewatchCommitted(
            onLocalRewatchCommitted(clean(), original.copy(positionMs = 90_000)),
            original
        )
        assertEquals(original, listOf(original).single())
        assertNull(apply(ledger = afterAba, generation = 3))
    }

    @Test fun absentCleanProofCannotOverwriteExistingLocalReplay() {
        assertNull(apply(ledger = RewatchLocalSyncLedger(generation = 3)))
    }

    @Test fun pendingOrUnpublishedLocalEventPreventsRemoteWrite() {
        assertNull(apply(pending = true))
    }

    @Test fun alternateSimklAccountAndWrongProfileCannotUseCleanProof() {
        assertNull(apply(simklAccount = "99221"))
        val otherProfile = original.copy(ownerKey = "local-profile:3")
        assertNull(apply(
            sessions = listOf(otherProfile), local = otherProfile
        ))
    }

    @Test fun remoteStaleRevisionCannotRewindNextUp() {
        assertNull(apply(remote = verified(nextUp, 5)))
        assertNull(apply(remote = verified(nextUp, 4)))
    }

    @Test fun cleanCertifyRejectsUnmatchedRemoteAndPendingWrites() {
        val ledger = RewatchLocalSyncLedger(generation = 3)
        assertNull(certifyRewatchLocalClean(
            listOf(original), ledger, 2, account, device, original,
            ledger.generation, verified(original.copy(positionMs = 88_000)), false
        ))
        assertNull(certifyRewatchLocalClean(
            listOf(original), ledger, 2, account, device, original,
            ledger.generation, verified(), true
        ))
    }

    @Test fun freshDeviceCanImportRemoteButLateLocalSavePreventsIt() {
        val emptyLedger = RewatchLocalSyncLedger(generation = 0)
        val imported = requireNotNull(apply(
            sessions = emptyList(), ledger = emptyLedger,
            local = null, generation = 0, remote = verified(nextUp, 6)
        ))
        assertEquals(RewatchEpisode(3, 19), imported.first.single().episode)
        assertEquals(1L, imported.second.generation)
        assertNull(apply(
            sessions = listOf(original), ledger = RewatchLocalSyncLedger(generation = 1),
            local = null, generation = 0
        ))
    }

    @Test fun malformedCleanProofFailsClosedInsteadOfResetting() {
        val saved = Gson().toJson(clean())
        assertThrows(IllegalArgumentException::class.java) {
            decodeRewatchLocalSyncLedger(
                saved.replace("local-profile:2", "local-profile:3"), 2
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeRewatchLocalSyncLedger(
                saved.replace("\"schemaVersion\":1", "\"schemaVersion\":99"), 2
            )
        }
    }

    @Test fun unrelatedSeriesSaveRetainsOtherCleanProofButChangesGeneration() {
        val ledger = clean()
        val unrelated = original.copy(canonicalSeriesId = "imdb:ttother")
        val changed = onLocalRewatchCommitted(ledger, unrelated)
        assertEquals(4L, changed.generation)
        assertEquals(1, changed.cleanBySeries.size)
        // Proof remains available but is no longer current until recertified
        assertNull(apply(ledger = changed, generation = changed.generation))
    }

    private fun officeFirstAdoption() : Triple<
        RewatchSession, RewatchSession, RewatchRemoteLookup.Verified
    > {
        val local = original.copy(
            canonicalSeriesId = "tt0386676",
            episode = RewatchEpisode(5, 16),
            sessionId = "android-existing-office",
            phase = RewatchPhase.NEXT_UP,
            lastCompletedEpisode = RewatchEpisode(5, 15),
            positionMs = 0L,
            durationMs = 0L
        )
        val fromIpad = local.copy(
            sessionId = "ios-newer-rewatch",
            episode = RewatchEpisode(5, 6),
            phase = RewatchPhase.PLAYING,
            lastCompletedEpisode = null,
            positionMs = 23_000L,
            durationMs = 1_200_000L,
            updatedAtEpochMs = 0L
        )
        val proof = RewatchRemoteLookup.Verified(
            RewatchRemoteCheckpoint.Synced(1L, fromIpad.sessionId),
            fromIpad
        )
        return Triple(local, fromIpad, proof)
    }

    @Test fun officeFirstTestAdoptionRequiresExplicitOneTimeApproval() {
        val (local, remote, proof) = officeFirstAdoption()
        assertTrue(isApprovedOfficeInitialTestAdoption(local, proof, false, false))
        assertFalse(isApprovedOfficeInitialTestAdoption(local, proof, true, false))
        assertFalse(isApprovedOfficeInitialTestAdoption(local, proof, false, true))
        assertFalse(isApprovedOfficeInitialTestAdoption(
            local.copy(canonicalSeriesId = "tt9999999"), proof, false, false
        ))
        assertFalse(isApprovedOfficeInitialTestAdoption(
            local.copy(episode = RewatchEpisode(5, 15)), proof, false, false
        ))
        assertFalse(isApprovedOfficeInitialTestAdoption(
            local, verified(remote.copy(episode = RewatchEpisode(5, 7)), 1L),
            false, false
        ))
        assertFalse(isApprovedOfficeInitialTestAdoption(
            local, RewatchRemoteLookup.Invalid, false, false
        ))
    }

    @Test fun officeInitialMigrationCreatesCleanServerProofWithoutLosingSimklHistory() {
        val (local, remote, proof) = officeFirstAdoption()
        val ledger = RewatchLocalSyncLedger(generation = 11L)
        fun merge(
            expectedGeneration: Long = ledger.generation,
            pending: Boolean = false,
            allow: Boolean = false,
            accountId: String = account
        ) = mergeRewatchRemoteWithLedger(
            sessions = listOf(local),
            ledger = ledger,
            profileId = 2,
            simklAccountId = accountId,
            deviceId = device,
            expectedLocal = local,
            expectedGeneration = expectedGeneration,
            canonicalSeriesId = local.canonicalSeriesId,
            remoteLookup = proof,
            pendingOrUnpublished = pending,
            appliedAtEpochMs = 5_000L,
            allowExplicitInitialTestAdoption = allow
        )
        assertNull(merge())
        assertNull(merge(expectedGeneration = 10L, allow = true))
        assertNull(merge(pending = true, allow = true))
        val adopted = requireNotNull(merge(allow = true))
        assertTrue(sameRewatchCursor(adopted.first.single(), remote))
        assertEquals(12L, adopted.second.generation)
        assertEquals(1L, adopted.second.cleanBySeries.getValue(
            local.canonicalSeriesId
        ).revision)
        val subsequentLocal = adopted.first.single().copy(
            positionMs = 50_000L, updatedAtEpochMs = 6_000L
        )
        val dirtied = onLocalRewatchCommitted(adopted.second, subsequentLocal)
        assertTrue(dirtied.cleanBySeries.isEmpty())
        assertNull(mergeRewatchRemoteWithLedger(
            sessions = listOf(subsequentLocal),
            ledger = dirtied,
            profileId = 2,
            simklAccountId = account,
            deviceId = device,
            expectedLocal = subsequentLocal,
            expectedGeneration = dirtied.generation,
            canonicalSeriesId = local.canonicalSeriesId,
            remoteLookup = proof,
            pendingOrUnpublished = false,
            appliedAtEpochMs = 7_000L
        ))
    }
}
