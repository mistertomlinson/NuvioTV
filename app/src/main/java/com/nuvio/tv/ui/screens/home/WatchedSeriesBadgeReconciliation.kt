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

/**
 * Keep a previously validated completed-series checkmark while its owner is
 * actively replaying an episode. An in-progress session does not undo past
 * watched history, even if a tracking provider momentarily reports a partial
 * watched-episode projection during its refresh.
 *
 * Only history-derived disqualifications are suppressed during the replay.
 * Non-terminal status (a series returning or revived) still removes the badge,
 * and explicit Mark Unwatched clears the persisted badge directly.
 *
 * Never create a new completed badge from a replay alone.
 */
internal fun reconcileCompletedSeriesBadges(
    existingBadges: Set<String>,
    newlyVerifiedFullyWatched: Set<String>,
    validatedMissingEpisodes: Set<String>,
    validatedNonTerminal: Set<String>,
    missingFromWatchedHistory: Set<String>,
    activeReplayIds: Set<String>
): Set<String> {
    val replaysOfExistingCompletedSeries = activeReplayIds intersect existingBadges
    val disqualifiedByHistory =
        (validatedMissingEpisodes + missingFromWatchedHistory) -
            replaysOfExistingCompletedSeries
    return (
        existingBadges -
            disqualifiedByHistory -
            validatedNonTerminal
        ) + newlyVerifiedFullyWatched
}
