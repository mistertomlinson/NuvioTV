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

internal fun HomeViewModel.loadHomeCatalogOrderPreferencePipeline() {
    viewModelScope.launch {
        layoutPreferenceDataStore.homeCatalogOrderKeys.collectLatest { keys ->
            homeCatalogOrderKeys = keys
            rebuildCatalogOrder(addonsCache)
            scheduleUpdateCatalogRows()
        }
    }
}

internal fun HomeViewModel.scheduleCatalogPipeline(addons: List<Addon>, forceReload: Boolean = false) {
    catalogReloadTrigger.tryEmit(addons to forceReload)
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
                    layoutPreferenceDataStore.setLastShuffleTimestampMs(now)
                }
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
                scheduleCatalogPipeline(addons)
            }
    }
}

internal suspend fun HomeViewModel.loadAllCatalogsPipeline(
    addons: List<Addon>,
    forceReload: Boolean = false
) {
    if (!isActiveInstance) {
        return
    }
    if (!forceReload && catalogsLoadInProgress) {
        return
    }
    catalogPipelineMutex.withLock {
    val signature = buildHomeCatalogLoadSignature(addons)
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
    val mlProfileId = profileManager.activeProfileId.value
    val mlCached = myListDiskCache.load(mlProfileId)
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
    externalMetaPrefetchInFlightIds.clear()
    externalMetaPrefetchJob?.cancel()
    pendingExternalMetaPrefetchItemId = null
    prefetchedTmdbIds.clear()
    homeEnrichmentAttemptedIds.clear()
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
            val restored = homeEnrichmentDiskCache.loadAll()
            if (restored.isNotEmpty()) {
                enrichmentCache.putAll(restored)
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

        // Load persisted catalog rows from disk and show immediately while network fetches run
        val profileId = profileManager.activeProfileId.value
        val diskCached = catalogRepository.loadCatalogsFromDisk(profileId)
        if (diskCached.isNotEmpty()) {
            diskCached.forEach { (key, row) ->
                catalogSourceRows[key] = row
                val exposedItems = row.items.take(HOME_CATALOG_WINDOW_SIZE)
                catalogsMap[key] = row.copy(
                    items = exposedItems,
                    hasMore = row.items.size > exposedItems.size || row.hasMore
                )
            }
            diskCacheRestored = true
            val matchedKeys = diskCached.keys.count { it in catalogOrder }
            // Show cached rows immediately and hide spinner — network fetches refresh in background
            _uiState.update { it.copy(isLoading = false) }
            updateCatalogRowsPipeline()
            Log.d(HomeViewModel.TAG, "Restored ${diskCached.size} catalog rows from disk for profile $profileId")
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
        _uiState.update { it.copy(skeletonReady = true) }
        updateCatalogRowsPipeline()
        // If no platform catalogs exist, release immediately
        if (pendingPlatformCatalogKeys.isEmpty()) {
            triggerPlatformPreloadIfReady()
        }
        pendingCatalogLoads = catalogsToLoad.size
        catalogsToLoad.forEach { (addon, catalog) ->
            loadCatalogPipeline(addon, catalog, generation)
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
    generation: Long
) {
    val loadJob = viewModelScope.launch {
        var hasCountedCompletion = false
        catalogLoadSemaphore.withPermit {
            if (generation != catalogLoadGeneration) return@withPermit
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
                if (generation != catalogLoadGeneration) return@collect
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
                            val saveProfileId = profileManager.activeProfileId.value
                            viewModelScope.launch {
                                // Small delay to let the final scheduleUpdateCatalogRows settle
                                kotlinx.coroutines.delay(500)
                                if (pendingCatalogLoads == 0) {
                                    catalogRepository.saveCatalogsToDisk(saveProfileId)
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
    val __homeDiagStartedNs =
        android.os.SystemClock.elapsedRealtimeNanos()

    try {

    if (
        item.id in prefetchedTmdbIds ||
        item.id in enrichmentCache ||
        item.id in homeEnrichmentAttemptedIds
    ) {
        return
    }

    var recordTerminalAttempt = false

    try {
        val tmdbId =
            try {
                tmdbService.ensureTmdbId(
                    item.id,
                    item.apiType
                )
            } catch (
                cancellation:
                    kotlinx.coroutines.CancellationException
            ) {
                throw cancellation
            } catch (_: Exception) {
                null
            }

        if (tmdbId == null) {
            recordTerminalAttempt = true
            return
        }

        val enrichment =
            try {
                tmdbMetadataService.fetchEnrichment(
                    tmdbId = tmdbId,
                    contentType = item.type,
                    language = language
                )
            } catch (
                cancellation:
                    kotlinx.coroutines.CancellationException
            ) {
                throw cancellation
            } catch (_: Exception) {
                null
            }

        if (enrichment == null) {
            recordTerminalAttempt = true
            return
        }

        prefetchedTmdbIds.add(item.id)

        updateCatalogItemWithTmdb(
            item.id,
            enrichment
        )

        if (
            item.imdbRating == null &&
            enrichment.rating == null
        ) {
            enrichMissingImdbFromExternalMeta(
                item
            )
        }
    } catch (
        cancellation:
            kotlinx.coroutines.CancellationException
    ) {
        throw cancellation
    } catch (_: Exception) {
        recordTerminalAttempt = true
    } finally {
        if (
            recordTerminalAttempt &&
            homeEnrichmentAttemptedIds.add(item.id)
        ) {
            /*
             * Successful metadata updates already trigger recomputation.
             * A no-result attempt needs its own readiness recomputation.
             */
            scheduleUpdateCatalogRows()
        }
    }

    } finally {
        HomeScrollDiagnostics.recordProactiveEnrichment(
            durationNs =
                android.os.SystemClock
                    .elapsedRealtimeNanos() -
                    __homeDiagStartedNs
        )
    }
}

internal suspend fun HomeViewModel.updateCatalogRowsPipeline() {
    val __homeDiagStartedNs =
        android.os.SystemClock.elapsedRealtimeNanos()

    val __homeDiagBeforeState =
        _uiState.value

    val __homeDiagBeforeRows =
        __homeDiagBeforeState.catalogRows.size

    val __homeDiagBeforeItems =
        __homeDiagBeforeState.catalogRows.sumOf {
            row -> row.items.size
        }

    try {

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
        val rawRows = orderedKeys.mapNotNull { key -> catalogSnapshot[key] }
        val filteredRows = if (hideUnreleased) {
            val today = LocalDate.now()
            rawRows.map { it.filterReleasedItems(today) }
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
                if (key !in shuffleKeys) return@map row
                val rng = java.util.Random(seed + key.hashCode().toLong())
                // Find how many items were already shuffled in the current uiState
                val alreadyShuffled = _uiState.value.catalogRows
                    .find { r -> r.addonId == row.addonId && r.apiType == row.apiType && r.catalogId == row.catalogId }
                    ?.items ?: emptyList<com.nuvio.tv.domain.model.MetaPreview>()
                val alreadyShuffledIds = alreadyShuffled.map { it.id }.toSet()
                val newItems = row.items.filter { it.id !in alreadyShuffledIds }
                if (alreadyShuffled.isEmpty()) {
                    // First load — shuffle everything
                    row.copy(items = row.items.shuffled(rng))
                } else {
                    // Pagination — preserve existing order, shuffle only new items and append
                    row.copy(items = alreadyShuffled + newItems.shuffled(rng))
                }
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
                            logo = cached.logo ?: merged.logo,
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
                merged = merged.copy(logo = cached.logo ?: merged.logo)
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
                            imdbRating = cached.rating?.toFloat() ?: merged.imdbRating
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
            val attempted: Int = row.items.count { item ->
                homeEnrichmentAttemptedIds.contains(item.id) ||
                prefetchedTmdbIds.contains(item.id) ||
                enrichmentCache.containsKey(item.id) ||
                item.ageRating != null ||
                item.status != null ||
                item.logo != null
            }
            val allowOneMiss: Float = (row.items.size - 1).toFloat() / row.items.size.toFloat()
            val threshold: Float = if (row.items.size <= 10) maxOf(0.8f, allowOneMiss) else 0.8f
            if (attempted.toFloat() / row.items.size.toFloat() >= threshold) {
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
    if (currentTmdbSettings.enabled) {
        // Enrich items from earlier rows first so visible content gets badges sooner
        val rowIndexById = displayRows
            .flatMapIndexed { rowIdx, row -> row.items.map { it.id to rowIdx } }
            .toMap()
        // Include My List items in enrichment — they're in catalogsMap but not displayRows
        val myListRow = catalogsMap[HomeViewModel.MY_LIST_CATALOG_KEY]
        val myListItems = myListRow?.items ?: emptyList()
        val allItems = (displayRows.flatMap { it.items } + myListItems)
            .distinctBy { it.id }
            .filter {
                it.id !in prefetchedTmdbIds &&
                    it.id !in enrichmentCache &&
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
            append('|')
            append(modernHomePriorityRowKeys.joinToString(","))

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

        if (
            allItems.isNotEmpty() &&
            enrichmentPlanChanged
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
                 * Visible and adjacent rows retain full priority and continue
                 * during hero playback.
                 */
                coroutineScope {
                    priorityItems.map { item ->
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
                 * Lower-row work is deliberately batched so trailer playback
                 * has a suspension point. A batch already in progress may
                 * finish, but the next batch waits until playback stops.
                 */
                remainingItems
                    .chunked(2)
                    .forEach { batch ->
                        while (homeHeroTrailerPlaying) {
                            delay(100L)
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
                    }
                // Save entire enrichment cache to disk once after all items processed
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    homeEnrichmentDiskCache.saveAll(enrichmentCache.toMap())
                }
            }
        } else if (allItems.isEmpty()) {
            homeEnrichmentPlanSignature =
                proactivePlanSignature
        }
    } else {
        proactiveEnrichJob?.cancel()
        proactiveEnrichJob = null
        homeEnrichmentPlanSignature = null
    }

    schedulePosterStatusReconcilePipeline(displayRows)

    } finally {
        val __homeDiagAfterState =
            _uiState.value

        HomeScrollDiagnostics.recordCatalogUpdate(
            durationNs =
                android.os.SystemClock
                    .elapsedRealtimeNanos() -
                    __homeDiagStartedNs,
            beforeRows =
                __homeDiagBeforeRows,
            beforeItems =
                __homeDiagBeforeItems,
            afterRows =
                __homeDiagAfterState.catalogRows.size,
            afterItems =
                __homeDiagAfterState.catalogRows.sumOf {
                    row -> row.items.size
                }
        )
    }
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
    val allMovieItemsByKey = linkedMapOf<String, String>()
    rows.asSequence()
        .flatMap { row -> row.items.asSequence() }
        .filter { it.apiType.equals("movie", ignoreCase = true) }
        .forEach { item ->
            val key = homeItemStatusKey(item.id, item.apiType)
            if (key !in allMovieItemsByKey) allMovieItemsByKey[key] = item.id
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
                _uiState.update { state ->
                    val newStatus = buildMap {
                        allMovieItemsByKey.forEach { (statusKey, contentId) ->
                            put(statusKey, contentId in watchedIds)
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

    val allMovieItemsByKey = linkedMapOf<String, String>()
    rows.asSequence()
        .flatMap { row -> row.items.asSequence() }
        .filter { it.apiType.equals("movie", ignoreCase = true) }
        .forEach { item ->
            val key = homeItemStatusKey(item.id, item.apiType)
            if (key !in allMovieItemsByKey) {
                allMovieItemsByKey[key] = item.id
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
                        _uiState.update { state ->
                            val newStatus = buildMap {
                                allMovieItemsByKey.forEach { (statusKey, contentId) ->
                                    put(statusKey, contentId in watchedIds)
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


