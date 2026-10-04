package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import coil.Coil
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.ui.catalog.SEASONAL_SPOTLIGHT_ADDON_ID
import com.nuvio.tv.ui.catalog.seasonalSpotlightGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SEASONAL_STAGE_VISIBLE_WINDOW = 25
private const val SEASONAL_CONTENT_REVALIDATE_INTERVAL_MS =
    60L * 60L * 1000L

/**
 * Mark whether Home itself is currently being presented.
 *
 * Heavy Seasonal Spotlight staging intentionally starts only after Home has
 * left composition so network, TMDB and image decode work cannot compete with
 * DPAD scrolling.
 */
private fun HomeViewModel.ensureSeasonalSpotlightContentRefreshTicker() {
    if (
        seasonalSpotlightContentRefreshTickerJob
            ?.isActive == true
    ) {
        return
    }

    seasonalSpotlightContentRefreshTickerJob =
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(
                    SEASONAL_CONTENT_REVALIDATE_INTERVAL_MS
                )

                /*
                 * Deliberately tiny active-Home cost: one Boolean write.
                 * No catalog request, TMDB work, image request, row rebuild,
                 * Compose state mutation, or focus-path work happens here.
                 */
                seasonalSpotlightContentRefreshDue =
                    true

                if (!homePresentationVisible) {
                    startPendingSeasonalSpotlightStageIfNeeded()
                }
            }
        }
}

private fun HomeViewModel.startPendingSeasonalSpotlightStageIfNeeded() {
    if (
        seasonalSpotlightStageReadyState.value ||
        seasonalSpotlightStageJob?.isActive == true
    ) {
        return
    }

    val addonsForStage =
        pendingSeasonalSpotlightAddons
            ?: if (
                seasonalSpotlightContentRefreshDue
            ) {
                addonsCache
            } else {
                return
            }

    val seasonalAddon =
        addonsForStage.firstOrNull { addon ->
            addon.id ==
                SEASONAL_SPOTLIGHT_ADDON_ID
        } ?: run {
            /*
             * Nothing installed to probe. Do not wake once per Home exit for
             * the same irrelevant hourly tick.
             */
            seasonalSpotlightContentRefreshDue =
                false
            return
        }

    if (
        pendingSeasonalSpotlightAddons == null
    ) {
        /*
         * Same-manifest content refresh. Treat the active addon snapshot as
         * the pending candidate; it will be discarded again if the catalog
         * endpoint proves unchanged.
         */
        pendingSeasonalSpotlightAddons =
            addonsForStage

        seasonalSpotlightRefreshPendingState.value =
            true

        seasonalSpotlightStageReadyState.value =
            false

        stagedSeasonalSpotlightRows.clear()
    }

    stagePendingSeasonalSpotlight(
        addons = addonsForStage,
        seasonalAddon = seasonalAddon
    )
}

internal fun HomeViewModel.setHomePresentationVisible(
    visible: Boolean
) {
    ensureSeasonalSpotlightContentRefreshTicker()

    homePresentationVisible = visible

    if (visible) {
        /*
         * Never let background Seasonal preparation compete with an active
         * Home session. CatalogRepository retains completed fetches, so a
         * later staging attempt can reuse that work.
         */
        seasonalSpotlightStageJob?.cancel()
        seasonalSpotlightStageJob = null
        return
    }

    startPendingSeasonalSpotlightStageIfNeeded()
}

/**
 * Fetch and prepare the replacement Seasonal Spotlight block without touching
 * catalogsMap, catalogSourceRows, catalogOrder or HomeUiState.
 */
