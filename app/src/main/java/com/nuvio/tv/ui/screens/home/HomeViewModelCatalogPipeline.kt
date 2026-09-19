package com.nuvio.tv.ui.screens.home

import kotlinx.coroutines.sync.withLock

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import com.nuvio.tv.core.util.filterReleasedItems
import kotlinx.coroutines.withContext
import java.time.LocalDate

private const val HOME_CATALOG_WINDOW_SIZE = 25

private data class CatalogUpdateResult(
    val displayRows: List<CatalogRow>,
    val heroItems: List<com.nuvio.tv.domain.model.MetaPreview>,
    val gridItems: List<GridItem>,
    val fullRows: List<CatalogRow>
)

internal data class HomeCatalogReloadRequest(
    val addons: List<Addon>,
    val forceReload: Boolean,
    val profileId: Int
)

internal fun HomeViewModel.loadHomeCatalogOrderPreferencePipeline() {
    viewModelScope.launch {
        layoutPreferenceDataStore.homeCatalogOrderKeys.collectLatest { keys ->
            homeCatalogOrderKeys = keys
            rebuildCatalogOrder(addonsCache)
            scheduleUpdateCatalogRows()
        }
    }
}

internal fun HomeViewModel.scheduleCatalogPipeline(
    addons: List<Addon>,
    forceReload: Boolean = false,
    profileId: Int = profileManager.activeProfileId.value
) {
    catalogReloadTrigger.tryEmit(
        HomeCatalogReloadRequest(
            addons = addons,
            forceReload = forceReload,
            profileId = profileId
        )
    )
}

/*
 * Apply display-only manifest changes without replacing loaded rows.
 *
 * A stale-manifest refresh may change catalog titles or addon display names
 * while preserving the same catalog identities. Keep the existing items,
 * enrichment readiness, horizontal positions, and focus requesters intact.
 */
internal fun HomeViewModel.refreshCatalogDisplayMetadataPipeline(
    addons: List<Addon>
) {
    var changed = false

    addons.forEach { addon ->
        addon.catalogs.forEach { catalog ->
            val key = catalogKey(
                addonId = addon.id,
                type = catalog.apiType,
                catalogId = catalog.id
            )

            catalogsMap[key]?.let { current ->
                val updated = current.copy(
                    addonName = addon.displayName,
                    addonBaseUrl = addon.baseUrl,
                    catalogName = catalog.name
                )
                if (updated != current) {
                    catalogsMap[key] = updated
                    changed = true
                }
            }

            catalogSourceRows[key]?.let { current ->
                val updated = current.copy(
                    addonName = addon.displayName,
                    addonBaseUrl = addon.baseUrl,
                    catalogName = catalog.name
                )
                if (updated != current) {
                    catalogSourceRows[key] = updated
                    changed = true
                }
            }
        }
    }

    if (changed) {
        scheduleUpdateCatalogRows()
    }
}

internal fun HomeViewModel.loadDisabledHomeCatalogPreferencePipeline() {
    viewModelScope.launch {
        layoutPreferenceDataStore.disabledHomeCatalogKeys.collectLatest { keys ->
            val newKeys = keys.toSet()
            if (newKeys == disabledHomeCatalogKeys) return@collectLatest
            disabledHomeCatalogKeys = newKeys
            rebuildCatalogOrder(addonsCache)
            scheduleUpdateCatalogRows()
        }
    }
}

internal fun HomeViewModel.loadNumberedHomeCatalogPreferencePipeline() {
    viewModelScope.launch {
        layoutPreferenceDataStore.numberedHomeCatalogKeys.collectLatest { keys ->
            _numberedCatalogKeysSet.value = keys.toSet()
            scheduleUpdateCatalogRows()
        }
    }
    viewModelScope.launch {
        layoutPreferenceDataStore.outlineNumberedHomeCatalogKeys.collectLatest { keys ->
            _outlineNumberedCatalogKeysSet.value = keys.toSet()
            scheduleUpdateCatalogRows()
        }
    }
    viewModelScope.launch {
        layoutPreferenceDataStore.useThemeColorForNumbers.collectLatest { enabled ->
            _useThemeColorForNumbers.value = enabled
            scheduleUpdateCatalogRows()
        }
    }
}

internal fun HomeViewModel.loadShuffleHomeCatalogPreferencePipeline() {
    viewModelScope.launch {
        layoutPreferenceDataStore.shuffledHomeCatalogKeys.collectLatest { keys ->
            shuffledCatalogKeys = keys.toSet()
            scheduleUpdateCatalogRows()
        }
    }
    viewModelScope.launch {
        layoutPreferenceDataStore.lastShuffleTimestampMs.collectLatest { ts ->
            lastShuffleTimestampMs = ts
            // Auto-advance the shuffle timestamp on launch if 12 hours have elapsed.
            if (shuffledCatalogKeys.isNotEmpty() && ts > 0L) {
                val twelveHoursMs = 12L * 60 * 60 * 1000
                val now = System.currentTimeMillis()
                if (now - ts >= twelveHoursMs) {
                    // Let the new timestamp emission perform the row update so we
                    // never briefly rebuild using the expired shuffle seed.
                    layoutPreferenceDataStore.setLastShuffleTimestampMs(now)
                    return@collectLatest
                }
            }
            if (shuffledCatalogKeys.isNotEmpty()) {
                scheduleUpdateCatalogRows()
            }
        }
    }
    // Periodic timer: check every hour if shuffle needs advancing while app is open.
    viewModelScope.launch {
        while (true) {
            kotlinx.coroutines.delay(60 * 60 * 1000L)
            if (shuffledCatalogKeys.isEmpty()) continue
            val twelveHoursMs = 12L * 60 * 60 * 1000
            val now = System.currentTimeMillis()
            val ts = lastShuffleTimestampMs
            if (ts > 0L && now - ts >= twelveHoursMs) {
                layoutPreferenceDataStore.setLastShuffleTimestampMs(now)
            }
        }
    }
}

internal fun HomeViewModel.observeTmdbSettingsPipeline() {
    viewModelScope.launch {
        tmdbSettingsDataStore.settings
            .distinctUntilChanged()
            .collectLatest { settings ->
                currentTmdbSettings = settings
                scheduleUpdateCatalogRows()
            }
    }
}

internal fun HomeViewModel.observeInstalledAddonsPipeline() {
    viewModelScope.launch {
        addonRepository.getInstalledAddons()
            .collectLatest { addons ->
                addonsCache = addons

                /*
                 * Refresh names before evaluating the structural signature.
                 * Name-only manifest refreshes now update the existing rows,
                 * while scheduleCatalogPipeline() is safely rejected by the
                 * unchanged structural signature.
                 */
                refreshCatalogDisplayMetadataPipeline(addons)
                scheduleCatalogPipeline(addons)
            }
    }
}

