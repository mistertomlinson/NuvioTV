package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val XL_SPRING_TIME_SCALE = 1.10f
internal const val XL_PREVIEW_RETURN_MS = 260

// Keep a meaningful amount of the shared leg moving beyond the 250ms
// input gate; the separation leg starts from zero velocity. No independent
// short-distance spring is allowed to reach rest before separation starts.
internal fun xlCoupledSharedDurationMs(scale: Float): Float =
    if (scale <= 0f) 0f else XL_EXIT_SEPARATION_DELAY_MS.toFloat() + 200f * scale

internal fun xlCoupledSplitDurationMs(scale: Float): Float =
    if (scale <= 0f) 0f else 280f * scale

internal fun xlCoupledSharedProgress(elapsedMs: Float, durationMs: Float): Float {
    if (durationMs <= 0f) return 1f
    val t = (elapsedMs / durationMs).coerceIn(0f, 1f)
    // Nonzero launch velocity, continuous acceleration, zero landing velocity.
    return t + t * t * (1f - t)
}

internal fun xlCoupledSplitProgress(elapsedMs: Float, durationMs: Float): Float {
    if (durationMs <= 0f) return 1f
    val t = (elapsedMs / durationMs).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

internal data class XlScrollItemAnchor(val index: Int, val scrollOffset: Int)

internal data class XlGapMotion(val rowKey: String, val from: Dp, val to: Dp)
/** Draw-only, same-clock fraction for the prefix preceding an XL row. */
internal data class XlLandscapePrefixMotion(val xlKey: String, val from: Float, val to: Float,
    val portraitPrefix: Boolean = false)
internal data class XlVerticalMotion(val id: Int, val rowKey: String,
    val fromGap: Dp, val toGap: Dp, val scrollPx: Float, val compact: Boolean = true,
    val secondaryGap: XlGapMotion? = null, val itemAnchor: XlScrollItemAnchor? = null,
    val fixedFollowerMeasurement: Boolean = false,
    val followerEndExclusiveIndex: Int = Int.MAX_VALUE,
    val landscapePrefix: XlLandscapePrefixMotion? = null,
    val fixedPrefixMeasurement: Boolean = false,
    // An interrupted compact DOWN exit reuses its measured follower gap
    // while the UP spring restores its existing paint-only offset to zero.
    val fixedReturnFollowerMeasurement: Boolean = false,
    val coupledExit: Boolean = false)
internal data class XlPreviewReturn(val id: Int, val rowKey: String, val xlKey: String, val rowIndex: Int)

internal data class XlInterruptedRelocation(val id: Int, val rowKey: String, val previousJob: kotlinx.coroutines.Job?)

/** The exact difference between the measured final gap and its animated visual size. */
internal fun xlFixedFollowerOffsetPx(animatedGapPx: Int, fixedGapPx: Int): Float =
    (animatedGapPx - fixedGapPx).toFloat()

/** Apply the same paint offset to every ordinary row following the exiting XL. */
internal fun xlFixedOrdinarySuffixTranslationPx(
    motion: XlVerticalMotion?, rowIndex: Int, precedingXlIndex: Int, translationPx: Float
): Float {
    if (motion?.fixedFollowerMeasurement != true &&
        motion?.fixedReturnFollowerMeasurement != true) return 0f
    val firstOrdinaryIndex = motion.itemAnchor?.index ?: return 0f
    return if (rowIndex >= firstOrdinaryIndex && rowIndex < motion.followerEndExclusiveIndex &&
        (motion.coupledExit || precedingXlIndex == firstOrdinaryIndex - 1)) translationPx else 0f
}

/** Share one spring clock across XL travel and the shorter incoming-row leg. */
@OptIn(ExperimentalFoundationApi::class)
@Suppress("DEPRECATION")
@Composable
internal fun AnimateXlVerticalMotion(scene: XlSceneState, list: LazyListState) {
    val scope = rememberCoroutineScope()
    val animation by rememberUpdatedState(LocalBringIntoViewSpec.current.scrollAnimationSpec)
    val relocation = scene.interruptedRelocation
    val selected = scene.selection
    LaunchedEffect(scene, list, relocation, selected?.rowKey) {
        if (relocation == null || selected?.rowKey != relocation.rowKey || scene.verticalMotion != null) return@LaunchedEffect
        // A focus request issued while the old scroll is being cancelled can
        // finish without moving. Retry from the actual layout after release.
        try {
            relocation.previousJob?.join()
            withFrameNanos { }
            val info = list.layoutInfo
            val header = scene.headerPx(selected.rowKey)
            val gap = scene.gapPx(selected.rowKey)
            val anchor = scene.posterAnchorPx(selected)
            val owner = info.visibleItemsInfo.firstOrNull { it.key == selected.rowKey }
            if (owner != null) {
                val position = owner.offset + info.beforeContentPadding + gap + header
                list.animateScrollBy((position - anchor).toFloat(), animation)
            } else if (info.totalItemsCount == scene.rowCount && selected.rowIndex in 0 until info.totalItemsCount) {
                // Focus beyond the viewport may have recreated a recycled row.
                // Use its index only while the full row ordering is present.
                list.animateScrollToItem(selected.rowIndex, info.beforeContentPadding - anchor + header + gap)
            }
        } finally {
            if (scene.interruptedRelocation === relocation) scene.interruptedRelocation = null
        }
    }
    DisposableEffect(scene, list, scope) {
        scene.verticalLauncher = { active ->
            // Let the focus callback finish before acquiring the one scroll owner.
            scene.verticalJob = scope.launch {
                var completed = false
                try {
                    val focusPaint = scene.requestFocusPaint(active.id)
                    list.scroll(MutatePriority.PreventUserInput) {
                        coroutineScope {
                            // Rendering, layout and focus publish before motion.
                            // A detached/offscreen surface must never block input.
                            val fallback = launch {
                                repeat(2) { withFrameNanos { } }
                                focusPaint.complete(Unit)
                            }
                            try { focusPaint.await() } finally { fallback.cancel() }
                        }
                        var previous = 0f
                        var velocityFrame = Long.MIN_VALUE
                        fun recordDelta(delta: Float, frame: Long) {
                            if (velocityFrame != Long.MIN_VALUE && frame > velocityFrame) {
                                val dt = (frame - velocityFrame) / 1_000_000_000f
                                scene.recordVerticalVelocity(delta / dt)
                            }
                            velocityFrame = frame
                        }
                        val fromPx = (active.fromGap.value * scene.density).roundToInt()
                        val toPx = (active.toGap.value * scene.density).roundToInt()
                        val visiblePx = active.scrollPx - (toPx - fromPx)
                        // Use the exact ordinary-row spring at the actual
                        // travel distance. Stretch only this scroll animation
                        // by 10%; metadata and preview clocks remain independent.
                        val systemScale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
                        withContext(object : MotionDurationScale {
                            override val scaleFactor = systemScale * XL_SPRING_TIME_SCALE
                        }) {
                            if (active.coupledExit && active.fixedFollowerMeasurement && active.compact) {
                                // The outgoing XL and all of its followers share the
                                // incoming travel. A second spring adds separation
                                // only after the input gate has accepted a single tap.
                                // Both components are evaluated by one frame clock
                                // and one scroll owner; no second relocation.
                                val scale = systemScale * XL_SPRING_TIME_SCALE
                                // A short spring for the incoming leg can run out
                                // of visible pixels before the gate opens. Reserve
                                // enough shared travel for both legs to overlap.
                                val sharedDuration = xlCoupledSharedDurationMs(scale)
                                val splitDuration = xlCoupledSplitDurationMs(scale)
                                var start = Long.MIN_VALUE
                                var splitStart = Long.MIN_VALUE
                                while (true) {
                                    val now = withFrameNanos { it }
                                    if (scene.verticalMotion !== active) throw CancellationException("XL focus changed")
                                    if (start == Long.MIN_VALUE) start = now
                                    if (!scene.deferredDownwardExit && splitStart == Long.MIN_VALUE) splitStart = now
                                    val elapsed = (now - start) / 1_000_000f
                                    val splitElapsed = if (splitStart == Long.MIN_VALUE) 0f else
                                        (now - splitStart) / 1_000_000f
                                    val incoming = visiblePx * xlCoupledSharedProgress(elapsed, sharedDuration)
                                    val extra = if (splitStart == Long.MIN_VALUE) 0f else
                                        (toPx - fromPx) * xlCoupledSplitProgress(splitElapsed, splitDuration)
                                    val travel = incoming + extra
                                    // Keep scroll and visual separation in the
                                    // same physical-pixel coordinate system.
                                    scene.fixedFollowerTranslationPx = fromPx + extra - toPx
                                    val consumed = scrollBy(travel - previous)
                                    recordDelta(consumed, now)
                                    previous = travel
                                    if (splitStart != Long.MIN_VALUE && elapsed >= sharedDuration &&
                                        splitElapsed >= splitDuration) break
                                }
                                scene.compactRowRested = true
                            } else AnimationState(0f).animateTo(active.scrollPx, animationSpec = animation) {
                                if (scene.verticalMotion !== active) throw CancellationException("XL focus changed")
                                val fraction = (value / active.scrollPx).coerceIn(0f, 1f)
                                val travel = (active.scrollPx * fraction).roundToInt()
                                val incomingTravel = (visiblePx * fraction).roundToInt()
                                // The short incoming leg has its own spring range
                                // on this same clock, rather than hitting a clamp.
                                val gapPx = if (active.compact) fromPx + travel - incomingTravel
                                    else (fromPx + (toPx - fromPx) * fraction).roundToInt()
                                if (active.fixedFollowerMeasurement) {
                                    // Neither LazyColumn's item sizes nor its anchor
                                    // change during the spring. The follower's original
                                    // painted path is reconstructed from the virtual gap.
                                    Snapshot.withMutableSnapshot {
                                        if (scene.headroomValues[active.rowKey] != active.toGap)
                                            scene.headroomValues[active.rowKey] = active.toGap
                                        scene.fixedFollowerTranslationPx =
                                            xlFixedFollowerOffsetPx(gapPx, toPx)
                                    }
                                } else if (!active.fixedPrefixMeasurement) scene.headroomValues[active.rowKey] =
                                    (gapPx / scene.density).dp
                                active.secondaryGap?.let { gap ->
                                    if (active.fixedReturnFollowerMeasurement) {
                                        val paintedPx = ((gap.from.value +
                                            (gap.to.value - gap.from.value) * fraction) * scene.density).roundToInt()
                                        // The physical follower stays at its
                                        // original expanded measurement until
                                        // the final frame. The return target
                                        // (zero) is only its VIRTUAL painted
                                        // clearance, not its measured height.
                                        val measuredPx = scene.gapPx(gap.rowKey)
                                        scene.fixedFollowerTranslationPx = (paintedPx - measuredPx).toFloat()
                                    } else if (scene.previewReturn?.rowKey != gap.rowKey ||
                                        scene.transition?.outgoingMetadataCleared != true) {
                                        scene.headroomValues[gap.rowKey] =
                                            (gap.from.value + (gap.to.value - gap.from.value) * fraction).dp
                                    }
                                }
                                // One existing spring drives the draw-only XL
                                // prefix as well; never resize its measured gap.
                                active.landscapePrefix?.let { gap ->
                                    scene.landscapePreXlCompression[gap.xlKey] =
                                        gap.from + (gap.to - gap.from) * fraction
                                }
                                // Make the scroll owner distinguishable from
                                // headroom measure and metadata subcompose in traces.
                                val trace = android.os.Build.VERSION.SDK_INT >= 29 && android.os.Trace.isEnabled()
                                if (trace) android.os.Trace.beginSection("NuvioXL scrollBy")
                                try {
                                    val requested = travel - previous
                                    val consumed = scrollBy(requested)
                                    recordDelta(consumed, lastFrameTimeNanos)
                                    XlNavigationTrace.event("SPRING_STEP") { "id=${active.id} row=${active.rowKey} requested=$requested consumed=$consumed fraction=$fraction virtualGapPx=$gapPx" }
                                } finally {
                                    if (trace) android.os.Trace.endSection()
                                }
                                previous = travel.toFloat()
                                if (active.compact && incomingTravel == visiblePx.roundToInt()) scene.compactRowRested = true
                                if (abs(active.scrollPx - value) < 0.5f) cancelAnimation()
                            }
                        }
                        Snapshot.withMutableSnapshot {
                            if (!active.fixedPrefixMeasurement)
                                scene.headroomValues[active.rowKey] = active.toGap
                            if (active.fixedFollowerMeasurement)
                                scene.fixedFollowerTranslationPx = 0f
                        }
                        active.secondaryGap?.let {
                            if (!active.fixedReturnFollowerMeasurement &&
                                (scene.previewReturn?.rowKey != it.rowKey ||
                                    scene.transition?.outgoingMetadataCleared != true)) {
                                scene.headroomValues[it.rowKey] = it.to
                            }
                        }
                        active.landscapePrefix?.let { scene.landscapePreXlCompression[it.xlKey] = it.to }
                        scrollBy(active.scrollPx - previous)
                        // After the final scroll delta, swap the fixed
                        // measurement for the compact resting gap (zero) and
                        // release its equal-and-opposite paint offset in ONE
                        // snapshot. The follower must not travel to an
                        // expanded offscreen gap and then snap back onscreen.
                        if (active.fixedReturnFollowerMeasurement) {
                            val resting = checkNotNull(active.secondaryGap)
                            Snapshot.withMutableSnapshot {
                                scene.headroomValues[resting.rowKey] = resting.to
                                scene.heldHeadroomGoals[resting.rowKey] = resting.to
                                scene.fixedFollowerTranslationPx = 0f
                                scene.expandedXlRows.remove(active.rowKey)
                            }
                        }
                        // The portrait title peek needs a real, compact measured
                        // item when settled. Release the full clearance once
                        // (not on each animation frame). Its -clearance paint
                        // offset disappears in the SAME snapshot, preserving
                        // the visible row position at the handoff.
                        active.landscapePrefix?.takeIf {
                            it.portraitPrefix && it.to >= 1f
                        }?.let { gap ->
                            Snapshot.withMutableSnapshot {
                                scene.headroomValues[gap.xlKey] = 0.dp
                                scene.landscapePreXlCompression[gap.xlKey] = 1f
                            }
                        }
                        scene.compactRowRested = true
                    }
                    completed = true
                    scene.recordVerticalVelocity(0f)
                } finally {
                    if (scene.verticalMotion === active) {
                        if (active.fixedFollowerMeasurement || active.fixedReturnFollowerMeasurement) {
                            // A non-focus cancellation bypassing the explicit
                            // handoff must materialize the EXACT painted gap.
                            val key = if (active.fixedReturnFollowerMeasurement)
                                active.secondaryGap?.rowKey else active.rowKey
                            val finalGapPx = if (active.fixedReturnFollowerMeasurement)
                                key?.let { scene.gapPx(it) } ?: 0
                                else (active.toGap.value * scene.density).roundToInt()
                            Snapshot.withMutableSnapshot {
                                if (!completed && key != null) {
                                    val paintedPx = finalGapPx + scene.fixedFollowerTranslationPx.roundToInt()
                                    scene.headroomValues[key] =
                                        (paintedPx.coerceAtLeast(0) / scene.density).dp
                                }
                                scene.fixedFollowerTranslationPx = 0f
                            }
                        }
                        if (completed && !active.fixedPrefixMeasurement)
                            scene.headroomValues[active.rowKey] = active.toGap
                        scene.headroomMoving[active.rowKey] = false
                        active.secondaryGap?.let {
                            if (scene.previewReturn?.rowKey != it.rowKey) scene.headroomMoving[it.rowKey] = false
                        }
                        scene.verticalMotion = null
                        scene.verticalJob = null
                    }
                }
            }
        }
        scene.launchConfirmedMotion()
        onDispose {
            scene.cancelVerticalMotion()
            scene.verticalLauncher = null
        }
    }
}

/** The preview clock outlives a recycled LazyColumn row. */
@Composable
internal fun AnimateXlPreviewReturn(scene: XlSceneState, list: LazyListState) {
    val preview = scene.previewReturn
    LaunchedEffect(scene, list, preview?.id) {
        if (preview == null) return@LaunchedEffect
        fun baseTop(): Float? {
            val info = list.layoutInfo
            info.visibleItemsInfo.firstOrNull { it.key == preview.rowKey }?.let {
                return (it.offset + info.beforeContentPadding).toFloat()
            }
            return info.visibleItemsInfo.firstOrNull { it.key == preview.xlKey }?.let {
                (it.offset + info.beforeContentPadding + it.size + (24f * scene.density)).toFloat()
            }
        }
        scene.headroomMoving[preview.rowKey] = true
        try {
            // Keep the outgoing row attached to its metadata until that scene
            // clears. Closing this gap behind its mask consumes the visible
            // animation and reveals a preview that has already landed.
            androidx.compose.runtime.snapshotFlow { scene.outgoingMetadataOffscreen(list.layoutInfo) }.first { it }
            scene.releasePreviewGap(preview.id)
            val finalTop = (scene.xlTop(preview.xlKey).value +
                (scene.rowHeights[preview.xlKey] ?: scene.estimatedXlHeight).value + 24f) * scene.density
            val currentTop = baseTop()?.plus((scene.headroomValues[preview.rowKey]?.value ?: 0f) * scene.density)
                ?: (scene.screenHeight.value + 8f) * scene.density
            val start = currentTop.coerceAtMost((scene.screenHeight.value + 8f) * scene.density)
            androidx.compose.animation.core.animate(start, finalTop, animationSpec = tween(XL_PREVIEW_RETURN_MS)) { top, _ ->
                if (scene.previewReturn?.id != preview.id) throw CancellationException("Preview destination changed")
                baseTop()?.let { base ->
                    scene.previewReturnPaintOffsetPx = (top - base).coerceAtLeast(0f) - scene.gapPx(preview.rowKey)
                }
            }
            // If the longer XL entry is still moving, hold the visible preview
            // at its destination until the underlying row catches up. Do not
            // force its remaining gap to zero while the list is in flight.
            while (list.isScrollInProgress) {
                withFrameNanos { }
                if (scene.previewReturn?.id != preview.id) throw CancellationException("Preview destination changed")
                baseTop()?.let { base ->
                    scene.previewReturnPaintOffsetPx = (finalTop - base).coerceAtLeast(0f) - scene.gapPx(preview.rowKey)
                }
            }
            if (scene.previewReturn?.id == preview.id) Snapshot.withMutableSnapshot {
                scene.headroomValues[preview.rowKey] = 0.dp
                scene.previewReturnPaintOffsetPx = 0f
                scene.headroomMoving[preview.rowKey] = false
                scene.previewReturn = null
            }
        } finally {
            if (scene.previewReturn?.id == preview.id) scene.cancelPreviewReturn()
        }
    }
}

/** Read focus at relocation time instead of waiting for the parent composition. */
@OptIn(ExperimentalFoundationApi::class)
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
@Composable
internal fun rememberHomeVerticalScrollSpec(scene: XlSceneState, hasXlRows: Boolean,
    ordinaryTop: Dp, density: Density, default: BringIntoViewSpec,
    fastScrolling: () -> Boolean): BringIntoViewSpec {
    val latestFastScrolling by rememberUpdatedState(fastScrolling)
    return remember(scene, hasXlRows, ordinaryTop, density, default) {
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: AnimationSpec<Float> = default.scrollAnimationSpec
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                if (latestFastScrolling() || scene.deferredDownwardExit || scene.boundaryFocusArmed || scene.verticalMotion != null ||
                    (scene.continuousVerticalNavigation && (!scene.continuousGapsCollapsed || scene.continuousHeldScroll))) return 0f
                val selected = scene.selection
                val anchor = with(density) {
                    if (!hasXlRows) 40.dp.toPx()
                    else selected?.let { scene.posterAnchorPx(it, density.density, ordinaryTop.toPx()).toFloat() }
                        ?: (ordinaryTop.toPx() + 40.dp.toPx()).roundToInt().toFloat()
                }
                return offset - anchor
            }
        }
    }
}
