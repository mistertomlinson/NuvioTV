package com.nuvio.tv.data.local

import com.nuvio.tv.data.simkl.RewatchExistingSimklAuthorization
import com.nuvio.tv.data.simkl.defaultSimklApiConfiguration
import com.nuvio.tv.domain.rewatch.DisabledRewatchCommittedSaveSink
import com.nuvio.tv.domain.rewatch.ExistingSimklRewatchAuthorization
import com.nuvio.tv.domain.rewatch.ReplayBootstrapOutcome
import com.nuvio.tv.domain.rewatch.RewatchCommitIdentity
import com.nuvio.tv.domain.rewatch.RewatchCommittedSaveSink
import com.nuvio.tv.domain.rewatch.RewatchOutboxDrainResult
import com.nuvio.tv.domain.rewatch.RewatchRemoteCheckpoint
import com.nuvio.tv.domain.rewatch.RewatchRemoteLookup
import com.nuvio.tv.domain.rewatch.RewatchSyncOutbox
import com.nuvio.tv.domain.rewatch.RewatchTransportCoordinator
import com.nuvio.tv.domain.rewatch.RewatchVpsApi
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Explicit rollout barrier. Normal Nuvio builds remain on the exact
 * pre-sync player behavior until end-to-end Android/iPad tests and
 * fail-closed VPS deployment have been approved.
 */
internal object RewatchCrossDeviceGate {
    const val ENABLED = false
}

/**
 * All replay-only dependencies reuse the EXISTING Simkl login/client ID.
 * No PIN flow, second account, extra token persistence or Simkl watched
 * history write. The replay bearer stays in RAM; the non-secret device ID
 * can be recovered from a previously account-bound offline outbox.
 *
 * This singleton has no init work, coroutine, timer, HTTP request or
 * profile observer. Everything is demand-driven behind the disabled gate.
 */
