package com.nuvio.tv.ui.screens.home

import kotlin.math.roundToInt

/**
 * Haze 0.7.3 caches noise bitmaps by Float factor, but paints them with an
 * integer alpha. Give identical rendered opacities the same cache key rather
 * than decoding and allocating another bitmap on every fade frame.
 */
internal fun homeGlassNoiseFactor(visibilityProgress: Float): Float {
    val factor = 0.025f * visibilityProgress.coerceIn(0f, 1f)
    // Match Haze's cutoff before quantizing. Alpha 1 needs a factor of at
    // least 0.005: 1 / 255 would incorrectly switch noise off in that library.
    if (factor < 0.005f) return 0f
    return ((factor * 255f).roundToInt() / 255f).coerceAtLeast(0.005f)
}
