package com.nuvio.tv.ui.screens.home

/**
 * Returns persisted watched-series badge IDs that are conclusively stale.
 *
 * An empty watched-history projection is meaningful only after the active
 * provider has completed its initial load. Before that point it is an
 * inconclusive loading state and must not clear badges restored from disk.
 */
internal fun authoritativeMissingWatchedSeriesBadgeIds(
    currentBadgeIds: Set<String>,
    watchedHistoryIds: Set<String>,
    historyAuthoritative: Boolean
): Set<String> {
    if (!historyAuthoritative || currentBadgeIds.isEmpty()) {
        return emptySet()
    }

    return currentBadgeIds - watchedHistoryIds
}
