package com.nuvio.tv.core.tmdb

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.TmdbApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import com.nuvio.tv.data.local.ImdbTmdbMappingCache
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

private const val TAG = "TmdbService"
private val TMDB_API_KEY = BuildConfig.TMDB_API_KEY

/**
 * Service to handle TMDB ID conversions and lookups.
 * Provides caching to avoid redundant API calls.
 */
@Singleton
class TmdbService @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val imdbTmdbMappingCache: ImdbTmdbMappingCache
) {
    // Cache: IMDB ID -> TMDB ID
    private val imdbToTmdbCache = ConcurrentHashMap<String, Int>()
    
    // Cache: TMDB ID -> IMDB ID  
    private val tmdbToImdbCache = ConcurrentHashMap<Int, String>()

    private val imdbToTmdbInFlight = ConcurrentHashMap<String, CompletableDeferred<Int?>>()
    private val tmdbToImdbInFlight = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    
    // Mutex for thread-safe cache operations
    private val cacheMutex = Mutex()

    @Volatile private var mappingDiskCacheLoaded = false

    private suspend fun ensureMappingDiskCacheLoaded() {
        if (mappingDiskCacheLoaded) return
        mappingDiskCacheLoaded = true
        val loaded = imdbTmdbMappingCache.loadAll()
        if (loaded.isNotEmpty()) {
            loaded.forEach { (imdbId, tmdbId) -> preCacheMapping(imdbId, tmdbId) }
            Log.d(TAG, "Restored ${loaded.size} IMDB->TMDB mappings from disk")
        }
    }
    
    /**
     * Convert an IMDB ID to a TMDB ID.
     * 
     * @param imdbId The IMDB ID (e.g., "tt0133093")
     * @param mediaType The media type ("movie" or "series"/"tv")
     * @return The TMDB ID, or null if not found
     */
    suspend fun imdbToTmdb(imdbId: String, mediaType: String): Int? = withContext(Dispatchers.IO) {
        ensureMappingDiskCacheLoaded()
        // Validate IMDB ID format
        if (!imdbId.startsWith("tt")) {
            Log.w(TAG, "Invalid IMDB ID format: $imdbId")
            return@withContext null
        }
        
        // Check cache first
        imdbToTmdbCache[imdbId]?.let { cached ->
            Log.d(TAG, "Cache hit: IMDB $imdbId -> TMDB $cached")
            return@withContext cached
        }
        
        val normalizedType = normalizeMediaType(mediaType)
        val requestKey = "$imdbId:$normalizedType"
        val requestDeferred = CompletableDeferred<Int?>()
        imdbToTmdbInFlight.putIfAbsent(requestKey, requestDeferred)?.let { existing ->
            return@withContext existing.await()
        }

        try {
            Log.d(TAG, "Looking up TMDB ID for IMDB: $imdbId (type: $mediaType)")
            
            val response = tmdbApi.findByExternalId(
                externalId = imdbId,
                apiKey = TMDB_API_KEY,
                externalSource = "imdb_id"
            )
            
            if (!response.isSuccessful) {
                Log.e(TAG, "TMDB API error: ${response.code()} - ${response.message()}")
                requestDeferred.complete(null)
                return@withContext null
            }
            
            val body = response.body()
            if (body == null) {
                requestDeferred.complete(null)
                return@withContext null
            }
            
            // Determine which results to use based on media type
            val result = when (normalizedType) {
                "movie" -> body.movieResults?.firstOrNull()
                "tv", "series" -> body.tvResults?.firstOrNull()
                else -> body.movieResults?.firstOrNull() ?: body.tvResults?.firstOrNull()
            }
            
            result?.let { found ->
                Log.d(TAG, "Found TMDB ID: ${found.id} for IMDB: $imdbId")
                
                // Cache both directions
                cacheMutex.withLock {
                    imdbToTmdbCache[imdbId] = found.id
                    tmdbToImdbCache[found.id] = imdbId
                }
                GlobalScope.launch(Dispatchers.IO) {
                    imdbTmdbMappingCache.saveAll(imdbToTmdbCache.toMap())
                }
                
                requestDeferred.complete(found.id)
                return@withContext found.id
            }
            
            Log.w(TAG, "No TMDB result found for IMDB: $imdbId")
            requestDeferred.complete(null)
            null
            
        } catch (e: CancellationException) {
            requestDeferred.cancel(e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up TMDB ID for $imdbId: ${e.message}", e)
            requestDeferred.complete(null)
            null
        } finally {
            imdbToTmdbInFlight.remove(requestKey, requestDeferred)
        }
    }
    
    /**
     * Convert a TMDB ID to an IMDB ID.
     * 
     * @param tmdbId The TMDB ID
     * @param mediaType The media type ("movie" or "series"/"tv")
     * @return The IMDB ID, or null if not found
     */
    suspend fun tmdbToImdb(tmdbId: Int, mediaType: String): String? = withContext(Dispatchers.IO) {
        // Check cache first
        tmdbToImdbCache[tmdbId]?.let { cached ->
            Log.d(TAG, "Cache hit: TMDB $tmdbId -> IMDB $cached")
            return@withContext cached
        }
        
        val normalizedType = normalizeMediaType(mediaType)
        val requestKey = "$tmdbId:$normalizedType"
        val requestDeferred = CompletableDeferred<String?>()
        tmdbToImdbInFlight.putIfAbsent(requestKey, requestDeferred)?.let { existing ->
            return@withContext existing.await()
        }

        try {
            Log.d(TAG, "Looking up IMDB ID for TMDB: $tmdbId (type: $mediaType)")
            
            val response = when (normalizedType) {
                "movie" -> tmdbApi.getMovieExternalIds(tmdbId, TMDB_API_KEY)
                "tv", "series" -> tmdbApi.getTvExternalIds(tmdbId, TMDB_API_KEY)
                else -> tmdbApi.getMovieExternalIds(tmdbId, TMDB_API_KEY)
            }
            
            if (!response.isSuccessful) {
                Log.e(TAG, "TMDB API error: ${response.code()} - ${response.message()}")
                requestDeferred.complete(null)
                return@withContext null
            }
            
            val body = response.body()
            if (body == null) {
                requestDeferred.complete(null)
                return@withContext null
            }
            
            body.imdbId?.let { imdbId ->
                Log.d(TAG, "Found IMDB ID: $imdbId for TMDB: $tmdbId")
                
                // Cache both directions
                cacheMutex.withLock {
                    tmdbToImdbCache[tmdbId] = imdbId
                    imdbToTmdbCache[imdbId] = tmdbId
                }
                
                requestDeferred.complete(imdbId)
                return@withContext imdbId
            }
            
            Log.w(TAG, "No IMDB ID found for TMDB: $tmdbId")
            requestDeferred.complete(null)
            null
            
        } catch (e: CancellationException) {
            requestDeferred.cancel(e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up IMDB ID for $tmdbId: ${e.message}", e)
            requestDeferred.complete(null)
            null
        } finally {
            tmdbToImdbInFlight.remove(requestKey, requestDeferred)
        }
    }
    

    /**
     * Like imdbToTmdb, but if the resolved TMDB entry has no episodes for the
     * requested season, falls back to a TMDB name search to find the correct entry.
     * Handles cases where IMDB and TMDB have different series structures
     * (e.g. IMDB treats a sequel as Season 2 while TMDB lists it as a separate series).
     */
    suspend fun imdbToTmdbWithSeasonFallback(
        imdbId: String,
        mediaType: String,
        showName: String?,
        requiredSeason: Int?
    ): Int? = withContext(Dispatchers.IO) {
        val primaryId = imdbToTmdb(imdbId, mediaType) ?: return@withContext null

        if (requiredSeason == null || requiredSeason <= 1 || showName.isNullOrBlank()) {
            return@withContext primaryId
        }

        try {
            val seasonResponse = tmdbApi.getTvSeasonDetails(
                tvId = primaryId,
                seasonNumber = requiredSeason,
                apiKey = TMDB_API_KEY,
                language = "en-US"
            )
            if (seasonResponse.isSuccessful && !seasonResponse.body()?.episodes.isNullOrEmpty()) {
                return@withContext primaryId
            }
        } catch (e: Exception) {
            Log.w(TAG, "Season check failed for TMDB $primaryId season $requiredSeason: ${e.message}")
        }

        Log.d(TAG, "TMDB $primaryId missing season $requiredSeason, searching by name: $showName")
        try {
            val searchResponse = tmdbApi.searchTv(
                apiKey = TMDB_API_KEY,
                query = showName,
                language = "en-US"
            )
            if (!searchResponse.isSuccessful) return@withContext primaryId

            val results = searchResponse.body()?.results.orEmpty()
            if (results.isEmpty()) return@withContext primaryId

            val normalizedQuery = showName.trim().lowercase()
            val best = results.firstOrNull {
                it.name?.trim()?.lowercase() == normalizedQuery ||
                it.originalName?.trim()?.lowercase() == normalizedQuery
            } ?: results.firstOrNull()

            val fallbackId = best?.id ?: return@withContext primaryId

            if (fallbackId != primaryId) {
                Log.d(TAG, "Season fallback: using TMDB $fallbackId instead of $primaryId for '$showName' season $requiredSeason")
                cacheMutex.withLock {
                    imdbToTmdbCache[imdbId] = fallbackId
                    tmdbToImdbCache[fallbackId] = imdbId
                }
            }

            fallbackId
        } catch (e: Exception) {
            Log.w(TAG, "Name search fallback failed for '$showName': ${e.message}")
            primaryId
        }
    }

    /**
     * Get a TMDB ID from a video ID string.
     * Handles both IMDB IDs (tt...) and TMDB IDs.
     * 
     * @param videoId The video ID (can be IMDB or TMDB format)
     * @param mediaType The media type
     * @return The TMDB ID as a string, or null if conversion failed
     */
    suspend fun ensureTmdbId(videoId: String, mediaType: String): String? {
        // Check if it's already a TMDB ID (numeric or prefixed)
        val cleanId = videoId
            .removePrefix("tmdb:")
            .removePrefix("movie:")
            .removePrefix("series:")

        // Stremio-style series ids can look like: tt1234567:season:episode
        // Plugins/TMDB lookup need the base external id only.
        val idPart = cleanId
            .substringBefore(':')
            .substringBefore('/')
            .trim()
        
        // If it's an IMDB ID, convert it
        if (idPart.startsWith("tt")) {
            val tmdbId = imdbToTmdb(idPart, normalizeMediaType(mediaType))
            return tmdbId?.toString()
        }
        
        // If it looks like a numeric ID, assume it's already a TMDB ID
        if (idPart.all { it.isDigit() }) {
            return idPart
        }
        
        // Unknown format
        Log.w(TAG, "Unknown video ID format: $videoId")
        return null
    }
    
    /**
     * Normalize media type to consistent format
     */
    private fun normalizeMediaType(mediaType: String): String {
        return when (mediaType.lowercase()) {
            "series", "tv", "show", "tvshow" -> "tv"
            "movie", "film" -> "movie"
            else -> mediaType.lowercase()
        }
    }
    
    /**
     * Clear all caches
     */
    fun clearCache() {
        imdbToTmdbCache.clear()
        tmdbToImdbCache.clear()
        imdbToTmdbInFlight.clear()
        tmdbToImdbInFlight.clear()
        Log.d(TAG, "Cache cleared")
    }
    
    /**
     * Pre-populate cache with known mappings
     */
    /** Synchronous cache-only lookup: returns imdb ID if already cached, null otherwise. */
    fun getCachedImdbId(tmdbId: Int): String? = tmdbToImdbCache[tmdbId]

    fun getCachedTmdbId(imdbId: String): Int? = imdbToTmdbCache[imdbId]

    fun preCacheMapping(imdbId: String, tmdbId: Int) {
        imdbToTmdbCache[imdbId] = tmdbId
        tmdbToImdbCache[tmdbId] = imdbId
    }

    fun apiKey(): String = TMDB_API_KEY
    fun cachedTmdbId(contentId: String): String? {
        return null // TMDB id cache lookup stub
    }

}
