package com.nuvio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private data class ImdbTmdbEntry(
    // Accept the field names used by the existing minified Enhanced build.
    @SerializedName(value = "tmdbId", alternate = ["a"])
    val tmdbId: Int,
    @SerializedName(value = "cachedAtMs", alternate = ["b"])
    val cachedAtMs: Long
)

@Singleton
class ImdbTmdbMappingCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ImdbTmdbMappingCache"
        private const val MAX_ENTRIES = 5000
        private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
    }

    private val mutex = Mutex()
    private val cacheFile: File get() {
        val dir = File(context.filesDir, "imdb_tmdb_mapping")
        dir.mkdirs()
        return File(dir, "cache.json")
    }

    private val store = SnapshotJsonFile<ImdbTmdbEntry>(
        file = { cacheFile },
        type = object : TypeToken<Map<String, ImdbTmdbEntry>>() {}.type
    )

    suspend fun loadAll(): Map<String, Int> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val entries = store.read()
                val now = System.currentTimeMillis()
                val valid = entries.filter { (_, v) -> now - v.cachedAtMs < MAX_AGE_MS }
                Log.d(TAG, "Loaded ${valid.size} valid mappings (${entries.size - valid.size} expired)")
                valid.mapValues { it.value.tmdbId }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load mapping cache: ${e.message}")
                emptyMap()
            }
        }
    }

    suspend fun saveAll(mappings: Map<String, Int>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val now = System.currentTimeMillis()
                val existing = try {
                    store.read()
                } catch (_: Exception) { emptyMap() }

                val merged = mappings.entries
                    .map { (k, v) ->
                        val existingEntry = existing[k]
                        val ts = if (existingEntry?.tmdbId == v) existingEntry.cachedAtMs else now
                        k to ImdbTmdbEntry(tmdbId = v, cachedAtMs = ts)
                    }
                    .sortedByDescending { it.second.cachedAtMs }
                    .take(MAX_ENTRIES)
                    .toMap()

                store.write(merged)
                Log.d(TAG, "Saved ${merged.size} IMDB->TMDB mappings to disk")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save mapping cache: ${e.message}")
            }
        }
    }

}
