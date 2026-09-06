package com.nuvio.tv.ui.screens.player

import android.util.Log
import kotlinx.coroutines.flow.update

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
    val showRating = state.isRatingProviderConnected
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
    preparePostPlayRecommendationsForManualEndAction()
}
