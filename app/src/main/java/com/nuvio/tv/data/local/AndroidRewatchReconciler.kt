package com.nuvio.tv.data.local

import com.nuvio.tv.domain.rewatch.ReplayBootstrapOutcome
import com.nuvio.tv.domain.rewatch.RewatchCommitIdentity
import com.nuvio.tv.domain.rewatch.RewatchRemoteCheckpoint
import com.nuvio.tv.domain.rewatch.RewatchRemoteLookup
import com.nuvio.tv.domain.rewatch.RewatchSession
import com.nuvio.tv.domain.rewatch.RewatchSyncOutbox
import com.nuvio.tv.domain.rewatch.RewatchTransportCoordinator
import com.nuvio.tv.domain.rewatch.RewatchPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A transport-agnostic seam allowing exact coordinator regression tests. */
internal interface RewatchAtomicLocalAccess {
    suspend fun observe(profileId: Int): RewatchObservedLocalSync
    suspend fun certify(
        identity: RewatchCommitIdentity,
        expectedLocal: RewatchSession,
        expectedGeneration: Long,
        remote: RewatchRemoteLookup,
        pending: Boolean,
        stillCurrent: () -> Boolean
    ): Boolean
    suspend fun apply(
        identity: RewatchCommitIdentity,
        canonicalSeriesId: String,
        observed: RewatchObservedLocalSync,
        remote: RewatchRemoteLookup,
        pending: Boolean,
        nowEpochMs: Long,
        stillCurrent: () -> Boolean
    ): Boolean
}

internal class RewatchAtomicDataStoreAccess(
    private val store: RewatchSessionStore
) : RewatchAtomicLocalAccess {
    override suspend fun observe(profileId: Int) =
        store.observeLocalReplaySync(profileId)

    override suspend fun certify(
        identity: RewatchCommitIdentity,
        expectedLocal: RewatchSession,
        expectedGeneration: Long,
        remote: RewatchRemoteLookup,
        pending: Boolean,
        stillCurrent: () -> Boolean
    ) = store.certifyAuthenticatedRemoteMatch(
        expectedProfileId = identity.profileId,
        simklAccountId = identity.simklAccountId,
        deviceId = identity.deviceId,
        expectedLocal = expectedLocal,
        expectedGeneration = expectedGeneration,
        remoteLookup = remote,
        pendingOrUnpublished = pending,
        stillAuthenticated = stillCurrent
    )

    override suspend fun apply(
        identity: RewatchCommitIdentity,
        canonicalSeriesId: String,
        observed: RewatchObservedLocalSync,
        remote: RewatchRemoteLookup,
        pending: Boolean,
        nowEpochMs: Long,
        stillCurrent: () -> Boolean
    ) = store.applyAuthenticatedRemoteIfClean(
        expectedProfileId = identity.profileId,
        simklAccountId = identity.simklAccountId,
        deviceId = identity.deviceId,
        canonicalSeriesId = canonicalSeriesId,
        observed = observed,
        remoteLookup = remote,
        pendingOrUnpublished = pending,
        appliedAtEpochMs = nowEpochMs,
        stillAuthenticated = stillCurrent
    )
}

internal enum class RewatchPullResult {
    NO_EXISTING_SIMKL_LOGIN,
    NO_VERIFIED_REMOTE,
    REMOTE_EMPTY,
    LOCAL_PENDING,
    LOCAL_CONFLICT,
    PROFILE_CHANGED,
    CLEAN_VERIFIED,
    REMOTE_APPLIED,
    REMOTE_APPLIED_REVISION_REFRESH_PENDING
}

/**
 * Exact sequence for one title: capture authenticated identity + local
 * mutation generation, fetch validated VPS state, block pending offline
 * events, compare-and-swap the local replay (or certify it unchanged), then
 * advance the persisted remote revision from that same authenticated GET.
 *
 * The outbox and replay DataStore use different files, so a single cross-file
 * transaction is impossible. The replay DataStore's mutation generation is
 * the authoritative guard against local saves racing the network. A failed
 * outbox checkpoint refresh does NOT roll back an already applied local
 * replay; retrying a future authenticated GET repairs the checkpoint.
 *
 * This class is deliberately not registered in player/Home startup yet.
 */
