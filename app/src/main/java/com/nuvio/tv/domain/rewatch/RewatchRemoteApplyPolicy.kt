package com.nuvio.tv.domain.rewatch

/**
 * Conservative remote-to-local replay promotion policy.
 *
 * A matching Simkl owner and an authenticated VPS snapshot are necessary but
 * NOT sufficient to overwrite an existing device-local replay. The caller
 * also needs an independently recorded clean local baseline (the cursor at
 * the last acknowledged or applied revision). Merely having an empty outbox
 * does not prove that a local replay is clean: a previous optional queue
 * write may have failed or may have been disabled.
 *
 * This pure decision layer does no network, persistence or watched-history
 * writes. The actual merge remains disabled until its durable baseline and
 * race-safe DataStore update are implemented and tested.
 */
internal sealed interface RewatchRemoteApplyDecision {
    data object NoChange : RewatchRemoteApplyDecision
    data object PreserveLocalConflict : RewatchRemoteApplyDecision
    data class ApplyRemote(val session: RewatchSession, val revision: Long) :
        RewatchRemoteApplyDecision
}

private fun sameReplayCursor(a: RewatchSession, b: RewatchSession): Boolean =
    a.ownerKey == b.ownerKey &&
        a.canonicalSeriesId.trim().equals(
            b.canonicalSeriesId.trim(), ignoreCase = true
        ) &&
        a.sessionId == b.sessionId &&
        a.episode == b.episode &&
        a.positionMs == b.positionMs &&
        a.durationMs == b.durationMs &&
        a.lastCompletedEpisode == b.lastCompletedEpisode &&
        a.phase == b.phase

/**
 * The remote payload must already have come from the current profile's
 * authenticated, validated response. Never use local clock timestamps as
 * cross-device ordering evidence.
 */
internal fun decideRewatchRemoteApplication(
    profileId: Int,
    canonicalSeriesId: String,
    local: RewatchSession?,
    remoteLookup: RewatchRemoteLookup,
    lastAppliedRevision: Long,
    lastKnownCleanLocalSession: RewatchSession?,
    hasUnacknowledgedLocalEvents: Boolean,
    hasUnpublishedLocalChanges: Boolean
): RewatchRemoteApplyDecision {
    if (profileId <= 0 || canonicalSeriesId.isBlank() ||
        lastAppliedRevision < 0 ||
        remoteLookup !is RewatchRemoteLookup.Verified ||
        hasUnacknowledgedLocalEvents || hasUnpublishedLocalChanges) {
        return RewatchRemoteApplyDecision.PreserveLocalConflict
    }
    val ownerKey = "local-profile:$profileId"
    fun matchesOwner(session: RewatchSession) =
        session.ownerKey == ownerKey &&
            session.canonicalSeriesId.trim().equals(
                canonicalSeriesId.trim(), ignoreCase = true
            )

    if (local != null && !matchesOwner(local)) {
        return RewatchRemoteApplyDecision.PreserveLocalConflict
    }
    if (lastKnownCleanLocalSession != null &&
        !matchesOwner(lastKnownCleanLocalSession)) {
        return RewatchRemoteApplyDecision.PreserveLocalConflict
    }
    val checkpoint = remoteLookup.checkpoint
    if (checkpoint !is RewatchRemoteCheckpoint.Synced) {
        // Never delete the local cursor simply because the VPS is empty.
        return RewatchRemoteApplyDecision.NoChange
    }
    val remote = remoteLookup.remoteSession
        ?: return RewatchRemoteApplyDecision.PreserveLocalConflict
    if (!matchesOwner(remote) || remote.sessionId != checkpoint.sessionId) {
        return RewatchRemoteApplyDecision.PreserveLocalConflict
    }
    if (checkpoint.revision < lastAppliedRevision) {
        return RewatchRemoteApplyDecision.PreserveLocalConflict
    }
    if (checkpoint.revision == lastAppliedRevision) {
        return if (local != null && sameReplayCursor(local, remote))
            RewatchRemoteApplyDecision.NoChange
        else RewatchRemoteApplyDecision.PreserveLocalConflict
    }

    // A fresh install has no local replay and can adopt the remote session,
    // as long as the caller has positively established no unqueued edits.
    if (local == null) {
        return if (lastKnownCleanLocalSession == null)
            RewatchRemoteApplyDecision.ApplyRemote(remote, checkpoint.revision)
        else RewatchRemoteApplyDecision.PreserveLocalConflict
    }

    // Never infer "clean" from an empty outbox. This proof must come from
    // a durable baseline captured after a prior successful sync.
    if (lastKnownCleanLocalSession == null ||
        !sameReplayCursor(local, lastKnownCleanLocalSession)) {
        return RewatchRemoteApplyDecision.PreserveLocalConflict
    }
    return RewatchRemoteApplyDecision.ApplyRemote(remote, checkpoint.revision)
}
