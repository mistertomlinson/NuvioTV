package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchedSeriesStateHolder @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "watched_series_cache"
        private val KEY = stringSetPreferencesKey("fully_watched_ids")
        private val REVALIDATE_KEY = stringPreferencesKey("revalidate_after")
        private val SEMANTICS_VERSION_KEY =
            androidx.datastore.preferences.core.intPreferencesKey(
                "watched_series_semantics_version"
            )
        /*
         * v3 invalidates finite badge validations written by the old
         * no-next-up shortcut before terminal status had been resolved.
         */
        private const val TERMINAL_STATUS_SEMANTICS_VERSION = 3
        private const val DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val _fullyWatchedSeriesIds = MutableStateFlow<Set<String>>(emptySet())
    val fullyWatchedSeriesIds: StateFlow<Set<String>> = _fullyWatchedSeriesIds.asStateFlow()

    @Volatile
    private var revalidateAfterMap: Map<String, Long> = emptyMap()
    private var loaded = false

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    suspend fun loadFromDisk() {
        if (loaded) return
        val profileStore = store()
        val prefs = profileStore.data.first()
        val persisted = prefs[KEY] ?: emptySet()
        val persistedValidation =
            parseTimestamps(prefs[REVALIDATE_KEY])
        val semanticsVersion =
            prefs[SEMANTICS_VERSION_KEY] ?: 1

        revalidateAfterMap =
            if (semanticsVersion < TERMINAL_STATUS_SEMANTICS_VERSION) {
                /*
                 * Preserve the visible badge set, but expire every previously
                 * validated series once so terminal-only semantics are applied.
                 * Keeping the IDs and validation keys means Home can use its
                 * gentle sequential revalidation path instead of treating the
                 * whole library as brand-new work.
                 */
                buildMap {
                    persistedValidation.forEach { (id, deadline) ->
                        /*
                         * Long.MAX_VALUE is the existing marker for a series
                         * that still has unwatched released episodes.  Its
                         * terminal status is irrelevant until watched history
                         * changes, so do not create one-time migration work for
                         * those ordinary partial shows.
                         */
                        val shouldRevalidate =
                            id in persisted ||
                                deadline != Long.MAX_VALUE
                        put(id, if (shouldRevalidate) 0L else deadline)
                    }
                    persisted.forEach { id ->
                        if (id !in this) {
                            put(id, 0L)
                        }
                    }
                }
            } else {
                persistedValidation
            }

        if (_fullyWatchedSeriesIds.value.isEmpty() && persisted.isNotEmpty()) {
            _fullyWatchedSeriesIds.value = persisted
        }

        if (semanticsVersion < TERMINAL_STATUS_SEMANTICS_VERSION) {
            profileStore.edit { mutablePrefs ->
                mutablePrefs[SEMANTICS_VERSION_KEY] =
                    TERMINAL_STATUS_SEMANTICS_VERSION
                mutablePrefs[REVALIDATE_KEY] =
                    gson.toJson(revalidateAfterMap)
            }
        }
        loaded = true
    }

    fun update(ids: Set<String>) {
        _fullyWatchedSeriesIds.value = ids
        scope.launch {
            store().edit { prefs -> prefs[KEY] = ids }
        }
    }

    @Synchronized
    fun updateWithValidation(
        ids: Set<String>,
        validatedIds: Set<String>,
        revalidateAt: Map<String, Long> = emptyMap()
    ) {
        val idsChanged = _fullyWatchedSeriesIds.value != ids
        _fullyWatchedSeriesIds.value = ids
        val now = System.currentTimeMillis()
        val defaultDeadline = now + DEFAULT_TTL_MS
        val updated = revalidateAfterMap.toMutableMap()
        var deadlinesChanged = false
        validatedIds.forEach { id ->
            val newDeadline = revalidateAt[id] ?: defaultDeadline
            if (updated[id] != newDeadline) {
                updated[id] = newDeadline
                deadlinesChanged = true
            }
        }
        if (idsChanged) {
            val keysToRetain = ids + validatedIds
            val sizeBefore = updated.size
            updated.keys.retainAll(keysToRetain)
            if (updated.size != sizeBefore) deadlinesChanged = true
        }
        revalidateAfterMap = updated
        if (idsChanged || deadlinesChanged) {
            scope.launch {
                store().edit { prefs ->
                    prefs[KEY] = ids
                    prefs[REVALIDATE_KEY] = gson.toJson(updated)
                }
            }
        }
    }

    @Synchronized
    fun invalidate(ids: Set<String>) {
        if (ids.isEmpty()) return

        val currentIds = _fullyWatchedSeriesIds.value
        val updatedIds = currentIds - ids
        val updatedValidation =
            revalidateAfterMap.filterKeys { key -> key !in ids }

        val idsChanged = updatedIds != currentIds
        val validationChanged =
            updatedValidation.size != revalidateAfterMap.size

        if (!idsChanged && !validationChanged) return

        _fullyWatchedSeriesIds.value = updatedIds
        revalidateAfterMap = updatedValidation

        scope.launch {
            store().edit { prefs ->
                prefs[KEY] = updatedIds
                prefs[REVALIDATE_KEY] =
                    gson.toJson(updatedValidation)
            }
        }
    }

    fun isSeriesValidationFresh(contentId: String): Boolean {
        val deadline = revalidateAfterMap[contentId] ?: return false
        return System.currentTimeMillis() < deadline
    }

    fun hasBeenValidated(contentId: String): Boolean = contentId in revalidateAfterMap

    fun filterStaleIds(ids: Set<String>): Set<String> {
        val now = System.currentTimeMillis()
        return ids.filter { id ->
            val deadline = revalidateAfterMap[id] ?: return@filter true
            now >= deadline
        }.toSet()
    }

    private fun parseTimestamps(json: String?): Map<String, Long> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val type = object : TypeToken<Map<String, Long>>() {}.type
            gson.fromJson<Map<String, Long>>(json, type) ?: emptyMap()
        }.getOrDefault(emptyMap())
    }
}
