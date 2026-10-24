#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
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
    parser = argparse.ArgumentParser(
        description="Apply the 2026-10-24 Nuvio holiday/CW/credits/EOF fixes."
    )
    parser.parse_args()

    snow = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/home/HolidaySnowOverlay.kt"
    home_content = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/home/ModernHomeContent.kt"
    home_rows = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/home/ModernHomeRows.kt"
    post_play = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerPostPlayModels.kt"
    skip_credits = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/player/SkipCreditsButton.kt"
    ui_state = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerUiState.kt"
    runtime = ROOT / "app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeController.kt"
    post_credit_test = ROOT / "app/src/test/java/com/nuvio/tv/ui/screens/player/PostCreditSceneTimingTest.kt"
    post_play_test = ROOT / "app/src/test/java/com/nuvio/tv/ui/screens/player/PlayerPostPlayModelsTest.kt"

    replace_exact(
        snow,
        r'''    private fun rebuildBankPaths(state: SeasonalSnowState) {
        bankFillPath.reset()
        bankTopPath.reset()

        val density = resources.displayMetrics.density
        val columnWidth = if (SNOW_BANK_COLUMNS > 1) {
            width.toFloat() / (SNOW_BANK_COLUMNS - 1)
        } else {
            width.toFloat()
        }

        fun wrappedBankColumn(index: Int): Int {
            val wrapped = index % SNOW_BANK_COLUMNS
            return if (wrapped < 0) wrapped + SNOW_BANK_COLUMNS else wrapped
        }

        fun bankFillProgress(column: Int): Float {
            val wrapped = wrappedBankColumn(column)
            val cap = snowBankColumnCapDp[wrapped].coerceAtLeast(0.01f)
            return (state.bankHeightsDp[wrapped] / cap).coerceIn(0f, 1f)
        }

        fun bankSurfaceY(column: Int): Float {
            var progress = 0f
            for (
                offset in -SNOW_BANK_RENDER_SMOOTH_RADIUS..
                    SNOW_BANK_RENDER_SMOOTH_RADIUS
            ) {
                progress += bankFillProgress(column + offset) *
                    snowBankRenderKernel[offset + SNOW_BANK_RENDER_SMOOTH_RADIUS]
            }

            val heightDp = snowBankColumnCapDp[column] *
                progress.coerceIn(0f, 1f)
            return height - heightDp * density
        }

        val firstY = bankSurfaceY(0)
        bankFillPath.moveTo(0f, height.toFloat())
        bankFillPath.lineTo(0f, firstY)
        bankTopPath.moveTo(0f, firstY)

        var previousX = 0f
        var previousY = firstY

        for (column in 1 until SNOW_BANK_COLUMNS) {
            val x = column * columnWidth
            val y = bankSurfaceY(column)
            val midpointX = (previousX + x) * 0.5f
            val midpointY = (previousY + y) * 0.5f

            bankFillPath.quadTo(previousX, previousY, midpointX, midpointY)
            bankTopPath.quadTo(previousX, previousY, midpointX, midpointY)
            previousX = x
            previousY = y
        }

        bankFillPath.quadTo(previousX, previousY, previousX, previousY)
        bankTopPath.quadTo(previousX, previousY, previousX, previousY)
        bankFillPath.lineTo(width.toFloat(), height.toFloat())
        bankFillPath.close()

        bankOuterFeatherPaint.strokeWidth = 2.4f * density
        bankInnerFeatherPaint.strokeWidth = 1.2f * density
    }''',
        r'''    private fun rebuildBankPaths(state: SeasonalSnowState) {
        bankFillPath.reset()
        bankTopPath.reset()

        val density = resources.displayMetrics.density

        fun wrappedBankColumn(index: Int): Int {
            val wrapped = index % SNOW_BANK_COLUMNS
            return if (wrapped < 0) wrapped + SNOW_BANK_COLUMNS else wrapped
        }

        fun bankFillProgress(column: Int): Float {
            val wrapped = wrappedBankColumn(column)
            val cap = snowBankColumnCapDp[wrapped].coerceAtLeast(0.01f)
            return (state.bankHeightsDp[wrapped] / cap).coerceIn(0f, 1f)
        }

        /*
         * Keep the accumulation simulation at 768 columns. Increasing that
         * simulation resolution would multiply deposit/smoothing work for no
         * gameplay benefit. Instead, build a smoothed simulation profile once
         * per bank generation and resample only the rendered silhouette.
         *
         * On a 4K surface this roughly doubles the number of render samples
         * (capped at 1536), while 1080p stays close to the existing cost. The
         * cached Path is still what gets drawn every frame.
         */
        val smoothedSurfaceY = FloatArray(SNOW_BANK_COLUMNS)
        for (column in 0 until SNOW_BANK_COLUMNS) {
            var progress = 0f
            for (
                offset in -SNOW_BANK_RENDER_SMOOTH_RADIUS..
                    SNOW_BANK_RENDER_SMOOTH_RADIUS
            ) {
                progress += bankFillProgress(column + offset) *
                    snowBankRenderKernel[offset + SNOW_BANK_RENDER_SMOOTH_RADIUS]
            }

            val heightDp = snowBankColumnCapDp[column] *
                progress.coerceIn(0f, 1f)
            smoothedSurfaceY[column] = height - heightDp * density
        }

        fun surfaceY(column: Int): Float =
            smoothedSurfaceY[wrappedBankColumn(column)]

        /*
         * Catmull-Rom interpolation removes the visible "one column, one
         * height" stepping without changing the accumulation model. Clamp the
         * interpolated value to its four source samples so a sharp local drift
         * cannot overshoot and create a little spike above the real snowbank.
         */
        fun interpolatedSurfaceY(sourceColumn: Float): Float {
            val base = sourceColumn.toInt()
                .coerceIn(0, SNOW_BANK_COLUMNS - 1)
            val t = (sourceColumn - base).coerceIn(0f, 1f)
            val t2 = t * t
            val t3 = t2 * t

            val p0 = surfaceY(base - 1)
            val p1 = surfaceY(base)
            val p2 = surfaceY(base + 1)
            val p3 = surfaceY(base + 2)

            val interpolated = 0.5f * (
                2f * p1 +
                    (-p0 + p2) * t +
                    (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 +
                    (-p0 + 3f * p1 - 3f * p2 + p3) * t3
                )

            return interpolated.coerceIn(
                minOf(p0, p1, p2, p3),
                maxOf(p0, p1, p2, p3)
            )
        }

        val renderSampleCount = minOf(
            maxOf(SNOW_BANK_COLUMNS, (width / 2).coerceAtLeast(2)),
            1536
        )
        val renderLastIndex = (renderSampleCount - 1).coerceAtLeast(1)

        val firstY = interpolatedSurfaceY(0f)
        bankFillPath.moveTo(0f, height.toFloat())
        bankFillPath.lineTo(0f, firstY)
        bankTopPath.moveTo(0f, firstY)

        for (sample in 1..renderLastIndex) {
            val fraction = sample.toFloat() / renderLastIndex.toFloat()
            val x = width * fraction
            val sourceColumn = (SNOW_BANK_COLUMNS - 1) * fraction
            val y = interpolatedSurfaceY(sourceColumn)

            bankFillPath.lineTo(x, y)
            bankTopPath.lineTo(x, y)
        }

        bankFillPath.lineTo(width.toFloat(), height.toFloat())
        bankFillPath.close()

        bankOuterFeatherPaint.strokeWidth = 2.4f * density
        bankInnerFeatherPaint.strokeWidth = 1.2f * density
    }''',
        label="smooth accumulated snowbank render surface",
    )

    replace_exact(
        home_rows,
        r'''        val retainedRealFocusIndex =
            uiCaches.lastActuallyFocusedIndexByRow[row.key]

        val rowListState = rowListStates.getOrPut(row.key) {
            LazyListState(
                firstVisibleItemIndex =
                    retainedRealFocusIndex
                        ?: focusStateCatalogRowScrollStates[row.key]
                        ?: 0,
                firstVisibleItemScrollOffset =
                    if (retainedRealFocusIndex != null) {
                        0
                    } else {
                        focusStateCatalogRowScrollStates[
                            "${row.key}::offset"
                        ] ?: 0
                    }
            )
        }''',
        r'''        val retainedRealFocusIndex =
            uiCaches.lastActuallyFocusedIndexByRow[row.key]

        /*
         * A player return can structurally remove the focused CW title while
         * Home is still behind its opaque return curtain. In that case the
         * numeric focus target alone is not enough: an existing LazyListState
         * can still carry the old positive pixel offset and leave item 0
         * partially offscreen.
         *
         * Seed a newly-created state at the exact target with offset 0, and
         * perform the same non-animated snap for an already-existing state.
         * The Home return curtain remains opaque through the following frame,
         * so the user never sees this correction.
         */
        val forcedContinueWatchingViewportIndex =
            if (
                row.key == "continue_watching" &&
                forceContinueWatchingRestoreToStart &&
                row.items.isNotEmpty()
            ) {
                continueWatchingRestoreTargetIndex.coerceIn(
                    0,
                    row.items.lastIndex
                )
            } else {
                null
            }

        val rowListState = rowListStates.getOrPut(row.key) {
            LazyListState(
                firstVisibleItemIndex =
                    forcedContinueWatchingViewportIndex
                        ?: retainedRealFocusIndex
                        ?: focusStateCatalogRowScrollStates[row.key]
                        ?: 0,
                firstVisibleItemScrollOffset =
                    if (
                        forcedContinueWatchingViewportIndex != null ||
                        retainedRealFocusIndex != null
                    ) {
                        0
                    } else {
                        focusStateCatalogRowScrollStates[
                            "${row.key}::offset"
                        ] ?: 0
                    }
            )
        }

        LaunchedEffect(
            forceContinueWatchingRestoreToStart,
            forcedContinueWatchingViewportIndex,
            rowListState
        ) {
            val targetIndex =
                forcedContinueWatchingViewportIndex
                    ?: return@LaunchedEffect

            rowListState.scrollToItem(
                targetIndex,
                0
            )
        }''',
        label="pre-position Continue Watching before Home reveal",
    )

    replace_exact(
        home_content,
        r'''            pendingRowFocus.suppressBringIntoView =
                false
            pendingRowFocus.nonce++''',
        r'''            /*
             * The viewport was positioned explicitly above. Re-enabling
             * BringIntoView here can immediately reintroduce the old
             * horizontal offset (and was the source of prior errant row
             * scrolling). Keep it suppressed for this atomic handoff.
             */
            pendingRowFocus.suppressBringIntoView =
                true
            pendingRowFocus.nonce++''',
        label="keep CW structural handoff bring-into-view suppressed",
    )

    replace_exact(
        post_play,
        r'''internal const val CREDIT_COMPLETION_EXIT_GRACE_MS = 2 * 60_000L
internal const val POST_CREDIT_SCENE_CONTINUITY_GAP_MS = 10_000L''',
        r'''internal const val CREDIT_COMPLETION_EXIT_GRACE_MS = 2 * 60_000L
internal const val SKIP_CREDITS_TIMEOUT_MS = 10_000L
internal const val POST_CREDIT_SCENE_CONTINUITY_GAP_MS =
    SKIP_CREDITS_TIMEOUT_MS''',
        label="share Skip Credits timeout with timing rules",
    )

    replace_exact(
        post_play,
        r'''/**
 * Returns the next post-credit scene that should be offered as a Skip Credits
 * target at the current playback position.
 *
 * The first post-credit scene always gets a skip opportunity once credits
 * begin. Later scenes are treated as part of the same continuous post-credit
 * cluster when they begin within 10 seconds of the previous scene ending.
 */''',
        r'''/**
 * Returns the next post-credit scene that should be offered as a Skip Credits
 * target at the current playback position.
 *
 * A button is useful only when the upcoming scene is farther away than the
 * button's own countdown lifetime. Gaps at or below that threshold flow
 * directly into the scene with no transient Skip Credits prompt. Later scenes
 * also preserve the existing continuity-gap rule.
 */''',
        label="document Skip Credits minimum lead-time rule",
    )

    replace_exact(
        post_play,
        r'''internal fun postCreditSkipTarget(
    timing: CreditTimingUiState,
    positionMs: Long,
    skippedSceneStarts: Set<Long> = emptySet(),
    continuityGapMs: Long = POST_CREDIT_SCENE_CONTINUITY_GAP_MS
): PostCreditSceneTiming? {''',
        r'''internal fun postCreditSkipTarget(
    timing: CreditTimingUiState,
    positionMs: Long,
    skippedSceneStarts: Set<Long> = emptySet(),
    continuityGapMs: Long = POST_CREDIT_SCENE_CONTINUITY_GAP_MS,
    minimumOfferLeadTimeMs: Long = SKIP_CREDITS_TIMEOUT_MS
): PostCreditSceneTiming? {''',
        label="add minimum Skip Credits offer lead time",
    )

    replace_exact(
        post_play,
        r'''        if (index == 0) {
            val creditsStartMs = timing.creditsStartMs ?: return null
            return scene.takeIf { positionMs >= creditsStartMs }
        }

        val previousScene = scenes[index - 1]
        if (positionMs < previousScene.endMs) return null

        val gapMs = scene.startMs - previousScene.endMs
        return scene.takeIf {
            gapMs > continuityGapMs.coerceAtLeast(0L)
        }''',
        r'''        if (index == 0) {
            val creditsStartMs = timing.creditsStartMs ?: return null
            val gapMs = scene.startMs - creditsStartMs
            return scene.takeIf {
                positionMs >= creditsStartMs &&
                    gapMs > minimumOfferLeadTimeMs.coerceAtLeast(0L)
            }
        }

        val previousScene = scenes[index - 1]
        if (positionMs < previousScene.endMs) return null

        val gapMs = scene.startMs - previousScene.endMs
        return scene.takeIf {
            gapMs > continuityGapMs.coerceAtLeast(0L) &&
                gapMs > minimumOfferLeadTimeMs.coerceAtLeast(0L)
        }''',
        label="suppress Skip Credits when scene gap is <= button lifetime",
    )

    replace_exact(
        skip_credits,
        "\nprivate const val SKIP_CREDITS_TIMEOUT_MS = 10_000\n",
        "\n",
        label="remove duplicate Skip Credits timeout constant",
    )

    replace_exact(
        skip_credits,
        r'''                        (1f - progress.value) * SKIP_CREDITS_TIMEOUT_MS
                    ).toInt().coerceAtLeast(1),''',
        r'''                        (1f - progress.value) *
                            SKIP_CREDITS_TIMEOUT_MS.toFloat()
                    ).toInt().coerceAtLeast(1),''',
        label="use shared Skip Credits timeout in countdown",
    )

    replace_exact(
        post_credit_test,
        r'''    @Test
    fun `first post credit scene always gets skip opportunity`() {
        val first = PostCreditSceneTiming(
            startMs = 140_000L,
            endMs = 160_000L
        )

        assertEquals(
            first,
            postCreditSkipTarget(
                timing = timing(first),
                positionMs = 110_000L
            )
        )
    }''',
        r'''    @Test
    fun `first post credit scene gets skip opportunity when more than timeout away`() {
        val first = PostCreditSceneTiming(
            startMs = 140_000L,
            endMs = 160_000L
        )

        assertEquals(
            first,
            postCreditSkipTarget(
                timing = timing(first),
                positionMs = 110_000L
            )
        )
    }

    @Test
    fun `first post credit scene inside timeout never gets skip opportunity`() {
        val first = PostCreditSceneTiming(
            startMs = 109_999L,
            endMs = 130_000L
        )

        assertNull(
            postCreditSkipTarget(
                timing = timing(first),
                positionMs = 100_000L
            )
        )
    }

    @Test
    fun `first post credit scene exactly one timeout away never gets skip opportunity`() {
        val first = PostCreditSceneTiming(
            startMs = 110_000L,
            endMs = 130_000L
        )

        assertNull(
            postCreditSkipTarget(
                timing = timing(first),
                positionMs = 100_000L
            )
        )
    }

    @Test
    fun `first post credit scene one millisecond beyond timeout gets skip opportunity`() {
        val first = PostCreditSceneTiming(
            startMs = 110_001L,
            endMs = 130_000L
        )

        assertEquals(
            first,
            postCreditSkipTarget(
                timing = timing(first),
                positionMs = 100_000L
            )
        )
    }''',
        label="cover first-scene Skip Credits timeout boundary",
    )

    replace_exact(
        post_play_test,
        r'''    @Test
    fun `manual near end exit starts post play for a movie`() {''',
        r'''    @Test
    fun `true eof ignores transient background completion blockers`() {
        val state = PlayerUiState(
            playbackEnded = true,
            isPostPlayRecommendationLoading = true,
            creditTiming = CreditTimingUiState(
                status = CreditTimingStatus.RUNNING
            )
        )

        assertFalse(state.blocksNaturalCompletion)
    }

    @Test
    fun `transient completion blockers still block before eof`() {
        val state = PlayerUiState(
            playbackEnded = false,
            isPostPlayRecommendationLoading = true,
            creditTiming = CreditTimingUiState(
                status = CreditTimingStatus.RUNNING
            )
        )

        assertTrue(state.blocksNaturalCompletion)
    }

    @Test
    fun `visible post play still owns screen at eof`() {
        val state = PlayerUiState(
            playbackEnded = true,
            isPostPlayRecommendationVisible = true
        )

        assertTrue(state.blocksNaturalCompletion)
    }

    @Test
    fun `manual near end exit starts post play for a movie`() {''',
        label="cover natural EOF blocker behavior",
    )

    replace_exact(
        ui_state,
        r'''    val blocksNaturalCompletion: Boolean
        get() = isPostPlayRecommendationVisible ||
            isPostPlayRecommendationLoading ||
            creditTiming.status == CreditTimingStatus.RUNNING ||
            showRatingOverlay ||
            ratingOverlayDestination == RatingOverlayDestination.POST_PLAY''',
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
        label="do not let transient background work block true EOF",
    )

    replace_exact(
        runtime,
        r'''    fun stopAndRelease() {
        releasePlayer()
    }

    fun stopAndReleaseForUserExit() {
        releasePlayer(
            flushPlaybackState = true,
            creditBoundaryGraceMs = CREDIT_COMPLETION_EXIT_GRACE_MS
        )
    }''',
        r'''    private fun cancelTerminalPlaybackWork() {
        /*
         * These jobs belong to the playback session rather than to a single
         * ExoPlayer instance. Internal decoder/source rebuilds call
         * releasePlayer() directly and must NOT cancel them. Terminal exits,
         * including natural EOF, do cancel them so late analyzer/recommendation
         * results cannot mutate stale Player state after navigation.
         */
        creditAnalysisJob?.cancel()
        creditAnalysisJob = null
        recommendationLoadJob?.cancel()
        recommendationLoadJob = null
        recommendationMetadataJob?.cancel()
        recommendationMetadataJob = null
    }

    fun stopAndRelease() {
        cancelTerminalPlaybackWork()
        releasePlayer()
    }

    fun stopAndReleaseForUserExit() {
        cancelTerminalPlaybackWork()
        releasePlayer(
            flushPlaybackState = true,
            creditBoundaryGraceMs = CREDIT_COMPLETION_EXIT_GRACE_MS
        )
    }''',
        label="cancel credit/post-play work only on terminal player exit",
    )

    replace_exact(
        runtime,
        r'''    fun onCleared() {
        creditAnalysisJob?.cancel()
        recommendationLoadJob?.cancel()
        recommendationMetadataJob?.cancel()
        releasePlayer()
        mediaSourceFactory.shutdown()
        sourceChipErrorDismissJob?.cancel()
    }''',
        r'''    fun onCleared() {
        cancelTerminalPlaybackWork()
        releasePlayer()
        mediaSourceFactory.shutdown()
        sourceChipErrorDismissJob?.cancel()
    }''',
        label="reuse terminal playback cleanup on ViewModel clear",
    )

    require(snow, "val renderSampleCount = minOf(", label="snowbank render resampling")
    require(home_rows, "forcedContinueWatchingViewportIndex", label="CW pre-visible viewport target")
    require(
        home_content,
        "pendingRowFocus.suppressBringIntoView =\n                true",
        label="CW bring-into-view suppression",
    )
    require(
        post_play,
        "gapMs > minimumOfferLeadTimeMs.coerceAtLeast(0L)",
        label="Skip Credits strict greater-than timeout rule",
    )
    require(skip_credits, "SKIP_CREDITS_TIMEOUT_MS.toFloat()", label="shared Skip Credits countdown constant")
    require(ui_state, "!playbackEnded &&", label="EOF transient-block bypass")
    require(post_credit_test, "one millisecond beyond timeout", label="Skip Credits boundary tests")
    require(post_play_test, "true eof ignores transient background completion blockers", label="EOF blocker tests")
    require(runtime, "cancelTerminalPlaybackWork()", label="terminal EOF/release job cancellation")

    print()
    if CHANGED:
        print("Patch applied successfully to:")
        for path in CHANGED:
            print(f"  - {path.relative_to(ROOT)}")
    else:
        print("Patch already applied; no files changed.")

    print()
    print("Recommended verification:")
    print("  git diff --check")
    print("  ./gradlew :app:compileDebugKotlin")
    print("  ./gradlew :app:assembleDebug")


if __name__ == "__main__":
    main()
