package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateTo
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal data class XlVerticalMotion(val id: Int, val rowKey: String,
    val fromGap: Dp, val toGap: Dp, val scrollPx: Float)

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
        if (relocation == null || selected?.rowKey != relocation.rowKey) return@LaunchedEffect
        // A focus request issued while the old scroll is being cancelled can
        // finish without moving. Retry from the actual layout after release.
        try {
            relocation.previousJob?.join()
            withFrameNanos { }
            val info = list.layoutInfo
            val header = scene.headerHeights[selected.rowKey] ?: 40f * scene.density
            val gap = (scene.headroomValues[selected.rowKey]?.value ?: 0f) * scene.density
            val anchor = if (selected.isXl) scene.xlTop(selected.rowKey).value * scene.density + header
                else (scene.ordinaryTop.value + 40f) * scene.density
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
            scene.verticalJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                var completed = false
                try {
                    list.scroll(MutatePriority.Default) {
                        var previous = 0f
                        val fromPx = (active.fromGap.value * scene.density).roundToInt()
                        val toPx = (active.toGap.value * scene.density).roundToInt()
                        val visiblePx = active.scrollPx - (toPx - fromPx)
                        AnimationState(0f).animateTo(active.scrollPx, animationSpec = animation) {
                            if (scene.verticalMotion !== active) throw CancellationException("XL focus changed")
                            val travel = value.coerceIn(0f, active.scrollPx).roundToInt()
                            val gapPx = fromPx + (travel - visiblePx).roundToInt().coerceIn(0, toPx - fromPx)
                            scene.headroomValues[active.rowKey] = (gapPx / scene.density).dp
                            scrollBy(travel - previous)
                            previous = travel.toFloat()
                            if (travel >= visiblePx) scene.compactRowRested = true
                            // Match relocation's half-pixel stop instead of
                            // waiting through an invisible float-spring tail.
                            if (value >= active.scrollPx - 0.5f) cancelAnimation()
                        }
                        scene.headroomValues[active.rowKey] = active.toGap
                        scrollBy(active.scrollPx - previous)
                        scene.compactRowRested = true
                    }
                    completed = true
                } finally {
                    if (scene.verticalMotion === active) {
                        if (completed) scene.headroomValues[active.rowKey] = active.toGap
                        scene.headroomMoving[active.rowKey] = false
                        scene.verticalMotion = null
                        scene.verticalJob = null
                    }
                }
            }
        }
        onDispose {
            scene.cancelVerticalMotion()
            scene.verticalLauncher = null
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
                if (latestFastScrolling() || scene.verticalMotion != null) return 0f
                val selected = scene.selection
                val anchor = with(density) {
                    if (!hasXlRows) 40.dp.toPx()
                    else if (selected?.isXl == true) scene.xlTop(selected.rowKey).toPx() +
                        (scene.headerHeights[selected.rowKey] ?: 40.dp.toPx())
                    else ordinaryTop.toPx() + 40.dp.toPx()
                }
                return offset - anchor
            }
        }
    }
}
