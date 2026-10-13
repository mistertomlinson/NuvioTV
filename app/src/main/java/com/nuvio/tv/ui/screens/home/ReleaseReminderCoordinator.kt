package com.nuvio.tv.ui.screens.home

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.ReleaseReminderBadge
import com.nuvio.tv.data.local.ReleaseReminderDataStore
import com.nuvio.tv.data.local.ReleaseReminderRecord
import com.nuvio.tv.data.local.ReleaseReminderStatus
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "ReleaseReminderCoordinator"
private const val BADGE_LIFETIME_MS = 30L * 24 * 60 * 60 * 1000
private const val DUE_RECHECK_MS = 60L * 60 * 1000
private const val FUTURE_RECHECK_MS = 12L * 60 * 60 * 1000

private data class RuntimeSnapshot(
    val cwResolved: Boolean = false,
    val continueWatchingItems: List<ContinueWatchingItem> = emptyList(),
    val catalogRows: List<CatalogRow> = emptyList()
)

private data class WatchedReminderSnapshot(
    val profileId: Int,
    val reminders: List<ReleaseReminderRecord>,
    val watchedMovieIds: Set<String>,
    val fullyWatchedSeriesIds: Set<String>
)

@Singleton
class ReleaseReminderCoordinator @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val dataStore: ReleaseReminderDataStore,
    private val metaRepository: MetaRepository,
    private val tmdbService: TmdbService,
    private val libraryRepository: LibraryRepository,
    private val watchProgressRepository: WatchProgressRepository,
    private val watchedSeriesStateHolder: WatchedSeriesStateHolder,
    private val profileManager: ProfileManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runMutex = Mutex()
    private val started = AtomicBoolean(false)
    private val snapshot = AtomicReference(RuntimeSnapshot())
    @Volatile private var homeVisible = false

    private val snapshotRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    fun start() {
        if (!started.compareAndSet(false, true)) return

        scope.launch {
            dataStore.reminders.collectLatest { reminders ->
                com.nuvio.tv.core.reminders.ReleaseReminderWorker.scheduleDue(
                    appContext,
                    reminders
                )
            }
        }

        /*
         * Watched status supersedes every release-reminder state.
         *
         * Restart the entire reconciliation stream for each selected profile.
         * That prevents a transient profile handoff from ever pairing one
         * profile's watched projection with another profile's reminders.
         *
         * Movies use the active tracking provider's profile-gated watched
         * projection. Series use only the durable fully-watched-series set:
         * watching one episode must never cancel a new-season reminder.
         */
        scope.launch {
            profileManager.activeProfileId.collectLatest { profileId ->
                combine(
                    dataStore.reminders,
                    watchProgressRepository.observeWatchedMovieIds(),
                    watchedSeriesStateHolder
                        .observeFullyWatchedSeriesIds(profileId)
                ) {
                    reminders,
                    watchedMovieIds,
                    fullyWatchedSeriesIds ->

                    WatchedReminderSnapshot(
                        profileId = profileId,
                        reminders = reminders,
                        watchedMovieIds = watchedMovieIds,
                        fullyWatchedSeriesIds =
                            fullyWatchedSeriesIds
                    )
                }.collect { watchedSnapshot ->
                    invalidateWatchedReminders(
                        watchedSnapshot
                    )
                }
            }
        }

        scope.launch {
            snapshotRequests.collectLatest {
                delay(350L)
                runMaintenance(
                    forceFutureAuthority = false,
                    dueWorker = false,
                    snapshotEvent = true
                )
            }
        }

        onReminderStoreChanged()
    }

    /**
     * Deterministic entry point used by regression tests and by any future
     * explicit maintenance caller.
     */
    internal suspend fun runWatchedReminderMaintenance() {
        val profileId =
            profileManager.activeProfileId.value

        val reminders =
            dataStore.reminders.first()

        if (
            profileManager.activeProfileId.value !=
                profileId
        ) {
            return
        }

        val watchedMovieIds =
            watchProgressRepository
                .observeWatchedMovieIds()
                .first()

        if (
            profileManager.activeProfileId.value !=
                profileId
        ) {
            return
        }

        val fullyWatchedSeriesIds =
            watchedSeriesStateHolder
                .observeFullyWatchedSeriesIds(
                    profileId
                )
                .first()

        if (
            profileManager.activeProfileId.value !=
                profileId
        ) {
            return
        }

        invalidateWatchedReminders(
            WatchedReminderSnapshot(
                profileId = profileId,
                reminders = reminders,
                watchedMovieIds = watchedMovieIds,
                fullyWatchedSeriesIds =
                    fullyWatchedSeriesIds
            )
        )
    }

    private suspend fun invalidateWatchedReminders(
        watchedSnapshot: WatchedReminderSnapshot
    ) {
        runMutex.withLock {
            if (
                profileManager.activeProfileId.value !=
                    watchedSnapshot.profileId
            ) {
                return@withLock
            }

            val watchedReminders =
                watchedSnapshot.reminders.filter {
                    reminder ->
                    reminder.matchesWatchedStateRespectingFutureSeason(
                        watchedMovieIds =
                            watchedSnapshot.watchedMovieIds,
                        fullyWatchedSeriesIds =
                            watchedSnapshot
                                .fullyWatchedSeriesIds
                    )
                }

            for (reminder in watchedReminders) {
                if (
                    profileManager.activeProfileId.value !=
                        watchedSnapshot.profileId
                ) {
                    return@withLock
                }

                /*
                 * Remove the record entirely, including a previously fulfilled
                 * Available Now/New Season badge. Once the title is watched,
                 * the watched checkmark is the only useful terminal status.
                 *
                 * ReleaseReminderDataStore then emits the smaller reminder
                 * list and the existing WorkManager observer naturally
                 * reschedules/cancels the due job.
                 */
                dataStore.remove(reminder.key)

                Log.i(
                    TAG,
                    "Removed watched release reminder ${reminder.key}"
                )
            }
        }
    }

    fun setHomeVisible(visible: Boolean) {
        homeVisible = visible
    }

    fun updateHomeSnapshot(
        cwResolved: Boolean,
        continueWatchingItems: List<ContinueWatchingItem>,
        catalogRows: List<CatalogRow>
    ) {
        snapshot.set(
            RuntimeSnapshot(
                cwResolved,
                continueWatchingItems,
                catalogRows
            )
        )
        snapshotRequests.tryEmit(Unit)
    }

    fun onReminderStoreChanged() {
        snapshotRequests.tryEmit(Unit)
    }

    suspend fun runPeriodicMaintenance() {
        runMaintenance(
            forceFutureAuthority = true,
            dueWorker = false,
            snapshotEvent = false
        )
    }

    suspend fun runDueMaintenance() {
        runMaintenance(
            forceFutureAuthority = false,
            dueWorker = true,
            snapshotEvent = false
        )
    }

    private suspend fun runMaintenance(
        forceFutureAuthority: Boolean,
        dueWorker: Boolean,
        snapshotEvent: Boolean
    ) {
        runMutex.withLock {
            val profileId = profileManager.activeProfileId.value
            val now = System.currentTimeMillis()
            val today = localDate(now)
            val runtime = snapshot.get()

            for (stored in dataStore.reminders.first()) {
                if (profileManager.activeProfileId.value != profileId) return@withLock

                var reminder = stored

                if (reminder.status == ReleaseReminderStatus.FULFILLED) {
                    val fulfilledAt = reminder.fulfilledAtMillis
                    val expired = fulfilledAt == null ||
                        now - fulfilledAt >= BADGE_LIFETIME_MS
                    val playbackStarted = runtime.cwResolved &&
                        runtime.continueWatchingItems.any(reminder::matchesContinueWatching)

                    if (expired || playbackStarted) {
                        dataStore.remove(reminder.key)
                    } else if (!reminder.membershipConfirmed) {
                        dataStore.markArmed(reminder.key)
                    }
                    continue
                }

                if (reminder.imdbId.isNullOrBlank() && reminder.tmdbId != null) {
                    val resolved = runCatching {
                        tmdbService.tmdbToImdb(reminder.tmdbId, reminder.itemType)
                    }.getOrNull()

                    /*
                     * Identity resolution may suspend. Never continue processing
                     * this profile's reminder after the user changes profiles.
                     */
                    if (profileManager.activeProfileId.value != profileId) {
                        return@withLock
                    }

                    if (!resolved.isNullOrBlank()) {
                        reminder = reminder.copy(imdbId = resolved)
                        dataStore.upsert(reminder)
                    }
                }

                val comingSoon = runtime.findComingSoonMatch(reminder)
                if (comingSoon != null) {
                    val h = comingSoon.behaviorHints
                    val date = h?.releaseDate?.takeIf(::isExactDate)
                    val season = h?.upcomingSeason
                    val platform = h?.platformId?.trim()?.takeIf { it.isNotBlank() }
                    val badge = if ((season ?: reminder.seasonNumber ?: 0) >= 2) {
                        ReleaseReminderBadge.NEW_SEASON
                    } else {
                        ReleaseReminderBadge.AVAILABLE_NOW
                    }
                    val updated = reminder.copy(
                        releaseDate = date ?: reminder.releaseDate,
                        seasonNumber = season ?: reminder.seasonNumber,
                        platformId = platform ?: reminder.platformId,
                        badge = badge
                    )
                    if (updated != reminder) {
                        reminder = updated
                        dataStore.upsert(reminder)
                    }
                }

                val releaseDate = parseDate(reminder.releaseDate)
                val due = releaseDate != null && !releaseDate.isAfter(today)
                val latest = runtime.hasAvailabilityConfirmation(reminder)
                val disappeared =
                    runtime.hasComingSoonSource(reminder) && comingSoon == null

                val last = reminder.lastAuthorityCheckAtMillis
                val dueReady = due && (
                    last == null || now - last >= DUE_RECHECK_MS
                )
                val futureReady =
                    last == null || now - last >= FUTURE_RECHECK_MS

                val shouldCheck =
                    latest ||
                    ((dueWorker || snapshotEvent) && dueReady) ||
                    (snapshotEvent && disappeared && dueReady) ||
                    (forceFutureAuthority && futureReady)

                if (!shouldCheck) continue

                val fresh = resolveFreshMeta(reminder)

                /*
                 * Fresh authority resolution is a network operation. The profile
                 * that initiated it must still be active before any reminder or
                 * library state is mutated.
                 */
                if (profileManager.activeProfileId.value != profileId) {
                    return@withLock
                }

                val checked = reminder.copy(lastAuthorityCheckAtMillis = now)

                if (fresh == null) {
                    dataStore.upsert(checked)
                    continue
                }

                val hints = fresh.behaviorHints
                val state = hints?.releaseReminderState?.trim()?.lowercase()

                if (state == "coming_soon") {
                    val date = hints.releaseDate?.takeIf(::isExactDate)
                    val season = hints.upcomingSeason
                    val platform = hints.platformId?.trim()?.takeIf { it.isNotBlank() }
                    val badge = if ((season ?: reminder.seasonNumber ?: 0) >= 2) {
                        ReleaseReminderBadge.NEW_SEASON
                    } else {
                        ReleaseReminderBadge.AVAILABLE_NOW
                    }

                    dataStore.upsert(
                        checked.copy(
                            releaseDate = date ?: reminder.releaseDate,
                            seasonNumber = season ?: reminder.seasonNumber,
                            platformId = platform ?: reminder.platformId,
                            badge = badge
                        )
                    )
                    continue
                }

                if (state == "unknown") {
                    dataStore.upsert(checked)
                    continue
                }

                var released = state == "released"
                if (released) {
                    released = sourceReleaseMatches(reminder, fresh)
                }

                val available = released || (state == null && latest)
                if (!available) {
                    dataStore.upsert(checked)
                    continue
                }

                if (
                    runtime.cwResolved &&
                    runtime.continueWatchingItems.any(reminder::matchesContinueWatching)
                ) {
                    dataStore.remove(reminder.key)
                    continue
                }

                /*
                 * Fulfillment is profile-sensitive. Recheck at the mutation
                 * boundary in case processing was suspended beforehand.
                 */
                if (profileManager.activeProfileId.value != profileId) {
                    return@withLock
                }

                runCatching {
                    libraryRepository.ensureInDefault(reminder.toLibraryEntryInput())
                }.onSuccess { confirmed ->
                    /*
                     * ensureInDefault() may itself suspend. Do not mark a
                     * reminder fulfilled in a profile that became active while
                     * that operation was in flight.
                     */
                    if (profileManager.activeProfileId.value != profileId) {
                        return@onSuccess
                    }

                    if (confirmed) {
                        dataStore.markFulfilled(
                            reminder.key,
                            System.currentTimeMillis()
                        )
                        Log.i(
                            TAG,
                            "Fulfilled ${reminder.key}" +
                                if (homeVisible) " while Home visible" else ""
                        )
                    } else {
                        dataStore.upsert(checked)
                    }
                }.onFailure { error ->
                    /*
                     * Do not write failure bookkeeping into a newly active
                     * profile either.
                     */
                    if (profileManager.activeProfileId.value == profileId) {
                        dataStore.upsert(checked)
                        Log.w(
                            TAG,
                            "Fulfillment failed for ${reminder.key}: ${error.message}"
                        )
                    }
                }

                if (profileManager.activeProfileId.value != profileId) {
                    return@withLock
                }
            }

            com.nuvio.tv.core.reminders.ReleaseReminderWorker.scheduleDue(
                appContext,
                dataStore.reminders.first()
            )
        }
    }

    private suspend fun resolveFreshMeta(
        reminder: ReleaseReminderRecord
    ): Meta? {
        val base = reminder.addonBaseUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val id = reminder.tmdbId?.let { "tmdb:$it" } ?: reminder.itemId

        return try {
            when (
                val result = metaRepository.getMetaFresh(
                    addonBaseUrl = base,
                    type = reminder.itemType,
                    id = id
                ).first { it !is NetworkResult.Loading }
            ) {
                is NetworkResult.Success -> result.data
                else -> null
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "Fresh authority failed for ${reminder.key}: ${t.message}")
            null
        }
    }

    private fun sourceReleaseMatches(
        reminder: ReleaseReminderRecord,
        meta: Meta
    ): Boolean {
        val hints = meta.behaviorHints
        val rp = reminder.platformId?.trim()?.takeIf { it.isNotBlank() }
        val ap = hints?.platformId?.trim()?.takeIf { it.isNotBlank() }
        val platformSpecific = rp != null && !rp.equals("global", true)

        if (platformSpecific && (ap == null || !ap.equals(rp, true))) {
            return false
        }

        val season = reminder.seasonNumber ?: 0
        if (season >= 2 && (hints?.newSeasonNumber ?: 0) < season) {
            return false
        }

        return true
    }
}

