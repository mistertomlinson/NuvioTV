package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Resolve one complete fresh manifest snapshot before a manual catalog reload.
 *
 * Dynamic addons such as Watchly publish row names and row payloads as one
 * generation. Reloading catalog endpoints from an older manifest can pair an
 * old name with new content. All manifest fetches therefore have to succeed
 * before Home is allowed to start the destructive forced catalog reload.
 */
internal suspend fun refreshAddonsForManualCatalogReload(
    currentAddons: List<Addon>,
    fetchAddon: suspend (String) -> NetworkResult<Addon>
): List<Addon>? = coroutineScope {
    val refreshed = currentAddons.map { current ->
        async {
            when (val result = fetchAddon(current.baseUrl)) {
                is NetworkResult.Success -> {
                    result.data.copy(
                        enabled = current.enabled,
                        displayName = current.displayName
                    )
                }

                is NetworkResult.Error,
                NetworkResult.Loading -> null
            }
        }
    }.awaitAll()

    if (refreshed.any { it == null }) {
        null
    } else {
        refreshed.filterNotNull()
    }
}
