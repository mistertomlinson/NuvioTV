package com.nuvio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val STREAMING_ORIGINALS_REFRESH_ADDON_ID =
    "community.streaming.originals.v6"
private const val STREAMING_ORIGINALS_VISIBLE_WINDOW = 25
private const val STREAMING_ORIGINALS_REFRESH_CHECK_MS = 60_000L
private const val STREAMING_ORIGINALS_REFRESH_PREFS =
    "streaming_originals_home_refresh"
private const val STREAMING_ORIGINALS_REFRESH_DUE = "refresh_due"
private const val STREAMING_ORIGINALS_LAST_SUCCESS_DATE =
    "last_success_local_date"
private data class StreamingOriginalsRefreshState(
    var tickerJob: Job? = null,
    var stageJob: Job? = null,
    @Volatile var refreshGeneration: Long = 0,
    var stageReady: Boolean = false,
    var stagedRows: Map<String, CatalogRow> = emptyMap(),
    var stagedAddons: List<Addon>? = null,
    var stagedLocalDate: String? = null
)

private val streamingOriginalsRefreshStates =
    Collections.synchronizedMap(
        WeakHashMap<HomeViewModel, StreamingOriginalsRefreshState>()
    )

private fun HomeViewModel.streamingOriginalsRefreshState():
    StreamingOriginalsRefreshState =
    synchronized(streamingOriginalsRefreshStates) {
        streamingOriginalsRefreshStates.getOrPut(this) {
            StreamingOriginalsRefreshState()
        }
    }

private fun HomeViewModel.streamingOriginalsRefreshPrefs() =
    appContext.getSharedPreferences(
        STREAMING_ORIGINALS_REFRESH_PREFS,
        android.content.Context.MODE_PRIVATE
    )

private fun localNow(): ZonedDateTime =
    ZonedDateTime.now(STREAMING_ORIGINALS_TIME_ZONE)

private fun HomeViewModel.setStreamingOriginalsRefreshDue(
    due: Boolean
) {
    streamingOriginalsRefreshPrefs()
        .edit()
        .putBoolean(
            STREAMING_ORIGINALS_REFRESH_DUE,
            due
        )
        .apply()
}

private fun HomeViewModel.isStreamingOriginalsRefreshDue(): Boolean =
    streamingOriginalsRefreshPrefs()
        .getBoolean(
            STREAMING_ORIGINALS_REFRESH_DUE,
            false
        )

private fun HomeViewModel.markStreamingOriginalsRefreshDue(
    reason: String
) {
    val state = streamingOriginalsRefreshState()
    synchronized(state) {
        // A new checkpoint supersedes a staged or still-running replacement
        // from an earlier checkpoint (e.g. 11:45 still running at 12:45).
        state.refreshGeneration++
        state.stageJob?.cancel()
        state.stageJob = null
        if (state.stageReady) {
            state.stageReady = false
            state.stagedRows = emptyMap()
            state.stagedAddons = null
            state.stagedLocalDate = null
            seasonalSpotlightStageReadyState.value = false
        }
        setStreamingOriginalsRefreshDue(true)
    }

    Log.d(HomeViewModel.TAG, "Streaming Originals refresh due: $reason")
    // Run a lightweight HTTP probe even if Home stays composed through a dream.
    startPendingStreamingOriginalsStageIfNeeded()
}

private fun HomeViewModel.discardStaleStreamingOriginalsStage(
    currentLocalDate: String
) {
    val state = streamingOriginalsRefreshState()
    if (
        !state.stageReady ||
        state.stagedLocalDate == null ||
        state.stagedLocalDate == currentLocalDate
    ) {
        return
    }

    state.stageReady = false
    state.stagedRows = emptyMap()
    state.stagedAddons = null
    state.stagedLocalDate = null

    seasonalSpotlightStageReadyState.value = false

    Log.d(
        HomeViewModel.TAG,
        "Discarded prior-day Streaming Originals stage"
    )
}