internal fun HomeViewModel.stagePendingSeasonalSpotlight(
    addons: List<Addon>,
    seasonalAddon: Addon
) {
    seasonalSpotlightStageJob?.cancel()

    seasonalSpotlightStageReadyState.value = false
    stagedSeasonalSpotlightRows.clear()

    seasonalSpotlightStageJob =
        viewModelScope.launch(Dispatchers.IO) {
            val preparedRows =
                coroutineScope {
                    seasonalAddon.catalogs.map { catalog ->
                        async {
                            var latestRow:
                                CatalogRow? = null

                            var hardFailure = false

                            catalogRepository.getCatalog(
                                addonBaseUrl =
                                    seasonalAddon.baseUrl,
                                addonId =
                                    seasonalAddon.id,
                                addonName =
                                    seasonalAddon.displayName,
                                catalogId =
                                    catalog.id,
                                catalogName =
                                    catalog.name,
                                type =
                                    catalog.apiType,
                                skip = 0,
                                skipStep =
                                    catalog.skipStep(),
                                supportsSkip =
                                    catalog.supportsExtra(
                                        "skip"
                                    )
                            ).collect { result ->
                                when (result) {
                                    is NetworkResult.Success -> {
                                        latestRow =
                                            result.data
                                                .withCatalogDisplayMetadata(
                                                    addonName =
                                                        seasonalAddon
                                                            .displayName,
                                                    addonBaseUrl =
                                                        seasonalAddon
                                                            .baseUrl,
                                                    catalogName =
                                                        catalog.name
                                                )
                                    }

                                    is NetworkResult.Error -> {
                                        if (latestRow == null) {
                                            hardFailure = true
                                        }
                                    }

                                    NetworkResult.Loading -> {
                                        // No-op.
                                    }
                                }
                            }

                            if (hardFailure) {
                                null
                            } else {
                                latestRow
                            }
                        }
                    }.awaitAll()
                }

            /*
             * Fail closed to the currently visible Seasonal block.
             * Never promote a partial replacement.
             */
            if (
                preparedRows.size !=
                    seasonalAddon.catalogs.size ||
                preparedRows.any {
                    it == null ||
                        it.items.isEmpty()
                }
            ) {
                android.util.Log.w(
                    HomeViewModel.TAG,
                    "Seasonal Spotlight stage incomplete; " +
                        "keeping current Home rows"
                )
                return@launch
            }

            val tmdbSettings =
                currentTmdbSettings

            val fullyPrepared =
                preparedRows
                    .filterNotNull()
                    .map { row ->
                        val visibleItems =
                            row.items.take(
                                SEASONAL_STAGE_VISIBLE_WINDOW
                            )

                        val enrichedVisibleItems =
                            if (
                                tmdbSettings.enabled &&
                                (
                                    tmdbSettings.useArtwork ||
                                        tmdbSettings.useBasicInfo ||
                                        tmdbSettings.useDetails
                                    )
                            ) {
                                enrichHeroItemsPipeline(
                                    items =
                                        visibleItems,
                                    settings =
                                        tmdbSettings
                                ).mapIndexed { index, enriched ->
                                    /*
                                     * Seasonal Spotlight's English-only
                                     * curation is an addon eligibility rule,
                                     * not presentation metadata. Preserve the
                                     * catalog item's original language field
                                     * so TMDB enrichment cannot surface "EN"
                                     * in Nuvio's Home hero for this addon.
                                     */
                                    enriched.copy(
                                        language =
                                            visibleItems
                                                .getOrNull(index)
                                                ?.language
                                    )
                                }
                            } else {
                                visibleItems
                            }

                        val enrichedById =
                            enrichedVisibleItems
                                .associateBy {
                                    "${it.apiType}:${it.id}"
                                }

                        row.copy(
                            items =
                                row.items.map { item ->
                                    enrichedById[
                                        "${item.apiType}:${item.id}"
                                    ] ?: item
                                },
                            isLoading = false
                        )
                    }

            /*
             * Manifest identity can stay unchanged across the addon's daily
             * item shuffle. Compare only presentation-relevant server facts:
             * catalog identity/name plus the ordered content IDs.
             *
             * Do this before image prewarming so an hourly no-change probe
             * remains extremely cheap after the catalog response arrives.
             */
            if (
                !seasonalSpotlightStageDiffersFromLive(
                    fullyPrepared
                )
            ) {
                pendingSeasonalSpotlightAddons =
                    null

                stagedSeasonalSpotlightRows.clear()

                seasonalSpotlightStageReadyState.value =
                    false

                seasonalSpotlightRefreshPendingState.value =
                    false

                seasonalSpotlightContentRefreshDue =
                    false

                android.util.Log.d(
                    HomeViewModel.TAG,
                    "Seasonal Spotlight content unchanged; " +
                        "discarded staged probe"
                )

                return@launch
            }

            /*
             * Warm the images the first Home viewport can actually display.
             * This is deliberately off-screen and sequential: freshness is
             * background work, not something allowed to steal resources from
             * active Home navigation.
             */
            prewarmSeasonalSpotlightStage(
                fullyPrepared
            )

            /*
             * A newer manifest may have arrived while this stage was running.
             * Never publish preparation belonging to an obsolete manifest.
             */
            if (
                pendingSeasonalSpotlightAddons !=
                    addons
            ) {
                return@launch
            }

            synchronized(
                stagedSeasonalSpotlightRows
            ) {
                stagedSeasonalSpotlightRows.clear()

                fullyPrepared.forEach { row ->
                    val key =
                        catalogKey(
                            addonId =
                                row.addonId,
                            type =
                                row.apiType,
                            catalogId =
                                row.catalogId
                        )

                    stagedSeasonalSpotlightRows[
                        key
                    ] = row
                }
            }

            seasonalSpotlightContentRefreshDue =
                false

            seasonalSpotlightStageReadyState.value =
                true

            android.util.Log.d(
                HomeViewModel.TAG,
                "Seasonal Spotlight replacement staged: " +
                    "${fullyPrepared.size} rows"
            )
        }
}

