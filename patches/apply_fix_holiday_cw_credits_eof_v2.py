#!/usr/bin/env python3
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE_PATCH = ROOT / "patches/apply_fix_holiday_cw_credits_eof.py"
CHANGED: list[Path] = []


def replace_exact(path: Path, old: str, new: str, *, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count == 0 and new in text:
        print(f"[already applied] {label}")
        return
    if count != 1:
        raise SystemExit(
            f"{label}: expected exactly one original fragment in {path}, found {count}"
        )
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    if path not in CHANGED:
        CHANGED.append(path)
    print(f"[patched] {label}")


def require(path: Path, needle: str, *, label: str) -> None:
    if needle not in path.read_text(encoding="utf-8"):
        raise SystemExit(f"{label}: validation marker missing from {path}")


def main() -> None:
    # Apply the reviewed four-fix base patch first. It is idempotent and aborts
    # rather than partially applying if the checked dev source has drifted.
    subprocess.run([sys.executable, str(BASE_PATCH)], cwd=ROOT, check=True)

    post_play = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerPostPlayModels.kt"
    credit_rating = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerCreditRating.kt"
    initialization = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt"
    ui_state = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerUiState.kt"
    post_play_test = ROOT / "app/src/test/java/com/nuvio/tv/ui/screens/player/PlayerPostPlayModelsTest.kt"

    replace_exact(
        post_play,
        r'''internal fun shouldShowCreditRatingPrompt(
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

internal fun shouldStartManualEndAction(''',
        r'''internal fun shouldShowCreditRatingPrompt(
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

/**
 * Terminal Media3 EOF is the final fallback rating boundary.
 *
 * Normal analyzer-driven rating timing still wins whenever it fires before
 * the last frame. This path exists for titles whose post-credit scene reaches
 * the physical end of the file, or whose analyzer never publishes a usable
 * final-credit timestamp before playback stops.
 */
internal fun shouldShowNaturalEndRatingPrompt(
    state: PlayerUiState
): Boolean {
    if (!state.playbackEnded || state.error != null) return false
    if (!state.isRatingProviderConnected || state.creditRatingPromptHandled) return false
    if (state.ratingOverlayDestination != null || state.showRatingOverlay) return false

    return isRatingPromptEligibleContent(
        contentType = state.contentType,
        currentSeason = state.currentSeason,
        currentEpisode = state.currentEpisode,
        episodes = state.episodesAll
    )
}

internal fun shouldStartManualEndAction(''',
        label="add terminal EOF rating fallback decision",
    )

    replace_exact(
        credit_rating,
        r'''internal fun PlayerRuntimeController.requestManualEndAction() {''',
        r'''internal fun PlayerRuntimeController.evaluateNaturalEndRatingPrompt() {
    val state = _uiState.value
    if (!shouldShowNaturalEndRatingPrompt(state)) return

    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    creditAnalysisJob?.cancel()
    creditAnalysisJob = null

    Log.i(
        PlayerRuntimeController.TAG,
        "Showing rating overlay at terminal playback EOF"
    )

    _uiState.update { current ->
        if (!shouldShowNaturalEndRatingPrompt(current)) {
            current
        } else {
            current.copy(
                // Reuse the established end-flow flag so post-play loading is
                // allowed even when the analyzer never produced final credits.
                manualEndActionRequested = true,
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

    /*
     * Preload More Like This behind the rating overlay. If recommendations are
     * already ready, blocksEndActionForRating prevents them from becoming
     * visible until the rating step completes. If they still need network
     * work, the EOF completion gate below keeps the ended player alive only
     * while that intentional post-play handoff is loading.
     */
    preparePostPlayRecommendationsForManualEndAction()
}

internal fun PlayerRuntimeController.requestManualEndAction() {''',
        label="show rating over ended player and preload post-play",
    )

    replace_exact(
        initialization,
        r'''                        if (playbackState == Player.STATE_ENDED) {
                            emitCompletionScrobbleStop(progressPercent = 99.5f)
                            saveWatchProgress()
                            resetNextEpisodeCardState(clearEpisode = false)
                            schedulePostPlayTrailerAfterPlaybackEnded()
                        }''',
        r'''                        if (playbackState == Player.STATE_ENDED) {
                            emitCompletionScrobbleStop(progressPercent = 99.5f)
                            saveWatchProgress()
                            resetNextEpisodeCardState(clearEpisode = false)

                            /*
                             * The progress loop stops at EOF, so this is the
                             * last authoritative chance to enter the rating
                             * flow when the analyzer never emitted final-credit
                             * timing. Keep the ended player as the rating
                             * backdrop instead of releasing it first.
                             */
                            evaluateNaturalEndRatingPrompt()
                            schedulePostPlayTrailerAfterPlaybackEnded()
                        }''',
        label="evaluate terminal rating fallback at Media3 EOF",
    )

    replace_exact(
        ui_state,
        r'''    val blocksNaturalCompletion: Boolean
        get() =
            /*
             * A true Media3 EOF is authoritative. Background analysis or
             * recommendation loading may continue for seconds, but neither is
             * a visible destination and neither may strand the user on the
             * player's black final frame. Visible post-play/rating UI still
             * owns the screen and therefore remains a legitimate blocker.
             */
            isPostPlayRecommendationVisible ||
                showRatingOverlay ||
                ratingOverlayDestination == RatingOverlayDestination.POST_PLAY ||
                (
                    !playbackEnded &&
                        (
                            isPostPlayRecommendationLoading ||
                                creditTiming.status == CreditTimingStatus.RUNNING
                            )
                    )''',
        r'''    val blocksNaturalCompletion: Boolean
        get() =
            /*
             * True EOF is authoritative, but it must not race past an eligible
             * terminal rating prompt or the intentional rating -> post-play
             * handoff. Generic analyzer/recommendation background work still
             * cannot strand an ended player.
             */
            isPostPlayRecommendationVisible ||
                showRatingOverlay ||
                ratingOverlayDestination == RatingOverlayDestination.POST_PLAY ||
                shouldShowNaturalEndRatingPrompt(this) ||
                (
                    playbackEnded &&
                        manualEndActionRequested &&
                        isPostPlayRecommendationLoading
                    ) ||
                (
                    !playbackEnded &&
                        (
                            isPostPlayRecommendationLoading ||
                                creditTiming.status == CreditTimingStatus.RUNNING
                            )
                    )''',
        label="hold EOF only for rating and intentional post-play handoff",
    )

    replace_exact(
        post_play_test,
        r'''    @Test
    fun `true eof ignores transient background completion blockers`() {''',
        r'''    @Test
    fun `terminal eof offers rating when analyzer never supplied final credits`() {
        val state = PlayerUiState(
            playbackEnded = true,
            contentType = "movie",
            isRatingProviderConnected = true,
            creditTiming = CreditTimingUiState(
                status = CreditTimingStatus.RUNNING,
                finalCreditsStartMs = null
            )
        )

        assertTrue(shouldShowNaturalEndRatingPrompt(state))
        assertTrue(state.blocksNaturalCompletion)
    }

    @Test
    fun `terminal eof does not repeat an already handled rating prompt`() {
        val state = PlayerUiState(
            playbackEnded = true,
            contentType = "movie",
            isRatingProviderConnected = true,
            creditRatingPromptHandled = true
        )

        assertFalse(shouldShowNaturalEndRatingPrompt(state))
    }

    @Test
    fun `terminal eof waits only for intentional post play preload after rating`() {
        val state = PlayerUiState(
            playbackEnded = true,
            contentType = "movie",
            isRatingProviderConnected = true,
            creditRatingPromptHandled = true,
            manualEndActionRequested = true,
            isPostPlayRecommendationLoading = true
        )

        assertTrue(state.blocksNaturalCompletion)
    }

    @Test
    fun `terminal eof releases when intentional post play preload is no longer active`() {
        val state = PlayerUiState(
            playbackEnded = true,
            contentType = "movie",
            isRatingProviderConnected = true,
            creditRatingPromptHandled = true,
            manualEndActionRequested = true,
            isPostPlayRecommendationLoading = false,
            isPostPlayRecommendationVisible = false
        )

        assertFalse(state.blocksNaturalCompletion)
    }

    @Test
    fun `true eof ignores transient background completion blockers`() {''',
        label="cover terminal rating and post-play EOF handoff",
    )

    require(
        post_play,
        "shouldShowNaturalEndRatingPrompt",
        label="terminal EOF rating decision",
    )
    require(
        credit_rating,
        "Showing rating overlay at terminal playback EOF",
        label="terminal EOF rating controller path",
    )
    require(
        initialization,
        "evaluateNaturalEndRatingPrompt()",
        label="Media3 EOF invokes rating fallback",
    )
    require(
        ui_state,
        "manualEndActionRequested &&\n                        isPostPlayRecommendationLoading",
        label="rating to post-play EOF hold",
    )
    require(
        post_play_test,
        "terminal eof offers rating when analyzer never supplied final credits",
        label="terminal EOF rating regression tests",
    )

    print()
    if CHANGED:
        print("EOF rating refinement applied successfully to:")
        for path in CHANGED:
            print(f"  - {path.relative_to(ROOT)}")
    else:
        print("EOF rating refinement already applied; no additional files changed.")

    print()
    print("Recommended verification:")
    print("  git diff --check")
    print("  ./gradlew :app:testDebugUnitTest")
    print("  ./gradlew :app:compileDebugKotlin")
    print("  ./gradlew :app:assembleDebug")


if __name__ == "__main__":
    main()
