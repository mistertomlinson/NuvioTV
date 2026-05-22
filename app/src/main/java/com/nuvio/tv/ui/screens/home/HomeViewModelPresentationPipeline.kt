package com.nuvio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbEnrichment
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.TmdbSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private data class CoreLayoutPrefs(
    val layout: HomeLayout,
    val heroCatalogKeys: List<String>,
    val heroSectionEnabled: Boolean,
    val posterLabelsEnabled: Boolean,
    val catalogAddonNameEnabled: Boolean,
    val catalogTypeSuffixEnabled: Boolean,
    val hideUnreleasedContent: Boolean
)

private data class FocusedBackdropPrefs(
    val expandEnabled: Boolean,
    val expandDelaySeconds: Int,
    val trailerEnabled: Boolean,
    val trailerMuted: Boolean,
    val trailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget,
    val noBackdropImage: Boolean,
    val heroTrailerAllowLetterboxing: Boolean
)

private data class LayoutUiPrefs(
    val layout: HomeLayout,
    val heroCatalogKeys: List<String>,
    val heroSectionEnabled: Boolean,
    val posterLabelsEnabled: Boolean,
    val catalogAddonNameEnabled: Boolean,
    val catalogTypeSuffixEnabled: Boolean,
    val hideUnreleasedContent: Boolean,
    val modernLandscapePostersEnabled: Boolean,
    val focusedBackdropExpandEnabled: Boolean,
    val focusedBackdropExpandDelaySeconds: Int,
    val focusedBackdropTrailerEnabled: Boolean,
    val focusedBackdropTrailerMuted: Boolean,
    val focusedBackdropTrailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget,
    val focusedBackdropNoBackdropImage: Boolean,
    val focusedBackdropHeroTrailerAllowLetterboxing: Boolean,
    val posterCardWidthDp: Int,
    val posterCardHeightDp: Int,
    val posterCardCornerRadiusDp: Int
)

