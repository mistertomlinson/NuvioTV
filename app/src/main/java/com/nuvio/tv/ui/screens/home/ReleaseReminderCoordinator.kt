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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "ReleaseReminderCoordinator"
private const val BADGE_LIFETIME_MS = 30L * 24 * 60 * 60 * 1000
private const val DUE_RECHECK_MS = 60L * 60 * 1000
private const val FUTURE_RECHECK_MS = 12L * 60 * 60 * 1000
private const val FULFILLMENT_RETRY_MS = 60L * 1000

private data class RuntimeSnapshot(
    val profileId: Int? = null,
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

private data class ProfileReminderKey(
    val profileId: Int,
    val reminderKey: String
)

private data class DeferredAvailableReminder(
    val record: ReleaseReminderRecord
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
    private val deferredAvailableReminders =
        ConcurrentHashMap<ProfileReminderKey, DeferredAvailableReminder>()

    /*
     * Removing a reminder can remove an armed/fulfilled poster badge from the
     * currently rendered Home tree. Keep that mutation behind the same Home
     * presentation freeze as fulfillment/My List insertion.
     */
    private val deferredReminderRemovals =
        ConcurrentHashMap<ProfileReminderKey, Unit>()

    @Volatile private var homeVisible = false
    // Preserve Home-visible deferral before MainActivity delivers its first lifecycle event.
    // A Worker-only process still has homeVisible=false and may commit off-screen.
    @Volatile private var appForeground = true
    @Volatile private var coveredResumeDue = false
    @Volatile private var observedReminders: List<ReleaseReminderRecord>? = null

    /**
     * The return curtain is expensive by design: do not show it on every wake
     * when a profile has no reminders, or only reminders for future dates.
     * Unknown initial DataStore state is covered conservatively.
     */
    private fun hasResumeWork(): Boolean {
        val reminders = observedReminders ?: return true
        val today = localDate(System.currentTimeMillis())
        val profileId = profileManager.activeProfileId.value
        return reminders.any { record ->
            val date = parseDate(record.releaseDate)
            val due = date != null && !date.isAfter(today)
            val expiredBadge = record.status == ReleaseReminderStatus.FULFILLED &&
                (record.fulfilledAtMillis == null ||
                    System.currentTimeMillis() - record.fulfilledAtMillis >= BADGE_LIFETIME_MS)
            due || record.availabilityConfirmedAtMillis != null || expiredBadge
        } || deferredAvailableReminders.keys.any { it.profileId == profileId } ||
            deferredReminderRemovals.keys.any { it.profileId == profileId }
    }

    /**
     * Compose remains mounted when Android TV is asleep. An Activity pause is
     * therefore an off-screen commitment boundary even when homeVisible=true.
     */
    fun onApplicationForegroundChanged(foreground: Boolean) {
        val wasForeground = appForeground
        appForeground = foreground
        if (foreground) {
            coveredResumeDue = hasResumeWork()
        } else if (wasForeground) {
            scope.launch {
                runMaintenance(
                    forceFutureAuthority = false,
                    dueWorker = false,
                    snapshotEvent = true
                )
            }
        }
    }

    fun needsCoveredResume(): Boolean = coveredResumeDue

    /**
     * Called only while Home has an opaque resume cover. Serializes after
     * Worker batches and completes due/provider writes before Home is revealed.
     */
    suspend fun finishCoveredResume(cwReady: Boolean) {
        if (!coveredResumeDue) return
        try {
            if (cwReady) {
                runMaintenance(
                    forceFutureAuthority = false,
                    dueWorker = true,
                    snapshotEvent = true,
                    coveredHome = true
                )
            } else {
                // Don't insert into My List on an unresolved CW projection.
                // Still wait for any already-started background mutation before
                // allowing the opaque Home cover to leave.
                runMutex.withLock { }
            }
        } finally {
            coveredResumeDue = false
        }
    }


    /* Test fixtures can wait for off-Home background maintenance to finish
     * before releasing the static Worker mocks. Production keeps the
     * coordinator alive as an application singleton. */
    internal suspend fun stopBackgroundWorkForTests() {
        scope.coroutineContext[Job]?.cancelAndJoin()
    }

    private val snapshotRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    fun start() {
        if (!started.compareAndSet(false, true)) return

        scope.launch {
            dataStore.reminders.collectLatest { reminders ->
                observedReminders = reminders
                com.nuvio.tv.core.reminders.ReleaseReminderWorker.scheduleDue(
                    appContext,
                    reminders
                )
            }
        }

        scope.launch {
            profileManager.activeProfileId.collectLatest { profileId ->
                combine(
                    dataStore.remindersForProfile(profileId),
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
                        fullyWatchedSeriesIds = fullyWatchedSeriesIds
                    )
                }.collect { watchedSnapshot ->
                    invalidateWatchedReminders(watchedSnapshot)
                }
            }
        }

        /*
         * A profile handoff normally happens while Home is being replaced by
         * the profile picker. Wake the newly selected profile immediately so
         * matured reminders do not need an extra Details round-trip.
         */
        scope.launch {
            profileManager.activeProfileId
                .drop(1)
                .collect {
                    runMaintenance(
                        forceFutureAuthority = false,
                        dueWorker = false,
                        snapshotEvent = true
                    )
                }
        }

        scope.launch {
            /*
             * collectLatest used to cancel the active maintenance pass when
             * fulfillment itself changed My List/catalogRows. A request that
             * arrives during a pass is now merely coalesced and handled after
             * the current batch has completed.
             */
            snapshotRequests.collect {
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

    internal suspend fun runWatchedReminderMaintenance() {
        val profileId = profileManager.activeProfileId.value
        val reminders = dataStore.remindersForProfile(profileId).first()

        if (profileManager.activeProfileId.value != profileId) {
            return
        }

        val watchedMovieIds =
            watchProgressRepository.observeWatchedMovieIds().first()

        if (profileManager.activeProfileId.value != profileId) {
            return
        }

        val fullyWatchedSeriesIds =
            watchedSeriesStateHolder
                .observeFullyWatchedSeriesIds(profileId)
                .first()

        if (profileManager.activeProfileId.value != profileId) {
            return
        }

        invalidateWatchedReminders(
            WatchedReminderSnapshot(
                profileId = profileId,
                reminders = reminders,
                watchedMovieIds = watchedMovieIds,
                fullyWatchedSeriesIds = fullyWatchedSeriesIds
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
                watchedSnapshot.reminders.filter { reminder ->
                    reminder.matchesWatchedStateRespectingFutureSeason(
                        watchedMovieIds = watchedSnapshot.watchedMovieIds,
                        fullyWatchedSeriesIds =
                            watchedSnapshot.fullyWatchedSeriesIds
                    )
                }

            for (reminder in watchedReminders) {
                if (
                    profileManager.activeProfileId.value !=
                        watchedSnapshot.profileId
                ) {
                    return@withLock
                }

                val queuedKey =
                    ProfileReminderKey(
                        watchedSnapshot.profileId,
                        reminder.key
                    )

                deferredAvailableReminders.remove(queuedKey)

                if (homeVisible) {
                    deferredReminderRemovals[queuedKey] = Unit
                    Log.i(
                        TAG,
                        "Staged watched reminder removal ${reminder.key} until Home exits"
                    )
                } else {
                    deferredReminderRemovals.remove(queuedKey)
                    dataStore.remove(
                        reminder.key,
                        watchedSnapshot.profileId
                    )
                    Log.i(
                        TAG,
                        "Removed watched release reminder ${reminder.key}"
                    )
                }
            }
        }
    }

    fun setHomeVisible(visible: Boolean) {
        val wasVisible = homeVisible
        homeVisible = visible

        if (wasVisible && !visible) {
            /*
             * Home exit is the fulfillment boundary. Do not send it through
             * the 350 ms catalog-snapshot debounce: begin the off-screen batch
             * immediately and let runMutex serialize it with any older pass.
             */
            scope.launch {
                runMaintenance(
                    forceFutureAuthority = false,
                    dueWorker = false,
                    snapshotEvent = true
                )
            }
        }
    }

    fun updateHomeSnapshot(
        cwResolved: Boolean,
        continueWatchingItems: List<ContinueWatchingItem>,
        catalogRows: List<CatalogRow>
    ) {
        snapshot.set(
            RuntimeSnapshot(
                profileId = profileManager.activeProfileId.value,
                cwResolved = cwResolved,
                continueWatchingItems = continueWatchingItems,
                catalogRows = catalogRows
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

    internal suspend fun scheduleDueAfterWorker() {
        val profileId = profileManager.activeProfileId.value
        val reminders = dataStore.remindersForProfile(profileId).first()
        com.nuvio.tv.core.reminders.ReleaseReminderWorker
            .scheduleDueAfterRunningWorker(
                appContext,
                reminders
            )
    }

    private suspend fun runMaintenance(
        forceFutureAuthority: Boolean,
        dueWorker: Boolean,
        snapshotEvent: Boolean,
        coveredHome: Boolean = false
    ) {
        runMutex.withLock {
            val profileId = profileManager.activeProfileId.value
            val now = System.currentTimeMillis()
            val today = localDate(now)
            val batchStartedOffHome = !homeVisible || !appForeground || coveredHome
            val rawRuntime = snapshot.get()
            val runtime =
                if (rawRuntime.profileId == profileId) {
                    rawRuntime
                } else {
                    RuntimeSnapshot(profileId = profileId)
                }

            for (stored in dataStore.remindersForProfile(profileId).first()) {
                if (profileManager.activeProfileId.value != profileId) {
                    return@withLock
                }

                val queuedKey = ProfileReminderKey(profileId, stored.key)

                /*
                 * Whether this batch may mutate visible Home state is fixed at
                 * batch start. If Home re-enters while an off-screen batch is
                 * running, do not split the batch after item #1.
                 */
                val mayCommitHomeMutations = batchStartedOffHome

                if (deferredReminderRemovals.containsKey(queuedKey)) {
                    if (!mayCommitHomeMutations) {
                        continue
                    }

                    deferredReminderRemovals.remove(queuedKey)
                    deferredAvailableReminders.remove(queuedKey)
                    dataStore.remove(stored.key, profileId)
                    continue
                }

                var reminder = stored

                if (reminder.status == ReleaseReminderStatus.FULFILLED) {
                    deferredAvailableReminders.remove(queuedKey)
                    val fulfilledAt = reminder.fulfilledAtMillis
                    val expired = fulfilledAt == null ||
                        now - fulfilledAt >= BADGE_LIFETIME_MS
                    val playbackStarted = runtime.cwResolved &&
                        runtime.continueWatchingItems.any(
                            reminder::matchesContinueWatching
                        )

                    if (expired || playbackStarted) {
                        if (!mayCommitHomeMutations) {
                            deferredReminderRemovals[queuedKey] = Unit
                        } else {
                            deferredReminderRemovals.remove(queuedKey)
                            dataStore.remove(reminder.key, profileId)
                        }
                    } else if (!reminder.membershipConfirmed) {
                        if (mayCommitHomeMutations) {
                            dataStore.markArmed(reminder.key, profileId)
                        }
                    }
                    continue
                }

                /* Authority confirmation is written before the My List mutation.
                 * Rehydrate the pending fulfillment after process death, even
                 * if the original in-memory deferred map no longer exists. */
                val deferred = deferredAvailableReminders[queuedKey]
                if (deferred != null || reminder.availabilityConfirmedAtMillis != null) {
                    val availableRecord =
                        if (reminder.availabilityConfirmedAtMillis != null) {
                            reminder
                        } else {
                            checkNotNull(deferred).record
                        }
                    if (!mayCommitHomeMutations) {
                        /* The due worker may wake while Home is still open.
                         * Move its next retry forward to avoid a five-second
                         * rescheduling loop without touching visible badges. */
                        if (
                            now - (availableRecord.lastAuthorityCheckAtMillis ?: 0L) >=
                                FULFILLMENT_RETRY_MS
                        ) {
                            dataStore.upsert(
                                availableRecord.copy(lastAuthorityCheckAtMillis = now),
                                profileId
                            )
                        }
                        continue
                    }

                    if (
                        runtime.cwResolved &&
                        runtime.continueWatchingItems.any(
                            availableRecord::matchesContinueWatching
                        )
                    ) {
                        deferredAvailableReminders.remove(queuedKey)
                        deferredReminderRemovals.remove(queuedKey)
                        dataStore.remove(reminder.key, profileId)
                        continue
                    }

                    val fulfilled = fulfillAvailableReminder(
                        record = availableRecord,
                        profileId = profileId
                    )
                    if (fulfilled) {
                        deferredAvailableReminders.remove(queuedKey)
                    }
                    continue
                }

                if (reminder.imdbId.isNullOrBlank() && reminder.tmdbId != null) {
                    val resolved = runCatching {
                        tmdbService.tmdbToImdb(
                            reminder.tmdbId,
                            reminder.itemType
                        )
                    }.getOrNull()

                    if (profileManager.activeProfileId.value != profileId) {
                        return@withLock
                    }

                    if (!resolved.isNullOrBlank()) {
                        reminder = reminder.copy(imdbId = resolved)
                        dataStore.upsert(reminder, profileId)
                    }
                }

                val comingSoon = runtime.findComingSoonMatch(reminder)
                if (comingSoon != null) {
                    val hints = comingSoon.behaviorHints
                    val date = hints?.releaseDate?.takeIf(::isExactDate)
                    val season = hints?.upcomingSeason
                    val platform =
                        hints?.platformId?.trim()?.takeIf { it.isNotBlank() }
                    val badge =
                        if ((season ?: reminder.seasonNumber ?: 0) >= 2) {
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
                        dataStore.upsert(reminder, profileId)
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

                if (profileManager.activeProfileId.value != profileId) {
                    return@withLock
                }

                val checked = reminder.copy(lastAuthorityCheckAtMillis = now)

                if (fresh == null) {
                    dataStore.upsert(checked, profileId)
                    continue
                }

                val hints = fresh.behaviorHints
                val state = hints?.releaseReminderState?.trim()?.lowercase()

                if (state == "coming_soon") {
                    val date = hints.releaseDate?.takeIf(::isExactDate)
                    val season = hints.upcomingSeason
                    val platform =
                        hints.platformId?.trim()?.takeIf { it.isNotBlank() }
                    val badge =
                        if ((season ?: reminder.seasonNumber ?: 0) >= 2) {
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
                        ),
                        profileId
                    )
                    continue
                }

                if (state == "unknown") {
                    dataStore.upsert(checked, profileId)
                    continue
                }

                var released = state == "released"
                if (released) {
                    released = sourceReleaseMatches(reminder, fresh)
                }

                val available = released || (state == null && latest)
                if (!available) {
                    dataStore.upsert(checked, profileId)
                    continue
                }

                /* Durable write-ahead checkpoint: if the app dies during
                 * provider mutation, retry the confirmed release directly
                 * instead of depending on a second availability response. */
                val confirmedAvailable = checked.copy(
                    availabilityConfirmedAtMillis = now
                )
                dataStore.upsert(confirmedAvailable, profileId)

                if (
                    runtime.cwResolved &&
                    runtime.continueWatchingItems.any(
                        reminder::matchesContinueWatching
                    )
                ) {
                    deferredAvailableReminders.remove(queuedKey)
                    if (!mayCommitHomeMutations) {
                        deferredReminderRemovals[queuedKey] = Unit
                    } else {
                        deferredReminderRemovals.remove(queuedKey)
                        dataStore.remove(reminder.key, profileId)
                    }
                    continue
                }

                if (profileManager.activeProfileId.value != profileId) {
                    return@withLock
                }

                if (!mayCommitHomeMutations) {
                    deferredAvailableReminders[queuedKey] =
                        DeferredAvailableReminder(confirmedAvailable)
                    Log.i(
                        TAG,
                        "Staged fulfillment ${reminder.key} until Home exits"
                    )
                    continue
                }

                fulfillAvailableReminder(
                    record = confirmedAvailable,
                    profileId = profileId
                )

                if (profileManager.activeProfileId.value != profileId) {
                    return@withLock
                }
            }

            if (profileManager.activeProfileId.value == profileId) {
                com.nuvio.tv.core.reminders.ReleaseReminderWorker.scheduleDue(
                    appContext,
                    dataStore.remindersForProfile(profileId).first()
                )
            }
        }
    }

    private suspend fun fulfillAvailableReminder(
        record: ReleaseReminderRecord,
        profileId: Int
    ): Boolean {
        if (profileManager.activeProfileId.value != profileId) {
            return false
        }

        val retryable = record.copy(
            lastAuthorityCheckAtMillis = System.currentTimeMillis()
        )

        return try {
            profileManager.withActiveProfileMutation(profileId) {
                val confirmed =
                    libraryRepository.ensureInDefault(
                        record.toLibraryEntryInput()
                    )

                if (confirmed) {
                    val queuedKey = ProfileReminderKey(profileId, record.key)
                    deferredReminderRemovals.remove(queuedKey)
                    dataStore.markFulfilled(
                        record.key,
                        System.currentTimeMillis(),
                        profileId
                    )
                    Log.i(
                        TAG,
                        "Fulfilled ${record.key} off Home"
                    )
                    true
                } else {
                    dataStore.upsert(retryable, profileId)
                    false
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            dataStore.upsert(retryable, profileId)
            Log.w(
                TAG,
                "Fulfillment failed for ${record.key}: ${error.message}"
            )
            false
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
            Log.w(
                TAG,
                "Fresh authority failed for ${reminder.key}: ${t.message}"
            )
            null
        }
    }

    private fun sourceReleaseMatches(
        reminder: ReleaseReminderRecord,
        meta: Meta
    ): Boolean {
        val hints = meta.behaviorHints
        val reminderPlatform =
            reminder.platformId?.trim()?.takeIf { it.isNotBlank() }
        val authorityPlatform =
            hints?.platformId?.trim()?.takeIf { it.isNotBlank() }
        val platformSpecific =
            reminderPlatform != null &&
                !reminderPlatform.equals("global", true)

        if (
            platformSpecific &&
            (
                authorityPlatform == null ||
                    !authorityPlatform.equals(reminderPlatform, true)
            )
        ) {
            return false
        }

        val season = reminder.seasonNumber ?: 0
        if (season >= 2 && (hints?.newSeasonNumber ?: 0) < season) {
            return false
        }

        return true
    }
}

private fun RuntimeSnapshot.rowsForReminder(
    reminder: ReleaseReminderRecord
): Sequence<CatalogRow> {
    val base = reminder.addonBaseUrl
        ?.trim()
        ?.trimEnd('/')
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }

    return catalogRows.asSequence().filter { row ->
        base == null ||
            row.addonBaseUrl
                .trim()
                .trimEnd('/')
                .lowercase() == base
    }
}

private fun MetaPreview.matchesReminderPlatform(
    reminder: ReleaseReminderRecord
): Boolean {
    val reminderPlatform =
        reminder.platformId?.trim()?.takeIf { it.isNotBlank() }

    if (
        reminderPlatform == null ||
        reminderPlatform.equals("global", true)
    ) {
        return true
    }

    val candidatePlatform =
        behaviorHints?.platformId?.trim()?.takeIf { it.isNotBlank() }

    return candidatePlatform != null &&
        candidatePlatform.equals(reminderPlatform, true)
}

private fun RuntimeSnapshot.findComingSoonMatch(
    reminder: ReleaseReminderRecord
): MetaPreview? =
    rowsForReminder(reminder)
        .flatMap { it.items.asSequence() }
        .firstOrNull { item ->
            item.behaviorHints?.comingSoon == true &&
                item.matchesReminderPlatform(reminder) &&
                reminder.matches(item.id, item.apiType, item.imdbId)
        }

private fun RuntimeSnapshot.hasComingSoonSource(
    reminder: ReleaseReminderRecord
): Boolean =
    rowsForReminder(reminder)
        .flatMap { it.items.asSequence() }
        .any { item ->
            item.behaviorHints?.comingSoon == true &&
                item.matchesReminderPlatform(reminder)
        }

private fun RuntimeSnapshot.hasAvailabilityConfirmation(
    reminder: ReleaseReminderRecord
): Boolean =
    rowsForReminder(reminder)
        .filter { it.catalogId.startsWith("latest_", true) }
        .flatMap { it.items.asSequence() }
        .any { item ->
            if (
                !item.matchesReminderPlatform(reminder) ||
                !reminder.matches(item.id, item.apiType, item.imdbId)
            ) {
                return@any false
            }

            val remindedSeason = reminder.seasonNumber ?: 0
            if (remindedSeason < 2) return@any true

            (item.behaviorHints?.newSeasonNumber ?: return@any false) >=
                remindedSeason
        }

private fun ReleaseReminderRecord.matchesContinueWatching(
    item: ContinueWatchingItem
): Boolean = when (item) {
    is ContinueWatchingItem.InProgress ->
        matches(
            item.progress.contentId,
            item.progress.contentType,
            null
        )

    is ContinueWatchingItem.NextUp ->
        matches(
            item.info.contentId,
            item.info.contentType,
            null
        )
}

private fun ReleaseReminderRecord.matches(
    candidateId: String,
    candidateType: String,
    candidateImdbId: String?
): Boolean {
    if (normalizeType(itemType) != normalizeType(candidateType)) return false
    return identityTokens(itemId, imdbId, tmdbId, traktId, simklId)
        .intersect(
            identityTokens(
                candidateId,
                candidateImdbId,
                null,
                null,
                null
            )
        )
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
    imdbId
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }
        ?.let { add("imdb:$it") }
    tmdbId?.let { add("tmdb:$it") }
    traktId?.let { add("trakt:$it") }
    simklId?.let { add("simkl:$it") }
    if (raw.startsWith("tmdb:")) {
        add("tmdb:${raw.removePrefix("tmdb:").substringBefore(':')}")
    }
    if (raw.startsWith("trakt:")) {
        add("trakt:${raw.removePrefix("trakt:").substringBefore(':')}")
    }
    if (raw.startsWith("simkl:")) {
        add("simkl:${raw.removePrefix("simkl:").substringBefore(':')}")
    }
}

private fun normalizeType(raw: String): String =
    when (raw.trim().lowercase()) {
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
