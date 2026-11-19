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

internal fun xlRowTopInset(isXl: Boolean, ordinaryRowTop: Float): Float =
    if (isXl) 54f else ordinaryRowTop

/** The selector is 34dp high, inset 8dp from the top of Home. */
internal const val HOME_PLATFORM_SELECTOR_BOTTOM_DP = 42f

internal fun xlBalancedTop(screenHeight: Float, summaryRowHeight: Float, fullWidthIcons: Boolean): Float =
    if (fullWidthIcons) ((HOME_PLATFORM_SELECTOR_BOTTOM_DP + screenHeight - summaryRowHeight) / 2f).coerceAtLeast(54f)
    else 54f

/** Put the next hero just below the viewport, while letting the whole XL row clear it. */
internal fun xlFollowingHeadroom(screenHeight: Float, xlBottom: Float, ordinaryTop: Float,
    metadataTop: Float, headerCorrection: Float, clearXl: Boolean = true): Float {
    val previewGap = (screenHeight + 8f - xlBottom - 24f + ordinaryTop - metadataTop - headerCorrection).coerceAtLeast(0f)
    return if (clearXl) maxOf(ordinaryTop - 24f + 1f, previewGap) else previewGap
}

/** Keep a double Left from item 1 from becoming a sidebar-open at item 0. */
internal class XlRowLeftShortcut {
    private var lastReleaseAtMs = 0L
    private var pressStartedAtMs = 0L
    private var previousPressStartedAfterFirst = false
    private var currentPressStartedAfterFirst = false
    private var consumedDoublePress = false

    fun onDown(index: Int, repeatCount: Int, enabled: Boolean, nowMs: Long): Boolean {
        if (repeatCount != 0) {
            // A held press cannot arm the next fresh Left as a double tap.
            reset()
            return false
        }
        val elapsed = nowMs - lastReleaseAtMs
        val doublePress = enabled && previousPressStartedAfterFirst &&
            lastReleaseAtMs != 0L && elapsed in 0..HOME_DOUBLE_UP_GAP_MS &&
            nowMs - pressStartedAtMs in 0..250L
        pressStartedAtMs = nowMs
        currentPressStartedAfterFirst = index > 0
        consumedDoublePress = doublePress
        if (doublePress) lastReleaseAtMs = 0L
        return doublePress
    }

    fun onUp(nowMs: Long) {
        val quickTap = pressStartedAtMs > 0L && nowMs - pressStartedAtMs in 0..250L
        lastReleaseAtMs = if (consumedDoublePress || !quickTap) 0L else nowMs
        previousPressStartedAfterFirst = quickTap && currentPressStartedAfterFirst
        consumedDoublePress = false
    }

    fun reset() {
        lastReleaseAtMs = 0L
        pressStartedAtMs = 0L
        previousPressStartedAfterFirst = false
        currentPressStartedAfterFirst = false
        consumedDoublePress = false
    }
}

internal const val XL_RANK_OVERLAP_DP = 8f
internal const val XL_BACKDROP_DIM_ALPHA = 0.94f

internal fun xlRankFontSize(cardHeight: Float): Float = (cardHeight * 0.22f).coerceIn(40f, 56f)
internal fun xlRankBaselineDrop(fontSize: Float): Float = fontSize * 0.10f

/** Measured once per catalog length, not while navigating its cards. */
internal class XlRankTrack(
    private val portraitWidth: Float,
    val textWidths: FloatArray,
    val fontSize: Float = 0f
) {
    private val outside = FloatArray(textWidths.size) { (textWidths[it] - XL_RANK_OVERLAP_DP).coerceAtLeast(0f) }
    private val prefix = FloatArray(textWidths.size + 1).also { sums ->
        for (i in textWidths.indices) sums[i + 1] = sums[i] + portraitWidth + 12f + outside[i]
    }
    val featuredLeft: Float = 52f

    fun portraitLeft(index: Int, selected: Int, featuredWidth: Float): Float =
        portraitLeftAtOrigin(index, origin(selected), featuredWidth)

    fun origin(selected: Int): Float = prefix[(selected + 1).coerceIn(0, textWidths.size)]

    fun portraitLeftAtOrigin(index: Int, origin: Float, featuredWidth: Float): Float =
        featuredLeft + featuredWidth + 12f + prefix[index] - origin + outside[index]

    /** Binary search keeps even a long jump's composition bounded to the visible runway. */
    fun firstPortrait(origin: Float): Int {
        var low = 0
        var high = textWidths.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (prefix[mid] < origin) low = mid + 1 else high = mid
        }
        return (low - 1).coerceAtLeast(0)
    }

    fun numberLeft(index: Int, posterLeft: Float): Float = posterLeft - outside[index]
}

internal class XlHorizontalRepeatGate {
    private var lastMoveAtMs = 0L
    fun accept(repeatCount: Int, nowMs: Long, fast: Boolean): Boolean {
        val gap = if (fast) 48L else 80L
        if (repeatCount > 0 && nowMs - lastMoveAtMs < gap) return false
        lastMoveAtMs = nowMs
        return true
    }
}
