#!/usr/bin/env python3
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
V3_PATCH = ROOT / "patches/apply_fix_holiday_cw_credits_eof_v3.py"
CHANGED: list[Path] = []


def write_if_changed(path: Path, text: str, *, label: str) -> None:
    old = path.read_text(encoding="utf-8")
    if old == text:
        print(f"[already applied] {label}")
        return
    path.write_text(text, encoding="utf-8")
    if path not in CHANGED:
        CHANGED.append(path)
    print(f"[patched] {label}")


def replace_exact(path: Path, old: str, new: str, *, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        print(f"[already applied] {label}")
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"{label}: expected exactly one original fragment in {path}, found {count}"
        )
    write_if_changed(path, text.replace(old, new, 1), label=label)


def replace_regex(
    path: Path,
    pattern: str,
    replacement: str,
    *,
    label: str,
    already_marker: str,
) -> None:
    text = path.read_text(encoding="utf-8")
    if already_marker in text:
        print(f"[already applied] {label}")
        return
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(
            f"{label}: expected exactly one regex match in {path}, found {count}"
        )
    write_if_changed(path, updated, label=label)


def insert_before_last(path: Path, marker: str, addition: str, *, label: str, already_marker: str) -> None:
    text = path.read_text(encoding="utf-8")
    if already_marker in text:
        print(f"[already applied] {label}")
        return
    index = text.rfind(marker)
    if index < 0:
        raise SystemExit(f"{label}: final marker not found in {path}")
    write_if_changed(path, text[:index] + addition + text[index:], label=label)


def require(path: Path, needle: str, *, label: str) -> None:
    if needle not in path.read_text(encoding="utf-8"):
        raise SystemExit(f"{label}: validation marker missing from {path}")


