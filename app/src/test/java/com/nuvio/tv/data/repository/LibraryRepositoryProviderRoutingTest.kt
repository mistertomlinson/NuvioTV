package com.nuvio.tv.data.repository

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.LibrarySyncService
import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingLibraryProviderRegistry
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.ListMembershipSnapshot
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRepositoryProviderRoutingTest {

    @Test
    fun `selected authenticated Simkl is the effective library source`() = runTest {
        val provider = FakeLibraryProvider(authenticated = true)
        val repository = createRepository(
            requestedSource = LibrarySourceMode.SIMKL,
            provider = provider
        ).first

        assertEquals(LibrarySourceMode.SIMKL, repository.sourceMode.first())
    }


    @Test
    fun `selected Simkl watchlist items contain only Plan to Watch`() =
        runTest {
            val provider = FakeLibraryProvider(authenticated = true)
            provider.items.value = listOf(
                libraryEntry(
                    id = "tt0000001",
                    listKey = FakeLibraryProvider.PLAN_TO_WATCH_KEY,
                    listedAt = 200L
                ),
                libraryEntry(
                    id = "tt0000002",
                    listKey = FakeLibraryProvider.WATCHING_KEY,
                    listedAt = 300L
                )
            )

            val repository = createRepository(
                requestedSource = LibrarySourceMode.SIMKL,
                provider = provider
            ).first

            assertEquals(
                listOf("tt0000001"),
                repository.watchlistItems.first().map(LibraryEntry::id)
            )
        }

    @Test
    fun `disconnected selected Simkl falls back to local library`() = runTest {
        val provider = FakeLibraryProvider(authenticated = false)
        val repository = createRepository(
            requestedSource = LibrarySourceMode.SIMKL,
            provider = provider
        ).first

        assertEquals(LibrarySourceMode.LOCAL, repository.sourceMode.first())
    }

    @Test
    fun `default toggle writes only to selected Simkl provider`() = runTest {
        val provider = FakeLibraryProvider(authenticated = true)
        val traktLibraryService = mockk<TraktLibraryService>(relaxed = true)
        val repository = createRepository(
            requestedSource = LibrarySourceMode.SIMKL,
            provider = provider,
            traktLibraryService = traktLibraryService
        ).first
        val item = LibraryEntryInput(
            itemId = "tt1234567",
            itemType = "movie",
            title = "Routing Test"
        )

        repository.toggleDefault(item)

        assertEquals(1, provider.appliedChanges.size)
        assertEquals(
            mapOf(FakeLibraryProvider.PLAN_TO_WATCH_KEY to true),
            provider.appliedChanges.single().desiredMembership
        )
        assertTrue(repository.isInWatchlist(item.itemId, item.itemType).first())
        coVerify(exactly = 0) {
            traktLibraryService.toggleWatchlist(any(), any())
        }
    }

    @Test
    fun `manual refresh targets selected Simkl provider`() = runTest {
        val provider = FakeLibraryProvider(authenticated = true)
        val repository = createRepository(
            requestedSource = LibrarySourceMode.SIMKL,
            provider = provider
        ).first

        repository.refreshNow()

        assertEquals(
            listOf(TrackingRefreshIntent.USER_INITIATED),
            provider.refreshIntents
        )
    }

    private fun libraryEntry(
        id: String,
        listKey: String,
        listedAt: Long
    ): LibraryEntry = LibraryEntry(
        id = id,
        type = "movie",
        name = id,
        poster = null,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        addonBaseUrl = null,
        listKeys = setOf(listKey),
        listedAt = listedAt
    )

    private fun createRepository(
        requestedSource: LibrarySourceMode,
        provider: FakeLibraryProvider,
        traktLibraryService: TraktLibraryService =
            mockk(relaxed = true)
    ): Pair<LibraryRepositoryImpl, TraktLibraryService> {
        val libraryPreferences = mockk<LibraryPreferences>(relaxed = true)
        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        val traktSettingsDataStore = mockk<TraktSettingsDataStore>()
        val librarySyncService = mockk<LibrarySyncService>(relaxed = true)
        val authManager = mockk<AuthManager>(relaxed = true)
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns
            MutableStateFlow(1)

        every { libraryPreferences.libraryItems } returns flowOf(emptyList())
        every {
            libraryPreferences.isInLibrary(any(), any())
        } returns flowOf(false)
        every {
            traktAuthDataStore.isEffectivelyAuthenticated
        } returns flowOf(true)
        every {
            traktSettingsDataStore.librarySourceMode
        } returns flowOf(requestedSource)

        return LibraryRepositoryImpl(
            libraryPreferences = libraryPreferences,
            traktAuthDataStore = traktAuthDataStore,
            traktSettingsDataStore = traktSettingsDataStore,
            traktLibraryService = traktLibraryService,
            librarySyncService = librarySyncService,
            authManager = authManager,
            trackingProviders = TrackingLibraryProviderRegistry(
                setOf(provider)
            ),
            profileManager = profileManager
        ) to traktLibraryService
    }

    private class FakeLibraryProvider(
        authenticated: Boolean
    ) : TrackingLibraryProvider {

        companion object {
            const val PLAN_TO_WATCH_KEY =
                "simkl:status:plantowatch"
            const val WATCHING_KEY = "simkl:status:watching"
        }

        override val providerId = TrackingProviderId.SIMKL
        override val isAuthenticated = MutableStateFlow(authenticated)
        override val isRefreshing = MutableStateFlow(false)
        override val items = MutableStateFlow<List<LibraryEntry>>(emptyList())
        override val tabs = MutableStateFlow(
            listOf(
                LibraryListTab(
                    key = PLAN_TO_WATCH_KEY,
                    title = "Plan to Watch",
                    type = LibraryListTab.Type.WATCHLIST,
                    trackingProviderId = providerId.storageId
                )
            )
        )

        private val membership = MutableStateFlow<Set<String>>(emptySet())
        val appliedChanges = mutableListOf<ListMembershipChanges>()
        val refreshIntents = mutableListOf<TrackingRefreshIntent>()

        override fun recognizesListKey(key: String): Boolean =
            key == PLAN_TO_WATCH_KEY || key == WATCHING_KEY

        override fun observeMembership(
            itemId: String,
            itemType: String
        ): Flow<Set<String>> = membership

        override fun toggledDefaultMembership(
            currentMembership: Map<String, Boolean>
        ): Map<String, Boolean> =
            currentMembership.mapValues { false }.toMutableMap().apply {
                if (currentMembership.values.none { it }) {
                    this[PLAN_TO_WATCH_KEY] = true
                }
            }

        override suspend fun getMembershipSnapshot(
            item: LibraryEntryInput
        ): ListMembershipSnapshot =
            ListMembershipSnapshot(
                mapOf(
                    PLAN_TO_WATCH_KEY to
                        membership.value.contains(PLAN_TO_WATCH_KEY)
                )
            )

        override suspend fun applyMembershipChanges(
            item: LibraryEntryInput,
            changes: ListMembershipChanges,
            destructiveRemovalConfirmed: Boolean
        ) {
            appliedChanges += changes
            membership.value = changes.desiredMembership
                .filterValues { it }
                .keys
                .filter(::recognizesListKey)
                .toSet()
        }

        override suspend fun refresh(intent: TrackingRefreshIntent) {
            refreshIntents += intent
        }
    }
}