internal suspend fun HomeViewModel.loadAllCatalogsPipeline(
    addons: List<Addon>,
    forceReload: Boolean = false,
    profileId: Int
) {
    if (
        !isActiveInstance ||
        profileId != profileManager.activeProfileId.value
    ) {
        return
    }

    catalogPipelineMutex.withLock {
    if (profileId != profileManager.activeProfileId.value) {
        return@withLock
    }

    val signature =
        buildHomeCatalogLoadSignature(
            addons = addons,
            profileId = profileId
        )
    if (!forceReload &&
        signature == activeCatalogLoadSignature &&
        (catalogsLoadInProgress || catalogsMap.isNotEmpty())
    ) {
        return@withLock
    }

    activeCatalogLoadSignature = signature
    catalogsLoadInProgress = true
    catalogLoadGeneration += 1
    val generation = catalogLoadGeneration
    cancelInFlightCatalogLoads()

    _uiState.update { it.copy(isLoading = true, error = null, installedAddonsCount = addons.size) }
    pendingPlatformCatalogKeys.clear()
    platformPreloadTriggered = false
    _uiState.update { it.copy(enrichmentReadyRowKeys = emptySet()) }
    catalogOrder.clear()
    catalogsMap.clear()
    catalogSourceRows.clear()
    // Re-inject ML row from disk cache immediately so it survives pipeline restart
    val mlProfileId = profileId
    val mlSourceMode = libraryRepository.sourceMode.first()
    val mlCached = myListDiskCache.load(
        profileId = mlProfileId,
        sourceMode = mlSourceMode
    )
    if (mlCached.isNotEmpty()) {
        val mlItems = mlCached.map { c ->
            com.nuvio.tv.domain.model.MetaPreview(
                id = c.id,
                type = com.nuvio.tv.domain.model.ContentType.fromString(c.type),
                rawType = c.type,
                name = c.name,
                poster = c.poster,
                posterShape = com.nuvio.tv.domain.model.PosterShape.POSTER,
                background = c.background,
                logo = c.logo,
                description = c.description,
                releaseInfo = c.releaseInfo,
                imdbRating = c.imdbRating,
                genres = c.genres
            )
        }
        catalogsMap[HomeViewModel.MY_LIST_CATALOG_KEY] = com.nuvio.tv.domain.model.CatalogRow(
            addonId = HomeViewModel.MY_LIST_ADDON_ID,
            addonName = "Built-In",
            addonBaseUrl = "",
            catalogId = HomeViewModel.MY_LIST_CATALOG_ID,
            catalogName = "My List",
            type = com.nuvio.tv.domain.model.ContentType.UNKNOWN,
            rawType = "mixed",
            items = mlItems,
            isLoading = true,
            hasMore = false,
            supportsSkip = false
        )
    }
    // Clear repository-level cache on pipeline restart to prevent cross-profile
    // key collisions when multiple instances of the same addon exist across profiles.
    catalogRepository.clearInMemoryCache()
    posterStatusReconcileJob?.cancel()
    reconcilePosterStatusObserversPipeline(emptyList())
    _fullCatalogRows.value = emptyList()
    hasRenderedFirstCatalog = false
    diskCacheRestored = false
    saveCachedVisiblePlatformIds(emptySet())
    trailerPreviewLoadingIds.clear()
    trailerPreviewNegativeCache.clear()
    trailerPreviewUrlsState.clear()
    trailerPreviewAudioUrlsState.clear()
    activeTrailerPreviewItemId = null
    trailerPreviewRequestVersion = 0L
    prefetchedExternalMetaIds.clear()
    externalImdbRatingCache.clear()
    externalMetaPrefetchInFlightIds.clear()
    externalMetaPrefetchJob?.cancel()
    pendingExternalMetaPrefetchItemId = null
    prefetchedTmdbIds.clear()
    homeEnrichmentAttemptedIds.clear()

    /*
     * In-memory TMDB enrichment survives a catalog reload and remains a
     * zero-TMDB-network fast path. Do not mark these titles terminal yet:
     * any configured external/IMDb completion channel must settle before
     * the row is allowed to leave its skeleton state.
     */
    if (enrichmentCache.isNotEmpty()) {
        val cachedIds =
            enrichmentCache.keys.toList()

        prefetchedTmdbIds.addAll(
            cachedIds
        )
    }

    modernHomePriorityRowKeys = emptyList()
    homeEnrichmentPlanSignature = null
    // NOTE: enrichmentCache is intentionally NOT cleared here. It is keyed by
    // global content id (item.id), so TMDB enrichment stays valid across a
    // catalog reload regardless of which addon served the item or whether an
    // addon's catalog list changed. Clearing it made every catalog reload
    // (e.g. an addon manifest that differs between the cached and freshly
    // fetched emit) discard expensive, still-valid enrichment, forcing already-
    // enriched platforms to re-enrich on revisit. The signature guard above
    // already prevents redundant reloads; enrichment is repopulated from disk
    // (line ~234), focus enrichment, and the proactive job as needed. Profile
    // switches use a separate reload path and profile-scoped caches, so this is
    // not a cross-profile safety mechanism.
    enrichmentRestoreComplete = false
    // Load enrichment cache in background — don't block catalog fetching on it.
    // The readiness gate defers row promotions until this completes, then we
    // re-run the recompute so gated rows open against the full cache.
    viewModelScope.launch {
        try {
            val restored =
                homeEnrichmentDiskCache.loadAll()

            val restoredExternalStates =
                homeEnrichmentDiskCache
                    .loadExternalMetaStates()

            if (restored.isNotEmpty()) {
                enrichmentCache.putAll(
                    restored
                )

                /*
                 * Restored TMDB data remains reusable without another TMDB
                 * request. External/IMDb completion is restored separately:
                 * older cache entries simply report unsettled and pass through
                 * the bounded proactive completion path once.
                 */
                val restoredIds =
                    restored.keys.toList()

                prefetchedTmdbIds.addAll(
                    restoredIds
                )
            }

            restoredExternalStates.forEach {
                    (id, state) ->
                if (state.settled) {
                    prefetchedExternalMetaIds.add(
                        id
                    )

                    state.imdbRating?.let {
                            rating ->
                        externalImdbRatingCache[id] =
                            rating
                    }
                }
            }
        } finally {
            enrichmentRestoreComplete = true
            runCatching { updateCatalogRowsPipeline() }
        }
    }
    proactiveEnrichJob?.cancel()
    proactiveEnrichJob = null
    tmdbEnrichFocusJob?.cancel()
    pendingTmdbEnrichItemId = null
    lastHeroEnrichmentSignature = null
    lastHeroEnrichedItems = emptyList()

    try {
        if (addons.isEmpty()) {
            catalogsLoadInProgress = false
            _uiState.update { it.copy(isLoading = false, error = "No addons installed") }
            return@withLock
        }

        rebuildCatalogOrder(addons)

        if (catalogOrder.isEmpty()) {
            catalogsLoadInProgress = false
            _uiState.update { it.copy(isLoading = false, error = "No catalog addons installed") }
            return@withLock
        }

        val catalogsToLoad = addons.flatMap { addon ->
            addon.catalogs
                .filterNot {
                    !it.shouldShowOnHome() || isCatalogDisabled(
                        addonBaseUrl = addon.baseUrl,
                        addonId = addon.id,
                        type = it.apiType,
                        catalogId = it.id,
                        catalogName = it.name
                    )
                }
                .map { catalog -> addon to catalog }
        }
        // Track which catalogs are platform-categorized so we can trigger preload
        // as soon as just those finish — not waiting for every catalog.
        catalogsToLoad.forEach { (addon, catalog) ->
            if (inferPlatformId(catalog.name) != null) {
                val key = catalogKey(addonId = addon.id, type = catalog.apiType, catalogId = catalog.id)
                pendingPlatformCatalogKeys.add(key)
            }
        }
        // Seed every catalog with an empty isLoading=true placeholder row immediately —
        // catalog name/addon known synchronously from the manifest before any network
        // fetch. Gives the UI skeleton rows to show shimmer placeholders for.
        // Disk-cache restore below overwrites these with real cached items where available.
        catalogsToLoad.forEach { (addon, catalog) ->
            val key = catalogKey(addonId = addon.id, type = catalog.apiType, catalogId = catalog.id)
            if (!catalogsMap.containsKey(key)) {
                catalogsMap[key] = com.nuvio.tv.domain.model.CatalogRow(
                    addonId = addon.id,
                    addonName = addon.displayName,
                    addonBaseUrl = addon.baseUrl,
                    catalogId = catalog.id,
                    catalogName = catalog.name,
                    type = com.nuvio.tv.domain.model.ContentType.fromString(catalog.apiType),
                    rawType = catalog.apiType,
                    items = emptyList(),
                    isLoading = true,
                    hasMore = false,
                    supportsSkip = false
                )
            }
        }

        /*
         * Disk restoration and fresh catalog loading are independent I/O.
         * Start both together, but keep skeletonReady behind disk completion
         * so Home's existing release semantics remain unchanged. A fresh
         * network row writes catalogSourceRows first; the disk merge below
         * therefore fills only unresolved keys and can never overwrite it.
         */
        val diskCacheDeferred = viewModelScope.async {
            catalogRepository.loadCatalogsFromDisk(profileId)
        }
        pendingCatalogLoads = catalogsToLoad.size
        catalogsToLoad.forEach { (addon, catalog) ->
            loadCatalogPipeline(
                addon = addon,
                catalog = catalog,
                generation = generation,
                profileId = profileId,
                diskRestoreJob = diskCacheDeferred
            )
        }

        val diskCached = diskCacheDeferred.await()
        if (diskCached.isNotEmpty()) {
            diskCached.forEach { (key, row) ->
                if (!catalogSourceRows.containsKey(key)) {
                    catalogSourceRows[key] = row
                    val exposedItems = row.items.take(HOME_CATALOG_WINDOW_SIZE)
                    catalogsMap[key] = row.copy(
                        items = exposedItems,
                        hasMore = row.items.size > exposedItems.size || row.hasMore
                    )
                }
            }
            diskCacheRestored = true
            // Show cached/fresh rows immediately while remaining requests continue.
            _uiState.update { it.copy(isLoading = false) }
            updateCatalogRowsPipeline()
            Log.d(HomeViewModel.TAG, "Restored ${diskCached.size} catalog rows from disk for profile $profileId")
        }

        _uiState.update { it.copy(skeletonReady = true) }
        updateCatalogRowsPipeline()
        // If no platform catalogs exist (or they completed during disk restore), release immediately.
        if (pendingPlatformCatalogKeys.isEmpty()) {
            triggerPlatformPreloadIfReady()
        }
    } catch (e: Exception) {
        catalogsLoadInProgress = false
        _uiState.update { it.copy(isLoading = false, error = e.message) }
    }
    } // end withLock
}

