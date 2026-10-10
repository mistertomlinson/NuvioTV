package com.nuvio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.ReleaseReminderBadge
import com.nuvio.tv.data.local.ReleaseReminderRecord
import com.nuvio.tv.data.local.ReleaseReminderStatus
import com.nuvio.tv.data.local.releaseReminderKey
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val RELEASE_BADGE_LIFETIME_MS = 30L * 24 * 60 * 60 * 1000
private const val RELEASE_REMINDER_RECHECK_INTERVAL_MS = 60_000L
private const val RELEASE_FALLBACK_HOUR_LOCAL = 4

private data class ReleaseReminderHomeSnapshot(
    val continueWatchingItems: List<ContinueWatchingItem>,
    val catalogRows: List<CatalogRow>
) {
    val catalogItems: List<MetaPreview>
        get() = catalogRows.flatMap { it.items }
}

private data class ReleaseReminderPipelineInput(
    val reminders: List<ReleaseReminderRecord>,
    val cwResolved: Boolean,
    val snapshot: ReleaseReminderHomeSnapshot,
    val nowMillis: Long
)

private fun releaseReminderClock() = flow {
    while (true) {
        emit(System.currentTimeMillis())
        delay(RELEASE_REMINDER_RECHECK_INTERVAL_MS)
    }
}

internal fun releaseReminderIdentityKeys(
    item: MetaPreview
): Set<String> =
    buildSet {
        releaseReminderKey(
            item.id,
            item.apiType
        )?.let(::add)

        item.imdbId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { imdbId ->
                releaseReminderKey(
                    imdbId,
                    item.apiType
                )?.let(::add)
            }
    }

private fun releaseReminderIdentityKeys(
    record: ReleaseReminderRecord
): Set<String> =
    buildSet {
        add(record.key)

        record.imdbId?.let { id ->
            releaseReminderKey(
                id,
                record.itemType
            )?.let(::add)
        }

        record.tmdbId?.let { id ->
            releaseReminderKey(
                "tmdb:$id",
                record.itemType
            )?.let(::add)
        }

        record.traktId?.let { id ->
            releaseReminderKey(
                "trakt:$id",
                record.itemType
            )?.let(::add)
        }

        record.simklId?.let { id ->
            releaseReminderKey(
                "simkl:$id",
                record.itemType
            )?.let(::add)
        }
    }

private fun MetaPreview.toHomeReleaseReminderRecord(
    addonBaseUrl: String
): ReleaseReminderRecord {
    val normalizedId = id.trim()

    fun prefixedLong(prefix: String): Long? =
        normalizedId
            .takeIf {
                it.startsWith(
                    "$prefix:",
                    ignoreCase = true
                )
            }
            ?.substringAfter(':')
            ?.substringBefore(':')
            ?.toLongOrNull()

    val seasonNumber =
        behaviorHints?.upcomingSeason

    return ReleaseReminderRecord(
        itemId = id,
        itemType = apiType,
        title = name,
        year =
            behaviorHints
                ?.releaseYear
                ?.trim()
                ?.toIntOrNull(),
        traktId =
            prefixedLong("trakt")
                ?.toInt(),
        simklId =
            prefixedLong("simkl"),
        imdbId =
            imdbId
                ?.trim()
                ?.takeIf { it.isNotBlank() },
        tmdbId =
            prefixedLong("tmdb")
                ?.toInt(),
        poster = poster,
        background = backdropUrl,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres,
        addonBaseUrl =
            addonBaseUrl
                .trim()
                .takeIf { it.isNotBlank() },
        releaseDate =
            behaviorHints?.releaseDate,
        seasonNumber = seasonNumber,
        platformId =
            behaviorHints?.platformId,
        badge =
            if ((seasonNumber ?: 0) >= 2) {
                ReleaseReminderBadge.NEW_SEASON
            } else {
                ReleaseReminderBadge.AVAILABLE_NOW
            }
    )
}