@OptIn(FlowPreview::class)
internal fun HomeViewModel.observeLayoutPreferencesPipeline() {
    val coreLayoutPrefsFlow = combine(
        combine(
            layoutPreferenceDataStore.selectedLayout,
            layoutPreferenceDataStore.heroCatalogSelections,
            layoutPreferenceDataStore.heroSectionEnabled,
            layoutPreferenceDataStore.posterLabelsEnabled,
            layoutPreferenceDataStore.catalogAddonNameEnabled
        ) { layout, heroCatalogKeys, heroSectionEnabled, posterLabelsEnabled, catalogAddonNameEnabled ->
            CoreLayoutPrefs(
                layout = layout,
                heroCatalogKeys = heroCatalogKeys,
                heroSectionEnabled = heroSectionEnabled,
                posterLabelsEnabled = posterLabelsEnabled,
                catalogAddonNameEnabled = catalogAddonNameEnabled,
                catalogTypeSuffixEnabled = true,
                hideUnreleasedContent = false
            )
        },
        layoutPreferenceDataStore.catalogTypeSuffixEnabled,
        layoutPreferenceDataStore.hideUnreleasedContent
    ) { corePrefs, catalogTypeSuffixEnabled, hideUnreleasedContent ->
        corePrefs.copy(
            catalogTypeSuffixEnabled = catalogTypeSuffixEnabled,
            hideUnreleasedContent = hideUnreleasedContent
        )
    }

    val focusedBackdropPrefsFlow = combine(
        layoutPreferenceDataStore.focusedPosterBackdropExpandEnabled,
        layoutPreferenceDataStore.focusedPosterBackdropExpandDelaySeconds,
        layoutPreferenceDataStore.focusedPosterBackdropTrailerEnabled,
        layoutPreferenceDataStore.focusedPosterBackdropTrailerMuted,
        layoutPreferenceDataStore.focusedPosterBackdropTrailerPlaybackTarget
    ) { expandEnabled, expandDelaySeconds, trailerEnabled, trailerMuted, trailerPlaybackTarget ->
        FocusedBackdropPrefs(
            expandEnabled = expandEnabled,
            expandDelaySeconds = expandDelaySeconds,
            trailerEnabled = trailerEnabled,
            trailerMuted = trailerMuted,
            trailerPlaybackTarget = trailerPlaybackTarget,
            noBackdropImage = false,
            heroTrailerAllowLetterboxing = false
        )
    }.combine(layoutPreferenceDataStore.focusedPosterNoBackdropImage) { prefs, noBackdrop ->
        prefs.copy(noBackdropImage = noBackdrop)
    }.combine(layoutPreferenceDataStore.heroTrailerAllowLetterboxing) { prefs, heroTrailerAllowLetterboxing ->
        prefs.copy(heroTrailerAllowLetterboxing = heroTrailerAllowLetterboxing)
    }

    val modernLayoutPrefsFlow = layoutPreferenceDataStore.modernLandscapePostersEnabled

    val baseLayoutUiPrefsFlow = combine(
        coreLayoutPrefsFlow,
        focusedBackdropPrefsFlow,
        layoutPreferenceDataStore.posterCardWidthDp,
        layoutPreferenceDataStore.posterCardHeightDp,
        layoutPreferenceDataStore.posterCardCornerRadiusDp
    ) { corePrefs, focusedBackdropPrefs, posterCardWidthDp, posterCardHeightDp, posterCardCornerRadiusDp ->
        LayoutUiPrefs(
            layout = corePrefs.layout,
            heroCatalogKeys = corePrefs.heroCatalogKeys,
            heroSectionEnabled = corePrefs.heroSectionEnabled,
            posterLabelsEnabled = corePrefs.posterLabelsEnabled,
            catalogAddonNameEnabled = corePrefs.catalogAddonNameEnabled,
            catalogTypeSuffixEnabled = corePrefs.catalogTypeSuffixEnabled,
            hideUnreleasedContent = corePrefs.hideUnreleasedContent,
            modernLandscapePostersEnabled = false,
            focusedBackdropExpandEnabled = focusedBackdropPrefs.expandEnabled,
            focusedBackdropExpandDelaySeconds = focusedBackdropPrefs.expandDelaySeconds,
            focusedBackdropTrailerEnabled = focusedBackdropPrefs.trailerEnabled,
            focusedBackdropTrailerMuted = focusedBackdropPrefs.trailerMuted,
            focusedBackdropTrailerPlaybackTarget = focusedBackdropPrefs.trailerPlaybackTarget,
            focusedBackdropNoBackdropImage = focusedBackdropPrefs.noBackdropImage,
            focusedBackdropHeroTrailerAllowLetterboxing = focusedBackdropPrefs.heroTrailerAllowLetterboxing,
            posterCardWidthDp = posterCardWidthDp,
            posterCardHeightDp = posterCardHeightDp,
            posterCardCornerRadiusDp = posterCardCornerRadiusDp
        )
    }

    viewModelScope.launch {
        combine(
            baseLayoutUiPrefsFlow,
            modernLayoutPrefsFlow,
            layoutPreferenceDataStore.aggregateStreamingPlatformsEnabled,
            layoutPreferenceDataStore.showAllCatalogsOnHome
        ) { basePrefs, modernPrefs, aggregatePlatforms, showAllOnHome ->
            Triple(basePrefs.copy(modernLandscapePostersEnabled = modernPrefs), aggregatePlatforms, showAllOnHome)
        }
            .combine(layoutPreferenceDataStore.fullWidthIconRowEnabled) { triple, fullWidthIconRow ->
                Pair(triple, fullWidthIconRow)
            }
            .combine(layoutPreferenceDataStore.fastPlatformScrollEnabled) { pair, fastPlatformScroll ->
                Pair(pair, fastPlatformScroll)
            }
            .combine(layoutPreferenceDataStore.dimIconsOnRowExitEnabled) { outerPair, dimIconsOnRowExit ->
                Pair(outerPair, dimIconsOnRowExit)
            }
            .combine(layoutPreferenceDataStore.landscapeHomeCatalogKeys) { outerPair2, landscapeKeys ->
                Pair(outerPair2, landscapeKeys.toSet())
            }
            .distinctUntilChanged()
            .debounce(300)
            .collectLatest { (outerPairVal2, landscapeCatalogKeys) ->
            val (outerPairVal, dimIconsOnRowExitEnabled) = outerPairVal2
            val (pairVal, fastPlatformScrollEnabled) = outerPairVal
            val (tripleVal, fullWidthIconRowEnabled) = pairVal
            val (prefs, aggregateStreamingPlatforms, showAllCatalogsOnHome) = tripleVal
                val effectivePosterLabelsEnabled = if (prefs.layout == HomeLayout.MODERN) {
                    false
                } else {
                    prefs.posterLabelsEnabled
                }
                val previousState = _uiState.value
                val shouldRefreshCatalogPresentation =
                    currentHeroCatalogKeys != prefs.heroCatalogKeys ||
                        previousState.heroSectionEnabled != prefs.heroSectionEnabled ||
                        previousState.homeLayout != prefs.layout ||
                        previousState.hideUnreleasedContent != prefs.hideUnreleasedContent
                currentHeroCatalogKeys = prefs.heroCatalogKeys
                _uiState.update {
                    it.copy(
                        homeLayout = prefs.layout,
                        heroCatalogKeys = prefs.heroCatalogKeys,
                        heroSectionEnabled = prefs.heroSectionEnabled,
                        posterLabelsEnabled = effectivePosterLabelsEnabled,
                        catalogAddonNameEnabled = prefs.catalogAddonNameEnabled,
                        catalogTypeSuffixEnabled = prefs.catalogTypeSuffixEnabled,
                        hideUnreleasedContent = prefs.hideUnreleasedContent,
                        modernLandscapePostersEnabled = prefs.modernLandscapePostersEnabled,
                        focusedPosterBackdropExpandEnabled = prefs.focusedBackdropExpandEnabled,
                        focusedPosterBackdropExpandDelaySeconds = prefs.focusedBackdropExpandDelaySeconds,
                        focusedPosterBackdropTrailerEnabled = prefs.focusedBackdropTrailerEnabled,
                        focusedPosterBackdropTrailerMuted = prefs.focusedBackdropTrailerMuted,
                        focusedPosterBackdropTrailerPlaybackTarget = prefs.focusedBackdropTrailerPlaybackTarget,
                        focusedPosterNoBackdropImage = prefs.focusedBackdropNoBackdropImage,
                        heroTrailerAllowLetterboxing = prefs.focusedBackdropHeroTrailerAllowLetterboxing,
                        posterCardWidthDp = prefs.posterCardWidthDp,
                        posterCardHeightDp = prefs.posterCardHeightDp,
                        posterCardCornerRadiusDp = prefs.posterCardCornerRadiusDp,
                        aggregateStreamingPlatformsEnabled = aggregateStreamingPlatforms,
                        showAllCatalogsOnHome = showAllCatalogsOnHome,
                        fullWidthIconRowEnabled = fullWidthIconRowEnabled,
                        dimIconsOnRowExitEnabled = dimIconsOnRowExitEnabled,
                        fastPlatformScrollEnabled = fastPlatformScrollEnabled,
                        landscapeCatalogKeys = landscapeCatalogKeys
                    )
                }
                if (shouldRefreshCatalogPresentation) {
                    scheduleUpdateCatalogRows()
                }
            }
    }
}

