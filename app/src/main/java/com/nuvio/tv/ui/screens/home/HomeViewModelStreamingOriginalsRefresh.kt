package com.nuvio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
private const val STREAMING_ORIGINALS_LAST_PRECHECK_DATE =
    "last_2345_precheck_local_date"
private const val STREAMING_ORIGINALS_LAST_RECOVERY_DATE =
    "last_0045_recovery_local_date"

private val STREAMING_ORIGINALS_PRECHECK_TIME: LocalTime =
    LocalTime.of(23, 45)
private val STREAMING_ORIGINALS_RECOVERY_TIME: LocalTime =
    LocalTime.of(0, 45)

private data class StreamingOriginalsRefreshState(
    var tickerJob: Job? = null,
    var stageJob: Job? = null,
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
    ZonedDateTime.now(ZoneId.systemDefault())

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
    if (!isStreamingOriginalsRefreshDue()) {
        setStreamingOriginalsRefreshDue(true)
        Log.d(
            HomeViewModel.TAG,
            "Streaming Originals refresh due: $reason"
        )
    }

    if (!homePresentationVisible) {
        startPendingStreamingOriginalsStageIfNeeded()
    }
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
        "Discarded pre-midnight Streaming Originals stage"
    )
}

private fun HomeViewModel.evaluateStreamingOriginalsRefreshBoundary() {
    val now = localNow()
    val localDate = now.toLocalDate().toString()
    val localTime = now.toLocalTime()
    val prefs = streamingOriginalsRefreshPrefs()

    val lastSuccess =
        prefs.getString(
            STREAMING_ORIGINALS_LAST_SUCCESS_DATE,
            null
        )

    if (lastSuccess == null) {
        prefs.edit()
            .putString(
                STREAMING_ORIGINALS_LAST_SUCCESS_DATE,
                localDate
            )
            .putBoolean(
                STREAMING_ORIGINALS_REFRESH_DUE,
                false
            )
            .apply()
    } else if (lastSuccess != localDate) {
        discardStaleStreamingOriginalsStage(localDate)
        markStreamingOriginalsRefreshDue("local_midnight_or_resume_catchup")
    }

    if (localTime >= STREAMING_ORIGINALS_PRECHECK_TIME) {
        val precheckDate =
            prefs.getString(
                STREAMING_ORIGINALS_LAST_PRECHECK_DATE,
                null
            )

        if (precheckDate != localDate) {
            prefs.edit()
                .putString(
                    STREAMING_ORIGINALS_LAST_PRECHECK_DATE,
                    localDate
                )
                .apply()
            markStreamingOriginalsRefreshDue("23:45_local_precheck")
        }
    }

    if (localTime >= STREAMING_ORIGINALS_RECOVERY_TIME) {
        val recoveryDate =
            prefs.getString(
                STREAMING_ORIGINALS_LAST_RECOVERY_DATE,
                null
            )
        val currentLastSuccess =
            prefs.getString(
                STREAMING_ORIGINALS_LAST_SUCCESS_DATE,
                null
            )

        if (
            recoveryDate != localDate &&
            currentLastSuccess != localDate
        ) {
            prefs.edit()
                .putString(
                    STREAMING_ORIGINALS_LAST_RECOVERY_DATE,
                    localDate
                )
                .apply()
            markStreamingOriginalsRefreshDue("00:45_local_recovery")
        }
    }
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

internal fun HomeViewModel.cancelStreamingOriginalsStageForVisibleHome() {
    val state = streamingOriginalsRefreshState()
    if (state.stageReady) return

    state.stageJob?.cancel()
    state.stageJob = null
}

internal fun HomeViewModel.startPendingStreamingOriginalsStageIfNeeded() {
    if (homePresentationVisible) return
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
    val stageDate = LocalDate.now(ZoneId.systemDefault()).toString()

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
                profileManager.activeProfileId.value != profileId ||
                homePresentationVisible
            ) {
                return@launch
            }

            /*
             * This stage is an authoritative date-boundary probe. Remove only
             * Streaming Originals request-cache entries so a cached Success
             * cannot hide a failed network fetch and falsely satisfy today's
             * midnight/recovery check. Disk fallback and every other addon's
             * cache remain untouched.
             */
            catalogRepository.clearAddonInMemoryCache(
                STREAMING_ORIGINALS_REFRESH_ADDON_ID
            )

            val semaphore = Semaphore(4)
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
                profileManager.activeProfileId.value != profileId ||
                homePresentationVisible ||
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
             * A 23:45 precheck that finishes after midnight is yesterday's
             * snapshot. Never let it satisfy or overwrite the new day's due
             * state. The midnight/recovery pass will stage again.
             */
            if (
                LocalDate.now(ZoneId.systemDefault()).toString() !=
                    stageDate
            ) {
                setStreamingOriginalsRefreshDue(true)
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
                state.stageReady = false
                state.stagedRows = emptyMap()
                state.stagedAddons = null
                state.stagedLocalDate = null
                setStreamingOriginalsRefreshDue(false)
                streamingOriginalsRefreshPrefs()
                    .edit()
                    .putString(
                        STREAMING_ORIGINALS_LAST_SUCCESS_DATE,
                        stageDate
                    )
                    .apply()
                Log.d(
                    HomeViewModel.TAG,
                    "Streaming Originals content unchanged; staged probe discarded"
                )
                return@launch
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
                profileManager.activeProfileId.value != profileId ||
                homePresentationVisible
            ) {
                return@launch
            }

            if (
                LocalDate.now(ZoneId.systemDefault()).toString() !=
                    stageDate
            ) {
                setStreamingOriginalsRefreshDue(true)
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

            state.stagedRows = staged
            state.stagedAddons = nextAddons
            state.stagedLocalDate = stageDate
            state.stageReady = true

            setStreamingOriginalsRefreshDue(false)
            streamingOriginalsRefreshPrefs()
                .edit()
                .putString(
                    STREAMING_ORIGINALS_LAST_SUCCESS_DATE,
                    stageDate
                )
                .apply()

            seasonalSpotlightStageReadyState.value = true

            Log.d(
                HomeViewModel.TAG,
                "Streaming Originals replacement staged: ${staged.size} rows"
            )
        }
}

