package com.nuvio.tv.ui.screens.player

import android.util.Log
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun PlayerRuntimeController.evaluateCreditRatingPrompt(positionMs: Long) {
    val state = _uiState.value
    if (!shouldShowCreditRatingPrompt(state, positionMs)) return

    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    Log.i(
        PlayerRuntimeController.TAG,
        "Showing rating overlay at final-credit boundary positionMs=$positionMs"
    )
    _uiState.update {
        it.copy(
            showRatingOverlay = true,
            showControls = false,
            showPauseOverlay = false,
            showPlayerBlackout = false,
            ratingSubmitted = false,
            pendingRating = null,
            ratingOverlayDestination = RatingOverlayDestination.POST_PLAY,
            isPostPlayRecommendationVisible = false,
            showNextEpisodeCard = false,
            nextEpisodeAutoPlaySearching = false,
            nextEpisodeAutoPlaySourceName = null,
            nextEpisodeAutoPlayCountdownSec = null
        )
    }
}

internal fun PlayerRuntimeController.evaluateNaturalEndRatingPrompt() {
    val state = _uiState.value
    if (!shouldReserveNaturalEndRatingPrompt(state)) {
        if (state.naturalEndRatingCheckPending) {
            _uiState.update {
                it.copy(naturalEndRatingCheckPending = false)
            }
        }
        return
    }

    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    creditAnalysisJob?.cancel()
    creditAnalysisJob = null

    val cachedAvailability = state.isRatingProviderConnected
    scope.launch {
        val available = runCatching {
            trackingRatingCoordinator.isAvailable()
        }.getOrDefault(cachedAvailability)

        val current = _uiState.value
        val stillReserved =
            current.naturalEndRatingCheckPending &&
                shouldReserveNaturalEndRatingPrompt(current)

        Log.i(
            PlayerRuntimeController.TAG,
            "Terminal EOF rating check: " +
                "available=$available, " +
                "cachedAvailability=$cachedAvailability, " +
                "stillReserved=$stillReserved, " +
                "contentType=${current.contentType}, " +
                "handled=${current.creditRatingPromptHandled}, " +
                "timingStatus=${current.creditTiming.status}, " +
                "finalCreditsStartMs=${current.creditTiming.finalCreditsStartMs}, " +
                "postCreditScenes=${current.creditTiming.postCreditScenes.size}"
        )

        if (!stillReserved) {
            _uiState.update {
                it.copy(
                    isRatingProviderConnected = available,
                    naturalEndRatingCheckPending = false
                )
            }
            return@launch
        }

        if (available) {
            _uiState.update { latest ->
                if (!shouldReserveNaturalEndRatingPrompt(latest)) {
                    latest.copy(naturalEndRatingCheckPending = false)
                } else {
                    latest.copy(
                        isRatingProviderConnected = true,
                        naturalEndRatingCheckPending = false,
                        manualEndActionRequested = true,
                        showRatingOverlay = true,
                        showControls = false,
                        showPauseOverlay = false,
                        showPlayerBlackout = false,
                        ratingSubmitted = false,
                        pendingRating = null,
                        ratingOverlayDestination = RatingOverlayDestination.POST_PLAY,
                        creditRatingPromptHandled = false,
                        isPostPlayRecommendationVisible = false,
                        showNextEpisodeCard = false,
                        nextEpisodeAutoPlaySearching = false,
                        nextEpisodeAutoPlaySourceName = null,
                        nextEpisodeAutoPlayCountdownSec = null
                    )
                }
            }

            if (_uiState.value.showRatingOverlay) {
                Log.i(
                    PlayerRuntimeController.TAG,
                    "Showing rating overlay at terminal playback EOF"
                )
                preparePostPlayRecommendationsForManualEndAction()
            }
            return@launch
        }

        /*
         * No selected/connected rating provider: preserve the normal EOF ->
         * More Like This flow. Keep the pending reservation until post-play is
         * visible or loading so natural completion cannot slip through between
         * these state updates.
         */
        _uiState.update {
            it.copy(
                isRatingProviderConnected = false,
                creditRatingPromptHandled = true,
                manualEndActionRequested = true
            )
        }
        preparePostPlayRecommendationsForManualEndAction()
        _uiState.update {
            it.copy(naturalEndRatingCheckPending = false)
        }
        schedulePostPlayTrailerAfterPlaybackEnded()
    }
}

