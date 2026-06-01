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

    viewModelScope.launch {
        runCatching {
            libraryRepository.toggleDefault(item.toLibraryEntryInput(addonBaseUrl))
        }.onFailure { error ->
            Log.w(HomeViewModel.TAG, "Failed to toggle poster library for ${item.id}: ${error.message}")
        }
        _uiState.update { state ->
            state.copy(posterLibraryPending = state.posterLibraryPending - statusKey)
        }
        // Update ML row directly — avoid full Trakt re-fetch which causes poster flash
        val currentMlRow = catalogsMap[HomeViewModel.MY_LIST_CATALOG_KEY]
        val isCurrentlyInList = currentMlRow?.items?.any { it.id == item.id } == true
        if (isCurrentlyInList) {
            // Remove item from ML row
            val updatedItems = currentMlRow!!.items.filter { it.id != item.id }
            if (updatedItems.isEmpty()) {
                catalogsMap.remove(HomeViewModel.MY_LIST_CATALOG_KEY)
            } else {
                catalogsMap[HomeViewModel.MY_LIST_CATALOG_KEY] = currentMlRow.copy(items = updatedItems)
            }
        } else {
            // Add item to ML row — apply enrichment cache immediately
            val cached = enrichmentCache[item.id]
            val enrichedItem = if (cached != null) {
                item.copy(
                    poster = item.poster ?: cached.poster,
                    logo = cached.logo ?: item.logo,
                    landscapePoster = cached.detailBackdrop ?: item.landscapePoster,
                    name = cached.localizedTitle ?: item.name,
                    description = cached.description ?: item.description,
                    genres = if (cached.genres.isNotEmpty()) cached.genres else item.genres,
                    imdbRating = cached.rating?.toFloat() ?: item.imdbRating,
                    ageRating = cached.ageRating ?: item.ageRating,
                    status = cached.status ?: item.status,
                    runtime = cached.runtimeMinutes?.toString() ?: item.runtime
                )
            } else item
            if (currentMlRow != null) {
                catalogsMap[HomeViewModel.MY_LIST_CATALOG_KEY] = currentMlRow.copy(
                    items = listOf(enrichedItem) + currentMlRow.items
                )
            } else {
                catalogsMap[HomeViewModel.MY_LIST_CATALOG_KEY] = com.nuvio.tv.domain.model.CatalogRow(
                    addonId = HomeViewModel.MY_LIST_ADDON_ID,
                    addonName = "Built-In",
                    addonBaseUrl = "",
                    catalogId = HomeViewModel.MY_LIST_CATALOG_ID,
                    catalogName = "My List",
                    type = com.nuvio.tv.domain.model.ContentType.UNKNOWN,
                    rawType = "mixed",
                    items = listOf(enrichedItem),
                    isLoading = false,
                    hasMore = false,
                    supportsSkip = false
                )
                if (HomeViewModel.MY_LIST_CATALOG_KEY !in catalogOrder) {
                    catalogOrder.add(0, HomeViewModel.MY_LIST_CATALOG_KEY)
                }
            }
        }
        scheduleUpdateCatalogRows()
        // Sync Trakt snapshot in background so subsequent toggles read correct membership
        viewModelScope.launch { traktLibraryService.refreshNow() }
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