internal fun HomeViewModel.observeExternalMetaPrefetchPreferencePipeline() {
    viewModelScope.launch {
        layoutPreferenceDataStore.preferExternalMetaAddonDetail
            .distinctUntilChanged()
            .collectLatest { enabled ->
                externalMetaPrefetchEnabled = enabled
                if (!enabled) {
                    externalMetaPrefetchJob?.cancel()
                    pendingExternalMetaPrefetchItemId = null
                    externalMetaPrefetchInFlightIds.clear()
                }
            }
    }
}

internal fun HomeViewModel.requestTrailerPreviewPipeline(item: MetaPreview) {
    requestTrailerPreviewPipeline(
        itemId = item.id,
        title = item.name,
        releaseInfo = item.releaseInfo,
        apiType = item.apiType,
        fallbackYtId = item.trailerYtIds.firstOrNull()
    )
}

internal fun HomeViewModel.requestTrailerPreviewPipeline(
    itemId: String,
    title: String,
    releaseInfo: String?,
    apiType: String,
    fallbackYtId: String? = null
) {
    if (startupGracePeriodActive) return
    if (activeTrailerPreviewItemId != itemId) {
        activeTrailerPreviewItemId = itemId
        trailerPreviewRequestVersion++
    }

    if (trailerPreviewNegativeCache.contains(itemId)) return
    if (trailerPreviewUrlsState.containsKey(itemId)) return
    if (!trailerPreviewLoadingIds.add(itemId)) return

    val requestVersion = trailerPreviewRequestVersion

    viewModelScope.launch {
        val tmdbId = try {
            tmdbService.ensureTmdbId(itemId, apiType)
        } catch (_: Exception) {
            null
        }

        val trailerSource = trailerService.getTrailerPlaybackSource(
            title = title,
            year = extractYear(releaseInfo),
            tmdbId = tmdbId,
            type = apiType
        )

        val isLatestFocusedItem =
            activeTrailerPreviewItemId == itemId && trailerPreviewRequestVersion == requestVersion
        if (!isLatestFocusedItem) {
            trailerPreviewLoadingIds.remove(itemId)
            return@launch
        }

        if (trailerSource?.videoUrl.isNullOrBlank()) {
            val fallbackSource = fallbackYtId?.let { ytId ->
                trailerService.getTrailerPlaybackSourceFromYouTubeUrl(
                    youtubeUrl = "https://www.youtube.com/watch?v=$ytId",
                    title = title,
                    year = extractYear(releaseInfo)
                )
            }
            if (fallbackSource?.videoUrl != null) {
                if (trailerPreviewUrlsState[itemId] != fallbackSource.videoUrl) {
                    trailerPreviewUrlsState[itemId] = fallbackSource.videoUrl
                }
                val fallbackAudio = fallbackSource.audioUrl
                if (fallbackAudio.isNullOrBlank()) {
                    trailerPreviewAudioUrlsState.remove(itemId)
                } else if (trailerPreviewAudioUrlsState[itemId] != fallbackAudio) {
                    trailerPreviewAudioUrlsState[itemId] = fallbackAudio
                }
            } else {
                trailerPreviewNegativeCache.add(itemId)
                trailerPreviewUrlsState.remove(itemId)
                trailerPreviewAudioUrlsState.remove(itemId)
            }
        } else {
            val videoUrl = trailerSource.videoUrl
            if (trailerPreviewUrlsState[itemId] != videoUrl) {
                trailerPreviewUrlsState[itemId] = videoUrl
            }
            val audioUrl = trailerSource.audioUrl
            if (audioUrl.isNullOrBlank()) {
                trailerPreviewAudioUrlsState.remove(itemId)
            } else if (trailerPreviewAudioUrlsState[itemId] != audioUrl) {
                trailerPreviewAudioUrlsState[itemId] = audioUrl
            }
        }

        trailerPreviewLoadingIds.remove(itemId)
    }
}

