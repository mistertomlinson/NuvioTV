package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * All distances are physical pixels. Structural offsets anticipate the
 * one-time spacer commit. Virtual clearance pushes previously visible rows
 * above the viewport without changing any of the selected row's geometry.
 */
internal data class XlLandingPaintPlan(
    val offsets: Map<String, Float>,
    val virtualClearancePx: Float
)

internal fun xlLandingPaintPlan(
    keys: List<String>,
    gapsPx: List<Float>,
    selectedIndex: Int,
    compressedFollowingIndex: Int?,
    ordinaryDestination: Boolean,
    predecessorBottomAtCompactAnchorPx: Float,
    overscanPx: Float
): XlLandingPaintPlan {
    require(keys.size == gapsPx.size && selectedIndex in keys.indices)
    val anchorPrefix = gapsPx.take(selectedIndex + 1).sum()
    var cumulative = 0f
    var compressed = 0f
    val structural = gapsPx.mapIndexed { i, gap ->
        cumulative += gap
        if (i == compressedFollowingIndex) compressed += gap
        cumulative - anchorPrefix - compressed
    }
    val clearance = if (ordinaryDestination && selectedIndex > 0)
        (predecessorBottomAtCompactAnchorPx + structural[selectedIndex - 1] + overscanPx)
            .coerceAtLeast(0f) else 0f
    val offsets = keys.mapIndexedNotNull { i, key ->
        val delta = structural[i] - if (i < selectedIndex) clearance else 0f
        if (delta != 0f) key to delta else null
    }.toMap()
    return XlLandingPaintPlan(offsets, clearance)
}


/** A single immutable painted frame: the ordinary metadata and its mask
 *  always share a displacement, including a direction reversal. */
internal data class XlRetainedPresentation(
    val metadata: XlMetadataSnapshot?,
    val epoch: Int,
    val translation: Float,
    val alpha: Float,
    val clipTop: Float,
    val mask: Pair<Float, Float>,
    val originRowIndex: Int = -1
) {
    // Use the XL's PAINTED bounds, not logical focus or accumulated scroll
    // distance. The same correction moves both metadata and its feather.
    fun outsideXl(xlTop: Float, xlBottom: Float, xlAbove: Boolean,
        viewportPx: Float, featherPx: Float): XlRetainedPresentation? {
        val shift = if (xlAbove) (xlBottom - mask.first).coerceAtLeast(0f)
            else (xlTop - featherPx - mask.second).coerceAtMost(0f)
        return moved(-shift, viewportPx, featherPx)
    }

    fun moved(consumedPx: Float, viewportPx: Float, featherPx: Float): XlRetainedPresentation? {
        val top = mask.first - consumedPx
        val bottom = mask.second - consumedPx
        if (bottom + featherPx <= 0f || top >= viewportPx) return null
        return copy(translation = translation - consumedPx, mask = top to bottom)
    }
}

/**
 * The rapid navigation presentation has exactly one outgoing owner.
 * Capture once on takeover; every subsequent delta translates the same
 * painted frame. An intermediate destination cannot replace that frame.
 */
internal class XlNavigationPresentation {
    var identity by androidx.compose.runtime.mutableStateOf<XlRetainedPresentation?>(null)
        private set
    private var travel by androidx.compose.runtime.mutableFloatStateOf(0f)
    val outgoing: XlRetainedPresentation?
        get() = identity?.let { frame ->
            frame.copy(translation = frame.translation - travel,
                mask = (frame.mask.first - travel) to (frame.mask.second - travel))
        }

    fun capture(frame: XlRetainedPresentation?) {
        if (identity == null && frame != null) {
            travel = 0f
            identity = frame
        }
    }

    fun advance(consumedPx: Float, viewportPx: Float, featherPx: Float) {
        val frame = identity ?: return
        if (consumedPx == 0f) return
        val next = travel + consumedPx
        if (frame.mask.second - next + featherPx <= 0f || frame.mask.first - next >= viewportPx)
            clear()
        else travel = next
    }

    fun clear() {
        identity = null
        travel = 0f
    }
}


/** A heading can paint inside the viewport while its lazy item's raw
 *  geometry is still below it. The fallback owns only that title strip. */
internal fun xlMissingTitlePeekTop(rawTop: Float, translation: Float, viewport: Float): Float? {
    if (!rawTop.isFinite() || !translation.isFinite() || viewport <= 0f) return null
    val top = rawTop + translation
    return top.takeIf { rawTop >= viewport && it < viewport && it >= viewport * 0.75f }
}