internal fun HomeViewModel.togglePosterReleaseReminder(
    item: MetaPreview,
    addonBaseUrl: String
) {
    if (
        item.behaviorHints?.comingSoon != true
    ) {
        return
    }

    val identityKeys =
        releaseReminderIdentityKeys(item)

    if (identityKeys.isEmpty()) {
        return
    }

    val previousMembership =
        identityKeys.associateWith { key ->
            key in
                _uiState.value
                    .armedReleaseReminderKeys
        }

    val reminderWasSet =
        previousMembership.values.any { it }

    val enableReminder =
        !reminderWasSet

    /*
     * Optimistic Home update.
     *
     * This is deliberately synchronous with the popup action so
     * the poster bell changes before the DataStore write completes.
     * The existing reminder pipeline will subsequently confirm the
     * same state from durable storage.
     */
    _uiState.update { state ->
        val updated =
            state
                .armedReleaseReminderKeys
                .toMutableSet()

        if (enableReminder) {
            updated.addAll(identityKeys)
        } else {
            updated.removeAll(identityKeys)
        }

        state.copy(
            armedReleaseReminderKeys =
                updated
        )
    }

    viewModelScope.launch {
        runCatching {
            if (enableReminder) {
                releaseReminderDataStore
                    .setReminder(
                        item.toHomeReleaseReminderRecord(
                            addonBaseUrl
                        ),
                        enabled = true
                    )
            } else {
                /*
                 * Details and Home can reach the same title through
                 * different provider IDs. Find the actual ARMED
                 * record through all persisted aliases before
                 * removing it.
                 */
                val matchingRecords =
                    releaseReminderDataStore
                        .reminders
                        .first()
                        .filter { record ->
                            record.status ==
                                ReleaseReminderStatus.ARMED &&
                                releaseReminderIdentityKeys(
                                    record
                                ).any(
                                    identityKeys::contains
                                )
                        }

                if (matchingRecords.isNotEmpty()) {
                    matchingRecords.forEach { record ->
                        releaseReminderDataStore
                            .remove(record.key)
                    }
                } else {
                    /*
                     * Handles old boolean-only reminder records.
                     */
                    identityKeys.forEach { key ->
                        releaseReminderDataStore
                            .remove(key)
                    }
                }
            }
        }.onFailure { error ->
            /*
             * Roll back only this title's identities. Do not
             * disturb reminder changes for any other poster.
             */
            _uiState.update { state ->
                val restored =
                    state
                        .armedReleaseReminderKeys
                        .toMutableSet()

                previousMembership.forEach {
                        (key, wasSet) ->
                    if (wasSet) {
                        restored.add(key)
                    } else {
                        restored.remove(key)
                    }
                }

                state.copy(
                    armedReleaseReminderKeys =
                        restored
                )
            }

            Log.w(
                HomeViewModel.TAG,
                "Failed to toggle poster release reminder " +
                    "${item.id}: ${error.message}"
            )
        }
    }
}