internal fun HomeViewModel.onItemFocusPipeline(item: MetaPreview) {
    if (startupGracePeriodActive) return
    if (item.id in prefetchedTmdbIds || item.id in prefetchedExternalMetaIds) {
        Log.d("NuvioEnrich", "[FOCUS] ${item.name} — already enriched, skipping")
        return
    }
    if (pendingTmdbEnrichItemId == item.id) {
        Log.d("NuvioEnrich", "[FOCUS] ${item.name} — already pending, skipping")
        return
    }

    // Clear enriching for previous item immediately when focus moves away
    if (_enrichingItemId.value != null && _enrichingItemId.value != item.id) {
        setEnrichingItemId(null)
    }

    val willEnrich = currentTmdbSettings.enabled || externalMetaPrefetchEnabled
    if (willEnrich) setEnrichingItemId(item.id)

    val focusTimeMs = System.currentTimeMillis()
    Log.d("NuvioEnrich", "[FOCUS] ${item.name} (${item.id}) — focus received, debounce=${HomeViewModel.EXTERNAL_META_PREFETCH_FOCUS_DEBOUNCE_MS}ms")

    pendingTmdbEnrichItemId = item.id
    tmdbEnrichFocusJob?.cancel()
    tmdbEnrichFocusJob = viewModelScope.launch(Dispatchers.IO) {
        delay(HomeViewModel.EXTERNAL_META_PREFETCH_FOCUS_DEBOUNCE_MS)
        if (pendingTmdbEnrichItemId != item.id) {
            Log.d("NuvioEnrich", "[FOCUS] ${item.name} — cancelled during debounce (focus moved)")
            if (_enrichingItemId.value == item.id) setEnrichingItemId(null)
            return@launch
        }
        if (item.id in prefetchedTmdbIds || item.id in prefetchedExternalMetaIds) {
            Log.d("NuvioEnrich", "[FOCUS] ${item.name} — already enriched after debounce, skipping")
            if (_enrichingItemId.value == item.id) setEnrichingItemId(null)
            return@launch
        }

        val afterDebounceMs = System.currentTimeMillis() - focusTimeMs
        Log.d("NuvioEnrich", "[FOCUS] ${item.name} — debounce cleared at +${afterDebounceMs}ms, starting network")

        try {
            var tmdbEnriched = false
            if (currentTmdbSettings.enabled) {
                val t1 = System.currentTimeMillis()
                val tmdbId = runCatching { tmdbService.ensureTmdbId(item.id, item.apiType) }.getOrNull()
                Log.d("NuvioEnrich", "[FOCUS] ${item.name} — ensureTmdbId=${tmdbId} at +${System.currentTimeMillis() - focusTimeMs}ms (took ${System.currentTimeMillis() - t1}ms)")
                val enrichment = if (tmdbId != null) {
                    val t2 = System.currentTimeMillis()
                    val result = runCatching {
                        tmdbMetadataService.fetchEnrichment(
                            tmdbId = tmdbId,
                            contentType = item.type,
                            language = currentTmdbSettings.language
                        )
                    }.getOrNull()
                    Log.d("NuvioEnrich", "[FOCUS] ${item.name} — fetchEnrichment=${result != null} at +${System.currentTimeMillis() - focusTimeMs}ms (took ${System.currentTimeMillis() - t2}ms)")
                    result
                } else null
                if (enrichment != null) {
                    prefetchedTmdbIds.add(item.id)
                    prefetchedExternalMetaIds.add(item.id)
                    updateCatalogItemWithTmdb(item.id, enrichment)
                    Log.d("NuvioEnrich", "[FOCUS] ${item.name} — UI updated at +${System.currentTimeMillis() - focusTimeMs}ms total")
                    tmdbEnriched = true
                } else {
                    Log.d("NuvioEnrich", "[FOCUS] ${item.name} — enrichment null/failed at +${System.currentTimeMillis() - focusTimeMs}ms")
                }
            }
            if (!tmdbEnriched && externalMetaPrefetchEnabled &&
                item.id !in prefetchedExternalMetaIds &&
                externalMetaPrefetchInFlightIds.add(item.id)) {
                try {
                    val result = metaRepository.getMetaFromAllAddons(item.apiType, item.id)
                        .first { it is NetworkResult.Success || it is NetworkResult.Error }
                    if (result is NetworkResult.Success) {
                        prefetchedExternalMetaIds.add(item.id)
                        updateCatalogItemWithMeta(item.id, result.data)
                    }
                } finally {
                    externalMetaPrefetchInFlightIds.remove(item.id)
                    if (pendingTmdbEnrichItemId == item.id) pendingTmdbEnrichItemId = null
                }
            }
        } finally {
            if (_enrichingItemId.value == item.id) setEnrichingItemId(null)
        }
    }
}

