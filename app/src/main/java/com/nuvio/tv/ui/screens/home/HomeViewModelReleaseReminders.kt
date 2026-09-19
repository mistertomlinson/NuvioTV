package com.nuvio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.ReleaseReminderBadge
import com.nuvio.tv.data.local.ReleaseReminderRecord
import com.nuvio.tv.data.local.ReleaseReminderStatus
import com.nuvio.tv.data.local.releaseReminderKey
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val RELEASE_BADGE_LIFETIME_MS = 30L * 24 * 60 * 60 * 1000

private data class ReleaseReminderHomeSnapshot(
    val continueWatchingItems: List<ContinueWatchingItem>,
    val catalogItems: List<MetaPreview>
)

internal fun HomeViewModel.observeReleaseRemindersPipeline() {
    viewModelScope.launch {
        combine(
            releaseReminderDataStore.reminders,
            _initialCwResolved,
            _uiState
                .map { state ->
                    ReleaseReminderHomeSnapshot(
                        continueWatchingItems = state.continueWatchingItems,
                        catalogItems = state.catalogRows.flatMap { it.items }
                    )
                }
                .distinctUntilChanged()
        ) { reminders, cwResolved, snapshot ->
            Triple(reminders, cwResolved, snapshot)
        }.collect { (reminders, cwResolved, snapshot) ->
            val nowMillis = System.currentTimeMillis()
            val liveBadges = buildMap {
                reminders
                    .asSequence()
                    .filter { it.status == ReleaseReminderStatus.FULFILLED }
                    .filter { reminder ->
                        val fulfilledAt = reminder.fulfilledAtMillis ?: return@filter false
                        nowMillis - fulfilledAt < RELEASE_BADGE_LIFETIME_MS
                    }
                    .forEach { reminder ->
                        put(reminder.key, reminder.badge)
                        reminder.imdbId?.let { id ->
                            releaseReminderKey(id, reminder.itemType)?.let { put(it, reminder.badge) }
                        }
                        reminder.tmdbId?.let { id ->
                            releaseReminderKey("tmdb:$id", reminder.itemType)?.let { put(it, reminder.badge) }
                        }
                        reminder.traktId?.let { id ->
                            releaseReminderKey("trakt:$id", reminder.itemType)?.let { put(it, reminder.badge) }
                        }
                        reminder.simklId?.let { id ->
                            releaseReminderKey("simkl:$id", reminder.itemType)?.let { put(it, reminder.badge) }
                        }
                    }
            }

            _uiState.update { state ->
                if (state.releaseReminderBadges == liveBadges) state
                else state.copy(releaseReminderBadges = liveBadges)
            }

            if (!cwResolved) return@collect

            for (reminder in reminders) {
                if (reminder.status == ReleaseReminderStatus.FULFILLED) {
                    val fulfilledAt = reminder.fulfilledAtMillis
                    val expired = fulfilledAt == null ||
                        nowMillis - fulfilledAt >= RELEASE_BADGE_LIFETIME_MS
                    val playbackStarted = snapshot.continueWatchingItems.any { item ->
                        reminder.matchesContinueWatching(item)
                    }
                    if (expired || playbackStarted) {
                        releaseReminderDataStore.remove(reminder.key)
                    }
                    continue
                }

                val catalogMatch = snapshot.catalogItems.firstOrNull { item ->
                    reminder.matches(item.id, item.apiType, item.imdbId)
                }
                val catalogReleaseDate = catalogMatch
                    ?.behaviorHints
                    ?.releaseDate
                    ?.takeIf(::isExactReleaseDate)
                val catalogSeason = catalogMatch
                    ?.behaviorHints
                    ?.upcomingSeason
                val refreshedBadge = if ((catalogSeason ?: reminder.seasonNumber ?: 0) >= 2) {
                    ReleaseReminderBadge.NEW_SEASON
                } else {
                    ReleaseReminderBadge.AVAILABLE_NOW
                }

                if (
                    (catalogReleaseDate != null && catalogReleaseDate != reminder.releaseDate) ||
                    (catalogSeason != null && catalogSeason != reminder.seasonNumber) ||
                    refreshedBadge != reminder.badge
                ) {
                    releaseReminderDataStore.upsert(
                        reminder.copy(
                            releaseDate = catalogReleaseDate ?: reminder.releaseDate,
                            seasonNumber = catalogSeason ?: reminder.seasonNumber,
                            badge = refreshedBadge
                        )
                    )
                    continue
                }

                val releaseDate = parseExactReleaseDate(reminder.releaseDate) ?: continue
                if (releaseDate.isAfter(LocalDate.now())) continue

                /* Continue Watching is authoritative and must never be duplicated in My List. */
                if (snapshot.continueWatchingItems.any(reminder::matchesContinueWatching)) {
                    releaseReminderDataStore.remove(reminder.key)
                    continue
                }

                runCatching {
                    libraryRepository.ensureInDefault(reminder.toLibraryEntryInput())
                }.onSuccess {
                    releaseReminderDataStore.markFulfilled(
                        key = reminder.key,
                        fulfilledAtMillis = System.currentTimeMillis()
                    )
                }.onFailure { error ->
                    Log.w(
                        HomeViewModel.TAG,
                        "Failed to fulfill release reminder ${reminder.key}: ${error.message}"
                    )
                }
            }
        }
    }
}

