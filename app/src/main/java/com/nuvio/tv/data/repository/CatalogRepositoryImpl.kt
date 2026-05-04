package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.repository.CatalogRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogRepositoryImpl @Inject constructor(
    private val api: AddonApi,
    private val moshi: Moshi,
    @ApplicationContext private val context: Context
) : CatalogRepository {
    companion object {
        private const val TAG = "CatalogRepository"
        private const val DISK_CACHE_TTL_MS = 24L * 60 * 60 * 1000 // 24 hours
        private const val DISK_CACHE_VERSION = 1
    }

    private val catalogCache = ConcurrentHashMap<String, CatalogRow>()

    // Moshi adapter for serializing the full cache map
    private val cacheAdapter by lazy {
        val type = Types.newParameterizedType(
            Map::class.java,
            String::class.java,
            CatalogRow::class.java
        )
        moshi.adapter<Map<String, CatalogRow>>(type)
    }

    private fun diskCacheFile(profileId: Int): File =
        File(context.cacheDir, "catalog_cache_v${DISK_CACHE_VERSION}_p${profileId}.json")

    override suspend fun saveCatalogsToDisk(profileId: Int) = withContext(Dispatchers.IO) {
        try {
            val snapshot = catalogCache.toMap()
            if (snapshot.isEmpty()) return@withContext
            val json = cacheAdapter.toJson(snapshot)
            diskCacheFile(profileId).writeText(json)
            Log.d(TAG, "Saved ${snapshot.size} catalog entries to disk for profile $profileId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save catalog cache to disk", e)
        }
    }

    override suspend fun loadCatalogsFromDisk(profileId: Int): Map<String, CatalogRow> = withContext(Dispatchers.IO) {
        try {
            val file = diskCacheFile(profileId)
            if (!file.exists()) return@withContext emptyMap()
            val ageMs = System.currentTimeMillis() - file.lastModified()
            if (ageMs > DISK_CACHE_TTL_MS) {
                Log.d(TAG, "Disk cache for profile $profileId is stale (${ageMs/1000}s old), ignoring")
                file.delete()
                return@withContext emptyMap()
            }
            val json = file.readText()
            val cached = cacheAdapter.fromJson(json) ?: emptyMap()
            // Warm the in-memory cache
            catalogCache.putAll(cached)
            Log.d(TAG, "Loaded ${cached.size} catalog entries from disk for profile $profileId")
            cached
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load catalog cache from disk", e)
            emptyMap()
        }
    }

    override fun clearDiskCache(profileId: Int) {
        diskCacheFile(profileId).delete()
    }

    override fun getCatalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        skipStep: Int,
        extraArgs: Map<String, String>,
        supportsSkip: Boolean
    ): Flow<NetworkResult<CatalogRow>> = flow {
        val cacheKey = buildCacheKey(
            addonBaseUrl = addonBaseUrl,
            addonId = addonId,
            type = type,
            catalogId = catalogId,
            skip = skip,
            skipStep = skipStep,
            extraArgs = extraArgs
        )

        // Emit cached data immediately if available
        val cached = catalogCache[cacheKey]
        if (cached != null) {
            emit(NetworkResult.Success(cached))
        } else {
            emit(NetworkResult.Loading)
        }

        val url = buildCatalogUrl(addonBaseUrl, type, catalogId, skip, extraArgs)
        Log.d(
            TAG,
            "Fetching catalog addonId=$addonId addonName=$addonName type=$type catalogId=$catalogId skip=$skip skipStep=$skipStep supportsSkip=$supportsSkip url=$url"
        )

        when (val result = safeApiCall { api.getCatalog(url) }) {
            is NetworkResult.Success -> {
                val items = result.data.metas.map { it.toDomain() }
                Log.d(
                    TAG,
                    "Catalog fetch success addonId=$addonId type=$type catalogId=$catalogId items=${items.size}"
                )

                val effectiveSkipStep = if (skip == 0 && items.isNotEmpty() && items.size < skipStep) {
                    items.size
                } else {
                    skipStep
                }
                val catalogRow = CatalogRow(
                    addonId = addonId,
                    addonName = addonName,
                    addonBaseUrl = addonBaseUrl,
                    catalogId = catalogId,
                    catalogName = catalogName,
                    type = ContentType.fromString(type),
                    rawType = type,
                    items = items,
                    isLoading = false,
                    hasMore = supportsSkip && items.isNotEmpty(),
                    currentPage = if (effectiveSkipStep > 0) skip / effectiveSkipStep else 0,
                    supportsSkip = supportsSkip,
                    skipStep = effectiveSkipStep
                )
                catalogCache[cacheKey] = catalogRow
                // Only emit fresh data if it differs from cache
                if (cached == null || cached.items != catalogRow.items) {
                    emit(NetworkResult.Success(catalogRow))
                }
            }
            is NetworkResult.Error -> {
                Log.w(
                    TAG,
                    "Catalog fetch failed addonId=$addonId type=$type catalogId=$catalogId code=${result.code} message=${result.message} url=$url"
                )
                // Only emit error if we had no cached data
                if (cached == null) {
                    emit(result)
                }
            }
            NetworkResult.Loading -> { /* Already emitted */ }
        }
    }

    private fun buildCatalogUrl(
        baseUrl: String,
        type: String,
        catalogId: String,
        skip: Int,
        extraArgs: Map<String, String>
    ): String {
        val cleanBaseUrl = baseUrl.trimEnd('/')

        if (extraArgs.isEmpty()) {
            return if (skip > 0) {
                "$cleanBaseUrl/catalog/$type/$catalogId/skip=$skip.json"
            } else {
                "$cleanBaseUrl/catalog/$type/$catalogId.json"
            }
        }

        val allArgs = LinkedHashMap<String, String>()
        allArgs.putAll(extraArgs)

        // For Stremio catalogs, pagination is controlled by `skip` inside extraArgs.
        if (!allArgs.containsKey("skip") && skip > 0) {
            allArgs["skip"] = skip.toString()
        }

        val encodedArgs = allArgs.entries.joinToString("&") { (key, value) ->
            "${encodeArg(key)}=${encodeArg(value)}"
        }

        return "$cleanBaseUrl/catalog/$type/$catalogId/$encodedArgs.json"
    }

    private fun encodeArg(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    private fun buildCacheKey(
        addonBaseUrl: String,
        addonId: String,
        type: String,
        catalogId: String,
        skip: Int,
        skipStep: Int,
        extraArgs: Map<String, String>
    ): String {
        val normalizedArgs = extraArgs.entries
            .sortedBy { it.key }
            .joinToString("&") { "${it.key}=${it.value}" }
        val normalizedBaseUrl = addonBaseUrl.trim().trimEnd('/').lowercase()
        return "${normalizedBaseUrl}_${addonId}_${type}_${catalogId}_${skip}_${skipStep}_${normalizedArgs}"
    }
}