internal fun HomeViewModel.preloadAdjacentItemPipeline(item: MetaPreview) {
    if (startupGracePeriodActive) return
    if (item.id in prefetchedTmdbIds || item.id in prefetchedExternalMetaIds) return
    if (pendingTmdbEnrichItemId == item.id || pendingAdjacentPrefetchItemId == item.id) return

    pendingAdjacentPrefetchItemId = item.id
    adjacentItemPrefetchJob?.cancel()
    adjacentItemPrefetchJob = viewModelScope.launch(Dispatchers.IO) {
        delay(HomeViewModel.EXTERNAL_META_PREFETCH_ADJACENT_DEBOUNCE_MS)
        if (pendingAdjacentPrefetchItemId != item.id) return@launch
        if (item.id in prefetchedTmdbIds || item.id in prefetchedExternalMetaIds) return@launch

        try {
            var tmdbEnriched = false
            if (currentTmdbSettings.enabled) {
                val tmdbId = runCatching { tmdbService.ensureTmdbId(item.id, item.apiType) }.getOrNull()
                val enrichment = if (tmdbId != null) runCatching {
                    tmdbMetadataService.fetchEnrichment(
                        tmdbId = tmdbId,
                        contentType = item.type,
                        language = currentTmdbSettings.language
                    )
                }.getOrNull() else null
                if (enrichment != null) {
                    prefetchedTmdbIds.add(item.id)
                    prefetchedExternalMetaIds.add(item.id)
                    updateCatalogItemWithTmdb(item.id, enrichment)
                    tmdbEnriched = true
                }
            }
            if (!tmdbEnriched &&
                externalMetaPrefetchEnabled &&
                item.id !in prefetchedExternalMetaIds &&
                externalMetaPrefetchInFlightIds.add(item.id)
            ) {
                try {
                    val result = metaRepository.getMetaFromAllAddons(item.apiType, item.id)
                        .first { it is NetworkResult.Success || it is NetworkResult.Error }
                    if (result is NetworkResult.Success) {
                        prefetchedExternalMetaIds.add(item.id)
                        updateCatalogItemWithMeta(item.id, result.data)
                    }
                } finally {
                    externalMetaPrefetchInFlightIds.remove(item.id)
                }
            }
        } finally {
            if (pendingAdjacentPrefetchItemId == item.id) {
                pendingAdjacentPrefetchItemId = null
            }
        }
    }
}

