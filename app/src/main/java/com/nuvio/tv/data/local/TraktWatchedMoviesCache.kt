package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktWatchedMoviesCache @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "trakt_watched_movies_cache"
        private val KEY = stringSetPreferencesKey("watched_movie_ids")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _watchedIds = MutableStateFlow<Set<String>>(emptySet())
    val watchedIds: StateFlow<Set<String>> = _watchedIds.asStateFlow()

    @Volatile
    private var loaded = false

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    suspend fun loadFromDisk() {
        if (loaded) return
        val prefs = store().data.first()
        val persisted = prefs[KEY] ?: emptySet()
        if (persisted.isNotEmpty()) {
            _watchedIds.value = persisted
        }
        loaded = true
    }

    fun update(ids: Set<String>) {
        _watchedIds.value = ids
        scope.launch {
            store().edit { prefs -> prefs[KEY] = ids }
        }
    }

    fun reset() {
        _watchedIds.value = emptySet()
        loaded = false
        scope.launch {
            store().edit { prefs -> prefs[KEY] = emptySet() }
        }
    }
}
