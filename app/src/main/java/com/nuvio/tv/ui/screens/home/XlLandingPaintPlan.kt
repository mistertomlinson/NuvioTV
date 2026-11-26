package com.nuvio.tv.ui.screens.home

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