@Singleton
internal class AndroidRewatchSyncRuntime @Inject constructor(
    private val existingSimkl: RewatchExistingSimklAuthorization,
    private val storage: AndroidRewatchOutboxStorage,
    private val local: RewatchSessionStore
) {
    private val stateLock = Any()
    private var boundAuthorization: ExistingSimklRewatchAuthorization? = null
    private var boundIdentity: RewatchCommitIdentity? = null
    private val verifiedCheckpoints = mutableMapOf<String, RewatchRemoteCheckpoint>()

    private fun seriesKey(value: String) = value.trim().lowercase()

    private fun currentBinding(): RewatchCommitIdentity? {
        val current = existingSimkl.snapshot() ?: return null
        return synchronized(stateLock) {
            val identity = boundIdentity ?: return@synchronized null
            if (boundAuthorization != current ||
                current.accountId?.toString() != identity.simklAccountId ||
                current.profileId != identity.profileId) null
            else identity
        }
    }

    private fun setBinding(
        auth: ExistingSimklRewatchAuthorization,
        identity: RewatchCommitIdentity
    ) {
        synchronized(stateLock) {
            if (existingSimkl.snapshot() != auth ||
                auth.accountId?.toString() != identity.simklAccountId ||
                auth.profileId != identity.profileId) return
            if (boundAuthorization != auth || boundIdentity != identity) {
                // A different account, profile or installation must never
                // inherit the previous account's remote slot cache.
                verifiedCheckpoints.clear()
            }
            boundAuthorization = auth
            boundIdentity = identity
        }
    }

    private val outbox by lazy {
        RewatchSyncOutbox(storage) { profileId, simklAccountId, deviceId ->
            val current = currentBinding()
            current?.profileId == profileId &&
                current.simklAccountId == simklAccountId &&
                current.deviceId == deviceId
        }
    }

    private val transport by lazy {
        RewatchTransportCoordinator(
            currentAuthorization = { existingSimkl.snapshot() },
            installationIdForProfile = { storage.installationId(it) },
            clientId = defaultSimklApiConfiguration().clientId,
            api = RewatchVpsApi(AndroidRewatchVpsHttpExchange),
            outbox = outbox,
            nowEpochMs = System::currentTimeMillis
        )
    }

    /** No HTTP. After restart, an existing account's old device ID may be
     * recovered while the VPS is offline; an unknown ID is never invented. */
    private suspend fun identityForLocalSave(): RewatchCommitIdentity? {
        val auth = existingSimkl.snapshot() ?: return null
        val accountId = auth.accountId?.takeIf { it > 0 }?.toString() ?: return null
        currentBinding()?.takeIf {
            it.profileId == auth.profileId && it.simklAccountId == accountId
        }?.let { return it }
        val verified = transport.currentVerifiedIdentity()?.takeIf {
            it.profileId == auth.profileId && it.simklAccountId == accountId
        }
        val identity = verified ?: run {
            val deviceId = outbox.knownDeviceIdForAccount(auth.profileId, accountId)
                ?: return null
            RewatchCommitIdentity(auth.profileId, accountId, deviceId)
        }
        if (existingSimkl.snapshot() != auth) return null
        setBinding(auth, identity)
        return currentBinding()
    }

    val committedSaveSink: RewatchCommittedSaveSink =
        RewatchCommittedSaveSink { profileId, before, after ->
            if (RewatchCrossDeviceGate.ENABLED) {
                val identity = identityForLocalSave() ?: return@RewatchCommittedSaveSink
                if (identity.profileId != profileId ||
                    after.ownerKey != "local-profile:$profileId") {
                    return@RewatchCommittedSaveSink
                }
                val checkpoint = synchronized(stateLock) {
                    verifiedCheckpoints[seriesKey(after.canonicalSeriesId)]
                        ?: RewatchRemoteCheckpoint.Unknown
                }
                if (currentBinding() != identity) return@RewatchCommittedSaveSink
                outbox.enqueueCommittedTransition(
                    profileId = profileId,
                    simklAccountId = identity.simklAccountId,
                    deviceId = identity.deviceId,
                    before = before,
                    after = after,
                    checkpoint = checkpoint,
                    eventId = UUID.randomUUID().toString()
                )
            }
        }

    private val reconciler by lazy {
        AndroidRewatchReconciler(
            ensureIdentity = {
                if (transport.ensureConnected() != ReplayBootstrapOutcome.CONNECTED) {
                    null
                } else {
                    val auth = existingSimkl.snapshot()
                    val identity = transport.currentVerifiedIdentity()
                    if (auth != null && identity != null) {
                        setBinding(auth, identity)
                    }
                    currentBinding()
                }
            },
            currentIdentity = { currentBinding() },
            fetchSeries = { series ->
                val identity = currentBinding()
                val lookup = transport.fetchCheckpointForSeries(series)
                if (identity != null && currentBinding() == identity &&
                    lookup is RewatchRemoteLookup.Verified) {
                    synchronized(stateLock) {
                        if (currentBinding() == identity) {
                            verifiedCheckpoints[seriesKey(series)] = lookup.checkpoint
                        }
                    }
                }
                lookup
            },
            local = RewatchAtomicDataStoreAccess(local),
            outbox = outbox,
            nowEpochMs = System::currentTimeMillis
        )
    }

    suspend fun pullSeries(canonicalSeriesId: String): RewatchPullResult {
        if (!RewatchCrossDeviceGate.ENABLED) return RewatchPullResult.NO_VERIFIED_REMOTE
        return reconciler.pullSeries(canonicalSeriesId)
    }

    suspend fun uploadPending(): RewatchOutboxDrainResult {
        if (!RewatchCrossDeviceGate.ENABLED) return RewatchOutboxDrainResult.UNAVAILABLE
        val auth = existingSimkl.snapshot() ?: return RewatchOutboxDrainResult.UNAVAILABLE
        val connected = transport.ensureConnected()
        if (connected != ReplayBootstrapOutcome.CONNECTED) return RewatchOutboxDrainResult.UNAVAILABLE
        val identity = transport.currentVerifiedIdentity()
            ?: return RewatchOutboxDrainResult.UNAVAILABLE
        setBinding(auth, identity)
        if (currentBinding() != identity) return RewatchOutboxDrainResult.PROFILE_CHANGED
        val touched = outbox.pendingSeriesIds(
            identity.profileId, identity.simklAccountId, identity.deviceId
        )
        val result = transport.submitPending()
        if (result == RewatchOutboxDrainResult.DRAINED &&
            currentBinding() == identity) {
            // An accepted POST does not itself prove that the local replay
            // has remained unchanged. Verify the server's final cursor
            // independently before certifying it clean.
            for (series in touched) {
                if (currentBinding() != identity) break
                try {
                    reconciler.pullSeries(series)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Keep the successfully uploaded acknowledgment. A
                    // future GET can repair the missing clean proof.
                }
            }
        }
        return result
    }
}
