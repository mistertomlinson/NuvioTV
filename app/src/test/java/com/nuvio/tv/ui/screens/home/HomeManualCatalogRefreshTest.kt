package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ContentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class HomeManualCatalogRefreshTest {
    private fun addon(
        id: String,
        baseUrl: String,
        catalogName: String,
        displayName: String = id
    ) = Addon(
        id = id,
        name = id,
        displayName = displayName,
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = baseUrl,
        catalogs = listOf(
            CatalogDescriptor(
                type = ContentType.MOVIE,
                id = "$id.catalog",
                name = catalogName
            )
        ),
        types = listOf(ContentType.MOVIE),
        resources = emptyList()
    )

    @Test
    fun `manual refresh returns one complete fresh manifest snapshot in installed order`() = runBlocking {
        val current = listOf(
            addon(
                id = WATCHLY_ADDON_ID,
                baseUrl = "https://watchly.example",
                catalogName = "Old Watchly Name",
                displayName = "Watchly Custom"
            ),
            addon(
                id = "other.addon",
                baseUrl = "https://other.example",
                catalogName = "Old Other Name"
            )
        )

        val freshByUrl = mapOf(
            "https://watchly.example" to current[0].copy(
                displayName = "Watchly",
                catalogs = current[0].catalogs.map {
                    it.copy(name = "Fresh Watchly Name")
                }
            ),
            "https://other.example" to current[1].copy(
                catalogs = current[1].catalogs.map {
                    it.copy(name = "Fresh Other Name")
                }
            )
        )

        val refreshed = refreshAddonsForManualCatalogReload(current) { url ->
            NetworkResult.Success(requireNotNull(freshByUrl[url]))
        }

        requireNotNull(refreshed)
        assertEquals(
            listOf(WATCHLY_ADDON_ID, "other.addon"),
            refreshed.map { it.id }
        )
        assertEquals(
            "Fresh Watchly Name",
            refreshed[0].catalogs.single().name
        )
        assertEquals(
            "Watchly Custom",
            refreshed[0].displayName
        )
    }

    @Test
    fun `manual refresh cannot publish after profile switch`() {
        assertTrue(
            manualCatalogRefreshProfileStillActive(
                startedProfileId = 1,
                activeProfileId = 1
            )
        )

        assertFalse(
            manualCatalogRefreshProfileStillActive(
                startedProfileId = 1,
                activeProfileId = 2
            )
        )
    }

    @Test
    fun `manual refresh aborts before catalog reload when any manifest fetch fails`() = runBlocking {
        val current = listOf(
            addon(
                id = WATCHLY_ADDON_ID,
                baseUrl = "https://watchly.example",
                catalogName = "Old Watchly Name"
            ),
            addon(
                id = "broken.addon",
                baseUrl = "https://broken.example",
                catalogName = "Broken"
            )
        )

        val refreshed = refreshAddonsForManualCatalogReload(current) { url ->
            if (url == "https://watchly.example") {
                NetworkResult.Success(
                    current[0].copy(
                        catalogs = current[0].catalogs.map {
                            it.copy(name = "Fresh Watchly Name")
                        }
                    )
                )
            } else {
                NetworkResult.Error("manifest failed")
            }
        }

        assertNull(refreshed)
    }
}
