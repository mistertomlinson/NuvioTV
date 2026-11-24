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
    val fixedReturnFollowerMeasurement: Boolean = false)
internal data class XlPreviewReturn(val id: Int, val rowKey: String, val xlKey: String)

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
        precedingXlIndex == firstOrdinaryIndex - 1) translationPx else 0f
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
                            AnimationState(0f).animateTo(active.scrollPx, animationSpec = animation) {
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
                                        val measuredPx = (gap.to.value * scene.density).roundToInt()
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
                                    scrollBy(travel - previous)
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
                            if (active.fixedReturnFollowerMeasurement) {
                                // The frozen reverse follower has always been
                                // measured at its final target. Release only
                                // its visual translation after the last scroll.
                                scene.fixedFollowerTranslationPx = 0f
                            } else if (scene.previewReturn?.rowKey != it.rowKey ||
                                scene.transition?.outgoingMetadataCleared != true) {
                                scene.headroomValues[it.rowKey] = it.to
                            }
                        }
                        active.landscapePrefix?.let { scene.landscapePreXlCompression[it.xlKey] = it.to }
                        scrollBy(active.scrollPx - previous)
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
                } finally {
                    if (scene.verticalMotion === active) {
                        if (active.fixedFollowerMeasurement || active.fixedReturnFollowerMeasurement) {
                            // A non-focus cancellation bypassing the explicit
                            // handoff must materialize the EXACT painted gap.
                            val key = if (active.fixedReturnFollowerMeasurement)
                                active.secondaryGap?.rowKey else active.rowKey
                            val finalGapPx = if (active.fixedReturnFollowerMeasurement)
                                ((active.secondaryGap?.to?.value ?: 0f) * scene.density).roundToInt()
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
            androidx.compose.animation.core.animate(start, finalTop, animationSpec = tween(360)) { top, _ ->
                if (scene.previewReturn?.id != preview.id) throw CancellationException("Preview destination changed")
                baseTop()?.let { base -> scene.headroomValues[preview.rowKey] = ((top - base) / scene.density).coerceAtLeast(0f).dp }
            }
            // If the longer XL entry is still moving, hold the visible preview
            // at its destination until the underlying row catches up. Do not
            // force its remaining gap to zero while the list is in flight.
            while (list.isScrollInProgress) {
                withFrameNanos { }
                if (scene.previewReturn?.id != preview.id) throw CancellationException("Preview destination changed")
                baseTop()?.let { base -> scene.headroomValues[preview.rowKey] = ((finalTop - base) / scene.density).coerceAtLeast(0f).dp }
            }
            scene.headroomValues[preview.rowKey] = 0.dp
        } finally {
            if (scene.previewReturn?.id == preview.id) {
                scene.headroomMoving[preview.rowKey] = false
                scene.previewReturn = null
            }
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
                if (latestFastScrolling() || scene.boundaryFocusArmed || scene.verticalMotion != null) return 0f
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