internal class AndroidRewatchReconciler(
    private val ensureIdentity: suspend () -> RewatchCommitIdentity?,
    private val currentIdentity: () -> RewatchCommitIdentity?,
    private val fetchSeries: suspend (String) -> RewatchRemoteLookup,
    private val local: RewatchAtomicLocalAccess,
    private val outbox: RewatchSyncOutbox,
    private val nowEpochMs: () -> Long
) {
    private val mutex = Mutex()

    suspend fun pullSeries(canonicalSeriesId: String): RewatchPullResult = mutex.withLock {
        if (canonicalSeriesId.isBlank()) return@withLock RewatchPullResult.NO_VERIFIED_REMOTE
        val identity = ensureIdentity()
            ?: return@withLock RewatchPullResult.NO_EXISTING_SIMKL_LOGIN
        fun unchanged() = currentIdentity() == identity
        if (!unchanged()) return@withLock RewatchPullResult.PROFILE_CHANGED
        var stage = "LOCAL_OBSERVE"
        try {
            // Observe BEFORE the request so any intervening local playback
            // save changes the atomic generation and invalidates this read.
            val observed = local.observe(identity.profileId)
            android.util.Log.i("RewatchVpsTest",
                "pull_stage=LOCAL_OBSERVED count=" + observed.sessions.size)
            if (!unchanged()) return@withLock RewatchPullResult.PROFILE_CHANGED
            stage = "REMOTE_FETCH"
            val lookup = fetchSeries(canonicalSeriesId)
            if (!unchanged()) return@withLock RewatchPullResult.PROFILE_CHANGED
            android.util.Log.i("RewatchVpsTest",
                "pull_stage=REMOTE_FETCHED verified=" +
                    (lookup is RewatchRemoteLookup.Verified))
            val verified = lookup as? RewatchRemoteLookup.Verified
                ?: return@withLock RewatchPullResult.NO_VERIFIED_REMOTE
            if (verified.checkpoint == RewatchRemoteCheckpoint.ConfirmedEmpty) {
                return@withLock RewatchPullResult.REMOTE_EMPTY
            }
            if (verified.checkpoint !is RewatchRemoteCheckpoint.Synced ||
                verified.remoteSession == null) {
                return@withLock RewatchPullResult.NO_VERIFIED_REMOTE
            }
            stage = "OUTBOX_CHECK"
            val pending = outbox.hasPendingForSeries(
                identity.profileId, identity.simklAccountId,
                identity.deviceId, canonicalSeriesId
            )
            if (!unchanged()) return@withLock RewatchPullResult.PROFILE_CHANGED
            if (pending) return@withLock RewatchPullResult.LOCAL_PENDING

            val matching = observed.sessions.filter {
                it.canonicalSeriesId.trim().equals(
                    canonicalSeriesId.trim(), ignoreCase = true
                )
            }
            if (matching.size > 1) return@withLock RewatchPullResult.LOCAL_CONFLICT
            val current = matching.singleOrNull()
            val remote = verified.remoteSession
            if (current != null && sameRewatchCursor(current, remote)) {
                val clean = local.certify(
                    identity, current, observed.generation,
                    verified, pending = false, stillCurrent = ::unchanged
                )
                if (!clean) return@withLock RewatchPullResult.LOCAL_CONFLICT
                // Proven remote match; a fresh matching GET can also repair
                // an acknowledgment lost between a previous apply and crash.
                if (!unchanged()) return@withLock RewatchPullResult.PROFILE_CHANGED
                val recorded = outbox.recordVerifiedCleanBaseline(
                    identity.profileId, identity.simklAccountId,
                    identity.deviceId, current, verified
                )
                return@withLock if (recorded)
                    RewatchPullResult.CLEAN_VERIFIED
                else RewatchPullResult.LOCAL_CONFLICT
            }

            stage = "APPLY_REMOTE"
            val applied = local.apply(
                identity, canonicalSeriesId, observed, verified,
                pending = false, nowEpochMs = nowEpochMs(),
                stillCurrent = ::unchanged
            )
            if (!applied) return@withLock RewatchPullResult.LOCAL_CONFLICT
            if (!unchanged()) return@withLock RewatchPullResult.REMOTE_APPLIED_REVISION_REFRESH_PENDING

            // A concurrent player write after application changes generation
            // and invalidates the local clean proof. If the visible replay is
            // already different, do not certify stale state in the outbox.
            val refreshed = local.observe(identity.profileId)
            val appliedSession = refreshed.sessions.singleOrNull {
                it.canonicalSeriesId.trim().equals(
                    canonicalSeriesId.trim(), ignoreCase = true
                )
            }
            if (appliedSession == null ||
                !sameRewatchCursor(appliedSession, remote) ||
                !unchanged()) {
                return@withLock RewatchPullResult.REMOTE_APPLIED_REVISION_REFRESH_PENDING
            }
            val recorded = outbox.recordVerifiedCleanBaseline(
                identity.profileId, identity.simklAccountId,
                identity.deviceId, appliedSession, verified
            )
            if (recorded) RewatchPullResult.REMOTE_APPLIED
            else RewatchPullResult.REMOTE_APPLIED_REVISION_REFRESH_PENDING
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Isolated test-branch diagnostics: stage and exception type only.
            // Never log raw messages, IDs, credentials, or replay payloads.
            android.util.Log.w("RewatchVpsTest",
                "pull_exception_stage=" + stage +
                    " type=" + error.javaClass.simpleName)
            // Do not reset watched history or erase a local replay when
            // the optional sync service or either DataStore is unavailable.
            RewatchPullResult.NO_VERIFIED_REMOTE
        }
    }
}

/** Build-only integration. No startup or recurring network task is registered. */
internal fun createUnregisteredAndroidRewatchReconciler(
    transport: RewatchTransportCoordinator,
    store: RewatchSessionStore,
    outbox: RewatchSyncOutbox,
    nowEpochMs: () -> Long
) = AndroidRewatchReconciler(
    ensureIdentity = {
        if (transport.ensureConnected() == ReplayBootstrapOutcome.CONNECTED)
            transport.currentVerifiedIdentity()
        else null
    },
    currentIdentity = { transport.currentVerifiedIdentity() },
    fetchSeries = { transport.fetchCheckpointForSeries(it) },
    local = RewatchAtomicDataStoreAccess(store),
    outbox = outbox,
    nowEpochMs = nowEpochMs
)