internal fun HomeViewModel.observeReleaseRemindersPipeline() {
    viewModelScope.launch {
        combine(
            releaseReminderDataStore.reminders,
            _initialCwResolved,
            _uiState
                .map { state ->
                    ReleaseReminderHomeSnapshot(
                        continueWatchingItems = state.continueWatchingItems,
                        catalogRows = state.catalogRows
                    )
                }
                .distinctUntilChanged(),
            releaseReminderClock()
        ) { reminders, cwResolved, snapshot, nowMillis ->
            ReleaseReminderPipelineInput(
                reminders = reminders,
                cwResolved = cwResolved,
                snapshot = snapshot,
                nowMillis = nowMillis
            )
        }.collect { input ->
            val reminders = input.reminders
            val cwResolved = input.cwResolved
            val snapshot = input.snapshot
            val nowMillis = input.nowMillis


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

            val armedReminderKeys = buildSet {
                reminders
                    .asSequence()
                    .filter {
                        it.status ==
                            ReleaseReminderStatus.ARMED
                    }
                    .forEach { reminder ->
                        add(reminder.key)

                        reminder.imdbId?.let { id ->
                            releaseReminderKey(
                                id,
                                reminder.itemType
                            )?.let(::add)
                        }

                        reminder.tmdbId?.let { id ->
                            releaseReminderKey(
                                "tmdb:$id",
                                reminder.itemType
                            )?.let(::add)
                        }

                        reminder.traktId?.let { id ->
                            releaseReminderKey(
                                "trakt:$id",
                                reminder.itemType
                            )?.let(::add)
                        }

                        reminder.simklId?.let { id ->
                            releaseReminderKey(
                                "simkl:$id",
                                reminder.itemType
                            )?.let(::add)
                        }
                    }
            }

            _uiState.update { state ->
                if (
                    state.releaseReminderBadges == liveBadges &&
                    state.armedReleaseReminderKeys ==
                        armedReminderKeys
                ) {
                    state
                } else {
                    state.copy(
                        releaseReminderBadges = liveBadges,
                        armedReleaseReminderKeys =
                            armedReminderKeys
                    )
                }
            }

            if (!cwResolved) {
                return@collect
            }

            for (reminder in reminders) {

                /*
                 * Resolve a durable cross-provider identity before fulfillment.
                 *
                 * Release catalogs may identify a title by TMDB while Simkl/Trakt
                 * My List renders the same title by IMDb. Persist the IMDb alias
                 * once so badge matching stays provider-neutral and requires no
                 * lookup or extra work from the Compose card path.
                 */
                if (reminder.imdbId.isNullOrBlank() && reminder.tmdbId != null) {
                    val resolvedImdbId = runCatching {
                        tmdbService.tmdbToImdb(
                            reminder.tmdbId,
                            reminder.itemType
                        )
                    }.onFailure { error ->
                        Log.w(
                            HomeViewModel.TAG,
                            "Failed to resolve release reminder IMDb alias " +
                                "for ${reminder.key}: ${error.message}"
                        )
                    }.getOrNull()

                    if (!resolvedImdbId.isNullOrBlank()) {
                        releaseReminderDataStore.upsert(
                            reminder.copy(imdbId = resolvedImdbId)
                        )
                        continue
                    }
                }

                if (reminder.status == ReleaseReminderStatus.FULFILLED) {

                    val fulfilledAt = reminder.fulfilledAtMillis
                    val expired = fulfilledAt == null ||
                        nowMillis - fulfilledAt >= RELEASE_BADGE_LIFETIME_MS
                    val playbackStarted = snapshot.continueWatchingItems.any { item ->
                        reminder.matchesContinueWatching(item)
                    }
                    if (expired || playbackStarted) {
                        releaseReminderDataStore.remove(reminder.key)
                        continue
                    }

                    /*
                     * Records fulfilled by the original implementation were
                     * marked complete merely because no exception was thrown.
                     * Re-arm those once so they go through verified insertion.
                     */
                    if (!reminder.membershipConfirmed) {
                        releaseReminderDataStore.markArmed(reminder.key)
                    }
                    continue
                }

                val catalogMatch = snapshot.catalogItems.firstOrNull { item ->
                    reminder.matches(item.id, item.apiType, item.imdbId)
                }

                if (reminder.title.isBlank() && catalogMatch != null) {
                    val inferredTmdbId =
                        reminder.tmdbId
                            ?: reminder.itemId
                                .takeIf { it.startsWith("tmdb:", ignoreCase = true) }
                                ?.substringAfter(':')
                                ?.substringBefore(':')
                                ?.toIntOrNull()

                    releaseReminderDataStore.upsert(
                        reminder.copy(
                            title = catalogMatch.name,
                            year = Regex("(\\d{4})")
                                .find(catalogMatch.releaseInfo.orEmpty())
                                ?.groupValues
                                ?.getOrNull(1)
                                ?.toIntOrNull(),
                            imdbId = reminder.imdbId ?: catalogMatch.imdbId,
                            tmdbId = inferredTmdbId,
                            poster = catalogMatch.poster,
                            background = catalogMatch.background,
                            logo = catalogMatch.logo,
                            description = catalogMatch.description,
                            releaseInfo = catalogMatch.releaseInfo,
                            imdbRating = catalogMatch.imdbRating,
                            genres = catalogMatch.genres,
                            releaseDate =
                                catalogMatch.behaviorHints?.releaseDate
                                    ?: reminder.releaseDate,
                            seasonNumber =
                                catalogMatch.behaviorHints?.upcomingSeason
                                    ?: reminder.seasonNumber,
                            platformId =
                                catalogMatch.behaviorHints?.platformId
                                    ?.takeIf { it.isNotBlank() }
                                    ?: reminder.platformId
                        )
                    )

                    continue
                }

                val catalogReleaseDate = catalogMatch
                    ?.behaviorHints
                    ?.releaseDate
                    ?.takeIf(::isExactReleaseDate)
                val catalogSeason = catalogMatch
                    ?.behaviorHints
                    ?.upcomingSeason
                val catalogPlatformId = catalogMatch
                    ?.behaviorHints
                    ?.platformId
                    ?.takeIf { it.isNotBlank() }
                val refreshedBadge = if ((catalogSeason ?: reminder.seasonNumber ?: 0) >= 2) {
                    ReleaseReminderBadge.NEW_SEASON
                } else {
                    ReleaseReminderBadge.AVAILABLE_NOW
                }

                if (
                    (catalogReleaseDate != null && catalogReleaseDate != reminder.releaseDate) ||
                    (catalogSeason != null && catalogSeason != reminder.seasonNumber) ||
                    (
                        catalogPlatformId != null &&
                            !catalogPlatformId.equals(
                                reminder.platformId,
                                ignoreCase = true
                            )
                    ) ||
                    refreshedBadge != reminder.badge
                ) {

                    releaseReminderDataStore.upsert(
                        reminder.copy(
                            releaseDate = catalogReleaseDate ?: reminder.releaseDate,
                            seasonNumber = catalogSeason ?: reminder.seasonNumber,
                            platformId = catalogPlatformId ?: reminder.platformId,
                            badge = refreshedBadge
                        )
                    )
                    continue
                }

                /*
                 * Older reminder records predate persisted platformId.
                 *
                 * Once such a reminder reaches its exact release date,
                 * ask its originating addon for the authoritative release
                 * source. This is background reminder work only; it does
                 * not participate in Compose or scroll processing.
                 */
                if (reminder.platformId.isNullOrBlank()) {
                    val migrationReleaseDate =
                        parseExactReleaseDate(reminder.releaseDate)

                    if (
                        migrationReleaseDate != null &&
                        !migrationReleaseDate.isAfter(
                            localReleaseDateAt(nowMillis)
                        )
                    ) {
                        val resolvedPlatformId =
                            resolveLegacyReminderPlatformId(
                                reminder
                            )

                        if (!resolvedPlatformId.isNullOrBlank()) {
                            Log.i(
                                HomeViewModel.TAG,
                                "Migrated release reminder source " +
                                    "${reminder.key} -> $resolvedPlatformId"
                            )

                            releaseReminderDataStore.upsert(
                                reminder.copy(
                                    platformId =
                                        resolvedPlatformId
                                )
                            )

                            continue
                        }
                    }
                }

                val releaseDate =
                    parseExactReleaseDate(reminder.releaseDate)

                val globalReleaseDateConfirmed =
                    reminder.platformId.equals(
                        "global",
                        ignoreCase = true
                    ) &&
                        releaseDate != null &&
                        !releaseDate.isAfter(
                            localReleaseDateAt(nowMillis)
                        )

                val availabilityConfirmed =
                    globalReleaseDateConfirmed ||
                        snapshot.hasAvailabilityConfirmation(reminder)

                if (!availabilityConfirmed) {
                    if (releaseDate == null) {
                        continue
                    }

                    if (!releaseFallbackWindowReached(releaseDate, nowMillis)) {
                        continue
                    }
                }

                /* Continue Watching is authoritative and must never be duplicated in My List. */
                if (snapshot.continueWatchingItems.any(reminder::matchesContinueWatching)) {
                    releaseReminderDataStore.remove(reminder.key)
                    continue
                }

                /*
                 * Automatic release promotion must never mutate My List while
                 * that row is in or immediately adjacent to the visible Home
                 * viewport. Reuse Modern Home's existing advisory priority list:
                 * no new snapshotFlow, layout observer, or scroll-path state.
                 *
                 * The reminder remains ARMED and the normal background pipeline
                 * retries later, after My List has moved outside that window.
                 */
                if (
                    HomeViewModel.MY_LIST_CATALOG_KEY in
                    modernHomePriorityRowKeys
                ) {
                    continue
                }


                runCatching {
                    libraryRepository.ensureInDefault(reminder.toLibraryEntryInput())
                }.onSuccess { membershipConfirmed ->
                    if (membershipConfirmed) {
                        Log.i(
                            HomeViewModel.TAG,
                            "Release reminder fulfilled: ${reminder.key}"
                        )

                        releaseReminderDataStore.markFulfilled(
                            key = reminder.key,
                            fulfilledAtMillis = System.currentTimeMillis()
                        )
                    } else {
                        Log.w(
                            HomeViewModel.TAG,
                            "Release reminder membership was not confirmed for ${reminder.key}"
                        )
                    }
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

private suspend fun HomeViewModel.resolveLegacyReminderPlatformId(
    reminder: ReleaseReminderRecord
): String? {
    val addonBaseUrl = reminder.addonBaseUrl
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return null

    return try {
        when (
            val result = metaRepository.getMeta(
                addonBaseUrl = addonBaseUrl,
                type = reminder.itemType,
                id = reminder.itemId
            ).first {
                it !is NetworkResult.Loading
            }
        ) {
            is NetworkResult.Success ->
                result.data
                    .behaviorHints
                    ?.platformId
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

            else ->
                null
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Log.w(
            HomeViewModel.TAG,
            "Failed to resolve legacy release reminder source " +
                "${reminder.key}: ${error.message}"
        )
        null
    }
}

private fun localReleaseDateAt(
    nowMillis: Long
): LocalDate =
    Instant
        .ofEpochMilli(nowMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()

private fun ReleaseReminderHomeSnapshot.hasAvailabilityConfirmation(
    reminder: ReleaseReminderRecord
): Boolean {
    val reminderBaseUrl = reminder.addonBaseUrl
        ?.trim()
        ?.trimEnd('/')
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }

    return catalogRows
        .asSequence()
        .filter { row -> row.catalogId.startsWith("latest_", ignoreCase = true) }
        .filter { row ->
            reminderBaseUrl == null ||
                row.addonBaseUrl.trim().trimEnd('/').lowercase() == reminderBaseUrl
        }
        .flatMap { row -> row.items.asSequence() }
        .any { item -> reminder.matches(item.id, item.apiType, item.imdbId) }
}

private fun releaseFallbackWindowReached(
    releaseDate: LocalDate,
    nowMillis: Long
): Boolean {
    val fallbackMillis = releaseDate
        .plusDays(1)
        .atTime(RELEASE_FALLBACK_HOUR_LOCAL, 0)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
    return nowMillis >= fallbackMillis
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