/**
 * Fast marker check for Home composition. Reading the generation itself is
 * always dispatched to IO; no file or JSON work reaches the DPAD/scroll path.
 */
internal fun HomeViewModel.hasDurableStreamingOriginalsGeneration(): Boolean =
    streamingOriginalsGenerationStore.hasSnapshot(profileManager.activeProfileId.value)

/**
 * A Worker may finish while the process is backgrounded (or after Home's
 * ViewModel was destroyed). Consume that complete generation only behind the
 * Home entry/resume cover. It is never applied by the Worker itself.
 */
internal suspend fun HomeViewModel.applyDurableStreamingOriginalsIfReady(): Boolean {
    val profileId = profileManager.activeProfileId.value
    val generation = streamingOriginalsGenerationStore.read(profileId) ?: run {
        // A marker without readable bytes must not trap every Home entry
        // behind a return curtain after a corrupted or interrupted write.
        streamingOriginalsGenerationStore.clearBrokenSnapshot(profileId)
        return false
    }
    val today = LocalDate.now(ZoneId.systemDefault()).toString()

    if (generation.localDate != today) {
        streamingOriginalsGenerationStore.clearIfCurrent(
            profileId, generation.createdAtMillis
        )
        return false
    }

    // On process restart the Home pipeline must first restore its own catalog
    // maps. Otherwise an ordinary catalog load can overwrite the staged swap.
    kotlinx.coroutines.withTimeoutOrNull(9_000L) {
        _uiState.first { state ->
            state.catalogsReady ||
                (state.error != null && !state.isLoading)
        }
    } ?: return false

    if (profileManager.activeProfileId.value != profileId) return false
    val currentAddon = addonsCache.firstOrNull {
        it.id == STREAMING_ORIGINALS_REFRESH_ADDON_ID
    } ?: return false
    if (currentAddon.baseUrl.trimEnd('/') != generation.addon.baseUrl.trimEnd('/')) {
        // Prevent an old profile/addon configuration from replacing live data.
        streamingOriginalsGenerationStore.clearIfCurrent(
            profileId, generation.createdAtMillis
        )
        return false
    }
    if (!streamingOriginalsStageDiffersFromLive(generation.rows)) {
        streamingOriginalsGenerationStore.clearIfCurrent(
            profileId, generation.createdAtMillis
        )
        return false
    }

    // Avoid TMDB/image network requests here. The established Home enrichment
    // pipeline and its row-readiness gates own those after this raw swap.
    val state = streamingOriginalsRefreshState()
    state.stageJob?.cancel()
    state.stageJob = null
    state.stagedRows = generation.rows.associateBy { row ->
        catalogKey(
            addonId = row.addonId,
            type = row.apiType,
            catalogId = row.catalogId
        )
    }
    state.stagedAddons = addonsCache.map { addon ->
        if (addon.id == STREAMING_ORIGINALS_REFRESH_ADDON_ID) {
            generation.addon
        } else addon
    }
    state.stagedLocalDate = today
    state.stageReady = true

    val applied = applyStagedStreamingOriginalsIfReady()
    if (applied) {
        streamingOriginalsGenerationStore.clearIfCurrent(
            profileId, generation.createdAtMillis
        )
    }
    return applied
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