private fun HomeViewModel.evaluateStreamingOriginalsRefreshBoundary() {
    val now = localNow()
    val localDate = now.toLocalDate().toString()
    val prefs = streamingOriginalsRefreshPrefs()
    val lastSuccess = prefs.getString(STREAMING_ORIGINALS_LAST_SUCCESS_DATE, null)

    if (lastSuccess == null) {
        // The first Home load already fetches catalogs. Record checkpoints
        // that have passed today rather than immediately duplicating it.
        val editor = prefs.edit()
            .putString(STREAMING_ORIGINALS_LAST_SUCCESS_DATE, localDate)
            .putBoolean(STREAMING_ORIGINALS_REFRESH_DUE, false)
        StreamingOriginalsTimedCheck.entries.forEach { check ->
            if (now.toLocalTime() >= check.at) {
                editor.putString(check.preferenceKey, localDate)
            }
        }
        editor.apply()
        return
    }

    val priorCheckpoints = StreamingOriginalsTimedCheck.entries.associateWith {
        prefs.getString(it.preferenceKey, null)
    }
    val due = dueStreamingOriginalsTimedChecks(now, priorCheckpoints)
    if (due.isEmpty()) return

    if (due.contains(StreamingOriginalsTimedCheck.MIDNIGHT)) {
        discardStaleStreamingOriginalsStage(localDate)
    }

    // Date-keyed markers are persisted independently. A successful 11:45
    // stage cannot suppress the 12:45 recheck, and a resuming app coalesces
    // all overdue checkpoints into one fresh off-screen snapshot.
    val editor = prefs.edit()
    due.forEach { check ->
        editor.putString(check.preferenceKey, localDate)
    }
    editor.apply()

    markStreamingOriginalsRefreshDue(due.joinToString(",") { it.reason })
}

internal fun HomeViewModel.ensureStreamingOriginalsRefreshTicker() {
    val state = streamingOriginalsRefreshState()
    if (state.tickerJob?.isActive == true) return

    evaluateStreamingOriginalsRefreshBoundary()

    state.tickerJob =
        viewModelScope.launch {
            while (true) {
                delay(STREAMING_ORIGINALS_REFRESH_CHECK_MS)
                evaluateStreamingOriginalsRefreshBoundary()
            }
        }
}

internal fun HomeViewModel.streamingOriginalsStageBusyOrReady(): Boolean {
    val state = streamingOriginalsRefreshState()
    return state.stageReady || state.stageJob?.isActive == true
}

internal fun HomeViewModel.checkStreamingOriginalsRefreshOnResume() {
    // Android may suspend the ticker while the display/device sleeps.
    evaluateStreamingOriginalsRefreshBoundary()
    startPendingStreamingOriginalsStageIfNeeded()
}

internal fun HomeViewModel.applyStreamingOriginalsIfQuiet() {
    if (homePresentationVisible) return
    val state = streamingOriginalsRefreshState()
    if (!state.stageReady) return
    viewModelScope.launch {
        if (!homePresentationVisible) {
            applyStagedStreamingOriginalsIfReady()
        }
    }
}