private fun RuntimeSnapshot.findComingSoonMatch(
    reminder: ReleaseReminderRecord
): MetaPreview? =
    catalogRows.asSequence()
        .flatMap { it.items.asSequence() }
        .firstOrNull {
            it.behaviorHints?.comingSoon == true &&
                reminder.matches(it.id, it.apiType, it.imdbId)
        }

private fun RuntimeSnapshot.hasComingSoonSource(
    reminder: ReleaseReminderRecord
): Boolean {
    val base = reminder.addonBaseUrl
        ?.trim()
        ?.trimEnd('/')
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }
        ?: return false

    return catalogRows.asSequence()
        .filter {
            it.addonBaseUrl.trim().trimEnd('/').lowercase() == base
        }
        .flatMap { it.items.asSequence() }
        .any { it.behaviorHints?.comingSoon == true }
}

private fun RuntimeSnapshot.hasAvailabilityConfirmation(
    reminder: ReleaseReminderRecord
): Boolean {
    val base = reminder.addonBaseUrl
        ?.trim()
        ?.trimEnd('/')
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }

    return catalogRows.asSequence()
        .filter { it.catalogId.startsWith("latest_", true) }
        .filter {
            base == null ||
                it.addonBaseUrl.trim().trimEnd('/').lowercase() == base
        }
        .flatMap { it.items.asSequence() }
        .any { item ->
            if (!reminder.matches(item.id, item.apiType, item.imdbId)) {
                return@any false
            }
            val remindedSeason = reminder.seasonNumber ?: 0
            if (remindedSeason < 2) return@any true
            (item.behaviorHints?.newSeasonNumber ?: return@any false) >= remindedSeason
        }
}

