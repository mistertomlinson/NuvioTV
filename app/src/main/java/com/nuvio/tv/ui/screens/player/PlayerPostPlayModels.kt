package com.nuvio.tv.ui.screens.player

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.MDBListRatings
import com.nuvio.tv.domain.model.Video

internal const val CREDIT_ANALYZER_TRIGGER_POSITION_MS = 5 * 60_000L
internal const val MANUAL_END_ACTION_THRESHOLD = 0.85
internal const val NEXT_EPISODE_CREDIT_LEAD_MS = 3_000L

enum class CreditTimingStatus {
    NOT_STARTED,
    RUNNING,
    INTRO_DB_AVAILABLE,
    COMPLETE,
    FALLBACK
}

enum class RatingOverlayDestination {
    EXIT_PLAYER,
    POST_PLAY
}

@Immutable
data class CreditTimingUiState(
    val status: CreditTimingStatus = CreditTimingStatus.NOT_STARTED,
    val creditsStartMs: Long? = null,
    val finalCreditsStartMs: Long? = null,
    val hasPostCreditScenes: Boolean = false,
    val confidence: Double? = null
) {
    val isAuthoritative: Boolean
        get() = status == CreditTimingStatus.COMPLETE && finalCreditsStartMs != null
}

@Immutable
data class PostPlayRecommendation(
    val id: String,
    val contentType: String,
    val title: String,
    val backdrop: String?,
    val poster: String?,
    val logo: String? = null,
    val description: String?,
    val releaseInfo: String?,
    val genres: List<String>,
    val runtime: String? = null,
    val imdbRating: Float? = null,
    val tmdbRating: Float? = null,
    val mdbListRatings: MDBListRatings? = null,
    val playbackVideoId: String? = null,
    val trailerVideoUrl: String? = null,
    val trailerAudioUrl: String? = null,
    val metadataResolved: Boolean = false
) {
    val hasTrailer: Boolean
        get() = !trailerVideoUrl.isNullOrBlank()
}

internal fun shouldShowPostPlayTrailerAction(
    recommendation: PostPlayRecommendation,
    isTrailerPlaying: Boolean
): Boolean = recommendation.hasTrailer && !isTrailerPlaying

internal fun PostPlayRecommendation.isPresentationReady(): Boolean =
    metadataResolved &&
        !logo.isNullOrBlank()

/**
 * Returns null when legacy IntroDB/percentage timing should decide. A running
 * analyzer uses a cross-release estimate when one is available and otherwise
 * returns false so fallback UI cannot appear prematurely.
 */
internal fun authoritativeEndActionDecision(
    timing: CreditTimingUiState,
    positionMs: Long,
    leadTimeMs: Long = 0L
): Boolean? {
    if (timing.status == CreditTimingStatus.RUNNING && timing.finalCreditsStartMs == null) {
        return false
    }
    return timing.finalCreditsStartMs?.let { finalCreditsStartMs ->
        val triggerPositionMs =
            (finalCreditsStartMs - leadTimeMs.coerceAtLeast(0L))
                .coerceAtLeast(0L)
        positionMs >= triggerPositionMs
    }
}

internal fun isRatingPromptEligibleContent(
    contentType: String?,
    currentSeason: Int?,
    currentEpisode: Int?,
    episodes: List<Video>
): Boolean {
    return when (contentType?.trim()?.lowercase()) {
        "movie" -> true
        "series", "tv" -> {
            if (currentSeason == null || currentEpisode == null || episodes.isEmpty()) {
                false
            } else {
                // A series is rateable only after the final episode of the highest
                // season that has actually started airing. A wholly future season
                // must not block the prompt, while future episodes in the current
                // season keep that season from being treated as complete.
                val highestAiredSeason = episodes
                    .asSequence()
                    .filter { PlayerNextEpisodeRules.hasEpisodeAired(it.released) }
                    .mapNotNull { it.season }
                    .filter { it > 0 }
                    .maxOrNull()

                val finalEpisodeInCurrentSeason = episodes
                    .asSequence()
                    .filter { it.season == currentSeason }
                    .mapNotNull { it.episode }
                    .maxOrNull()

                highestAiredSeason != null &&
                    currentSeason == highestAiredSeason &&
                    finalEpisodeInCurrentSeason != null &&
                    currentEpisode >= finalEpisodeInCurrentSeason
            }
        }
        else -> false
    }
}

internal fun shouldShowCreditRatingPrompt(
    state: PlayerUiState,
    positionMs: Long
): Boolean {
    if (!state.isRatingProviderConnected || state.creditRatingPromptHandled) return false
    if (state.ratingOverlayDestination != null || state.showRatingOverlay) return false
    if (!isRatingPromptEligibleContent(
            contentType = state.contentType,
            currentSeason = state.currentSeason,
            currentEpisode = state.currentEpisode,
            episodes = state.episodesAll
        )
    ) {
        return false
    }
    val finalCreditsStartMs = state.creditTiming.finalCreditsStartMs ?: return false
    return positionMs >= finalCreditsStartMs
}

internal fun shouldStartManualEndAction(
    state: PlayerUiState,
    positionMs: Long,
    durationMs: Long
): Boolean {
    if (durationMs <= 0L || positionMs < 0L) return false
    if (state.creditRatingPromptHandled || state.postPlayRecommendationDismissed) return false
    if (positionMs.toDouble() / durationMs.toDouble() < MANUAL_END_ACTION_THRESHOLD) return false

    return when (state.contentType?.trim()?.lowercase()) {
        "movie" -> true
        "series", "tv" -> {
            val ratingEligible = isRatingPromptEligibleContent(
                contentType = state.contentType,
                currentSeason = state.currentSeason,
                currentEpisode = state.currentEpisode,
                episodes = state.episodesAll
            )
            val analyzedCreditsStart = state.creditTiming.finalCreditsStartMs
            val analyzerLooksLate =
                state.nextEpisode?.hasAired == true &&
                    state.creditTiming.status == CreditTimingStatus.COMPLETE &&
                    analyzedCreditsStart != null &&
                    positionMs < analyzedCreditsStart

            ratingEligible || analyzerLooksLate
        }
        else -> false
    }
}
