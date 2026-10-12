package com.nuvio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.ReleaseReminderBadge
import com.nuvio.tv.data.local.ReleaseReminderRecord
import com.nuvio.tv.data.local.ReleaseReminderStatus
import com.nuvio.tv.data.local.releaseReminderKey
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val RELEASE_BADGE_LIFETIME_MS =
    30L * 24 * 60 * 60 * 1000

internal fun releaseReminderIdentityKeys(
    item: MetaPreview
): Set<String> = buildSet {
    releaseReminderKey(item.id, item.apiType)?.let(::add)
    item.imdbId
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { id ->
            releaseReminderKey(id, item.apiType)?.let(::add)
        }
}

internal fun releaseReminderIdentityKeys(
    record: ReleaseReminderRecord
): Set<String> = buildSet {
    add(record.key)
    record.imdbId?.let { releaseReminderKey(it, record.itemType)?.let(::add) }
    record.tmdbId?.let { releaseReminderKey("tmdb:$it", record.itemType)?.let(::add) }
    record.traktId?.let { releaseReminderKey("trakt:$it", record.itemType)?.let(::add) }
    record.simklId?.let { releaseReminderKey("simkl:$it", record.itemType)?.let(::add) }
}

private fun MetaPreview.toHomeReleaseReminderRecord(
    addonBaseUrl: String
): ReleaseReminderRecord {
    val normalizedId = id.trim()

    fun prefixedLong(prefix: String): Long? =
        normalizedId
            .takeIf { it.startsWith("$prefix:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.substringBefore(':')
            ?.toLongOrNull()

    val season = behaviorHints?.upcomingSeason

    return ReleaseReminderRecord(
        itemId = id,
        itemType = apiType,
        title = name,
        year = behaviorHints?.releaseYear?.trim()?.toIntOrNull(),
        traktId = prefixedLong("trakt")?.toInt(),
        simklId = prefixedLong("simkl"),
        imdbId = imdbId?.trim()?.takeIf { it.isNotBlank() },
        tmdbId = prefixedLong("tmdb")?.toInt(),
        poster = poster,
        background = backdropUrl,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres,
        addonBaseUrl = addonBaseUrl.trim().takeIf { it.isNotBlank() },
        releaseDate = behaviorHints?.releaseDate,
        seasonNumber = season,
        platformId = behaviorHints?.platformId,
        badge = if ((season ?: 0) >= 2) {
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
    if (item.behaviorHints?.comingSoon != true) return

    val identityKeys = releaseReminderIdentityKeys(item)
    if (identityKeys.isEmpty()) return

    val previous = identityKeys.associateWith { key ->
        key in _uiState.value.armedReleaseReminderKeys
    }
    val enable = !previous.values.any { it }

    _uiState.update { state ->
        val updated = state.armedReleaseReminderKeys.toMutableSet()
        if (enable) updated.addAll(identityKeys) else updated.removeAll(identityKeys)
        state.copy(armedReleaseReminderKeys = updated)
    }

    viewModelScope.launch {
        runCatching {
            if (enable) {
                releaseReminderDataStore.setReminder(
                    item.toHomeReleaseReminderRecord(addonBaseUrl),
                    enabled = true
                )
            } else {
                val matching = releaseReminderDataStore.reminders.first()
                    .filter { record ->
                        record.status == ReleaseReminderStatus.ARMED &&
                            releaseReminderIdentityKeys(record).any(identityKeys::contains)
                    }
                if (matching.isNotEmpty()) {
                    matching.forEach { releaseReminderDataStore.remove(it.key) }
                } else {
                    identityKeys.forEach { releaseReminderDataStore.remove(it) }
                }
            }
        }.onSuccess {
            releaseReminderCoordinator.onReminderStoreChanged()
        }.onFailure { error ->
            _uiState.update { state ->
                val restored = state.armedReleaseReminderKeys.toMutableSet()
                previous.forEach { (key, wasSet) ->
                    if (wasSet) restored.add(key) else restored.remove(key)
                }
                state.copy(armedReleaseReminderKeys = restored)
            }
            Log.w(
                HomeViewModel.TAG,
                "Failed to toggle poster release reminder ${item.id}: ${error.message}"
            )
        }
    }
}

internal fun HomeViewModel.observeReleaseRemindersUiPipeline() {
    viewModelScope.launch {
        releaseReminderDataStore.reminders.collectLatest { reminders ->
            val now = System.currentTimeMillis()

            val badges = buildMap {
                reminders.asSequence()
                    .filter { it.status == ReleaseReminderStatus.FULFILLED }
                    .filter { record ->
                        val fulfilledAt = record.fulfilledAtMillis ?: return@filter false
                        now - fulfilledAt < RELEASE_BADGE_LIFETIME_MS
                    }
                    .forEach { record ->
                        releaseReminderIdentityKeys(record).forEach { key ->
                            put(key, record.badge)
                        }
                    }
            }

            val armed = buildSet {
                reminders.asSequence()
                    .filter { it.status == ReleaseReminderStatus.ARMED }
                    .forEach { addAll(releaseReminderIdentityKeys(it)) }
            }

            _uiState.update { state ->
                if (
                    state.releaseReminderBadges == badges &&
                    state.armedReleaseReminderKeys == armed
                ) state
                else state.copy(
                    releaseReminderBadges = badges,
                    armedReleaseReminderKeys = armed
                )
            }
        }
    }
}

internal fun HomeViewModel.observeReleaseReminderRuntimeSnapshotPipeline() {
    viewModelScope.launch {
        combine(
            _initialCwResolved,
            _uiState
                .map { it.continueWatchingItems to it.catalogRows }
                .distinctUntilChanged()
        ) { cwResolved, pair ->
            Triple(cwResolved, pair.first, pair.second)
        }.collectLatest { (cwResolved, cw, rows) ->
            releaseReminderCoordinator.updateHomeSnapshot(
                cwResolved = cwResolved,
                continueWatchingItems = cw,
                catalogRows = rows
            )
        }
    }
}

internal fun HomeViewModel.setReleaseReminderHomeVisible(
    visible: Boolean
) {
    releaseReminderCoordinator.setHomeVisible(visible)
}
