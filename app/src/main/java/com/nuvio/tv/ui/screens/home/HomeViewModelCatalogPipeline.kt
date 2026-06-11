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
    android.util.Log.e("NuvioCache", "scheduleCatalogPipeline EMIT addons=${addons.size} force=$forceReload thread=${Thread.currentThread().name} stack=${Thread.currentThread().stackTrace.drop(3).take(4).joinToString("|") { it.methodName }}")
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
android.util.Log.d("NuvioTiming", "Catalog load START addons=${addons.size} force=$forceReload ts=${System.currentTimeMillis()}")
    if (!isActiveInstance) {
        android.util.Log.e("NuvioCache", "loadAllCatalogsPipeline SKIPPED stale instance=${System.identityHashCode(this)}")
        return
    }
    if (!forceReload && catalogsLoadInProgress) {
        android.util.Log.e("NuvioCache", "loadAllCatalogsPipeline SKIPPED already in progress")
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
    android.util.Log.e("NuvioCache", "loadAllCatalogsPipeline START generation=$generation force=$forceReload addons=${addons.size}")

    _uiState.update { it.copy(isLoading = true, error = null, installedAddonsCount = addons.size) }
    catalogOrder.clear()
    catalogsMap.clear()
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
    truncatedRowCache.clear()
    hasRenderedFirstCatalog = false
    diskCacheRestored = false
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
    enrichmentCache.clear()
    // Load enrichment cache in background — don't block catalog pipeline on it
    viewModelScope.launch {
        val restored = homeEnrichmentDiskCache.loadAll()
        if (restored.isNotEmpty()) {
            enrichmentCache.putAll(restored)
            android.util.Log.d("NuvioEnrich", "[PROACTIVE] restored ${restored.size} enrichment entries from disk")
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
        android.util.Log.e("NuvioCache", "loadCatalogsFromDisk returned ${diskCached.size} entries for profile $profileId")
        if (diskCached.isNotEmpty()) {
            diskCached.forEach { (key, row) -> catalogsMap[key] = row }
            diskCacheRestored = true
            android.util.Log.e("NuvioCache", "catalogsMap now has ${catalogsMap.size} rows, catalogOrder has ${catalogOrder.size} keys after disk restore")
            val matchedKeys = diskCached.keys.count { it in catalogOrder }
            android.util.Log.e("NuvioCache", "disk cache keys matching catalogOrder: $matchedKeys / ${diskCached.size}")
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
                        val preEnriched = existingRow?.items?.count { it.ageRating != null } ?: 0
                        val postEnriched = mergedRow.items.count { it.ageRating != null }
                        if (preEnriched > 0) {
                            android.util.Log.w("NuvioEnrich", "[RELOAD] key=$key pre-enriched=$preEnriched post-enriched=$postEnriched")
                        }
                        catalogsMap[key] = mergedRow
                        // Proactively enrich per-catalog landscape rows as pages arrive.
                        // Uses a dedicated concurrent batch instead of the single-slot
                        // preloadAdjacentItemPipeline which cancels on every call.
                        val isPerCatalogLandscapeRow = key in _uiState.value.landscapeCatalogKeys
                        if (isPerCatalogLandscapeRow && currentTmdbSettings.enabled) {
                            val itemsToEnrich = mergedRow.items.take(25)
                                .filter { it.id !in prefetchedTmdbIds }
                            if (itemsToEnrich.isNotEmpty()) {
                                val tmdbSettingsSnapshot = currentTmdbSettings
                                viewModelScope.launch(Dispatchers.IO) {
                                    val semaphore = kotlinx.coroutines.sync.Semaphore(4)
                                    coroutineScope {
                                        itemsToEnrich.map { item ->
                                            async {
                                                if (item.id in prefetchedTmdbIds) return@async
                                                semaphore.acquire()
                                                try {
                                                    val tmdbId = runCatching {
                                                        tmdbService.ensureTmdbId(item.id, item.apiType)
                                                    }.getOrNull() ?: return@async
                                                    val enrichment = runCatching {
                                                        tmdbMetadataService.fetchEnrichment(
                                                            tmdbId = tmdbId,
                                                            contentType = item.type,
                                                            language = tmdbSettingsSnapshot.language
                                                        )
                                                    }.getOrNull() ?: return@async
                                                    prefetchedTmdbIds.add(item.id)
                                                    prefetchedExternalMetaIds.add(item.id)
                                                    updateCatalogItemWithTmdb(item.id, enrichment)
                                                } finally {
                                                    semaphore.release()
                                                }
                                            }
                                        }.awaitAll()
                                    }
                                }
                            }
                        }
                        // Hide spinner immediately on first catalog result — don't wait for debounce
                        if (!hasRenderedFirstCatalog && mergedRow.items.isNotEmpty()) {
                            _uiState.update { it.copy(isLoading = false) }
                        }
                        val stomped2 = mergedRow.items.filter { it.ageRating == null }
                        val had = existingRow?.items?.filter { it.ageRating != null } ?: emptyList()
                        if (had.isNotEmpty() && stomped2.any { item -> had.any { it.id == item.id } }) {
                            android.util.Log.w("NuvioEnrich", "[STOMP2] after merge, ${had.size} enriched items still lost ageRating in key=$key")
                        }
                        if (!hasCountedCompletion) {
                            pendingCatalogLoads = (pendingCatalogLoads - 1).coerceAtLeast(0)
                            hasCountedCompletion = true
                        }
                        Log.d(
                            HomeViewModel.TAG,
                            "Home catalog loaded addonId=${addon.id} type=${catalog.apiType} catalogId=${catalog.id} items=${result.data.items.size} pending=$pendingCatalogLoads"
                        )
                        if (pendingCatalogLoads == 0) {
                            catalogsLoadInProgress = false
                            android.util.Log.d("NuvioTiming", "Catalog load COMPLETE rows=${catalogsMap.size} ts=${System.currentTimeMillis()}")
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

internal fun HomeViewModel.loadMoreCatalogItemsPipeline(catalogId: String, addonId: String, type: String) {
    val key = catalogKey(addonId = addonId, type = type, catalogId = catalogId)
    val currentRow = catalogsMap[key] ?: return

    if (currentRow.isLoading || !currentRow.hasMore) return
    if (key in _loadingCatalogs.value) return

    catalogsMap[key] = currentRow.copy(isLoading = true)
    _loadingCatalogs.update { it + key }

    viewModelScope.launch {
        val addon = addonsCache.find { it.id == addonId } ?: return@launch

        val nextSkip = (currentRow.currentPage + 1) * currentRow.skipStep
        catalogRepository.getCatalog(
            addonBaseUrl = addon.baseUrl,
            addonId = addon.id,
            addonName = addon.displayName,
            catalogId = catalogId,
            catalogName = currentRow.catalogName,
            type = currentRow.apiType,
            skip = nextSkip,
            skipStep = currentRow.skipStep,
            supportsSkip = currentRow.supportsSkip
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    val latestRow = catalogsMap[key] ?: currentRow
                    val existingIds = latestRow.items.asSequence()
                        .map { "${it.apiType}:${it.id}" }
                        .toHashSet()
                    val newUniqueItems = result.data.items.filter { item ->
                        "${item.apiType}:${item.id}" !in existingIds
                    }
                    val mergedItems = latestRow.items + newUniqueItems
                    val hasMore = if (newUniqueItems.isEmpty()) false else result.data.hasMore
                    catalogsMap[key] = result.data.copy(items = mergedItems, hasMore = hasMore)
                    _loadingCatalogs.update { it - key }
                    scheduleUpdateCatalogRows()
                }
                is NetworkResult.Error -> {
                    catalogsMap[key] = (catalogsMap[key] ?: currentRow).copy(isLoading = false)
                    _loadingCatalogs.update { it - key }
                    scheduleUpdateCatalogRows()
                }
                NetworkResult.Loading -> { }
            }
        }
    }
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

        val computedDisplayRows = orderedRows.map { row ->
            val shouldKeepFullRowInModern = currentLayout == HomeLayout.MODERN && row.supportsSkip
            if (row.items.size > 25 && !shouldKeepFullRowInModern) {
                val key = "${row.addonId}_${row.apiType}_${row.catalogId}"
                val cachedEntry = truncatedRowCache[key]
                if (cachedEntry != null && cachedEntry.sourceRow === row) {
                    cachedEntry.truncatedRow
                } else {
                    val truncatedRow = row.copy(items = row.items.take(25))
                    truncatedRowCache[key] = HomeViewModel.TruncatedRowCacheEntry(
                        sourceRow = row,
                        truncatedRow = truncatedRow
                    )
                    truncatedRow
                }
            } else {
                val key = "${row.addonId}_${row.apiType}_${row.catalogId}"
                truncatedRowCache.remove(key)
                row
            }
        }

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
                            status = cached.status ?: merged.status
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
                            logo = cached.logo ?: merged.logo,
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
        state.copy(
            catalogRows = if (state.catalogRows == finalRows) state.catalogRows else finalRows,
            heroItems = if (state.heroItems == enrichedHeroItems) state.heroItems else enrichedHeroItems,
            gridItems = if (state.gridItems == nextGridItems) state.gridItems else nextGridItems,
            isLoading = false,
            stableVisiblePlatformIds = if (allCatalogsLoaded || diskCacheRestored) {
                displayRows
                    .filter { it.items.isNotEmpty() }
                    .mapNotNull { inferPlatformId(it.catalogName) }
                    .toSet()
            } else {
                state.stableVisiblePlatformIds
            }
        )
    }

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
            .filter { it.id !in prefetchedTmdbIds && it.id !in enrichmentCache }
            .sortedBy { rowIndexById[it.id] ?: Int.MAX_VALUE }
        val myListItemIds = myListItems.map { it.id }.toSet()
        android.util.Log.d("NuvioEnrich", "[PROACTIVE] pipeline run: ${displayRows.size} rows, ${displayRows.flatMap { it.items }.distinctBy { it.id }.size} total items, ${allItems.size} need enrichment")
        if (allItems.isNotEmpty()) {
            val tmdbSettingsSnapshot = currentTmdbSettings
            // Split first row items for priority enrichment vs rest for background batch
            val firstRowIndex = rowIndexById.values.minOrNull() ?: 0
            // ML items get first-row priority — full concurrency, no semaphore
            val firstRowItems = allItems.filter { item ->
                (rowIndexById[item.id] ?: Int.MAX_VALUE) == firstRowIndex || item.id in myListItemIds
            }
            val remainingItems = allItems.filter { item ->
                (rowIndexById[item.id] ?: Int.MAX_VALUE) != firstRowIndex && item.id !in myListItemIds
            }
            proactiveEnrichJob?.cancel()
            proactiveEnrichJob = viewModelScope.launch(Dispatchers.IO) {
                // Enrich first row with full concurrency, no semaphore — these are immediately visible
                coroutineScope {
                    firstRowItems.map { item ->
                        async {
                            if (item.id in prefetchedTmdbIds) return@async
                            try {
                                val tmdbId = runCatching {
                                    tmdbService.ensureTmdbId(item.id, item.apiType)
                                }.getOrNull() ?: return@async
                                val enrichment = runCatching {
                                    tmdbMetadataService.fetchEnrichment(
                                        tmdbId = tmdbId,
                                        contentType = item.type,
                                        language = tmdbSettingsSnapshot.language
                                    )
                                }.getOrNull() ?: return@async
                                prefetchedTmdbIds.add(item.id)
                                prefetchedExternalMetaIds.add(item.id)
                                updateCatalogItemWithTmdb(item.id, enrichment)
                            } catch (_: Exception) {}
                        }
                    }.awaitAll()
                }
                // Then enrich remaining rows with semaphore throttling
                val semaphore = kotlinx.coroutines.sync.Semaphore(8)
                coroutineScope {
                    remainingItems.map { item ->
                        async {
                            if (item.id in prefetchedTmdbIds || item.id in enrichmentCache) return@async
                            semaphore.acquire()
                            try {
                                val tmdbId = runCatching {
                                    tmdbService.ensureTmdbId(item.id, item.apiType)
                                }.getOrNull() ?: return@async
                                val enrichment = runCatching {
                                    tmdbMetadataService.fetchEnrichment(
                                        tmdbId = tmdbId,
                                        contentType = item.type,
                                        language = tmdbSettingsSnapshot.language
                                    )
                                }.getOrNull() ?: return@async
                                prefetchedTmdbIds.add(item.id)
                                prefetchedExternalMetaIds.add(item.id)
                                updateCatalogItemWithTmdb(item.id, enrichment)
                            } finally {
                                semaphore.release()
                            }
                        }
                    }.awaitAll()
                }
                // Save entire enrichment cache to disk once after all items processed
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    homeEnrichmentDiskCache.saveAll(enrichmentCache.toMap())
                }
            }
        }
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


