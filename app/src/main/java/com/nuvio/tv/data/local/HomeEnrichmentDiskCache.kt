package com.nuvio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.tmdb.TmdbEnrichment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private data class HomeEnrichmentEntry(
    val enrichment: TmdbEnrichment,
    val cachedAtMs: Long,
    val externalMetaSettled: Boolean = false,
    val externalImdbRating: Float? = null
)

data class HomeExternalMetaState(
    val settled: Boolean,
    val imdbRating: Float?
)

@Singleton
class HomeEnrichmentDiskCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "HomeEnrichDiskCache"
        private const val MAX_ENTRIES = 3000
    }

    private val mutex = Mutex()
    private val store = SnapshotJsonFile<HomeEnrichmentEntry>(
        file = {
            val dir = File(context.filesDir, "home_enrichment")
            dir.mkdirs()
            // Retain the existing format and minification-safe cache version.
            File(dir, "cache_v2.json")
        },
        type = object : TypeToken<Map<String, HomeEnrichmentEntry>>() {}.type
    )

    suspend fun loadAll(): Map<String, TmdbEnrichment> = withContext(Dispatchers.IO) {
        mutex.withLock {
            readEntries().mapValues { it.value.enrichment }
        }
    }

    suspend fun loadExternalMetaStates(): Map<String, HomeExternalMetaState> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                // Reuse the same parsed snapshot as loadAll, including null ratings.
                readEntries().mapValues { (_, entry) ->
                    HomeExternalMetaState(entry.externalMetaSettled, entry.externalImdbRating)
                }
            }
        }

    suspend fun saveAll(
        cache: Map<String, TmdbEnrichment>,
        externalMetaStates: Map<String, HomeExternalMetaState> = emptyMap()
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val now = System.currentTimeMillis()
                val existing = readEntries()
                val merged = cache.map { (key, enrichment) ->
                    val previous = existing[key]
                    val incoming = externalMetaStates[key]
                    val settled = incoming?.settled ?: previous?.externalMetaSettled ?: false
                    val rating = if (incoming?.settled == true) {
                        incoming.imdbRating
                    } else {
                        previous?.externalImdbRating
                    }
                    val unchanged = previous?.enrichment == enrichment &&
                        previous.externalMetaSettled == settled &&
                        previous.externalImdbRating == rating
                    key to HomeEnrichmentEntry(
                        enrichment = enrichment,
                        cachedAtMs = if (unchanged) previous.cachedAtMs else now,
                        externalMetaSettled = settled,
                        externalImdbRating = rating
                    )
                }.sortedByDescending { it.second.cachedAtMs }
                    .take(MAX_ENTRIES)
                    .toMap()
                store.write(merged)
            } catch (error: Exception) {
                Log.w(TAG, "Failed to save home enrichment cache: ${error.message}")
            }
        }
    }

    /** Persist a focused repair without losing the other titles or rating state. */
    suspend fun saveEntry(key: String, enrichment: TmdbEnrichment) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val existing = readEntries()
                val previous = existing[key]
                if (previous?.enrichment == enrichment) return@withLock
                val updated = existing.toMutableMap()
                updated[key] = HomeEnrichmentEntry(
                    enrichment = enrichment,
                    cachedAtMs = System.currentTimeMillis(),
                    externalMetaSettled = previous?.externalMetaSettled ?: false,
                    externalImdbRating = previous?.externalImdbRating
                )
                store.write(updated.entries.sortedByDescending { it.value.cachedAtMs }
                    .take(MAX_ENTRIES).associate { it.key to it.value })
            } catch (error: Exception) {
                Log.w(TAG, "Failed to save focused enrichment repair: ${error.message}")
            }
        }
    }

    private fun readEntries(): Map<String, HomeEnrichmentEntry> = try {
        store.read()
    } catch (error: Exception) {
        Log.w(TAG, "Failed to load home enrichment cache: ${error.message}")
        emptyMap()
    }
}