private fun ReleaseReminderRecord.matchesContinueWatching(
    item: ContinueWatchingItem
): Boolean = when (item) {
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
    if (normalizeType(itemType) != normalizeType(candidateType)) return false
    return identityTokens(itemId, imdbId, tmdbId, traktId, simklId)
        .intersect(identityTokens(candidateId, candidateImdbId, null, null, null))
        .isNotEmpty()
}

internal fun shouldProtectReleaseReminderFromWatchedInvalidation(
    itemType: String,
    seasonNumber: Int?,
    badge: ReleaseReminderBadge
): Boolean =
    normalizeType(itemType) == "series" &&
        (
            (seasonNumber ?: 0) >= 2 ||
                badge == ReleaseReminderBadge.NEW_SEASON
        )

private fun ReleaseReminderRecord.matchesWatchedStateRespectingFutureSeason(
    watchedMovieIds: Set<String>,
    fullyWatchedSeriesIds: Set<String>
): Boolean {
    /*
     * A still-armed Season 2+ reminder represents future content.
     *
     * Fully-watched-series state describes the released episode set.
     * It must therefore never invalidate an armed reminder for an
     * unreleased future season, even if stale metadata temporarily
     * describes the show as terminal.
     */
    if (
        shouldProtectReleaseReminderFromWatchedInvalidation(
            itemType = itemType,
            seasonNumber = seasonNumber,
            badge = badge
        )
    ) {
        return false
    }

    return matchesWatchedState(
        watchedMovieIds = watchedMovieIds,
        fullyWatchedSeriesIds = fullyWatchedSeriesIds
    )
}