private fun HomeViewModel.seasonalSpotlightStageDiffersFromLive(
    rows: List<CatalogRow>
): Boolean {
    fun rowSignature(
        row: CatalogRow
    ): Pair<String, List<String>> =
        row.catalogName to
            row.items.map { item ->
                "${item.apiType}:${item.id}"
            }

    val stagedSignature =
        rows.associate { row ->
            catalogKey(
                addonId = row.addonId,
                type = row.apiType,
                catalogId = row.catalogId
            ) to rowSignature(row)
        }

    val liveSignature =
        synchronized(catalogSourceRows) {
            catalogSourceRows
                .filterKeys { key ->
                    seasonalSpotlightGroup(
                        key
                    ) != null
                }
                .mapValues { (_, row) ->
                    rowSignature(row)
                }
        }

    return stagedSignature !=
        liveSignature
}

private suspend fun HomeViewModel.prewarmSeasonalSpotlightStage(
    rows: List<CatalogRow>
) {
    withContext(Dispatchers.IO) {
        val imageLoader =
            Coil.imageLoader(appContext)

        val urls =
            linkedSetOf<String>()

        rows.forEach { row ->
            row.items
                .take(
                    SEASONAL_STAGE_VISIBLE_WINDOW
                )
                .mapNotNullTo(urls) {
                    it.poster
                }

            row.items
                .firstOrNull()
                ?.let { hero ->
                    hero.background
                        ?.let(urls::add)

                    hero.logo
                        ?.let(urls::add)

                    hero.landscapePoster
                        ?.let(urls::add)
                }
        }

        urls.forEach { url ->
            runCatching {
                imageLoader.execute(
                    ImageRequest.Builder(
                        appContext
                    )
                        .data(url)
                        .memoryCachePolicy(
                            CachePolicy.ENABLED
                        )
                        .build()
                )
            }
        }
    }
}

/**
 * Promote a completely staged Seasonal Spotlight replacement into Home.
 *
 * Only the Seasonal Spotlight block changes. The rest of Home, enrichment
 * caches, focus state and loaded catalog rows remain untouched.
 */
internal suspend fun HomeViewModel.applyStagedSeasonalSpotlightIfReady():
    Boolean {

    if (
        !seasonalSpotlightStageReadyState.value
    ) {
        return false
    }

    val pendingAddons =
        pendingSeasonalSpotlightAddons
            ?: return false

    val staged =
        synchronized(
            stagedSeasonalSpotlightRows
        ) {
            stagedSeasonalSpotlightRows
                .toMap()
        }

    /*
     * An empty staged map is valid when no Seasonal Spotlight event/catalog
     * is currently active. In that case applying the staged replacement means
     * removing the old Seasonal block and inserting nothing.
     */
    val oldSeasonalKeys =
        synchronized(catalogsMap) {
            catalogsMap.keys
                .filter { key ->
                    seasonalSpotlightGroup(
                        key
                    ) != null
                }
                .toList()
        }

    synchronized(catalogsMap) {
        oldSeasonalKeys.forEach(
            catalogsMap::remove
        )

        staged.forEach { (key, sourceRow) ->
            val visibleItems =
                sourceRow.items.take(
                    SEASONAL_STAGE_VISIBLE_WINDOW
                )

            catalogsMap[key] =
                sourceRow.copy(
                    items =
                        visibleItems,
                    isLoading = false,
                    hasMore =
                        sourceRow.items.size >
                            visibleItems.size ||
                            sourceRow.hasMore
                )

            /*
             * The staged visible items already completed the isolated TMDB
             * preparation above. Mark them terminal so the normal row gate
             * does not turn the atomic replacement back into skeletons.
             */
            homeEnrichmentAttemptedIds.addAll(
                visibleItems.map {
                    it.id
                }
            )
        }
    }

    synchronized(catalogSourceRows) {
        oldSeasonalKeys.forEach(
            catalogSourceRows::remove
        )

        staged.forEach {
                (key, sourceRow) ->
            catalogSourceRows[key] =
                sourceRow
        }
    }

    addonsCache =
        pendingAddons

    activeCatalogLoadSignature =
        buildHomeCatalogLoadSignature(
            addons =
                addonsCache,
            profileId =
                profileManager
                    .activeProfileId
                    .value
        )

    rebuildCatalogOrder(
        addonsCache
    )

    pendingSeasonalSpotlightAddons =
        null

    stagedSeasonalSpotlightRows.clear()

    seasonalSpotlightStageReadyState.value =
        false

    seasonalSpotlightRefreshPendingState.value =
        false

    seasonalSpotlightContentRefreshDue =
        false

    /*
     * Recompute Home synchronously before the frozen return frame is allowed
     * to dissolve. This updates catalogRows/order as one presentation commit.
     */
    updateCatalogRowsPipeline()

    android.util.Log.d(
        HomeViewModel.TAG,
        "Applied staged Seasonal Spotlight " +
            "replacement on Home return"
    )

    return true
}
