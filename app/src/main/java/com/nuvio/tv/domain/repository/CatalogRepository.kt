package com.nuvio.tv.domain.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.CatalogRow
import kotlinx.coroutines.flow.Flow

interface CatalogRepository {
    fun getCatalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int = 0,
        skipStep: Int = 100,
        extraArgs: Map<String, String> = emptyMap(),
        supportsSkip: Boolean = false
    ): Flow<NetworkResult<CatalogRow>>

    suspend fun saveCatalogsToDisk(profileId: Int)
    suspend fun loadCatalogsFromDisk(profileId: Int): Map<String, CatalogRow>
    fun clearDiskCache(profileId: Int)
    suspend fun clearAddonCache(addonId: String, profileId: Int)
}