internal fun HomeViewModel.loadCatalogPipeline(
    addon: Addon,
    catalog: CatalogDescriptor,
    generation: Long,
    profileId: Int,
    diskRestoreJob: kotlinx.coroutines.Job? = null
) {
    val loadJob = viewModelScope.launch {
        var hasCountedCompletion = false
        catalogLoadSemaphore.withPermit {
            if (
                generation != catalogLoadGeneration ||
                profileId != profileManager.activeProfileId.value
            ) {
                return@withPermit
            }
            val supportsSkip = catalog.supportsExtra("skip")
            val skipStep = catalog.skipStep()
            Log.d(
                HomeViewModel.TAG,
                "Loading home catalog addonId=${addon.id} addonName=${addon.name} type=${catalog.apiType} catalogId=${catalog.id} catalogName=${catalog.name} supportsSkip=$supportsSkip skipStep=$skipStep"
            )
            catalogRepository.getCatalog(
                addonBaseUrl = addon.baseUrl,
                addonId = addon.id,
                addonName = addon.displayName,
                catalogId = catalog.id,
                catalogName = catalog.name,
                type = catalog.apiType,
                skip = 0,
                skipStep = skipStep,
                supportsSkip = supportsSkip
            ).collect { result ->
                if (
                    generation != catalogLoadGeneration ||
                    profileId != profileManager.activeProfileId.value
                ) {
                    return@collect
                }
                when (result) {
                    is NetworkResult.Success -> {
                        val key = catalogKey(
                            addonId = addon.id,
                            type = catalog.apiType,
                            catalogId = catalog.id
                        )
                        val existingRow = catalogsMap[key]
                        val mergedRow = if (existingRow != null) {
                            val enrichedById = existingRow.items
                                .filter { it.ageRating != null || it.status != null || it.logo != null }
                                .associateBy { it.id }
                            if (enrichedById.isNotEmpty()) {
                                val mergedItems = result.data.items.map { freshItem ->
                                    val enriched = enrichedById[freshItem.id]
                                    if (enriched != null) {
                                        freshItem.copy(
                                            ageRating = enriched.ageRating ?: freshItem.ageRating,
                                            status = enriched.status ?: freshItem.status,
                                            logo = enriched.logo ?: freshItem.logo
                                        )
                                    } else freshItem
                                }
                                result.data.copy(items = mergedItems)
                            } else result.data
                        } else result.data
                        catalogSourceRows[key] = mergedRow

                        // Preserve an already-expanded row during a cached/fresh
                        // refresh, but never expose more than 25 on first load.
                        val exposedCount = maxOf(
                            HOME_CATALOG_WINDOW_SIZE,
                            existingRow?.items?.size ?: 0
                        )
                        val exposedItems = mergedRow.items.take(exposedCount)
                        val visibleRow = mergedRow.copy(
                            items = exposedItems,
                            hasMore = mergedRow.items.size > exposedItems.size ||
                                mergedRow.hasMore
                        )

                        val preEnriched = existingRow?.items?.count { it.ageRating != null } ?: 0
                        val postEnriched = visibleRow.items.count { it.ageRating != null }
                        if (preEnriched > 0) {
                        }
                        catalogsMap[key] = visibleRow
                        // Auto-detect addon-signaled landscape rows: if the majority of
                        // items carry posterShape=LANDSCAPE, treat this row as landscape
                        // without touching the user's persisted per-catalog preferences.
                        val landscapeItemCount = visibleRow.items.count {
                            it.posterShape == com.nuvio.tv.domain.model.PosterShape.LANDSCAPE
                        }
                        val totalItemCount = visibleRow.items.size
                        val addonSignalsLandscape = totalItemCount > 0 &&
                            landscapeItemCount * 2 >= totalItemCount
                        if (addonSignalsLandscape) {
                            if (addonSignaledLandscapeKeys.add(key)) {
                                _uiState.update { s ->
                                    s.copy(landscapeCatalogKeys = s.landscapeCatalogKeys + key)
                                }
                            }
                        }
                        /*
                         * Landscape rows now use the shared viewport-priority
                         * scheduler below. This avoids a second independent
                         * enrichment batch competing with launch and trailers.
                         */
                        // Hide spinner immediately on first catalog result — don't wait for debounce
                        if (!hasRenderedFirstCatalog && mergedRow.items.isNotEmpty()) {
                            _uiState.update { it.copy(isLoading = false) }
                        }
                        val stomped2 = visibleRow.items.filter { it.ageRating == null }
                        val had = existingRow?.items?.filter { it.ageRating != null } ?: emptyList()
                        if (had.isNotEmpty() && stomped2.any { item -> had.any { it.id == item.id } }) {
                        }
                        if (!hasCountedCompletion) {
                            pendingCatalogLoads = (pendingCatalogLoads - 1).coerceAtLeast(0)
                            hasCountedCompletion = true
                        }
                        val successKey = catalogKey(addonId = addon.id, type = catalog.apiType, catalogId = catalog.id)
                        if (pendingPlatformCatalogKeys.remove(successKey)) {
                            triggerPlatformPreloadIfReady()
                        }
                        Log.d(
                            HomeViewModel.TAG,
                            "Home catalog loaded addonId=${addon.id} type=${catalog.apiType} catalogId=${catalog.id} items=${result.data.items.size} pending=$pendingCatalogLoads"
                        )
                        if (pendingCatalogLoads == 0) {
                            catalogsLoadInProgress = false
                            val saveProfileId = profileId
                            viewModelScope.launch {
                                // Small delay to let the final scheduleUpdateCatalogRows settle
                                kotlinx.coroutines.delay(500)
                                diskRestoreJob?.join()
                                if (
                                    generation == catalogLoadGeneration &&
                                    saveProfileId == profileManager.activeProfileId.value &&
                                    pendingCatalogLoads == 0
                                ) {
                                    catalogRepository.saveCatalogsToDisk(
                                        saveProfileId
                                    )
                                }
                            }
                        }
                        scheduleUpdateCatalogRows()
                    }
                    is NetworkResult.Error -> {
                        if (!hasCountedCompletion) {
                            pendingCatalogLoads = (pendingCatalogLoads - 1).coerceAtLeast(0)
                            hasCountedCompletion = true
                        }
                        val errorKey = catalogKey(addonId = addon.id, type = catalog.apiType, catalogId = catalog.id)
                        if (pendingPlatformCatalogKeys.remove(errorKey)) {
                            triggerPlatformPreloadIfReady()
                        }
                        Log.w(
                            HomeViewModel.TAG,
                            "Home catalog failed addonId=${addon.id} type=${catalog.apiType} catalogId=${catalog.id} code=${result.code} message=${result.message}"
                        )
                        if (pendingCatalogLoads == 0) {
                            catalogsLoadInProgress = false
                            val saveProfileId =
                                profileId

                            /*
                             * Successful catalogs must still be persisted when
                             * the final catalog finishes with an error. Previously
                             * disk saving happened only when the final completion
                             * was successful, so one failing catalog could leave
                             * the next launch without the completed catalog cache.
                             */
                            viewModelScope.launch {
                                kotlinx.coroutines.delay(500)
                                diskRestoreJob?.join()
                                if (
                                    generation == catalogLoadGeneration &&
                                    saveProfileId == profileManager.activeProfileId.value &&
                                    pendingCatalogLoads == 0
                                ) {
                                    catalogRepository.saveCatalogsToDisk(
                                        saveProfileId
                                    )
                                }
                            }
                        }
                        scheduleUpdateCatalogRows()
                    }
                    NetworkResult.Loading -> {
                        /* Handled by individual row */
                    }
                }
            }
        }
    }
    registerCatalogLoadJob(loadJob)
}

internal fun HomeViewModel.triggerPlatformPreloadIfReady() {
    if (platformPreloadTriggered) return
    if (pendingPlatformCatalogKeys.isNotEmpty()) return
    platformPreloadTriggered = true
    // Collect first backdrop URL per platform from current catalogsMap
    val platformRows = catalogsMap.values
        .filter { it.items.isNotEmpty() && inferPlatformId(it.catalogName) != null }
        .groupBy { inferPlatformId(it.catalogName) }
    val backdropUrls = mutableListOf<String>()
    val platformIds = mutableSetOf<String>()
    for ((platformId, rows) in platformRows) {
        if (platformId == null) continue
        platformIds.add(platformId)
        val firstItem = rows.firstOrNull()?.items?.firstOrNull() ?: continue
        val backdrop = firstItem.backdropUrl
        if (!backdrop.isNullOrBlank()) backdropUrls.add(backdrop)
    }
    // Set stableVisiblePlatformIds immediately so the icon row knows which platforms exist
    _uiState.update { it.copy(stableVisiblePlatformIds = platformIds) }
    // Wait for Compose to push render dimensions before preloading — sizes must
    // match what ModernHeroMediaLayer requests or the Coil cache keys won't align
    // and the preload warms useless entries (slow launches then pay full load on
    // first platform visit). Dimensions are guaranteed once the hero composes, so
    // waiting is safe; 10s bound is only a leak guard. Never preload unsized.
    viewModelScope.launch {
        val deadline = System.currentTimeMillis() + 10_000L
        while (backdropPreloadWidthPx == 0 || backdropPreloadHeightPx == 0) {
            if (System.currentTimeMillis() >= deadline) return@launch
            kotlinx.coroutines.delay(16L)
        }
        preloadPlatformBackdrops(backdropUrls)
    }
}

