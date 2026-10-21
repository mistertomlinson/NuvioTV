#!/usr/bin/env python3
from pathlib import Path

HOME = Path("app/src/main/java/com/nuvio/tv/ui/screens/home/HomeViewModelContinueWatching.kt")
text = HOME.read_text()

old_call = "publishBadgeUpdate(allWatchedEpisodes)"
if text.count(old_call) != 2:
    raise SystemExit(f"Expected 2 ordinary publishBadgeUpdate calls, found {text.count(old_call)}")
text = text.replace(
    old_call,
    "publishBadgeUpdate(\n"
    "                            allWatchedEpisodes = allWatchedEpisodes,\n"
    "                            historyAuthoritative = authoritativeProfileCycle\n"
    "                        )"
)

old_async_call = "publishBadgeUpdate(asyncWatchedEpisodes)"
if text.count(old_async_call) != 1:
    raise SystemExit(f"Expected 1 async publishBadgeUpdate call, found {text.count(old_async_call)}")
text = text.replace(
    old_async_call,
    "publishBadgeUpdate(\n"
    "                                    allWatchedEpisodes = asyncWatchedEpisodes,\n"
    "                                    historyAuthoritative = authoritativeProfileCycle\n"
    "                                )"
)

old_signature = '''private fun HomeViewModel.publishBadgeUpdate(
    allWatchedEpisodes: Map<String, Set<Pair<Int, Int>>>
) {'''
new_signature = '''private fun HomeViewModel.publishBadgeUpdate(
    allWatchedEpisodes: Map<String, Set<Pair<Int, Int>>>,
    historyAuthoritative: Boolean
) {'''
if text.count(old_signature) != 1:
    raise SystemExit("publishBadgeUpdate signature anchor did not match exactly once")
text = text.replace(old_signature, new_signature)

old_merge = '''    val expandedNonTerminal =
        expandWatchedSeriesBadgeIds(validatedNonTerminal)

    // Merge with persisted badges — don't remove badges we have not
    // revalidated.  Do remove badges when a released regular episode is
    // missing OR when refreshed metadata says the show is no longer terminal.
    val disqualified = expandedNotFullyWatched + expandedNonTerminal
    val current = fullyWatchedSeriesIds.fullyWatchedSeriesIds.value
    val merged = (current - disqualified) + expandedFullyWatched
'''
new_merge = '''    val expandedNonTerminal =
        expandWatchedSeriesBadgeIds(validatedNonTerminal)

    val current = fullyWatchedSeriesIds.fullyWatchedSeriesIds.value
    val expandedWatchedHistoryIds =
        expandWatchedSeriesBadgeIds(allWatchedEpisodes.keys)
    val missingFromAuthoritativeHistory =
        authoritativeMissingWatchedSeriesBadgeIds(
            currentBadgeIds = current,
            watchedHistoryIds = expandedWatchedHistoryIds,
            historyAuthoritative = historyAuthoritative
        )

    // Merge with persisted badges — don't remove badges we have not
    // revalidated while a remote provider is still loading. Once watched
    // history is authoritative, a persisted badge whose show is absent from
    // that history is conclusively stale (for example after whole-series
    // "mark unwatched") and must be removed as well.
    val disqualified =
        expandedNotFullyWatched +
            expandedNonTerminal +
            missingFromAuthoritativeHistory
    val merged = (current - disqualified) + expandedFullyWatched
'''
if text.count(old_merge) != 1:
    raise SystemExit("badge merge anchor did not match exactly once")
text = text.replace(old_merge, new_merge)

HOME.write_text(text)

# This helper exists only to make the large-file edit deterministic. Remove it
# from the working tree so the final feature-branch diff contains production
# code and regression tests only.
Path(__file__).unlink()

print("Applied authoritative zero-history watched-badge reconciliation patch.")
