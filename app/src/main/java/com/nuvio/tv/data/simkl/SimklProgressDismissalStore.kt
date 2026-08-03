package com.nuvio.tv.data.simkl

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.domain.model.WatchProgress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

@Singleton
class SimklProgressDismissalStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "simkl_progress_dismissals"
        private const val MAX_STORED_ENTRIES = 300
    }

    private val gson = Gson()
    private val dismissedAtKey = stringPreferencesKey("dismissed_at_map")
    private val dismissedAtMapType =
        object : TypeToken<Map<String, Long>>() {}.type

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    val dismissedAtByKey: Flow<Map<String, Long>> =
        profileManager.activeProfileId.flatMapLatest { profileId ->
            factory.get(profileId, FEATURE).data.map { preferences ->
                parseDismissedAtMap(preferences[dismissedAtKey] ?: "{}")
            }
        }

    suspend fun dismiss(
        contentId: String,
        season: Int?,
        episode: Int?,
        dismissedAtEpochMs: Long = System.currentTimeMillis()
    ) {
        if (contentId.isBlank()) return
        val profileId = profileManager.activeProfileId.value
        val key = simklProgressDismissalKey(contentId, season, episode)

        store(profileId).edit { preferences ->
            val current = parseDismissedAtMap(
                preferences[dismissedAtKey] ?: "{}"
            ).toMutableMap()

            current[key] = dismissedAtEpochMs
            preferences[dismissedAtKey] = gson.toJson(prune(current))
        }
    }

    suspend fun clearForNewerProgress(progress: WatchProgress) {
        if (progress.contentId.isBlank()) return
        val profileId = profileManager.activeProfileId.value
        val candidateKeys = simklProgressDismissalKeys(progress)

        store(profileId).edit { preferences ->
            val current = parseDismissedAtMap(
                preferences[dismissedAtKey] ?: "{}"
            )
            val updated = clearSimklDismissalsForNewerProgress(
                dismissedAtByKey = current,
                progress = progress
            )

            if (updated == current) return@edit

            if (updated.isEmpty()) {
                preferences.remove(dismissedAtKey)
            } else {
                preferences[dismissedAtKey] = gson.toJson(updated)
            }
        }
    }

    private fun parseDismissedAtMap(json: String): Map<String, Long> =
        runCatching {
            gson.fromJson<Map<String, Long>>(json, dismissedAtMapType)
        }.getOrNull().orEmpty()

    private fun prune(entries: Map<String, Long>): Map<String, Long> =
        entries.entries
            .sortedByDescending(Map.Entry<String, Long>::value)
            .take(MAX_STORED_ENTRIES)
            .associateTo(linkedMapOf()) { entry -> entry.toPair() }
}

internal fun simklProgressDismissalKey(
    contentId: String,
    season: Int?,
    episode: Int?
): String {
    val normalizedContentId = contentId.trim().lowercase()
    return if (season != null && episode != null) {
        "$normalizedContentId|s${season}e${episode}"
    } else {
        "$normalizedContentId|all"
    }
}

internal fun simklProgressDismissalKeys(
    progress: WatchProgress
): List<String> = buildList {
    add(
        simklProgressDismissalKey(
            contentId = progress.contentId,
            season = null,
            episode = null
        )
    )
    if (progress.season != null && progress.episode != null) {
        add(
            simklProgressDismissalKey(
                contentId = progress.contentId,
                season = progress.season,
                episode = progress.episode
            )
        )
    }
}

internal fun clearSimklDismissalsForNewerProgress(
    dismissedAtByKey: Map<String, Long>,
    progress: WatchProgress
): Map<String, Long> {
    val updated = dismissedAtByKey.toMutableMap()

    simklProgressDismissalKeys(progress).forEach { key ->
        val dismissedAt = updated[key] ?: return@forEach
        if (progress.lastWatched > dismissedAt) {
            updated.remove(key)
        }
    }

    return updated
}

internal fun isSimklProgressDismissed(
    progress: WatchProgress,
    dismissedAtByKey: Map<String, Long>
): Boolean {
    val newestMatchingDismissal = simklProgressDismissalKeys(progress)
        .mapNotNull(dismissedAtByKey::get)
        .maxOrNull()
        ?: return false

    return progress.lastWatched <= newestMatchingDismissal
}

internal fun filterSimklDismissedProgress(
    entries: List<WatchProgress>,
    dismissedAtByKey: Map<String, Long>
): List<WatchProgress> = entries.filterNot { progress ->
    isSimklProgressDismissed(
        progress = progress,
        dismissedAtByKey = dismissedAtByKey
    )
}
