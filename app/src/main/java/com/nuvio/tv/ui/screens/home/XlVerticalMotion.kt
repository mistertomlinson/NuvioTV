package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class XlGapMotion(val rowKey: String, val from: Dp, val to: Dp)
internal data class XlVerticalMotion(val id: Int, val rowKey: String,
    val fromGap: Dp, val toGap: Dp, val scrollPx: Float, val compact: Boolean = true,
    val secondaryGap: XlGapMotion? = null, val ordinaryStridePx: Float = 264f)
internal data class XlPreviewReturn(val id: Int, val rowKey: String, val xlKey: String)

internal data class XlInterruptedRelocation(val id: Int, val rowKey: String, val previousJob: kotlinx.coroutines.Job?)

/** Travel together until the next row lands, then let XL continue past it. */
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
            val header = scene.headerHeights[selected.rowKey] ?: 40f * scene.density
            val gap = (scene.headroomValues[selected.rowKey]?.value ?: 0f) * scene.density
            val anchor = if (selected.isXl) scene.xlTop(selected.rowKey).value * scene.density + header
                else scene.ordinaryTop.value * scene.density + scene.ordinaryPosterInset(selected.rowKey, selected.rowIndex)
            val owner = info.visibleItemsInfo.firstOrNull { it.key == selected.rowKey }
            if (owner != null) {
                val position = owner.offset + info.beforeContentPadding + gap + header
                list.animateScrollBy(position - anchor, animation)
            } else if (info.totalItemsCount == scene.rowCount && selected.rowIndex in 0 until info.totalItemsCount) {
                // Focus beyond the viewport may have recreated a recycled row.
                // Use its index only while the full row ordering is present.
                list.animateScrollToItem(selected.rowIndex, (info.beforeContentPadding - anchor + header + gap).roundToInt())
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
                    list.scroll(MutatePriority.PreventUserInput) {
                        var previous = 0f
                        val fromPx = (active.fromGap.value * scene.density).roundToInt()
                        val toPx = (active.toGap.value * scene.density).roundToInt()
                        val visiblePx = active.scrollPx - (toPx - fromPx)
                        // Derive the travel time from the ordinary spring's
                        // half-pixel stop. A stretched spring front-loads too
                        // much XL movement; use an eased, distance-paced curve
                        // so its visible exit does not race into a hidden tail.
                        val reference = minOf(abs(active.scrollPx), active.ordinaryStridePx).coerceAtLeast(1f)
                        val paceSpec = (animation as? SpringSpec<Float>)?.let {
                            SpringSpec(it.dampingRatio, it.stiffness, 0.5f)
                        } ?: animation
                        val referenceNanos = TargetBasedAnimation(paceSpec, Float.VectorConverter, 0f, reference).durationNanos
                        val durationMillis = ((referenceNanos / 1_000_000f) *
                            (abs(active.scrollPx) / reference).coerceAtLeast(1f)).roundToInt().coerceAtLeast(1)
                        AnimationState(0f).animateTo(active.scrollPx,
                            animationSpec = tween(durationMillis, easing = FastOutSlowInEasing)) {
                            if (scene.verticalMotion !== active) throw CancellationException("XL focus changed")
                            val fraction = (value / active.scrollPx).coerceIn(0f, 1f)
                            val travel = (active.scrollPx * fraction).roundToInt()
                            val gapPx = if (active.compact) fromPx +
                                (travel - visiblePx).roundToInt().coerceIn(0, (toPx - fromPx).coerceAtLeast(0))
                                else (fromPx + (toPx - fromPx) * fraction).roundToInt()
                            scene.headroomValues[active.rowKey] = (gapPx / scene.density).dp
                            active.secondaryGap?.let { gap ->
                                scene.headroomValues[gap.rowKey] = (gap.from.value + (gap.to.value - gap.from.value) * fraction).dp
                            }
                            scrollBy(travel - previous)
                            previous = travel.toFloat()
                            if (active.compact && travel >= visiblePx) scene.compactRowRested = true
                            if (abs(active.scrollPx - value) < 0.5f) cancelAnimation()
                        }
                        scene.headroomValues[active.rowKey] = active.toGap
                        active.secondaryGap?.let { scene.headroomValues[it.rowKey] = it.to }
                        scrollBy(active.scrollPx - previous)
                        scene.compactRowRested = true
                    }
                    completed = true
                } finally {
                    if (scene.verticalMotion === active) {
                        if (completed) scene.headroomValues[active.rowKey] = active.toGap
                        scene.headroomMoving[active.rowKey] = false
                        active.secondaryGap?.let { scene.headroomMoving[it.rowKey] = false }
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
                    else if (selected?.isXl == true) scene.xlTop(selected.rowKey).toPx() +
                        (scene.headerHeights[selected.rowKey] ?: 40.dp.toPx())
                    else ordinaryTop.toPx() + (selected?.let { scene.ordinaryPosterInset(it.rowKey, it.rowIndex, density.density) } ?: 40.dp.toPx())
                }
                return offset - anchor
            }
        }
    }
}