internal fun HomeViewModel.startPendingStreamingOriginalsStageIfNeeded() {
    if (!isStreamingOriginalsRefreshDue()) return

    val state = streamingOriginalsRefreshState()
    if (
        state.stageReady ||
        state.stageJob?.isActive == true
    ) {
        return
    }

    if (
        pendingSeasonalSpotlightAddons != null ||
        seasonalSpotlightStageJob?.isActive == true ||
        seasonalSpotlightStageReadyState.value
    ) {
        return
    }

    val currentAddon =
        addonsCache.firstOrNull { addon ->
            addon.id == STREAMING_ORIGINALS_REFRESH_ADDON_ID
        } ?: run {
            setStreamingOriginalsRefreshDue(false)
            return
        }

    val profileId = profileManager.activeProfileId.value
    val stageDate = LocalDate.now(STREAMING_ORIGINALS_TIME_ZONE).toString()
    val stageGeneration = state.refreshGeneration

    state.stageJob =
        viewModelScope.launch(Dispatchers.IO) {
            val refreshedAddon =
                when (
                    val result = addonRepository.fetchAddon(
                        currentAddon.baseUrl
                    )
                ) {
                    is NetworkResult.Success ->
                        result.data.copy(
                            enabled = currentAddon.enabled,
                            displayName = currentAddon.displayName
                        )
                    is NetworkResult.Error,
                    NetworkResult.Loading -> null
                }

            if (
                refreshedAddon == null ||
                state.refreshGeneration != stageGeneration ||
                profileManager.activeProfileId.value != profileId
            ) {
                return@launch
            }

            /*
             * Each checkpoint must revalidate the server, not reuse an older
             * successful response from the in-memory request cache. Clear
             * only SO entries; every other addon's cache and disk fallback
             * remain untouched.
             */
            catalogRepository.clearAddonInMemoryCache(
                STREAMING_ORIGINALS_REFRESH_ADDON_ID
            )

            // Cap concurrent network requests while Home accepts DPAD input.
            val semaphore = Semaphore(if (homePresentationVisible) 2 else 4)
            val preparedRows =
                coroutineScope {
                    refreshedAddon.catalogs.map { catalog ->
                        async {
                            semaphore.withPermit {
                                var latestRow: CatalogRow? = null
                                var failed = false

                                catalogRepository.getCatalog(
                                    addonBaseUrl = refreshedAddon.baseUrl,
                                    addonId = refreshedAddon.id,
                                    addonName = refreshedAddon.displayName,
                                    catalogId = catalog.id,
                                    catalogName = catalog.name,
                                    type = catalog.apiType,
                                    skip = 0,
                                    skipStep = catalog.skipStep(),
                                    supportsSkip = catalog.supportsExtra("skip")
                                ).collect { result ->
                                    when (result) {
                                        is NetworkResult.Success -> {
                                            latestRow =
                                                result.data
                                                    .withCatalogDisplayMetadata(
                                                        addonName = refreshedAddon.displayName,
                                                        addonBaseUrl = refreshedAddon.baseUrl,
                                                        catalogName = catalog.name
                                                    )
                                        }
                                        is NetworkResult.Error -> {
                                            if (latestRow == null) {
                                                failed = true
                                            }
                                        }
                                        NetworkResult.Loading -> Unit
                                    }
                                }

                                if (failed) null else latestRow
                            }
                        }
                    }.awaitAll()
                }

            if (
                state.refreshGeneration != stageGeneration ||
                profileManager.activeProfileId.value != profileId ||
                preparedRows.size != refreshedAddon.catalogs.size ||
                preparedRows.any { it == null }
            ) {
                Log.w(
                    HomeViewModel.TAG,
                    "Streaming Originals stage incomplete; keeping live rows"
                )
                return@launch
            }

            val rawPreparedRows = preparedRows.filterNotNull()

            /*
             * A stage that began before midnight is yesterday's snapshot.
             * It cannot satisfy today's release-day rollover.
             */
            if (
                LocalDate.now(STREAMING_ORIGINALS_TIME_ZONE).toString() !=
                    stageDate
            ) {
                if (state.refreshGeneration == stageGeneration) {
                    setStreamingOriginalsRefreshDue(true)
                }
                Log.d(
                    HomeViewModel.TAG,
                    "Discarded Streaming Originals stage that crossed midnight"
                )
                return@launch
            }

            /*
             * Compare raw server facts before TMDB enrichment. Item identity
             * alone is insufficient: release dates / new-season hints may
             * change without changing row membership.
             */
            if (!streamingOriginalsStageDiffersFromLive(rawPreparedRows)) {
                synchronized(state) {
                    if (state.refreshGeneration != stageGeneration) {
                        return@launch
                    }
                    state.stageReady = false
                    state.stagedRows = emptyMap()
                    state.stagedAddons = null
                    state.stagedLocalDate = null
                    setStreamingOriginalsRefreshDue(false)
                    streamingOriginalsRefreshPrefs()
                        .edit()
                        .putString(STREAMING_ORIGINALS_LAST_SUCCESS_DATE, stageDate)
                        .apply()
                }
                Log.d(
                    HomeViewModel.TAG,
                    "Streaming Originals content unchanged; staged probe discarded"
                )
                return@launch
            }

            /*
             * The HTTP probe runs regardless of Home visibility, but heavy
             * TMDB enrichment waits for Home to be offscreen. A screensaver,
             * navigation or background transition can release this gate.
             */
            while (homePresentationVisible) {
                if (state.refreshGeneration != stageGeneration ||
                    profileManager.activeProfileId.value != profileId
                ) return@launch
                delay(500L)
            }

            val tmdbSettings = currentTmdbSettings
            val fullyPrepared =
                rawPreparedRows.map { row ->
                    val visibleItems =
                        row.items.take(
                            STREAMING_ORIGINALS_VISIBLE_WINDOW
                        )

                    val enrichedVisibleItems =
                        if (
                            visibleItems.isNotEmpty() &&
                            tmdbSettings.enabled &&
                            (
                                tmdbSettings.useArtwork ||
                                    tmdbSettings.useBasicInfo ||
                                    tmdbSettings.useDetails
                                )
                        ) {
                            enrichHeroItemsPipeline(
                                items = visibleItems,
                                settings = tmdbSettings
                            )
                        } else {
                            visibleItems
                        }

                    val enrichedById =
                        enrichedVisibleItems.associateBy { item ->
                            "${item.apiType}:${item.id}"
                        }

                    row.copy(
                        items = row.items.map { item ->
                            enrichedById[
                                "${item.apiType}:${item.id}"
                            ] ?: item
                        },
                        isLoading = false
                    )
                }

            if (
                state.refreshGeneration != stageGeneration ||
                profileManager.activeProfileId.value != profileId
            ) {
                return@launch
            }

            if (
                LocalDate.now(STREAMING_ORIGINALS_TIME_ZONE).toString() !=
                    stageDate
            ) {
                if (state.refreshGeneration == stageGeneration) {
                    setStreamingOriginalsRefreshDue(true)
                }
                Log.d(
                    HomeViewModel.TAG,
                    "Discarded enriched Streaming Originals stage after midnight"
                )
                return@launch
            }

            val staged =
                fullyPrepared.associateBy { row ->
                    catalogKey(
                        addonId = row.addonId,
                        type = row.apiType,
                        catalogId = row.catalogId
                    )
                }

            val nextAddons =
                addonsCache.map { addon ->
                    if (addon.id == STREAMING_ORIGINALS_REFRESH_ADDON_ID) {
                        refreshedAddon
                    } else {
                        addon
                    }
                }

            synchronized(state) {
                if (state.refreshGeneration != stageGeneration) {
                    return@launch
                }
                state.stagedRows = staged
                state.stagedAddons = nextAddons
                state.stagedLocalDate = stageDate
                state.stageReady = true
                setStreamingOriginalsRefreshDue(false)
                streamingOriginalsRefreshPrefs()
                    .edit()
                    .putString(STREAMING_ORIGINALS_LAST_SUCCESS_DATE, stageDate)
                    .apply()
                seasonalSpotlightStageReadyState.value = true
            }

            Log.d(
                HomeViewModel.TAG,
                "Streaming Originals replacement staged: ${staged.size} rows"
            )

            withContext(Dispatchers.Main.immediate) {
                if (!homePresentationVisible && state.stageReady &&
                    state.refreshGeneration == stageGeneration &&
                    profileManager.activeProfileId.value == profileId
                ) {
                    applyStagedStreamingOriginalsIfReady()
                }
            }
        }
}