internal fun HomeViewModel.loadMoreCatalogItemsPipeline(catalogId: String, addonId: String, type: String) {
    val key = catalogKey(addonId = addonId, type = type, catalogId = catalogId)
    val currentRow = catalogsMap[key] ?: return

    if (currentRow.isLoading || !currentRow.hasMore) return
    if (key in _loadingCatalogs.value) return

    val sourceRow = catalogSourceRows[key] ?: currentRow

    // The addon may have returned 50, 100, or more items in one server
    // response. Release only the next 25 without performing another request.
    if (sourceRow.items.size > currentRow.items.size) {
        val nextVisibleCount = minOf(
            currentRow.items.size + HOME_CATALOG_WINDOW_SIZE,
            sourceRow.items.size
        )
        val retainedByKey = currentRow.items.associateBy {
            "${it.apiType}:${it.id}"
        }
        val nextVisibleItems = sourceRow.items
            .take(nextVisibleCount)
            .map { sourceItem ->
                retainedByKey["${sourceItem.apiType}:${sourceItem.id}"]
                    ?: sourceItem
            }

        catalogsMap[key] = sourceRow.copy(
            items = nextVisibleItems,
            isLoading = false,
            hasMore = sourceRow.items.size > nextVisibleCount ||
                sourceRow.hasMore
        )

        scheduleUpdateCatalogRows()
        return
    }

    // No locally buffered items remain. A non-paginated addon is finished.
    if (!sourceRow.supportsSkip || !sourceRow.hasMore) {
        catalogsMap[key] = currentRow.copy(
            isLoading = false,
            hasMore = false
        )
        scheduleUpdateCatalogRows()
        return
    }

    catalogsMap[key] = currentRow.copy(isLoading = true)
    _loadingCatalogs.update { it + key }

    viewModelScope.launch {
        val addon = addonsCache.find { it.id == addonId }
        if (addon == null) {
            catalogsMap[key] = currentRow.copy(isLoading = false)
            _loadingCatalogs.update { it - key }
            return@launch
        }

        val exposedCountBeforeFetch = currentRow.items.size
        val nextSkip = (sourceRow.currentPage + 1) * sourceRow.skipStep
        var pageAddedItems = false

        catalogRepository.getCatalog(
            addonBaseUrl = addon.baseUrl,
            addonId = addon.id,
            addonName = addon.displayName,
            catalogId = catalogId,
            catalogName = currentRow.catalogName,
            type = currentRow.apiType,
            skip = nextSkip,
            skipStep = sourceRow.skipStep,
            supportsSkip = sourceRow.supportsSkip
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    val latestSource = catalogSourceRows[key] ?: sourceRow
                    val existingIds = latestSource.items.asSequence()
                        .map { "${it.apiType}:${it.id}" }
                        .toHashSet()

                    val newUniqueItems = result.data.items.filter { item ->
                        "${item.apiType}:${item.id}" !in existingIds
                    }

                    if (newUniqueItems.isNotEmpty()) {
                        pageAddedItems = true
                    }

                    // If the requested page produced no new items at all, the
                    // addon is ignoring skip or has reached its actual end.
                    val remoteHasMore =
                        if (newUniqueItems.isEmpty() && !pageAddedItems) {
                            false
                        } else {
                            result.data.hasMore
                        }

                    val mergedSource = result.data.copy(
                        items = latestSource.items + newUniqueItems,
                        hasMore = remoteHasMore
                    )
                    catalogSourceRows[key] = mergedSource

                    val nextVisibleCount = minOf(
                        exposedCountBeforeFetch + HOME_CATALOG_WINDOW_SIZE,
                        mergedSource.items.size
                    )
                    val retainedByKey =
                        (catalogsMap[key]?.items ?: currentRow.items)
                            .associateBy { "${it.apiType}:${it.id}" }
                    val nextVisibleItems = mergedSource.items
                        .take(nextVisibleCount)
                        .map { sourceItem ->
                            retainedByKey[
                                "${sourceItem.apiType}:${sourceItem.id}"
                            ] ?: sourceItem
                        }

                    catalogsMap[key] = mergedSource.copy(
                        items = nextVisibleItems,
                        isLoading = false,
                        hasMore = mergedSource.items.size > nextVisibleCount ||
                            mergedSource.hasMore
                    )
                    _loadingCatalogs.update { it - key }

                    scheduleUpdateCatalogRows()
                }

                is NetworkResult.Error -> {
                    catalogsMap[key] =
                        (catalogsMap[key] ?: currentRow).copy(
                            isLoading = false
                        )
                    _loadingCatalogs.update { it - key }
                    scheduleUpdateCatalogRows()
                }

                NetworkResult.Loading -> { }
            }
        }
    }
}

private suspend fun HomeViewModel.enrichProactiveHomeItem(
    item: com.nuvio.tv.domain.model.MetaPreview,
    language: String
) {
    if (item.id in homeEnrichmentAttemptedIds) {
        return
    }

    var reachedTerminalResult = false

    /*
     * My List needs a genuine IMDb rating independently of the optional
     * external-detail preference. Detect membership here on the existing
     * proactive background path so no lookup is added to focus or scrolling.
     */
    val forceImdbRatingLookup =
        synchronized(catalogsMap) {
            catalogsMap[HomeViewModel.MY_LIST_CATALOG_KEY]
                ?.items
                ?.any { candidate -> candidate.id == item.id } == true
        }

    /*
     * External metadata is supplementary, but it must not remain unbounded.
     * This includes both a lookup started here and one already owned by a
     * focus/prefetch job.
     */
    suspend fun completeExternalFallback(
        enrichment:
            com.nuvio.tv.core.tmdb.TmdbEnrichment
    ) {
        if (item.imdbRating != null) {
            return
        }

        externalImdbRatingCache[item.id]
            ?.let { cachedRating ->
                updateCatalogItemImdbRating(
                    item.id,
                    cachedRating
                )
                return
            }

        if (
            item.id in prefetchedExternalMetaIds
        ) {
            return
        }

        kotlinx.coroutines.withTimeoutOrNull(
            10_000L
        ) {
            enrichMissingImdbFromExternalMeta(
                item = item,
                allowWhenPrefetchDisabledForImdbRating =
                    forceImdbRatingLookup
            )

            while (
                item.id in
                    externalMetaPrefetchInFlightIds
            ) {
                delay(16L)
            }
        }
    }

    /*
     * Status is part of the settled Home metadata contract. The normal TMDB
     * enrichment occasionally returns without it, so perform the same direct
     * details repair that previously happened after focus, but do it here
     * before this title can become terminal and release its row.
     *
     * This runs only on the existing proactive IO worker, only when details
     * are enabled and status is actually missing, and is strictly bounded.
     */
    suspend fun completeMissingStatus(
        enrichment:
            com.nuvio.tv.core.tmdb.TmdbEnrichment
    ): com.nuvio.tv.core.tmdb.TmdbEnrichment {
        if (
            !currentTmdbSettings.useDetails ||
            !enrichment.status.isNullOrBlank()
        ) {
            return enrichment
        }

        val repairedStatus =
            kotlinx.coroutines.withTimeoutOrNull(
                4_000L
            ) {
                val tmdbId =
                    tmdbService.ensureTmdbId(
                        item.id,
                        item.apiType
                    ) ?: return@withTimeoutOrNull null

                tmdbMetadataService.fetchFreshStatus(
                    tmdbId = tmdbId,
                    contentType = item.type,
                    language = language
                )
            }

        return if (repairedStatus.isNullOrBlank()) {
            enrichment
        } else {
            enrichment.copy(
                status = repairedStatus
            )
        }
    }

    try {
        /*
         * Cached Home TMDB enrichment is a zero-TMDB-network fast path, but
         * cache presence alone is not Home readiness. Finish any configured
         * external/IMDb channel before recording the title as terminal.
         */
        val cachedEnrichment =
            enrichmentCache[item.id]

        if (cachedEnrichment != null) {
            prefetchedTmdbIds.add(
                item.id
            )

            val settledEnrichment =
                completeMissingStatus(
                    cachedEnrichment
                )

            if (settledEnrichment != cachedEnrichment) {
                updateCatalogItemWithTmdb(
                    item.id,
                    settledEnrichment
                )
            }

            completeExternalFallback(
                settledEnrichment
            )

            reachedTerminalResult = true
            return
        }

        val normalizedItemId =
            item.id
                .removePrefix("tmdb:")
                .removePrefix("movie:")
                .removePrefix("series:")
                .substringBefore(':')
                .substringBefore('/')
                .trim()

        val hasResolvableId =
            normalizedItemId.startsWith("tt") ||
                (
                    normalizedItemId.isNotEmpty() &&
                        normalizedItemId.all {
                            character ->
                            character.isDigit()
                        }
                )

        /*
         * An unsupported identifier format is a definitive no-result rather
         * than a temporary network failure.
         */
        if (!hasResolvableId) {
            reachedTerminalResult = true
            return
        }

        /*
         * Startup previously launched every item from the priority rows at
         * once. TMDB failures were collapsed to null and immediately treated
         * as permanent. Retry null results under a strict time and attempt
         * bound so temporary launch pressure cannot release unfinished cards.
         */
        val maximumAttempts = 3
        var attempt = 1
        var enrichment:
            com.nuvio.tv.core.tmdb.TmdbEnrichment? =
            null

        while (
            attempt <= maximumAttempts &&
            enrichment == null
        ) {
            enrichment =
                kotlinx.coroutines.withTimeoutOrNull(
                    12_000L
                ) {
                    val tmdbId =
                        tmdbService.ensureTmdbId(
                            item.id,
                            item.apiType
                        )
                            ?: return@withTimeoutOrNull null

                    tmdbMetadataService.fetchEnrichment(
                        tmdbId = tmdbId,
                        contentType = item.type,
                        language = language
                    )
                }

            if (
                enrichment == null &&
                attempt < maximumAttempts
            ) {
                delay(500L * attempt)
            }

            attempt += 1
        }

        if (enrichment == null) {
            android.util.Log.w(
                HomeViewModel.TAG,
                "Home enrichment exhausted " +
                    "$maximumAttempts attempts " +
                    "for ${item.id}"
            )

            /*
             * Repeated failure is terminal for this Home pass. This is the
             * finite escape hatch that prevents a missing or unreachable title
             * from holding the entire row forever.
             */
            reachedTerminalResult = true
            return
        }

        prefetchedTmdbIds.add(item.id)

        val settledEnrichment =
            completeMissingStatus(
                enrichment
            )

        updateCatalogItemWithTmdb(
            item.id,
            settledEnrichment
        )

        completeExternalFallback(
            settledEnrichment
        )

        reachedTerminalResult = true
    } catch (
        cancellation:
            kotlinx.coroutines.CancellationException
    ) {
        /*
         * Plan replacement is not terminal. The new plan must be able to
         * include this title again.
         */
        throw cancellation
    } catch (error: Exception) {
        android.util.Log.w(
            HomeViewModel.TAG,
            "Home enrichment failed after " +
                "bounded processing for ${item.id}: " +
                error.message
        )

        reachedTerminalResult = true
    } finally {
        if (reachedTerminalResult) {
            homeEnrichmentAttemptedIds.add(item.id)
        }
    }
}

