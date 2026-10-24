#!/usr/bin/env python3
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
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


def insert_before_last(
    path: Path,
    marker: str,
    addition: str,
    *,
    label: str,
    already_marker: str,
) -> None:
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
    # IMPORTANT: V7 intentionally does NOT invoke any earlier patch script.
    # It is a narrow follow-up for Brandon's already-tested V6 working tree.
    snow = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/home/HolidaySnowOverlay.kt"
    nav = ROOT / "app/src/main/java/com/nuvio/tv/ui/navigation/NuvioNavHost.kt"
    ui_state = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerUiState.kt"
    playback_events = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt"
    credit_timing = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerCreditTiming.kt"
    post_play_test = ROOT / "app/src/test/java/com/nuvio/tv/ui/screens/player/PlayerPostPlayModelsTest.kt"

    # ------------------------------------------------------------------
    # 1) Snow bank: retire the 768-column accumulation path from runtime.
    # Falling flakes are unchanged. The bank is now a 25-point continuous
    # spline whose depth grows smoothly for 150 seconds. The cached path is
    # rebuilt only 4x/sec while growing, then becomes completely static.
    # ------------------------------------------------------------------
    replace_exact(
        snow,
        '''private const val SNOW_BANK_MAX_HEIGHT_DP = 7.5f\nprivate const val SNOW_BANK_DEPOSIT_RADIUS = 16''',
        '''private const val SNOW_BANK_MAX_HEIGHT_DP = 7.5f\nprivate const val SNOW_BANK_SPLINE_CONTROL_POINTS = 25\nprivate const val SNOW_BANK_GROWTH_DURATION_NANOS = 150_000_000_000L\nprivate const val SNOW_BANK_PATH_UPDATE_INTERVAL_NANOS = 250_000_000L\nprivate const val SNOW_BANK_DEPOSIT_RADIUS = 16''',
        label="configure slow 150-second spline snowbank growth",
    )

    replace_exact(
        snow,
        '''    val bankHeightsDp = FloatArray(SNOW_BANK_COLUMNS)\n\n    @Volatile\n    var bankGeneration: Int = 0''',
        '''    @Volatile\n    var bankGeneration: Int = 0''',
        label="remove per-session 768-column bank state",
    )

    replace_exact(
        snow,
        '''        val beforeNanos = state.simulationNanos\n        val afterNanos = beforeNanos + deltaNanos\n        val bankChanged = depositSnowBankForInterval(\n            bankHeightsDp = state.bankHeightsDp,\n            particleCount = config.particleCount.coerceIn(1, MAX_SNOW_PARTICLES),\n            beforeSeconds = beforeNanos / NANOS_PER_SECOND,\n            afterSeconds = afterNanos / NANOS_PER_SECOND,\n            config = config\n        )\n\n        state.simulationNanos = afterNanos\n        if (bankChanged) {\n            state.bankGeneration = state.bankGeneration + 1\n        }''',
        '''        val afterNanos = state.simulationNanos + deltaNanos\n        state.simulationNanos = afterNanos\n\n        /*\n         * Bank geometry is a visual accumulation cue, not a collision map.\n         * Quantize only the cached Path rebuild: at 4 Hz over 150 seconds the\n         * vertical change per generation is far below a pixel, while the snow\n         * producer avoids the old per-landing 768-column deposit/search work.\n         */\n        val cappedBankNanos =\n            afterNanos.coerceAtMost(SNOW_BANK_GROWTH_DURATION_NANOS)\n        val newBankGeneration =\n            (cappedBankNanos / SNOW_BANK_PATH_UPDATE_INTERVAL_NANOS).toInt()\n        if (newBankGeneration != state.bankGeneration) {\n            state.bankGeneration = newBankGeneration\n        }''',
        label="drive snowbank from elapsed snowfall time instead of column deposits",
    )

    replace_regex(
        snow,
        r'''    private fun rebuildBankPaths\(state: SeasonalSnowState\) \{[\s\S]*?        bankInnerFeatherPaint\.strokeWidth = 1\.2f \* density\n    \}''',
        '''    private fun rebuildBankPaths(state: SeasonalSnowState) {\n        bankFillPath.reset()\n        bankTopPath.reset()\n\n        val density = resources.displayMetrics.density\n        val rawGrowth =\n            (\n                state.simulationNanos.toDouble() /\n                    SNOW_BANK_GROWTH_DURATION_NANOS.toDouble()\n                ).toFloat().coerceIn(0f, 1f)\n        // Smoothstep keeps the first flakes subtle and reaches full depth only\n        // after 2.5 minutes of visible snowfall.\n        val growth = rawGrowth * rawGrowth * (3f - 2f * rawGrowth)\n        val lastControl = (SNOW_BANK_SPLINE_CONTROL_POINTS - 1).coerceAtLeast(1)\n        val controlX = FloatArray(SNOW_BANK_SPLINE_CONTROL_POINTS)\n        val controlY = FloatArray(SNOW_BANK_SPLINE_CONTROL_POINTS)\n\n        for (index in 0..lastControl) {\n            val fraction = index.toFloat() / lastControl.toFloat()\n            val phase = fraction * PI.toFloat()\n\n            /*\n             * Broad deterministic drifts read as natural accumulation without\n             * exposing any discrete columns. A tiny growth-dependent component\n             * means the silhouette evolves instead of simply scaling upward.\n             */\n            val broad = sin(phase * 3.6f + 0.35f) * 0.58f\n            val secondary = sin(phase * 7.4f + 1.20f) * 0.27f\n            val fine = sin(phase * 12.6f + 2.05f) * 0.15f\n            val evolving =\n                sin(phase * 5.2f + growth * PI.toFloat() * 0.85f) * 0.035f\n            val contour = (broad + secondary + fine).coerceIn(-1f, 1f)\n            val localDepth =\n                (0.84f + ((contour + 1f) * 0.5f) * 0.16f + evolving)\n                    .coerceIn(0.80f, 1.02f)\n            val bankHeightDp = SNOW_BANK_MAX_HEIGHT_DP * growth * localDepth\n\n            controlX[index] = width * fraction\n            controlY[index] = height - bankHeightDp * density\n        }\n\n        bankFillPath.moveTo(0f, height.toFloat())\n        bankFillPath.lineTo(controlX[0], controlY[0])\n        bankTopPath.moveTo(controlX[0], controlY[0])\n\n        /*\n         * Convert a uniform Catmull-Rom spline to cubic Beziers. Only 24 cubic\n         * segments are drawn, with true tangent continuity and no cell edges.\n         */\n        for (index in 0 until lastControl) {\n            val previous = (index - 1).coerceAtLeast(0)\n            val next = index + 1\n            val following = (index + 2).coerceAtMost(lastControl)\n\n            val p0x = controlX[previous]\n            val p0y = controlY[previous]\n            val p1x = controlX[index]\n            val p1y = controlY[index]\n            val p2x = controlX[next]\n            val p2y = controlY[next]\n            val p3x = controlX[following]\n            val p3y = controlY[following]\n\n            val c1x = p1x + (p2x - p0x) / 6f\n            val c1y = p1y + (p2y - p0y) / 6f\n            val c2x = p2x - (p3x - p1x) / 6f\n            val c2y = p2y - (p3y - p1y) / 6f\n\n            bankFillPath.cubicTo(c1x, c1y, c2x, c2y, p2x, p2y)\n            bankTopPath.cubicTo(c1x, c1y, c2x, c2y, p2x, p2y)\n        }\n\n        bankFillPath.lineTo(width.toFloat(), height.toFloat())\n        bankFillPath.close()\n\n        bankOuterFeatherPaint.strokeWidth = 2.4f * density\n        bankInnerFeatherPaint.strokeWidth = 1.2f * density\n    }''',
        label="replace visible bank columns with continuous cubic spline",
        already_marker="Convert a uniform Catmull-Rom spline to cubic Beziers",
    )

    # ------------------------------------------------------------------
    # 2) Details -> Home: the NavHost already owns a retained 350ms Details
    # dissolve. Feed its exact inverse alpha into the independent snow window
    # instead of merely toggling a second asynchronous 350ms fade.
    # ------------------------------------------------------------------
    replace_exact(
        snow,
        '''internal fun SeasonalEffectsOverlayHost(\n    visible: Boolean,\n    keepAlive: Boolean = visible,\n    requireHomeReady: Boolean = true,\n    viewModel: SeasonalEffectsViewModel = hiltViewModel()''',
        '''internal fun SeasonalEffectsOverlayHost(\n    visible: Boolean,\n    keepAlive: Boolean = visible,\n    requireHomeReady: Boolean = true,\n    revealAlphaOverride: Float? = null,\n    viewModel: SeasonalEffectsViewModel = hiltViewModel()''',
        label="accept exact navigation reveal alpha for seasonal surface",
    )

    replace_exact(
        snow,
        '''            surfaceRecoveryGeneration = surfaceRecoveryGeneration,\n            visible = visible,\n            appInForeground = appInForeground''',
        '''            surfaceRecoveryGeneration = surfaceRecoveryGeneration,\n            visible = visible,\n            revealAlphaOverride = revealAlphaOverride,\n            appInForeground = appInForeground''',
        label="pass navigation reveal alpha into dedicated snow surface",
    )

    replace_exact(
        snow,
        '''    surfaceRecoveryGeneration: Long,\n    visible: Boolean,\n    appInForeground: Boolean''',
        '''    surfaceRecoveryGeneration: Long,\n    visible: Boolean,\n    revealAlphaOverride: Float?,\n    appInForeground: Boolean''',
        label="add reveal alpha to dedicated snow surface signature",
    )

    # Two bind call sites share the same argument tail.
    text = snow.read_text(encoding="utf-8")
    bind_old = '''                state = snowState,\n                visible = visible,\n                appInForeground = appInForeground'''
    bind_new = '''                state = snowState,\n                visible = visible,\n                revealAlphaOverride = revealAlphaOverride,\n                appInForeground = appInForeground'''
    if bind_new in text:
        print("[already applied] bind exact reveal alpha to snow producer")
    else:
        count = text.count(bind_old)
        if count != 2:
            raise SystemExit(
                "bind exact reveal alpha to snow producer: "
                f"expected exactly two bind fragments in {snow}, found {count}"
            )
        write_if_changed(
            snow,
            text.replace(bind_old, bind_new),
            label="bind exact reveal alpha to snow producer",
        )

    replace_exact(
        snow,
        '''    @Volatile\n    private var appInForeground: Boolean = true\n\n    /*''',
        '''    @Volatile\n    private var appInForeground: Boolean = true\n\n    @Volatile\n    private var revealAlphaOverride: Float? = null\n\n    /*''',
        label="store navigation reveal alpha on producer thread",
    )

    replace_exact(
        snow,
        '''        state: SeasonalSnowState,\n        visible: Boolean,\n        appInForeground: Boolean\n    ) {\n        currentConfig = config\n        this.appInForeground = appInForeground''',
        '''        state: SeasonalSnowState,\n        visible: Boolean,\n        revealAlphaOverride: Float?,\n        appInForeground: Boolean\n    ) {\n        currentConfig = config\n        this.appInForeground = appInForeground\n        this.revealAlphaOverride = revealAlphaOverride?.coerceIn(0f, 1f)''',
        label="apply navigation reveal alpha without Compose-frame snow animation",
    )

    replace_exact(
        snow,
        '''                val alpha =\n                    presentationAlpha(\n                        frameNow\n                    )\n\n                val targetVisible =''',
        '''                val internalPresentationAlpha =\n                    presentationAlpha(\n                        frameNow\n                    )\n                val alpha =\n                    revealAlphaOverride\n                        ?: internalPresentationAlpha\n\n                val targetVisible =''',
        label="render snow and bank with exact Home dissolve alpha",
    )

    replace_exact(
        nav,
        '''    val seasonalOverlayTargetVisible =\n        seasonalOverlayVisible &&\n            (\n                !showDetailReturnOverlay.value ||\n                    detailReturnSnowReveal.value\n                )\n\n    Box(''',
        '''    val seasonalOverlayTargetVisible =\n        seasonalOverlayVisible &&\n            (\n                !showDetailReturnOverlay.value ||\n                    detailReturnSnowReveal.value\n                )\n\n    /*\n     * The snow Popup is a separate Android window, so Navigation/Compose alpha\n     * cannot fade it automatically. During Details -> Home, drive the snow\n     * producer from the exact inverse alpha of the retained Details frame.\n     */\n    val seasonalOverlayRevealAlphaOverride =\n        if (\n            seasonalOverlayRoute == Screen.Home.route &&\n            showDetailReturnOverlay.value\n        ) {\n            if (detailReturnSnowReveal.value) {\n                (1f - detailReturnOverlayAlpha.value).coerceIn(0f, 1f)\n            } else {\n                0f\n            }\n        } else {\n            null\n        }\n\n    Box(''',
        label="derive snow opacity from exact Details-to-Home dissolve",
    )

    replace_exact(
        nav,
        '''            visible = seasonalOverlayTargetVisible,\n            keepAlive = seasonalOverlayKeepAlive,\n            /*''',
        '''            visible = seasonalOverlayTargetVisible,\n            keepAlive = seasonalOverlayKeepAlive,\n            revealAlphaOverride = seasonalOverlayRevealAlphaOverride,\n            /*''',
        label="send exact Details-to-Home alpha to snow window",
    )

    # ------------------------------------------------------------------
    # 3) Rating -> More Like This: keep the existing black cover opaque after
    # Rating's reverse-stagger completes, reveal/preload post-play underneath,
    # then drop the cover target. PlayerScreen's existing 350ms alpha animation
    # performs the dissolve, with no player resize and no second PlayerView.
    # ------------------------------------------------------------------
    replace_exact(
        ui_state,
        '''    val creditRatingPromptHandled: Boolean = false,\n    val naturalEndRatingCheckPending: Boolean = false''',
        '''    val creditRatingPromptHandled: Boolean = false,\n    val naturalEndRatingCheckPending: Boolean = false,\n    val postPlayRevealPending: Boolean = false''',
        label="add post-play blackout reveal ownership state",
    )

    replace_exact(
        ui_state,
        '''                naturalEndRatingCheckPending ||\n                (''',
        '''                naturalEndRatingCheckPending ||\n                postPlayRevealPending ||\n                (''',
        label="block natural teardown during rating-to-post-play dissolve",
    )

    replace_exact(
        credit_timing,
        '''            creditRatingPromptHandled = false,\n            naturalEndRatingCheckPending = false''',
        '''            creditRatingPromptHandled = false,\n            naturalEndRatingCheckPending = false,\n            postPlayRevealPending = false''',
        label="reset post-play reveal ownership for new playback",
    )

    replace_exact(
        playback_events,
        '''import kotlinx.coroutines.launch''',
        '''import kotlinx.coroutines.launch\nimport kotlinx.coroutines.withTimeoutOrNull''',
        label="import bounded wait for post-play reveal",
    )

    replace_regex(
        playback_events,
        r'''        PlayerEvent\.OnRatingExitComplete -> \{[\s\S]*?        \}\n    \}\n\}\n\ninternal fun PlayerRuntimeController\.buildStreamInfoData''',
        '''        PlayerEvent.OnRatingExitComplete -> {\n            val rating = _uiState.value.pendingRating\n            val media = buildScrobbleItem()\n            if (_uiState.value.ratingOverlayDestination == RatingOverlayDestination.POST_PLAY) {\n                if (rating != null && media != null) {\n                    scope.launch {\n                        runCatching {\n                            trackingRatingCoordinator.submit(media = media, rating = rating)\n                        }\n                    }\n                }\n\n                /*\n                 * Rating's icons/text have already completed their designed\n                 * reverse-stagger. Keep the existing full-screen black cover\n                 * opaque while More Like This takes ownership underneath.\n                 */\n                _uiState.update {\n                    it.copy(\n                        showRatingOverlay = false,\n                        showPlayerBlackout = true,\n                        pendingRating = null,\n                        ratingOverlayDestination = null,\n                        creditRatingPromptHandled = true,\n                        postPlayRevealPending = true\n                    )\n                }\n\n                ratingTransitionJob?.cancel()\n                ratingTransitionJob = scope.launch {\n                    preparePostPlayRecommendationsForManualEndAction()\n\n                    /*\n                     * Recommendations are normally preloaded long before EOF.\n                     * Give a slower completion at most the same 750ms budget the\n                     * old implementation spent waiting before post-play. Never\n                     * turn this visual polish into another black-screen hang.\n                     */\n                    val postPlayReady =\n                        withTimeoutOrNull(750L) {\n                            while (isActive) {\n                                if (_uiState.value.isPostPlayRecommendationVisible) {\n                                    return@withTimeoutOrNull true\n                                }\n                                delay(16L)\n                            }\n                            false\n                        } == true\n\n                    if (postPlayReady) {\n                        // Commit at least one frame of More Like This beneath black.\n                        delay(32L)\n                    }\n\n                    _uiState.update { current ->\n                        if (!current.postPlayRevealPending) {\n                            current\n                        } else {\n                            current.copy(\n                                showPlayerBlackout = false,\n                                postPlayRevealPending = false\n                            )\n                        }\n                    }\n                }\n            } else if (rating != null && media != null) {\n                scope.launch {\n                    runCatching {\n                        trackingRatingCoordinator.submit(media = media, rating = rating)\n                    }\n                    _uiState.update {\n                        it.copy(\n                            ratingSubmitted = true,\n                            showRatingOverlay = false,\n                            ratingOverlayDestination = null\n                        )\n                    }\n                }\n            } else {\n                _uiState.update {\n                    it.copy(\n                        ratingSubmitted = true,\n                        showRatingOverlay = false,\n                        ratingOverlayDestination = null\n                    )\n                }\n            }\n        }\n    }\n}\n\ninternal fun PlayerRuntimeController.buildStreamInfoData''',
        label="fade black player cover away only after More Like This is underneath",
        already_marker="postPlayRevealPending = true",
    )

    insert_before_last(
        post_play_test,
        "\n}\n",
        '''\n    @Test\n    fun `post play reveal pending owns ended player until dissolve starts`() {\n        val state = PlayerUiState(\n            playbackEnded = true,\n            postPlayRevealPending = true\n        )\n\n        assertTrue(state.blocksNaturalCompletion)\n    }\n''',
        label="add post-play reveal completion-blocker regression",
        already_marker="post play reveal pending owns ended player until dissolve starts",
    )

    # Fail-fast validation of all three requested changes.
    require(
        snow,
        "SNOW_BANK_GROWTH_DURATION_NANOS = 150_000_000_000L",
        label="150-second spline accumulation",
    )
    require(
        snow,
        "Convert a uniform Catmull-Rom spline to cubic Beziers",
        label="continuous spline snowbank",
    )
    require(
        snow,
        "revealAlphaOverride",
        label="snow navigation-alpha handoff",
    )
    require(
        nav,
        "seasonalOverlayRevealAlphaOverride",
        label="Details-to-Home snow dissolve synchronization",
    )
    require(
        ui_state,
        "postPlayRevealPending",
        label="rating-to-post-play reveal ownership",
    )
    require(
        playback_events,
        "Commit at least one frame of More Like This beneath black",
        label="More Like This black-cover dissolve",
    )

    print()
    if CHANGED:
        print("V7 refinement applied successfully to:")
        for path in CHANGED:
            print(f"  - {path.relative_to(ROOT)}")
    else:
        print("V7 refinement already applied; no additional files changed.")

    print()
    print("Recommended verification:")
    print("  git diff --check")
    print("  ./gradlew :app:testDebugUnitTest")
    print("  ./gradlew :app:compileDebugKotlin")
    print("  ./gradlew :app:assembleSideload")


if __name__ == "__main__":
    main()
