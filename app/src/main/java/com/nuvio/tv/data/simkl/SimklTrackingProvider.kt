package com.nuvio.tv.data.simkl

import android.util.Log
import com.nuvio.tv.core.tracking.TRACKING_SCROBBLE_DIAGNOSTIC_TAG
import com.nuvio.tv.core.tracking.TrackingCapability
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingListStatus
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingProvider
import com.nuvio.tv.core.tracking.TrackingProviderDescriptor
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.core.tracking.TrackingScrobbler
import com.nuvio.tv.core.tracking.scrobbleDiagnosticSummary
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Singleton
class SimklTrackingScrobbler @Inject constructor(
    private val authRepository: SimklAuthRepository,
    private val syncRepository: SimklSyncRepository,
    private val mutationService: SimklMutationService
) : TrackingScrobbler {
    override val providerId = TrackingProviderId.SIMKL

    override suspend fun scrobble(
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent
    ) {
        val authenticated = authRepository.state.value.isAuthenticated
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "simkl adapter received action=${action.wireValue} authenticated=$authenticated " +
                event.scrobbleDiagnosticSummary()
        )
        if (!authenticated) {
            Log.d(
                TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                "simkl adapter skipped action=${action.wireValue} reason=not_authenticated"
            )
            return
        }
        syncRepository.ensureLoaded()
        val enrichedEvent = event.copy(
            media = syncRepository.state.value.snapshot
                .enrichMediaReference(event.media)
                .resolveAnimeEpisodeForSimkl()
        )
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "simkl adapter enriched action=${action.wireValue} ${enrichedEvent.scrobbleDiagnosticSummary()}"
        )
        val result = mutationService.scrobble(
            action = action,
            event = enrichedEvent
        )
        if (result.requiresHistoryRecovery) {
            Log.w(
                TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                "simkl completion conflict; repairing with history write " +
                    enrichedEvent.scrobbleDiagnosticSummary()
            )
        }
        val recovered = commitSimklScrobbleResult(
            result = result,
            media = enrichedEvent.media,
            mutationService = mutationService,
            syncRepository = syncRepository,
            watchedAtEpochMs = System.currentTimeMillis()
        )
        if (recovered) {
            Log.d(
                TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                "simkl completion conflict repaired " +
                    enrichedEvent.scrobbleDiagnosticSummary()
            )
        }

        if (action == TrackingScrobbleAction.START) {
            val startedPlanToWatchEntry = syncRepository.state.value.snapshot.entries
                .firstOrNull { entry ->
                    entry.status == SimklListStatus.PLAN_TO_WATCH &&
                        entry.media?.matchesTarget(result.media) == true
                }

            when {
                startedPlanToWatchEntry?.mediaType == SimklMediaType.MOVIES &&
                    startedPlanToWatchEntry.destructiveRemovalImpacts().isEmpty() -> {
                    val removeResult = mutationService.removeFromList(
                        items = listOf(enrichedEvent.media)
                    )
                    check(removeResult.isComplete) {
                        "Simkl could not remove the started movie from Plan to Watch"
                    }
                }

                startedPlanToWatchEntry != null &&
                    startedPlanToWatchEntry.mediaType != SimklMediaType.MOVIES -> {
                    val moveResult = mutationService.moveToList(
                        items = listOf(enrichedEvent.media),
                        destination = TrackingListStatus.WATCHING
                    )
                    check(moveResult.isComplete) {
                        "Simkl could not move the started title from Plan to Watch to Watching"
                    }
                }
            }
        }
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "simkl adapter complete action=${action.wireValue} ${enrichedEvent.scrobbleDiagnosticSummary()}"
        )
    }
}

internal suspend fun commitSimklScrobbleResult(
    result: SimklScrobbleResult,
    media: TrackingMediaReference,
    mutationService: SimklMutationService,
    syncRepository: SimklSyncRepository,
    watchedAtEpochMs: Long
): Boolean {
    if (!result.requiresHistoryRecovery) {
        syncRepository.commitScrobble(result)
        if (result.outcome == SimklScrobbleOutcome.SCROBBLE) {
            syncRepository.refresh(TrackingRefreshIntent.INVALIDATED)
        }
        return false
    }
    val recovery = mutationService.addToHistory(
        items = listOf(
            TrackingHistoryItem(
                media = media,
                watchedAtEpochMs = watchedAtEpochMs
            )
        )
    )
    check(recovery.isComplete) {
        "Simkl could not recover completed playback through history"
    }
    syncRepository.commitScrobble(result)
    syncRepository.refresh(TrackingRefreshIntent.INVALIDATED)
    return true
}

@Singleton
class SimklTrackingProvider @Inject constructor(
    authRepository: SimklAuthRepository,
    override val scrobbler: SimklTrackingScrobbler
) : TrackingProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val descriptor = TrackingProviderDescriptor(
        id = TrackingProviderId.SIMKL,
        displayName = "Simkl",
        capabilities = setOf(
            TrackingCapability.AUTHENTICATION,
            TrackingCapability.LIBRARY_READ,
            TrackingCapability.LIBRARY_WRITE,
            TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE,
            TrackingCapability.SCROBBLE
        )
    )
    override val isAuthenticated = authRepository.state
        .map { state -> state.isAuthenticated }
        .stateIn(scope, SharingStarted.Eagerly, authRepository.state.value.isAuthenticated)
}