private fun com.nuvio.tv.domain.model.MetaPreview.hasDatedComingSoonSignal(): Boolean {
    val hints = behaviorHints ?: return false
    if (hints.comingSoon != true) return false

    val hasExactDate = hints.releaseDate
        ?.trim()
        ?.let { raw ->
            runCatching { LocalDate.parse(raw) }.isSuccess
        } == true
    val hasReleaseYear = hints.releaseYear
        ?.trim()
        ?.let { year ->
            year.length == 4 && year.all(Char::isDigit)
        } == true

    val hasYearOnlyReleaseDate = hints.releaseDate
        ?.trim()
        ?.let { year ->
            year.length == 4 && year.all(Char::isDigit)
        } == true

    return hasExactDate || hasReleaseYear || hasYearOnlyReleaseDate
}

internal suspend fun HomeViewModel.updateCatalogRowsPipeline() {
    val orderedKeys = catalogOrder.toList()
    val catalogSnapshot = catalogsMap.toMap()
    val allCatalogsLoaded = pendingCatalogLoads == 0 && catalogSnapshot.isNotEmpty()
    val diskCacheRestored = this.diskCacheRestored
    val heroCatalogKeys = currentHeroCatalogKeys
    val currentLayout = _uiState.value.homeLayout
    val currentGridItems = _uiState.value.gridItems
    val heroSectionEnabled = _uiState.value.heroSectionEnabled
    val hideUnreleased = _uiState.value.hideUnreleasedContent

    val (displayRows, baseHeroItems, baseGridItems, fullRowsFiltered) = withContext(Dispatchers.Default) {
        val rawRows = orderedKeys
            .mapNotNull { key -> catalogSnapshot[key] }
            .map { row ->
                if (row.catalogId.startsWith("coming_soon_")) {
                    // Coming Soon is a dated promise. Entries without either a
                    // valid calendar date or a four-digit release year are not
                    // allowed into presentation or proactive enrichment.
                    row.copy(
                        items = row.items.filter { item ->
                            item.hasDatedComingSoonSignal()
                        }
                    )
                } else {
                    row
                }
            }
        val filteredRows = if (hideUnreleased) {
            val today = LocalDate.now()
            rawRows.map { row ->
                /*
                 * Coming Soon catalogs intentionally contain future releases.
                 * Keep them intact even when the global Home setting hides
                 * unreleased content from ordinary catalogs.
                 */
                if (row.catalogId.startsWith("coming_soon_")) {
                    row
                } else {
                    row.filterReleasedItems(today)
                }
            }
        } else {
            rawRows
        }

        // Apply per-catalog shuffle: randomise items for rows with shuffle enabled.
        // Seed is stable per 12h bucket so order only changes every 12 hours.
        val shuffleKeys = shuffledCatalogKeys
        val shuffleTs = lastShuffleTimestampMs
        val twelveHoursMs = 12L * 60 * 60 * 1000
        val seed = shuffleTs / twelveHoursMs
        val orderedRows = if (shuffleKeys.isEmpty()) {
            filteredRows
        } else {
            filteredRows.map { row ->
                val key = row.addonId + "_" + row.apiType + "_" + row.catalogId
                if (key !in shuffleKeys || key.contains("com.bimal.watchly")) return@map row
                /*
                 * Deterministic shuffle.
                 *
                 * An item's position is a pure function of its arrival page and
                 * a hash of (12h seed, catalog key, item id). Nothing depends on
                 * how much of the row has loaded, so every pipeline run during
                 * launch produces the identical order.
                 *
                 * The previous implementation shuffled a list whose contents
                 * were still growing and compared against _uiState, so the same
                 * seed yielded a different order on each run and rows silently
                 * reordered themselves while the user was navigating.
                 *
                 * Sorting by page bucket first preserves the append-only
                 * property of pagination: page 2 items always rank after page 1
                 * items, so already-visible posters never shift position.
                 */
                val rowSalt = seed + key.hashCode().toLong()
                val pageSize = 25
                row.copy(
                    items = row.items
                        .withIndex()
                        .sortedWith(
                            compareBy(
                                { (index, _) -> index / pageSize },
                                { (_, item) ->
                                    var h = rowSalt xor
                                        item.id.hashCode().toLong()
                                    h = h xor (h ushr 33)
                                    h *= -0xae502812aa7333L
                                    h = h xor (h ushr 29)
                                    h *= -0x3b314601e57a13adL
                                    h xor (h ushr 32)
                                }
                            )
                        )
                        .map { (_, item) -> item }
                )
            }
        }
        val selectedHeroCatalogSet = heroCatalogKeys.toSet()
        val selectedHeroRows = if (selectedHeroCatalogSet.isNotEmpty()) {
            orderedRows.filter { row ->
                val key = "${row.addonId}_${row.apiType}_${row.catalogId}"
                key in selectedHeroCatalogSet
            }
        } else {
            emptyList()
        }
        val heroItemsFromSelectedCatalogs = selectedHeroRows
            .asSequence()
            .flatMap { row -> row.items.asSequence() }
            .filter { item -> item.hasHeroArtwork() }
            .shuffled()
            .take(7)
            .toList()
        val fallbackHeroItemsFromSelectedCatalogs = selectedHeroRows
            .asSequence()
            .flatMap { row -> row.items.asSequence() }
            .shuffled()
            .take(7)
            .toList()

        val fallbackHeroItemsWithArtwork = orderedRows
            .asSequence()
            .flatMap { it.items.asSequence() }
            .filter { it.hasHeroArtwork() }
            .shuffled()
            .take(7)
            .toList()

        val computedHeroItems = when {
            heroItemsFromSelectedCatalogs.isNotEmpty() -> heroItemsFromSelectedCatalogs
            fallbackHeroItemsFromSelectedCatalogs.isNotEmpty() -> fallbackHeroItemsFromSelectedCatalogs
            fallbackHeroItemsWithArtwork.isNotEmpty() -> fallbackHeroItemsWithArtwork
            else -> emptyList()
        }

        // catalogsMap already contains only the currently released
        // 25-item windows, so every Home layout receives the same bounded rows.
        val computedDisplayRows = orderedRows

        val computedGridItems = if (currentLayout == HomeLayout.GRID) {
            buildList {
                if (heroSectionEnabled && computedHeroItems.isNotEmpty()) {
                    add(GridItem.Hero(computedHeroItems))
                }
                computedDisplayRows.filter { it.items.isNotEmpty() }.forEach { row ->
                    add(
                        GridItem.SectionDivider(
                            catalogName = row.catalogName,
                            catalogId = row.catalogId,
                            addonBaseUrl = row.addonBaseUrl,
                            addonId = row.addonId,
                            type = row.apiType
                        )
                    )
                    val hasEnoughForSeeAll = row.items.size >= 15
                    val displayItems = if (hasEnoughForSeeAll) row.items.take(14) else row.items.take(15)
                    displayItems.forEach { item ->
                        add(
                            GridItem.Content(
                                item = item,
                                addonBaseUrl = row.addonBaseUrl,
                                catalogId = row.catalogId,
                                catalogName = row.catalogName
                            )
                        )
                    }
                    if (hasEnoughForSeeAll) {
                        add(
                            GridItem.SeeAll(
                                catalogId = row.catalogId,
                                addonId = row.addonId,
                                type = row.apiType
                            )
                        )
                    }
                }
            }
        } else {
            currentGridItems
        }

        CatalogUpdateResult(computedDisplayRows, computedHeroItems, computedGridItems, orderedRows)
    }

    _fullCatalogRows.update { rows ->
        if (rows == fullRowsFiltered) rows else fullRowsFiltered
    }

    val nextGridItems = if (currentLayout == HomeLayout.GRID) {
        replaceGridHeroItemsPipeline(baseGridItems, baseHeroItems)
    } else {
        baseGridItems
    }

    _uiState.update { state ->
        // Re-read catalogsMap inside the atomic update to capture enrichment
        // that landed after the snapshot was taken at the top of this pipeline run.
        // Merge enrichment: for each item, prefer existing uiState enrichment over
        // fresh display row data, so pipeline runs never stomp already-enriched badges.
        // enrichmentCache is the source of truth for TMDB enrichment — it covers
        // items from all row types (catalogsMap rows AND special rows like Continue Watching).
        val freshRows = displayRows.map { row ->
            if (enrichmentCache.isEmpty()) row
            else row.copy(items = row.items.map { item ->
                val cached = enrichmentCache[item.id]
                if (cached == null) item
                else {
                    var merged = item
                    if (currentTmdbSettings.useBasicInfo) {
                        merged = merged.copy(
                            name = cached.localizedTitle ?: merged.name,
                            description = cached.description ?: merged.description,
                            genres = if (cached.genres.isNotEmpty()) cached.genres else merged.genres
                        )
                    }
                    if (currentTmdbSettings.useArtwork) {
                        merged = merged.copy(
                            /*
                             * TmdbMetadataService stores a verified MetaHub or
                             * metadata-addon logo in fallbackLogoUrl when TMDB
                             * itself has no suitable logo. The direct update
                             * path already uses it; the state rebuild must not
                             * silently discard it.
                             */
                            logo =
                                cached.logo
                                    ?: cached.fallbackLogoUrl
                                    ?: merged.logo,
                            // Only use detailBackdrop — don't fall back to background to avoid
                            // a visible flash when TMDB enrichment later provides the real image.
                            landscapePoster = cached.detailBackdrop ?: merged.landscapePoster
                        )
                    } else {
                        // Even with artwork disabled, set landscapePoster to background
                        // so landscape rows show an image (same as home backdrop)
                        merged = merged.copy(
                            landscapePoster = merged.landscapePoster ?: merged.background
                        )
                    }
                    if (currentTmdbSettings.useDetails) {
                        merged = merged.copy(
                            ageRating = cached.ageRating ?: merged.ageRating,
                            status = cached.status ?: merged.status,
                            runtime = cached.runtimeMinutes?.toString() ?: merged.runtime
                        )
                    }
                    merged
                }
            })
        }
        val enrichedHeroItems = if (enrichmentCache.isEmpty()) baseHeroItems
        else baseHeroItems.map { item ->
            val cached = enrichmentCache[item.id] ?: return@map item
            var merged = item
            if (currentTmdbSettings.useBasicInfo) {
                merged = merged.copy(
                    name = cached.localizedTitle ?: merged.name,
                    description = cached.description ?: merged.description,
                    genres = if (cached.genres.isNotEmpty()) cached.genres else merged.genres
                )
            }
            if (currentTmdbSettings.useArtwork) {
                merged = merged.copy(
                    logo =
                        cached.logo
                            ?: cached.fallbackLogoUrl
                            ?: merged.logo
                )
            }
            if (currentTmdbSettings.useDetails) {
                merged = merged.copy(
                    ageRating = cached.ageRating ?: merged.ageRating,
                    status = cached.status ?: merged.status
                )
            }
            merged
        }
        // Apply enrichment to ML row too — it's not in displayRows so freshRows misses it
        // Always include ML row — prefer catalogSnapshot, fall back to state.catalogRows
        // so the row survives even if catalogsMap was snapshotted before observeMyList injected it
        val rawMlRow = catalogsMap[HomeViewModel.MY_LIST_CATALOG_KEY]
        val enrichedMlRow = rawMlRow?.let { mlRow ->
            if (enrichmentCache.isEmpty()) mlRow
            else {
                val enrichedItems = mlRow.items.map { item ->
                    val cached = enrichmentCache[item.id] ?: return@map item
                    var merged = item
                    if (currentTmdbSettings.useBasicInfo) {
                        merged = merged.copy(
                            name = cached.localizedTitle ?: merged.name,
                            description = cached.description ?: merged.description,
                            genres = if (cached.genres.isNotEmpty()) cached.genres else merged.genres
                        )
                    }
                    if (currentTmdbSettings.useArtwork) {
                        merged = merged.copy(
                            // Same metahub fallback as updateCatalogItemWithTmdb — this path
                            // rebuilds from enrichmentCache directly so it needs its own fallback,
                            // otherwise a null TMDB logo here stomps the value set elsewhere.
                            logo = cached.logo ?: cached.fallbackLogoUrl ?: run {
                                val imdbId = merged.imdbId
                                    ?: item.id.removePrefix("tmdb:").toIntOrNull()
                                        ?.let { tmdbService.getCachedImdbId(it) }
                                    ?: if (item.id.startsWith("tt")) item.id else null
                                imdbId?.let { "https://images.metahub.space/logo/medium/$it/img" }
                            } ?: merged.logo,
                            landscapePoster = cached.detailBackdrop ?: merged.landscapePoster
                        )
                    }
                    if (currentTmdbSettings.useDetails) {
                        merged = merged.copy(
                            ageRating = cached.ageRating ?: merged.ageRating,
                            status = cached.status ?: merged.status,
                            runtime = cached.runtimeMinutes?.toString() ?: merged.runtime
                        )
                    }
                    if (currentTmdbSettings.useBasicInfo) {
                        merged = merged.copy(
                            imdbRating = merged.imdbRating
                        )
                    }
                    merged
                }
                mlRow.copy(items = enrichedItems)
            }
        }
        // Build finalRows: freshRows (addon catalogs) + ML row at its catalogOrder position
        val finalRows = if (enrichedMlRow != null) {
            val withoutMl = freshRows.filter { it.addonId != HomeViewModel.MY_LIST_ADDON_ID }
            val mlIndex = orderedKeys.indexOf(HomeViewModel.MY_LIST_CATALOG_KEY)
            if (mlIndex <= 0) {
                listOf(enrichedMlRow) + withoutMl
            } else {
                val insertAt = mlIndex.coerceAtMost(withoutMl.size)
                withoutMl.toMutableList().apply { add(insertAt, enrichedMlRow) }
            }
        } else {
            freshRows.filter { it.addonId != HomeViewModel.MY_LIST_ADDON_ID }
        }
        val tmdbEnabled = currentTmdbSettings.enabled
        val prevReadyKeys: Set<String> = state.enrichmentReadyRowKeys
        val nextReadyKeys = java.util.LinkedHashSet<String>(prevReadyKeys)
        // When TMDB is disabled, My List may not be in finalRows yet (observeMyList
        // is async) but we still want it to be considered ready so it doesn't shimmer.
        // Also mark any row currently in catalogsMap with items as ready.
        if (!tmdbEnabled) {
            nextReadyKeys.add(HomeViewModel.MY_LIST_CATALOG_KEY)
        }
        val restorePending = tmdbEnabled && !enrichmentRestoreComplete
        finalRows.forEach { row ->
            val rowKey: String = row.key()
            if (nextReadyKeys.contains(rowKey) || row.items.isEmpty()) return@forEach
            // Defer readiness judgment until the disk cache is fully restored —
            // judging against a half-restored cache opens rows unevenly enriched.
            if (restorePending) return@forEach
            if (!tmdbEnabled) {
                nextReadyKeys.add(rowKey)
                return@forEach
            }

            /*
             * A logo, age rating, or status supplied by an add-on does not
             * prove that TMDB artwork and the remaining hero metadata have
             * finished loading.
             *
             * Release the row only after every currently exposed title has
             * reached a terminal proactive-enrichment result.
             */
            val allItemsTerminal =
                row.items.all { item ->
                    homeEnrichmentAttemptedIds.contains(
                        item.id
                    )
                }

            if (allItemsTerminal) {
                nextReadyKeys.add(rowKey)
            }
        }
        state.copy(
            catalogRows = if (state.catalogRows == finalRows) state.catalogRows else finalRows,
            heroItems = if (state.heroItems == enrichedHeroItems) state.heroItems else enrichedHeroItems,
            gridItems = if (state.gridItems == nextGridItems) state.gridItems else nextGridItems,
            isLoading = false,
            catalogsReady = state.catalogsReady || allCatalogsLoaded || diskCacheRestored,
            stableVisiblePlatformIds = if (allCatalogsLoaded || diskCacheRestored) {
                displayRows
                    .filter { it.items.isNotEmpty() }
                    .mapNotNull { inferPlatformId(it.catalogName) }
                    .toSet()
            } else {
                state.stableVisiblePlatformIds
            },
            enrichmentReadyRowKeys = nextReadyKeys
        )
    }

    // Platform backdrop preload is now triggered from triggerPlatformPreloadIfReady()
    // which fires as soon as all platform-categorized catalogs resolve (success or error),
    // scoped to just that subset rather than waiting for disk cache or all catalogs.

    val tmdbSettings = currentTmdbSettings
    val shouldUseEnrichedHeroItems = tmdbSettings.enabled &&
        (tmdbSettings.useArtwork || tmdbSettings.useBasicInfo || tmdbSettings.useDetails)

    if (shouldUseEnrichedHeroItems && baseHeroItems.isNotEmpty()) {
        heroEnrichmentJob?.cancel()
        heroEnrichmentJob = viewModelScope.launch {
            val enrichmentSignature = heroEnrichmentSignaturePipeline(baseHeroItems, tmdbSettings)
            if (lastHeroEnrichmentSignature == enrichmentSignature) {
                val cached = lastHeroEnrichedItems
                _uiState.update { state ->
                    state.copy(
                        heroItems = if (state.heroItems == cached) state.heroItems else cached,
                        gridItems = if (currentLayout == HomeLayout.GRID) {
                            val enrichedGrid = replaceGridHeroItemsPipeline(state.gridItems, cached)
                            if (state.gridItems == enrichedGrid) state.gridItems else enrichedGrid
                        } else state.gridItems
                    )
                }
            } else {
                val enrichedItems = enrichHeroItemsPipeline(baseHeroItems, tmdbSettings)
                lastHeroEnrichmentSignature = enrichmentSignature
                lastHeroEnrichedItems = enrichedItems
                _uiState.update { state ->
                    state.copy(
                        heroItems = if (state.heroItems == enrichedItems) state.heroItems else enrichedItems,
                        gridItems = if (currentLayout == HomeLayout.GRID) {
                            val enrichedGrid = replaceGridHeroItemsPipeline(state.gridItems, enrichedItems)
                            if (state.gridItems == enrichedGrid) state.gridItems else enrichedGrid
                        } else state.gridItems
                    )
                }
            }
        }
    } else {
        lastHeroEnrichmentSignature = null
        lastHeroEnrichedItems = emptyList()
    }

    // Proactive enrichment — enrich all catalog items in the background as rows load,
    // so badges appear without requiring the user to focus each item first.
    //
    // Cached catalog rows can become available before HomeEnrichmentDiskCache
    // finishes restoring on a true process cold launch. Starting proactive
    // work during that window would refetch every cached title unnecessarily.
    if (
        currentTmdbSettings.enabled &&
        enrichmentRestoreComplete
    ) {
        // Enrich items from earlier rows first so visible content gets badges sooner
        val rowIndexById = displayRows
            .flatMapIndexed { rowIdx, row -> row.items.map { it.id to rowIdx } }
            .toMap()
        // Include My List items in enrichment — they're in catalogsMap but not displayRows
        val myListRow = catalogsMap[HomeViewModel.MY_LIST_CATALOG_KEY]
        val myListItems = myListRow?.items ?: emptyList()
        val allItems = (displayRows.flatMap { it.items } + myListItems)
            .distinctBy { it.id }
            /*
             * Every unterminated title must pass through the completion path.
             * Cached TMDB enrichment takes the zero-network fast path there,
             * while any required external/IMDb channel is allowed to settle
             * before readiness is recorded.
             */
            .filter {
                it.id !in homeEnrichmentAttemptedIds
            }
            .sortedBy { rowIndexById[it.id] ?: Int.MAX_VALUE }
        val myListItemIds = myListItems.map { it.id }.toSet()

        val proactivePlanSignature = buildString {
            append(catalogLoadGeneration)
            append('|')
            append(currentTmdbSettings.language)
            append('|')
            append(currentTmdbSettings.useArtwork)
            append('|')
            append(currentTmdbSettings.useBasicInfo)
            append('|')
            append(currentTmdbSettings.useDetails)
            append('|')
            append(externalMetaPrefetchEnabled)

            /*
             * Viewport movement changes ordering preference, not the identity
             * of the enrichment work. Excluding row priority prevents vertical
             * and platform navigation from canceling and recreating the job.
             */
            displayRows.forEach { row ->
                append('|')
                append(row.key())
                append(':')

                row.items.forEach { item ->
                    append(item.id)
                    append(',')
                }
            }

            append("|my-list:")

            myListItems.forEach { item ->
                append(item.id)
                append(',')
            }
        }

        val enrichmentPlanChanged =
            homeEnrichmentPlanSignature != proactivePlanSignature

        /*
         * A matching signature proves only that this plan was scheduled
         * previously. It does not prove that its worker is still alive.
         *
         * If the coroutine is cancelled or terminates before every pending
         * title reaches a terminal result, later row updates must restart the
         * same plan. Otherwise those rows remain gated until focus enrichment
         * happens to process them individually.
         */
        val enrichmentWorkerMissing =
            proactiveEnrichJob?.isActive != true

        if (
            allItems.isNotEmpty() &&
            (
                enrichmentPlanChanged ||
                    enrichmentWorkerMissing
            )
        ) {
            homeEnrichmentPlanSignature =
                proactivePlanSignature
            val tmdbSettingsSnapshot = currentTmdbSettings
            /*
             * Use the actual Modern Home viewport when available. Before
             * LazyColumn reports its layout, prioritize the first two rows.
             * My List retains priority regardless of its rendered position.
             */
            val displayRowsByKey = displayRows.associateBy { it.key() }

            val reportedPriorityKeys = modernHomePriorityRowKeys.filter {
                it in displayRowsByKey
            }

            val priorityRowKeys =
                if (reportedPriorityKeys.isNotEmpty()) {
                    reportedPriorityKeys
                } else {
                    displayRows
                        .take(2)
                        .map { it.key() }
                }

            val priorityItemIds = buildSet {
                priorityRowKeys.forEach { rowKey ->
                    displayRowsByKey[rowKey]
                        ?.items
                        ?.forEach { item ->
                            add(item.id)
                        }
                }

                addAll(myListItemIds)
            }

            val priorityItems = allItems.filter { item ->
                item.id in priorityItemIds
            }

            val remainingItems = allItems.filter { item ->
                item.id !in priorityItemIds
            }
            proactiveEnrichJob?.cancel()
            proactiveEnrichJob = viewModelScope.launch(Dispatchers.IO) {
                /*
                 * Persist long-running proactive progress without touching the
                 * focus/scroll path. This worker already runs on Dispatchers.IO.
                 *
                 * A fixed 30-second throttle (not a per-item debounce) guarantees
                 * that a continuously running queue eventually reaches disk while
                 * avoiding frequent serialization or file writes. Intermediate
                 * writes are skipped during hero playback.
                 */
                var lastProgressPersistMs =
                    android.os.SystemClock.elapsedRealtime()

                fun externalMetaStateSnapshot():
                    Map<
                        String,
                        com.nuvio.tv.data.local.HomeExternalMetaState
                    > {
                    val settledIds =
                        synchronized(
                            prefetchedExternalMetaIds
                        ) {
                            prefetchedExternalMetaIds
                                .toList()
                        }

                    return settledIds.associateWith {
                            id ->
                        com.nuvio.tv.data.local
                            .HomeExternalMetaState(
                                settled = true,
                                imdbRating =
                                    externalImdbRatingCache[id]
                            )
                    }
                }

                suspend fun persistProgressIfDue() {
                    val now =
                        android.os.SystemClock.elapsedRealtime()

                    if (
                        homeHeroTrailerPlaying ||
                        now - lastProgressPersistMs < 30_000L
                    ) {
                        return
                    }

                    val snapshot =
                        synchronized(enrichmentCache) {
                            enrichmentCache.toMap()
                        }

                    homeEnrichmentDiskCache.saveAll(
                        snapshot,
                        externalMetaStateSnapshot()
                    )
                    lastProgressPersistMs = now
                }

                /*
                 * Visible and adjacent rows retain full priority and continue
                 * during hero playback.
                 */
                /*
                 * A TMDB enrichment fans out into details, credits, images,
                 * ratings, and occasionally fallback-logo requests. Launching
                 * every title from two complete rows simultaneously can create
                 * hundreds of requests and turn temporary failures into missing
                 * artwork. Keep four titles active at a time.
                 */
                priorityItems
                    .chunked(4)
                    .forEach { batch ->
                        coroutineScope {
                            batch.map { item ->
                                async {
                                    enrichProactiveHomeItem(
                                        item = item,
                                        language =
                                            tmdbSettingsSnapshot.language
                                    )
                                }
                            }.awaitAll()
                        }

                        /*
                         * Terminal readiness is still recorded per title, but
                         * publish it once per completed batch. Rebuilding Home
                         * once for every title creates avoidable startup churn.
                         */
                        scheduleUpdateCatalogRows()
                        persistProgressIfDue()
                    }
                /*
                 * Never completely suspend lower-row enrichment behind trailer
                 * playback. A stale trailer-playing flag previously left every
                 * non-priority Home and platform row gated indefinitely.
                 *
                 * Keep contention minimal by processing one title at a time
                 * while a trailer is active, then return to two-title batches
                 * when playback is no longer active.
                 */
                var remainingIndex = 0

                while (remainingIndex < remainingItems.size) {
                    val trailerActive =
                        homeHeroTrailerPlaying

                    val batchSize =
                        if (trailerActive) 1 else 2

                    val batchEnd =
                        (remainingIndex + batchSize)
                            .coerceAtMost(remainingItems.size)

                    val batch =
                        remainingItems.subList(
                            remainingIndex,
                            batchEnd
                        )

                    if (trailerActive) {
                        delay(250L)
                    }

                    coroutineScope {
                        batch.map { item ->
                            async {
                                enrichProactiveHomeItem(
                                    item = item,
                                    language =
                                        tmdbSettingsSnapshot.language
                                )
                            }
                        }.awaitAll()
                    }

                    scheduleUpdateCatalogRows()
                    persistProgressIfDue()
                    remainingIndex = batchEnd
                }
                // Final safety-net save after the complete proactive pass.
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val snapshot =
                        synchronized(enrichmentCache) {
                            enrichmentCache.toMap()
                        }
                    homeEnrichmentDiskCache.saveAll(
                        snapshot,
                        externalMetaStateSnapshot()
                    )
                }
            }
        } else if (allItems.isEmpty()) {
            homeEnrichmentPlanSignature =
                proactivePlanSignature
        }
    } else if (!currentTmdbSettings.enabled) {
        proactiveEnrichJob?.cancel()
        proactiveEnrichJob = null
        homeEnrichmentPlanSignature = null
    } else {
        /*
         * Enrichment cache restoration is still pending. The catalog rows may
         * be visible as skeletons, but no TMDB or external-metadata work may
         * begin until restoration has identified which titles are already
         * complete.
         *
         * loadAllCatalogsPipeline() normally cancels the previous job before
         * entering this state. Cancel again defensively so an older plan can
         * never continue through a reload race.
         */
        proactiveEnrichJob?.cancel()
        proactiveEnrichJob = null
        homeEnrichmentPlanSignature = null
    }

    schedulePosterStatusReconcilePipeline(displayRows)
}

