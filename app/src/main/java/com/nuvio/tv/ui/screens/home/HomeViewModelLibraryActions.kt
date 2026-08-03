package com.nuvio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun HomeViewModel.observeLibraryState() {
    viewModelScope.launch {
        libraryRepository.sourceMode
            .distinctUntilChanged()
            .collectLatest { sourceMode ->
                if (sourceMode != LibrarySourceMode.TRAKT) {
                    activePosterListPickerInput = null
                }
                _uiState.update { state ->
                    val resetPickerState = sourceMode != LibrarySourceMode.TRAKT
                    val updatedState = state.copy(
                        librarySourceMode = sourceMode,
                        showPosterListPicker = if (resetPickerState) false else state.showPosterListPicker,
                        posterListPickerPending = if (resetPickerState) false else state.posterListPickerPending,
                        posterListPickerError = if (resetPickerState) null else state.posterListPickerError,
                        posterListPickerTitle = if (resetPickerState) null else state.posterListPickerTitle,
                        posterListPickerMembership = if (resetPickerState) {
                            emptyMap()
                        } else {
                            state.posterListPickerMembership
                        }
                    )
                    if (updatedState == state) state else updatedState
                }
            }
    }

    viewModelScope.launch {
        libraryRepository.listTabs
            .distinctUntilChanged()
            .collectLatest { tabs ->
                _uiState.update { state ->
                    val filteredMembership = mergeMembershipWithTabs(
                        tabs = tabs,
                        membership = state.posterListPickerMembership
                    )
                    if (
                        state.libraryListTabs == tabs &&
                        state.posterListPickerMembership == filteredMembership
                    ) {
                        state
                    } else {
                        state.copy(
                            libraryListTabs = tabs,
                            posterListPickerMembership = filteredMembership
                        )
                    }
                }
            }
    }
}

fun HomeViewModel.togglePosterLibrary(item: MetaPreview, addonBaseUrl: String?) {
    val statusKey = homeItemStatusKey(item.id, item.apiType)
    if (statusKey in _uiState.value.posterLibraryPending) return

    _uiState.update { state ->
        state.copy(posterLibraryPending = state.posterLibraryPending + statusKey)
    }

    // Apply enrichment cache immediately so both what we persist and what we show
    // in-memory carry the fully-resolved logo (TMDB/metahub/meta-addon fallback),
    // not just the pre-enrichment MetaPreview — otherwise the saved item reverts to
    // its unenriched state on the next app launch even though this session looked right.
    val cachedForPersist = enrichmentCache[item.id]
    val enrichedItemForPersist = if (cachedForPersist != null) {
        item.copy(
            poster = item.poster ?: cachedForPersist.poster,
            logo = cachedForPersist.logo ?: cachedForPersist.fallbackLogoUrl ?: item.logo,
            landscapePoster = cachedForPersist.detailBackdrop ?: item.landscapePoster,
            name = cachedForPersist.localizedTitle ?: item.name,
            description = cachedForPersist.description ?: item.description,
            genres = if (cachedForPersist.genres.isNotEmpty()) cachedForPersist.genres else item.genres,
            imdbRating = cachedForPersist.rating?.toFloat() ?: item.imdbRating,
            ageRating = cachedForPersist.ageRating ?: item.ageRating,
            status = cachedForPersist.status ?: item.status,
            runtime = cachedForPersist.runtimeMinutes?.toString() ?: item.runtime
        )
    } else item

    viewModelScope.launch {
        val sourceMode = libraryRepository.sourceMode.first()
        val wasInDefaultList = runCatching {
            when (sourceMode) {
                LibrarySourceMode.SIMKL ->
                    libraryRepository.isInLibrary(
                        itemId = item.id,
                        itemType = item.apiType
                    ).first()

                LibrarySourceMode.TRAKT,
                LibrarySourceMode.LOCAL ->
                    libraryRepository.isInWatchlist(
                        itemId = item.id,
                        itemType = item.apiType
                    ).first()
            }
        }.getOrDefault(false)

        val result = runCatching {
            libraryRepository.toggleDefault(
                enrichedItemForPersist.toLibraryEntryInput(
                    addonBaseUrl
                )
            )
        }

        result.onFailure { error ->
            Log.w(
                HomeViewModel.TAG,
                "Failed to toggle poster library for ${item.id}: " +
                    error.message
            )
        }

        _uiState.update { state ->
            state.copy(
                posterLibraryPending =
                    state.posterLibraryPending - statusKey
            )
        }

        if (result.isSuccess) {
            val message = if (wasInDefaultList) {
                com.nuvio.tv.R.string.detail_removed_from_library
            } else {
                com.nuvio.tv.R.string.detail_added_to_library
            }
            showHomeMessage(appContext.getString(message))
        }
    }

}

fun HomeViewModel.openPosterListPicker(item: MetaPreview, addonBaseUrl: String?) {
    if (_uiState.value.librarySourceMode != LibrarySourceMode.TRAKT) {
        togglePosterLibrary(item, addonBaseUrl)
        return
    }
    val input = item.toLibraryEntryInput(addonBaseUrl)
    activePosterListPickerInput = input

    _uiState.update { state ->
        state.copy(
            showPosterListPicker = true,
            posterListPickerTitle = item.name,
            posterListPickerPending = true,
            posterListPickerError = null,
            posterListPickerMembership = mergeMembershipWithTabs(
                tabs = state.libraryListTabs,
                membership = emptyMap()
            )
        )
    }

    viewModelScope.launch {
        runCatching {
            libraryRepository.getMembershipSnapshot(input)
        }.onSuccess { snapshot ->
            _uiState.update { state ->
                state.copy(
                    showPosterListPicker = true,
                    posterListPickerPending = false,
                    posterListPickerError = null,
                    posterListPickerMembership = mergeMembershipWithTabs(
                        tabs = state.libraryListTabs,
                        membership = snapshot.listMembership
                    )
                )
            }
        }.onFailure { error ->
            Log.w(HomeViewModel.TAG, "Failed to load poster list picker for ${item.id}: ${error.message}")
            _uiState.update { state ->
                state.copy(
                    showPosterListPicker = true,
                    posterListPickerPending = false,
                    posterListPickerError = error.message ?: "Failed to load lists"
                )
            }
        }
    }
}

