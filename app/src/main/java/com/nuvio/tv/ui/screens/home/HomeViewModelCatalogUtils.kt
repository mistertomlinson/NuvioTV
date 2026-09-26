package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.ui.catalog.reconcileDynamicCatalogOrder
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.catalog.watchlyCatalogGroup
import com.nuvio.tv.ui.catalog.watchlySavedGroup
import kotlinx.coroutines.Job

internal fun HomeViewModel.catalogKey(addonId: String, type: String, catalogId: String): String {
    return "${addonId}_${type}_${catalogId}"
}

internal fun HomeViewModel.buildHomeCatalogLoadSignature(
    addons: List<Addon>,
    profileId: Int
): String {
    /*
     * Catalog names are display metadata, not catalog identity.
     *
     * Installed-addon observation can emit a cached manifest followed by a
     * refreshed manifest. Dynamic catalog names may legitimately differ
     * between those emissions. Including catalog.name here treated that
     * harmless label refresh as a structural change and destructively
     * restarted Home, temporarily replacing loaded rows with skeletons.
     *
     * Display metadata is refreshed in place by
     * refreshCatalogDisplayMetadataPipeline().
     */
    val addonCatalogSignature = addons
        .flatMap { addon ->
            addon.catalogs.map { catalog ->
                "${addon.id}|${addon.baseUrl}|${catalog.apiType}|${catalog.id}|${catalog.showInHome}|${catalog.hasExplicitShowInHome}"
            }
        }
        .sorted()
        .joinToString(separator = ",")
    val disabledSignature = disabledHomeCatalogKeys
        .asSequence()
        .sorted()
        .joinToString(separator = ",")
    return "$profileId::$addonCatalogSignature::$disabledSignature"
}

internal fun HomeViewModel.registerCatalogLoadJob(job: Job) {
    synchronized(activeCatalogLoadJobs) {
        activeCatalogLoadJobs.add(job)
    }
    job.invokeOnCompletion {
        synchronized(activeCatalogLoadJobs) {
            activeCatalogLoadJobs.remove(job)
        }
    }
}

internal fun HomeViewModel.cancelInFlightCatalogLoads() {
    val jobsToCancel = synchronized(activeCatalogLoadJobs) {
        activeCatalogLoadJobs.toList().also { activeCatalogLoadJobs.clear() }
    }
    jobsToCancel.forEach { it.cancel() }
}

internal fun HomeViewModel.rebuildCatalogOrder(
    addons: List<Addon>
) {
    val defaultOrder =
        buildDefaultCatalogOrder(
            addons
        )

    val mergedOrder =
        reconcileDynamicCatalogOrder(
            defaultOrder = defaultOrder,
            savedOrderKeys =
                homeCatalogOrderKeys,
            myListKey =
                HomeViewModel.MY_LIST_CATALOG_KEY
        )

    com.nuvio.tv.data.local.CatalogOrderProbe.log(
        appContext,
        "RECONCILE",
        "savedInput=$homeCatalogOrderKeys output=$mergedOrder " +
            "droppedFromSaved=${
                com.nuvio.tv.data.local.CatalogOrderProbe.dropped(
                    homeCatalogOrderKeys,
                    mergedOrder
                )
            }"
    )

    catalogOrder.clear()
    catalogOrder.addAll(
        mergedOrder
    )
}

private fun watchlyGroup(key: String): String? = watchlyCatalogGroup(key)

private fun HomeViewModel.buildDefaultCatalogOrder(addons: List<Addon>): List<String> {
    val orderedKeys = mutableListOf<String>()
    // My List is always a valid catalog key — observeMyList() controls whether
    // the row actually appears based on Trakt auth and watchlist content.
    orderedKeys.add(HomeViewModel.MY_LIST_CATALOG_KEY)
    addons.forEach { addon ->
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
            .forEach { catalog ->
                val key = catalogKey(
                    addonId = addon.id,
                    type = catalog.apiType,
                    catalogId = catalog.id
                )
                if (key !in orderedKeys) {
                    orderedKeys.add(key)
                }
            }
    }
    return orderedKeys
}

internal fun HomeViewModel.isCatalogDisabled(
    addonBaseUrl: String,
    addonId: String,
    type: String,
    catalogId: String,
    catalogName: String
): Boolean {
    if (disableCatalogKey(addonBaseUrl, type, catalogId, catalogName) in disabledHomeCatalogKeys) {
        return true
    }
    // Backward compatibility with previously stored keys.
    return catalogKey(addonId, type, catalogId) in disabledHomeCatalogKeys
}

internal fun HomeViewModel.disableCatalogKey(
    addonBaseUrl: String,
    type: String,
    catalogId: String,
    catalogName: String
): String {
    return "${addonBaseUrl}_${type}_${catalogId}_${catalogName}"
}

internal fun CatalogDescriptor.isSearchOnlyCatalog(): Boolean {
    return extra.any { extra -> extra.name.equals("search", ignoreCase = true) && extra.isRequired }
}

internal fun CatalogDescriptor.shouldShowOnHome(): Boolean {
    if (isSearchOnlyCatalog()) return false
    return !hasExplicitShowInHome || showInHome
}

internal fun MetaPreview.hasHeroArtwork(): Boolean {
    return !background.isNullOrBlank()
}

internal fun HomeViewModel.extractYear(releaseInfo: String?): String? {
    if (releaseInfo.isNullOrBlank()) return null
    return Regex("\\b(19|20)\\d{2}\\b").find(releaseInfo)?.value
}