internal fun HomeViewModel.schedulePosterStatusReconcilePipeline(rows: List<CatalogRow>) {
    posterStatusReconcileJob?.cancel()
    if (rows.isEmpty()) {
        reconcilePosterStatusObserversPipeline(rows)
        return
    }
    // Start the movie-watched batch observer immediately so Trakt watched state
    // begins flowing without waiting for the library-membership debounce.
    startMovieWatchedObserverIfNeeded(rows)
    posterStatusReconcileJob = viewModelScope.launch {
        delay(500)
        reconcilePosterStatusObserversPipeline(rows)
    }
}

internal fun HomeViewModel.startMovieWatchedObserverIfNeeded(rows: List<CatalogRow>) {
    val allMovieItemsByKey = linkedMapOf<String, Set<String>>()
    rows.asSequence()
        .flatMap { row -> row.items.asSequence() }
        .filter { it.apiType.equals("movie", ignoreCase = true) }
        .forEach { item ->
            val key = homeItemStatusKey(item.id, item.apiType)
            if (key !in allMovieItemsByKey) {
                allMovieItemsByKey[key] = homeMovieWatchedLookupIds(
                    itemId = item.id,
                    imdbId = item.imdbId
                )
            }
        }
    val desiredMovieKeys = allMovieItemsByKey.keys
    if (desiredMovieKeys == lastMovieWatchedItemKeys && movieWatchedBatchJob?.isActive == true) return
    lastMovieWatchedItemKeys = desiredMovieKeys
    movieWatchedObserverJobs.values.forEach { it.cancel() }
    movieWatchedObserverJobs.clear()
    movieWatchedBatchJob?.cancel()
    if (desiredMovieKeys.isEmpty()) return
    movieWatchedBatchJob = viewModelScope.launch {
        watchProgressRepository.observeWatchedMovieIds()
            .collectLatest { watchedIds ->
                val normalizedWatchedIds = normalizeHomeMovieWatchedIds(watchedIds)
                _uiState.update { state ->
                    val newStatus = buildMap {
                        allMovieItemsByKey.forEach { (statusKey, lookupIds) ->
                            put(statusKey, lookupIds.any(normalizedWatchedIds::contains))
                        }
                    }
                    if (state.movieWatchedStatus == newStatus) state
                    else state.copy(movieWatchedStatus = newStatus)
                }
            }
    }
}

