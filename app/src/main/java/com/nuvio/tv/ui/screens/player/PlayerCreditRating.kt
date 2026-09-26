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
    if (showRating || state.nextEpisode?.hasAired != true) {
        preparePostPlayRecommendationsForManualEndAction()
    } else {
        playNextEpisode(userInitiated = false)
    }
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
