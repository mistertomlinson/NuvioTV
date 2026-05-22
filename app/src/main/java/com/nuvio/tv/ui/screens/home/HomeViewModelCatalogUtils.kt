package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.Job

internal fun HomeViewModel.catalogKey(addonId: String, type: String, catalogId: String): String {
    return "${addonId}_${type}_${catalogId}"
}

internal fun HomeViewModel.buildHomeCatalogLoadSignature(addons: List<Addon>): String {
    val addonCatalogSignature = addons
        .flatMap { addon ->
            addon.catalogs.map { catalog ->
                "${addon.id}|${addon.baseUrl}|${catalog.apiType}|${catalog.id}|${catalog.name}|${catalog.showInHome}|${catalog.hasExplicitShowInHome}"
            }
        }
        .sorted()
        .joinToString(separator = ",")
    val disabledSignature = disabledHomeCatalogKeys
        .asSequence()
        .sorted()
        .joinToString(separator = ",")
    return "$addonCatalogSignature::$disabledSignature"
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

internal fun HomeViewModel.rebuildCatalogOrder(addons: List<Addon>) {
    val defaultOrder = buildDefaultCatalogOrder(addons)
    val availableSet = defaultOrder.toSet()

    // For dynamic Watchly catalogs (e.g. watchly.loved.ttXXX, watchly.watched.ttXXX,
    // watchly.theme.*), the catalog ID changes each session. Match saved keys by
    // group prefix so ordering is preserved across sessions.
    fun watchlyGroupPrefix(key: String): String? {
        return when {
            key.contains("watchly.loved.") -> "watchly.loved."
            key.contains("watchly.watched.") -> "watchly.watched."
            key.contains("watchly.theme.") -> "watchly.theme."
            else -> null
        }
    }

    val groupPrefixToAvailableKeys = mutableMapOf<String, MutableList<String>>()
    defaultOrder.forEach { key ->
        val prefix = watchlyGroupPrefix(key)
        if (prefix != null) {
            groupPrefixToAvailableKeys.getOrPut(prefix) { mutableListOf() }.add(key)
        }
    }

    val claimedAvailableKeys = mutableSetOf<String>()

    val savedValid = homeCatalogOrderKeys
        .asSequence()
        .mapNotNull { savedKey ->
            when {
                savedKey in availableSet -> savedKey
                else -> {
                    val prefix = watchlyGroupPrefix(savedKey)
                    if (prefix != null) {
                        groupPrefixToAvailableKeys[prefix]
                            ?.firstOrNull { it !in claimedAvailableKeys }
                    } else null
                }
            }
        }
        .onEach { claimedAvailableKeys.add(it) }
        .distinct()
        .toList()

    val savedSet = savedValid.toSet()
    val missing = defaultOrder.filterNot { it in savedSet }

    // For missing Watchly catalogs, insert at group position rather than bottom
    val mergedOrder = savedValid.toMutableList()
    missing.forEach { missingKey ->
        val group = watchlyGroup(missingKey)
        if (group == null) {
            mergedOrder.add(missingKey)
        } else {
            var insertAt = mergedOrder.indexOfLast { watchlyGroup(it) == group }
            if (insertAt >= 0) {
                mergedOrder.add(insertAt + 1, missingKey)
            } else {
                insertAt = mergedOrder.indexOfLast { watchlyGroup(it) != null }
                if (insertAt >= 0) {
                    mergedOrder.add(insertAt + 1, missingKey)
                } else {
                    mergedOrder.add(missingKey)
                }
            }
        }
    }

    catalogOrder.clear()
    catalogOrder.addAll(mergedOrder)
}

private fun watchlyGroup(key: String): String? {
    if (!key.contains("com.bimal.watchly")) return null
    val isMovie = key.contains("_movie_")
    val isSeries = key.contains("_series_")
    val typeSuffix = when {
        isMovie -> "movie"
        isSeries -> "series"
        else -> "movie"
    }
    return when {
        key.contains("watchly.watched") -> "watchly.watched.$typeSuffix"
        key.contains("watchly.theme") -> "watchly.theme.$typeSuffix"
        key.contains("watchly.rec") -> "watchly.rec.$typeSuffix"
        key.contains("watchly.creators") -> "watchly.creators.$typeSuffix"
        key.contains("watchly.all.loved") -> "watchly.all.loved.$typeSuffix"
        key.contains("watchly.loved") -> "watchly.loved.$typeSuffix"
        key.contains("watchly.liked") -> "watchly.liked.$typeSuffix"
        else -> "watchly.other"
    }
}

private fun HomeViewModel.buildDefaultCatalogOrder(addons: List<Addon>): List<String> {
    val orderedKeys = mutableListOf<String>()
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
