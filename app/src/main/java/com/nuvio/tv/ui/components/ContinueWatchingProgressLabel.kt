package com.nuvio.tv.ui.components

import com.nuvio.tv.domain.model.WatchProgress
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

internal fun formatContinueWatchingProgressLabel(
    progress: WatchProgress,
    resumeLabel: String,
    percentWatchedLabel: String,
    hoursMinLeftLabel: String,
    minLeftLabel: String
): String {
    val isSentinel = progress.position == 1L && progress.duration == 1L
    if (progress.duration <= 0L || isSentinel) {
        val pct = progress.progressPercent
        if (pct != null && pct > 0f && pct < 100f) {
            // Estimate remaining time from percent if we have no real duration
            // Show resume label as fallback — never show % to the user
            return resumeLabel
        }
        return resumeLabel
    }

    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(progress.remainingTime)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60

    return when {
        hours > 0 -> hoursMinLeftLabel.format(hours, minutes)
        else -> minLeftLabel.format(totalMinutes.coerceAtLeast(1))
    }
}