internal fun HomeViewModel.reconcilePosterStatusObserversPipeline(rows: List<CatalogRow>) {
    val desiredLibraryItemsByKey = linkedMapOf<String, Pair<String, String>>()
    rows.asSequence()
        .flatMap { row -> row.items.asSequence() }
        .take(HomeViewModel.MAX_POSTER_STATUS_OBSERVERS)
        .forEach { item ->
            val key = homeItemStatusKey(item.id, item.apiType)
            if (key !in desiredLibraryItemsByKey) {
                desiredLibraryItemsByKey[key] = item.id to item.apiType
            }
        }
    val desiredLibraryKeys = desiredLibraryItemsByKey.keys

    val allMovieItemsByKey = linkedMapOf<String, Set<String>>()
    rows.asSequence()
        .flatMap { row -> row.items.asSequence() }
        .filter { it.apiType.equals("movie", ignoreCase = true) }
        .forEach { item ->
            val key = homeItemStatusKey(item.id, item.apiType)
            if (key !in allMovieItemsByKey) {
                allMovieItemsByKey[key] = homeMovieWatchedLookupIds(
                    itemId = item.id,
                    imdbId = item.imdbId
                )
            }
        }
    val desiredMovieKeys = allMovieItemsByKey.keys

    posterLibraryObserverJobs.keys
        .filterNot { it in desiredLibraryKeys }
        .forEach { staleKey ->
            posterLibraryObserverJobs.remove(staleKey)?.cancel()
        }

    desiredLibraryItemsByKey.forEach { (statusKey, itemRef) ->
        val itemId = itemRef.first
        val itemType = itemRef.second

        if (statusKey !in posterLibraryObserverJobs) {
            posterLibraryObserverJobs[statusKey] = viewModelScope.launch {
                libraryRepository.isInLibrary(itemId = itemId, itemType = itemType)
                    .distinctUntilChanged()
                    .collectLatest { isInLibrary ->
                        _uiState.update { state ->
                            if (state.posterLibraryMembership[statusKey] == isInLibrary) {
                                state
                            } else {
                                state.copy(
                                    posterLibraryMembership = state.posterLibraryMembership + (statusKey to isInLibrary)
                                )
                            }
                        }
                    }
            }
        }
    }

    if (desiredMovieKeys != lastMovieWatchedItemKeys) {
        lastMovieWatchedItemKeys = desiredMovieKeys
        movieWatchedObserverJobs.values.forEach { it.cancel() }
        movieWatchedObserverJobs.clear()
        movieWatchedBatchJob?.cancel()
        seriesWatchedJob?.cancel()

        if (desiredMovieKeys.isNotEmpty()) {
            movieWatchedBatchJob = viewModelScope.launch {
                watchProgressRepository.observeWatchedMovieIds()
                    .collectLatest { watchedIds ->
                        val normalizedWatchedIds = normalizeHomeMovieWatchedIds(watchedIds)
                        _uiState.update { state ->
                            val newStatus = buildMap {
                                allMovieItemsByKey.forEach { (statusKey, lookupIds) ->
                                    put(statusKey, lookupIds.any(normalizedWatchedIds::contains))
                                }
                            }
                            if (state.movieWatchedStatus == newStatus) {
                                state
                            } else {
                                state.copy(movieWatchedStatus = newStatus)
                            }
                        }
                    }
            }
        }

        // Observe fully-watched series IDs from the badge pipeline and map them
        // to the catalog row items so series posters show the watched checkmark.
        val allSeriesItemsByKey = linkedMapOf<String, String>()
        rows.asSequence()
            .flatMap { row -> row.items.asSequence() }
            .filter { it.apiType.equals("series", ignoreCase = true) || it.apiType.equals("tv", ignoreCase = true) }
            .forEach { item ->
                val key = homeItemStatusKey(item.id, item.apiType)
                if (key !in allSeriesItemsByKey) allSeriesItemsByKey[key] = item.id
            }
        if (allSeriesItemsByKey.isNotEmpty()) {
            seriesWatchedJob = viewModelScope.launch {
                fullyWatchedSeriesIds.fullyWatchedSeriesIds
                    .collectLatest { watchedIds ->
                        _uiState.update { state ->
                            val newStatus = buildMap {
                                allSeriesItemsByKey.forEach { (statusKey, contentId) ->
                                    put(statusKey, contentId in watchedIds)
                                }
                            }
                            if (state.seriesWatchedStatus == newStatus) state
                            else state.copy(seriesWatchedStatus = newStatus)
                        }
                    }
            }
        }
    }

    _uiState.update { state ->
        val trimmedLibraryMembership =
            state.posterLibraryMembership.filterKeys { it in desiredLibraryKeys }
        val trimmedMovieWatchedStatus =
            state.movieWatchedStatus.filterKeys { it in desiredMovieKeys }
        val allSeriesKeys = rows.asSequence()
            .flatMap { row -> row.items.asSequence() }
            .filter { it.apiType.equals("series", ignoreCase = true) || it.apiType.equals("tv", ignoreCase = true) }
            .map { homeItemStatusKey(it.id, it.apiType) }
            .toSet()
        val trimmedSeriesWatchedStatus =
            state.seriesWatchedStatus.filterKeys { it in allSeriesKeys }
        val trimmedLibraryPending =
            state.posterLibraryPending.filterTo(linkedSetOf()) { it in desiredLibraryKeys }
        val trimmedMovieWatchedPending =
            state.movieWatchedPending.filterTo(linkedSetOf()) { it in desiredMovieKeys }

        if (
            trimmedLibraryMembership == state.posterLibraryMembership &&
            trimmedMovieWatchedStatus == state.movieWatchedStatus &&
            trimmedSeriesWatchedStatus == state.seriesWatchedStatus &&
            trimmedLibraryPending == state.posterLibraryPending &&
            trimmedMovieWatchedPending == state.movieWatchedPending
        ) {
            state
        } else {
            state.copy(
                posterLibraryMembership = trimmedLibraryMembership,
                movieWatchedStatus = trimmedMovieWatchedStatus,
                seriesWatchedStatus = trimmedSeriesWatchedStatus,
                posterLibraryPending = trimmedLibraryPending,
                movieWatchedPending = trimmedMovieWatchedPending
            )
        }
    }
}

internal fun homeMovieWatchedLookupIds(
    itemId: String,
    imdbId: String?
): Set<String> = normalizeHomeMovieWatchedIds(listOfNotNull(itemId, imdbId))

internal fun normalizeHomeMovieWatchedIds(ids: Iterable<String>): Set<String> = buildSet {
    ids.forEach { value ->
        val id = value.trim().lowercase()
        if (id.isBlank()) return@forEach
        add(id)
        when {
            id.startsWith("imdb:") -> id.substringAfter(':')
                .takeIf(String::isNotBlank)
                ?.let(::add)
            id.startsWith("tt") -> add("imdb:$id")
        }
    }
}