internal fun HomeViewModel.updateCatalogItemWithTmdb(itemId: String, enrichment: TmdbEnrichment) {
    fun mergeItem(currentItem: MetaPreview): MetaPreview {
        var merged = currentItem
        if (currentTmdbSettings.useBasicInfo) {
            merged = merged.copy(
                name = enrichment.localizedTitle ?: merged.name,
                description = enrichment.description ?: merged.description,
                genres = if (enrichment.genres.isNotEmpty()) enrichment.genres else merged.genres
            )
        }
        if (currentTmdbSettings.useArtwork) {
            merged = merged.copy(
                logo = enrichment.logo ?: merged.logo,
                // Prefer detailBackdrop (unique TMDB image), fall back to existing
                // landscapePoster, then background if neither is available.
                landscapePoster = enrichment.detailBackdrop ?: merged.landscapePoster ?: merged.background
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
                ageRating = enrichment.ageRating ?: merged.ageRating,
                status = enrichment.status ?: merged.status
            )
        }
        return merged
    }

    Log.d("NuvioEnrich", "[BADGE] updateCatalogItemWithTmdb called for $itemId ageRating=${enrichment.ageRating} status=${enrichment.status}")
    // Log current state of this item across all catalog rows before writing
    synchronized(catalogsMap) { catalogsMap.forEach { (key, row) ->
        val existing = row.items.firstOrNull { it.id == itemId }
        if (existing != null) {
            Log.d("NuvioEnrich", "[BADGE] PRE-WRITE key=$key existing ageRating=${existing.ageRating}")
        }
    } }
    var wroteToMap = false
    synchronized(catalogsMap) { catalogsMap.forEach { (key, row) ->
        val idx = row.items.indexOfFirst { it.id == itemId }
        if (idx >= 0) {
            val merged = mergeItem(row.items[idx])
            if (merged != row.items[idx]) {
                val mutableItems = row.items.toMutableList()
                mutableItems[idx] = merged
                catalogsMap[key] = row.copy(items = mutableItems)
                truncatedRowCache.remove(key)
                wroteToMap = true
                Log.d("NuvioEnrich", "[BADGE] wrote to catalogsMap key=$key ageRating=${merged.ageRating} status=${merged.status}")
            } else {
                Log.d("NuvioEnrich", "[BADGE] no change for key=$key (ageRating already=${row.items[idx].ageRating} status=${row.items[idx].status})")
            }
        }
    }
    } // end synchronized
    enrichmentCache[itemId] = enrichment
    if (!wroteToMap) Log.w("NuvioEnrich", "[BADGE] item $itemId NOT FOUND in catalogsMap — writing to enrichmentCache only")

    _uiState.update { state ->
        var changed = false
        val updatedRows = state.catalogRows.map { row ->
            val idx = row.items.indexOfFirst { it.id == itemId }
            if (idx < 0) row
            else {
                val mergedItem = mergeItem(row.items[idx])
                if (mergedItem == row.items[idx]) {
                    Log.d("NuvioEnrich", "[BADGE] uiState row already has ageRating=${row.items[idx].ageRating} for $itemId — no uiState change needed")
                    row
                } else {
                    changed = true
                    Log.d("NuvioEnrich", "[BADGE] uiState updated for $itemId ageRating=${mergedItem.ageRating} status=${mergedItem.status}")
                    val mutableItems = row.items.toMutableList()
                    mutableItems[idx] = mergedItem
                    row.copy(items = mutableItems)
                }
            }
        }
        if (!changed) Log.w("NuvioEnrich", "[BADGE] uiState.catalogRows did NOT change for $itemId — item may not be in catalogRows yet")
        if (changed) state.copy(catalogRows = updatedRows) else state
    }
    // catalogsMap is already updated inline above; future pipeline runs will
    // pick up enriched data from there. No need to schedule a rebuild here —
    // doing so races against the direct uiState write and causes flicker.
}

