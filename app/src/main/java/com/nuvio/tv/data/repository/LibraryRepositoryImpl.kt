package com.nuvio.tv.data.repository

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.LibrarySyncService
import com.nuvio.tv.core.tracking.TrackingLibraryProviderRegistry
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.effectiveLibrarySourceMode
import com.nuvio.tv.core.tracking.providerId
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.ListMembershipSnapshot
import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.model.TraktListPrivacy
import com.nuvio.tv.domain.repository.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRepositoryImpl @Inject constructor(
    private val libraryPreferences: LibraryPreferences,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val traktLibraryService: TraktLibraryService,
    private val librarySyncService: LibrarySyncService,
    private val authManager: AuthManager,
    private val trackingProviders: TrackingLibraryProviderRegistry,
    private val profileManager: ProfileManager
) : LibraryRepository {

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null
    var isSyncingFromRemote = false
    var hasCompletedInitialPull = false

    init {
        syncScope.launch {
            profileManager.activeProfileId
                .drop(1)
                .collect {
                    traktLibraryService.resetSnapshot()
                    if (sourceMode.first() == LibrarySourceMode.TRAKT) {
                        runCatching { traktLibraryService.refreshNow() }
                    }
                }
        }
    }

    private fun triggerRemoteSync() {
        if (isSyncingFromRemote) return
        if (!hasCompletedInitialPull) return
        if (!authManager.isAuthenticated) return
        syncJob?.cancel()
        syncJob = syncScope.launch {
            delay(500)
            librarySyncService.pushToRemote()
        }
    }

    private val providerConnections = combine(
        trackingProviders.providers().map { provider ->
            provider.isAuthenticated.map { authenticated ->
                provider.providerId to authenticated
            }
        }
    ) { states -> states.toMap() }

    override val sourceMode: Flow<LibrarySourceMode> = combine(
        traktSettingsDataStore.librarySourceMode,
        providerConnections
    ) { requestedMode, connections ->
        effectiveLibrarySourceMode(requestedMode) { providerId ->
            connections[providerId] == true
        }
    }.distinctUntilChanged()

    override val isSyncing: Flow<Boolean> = sourceMode
        .flatMapLatest { mode ->
            mode.providerId
                ?.let(trackingProviders::provider)
                ?.isRefreshing
                ?: flowOf(false)
        }
        .distinctUntilChanged()

    private val localLibraryEntries: Flow<List<LibraryEntry>> =
        libraryPreferences.libraryItems.map { items ->
            items.map { saved ->
                LibraryEntry(
                    id = saved.id,
                    type = saved.type,
                    name = saved.name,
                    poster = saved.poster,
                    posterShape = saved.posterShape,
                    background = saved.background,
                    logo = saved.logo,
                    description = saved.description,
                    releaseInfo = saved.releaseInfo,
                    imdbRating = saved.imdbRating,
                    genres = saved.genres,
                    addonBaseUrl = saved.addonBaseUrl,
                    listedAt = saved.addedAt
                )
            }
        }

    override val libraryItems: Flow<List<LibraryEntry>> = sourceMode
        .flatMapLatest { mode ->
            mode.providerId
                ?.let(trackingProviders::provider)
                ?.items
                ?: localLibraryEntries
        }
        .distinctUntilChanged()

    override val watchlistItems: Flow<List<LibraryEntry>> = sourceMode
        .flatMapLatest { mode ->
            val provider = mode.providerId?.let(trackingProviders::provider)
            if (provider == null) {
                localLibraryEntries.map { entries ->
                    entries.sortedByDescending(LibraryEntry::listedAt)
                }
            } else {
                combine(provider.items, provider.tabs) { items, tabs ->
                    val watchlistKeys = tabs.asSequence()
                        .filter { tab ->
                            tab.type == LibraryListTab.Type.WATCHLIST
                        }
                        .map(LibraryListTab::key)
                        .toSet()

                    items.asSequence()
                        .filter { entry ->
                            entry.listKeys.any(watchlistKeys::contains)
                        }
                        .sortedByDescending(LibraryEntry::listedAt)
                        .toList()
                }
            }
        }
        .distinctUntilChanged()

    override val listTabs: Flow<List<LibraryListTab>> = sourceMode
        .flatMapLatest { mode ->
            mode.providerId
                ?.let(trackingProviders::provider)
                ?.tabs
                ?: flowOf(emptyList())
        }
        .distinctUntilChanged()

    override fun isInLibrary(
        itemId: String,
        itemType: String
    ): Flow<Boolean> {
        return sourceMode.flatMapLatest { mode ->
            val provider = mode.providerId?.let(trackingProviders::provider)
            if (provider != null) {
                provider.observeMembership(itemId, itemType)
                    .map { memberships -> memberships.isNotEmpty() }
            } else {
                libraryPreferences.isInLibrary(
                    itemId = itemId,
                    itemType = itemType
                )
            }
        }.distinctUntilChanged()
    }

    override fun isInWatchlist(
        itemId: String,
        itemType: String
    ): Flow<Boolean> {
        return sourceMode.flatMapLatest { mode ->
            val provider = mode.providerId?.let(trackingProviders::provider)
            if (provider != null) {
                combine(
                    provider.observeMembership(itemId, itemType),
                    provider.tabs
                ) { memberships, tabs ->
                    tabs.any { tab ->
                        tab.type == LibraryListTab.Type.WATCHLIST &&
                            tab.key in memberships
                    }
                }
            } else {
                libraryPreferences.isInLibrary(
                    itemId = itemId,
                    itemType = itemType
                )
            }
        }.distinctUntilChanged()
    }

    override suspend fun toggleDefault(item: LibraryEntryInput) {
        toggleDefaultForSelectedProvider(item, emitTraktSignal = false)
    }

    override suspend fun toggleDefaultWithSignal(item: LibraryEntryInput) {
        toggleDefaultForSelectedProvider(item, emitTraktSignal = true)
    }

    private suspend fun toggleDefaultForSelectedProvider(
        item: LibraryEntryInput,
        emitTraktSignal: Boolean
    ) {
        val mode = sourceMode.first()

        if (mode == LibrarySourceMode.TRAKT) {
            traktLibraryService.toggleWatchlist(
                item = item,
                emitSignal = emitTraktSignal
            )
            return
        }

        val provider = mode.providerId?.let(trackingProviders::provider)
        if (provider != null) {
            val current = provider
                .getMembershipSnapshot(item)
                .listMembership
            provider.applyMembershipChanges(
                item = item,
                changes = ListMembershipChanges(
                    provider.toggledDefaultMembership(current)
                )
            )
            return
        }

        val isInLocal = libraryPreferences
            .isInLibrary(item.itemId, item.itemType)
            .first()

        if (isInLocal) {
            libraryPreferences.removeItem(
                itemId = item.itemId,
                itemType = item.itemType
            )
        } else {
            libraryPreferences.addItem(item.toSavedLibraryItem())
        }
        triggerRemoteSync()
    }

    override suspend fun getMembershipSnapshot(
        item: LibraryEntryInput
    ): ListMembershipSnapshot {
        val provider = sourceMode.first()
            .providerId
            ?.let(trackingProviders::provider)

        if (provider != null) {
            return provider.getMembershipSnapshot(item)
        }

        val inLocal = libraryPreferences
            .isInLibrary(item.itemId, item.itemType)
            .first()

        return ListMembershipSnapshot(
            listMembership = mapOf(LOCAL_LIST_KEY to inLocal)
        )
    }

    override suspend fun applyMembershipChanges(
        item: LibraryEntryInput,
        changes: ListMembershipChanges
    ) {
        val provider = sourceMode.first()
            .providerId
            ?.let(trackingProviders::provider)

        if (provider != null) {
            val providerMembership = changes.desiredMembership
                .filterKeys(provider::recognizesListKey)

            provider.applyMembershipChanges(
                item = item,
                changes = ListMembershipChanges(providerMembership)
            )
            return
        }

        val shouldBeSaved =
            changes.desiredMembership[LOCAL_LIST_KEY] == true

        if (shouldBeSaved) {
            libraryPreferences.addItem(item.toSavedLibraryItem())
        } else {
            libraryPreferences.removeItem(
                itemId = item.itemId,
                itemType = item.itemType
            )
        }
        triggerRemoteSync()
    }

    override suspend fun createPersonalList(name: String, description: String?, privacy: TraktListPrivacy) {
        requireTraktAuth()
        traktLibraryService.createPersonalList(name = name, description = description, privacy = privacy)
    }

    override suspend fun updatePersonalList(
        listId: String,
        name: String,
        description: String?,
        privacy: TraktListPrivacy
    ) {
        requireTraktAuth()
        traktLibraryService.updatePersonalList(
            listId = listId,
            name = name,
            description = description,
            privacy = privacy
        )
    }

    override suspend fun deletePersonalList(listId: String) {
        requireTraktAuth()
        traktLibraryService.deletePersonalList(listId)
    }

    override suspend fun reorderPersonalLists(orderedListIds: List<String>) {
        requireTraktAuth()
        traktLibraryService.reorderPersonalLists(orderedListIds)
    }

    override suspend fun refreshNow() {
        val provider = sourceMode.first()
            .providerId
            ?.let(trackingProviders::provider)
            ?: return

        provider.refresh(TrackingRefreshIntent.USER_INITIATED)
    }

    private suspend fun requireTraktAuth() {
        if (!traktAuthDataStore.isEffectivelyAuthenticated.first()) {
            throw IllegalStateException("Trakt authentication required")
        }
    }

    private fun LibraryEntryInput.toSavedLibraryItem(): SavedLibraryItem {
        return SavedLibraryItem(
            id = itemId,
            type = itemType,
            name = title,
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

    companion object {
        private const val LOCAL_LIST_KEY = "local"
    }
}
