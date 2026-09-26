package com.nuvio.tv.ui.screens.player

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.MDBListRatings
import com.nuvio.tv.domain.model.Video

internal const val CREDIT_ANALYZER_TRIGGER_POSITION_MS = 5 * 60_000L
internal const val MANUAL_END_ACTION_THRESHOLD = 0.85
internal const val POST_CREDIT_SCENE_CONTINUITY_GAP_MS = 10_000L

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
data class PostCreditSceneTiming(
    val startMs: Long,
    val endMs: Long
)

@Immutable
data class CreditTimingUiState(
    val status: CreditTimingStatus = CreditTimingStatus.NOT_STARTED,
    val creditsStartMs: Long? = null,
    val finalCreditsStartMs: Long? = null,
    val hasPostCreditScenes: Boolean = false,
    val postCreditScenes: List<PostCreditSceneTiming> = emptyList(),
    val confidence: Double? = null
) {
    val isAuthoritative: Boolean
        get() = status == CreditTimingStatus.COMPLETE && finalCreditsStartMs != null
}


/**
 * Returns the next post-credit scene that should be offered as a Skip Credits
 * target at the current playback position.
 *
 * The first post-credit scene always gets a skip opportunity once credits
 * begin. Later scenes are treated as part of the same continuous post-credit
 * cluster when they begin within 10 seconds of the previous scene ending.
 */
internal fun postCreditSkipTarget(
    timing: CreditTimingUiState,
    positionMs: Long,
    skippedSceneStarts: Set<Long> = emptySet(),
    continuityGapMs: Long = POST_CREDIT_SCENE_CONTINUITY_GAP_MS
): PostCreditSceneTiming? {
    if (positionMs < 0L) return null

    val hasUsableSceneTiming =
        timing.status == CreditTimingStatus.COMPLETE ||
            ((timing.status == CreditTimingStatus.RUNNING ||
                timing.status == CreditTimingStatus.FALLBACK) &&
                timing.postCreditScenes.isNotEmpty())

    if (!hasUsableSceneTiming) return null

    // Analyzer scenes are validated and sorted once when published to UI state.
    // Keep this playback-position path allocation- and sort-free.
    val scenes = timing.postCreditScenes
    if (scenes.isEmpty()) return null

    scenes.forEachIndexed { index, scene ->
        if (scene.startMs in skippedSceneStarts) {
            return@forEachIndexed
        }

        if (positionMs >= scene.startMs && positionMs < scene.endMs) {
            return null
        }

        if (positionMs >= scene.startMs) {
            return@forEachIndexed
        }

        if (index == 0) {
            val creditsStartMs = timing.creditsStartMs ?: return null
            return scene.takeIf { positionMs >= creditsStartMs }
        }

        val previousScene = scenes[index - 1]
        if (positionMs < previousScene.endMs) return null

        val gapMs = scene.startMs - previousScene.endMs
        return scene.takeIf {
            gapMs > continuityGapMs.coerceAtLeast(0L)
        }
    }

    return null
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
    if (state.postPlayRecommendationDismissed) return false

    val progressFraction = positionMs.toDouble() / durationMs.toDouble()

    return when (state.contentType?.trim()?.lowercase()) {
        "movie" -> {
            // Prefer real timing whenever Nuvio has it. This includes exact
            // analyzer results, cross-release fallback timing while a fresh
            // analysis is running, and any other validated movie timing.
            //
            // For a manual Back action, reaching the beginning of credits is
            // enough to consider the movie complete. The automatic rating
            // trigger remains tied to the final-credit boundary so viewers
            // who keep watching can still see post-credit scenes normally.
            val knownCreditsStartMs =
                state.creditTiming.creditsStartMs
                    ?: state.creditTiming.postCreditScenes.firstOrNull()?.startMs
                    ?: state.creditTiming.finalCreditsStartMs

            if (knownCreditsStartMs != null) {
                positionMs >= knownCreditsStartMs
            } else {
                // Last-resort legacy behavior when neither analyzer/cached
                // timing nor another credit source supplied a usable boundary.
                progressFraction >= MANUAL_END_ACTION_THRESHOLD
            }
        }

        "series", "tv" -> {
            if (progressFraction < MANUAL_END_ACTION_THRESHOLD) {
                return false
            }

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
