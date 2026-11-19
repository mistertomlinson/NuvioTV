package com.nuvio.tv.ui.screens.home

/** Layout math kept independent of composition and focus callbacks. */
internal data class XlRowGeometry(
    val cardHeight: Float,
    val featuredWidth: Float,
    val portraitWidth: Float,
    val visiblePortraitCount: Int
)

internal fun xlRowGeometry(screenWidth: Float, screenHeight: Float, largeMetadata: Boolean): XlRowGeometry {
    val metadataReserve = if (largeMetadata) 178f else 142f
    val availableHeight = (screenHeight - 54f - 40f - metadataReserve - 24f).coerceAtLeast(96f)
    val height = minOf(screenHeight * 0.48f, screenWidth * 0.55f * 9f / 16f, availableHeight)
    val featured = height * 16f / 9f
    val portrait = height * 2f / 3f
    val remaining = (screenWidth - 52f - featured - 12f).coerceAtLeast(0f)
    return XlRowGeometry(height, featured, portrait, kotlin.math.ceil(remaining / (portrait + 12f)).toInt() + 1)
}

/** The selected card stays at the leading edge while upcoming portraits move one slot. */
internal fun xlCardOffset(index: Int, selected: Int, featuredWidth: Float, portraitWidth: Float): Float = when {
    index < selected -> (index - selected) * (portraitWidth + 12f)
    index == selected -> 0f
    else -> featuredWidth + 12f + (index - selected - 1) * (portraitWidth + 12f)
}

/** Compose one predecessor for Left and a bounded runway for Right, even at the end. */
internal fun xlVisibleIndices(selected: Int, total: Int, portraitCount: Int): IntRange =
    if (total <= 0) IntRange.EMPTY else
        (selected - 1).coerceAtLeast(0)..minOf(total - 1, selected + portraitCount)

internal fun xlRowTopInset(isXl: Boolean, ordinaryRowTop: Float): Float =
    if (isXl) 54f else ordinaryRowTop

/** Keep a double Left from item 1 from becoming a sidebar-open at item 0. */
internal class XlRowLeftShortcut {
    private var lastReleaseAtMs = 0L
    private var previousPressStartedAfterFirst = false
    private var currentPressStartedAfterFirst = false
    private var consumedDoublePress = false

    fun onDown(index: Int, repeatCount: Int, enabled: Boolean, nowMs: Long): Boolean {
        if (repeatCount != 0) return false
        val elapsed = nowMs - lastReleaseAtMs
        val doublePress = enabled && previousPressStartedAfterFirst &&
            lastReleaseAtMs != 0L && elapsed in 0 until 300L
        currentPressStartedAfterFirst = index > 0
        consumedDoublePress = doublePress
        if (doublePress) lastReleaseAtMs = 0L
        return doublePress
    }

    fun onUp(nowMs: Long) {
        lastReleaseAtMs = if (consumedDoublePress) 0L else nowMs
        previousPressStartedAfterFirst = currentPressStartedAfterFirst
        consumedDoublePress = false
    }

    fun reset() {
        lastReleaseAtMs = 0L
        previousPressStartedAfterFirst = false
        currentPressStartedAfterFirst = false
        consumedDoublePress = false
    }
}