def main() -> None:
    # V3 contains the snowbank, CW, shared Skip Credits timeout, terminal EOF,
    # analyzer-complete, and post-play cleanup work. It is idempotent, so this
    # script is safe both on a clean branch and on Brandon's already-patched
    # local checkout.
    subprocess.run([sys.executable, str(V3_PATCH)], cwd=ROOT, check=True)

    post_play = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerPostPlayModels.kt"
    credit_rating = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerCreditRating.kt"
    initialization = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt"
    ui_state = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerUiState.kt"
    credit_timing = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerCreditTiming.kt"
    post_credit_test = ROOT / "app/src/test/java/com/nuvio/tv/ui/screens/player/PostCreditSceneTimingTest.kt"
    post_play_test = ROOT / "app/src/test/java/com/nuvio/tv/ui/screens/player/PlayerPostPlayModelsTest.kt"

    # ------------------------------------------------------------------
    # 1) Skip Credits: the 10-second rule is about time REMAINING when the
    # button would be offered. V1-V3 accidentally compared the entire analyzer
    # gap instead, which can still expose a button after playback has advanced
    # inside that last ten seconds (especially with cached timing).
    # ------------------------------------------------------------------
    replace_regex(
        post_play,
        r'''        if \(index == 0\) \{\n            val creditsStartMs = timing\.creditsStartMs \?: return null\n            val gapMs = scene\.startMs - creditsStartMs\n            return scene\.takeIf \{\n                positionMs >= creditsStartMs &&\n                    gapMs > minimumOfferLeadTimeMs\.coerceAtLeast\(0L\)\n            \}\n        \}\n\n        val previousScene = scenes\[index - 1\]\n        if \(positionMs < previousScene\.endMs\) return null\n\n        val gapMs = scene\.startMs - previousScene\.endMs\n        return scene\.takeIf \{\n            gapMs > continuityGapMs\.coerceAtLeast\(0L\) &&\n                gapMs > minimumOfferLeadTimeMs\.coerceAtLeast\(0L\)\n        \}''',
        '''        val minimumLeadMs = minimumOfferLeadTimeMs.coerceAtLeast(0L)\n        val remainingMs = scene.startMs - positionMs\n\n        if (index == 0) {\n            val creditsStartMs = timing.creditsStartMs ?: return null\n            return scene.takeIf {\n                positionMs >= creditsStartMs &&\n                    remainingMs > minimumLeadMs\n            }\n        }\n\n        val previousScene = scenes[index - 1]\n        if (positionMs < previousScene.endMs) return null\n\n        val structuralGapMs = scene.startMs - previousScene.endMs\n        return scene.takeIf {\n            structuralGapMs > continuityGapMs.coerceAtLeast(0L) &&\n                remainingMs > minimumLeadMs\n        }''',
        label="use live remaining time for Skip Credits 10-second gate",
        already_marker="val remainingMs = scene.startMs - positionMs",
    )

    # Keep rating eligibility aligned with the rest of the player, which already
    # accepts both movie and film as movie-like content.
    replace_exact(
        post_play,
        '''        "movie" -> true''',
        '''        "movie", "film" -> true''',
        label="treat film alias as rating-eligible movie content",
    )

    # Split the natural EOF decision into:
    # - reservation: synchronous, provider-independent, used to prevent a
    #   one-frame More Like This / navigation race at STATE_ENDED;
    # - display: cached-provider convenience used by pure tests/other callers.
    replace_regex(
        post_play,
        r'''/\*\*\n \* Terminal Media3 EOF is the final fallback rating boundary\.[\s\S]*?internal fun shouldShowNaturalEndRatingPrompt\(\n    state: PlayerUiState\n\): Boolean \{[\s\S]*?\n\}\n\ninternal fun shouldStartManualEndAction\(''',
        '''/**\n * Returns whether terminal EOF should reserve the player for a rating check.\n *\n * Provider availability is deliberately NOT part of this pure decision. The\n * provider is rechecked at EOF while this reservation blocks natural teardown,\n * preventing More Like This (or Home) from winning a one-frame race.\n */\ninternal fun shouldReserveNaturalEndRatingPrompt(\n    state: PlayerUiState\n): Boolean {\n    if (!state.playbackEnded || state.error != null) return false\n    if (state.creditRatingPromptHandled) return false\n    if (state.ratingOverlayDestination != null || state.showRatingOverlay) return false\n\n    return isRatingPromptEligibleContent(\n        contentType = state.contentType,\n        currentSeason = state.currentSeason,\n        currentEpisode = state.currentEpisode,\n        episodes = state.episodesAll\n    )\n}\n\ninternal fun shouldShowNaturalEndRatingPrompt(\n    state: PlayerUiState\n): Boolean =\n    state.isRatingProviderConnected &&\n        shouldReserveNaturalEndRatingPrompt(state)\n\ninternal fun shouldStartManualEndAction(''',
        label="reserve terminal EOF before provider recheck",
        already_marker="shouldReserveNaturalEndRatingPrompt",
    )

    # ------------------------------------------------------------------
    # 2) EOF rating handoff: mark the EOF rating check as pending in UI state.
    # That pending bit is a real ownership state, not inferred from analyzer
    # RUNNING/COMPLETE. It closes the race between Media3 STATE_ENDED, Compose's
    # natural-completion observer, and already-preloaded More Like This data.
    # ------------------------------------------------------------------
    replace_exact(
        ui_state,
        '''    val ratingOverlayDestination: RatingOverlayDestination? = null,\n    val creditRatingPromptHandled: Boolean = false''',
        '''    val ratingOverlayDestination: RatingOverlayDestination? = null,\n    val creditRatingPromptHandled: Boolean = false,\n    val naturalEndRatingCheckPending: Boolean = false''',
        label="add atomic terminal rating-check ownership state",
    )

    replace_regex(
        ui_state,
        r'''    val blocksNaturalCompletion: Boolean\n        get\(\) =\n            /\*[\s\S]*?\n                    \)\n\n    val blocksEndActionForRating:''',
        '''    val blocksNaturalCompletion: Boolean\n        get() =\n            isPostPlayRecommendationVisible ||\n                showRatingOverlay ||\n                ratingOverlayDestination == RatingOverlayDestination.POST_PLAY ||\n                naturalEndRatingCheckPending ||\n                (\n                    playbackEnded &&\n                        manualEndActionRequested &&\n                        isPostPlayRecommendationLoading\n                    ) ||\n                (\n                    !playbackEnded &&\n                        (\n                            isPostPlayRecommendationLoading ||\n                                creditTiming.status == CreditTimingStatus.RUNNING\n                            )\n                    )\n\n    val blocksEndActionForRating:''',
        label="make terminal rating check an explicit completion blocker",
        already_marker="naturalEndRatingCheckPending ||",
    )

    # Make the reservation in the SAME state update that publishes playbackEnded.
    # If post-play was made visible on the last progress tick, hide it atomically
    # while we decide whether Rating owns the screen first.
    replace_regex(
        initialization,
        r'''                        val isBuffering = playbackState == Player\.STATE_BUFFERING\n                        _uiState\.update \{\s*it\.copy\(\s*isBuffering = isBuffering,\s*playbackEnded = playbackState == Player\.STATE_ENDED,\s*duration = playerDuration\.coerceAtLeast\(0L\)\s*\)\s*\}''',
        '''                        val isBuffering = playbackState == Player.STATE_BUFFERING\n                        val playbackEndedNow = playbackState == Player.STATE_ENDED\n                        _uiState.update { state ->\n                            val endedState = if (playbackEndedNow) {\n                                state.copy(playbackEnded = true)\n                            } else {\n                                state\n                            }\n                            val reserveNaturalEndRating =\n                                playbackEndedNow &&\n                                    shouldReserveNaturalEndRatingPrompt(endedState)\n\n                            state.copy(\n                                isBuffering = isBuffering,\n                                playbackEnded = playbackEndedNow,\n                                duration = playerDuration.coerceAtLeast(0L),\n                                naturalEndRatingCheckPending = reserveNaturalEndRating,\n                                manualEndActionRequested =\n                                    if (reserveNaturalEndRating) true\n                                    else state.manualEndActionRequested,\n                                isPostPlayRecommendationVisible =\n                                    if (reserveNaturalEndRating) false\n                                    else state.isPostPlayRecommendationVisible,\n                                showNextEpisodeCard =\n                                    if (reserveNaturalEndRating) false\n                                    else state.showNextEpisodeCard,\n                                nextEpisodeAutoPlaySearching =\n                                    if (reserveNaturalEndRating) false\n                                    else state.nextEpisodeAutoPlaySearching,\n                                nextEpisodeAutoPlaySourceName =\n                                    if (reserveNaturalEndRating) null\n                                    else state.nextEpisodeAutoPlaySourceName,\n                                nextEpisodeAutoPlayCountdownSec =\n                                    if (reserveNaturalEndRating) null\n                                    else state.nextEpisodeAutoPlayCountdownSec\n                            )\n                        }''',
        label="reserve rating before STATE_ENDED becomes observable",
        already_marker="val reserveNaturalEndRating =",
    )

    # The EOF decision now rechecks the selected provider. Startup normally has
    # the correct value, but this makes terminal behavior authoritative and also
    # gives us a diagnostic log if a future test ever skips Rating again.
    replace_exact(
        credit_rating,
        '''import android.util.Log\nimport kotlinx.coroutines.flow.update''',
        '''import android.util.Log\nimport kotlinx.coroutines.flow.update\nimport kotlinx.coroutines.launch''',
        label="import coroutine launch for terminal provider recheck",
    )

    replace_regex(
        credit_rating,
        r'''internal fun PlayerRuntimeController\.evaluateNaturalEndRatingPrompt\(\) \{[\s\S]*?\n\}\n\ninternal fun PlayerRuntimeController\.requestManualEndAction\(\) \{''',
        '''internal fun PlayerRuntimeController.evaluateNaturalEndRatingPrompt() {\n    val state = _uiState.value\n    if (!shouldReserveNaturalEndRatingPrompt(state)) {\n        if (state.naturalEndRatingCheckPending) {\n            _uiState.update {\n                it.copy(naturalEndRatingCheckPending = false)\n            }\n        }\n        return\n    }\n\n    nextEpisodeAutoPlayJob?.cancel()\n    nextEpisodeAutoPlayJob = null\n    creditAnalysisJob?.cancel()\n    creditAnalysisJob = null\n\n    val cachedAvailability = state.isRatingProviderConnected\n    scope.launch {\n        val available = runCatching {\n            trackingRatingCoordinator.isAvailable()\n        }.getOrDefault(cachedAvailability)\n\n        val current = _uiState.value\n        val stillReserved =\n            current.naturalEndRatingCheckPending &&\n                shouldReserveNaturalEndRatingPrompt(current)\n\n        Log.i(\n            PlayerRuntimeController.TAG,\n            "Terminal EOF rating check: " +\n                "available=$available, " +\n                "cachedAvailability=$cachedAvailability, " +\n                "stillReserved=$stillReserved, " +\n                "contentType=${current.contentType}, " +\n                "handled=${current.creditRatingPromptHandled}, " +\n                "timingStatus=${current.creditTiming.status}, " +\n                "finalCreditsStartMs=${current.creditTiming.finalCreditsStartMs}, " +\n                "postCreditScenes=${current.creditTiming.postCreditScenes.size}"\n        )\n\n        if (!stillReserved) {\n            _uiState.update {\n                it.copy(\n                    isRatingProviderConnected = available,\n                    naturalEndRatingCheckPending = false\n                )\n            }\n            return@launch\n        }\n\n        if (available) {\n            _uiState.update { latest ->\n                if (!shouldReserveNaturalEndRatingPrompt(latest)) {\n                    latest.copy(naturalEndRatingCheckPending = false)\n                } else {\n                    latest.copy(\n                        isRatingProviderConnected = true,\n                        naturalEndRatingCheckPending = false,\n                        manualEndActionRequested = true,\n                        showRatingOverlay = true,\n                        showControls = false,\n                        showPauseOverlay = false,\n                        showPlayerBlackout = false,\n                        ratingSubmitted = false,\n                        pendingRating = null,\n                        ratingOverlayDestination = RatingOverlayDestination.POST_PLAY,\n                        creditRatingPromptHandled = false,\n                        isPostPlayRecommendationVisible = false,\n                        showNextEpisodeCard = false,\n                        nextEpisodeAutoPlaySearching = false,\n                        nextEpisodeAutoPlaySourceName = null,\n                        nextEpisodeAutoPlayCountdownSec = null\n                    )\n                }\n            }\n\n            if (_uiState.value.showRatingOverlay) {\n                Log.i(\n                    PlayerRuntimeController.TAG,\n                    "Showing rating overlay at terminal playback EOF"\n                )\n                preparePostPlayRecommendationsForManualEndAction()\n            }\n            return@launch\n        }\n\n        /*\n         * No selected/connected rating provider: preserve the normal EOF ->\n         * More Like This flow. Keep the pending reservation until post-play is\n         * visible or loading so natural completion cannot slip through between\n         * these state updates.\n         */\n        _uiState.update {\n            it.copy(\n                isRatingProviderConnected = false,\n                creditRatingPromptHandled = true,\n                manualEndActionRequested = true\n            )\n        }\n        preparePostPlayRecommendationsForManualEndAction()\n        _uiState.update {\n            it.copy(naturalEndRatingCheckPending = false)\n        }\n        schedulePostPlayTrailerAfterPlaybackEnded()\n    }\n}\n\ninternal fun PlayerRuntimeController.requestManualEndAction() {''',
        label="make EOF rating provider check atomic and authoritative",
        already_marker="Terminal EOF rating check:",
    )

    # Reset the pending ownership bit between playback sessions.
    replace_exact(
        credit_timing,
        '''            ratingOverlayDestination = null,\n            creditRatingPromptHandled = false''',
        '''            ratingOverlayDestination = null,\n            creditRatingPromptHandled = false,\n            naturalEndRatingCheckPending = false''',
        label="reset terminal rating ownership for new playback",
    )

    # If post-play recommendations were already preloaded, an EOF-triggered
    # rating flow reveals them only after Rating exits. Schedule the usual trailer
    # countdown here because there will be no second STATE_ENDED callback later.
    replace_regex(
        credit_timing,
        r'''            _uiState\.update \{ current ->\n                if \(\n                    current\.postPlayRecommendationDismissed \|\|\n                    current\.blocksEndActionForRating \|\|\n                    current\.showRatingOverlay\n                \) \{\n                    current\n                \} else \{\n                    current\.copy\(\n                        isPostPlayRecommendationVisible = true,\n                        showControls = false,\n                        showPauseOverlay = false,\n                        showNextEpisodeCard = false\n                    \)\n                \}\n            \}\n        \}\n        return''',
        '''            _uiState.update { current ->\n                if (\n                    current.postPlayRecommendationDismissed ||\n                    current.blocksEndActionForRating ||\n                    current.showRatingOverlay\n                ) {\n                    current\n                } else {\n                    current.copy(\n                        isPostPlayRecommendationVisible = true,\n                        showControls = false,\n                        showPauseOverlay = false,\n                        showNextEpisodeCard = false\n                    )\n                }\n            }\n            if (\n                _uiState.value.playbackEnded &&\n                _uiState.value.isPostPlayRecommendationVisible\n            ) {\n                schedulePostPlayTrailerAfterPlaybackEnded()\n            }\n        }\n        return''',
        label="schedule post-play trailer after EOF rating handoff",
        already_marker="_uiState.value.playbackEnded &&\n                _uiState.value.isPostPlayRecommendationVisible",
    )

    # ------------------------------------------------------------------
    # Regression tests for the exact failures observed on the TV.
    # ------------------------------------------------------------------
    insert_before_last(
        post_credit_test,
        "\n}\n",
        '''\n    @Test\n    fun `cached timing is suppressed once playback is inside final ten seconds`() {\n        val scene = PostCreditSceneTiming(\n            startMs = 140_000L,\n            endMs = 160_000L\n        )\n\n        val state = CreditTimingUiState(\n            status = CreditTimingStatus.COMPLETE,\n            creditsStartMs = 100_000L,\n            hasPostCreditScenes = true,\n            postCreditScenes = listOf(scene)\n        )\n\n        // The full analyzer gap is forty seconds, but a cached result can be\n        // consumed late. At exactly ten seconds remaining there must be no UI.\n        assertNull(\n            postCreditSkipTarget(\n                timing = state,\n                positionMs = 130_000L\n            )\n        )\n\n        assertNull(\n            postCreditSkipTarget(\n                timing = state,\n                positionMs = 130_001L\n            )\n        )\n\n        assertEquals(\n            scene,\n            postCreditSkipTarget(\n                timing = state,\n                positionMs = 129_999L\n            )\n        )\n    }\n\n    @Test\n    fun `later separated scene still requires more than ten seconds remaining`() {\n        val first = PostCreditSceneTiming(\n            startMs = 120_000L,\n            endMs = 130_000L\n        )\n        val second = PostCreditSceneTiming(\n            startMs = 160_000L,\n            endMs = 175_000L\n        )\n        val state = CreditTimingUiState(\n            status = CreditTimingStatus.COMPLETE,\n            creditsStartMs = 100_000L,\n            hasPostCreditScenes = true,\n            postCreditScenes = listOf(first, second)\n        )\n\n        assertNull(\n            postCreditSkipTarget(\n                timing = state,\n                positionMs = 150_000L,\n                skippedSceneStarts = setOf(first.startMs)\n            )\n        )\n        assertEquals(\n            second,\n            postCreditSkipTarget(\n                timing = state,\n                positionMs = 149_999L,\n                skippedSceneStarts = setOf(first.startMs)\n            )\n        )\n    }\n''',
        label="add live Skip Credits remaining-time regressions",
        already_marker="cached timing is suppressed once playback is inside final ten seconds",
    )

    insert_before_last(
        post_play_test,
        "\n}\n",
        '''\n    @Test\n    fun `terminal eof reserves rating before provider recheck`() {\n        val state = PlayerUiState(\n            playbackEnded = true,\n            contentType = "movie",\n            isRatingProviderConnected = false,\n            creditRatingPromptHandled = false\n        )\n\n        assertTrue(shouldReserveNaturalEndRatingPrompt(state))\n        assertFalse(shouldShowNaturalEndRatingPrompt(state))\n    }\n\n    @Test\n    fun `terminal rating pending state blocks natural completion`() {\n        val state = PlayerUiState(\n            playbackEnded = true,\n            contentType = "movie",\n            naturalEndRatingCheckPending = true\n        )\n\n        assertTrue(state.blocksNaturalCompletion)\n    }\n\n    @Test\n    fun `film alias is eligible for terminal movie rating`() {\n        assertTrue(\n            isRatingPromptEligibleContent(\n                contentType = "film",\n                currentSeason = null,\n                currentEpisode = null,\n                episodes = emptyList()\n            )\n        )\n    }\n''',
        label="add atomic EOF rating regression tests",
        already_marker="terminal eof reserves rating before provider recheck",
    )

    require(
        post_play,
        "val remainingMs = scene.startMs - positionMs",
        label="live Skip Credits lead-time gate",
    )
    require(
        post_play,
        "shouldReserveNaturalEndRatingPrompt",
        label="provider-independent EOF rating reservation",
    )
    require(
        ui_state,
        "naturalEndRatingCheckPending",
        label="terminal rating ownership state",
    )
    require(
        initialization,
        "val reserveNaturalEndRating =",
        label="atomic STATE_ENDED reservation",
    )
    require(
        credit_rating,
        "Terminal EOF rating check:",
        label="terminal provider recheck and diagnostics",
    )
    require(
        post_credit_test,
        "cached timing is suppressed once playback is inside final ten seconds",
        label="Skip Credits cached-timing regression",
    )
    require(
        post_play_test,
        "terminal rating pending state blocks natural completion",
        label="EOF rating ownership regression",
    )

    print()
    if CHANGED:
        print("V4 live-timing/EOF-rating refinement applied successfully to:")
        for path in CHANGED:
            print(f"  - {path.relative_to(ROOT)}")
    else:
        print("V4 refinement already applied; no additional files changed.")

    print()
    print("Recommended verification:")
    print("  git diff --check")
    print("  ./gradlew :app:testDebugUnitTest")
    print("  ./gradlew :app:compileDebugKotlin")
    print("  ./gradlew :app:assembleSideload")


if __name__ == "__main__":
    main()