private fun ReleaseReminderRecord.matchesWatchedState(
    watchedMovieIds: Set<String>,
    fullyWatchedSeriesIds: Set<String>
): Boolean =
    when (normalizeType(itemType)) {
        "movie" ->
            watchedMovieIds.any { watchedId ->
                matches(
                    candidateId = watchedId,
                    candidateType = "movie",
                    candidateImdbId = null
                )
            }

        "series" ->
            fullyWatchedSeriesIds.any { watchedId ->
                matches(
                    candidateId = watchedId,
                    candidateType = "series",
                    candidateImdbId = null
                )
            }

        else -> false
    }

private fun identityTokens(
    rawId: String,
    imdbId: String?,
    tmdbId: Int?,
    traktId: Int?,
    simklId: Long?
): Set<String> = buildSet {
    val raw = rawId.trim().lowercase()
    if (raw.isNotBlank()) add("raw:$raw")
    Regex("tt\\d+", RegexOption.IGNORE_CASE)
        .find(raw)?.value?.lowercase()?.let { add("imdb:$it") }
    imdbId?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { add("imdb:$it") }
    tmdbId?.let { add("tmdb:$it") }
    traktId?.let { add("trakt:$it") }
    simklId?.let { add("simkl:$it") }
    if (raw.startsWith("tmdb:")) add("tmdb:${raw.removePrefix("tmdb:").substringBefore(':')}")
    if (raw.startsWith("trakt:")) add("trakt:${raw.removePrefix("trakt:").substringBefore(':')}")
    if (raw.startsWith("simkl:")) add("simkl:${raw.removePrefix("simkl:").substringBefore(':')}")
}

private fun normalizeType(raw: String): String = when (raw.trim().lowercase()) {
    "film" -> "movie"
    "show", "tv" -> "series"
    else -> raw.trim().lowercase()
}

private fun parseDate(raw: String?): LocalDate? {
    val value = raw?.trim()?.takeIf { it.length == 10 } ?: return null
    return try {
        LocalDate.parse(value)
    } catch (_: DateTimeParseException) {
        null
    }
}

private fun isExactDate(raw: String): Boolean = parseDate(raw) != null

private fun localDate(now: Long): LocalDate =
    Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()

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
