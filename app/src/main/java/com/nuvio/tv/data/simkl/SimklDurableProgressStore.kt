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
class SimklDurableProgressStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "simkl_durable_progress"
        private const val MAX_STORED_ENTRIES = 300
    }

    private val gson = Gson()
    private val progressKey = stringPreferencesKey("progress_map")
    private val progressMapType =
        object : TypeToken<Map<String, WatchProgress>>() {}.type

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val allRawProgress: Flow<List<WatchProgress>> =
        profileManager.activeProfileId.flatMapLatest { profileId ->
            factory.get(profileId, FEATURE).data.map { preferences ->
                parseProgressMap(preferences[progressKey] ?: "{}")
                    .values
                    .filter(::shouldPersistSimklDurableProgress)
                    .sortedByDescending(WatchProgress::lastWatched)
            }
        }

    val allProgress: Flow<List<WatchProgress>> = allRawProgress.map { entries ->
        entries
            .groupBy { progress -> progress.contentId.lowercase() }
            .mapNotNull { (_, candidates) ->
                candidates.maxByOrNull(WatchProgress::lastWatched)
            }
            .sortedByDescending(WatchProgress::lastWatched)
    }

    fun episodeProgress(
        contentId: String
    ): Flow<Map<Pair<Int, Int>, WatchProgress>> = allRawProgress.map { entries ->
        entries.mapNotNull { progress ->
            val season = progress.season ?: return@mapNotNull null
            val episode = progress.episode ?: return@mapNotNull null
            if (!progress.contentId.equals(contentId, ignoreCase = true)) {
                return@mapNotNull null
            }
            (season to episode) to progress
        }.toMap()
    }

    suspend fun persist(progress: WatchProgress) {
        if (progress.isCompleted()) {
            removeProgress(
                contentId = progress.contentId,
                season = progress.season,
                episode = progress.episode
            )
            return
        }

        // Do not create durable entries for accidental starts below the
        // application's existing 2% Continue Watching threshold.
        if (!shouldPersistSimklDurableProgress(progress)) return

        val durable = progress.copy(
            source = WatchProgress.SOURCE_SIMKL_DURABLE,
            simklPlaybackId = null
        )
        val profileId = profileManager.activeProfileId.value

        store(profileId).edit { preferences ->
            val current = parseProgressMap(
                preferences[progressKey] ?: "{}"
            ).toMutableMap()

            current[createKey(durable)] = durable
            preferences[progressKey] = gson.toJson(prune(current))
        }
    }

    suspend fun removeProgress(
        contentId: String,
        season: Int?,
        episode: Int?
    ) {
        val profileId = profileManager.activeProfileId.value

        store(profileId).edit { preferences ->
            val current = parseProgressMap(
                preferences[progressKey] ?: "{}"
            ).toMutableMap()

            val keysToRemove = current.entries
                .filter { (_, progress) ->
                    val sameContent =
                        progress.contentId.equals(contentId, ignoreCase = true)
                    val sameEpisode =
                        season == null ||
                            episode == null ||
                            (
                                progress.season == season &&
                                    progress.episode == episode
                                )

                    sameContent && sameEpisode
                }
                .map(Map.Entry<String, WatchProgress>::key)

            keysToRemove.forEach(current::remove)

            if (current.isEmpty()) {
                preferences.remove(progressKey)
            } else {
                preferences[progressKey] = gson.toJson(current)
            }
        }
    }

    private fun parseProgressMap(json: String): Map<String, WatchProgress> =
        runCatching {
            gson.fromJson<Map<String, WatchProgress>>(json, progressMapType)
        }.getOrNull().orEmpty()

    private fun createKey(progress: WatchProgress): String =
        if (progress.season != null && progress.episode != null) {
            "${progress.contentId}_s${progress.season}e${progress.episode}"
        } else {
            progress.contentId
        }

    private fun prune(
        entries: Map<String, WatchProgress>
    ): Map<String, WatchProgress> =
        entries.entries
            .sortedByDescending { (_, progress) -> progress.lastWatched }
            .take(MAX_STORED_ENTRIES)
            .associateTo(linkedMapOf()) { entry -> entry.toPair() }
}

internal fun shouldPersistSimklDurableProgress(
    progress: WatchProgress
): Boolean = progress.isInProgress()

internal fun mergeSimklProgressWithDurable(
    remoteEntries: List<WatchProgress>,
    durableEntries: List<WatchProgress>,
    isWatched: (WatchProgress) -> Boolean
): List<WatchProgress> {
    val remoteContentIds = remoteEntries
        .mapTo(mutableSetOf()) { progress -> progress.contentId.lowercase() }

    return buildList {
        // Any active Simkl playback record for a title is authoritative.
        // Suppress older durable episodes of that same title so Continue
        // Watching never receives duplicate rows for one series.
        addAll(remoteEntries)

        durableEntries.forEach { progress ->
            if (
                shouldPersistSimklDurableProgress(progress) &&
                !isWatched(progress) &&
                progress.contentId.lowercase() !in remoteContentIds
            ) {
                add(progress)
            }
        }
    }.sortedByDescending(WatchProgress::lastWatched)
}

internal fun mergeSimklEpisodeProgressWithDurable(
    remoteEntries: Map<Pair<Int, Int>, WatchProgress>,
    durableEntries: Map<Pair<Int, Int>, WatchProgress>,
    isWatched: (WatchProgress) -> Boolean
): Map<Pair<Int, Int>, WatchProgress> {
    val merged = linkedMapOf<Pair<Int, Int>, WatchProgress>()

    durableEntries.forEach { (key, progress) ->
        if (
            shouldPersistSimklDurableProgress(progress) &&
            !isWatched(progress)
        ) {
            merged[key] = progress
        }
    }

    // Remote history/playback wins for matching episodes.
    merged.putAll(remoteEntries)
    return merged
}