private fun HomeViewModel.updateCatalogItemWithMeta(itemId: String, meta: Meta) {
    val incomingTrailerYtIds = meta.trailerYtIds

    fun mergeItem(currentItem: MetaPreview): MetaPreview = currentItem.copy(
        background = meta.backdropUrl ?: currentItem.backdropUrl,
        logo = meta.logo ?: currentItem.logo,
        description = meta.description ?: currentItem.description,
        imdbRating = meta.imdbRating ?: currentItem.imdbRating,
        genres = if (meta.genres.isNotEmpty()) meta.genres else currentItem.genres,
        runtime = meta.runtime ?: currentItem.runtime,
        status = meta.status ?: currentItem.status,
        ageRating = meta.ageRating ?: currentItem.ageRating,
        language = meta.language ?: currentItem.language,
        country = meta.country ?: currentItem.country,
        trailerYtIds = if (incomingTrailerYtIds.isNotEmpty()) incomingTrailerYtIds else currentItem.trailerYtIds
    )

    synchronized(catalogsMap) { catalogsMap.forEach { (key, row) ->
        val itemIndex = row.items.indexOfFirst { it.id == itemId }
        if (itemIndex >= 0) {
            val merged = mergeItem(row.items[itemIndex])
            if (merged != row.items[itemIndex]) {
                val mutableItems = row.items.toMutableList()
                mutableItems[itemIndex] = merged
                catalogsMap[key] = row.copy(items = mutableItems)
                truncatedRowCache.remove(key)
            }
        }
    } }

    _uiState.update { state ->
        var changed = false
        val updatedRows = state.catalogRows.map { row ->
            val itemIndex = row.items.indexOfFirst { it.id == itemId }
            if (itemIndex < 0) {
                row
            } else {
                val mergedItem = mergeItem(row.items[itemIndex])
                if (mergedItem == row.items[itemIndex]) {
                    row
                } else {
                    changed = true
                    val mutableItems = row.items.toMutableList()
                    mutableItems[itemIndex] = mergedItem
                    row.copy(items = mutableItems)
                }
            }
        }
        if (changed) state.copy(catalogRows = updatedRows) else state
    }

    // If external meta brought new trailerYtIds and the item has no trailer resolved yet, retry.
    // Covers: (a) item was in negative cache, (b) pipeline finished without result but wasn't
    // cached as negative (e.g. focus changed mid-flight), (c) pipeline still in-flight.
    if (incomingTrailerYtIds.isNotEmpty() && !trailerPreviewUrlsState.containsKey(itemId)) {
        trailerPreviewNegativeCache.remove(itemId)
        trailerPreviewLoadingIds.remove(itemId)
        // Bump version so any in-flight pipeline for this item treats itself as stale
        // and won't overwrite the retry result with a negative cache entry.
        if (activeTrailerPreviewItemId == itemId) trailerPreviewRequestVersion++
        val currentItem = catalogsMap.values.firstNotNullOfOrNull { row ->
            row.items.firstOrNull { it.id == itemId }
        } ?: return
        requestTrailerPreviewPipeline(currentItem)
    }
}

