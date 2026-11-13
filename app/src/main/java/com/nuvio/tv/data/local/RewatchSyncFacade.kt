package com.nuvio.tv.data.local

import com.nuvio.tv.domain.rewatch.RewatchCommittedSaveSink

/**
 * Public DI-facing boundary for an opt-in replay-only synchronization runtime.
 * The implementation, profile authentication, networking, and storage remain
 * internal and are never acquired when the rollout flag is off.
 */
interface RewatchSyncFacade {
    val committedSaveSink: RewatchCommittedSaveSink
    suspend fun reconcileForPresentation(seriesId: String? = null)
}
