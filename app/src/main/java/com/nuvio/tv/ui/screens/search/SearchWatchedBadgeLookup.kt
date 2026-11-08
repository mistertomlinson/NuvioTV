package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.ui.screens.home.homeSeriesWatchedLookupIds

/**
 * Search and Discover must match the same series ID aliases as Home.
 *
 * `normalizedWatchedSeriesIds` is normalized once per watched-state update
 * using Home's normalization, not repeatedly for every poster.
 */
internal fun isSearchSeriesWatched(
    itemId: String,
    imdbId: String?,
    normalizedWatchedSeriesIds: Set<String>
): Boolean =
    homeSeriesWatchedLookupIds(itemId, imdbId)
        .any(normalizedWatchedSeriesIds::contains)