private fun HomeViewModel.streamingOriginalsStageDiffersFromLive(
    rows: List<CatalogRow>
): Boolean {
    /*
     * catalogSourceRows may retain these three Home/TMDB enrichment overlays
     * across a fresh catalog response. They are not addon-source changes and
     * must not manufacture a midnight row diff.
     */
    fun signature(row: CatalogRow): Pair<String, List<Int>> =
        row.catalogName to row.items.map { item ->
            item.copy(
                ageRating = null,
                status = null,
                logo = null
            ).hashCode()
        }

    val staged = rows.associate { row ->
        catalogKey(
            addonId = row.addonId,
            type = row.apiType,
            catalogId = row.catalogId
        ) to signature(row)
    }

    val live =
        synchronized(catalogSourceRows) {
            catalogSourceRows.values
                .filter { row ->
                    row.addonId == STREAMING_ORIGINALS_REFRESH_ADDON_ID
                }
                .associate { row ->
                    catalogKey(
                        addonId = row.addonId,
                        type = row.apiType,
                        catalogId = row.catalogId
                    ) to signature(row)
                }
        }

    return staged != live
}

internal suspend fun HomeViewModel.applyStagedStreamingOriginalsIfReady():
    Boolean {
    val state = streamingOriginalsRefreshState()
    if (!state.stageReady) return false

    val staged = state.stagedRows
    val stagedAddons = state.stagedAddons ?: return false

    val oldKeys =
        synchronized(catalogSourceRows) {
            catalogSourceRows
                .filterValues { row ->
                    row.addonId == STREAMING_ORIGINALS_REFRESH_ADDON_ID
                }
                .keys
                .toList()
        }

    synchronized(catalogsMap) {
        oldKeys.forEach(catalogsMap::remove)

        staged.forEach { (key, sourceRow) ->
            val visibleItems =
                sourceRow.items.take(
                    STREAMING_ORIGINALS_VISIBLE_WINDOW
                )

            catalogsMap[key] =
                sourceRow.copy(
                    items = visibleItems,
                    isLoading = false,
                    hasMore =
                        sourceRow.items.size > visibleItems.size ||
                            sourceRow.hasMore
                )

            homeEnrichmentAttemptedIds.addAll(
                visibleItems.map { it.id }
            )
        }
    }

    synchronized(catalogSourceRows) {
        oldKeys.forEach(catalogSourceRows::remove)
        staged.forEach { (key, sourceRow) ->
            catalogSourceRows[key] = sourceRow
        }
    }

    addonsCache = stagedAddons
    activeCatalogLoadSignature =
        buildHomeCatalogLoadSignature(
            addons = addonsCache,
            profileId = profileManager.activeProfileId.value
        )
    rebuildCatalogOrder(addonsCache)

    state.stageReady = false
    state.stagedRows = emptyMap()
    state.stagedAddons = null
    state.stagedLocalDate = null
    state.stageJob = null

    seasonalSpotlightStageReadyState.value = false

    updateCatalogRowsPipeline()

    Log.d(
        HomeViewModel.TAG,
        "Applied staged Streaming Originals replacement on Home return"
    )

    return true
}