internal suspend fun HomeViewModel.enrichHeroItemsPipeline(
    items: List<MetaPreview>,
    settings: TmdbSettings
): List<MetaPreview> {
    if (items.isEmpty()) return items

    return coroutineScope {
        items.map { item ->
            async(Dispatchers.IO) {
                try {
                    val tmdbId = tmdbService.ensureTmdbId(item.id, item.apiType) ?: return@async item
                    val enrichment = tmdbMetadataService.fetchEnrichment(
                        tmdbId = tmdbId,
                        contentType = item.type,
                        language = settings.language
                    ) ?: return@async item

                    var enriched = item

                    if (settings.useArtwork) {
                        enriched = enriched.copy(
                            background = enrichment.backdrop ?: enriched.background,
                            logo = enrichment.logo ?: enriched.logo,
                            poster = enrichment.poster ?: enriched.poster
                        )
                    }

                    if (settings.useBasicInfo) {
                        enriched = enriched.copy(
                            name = enrichment.localizedTitle ?: enriched.name,
                            description = enrichment.description ?: enriched.description,
                            genres = if (enrichment.genres.isNotEmpty()) enrichment.genres else enriched.genres,
                            imdbRating = enrichment.rating?.toFloat() ?: enriched.imdbRating
                        )
                    }

                    if (settings.useDetails) {
                        enriched = enriched.copy(
                            runtime = enrichment.runtimeMinutes?.toString() ?: enriched.runtime,
                            releaseInfo = enrichment.releaseInfo ?: enriched.releaseInfo,
                            status = enrichment.status ?: enriched.status,
                            ageRating = enrichment.ageRating ?: enriched.ageRating,
                            country = enrichment.countries?.joinToString(", ") ?: enriched.country,
                            language = enrichment.language ?: enriched.language
                        )
                    }

                    enrichmentCache[item.id] = enrichment
                    enriched
                } catch (e: Exception) {
                    Log.w(HomeViewModel.TAG, "Hero enrichment failed for ${item.id}: ${e.message}")
                    item
                }
            }
        }.awaitAll()
    }
}

internal fun HomeViewModel.replaceGridHeroItemsPipeline(
    gridItems: List<GridItem>,
    heroItems: List<MetaPreview>
): List<GridItem> {
    if (gridItems.isEmpty()) return gridItems
    return gridItems.map { item ->
        if (item is GridItem.Hero) {
            item.copy(items = heroItems)
        } else {
            item
        }
    }
}

internal fun HomeViewModel.heroEnrichmentSignaturePipeline(
    items: List<MetaPreview>,
    settings: TmdbSettings
): String {
    val itemSignature = items.joinToString(separator = "|") { item ->
        "${item.id}:${item.apiType}:${item.name}:${item.backdropUrl}:${item.logo}:${item.poster}"
    }
    return buildString {
        append(settings.enabled)
        append(':')
        append(settings.language)
        append(':')
        append(settings.useArtwork)
        append(':')
        append(settings.useBasicInfo)
        append(':')
        append(settings.useDetails)
        append("::")
        append(itemSignature)
    }
}