internal fun PlayerRuntimeController.requestManualEndAction() {
    val state = _uiState.value
    if (!shouldStartManualEndAction(
            state = state,
            positionMs = state.currentPosition,
            durationMs = state.duration
        )
    ) {
        return
    }

    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    val ratingEligible = isRatingPromptEligibleContent(
        contentType = state.contentType,
        currentSeason = state.currentSeason,
        currentEpisode = state.currentEpisode,
        episodes = state.episodesAll
    )
    val showRating =
        !state.creditRatingPromptHandled &&
            state.isRatingProviderConnected &&
            ratingEligible
    Log.i(
        PlayerRuntimeController.TAG,
        "Manual near-end exit requested post-play positionMs=${state.currentPosition}"
    )
    _uiState.update {
        it.copy(
            manualEndActionRequested = true,
            showRatingOverlay = showRating,
            showControls = false,
            showPauseOverlay = false,
            showPlayerBlackout = false,
            ratingSubmitted = false,
            pendingRating = null,
            ratingOverlayDestination =
                if (showRating) RatingOverlayDestination.POST_PLAY else null,
            creditRatingPromptHandled = !showRating,
            isPostPlayRecommendationVisible = false,
            showNextEpisodeCard = false,
            nextEpisodeAutoPlaySearching = false,
            nextEpisodeAutoPlaySourceName = null,
            nextEpisodeAutoPlayCountdownSec = null
        )
    }
    // A manual Back end action is rating/post-play only. Next-episode
    // autoplay is driven by normal playback timing, never by the Back button.
    preparePostPlayRecommendationsForManualEndAction()
}

/**
 * Ends playback after the Skip Credits countdown expires.
 *
 * Unlike requestManualEndAction(), this does not use the generic 85% runtime
 * safety gate because the analyzer has already authoritatively established
 * that playback is in a credits block immediately before a post-credit scene.
 */
internal fun PlayerRuntimeController.requestCreditSkipTimeoutEndAction() {
    val state = _uiState.value

    // Only an exact, currently-active analyzer Skip Credits opportunity is
    // allowed to enter through this path.
    val skipTarget = postCreditSkipTarget(
        timing = state.creditTiming,
        positionMs = state.currentPosition,
        skippedSceneStarts = state.skippedPostCreditSceneStarts
    ) ?: return

    if (
        state.creditRatingPromptHandled ||
        state.postPlayRecommendationDismissed ||
        state.showRatingOverlay ||
        state.ratingOverlayDestination != null
    ) {
        return
    }

    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null

    val ratingEligible = isRatingPromptEligibleContent(
        contentType = state.contentType,
        currentSeason = state.currentSeason,
        currentEpisode = state.currentEpisode,
        episodes = state.episodesAll
    )
    val showRating =
        !state.creditRatingPromptHandled &&
            state.isRatingProviderConnected &&
            ratingEligible

    Log.i(
        PlayerRuntimeController.TAG,
        "Skip Credits countdown expired; starting normal end flow " +
            "positionMs=${state.currentPosition}"
    )

    _uiState.update {
        it.copy(
            skippedPostCreditSceneStarts =
                it.skippedPostCreditSceneStarts + skipTarget.startMs,
            manualEndActionRequested = true,
            showRatingOverlay = showRating,
            showControls = false,
            showPauseOverlay = false,
            showPlayerBlackout = false,
            ratingSubmitted = false,
            pendingRating = null,
            ratingOverlayDestination =
                if (showRating) RatingOverlayDestination.POST_PLAY else null,
            creditRatingPromptHandled = !showRating,
            isPostPlayRecommendationVisible = false,
            showNextEpisodeCard = false,
            nextEpisodeAutoPlaySearching = false,
            nextEpisodeAutoPlaySourceName = null,
            nextEpisodeAutoPlayCountdownSec = null
        )
    }

    if (showRating || state.nextEpisode?.hasAired != true) {
        preparePostPlayRecommendationsForManualEndAction()
    } else {
        playNextEpisode(userInitiated = false)
    }
}
