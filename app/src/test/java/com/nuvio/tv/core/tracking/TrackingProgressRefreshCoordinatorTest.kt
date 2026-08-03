package com.nuvio.tv.core.tracking

import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressSource
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TrackingProgressRefreshCoordinatorTest {

    @Test
    fun `selected Simkl refreshes only Simkl`() = runTest {
        val trakt = provider(TrackingProviderId.TRAKT)
        val simkl = provider(TrackingProviderId.SIMKL)
        val coordinator = coordinator(
            source = WatchProgressSource.SIMKL,
            providers = setOf(trakt, simkl)
        )

        coordinator.refreshSelected(TrackingRefreshIntent.AUTOMATIC)

        coVerify(exactly = 1) {
            simkl.refresh(TrackingRefreshIntent.AUTOMATIC)
        }
        coVerify(exactly = 0) {
            trakt.refresh(any())
        }
    }

    @Test
    fun `selected Trakt refreshes only Trakt`() = runTest {
        val trakt = provider(TrackingProviderId.TRAKT)
        val simkl = provider(TrackingProviderId.SIMKL)
        val coordinator = coordinator(
            source = WatchProgressSource.TRAKT,
            providers = setOf(trakt, simkl)
        )

        coordinator.refreshSelected(TrackingRefreshIntent.USER_INITIATED)

        coVerify(exactly = 1) {
            trakt.refresh(TrackingRefreshIntent.USER_INITIATED)
        }
        coVerify(exactly = 0) {
            simkl.refresh(any())
        }
    }

    @Test
    fun `Nuvio Sync refreshes no external provider`() = runTest {
        val trakt = provider(TrackingProviderId.TRAKT)
        val simkl = provider(TrackingProviderId.SIMKL)
        val coordinator = coordinator(
            source = WatchProgressSource.NUVIO_SYNC,
            providers = setOf(trakt, simkl)
        )

        coordinator.refreshSelected(TrackingRefreshIntent.AUTOMATIC)

        coVerify(exactly = 0) {
            trakt.refresh(any())
        }
        coVerify(exactly = 0) {
            simkl.refresh(any())
        }
    }

    @Test
    fun `disconnected selected provider is not refreshed`() = runTest {
        val simkl = provider(
            id = TrackingProviderId.SIMKL,
            authenticated = false
        )
        val coordinator = coordinator(
            source = WatchProgressSource.SIMKL,
            providers = setOf(simkl)
        )

        coordinator.refreshSelected(TrackingRefreshIntent.AUTOMATIC)

        coVerify(exactly = 0) {
            simkl.refresh(any())
        }
    }

    private fun coordinator(
        source: WatchProgressSource,
        providers: Set<TrackingProgressProvider>
    ): TrackingProgressRefreshCoordinator {
        val settingsDataStore = mockk<TraktSettingsDataStore>()
        every {
            settingsDataStore.watchProgressSource
        } returns flowOf(source)

        return TrackingProgressRefreshCoordinator(
            providers = TrackingProgressProviderRegistry(providers),
            settingsDataStore = settingsDataStore
        )
    }

    private fun provider(
        id: TrackingProviderId,
        authenticated: Boolean = true
    ): TrackingProgressProvider =
        mockk(relaxed = true) {
            every { providerId } returns id
            every { isAuthenticated } returns flowOf(authenticated)
        }
}