private fun isExactReleaseDate(raw: String): Boolean =
    parseExactReleaseDate(raw) != null

private fun parseExactReleaseDate(raw: String?): LocalDate? {
    val value = raw?.trim()?.takeIf { it.length == 10 } ?: return null
    return try {
        LocalDate.parse(value)
    } catch (_: DateTimeParseException) {
        null
    }
}

private fun ReleaseReminderRecord.matchesContinueWatching(item: ContinueWatchingItem): Boolean =
    when (item) {
        is ContinueWatchingItem.InProgress ->
            matches(item.progress.contentId, item.progress.contentType, null)

        is ContinueWatchingItem.NextUp ->
            matches(item.info.contentId, item.info.contentType, null)
    }

private fun ReleaseReminderRecord.matches(
    candidateId: String,
    candidateType: String,
    candidateImdbId: String?
): Boolean {
    if (normalizedReminderType(itemType) != normalizedReminderType(candidateType)) return false
    return identityTokens(itemId, imdbId, tmdbId, traktId, simklId)
        .intersect(identityTokens(candidateId, candidateImdbId, null, null, null))
        .isNotEmpty()
}

private fun identityTokens(
    rawId: String,
    imdbId: String?,
    tmdbId: Int?,
    traktId: Int?,
    simklId: Long?
): Set<String> = buildSet {
    val normalizedRaw = rawId.trim().lowercase()
    if (normalizedRaw.isNotBlank()) add("raw:$normalizedRaw")
    Regex("tt\\d+", RegexOption.IGNORE_CASE)
        .find(normalizedRaw)
        ?.value
        ?.lowercase()
        ?.let { add("imdb:$it") }
    imdbId?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { add("imdb:$it") }
    tmdbId?.let { add("tmdb:$it") }
    traktId?.let { add("trakt:$it") }
    simklId?.let { add("simkl:$it") }
    if (normalizedRaw.startsWith("tmdb:")) add("tmdb:${normalizedRaw.removePrefix("tmdb:").substringBefore(':')}")
    if (normalizedRaw.startsWith("trakt:")) add("trakt:${normalizedRaw.removePrefix("trakt:").substringBefore(':')}")
    if (normalizedRaw.startsWith("simkl:")) add("simkl:${normalizedRaw.removePrefix("simkl:").substringBefore(':')}")
}

private fun normalizedReminderType(raw: String): String = when (raw.trim().lowercase()) {
    "film" -> "movie"
    "show", "tv" -> "series"
    else -> raw.trim().lowercase()
}

private fun ReleaseReminderRecord.toLibraryEntryInput(): LibraryEntryInput =
    LibraryEntryInput(
        itemId = itemId,
        itemType = itemType,
        title = title,
        year = year,
        traktId = traktId,
        simklId = simklId,
        imdbId = imdbId,
        tmdbId = tmdbId,
        poster = poster,
        posterShape = PosterShape.POSTER,
        background = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres,
        addonBaseUrl = addonBaseUrl
    )
