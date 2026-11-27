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
    val mask: Pair<Float, Float>
) {
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
    var outgoing by androidx.compose.runtime.mutableStateOf<XlRetainedPresentation?>(null)
        private set

    fun capture(frame: XlRetainedPresentation?) {
        if (outgoing == null) outgoing = frame
    }

    fun advance(consumedPx: Float, viewportPx: Float, featherPx: Float) {
        if (consumedPx != 0f) outgoing = outgoing?.moved(consumedPx, viewportPx, featherPx)
    }

    fun clear() { outgoing = null }
}
