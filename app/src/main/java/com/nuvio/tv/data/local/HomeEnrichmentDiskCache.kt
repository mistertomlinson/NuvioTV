package com.nuvio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
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
    val cachedAtMs: Long
)

@Singleton
class HomeEnrichmentDiskCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "HomeEnrichDiskCache"
        private const val MAX_ENTRIES = 3000
    }

    private val gson = Gson()
    private val mutex = Mutex()
    private val cacheFile: File get() {
        val dir = File(context.filesDir, "home_enrichment")
        dir.mkdirs()
        return File(dir, "cache.json")
    }

    suspend fun loadAll(): Map<String, TmdbEnrichment> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val file = cacheFile
                if (!file.exists()) return@withLock emptyMap()
                val type = object : TypeToken<Map<String, HomeEnrichmentEntry>>() {}.type
                val entries: Map<String, HomeEnrichmentEntry> =
                    gson.fromJson(file.readText(), type) ?: emptyMap()
                Log.d(TAG, "Loaded ${entries.size} enrichment cache entries")
                entries.mapValues { it.value.enrichment }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load home enrichment cache: ${e.message}")
                emptyMap()
            }
        }
    }

    suspend fun saveAll(cache: Map<String, TmdbEnrichment>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val now = System.currentTimeMillis()
                val file = cacheFile
                val existing: Map<String, HomeEnrichmentEntry> = if (file.exists()) {
                    try {
                        val type = object : TypeToken<Map<String, HomeEnrichmentEntry>>() {}.type
                        gson.fromJson(file.readText(), type) ?: emptyMap()
                    } catch (_: Exception) { emptyMap() }
                } else emptyMap()

                val merged = cache.entries
                    .map { (k, v) ->
                        val existingEntry = existing[k]
                        val ts = if (existingEntry?.enrichment == v) existingEntry.cachedAtMs else now
                        k to HomeEnrichmentEntry(enrichment = v, cachedAtMs = ts)
                    }
                    .sortedByDescending { it.second.cachedAtMs }
                    .take(MAX_ENTRIES)
                    .toMap()

                atomicWrite(file, gson.toJson(merged))
                Log.d(TAG, "Saved ${merged.size} home enrichment entries to disk")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save home enrichment cache: ${e.message}")
            }
        }
    }

    /*
     * Persist one repaired title without requiring callers to copy and submit
     * the complete in-memory Home cache.
     */
    suspend fun saveEntry(
        key: String,
        enrichment: TmdbEnrichment
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val now =
                    System.currentTimeMillis()

                val file =
                    cacheFile

                val type =
                    object :
                        TypeToken<
                            Map<
                                String,
                                HomeEnrichmentEntry
                            >
                        >() { }.type

                val existing:
                    Map<
                        String,
                        HomeEnrichmentEntry
                    > =
                    if (file.exists()) {
                        try {
                            gson.fromJson(
                                file.readText(),
                                type
                            ) ?: emptyMap()
                        } catch (_: Exception) {
                            emptyMap()
                        }
                    } else {
                        emptyMap()
                    }

                val updated =
                    existing.toMutableMap()

                updated[key] =
                    HomeEnrichmentEntry(
                        enrichment =
                            enrichment,
                        cachedAtMs =
                            now
                    )

                val trimmed =
                    updated.entries
                        .sortedByDescending {
                            it.value.cachedAtMs
                        }
                        .take(MAX_ENTRIES)
                        .associate {
                            it.key to it.value
                        }

                atomicWrite(
                    file,
                    gson.toJson(trimmed)
                )

                Log.d(
                    TAG,
                    "Saved focused enrichment " +
                        "repair for $key"
                )
            } catch (error: Exception) {
                Log.w(
                    TAG,
                    "Failed to save focused " +
                        "enrichment repair: " +
                        error.message
                )
            }
        }
    }

    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
