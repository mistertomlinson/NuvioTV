package com.nuvio.tv.domain.rewatch

import kotlinx.coroutines.CancellationException

/**
 * The player calls this ONLY after its replay DataStore update succeeded.
 * It never decides that a provider scrobble should occur, nor does it read
 * or change watched history or Continue Watching.
 *
 * The production integration is initially disabled until the selected
 * profile's Simkl identity and remote revision are verified. No network is
 * performed by this boundary.
 */
fun interface RewatchCommittedSaveSink {
    suspend fun committed(
        profileId: Int,
        before: RewatchSession?,
        after: RewatchSession
    )
}

internal object DisabledRewatchCommittedSaveSink : RewatchCommittedSaveSink {
    override suspend fun committed(
        profileId: Int,
        before: RewatchSession?,
        after: RewatchSession
    ) = Unit
}

internal suspend fun notifyCommittedRewatchSave(
    sink: RewatchCommittedSaveSink,
    accepted: Boolean,
    profileId: Int,
    activeProfileId: () -> Int,
    before: RewatchSession?,
    after: RewatchSession
) {
    if (!accepted || profileId <= 0 || activeProfileId() != profileId ||
        after.ownerKey != "local-profile:$profileId" ||
        (before != null && (
            before.ownerKey != after.ownerKey ||
            !before.canonicalSeriesId.trim().equals(
                after.canonicalSeriesId.trim(), ignoreCase = true
            )
        ))) return
    try {
        sink.committed(profileId, before, after)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // An optional replay-sync failure MUST NOT roll back a successful
        // local save or cause fallback to Simkl watched-history tracking.
    }
}

/**
 * Pure, no-network bridge from successful local saves to the durable
 * replay-only outbox. The app supplies a checkpoint ONLY after a verified
 * authenticated GET and supplies a current, matched Simkl identity.
 *
 * Unknown checkpoints and stale/wrong profile identities do nothing:
 * never assume a failed VPS read means the remote slot is empty.
 */
internal class RewatchCommittedOutboxSink(
    private val currentIdentity: () -> RewatchCommitIdentity?,
    private val checkpoint: (RewatchCommitIdentity, String) -> RewatchRemoteCheckpoint,
    private val outbox: RewatchSyncOutbox,
    private val eventId: () -> String
) : RewatchCommittedSaveSink {
    override suspend fun committed(
        profileId: Int,
        before: RewatchSession?,
        after: RewatchSession
    ) {
        val identity = currentIdentity() ?: return
        if (identity.profileId != profileId) return
        val confirmed = checkpoint(identity, after.canonicalSeriesId)
        val planned = planCommittedRewatchEvent(
            before = before,
            after = after,
            checkpoint = confirmed,
            eventId = eventId(),
            deviceId = identity.deviceId
        ) ?: return
        if (currentIdentity() != identity) return
        outbox.enqueue(
            profileId = profileId,
            simklAccountId = identity.simklAccountId,
            deviceId = identity.deviceId,
            event = planned
        )
        // No upload here. The queue is drained only by an explicit,
        // separately validated transport coordinator.
    }
}

internal data class RewatchCommitIdentity(
    val profileId: Int,
    val simklAccountId: String,
    val deviceId: String
)
