package com.nuvio.tv.domain.rewatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RewatchRemoteApplyPolicyTest {
    private val previous = requireNotNull(startRewatchSession(
        ownerKey = "local-profile:2",
        canonicalSeriesId = "imdb:tt0386676",
        sessionId = "office-replay",
        episode = RewatchEpisode(3, 18),
        wasAlreadyWatched = true,
        updatedAtEpochMs = 100
    ))
    private val nextUp = previous.copy(
        episode = RewatchEpisode(3, 19),
        positionMs = 0,
        durationMs = 0,
        lastCompletedEpisode = RewatchEpisode(3, 18),
        phase = RewatchPhase.NEXT_UP,
        updatedAtEpochMs = 0
    )
    private val incoming = RewatchRemoteLookup.Verified(
        checkpoint = RewatchRemoteCheckpoint.Synced(4, previous.sessionId),
        remoteSession = nextUp
    )
    private fun decide(
        local: RewatchSession? = previous,
        lookup: RewatchRemoteLookup = incoming,
        revision: Long = 3,
        baseline: RewatchSession? = previous,
        pending: Boolean = false,
        unpublished: Boolean = false,
        profileId: Int = 2
    ) = decideRewatchRemoteApplication(
        profileId = profileId,
        canonicalSeriesId = "imdb:tt0386676",
        local = local,
        remoteLookup = lookup,
        lastAppliedRevision = revision,
        lastKnownCleanLocalSession = baseline,
        hasUnacknowledgedLocalEvents = pending,
        hasUnpublishedLocalChanges = unpublished
    )

    @Test fun cleanAcknowledgedReplayCanAdoptNewerRemoteNextUp() {
        val result = decide()
        assertTrue(result is RewatchRemoteApplyDecision.ApplyRemote)
        result as RewatchRemoteApplyDecision.ApplyRemote
        assertEquals(RewatchEpisode(3, 19), result.session.episode)
        assertEquals(RewatchPhase.NEXT_UP, result.session.phase)
        assertEquals(4L, result.revision)
    }

    @Test fun newDeviceCanAdoptRemoteWhenItsLocalReplayIsAbsent() {
        assertTrue(decide(local = null, baseline = null, revision = 0) is
            RewatchRemoteApplyDecision.ApplyRemote)
    }

    @Test fun unqueuedLocalChangeCannotBeOverwrittenEvenWithEmptyOutbox() {
        val local = previous.copy(positionMs = 180_000, updatedAtEpochMs = 500)
        assertEquals(RewatchRemoteApplyDecision.PreserveLocalConflict,
            decide(local = local))
        assertEquals(RewatchRemoteApplyDecision.PreserveLocalConflict,
            decide(baseline = null))
        assertEquals(RewatchRemoteApplyDecision.PreserveLocalConflict,
            decide(unpublished = true))
    }

    @Test fun offlinePendingEventsPreventRemoteApplication() {
        assertEquals(RewatchRemoteApplyDecision.PreserveLocalConflict,
            decide(pending = true))
    }

    @Test fun wrongProfileWrongOwnerAndWrongSeriesCannotApply() {
        assertEquals(RewatchRemoteApplyDecision.PreserveLocalConflict,
            decide(profileId = 3))
        val wrongOwner = previous.copy(ownerKey = "local-profile:3")
        assertEquals(RewatchRemoteApplyDecision.PreserveLocalConflict,
            decide(local = wrongOwner))
        val wrongRemote = incoming.copy(
            remoteSession = nextUp.copy(ownerKey = "local-profile:3")
        )
        assertEquals(RewatchRemoteApplyDecision.PreserveLocalConflict,
            decide(lookup = wrongRemote))
        val wrongSession = incoming.copy(
            checkpoint = RewatchRemoteCheckpoint.Synced(4, "another-session")
        )
        assertEquals(RewatchRemoteApplyDecision.PreserveLocalConflict,
            decide(lookup = wrongSession))
    }

    @Test fun unknownMalformedAndRemoteEmptyNeverDeleteLocalReplay() {
        assertEquals(RewatchRemoteApplyDecision.PreserveLocalConflict,
            decide(lookup = RewatchRemoteLookup.Invalid))
        assertEquals(RewatchRemoteApplyDecision.NoChange,
            decide(lookup = RewatchRemoteLookup.Verified(
                RewatchRemoteCheckpoint.ConfirmedEmpty, null
            )))
    }

    @Test fun oldRemoteRevisionCannotRewindAConfirmedCursor() {
        assertEquals(RewatchRemoteApplyDecision.PreserveLocalConflict,
            decide(revision = 5))
    }

    @Test fun equalRevisionIsNoopOnlyWhenSameActualReplayCursor() {
        val same = incoming.copy(
            remoteSession = previous,
            checkpoint = RewatchRemoteCheckpoint.Synced(3, previous.sessionId)
        )
        assertEquals(RewatchRemoteApplyDecision.NoChange,
            decide(lookup = same))
        assertEquals(RewatchRemoteApplyDecision.PreserveLocalConflict,
            decide(lookup = incoming.copy(
                checkpoint = RewatchRemoteCheckpoint.Synced(3, previous.sessionId)
            )))
    }

    @Test fun localReplayDeletedWithBaselineDoesNotResurrectUnexpectedly() {
        assertEquals(RewatchRemoteApplyDecision.PreserveLocalConflict,
            decide(local = null, baseline = previous))
    }
}
