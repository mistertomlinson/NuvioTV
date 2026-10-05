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
         * v4 invalidates finite validation deadlines once so existing installs
         * immediately adopt the restored seven-day new-season window and
         * next-episode revalidation schedule.
         */
        private const val TERMINAL_STATUS_SEMANTICS_VERSION = 4
        private const val DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val _fullyWatchedSeriesIds = MutableStateFlow<Set<String>>(emptySet())
    val fullyWatchedSeriesIds: StateFlow<Set<String>> = _fullyWatchedSeriesIds.asStateFlow()

    @Volatile
    private var revalidateAfterMap: Map<String, Long> = emptyMap()

    @Volatile
    private var loadedForProfileId: Int? = null

    private fun store(
        profileId: Int = profileManager.activeProfileId.value
    ) = factory.get(profileId, FEATURE)

    suspend fun loadFromDisk(
        profileId: Int = profileManager.activeProfileId.value
    ) {
        if (loadedForProfileId == profileId) return

        val profileStore = store(profileId)
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
        loadedForProfileId = profileId
    }

    fun update(ids: Set<String>) {
        _fullyWatchedSeriesIds.value = ids
        val profileId = profileManager.activeProfileId.value
        scope.launch {
            store(profileId).edit { prefs ->
                prefs[KEY] = ids
            }
        }
    }

    /**
     * Clear only process-local state. The selected profile's persisted badge
     * and validation state is reloaded separately.
     */
    fun clearInMemory() {
        _fullyWatchedSeriesIds.value = emptySet()
        revalidateAfterMap = emptyMap()
    }

    @Synchronized
    fun updateWithValidation(
        ids: Set<String>,
        validatedIds: Set<String>,
        revalidateAt: Map<String, Long> = emptyMap(),
        profileId: Int = profileManager.activeProfileId.value
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
                store(profileId).edit { prefs ->
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

        val profileId = profileManager.activeProfileId.value
        scope.launch {
            store(profileId).edit { prefs ->
                prefs[KEY] = updatedIds
                prefs[REVALIDATE_KEY] =
                    gson.toJson(updatedValidation)
            }
        }
    }

    /**
     * Clear only validation deadlines, forcing the next CW cycle to re-check
     * series metadata. Used by the explicit Continue Watching cache clear.
     */
    fun clearValidationState(
        profileId: Int = profileManager.activeProfileId.value
    ) {
        revalidateAfterMap = emptyMap()

        scope.launch {
            store(profileId).edit { prefs ->
                prefs.remove(REVALIDATE_KEY)
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