fun HomeViewModel.togglePosterListPickerMembership(listKey: String) {
    val currentValue = _uiState.value.posterListPickerMembership[listKey] == true
    _uiState.update { state ->
        state.copy(
            posterListPickerMembership = state.posterListPickerMembership.toMutableMap().apply {
                this[listKey] = !currentValue
            },
            posterListPickerError = null
        )
    }
}

fun HomeViewModel.savePosterListPickerMembership() {
    if (_uiState.value.posterListPickerPending) return
    if (_uiState.value.librarySourceMode != LibrarySourceMode.TRAKT) return
    val input = activePosterListPickerInput ?: return

    viewModelScope.launch {
        _uiState.update { state ->
            state.copy(
                posterListPickerPending = true,
                posterListPickerError = null
            )
        }

        runCatching {
            libraryRepository.applyMembershipChanges(
                item = input,
                changes = ListMembershipChanges(
                    desiredMembership = _uiState.value.posterListPickerMembership
                )
            )
        }.onSuccess {
            _uiState.update { state ->
                state.copy(
                    showPosterListPicker = false,
                    posterListPickerPending = false,
                    posterListPickerError = null,
                    posterListPickerTitle = null
                )
            }
            activePosterListPickerInput = null
            observeMyList()
        }.onFailure { error ->
            Log.w(HomeViewModel.TAG, "Failed to save poster list picker: ${error.message}")
            _uiState.update { state ->
                state.copy(
                    posterListPickerPending = false,
                    posterListPickerError = error.message ?: "Failed to update lists"
                )
            }
        }
    }
}

fun HomeViewModel.dismissPosterListPicker() {
    activePosterListPickerInput = null
    _uiState.update { state ->
        state.copy(
            showPosterListPicker = false,
            posterListPickerPending = false,
            posterListPickerError = null,
            posterListPickerTitle = null
        )
    }
}

fun HomeViewModel.togglePosterMovieWatched(item: MetaPreview) {
    if (!item.apiType.equals("movie", ignoreCase = true)) return
    val statusKey = homeItemStatusKey(item.id, item.apiType)
    if (statusKey in _uiState.value.movieWatchedPending) return

    _uiState.update { state ->
        state.copy(movieWatchedPending = state.movieWatchedPending + statusKey)
    }

    viewModelScope.launch {
        val currentlyWatched = _uiState.value.movieWatchedStatus[statusKey] == true
        runCatching {
            if (currentlyWatched) {
                watchProgressRepository.removeFromHistory(item.id, videoId = item.imdbId)
            } else {
                watchProgressRepository.markAsCompleted(buildCompletedMovieProgress(item))
                // Rate only through the selected connected Trakt or Simkl account.
                if (trackingRatingCoordinator.isAvailable()) {
                    val parsedIds = parseContentIds(item.id)
                    val year = Regex("(\\d{4})").find(item.releaseInfo ?: "")
                        ?.groupValues?.getOrNull(1)?.toIntOrNull()
                    _uiState.update { state ->
                        state.copy(
                            showWatchedRatingOverlay = true,
                            watchedRatingItemId = item.id,
                            watchedRatingItemType = item.apiType,
                            watchedRatingTitle = item.name,
                            watchedRatingImdbId = item.imdbId ?: parsedIds.imdb,
                            watchedRatingYear = year
                        )
                    }
                }
            }
        }.onFailure { error ->
            Log.w(HomeViewModel.TAG, "Failed to toggle poster watched status for ${item.id}: ${error.message}")
        }
        _uiState.update { state ->
            state.copy(movieWatchedPending = state.movieWatchedPending - statusKey)
        }
    }
}

private fun buildCompletedMovieProgress(item: MetaPreview): WatchProgress {
    return WatchProgress(
        contentId = item.id,
        contentType = item.apiType,
        name = item.name,
        poster = item.poster,
        backdrop = item.backdropUrl,
        logo = item.logo,
        videoId = item.id,
        season = null,
        episode = null,
        episodeTitle = null,
        position = 1L,
        duration = 1L,
        lastWatched = System.currentTimeMillis(),
        progressPercent = 100f
    )
}

private fun MetaPreview.toLibraryEntryInput(addonBaseUrl: String?): LibraryEntryInput {
    val year = Regex("(\\d{4})").find(releaseInfo ?: "")
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    val parsedIds = parseContentIds(id)
    return LibraryEntryInput(
        itemId = id,
        itemType = apiType,
        title = name,
        year = year,
        traktId = parsedIds.trakt,
        imdbId = parsedIds.imdb,
        tmdbId = parsedIds.tmdb,
        poster = poster,
        posterShape = posterShape,
        background = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres,
        addonBaseUrl = addonBaseUrl
    )
}

private fun mergeMembershipWithTabs(
    tabs: List<LibraryListTab>,
    membership: Map<String, Boolean>
): Map<String, Boolean> {
    return if (tabs.isEmpty()) {
        membership
    } else {
        tabs.associate { tab -> tab.key to (membership[tab.key] == true) }
    }
}
