package com.nuvio.tv.core.tracking

import com.nuvio.tv.data.local.TraktSettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope

data class TrackingProgressRefreshFailure(
    val providerId: TrackingProviderId,
    val cause: Throwable
)

@Singleton
class TrackingProgressRefreshCoordinator @Inject constructor(
    private val providers: TrackingProgressProviderRegistry,
    private val settingsDataStore: TraktSettingsDataStore
) {
    suspend fun refreshSelected(
        intent: TrackingRefreshIntent
    ): List<TrackingProgressRefreshFailure> {
        val providerId = settingsDataStore.watchProgressSource
            .first()
            .providerId
            ?: return emptyList()

        val provider = providers.provider(providerId)
            ?: return emptyList()

        if (!provider.isAuthenticated.first()) {
            return emptyList()
        }

        return listOfNotNull(refreshProvider(provider, intent))
    }

    suspend fun refreshConnected(
        intent: TrackingRefreshIntent
    ): List<TrackingProgressRefreshFailure> = supervisorScope {
        providers.providers()
            .filter { provider -> provider.isAuthenticated.first() }
            .map { provider ->
                async {
                    refreshProvider(provider, intent)
                }
            }
            .mapNotNull { operation -> operation.await() }
    }

    private suspend fun refreshProvider(
        provider: TrackingProgressProvider,
        intent: TrackingRefreshIntent
    ): TrackingProgressRefreshFailure? =
        try {
            provider.refresh(intent)
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            TrackingProgressRefreshFailure(provider.providerId, error)
        }
}
