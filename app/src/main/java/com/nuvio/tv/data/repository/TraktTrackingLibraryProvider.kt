package com.nuvio.tv.data.repository

import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.ListMembershipSnapshot
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Exposes the existing Trakt library implementation through the same
 * provider-neutral contract used by Simkl.
 *
 * The underlying TraktLibraryService remains the single owner of its
 * snapshot, optimistic mutations, personal lists, and watchlist signals.
 */
@Singleton
class TraktTrackingLibraryProvider @Inject constructor(
    private val libraryService: TraktLibraryService,
    authDataStore: TraktAuthDataStore
) : TrackingLibraryProvider {

    override val providerId = TrackingProviderId.TRAKT

    override val isAuthenticated: Flow<Boolean> =
        authDataStore.isEffectivelyAuthenticated.distinctUntilChanged()

    override val isRefreshing =
        libraryService.observeIsRefreshing().distinctUntilChanged()

    override val items = libraryService.observeAllItems()

    override val tabs = libraryService.observeListTabs()

    override fun recognizesListKey(key: String): Boolean =
        key == TraktLibraryService.WATCHLIST_KEY ||
            key.startsWith(TraktLibraryService.PERSONAL_KEY_PREFIX) ||
            key.startsWith(TraktLibraryService.MY_LIST_KEY_PREFIX)

    override fun observeMembership(
        itemId: String,
        itemType: String
    ): Flow<Set<String>> =
        libraryService.observeMembership(itemId, itemType)

    override fun toggledDefaultMembership(
        currentMembership: Map<String, Boolean>
    ): Map<String, Boolean> =
        currentMembership + (
            TraktLibraryService.WATCHLIST_KEY to
                (currentMembership[TraktLibraryService.WATCHLIST_KEY] != true)
        )

    override suspend fun getMembershipSnapshot(
        item: LibraryEntryInput
    ): ListMembershipSnapshot =
        libraryService.getMembershipSnapshot(item)

    override suspend fun applyMembershipChanges(
        item: LibraryEntryInput,
        changes: ListMembershipChanges,
        destructiveRemovalConfirmed: Boolean
    ) {
        libraryService.applyMembershipChanges(item, changes)
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) {
        libraryService.refreshNow()
    }
}
