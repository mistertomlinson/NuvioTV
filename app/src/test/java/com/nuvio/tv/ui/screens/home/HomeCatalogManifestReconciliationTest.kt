package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCatalogManifestReconciliationTest {
    private fun catalog(
        id: String,
        name: String,
        extra: List<CatalogExtra> = emptyList()
    ) = CatalogDescriptor(
        type = ContentType.MOVIE,
        id = id,
        name = name,
        extra = extra
    )

    @Test
    fun `name-only refresh keeps the existing catalog structure`() {
        val current = listOf(
            catalog(
                id = "seasonalspotlight.autumn.movie.romance",
                name = "Cozy Autumn Romance"
            )
        )
        val incoming = listOf(
            catalog(
                id = "seasonalspotlight.autumn.movie.romance",
                name = "Romantic Autumn Stories"
            )
        )

        assertTrue(
            hasSameCatalogStructure(
                current = current,
                incoming = incoming
            )
        )
    }

    @Test
    fun `catalog identity change requires staged replacement`() {
        val current = listOf(
            catalog(
                id = "seasonalspotlight.autumn.movie.romance",
                name = "Cozy Autumn Romance"
            )
        )
        val incoming = listOf(
            catalog(
                id = "seasonalspotlight.autumn.movie.foliage",
                name = "Fall Foliage Favorites"
            )
        )

        assertFalse(
            hasSameCatalogStructure(
                current = current,
                incoming = incoming
            )
        )
    }

    @Test
    fun `catalog order change requires staged replacement`() {
        val first = catalog(
            id = "seasonalspotlight.slot01",
            name = "First"
        )
        val second = catalog(
            id = "seasonalspotlight.slot02",
            name = "Second"
        )

        assertFalse(
            hasSameCatalogStructure(
                current = listOf(first, second),
                incoming = listOf(second, first)
            )
        )
    }

    @Test
    fun `catalog capability change requires staged replacement`() {
        val current = listOf(
            catalog(
                id = "seasonalspotlight.slot01",
                name = "Autumn Stories"
            )
        )
        val incoming = listOf(
            catalog(
                id = "seasonalspotlight.slot01",
                name = "Autumn Stories",
                extra = listOf(
                    CatalogExtra(
                        name = "skip",
                        isRequired = false
                    )
                )
            )
        )

        assertFalse(
            hasSameCatalogStructure(
                current = current,
                incoming = incoming
            )
        )
    }

    @Test
    fun `refreshed manifest title replaces stale row title without changing content`() {
        val current = CatalogRow(
            addonId = "community.seasonalspotlight",
            addonName = "Seasonal Spotlight",
            addonBaseUrl = "https://old.example",
            catalogId = "seasonalspotlight.autumn.movie.romance",
            catalogName = "Cozy Autumn Romance",
            type = ContentType.MOVIE,
            items = emptyList()
        )

        val updated = current.withCatalogDisplayMetadata(
            addonName = "Seasonal Spotlight",
            addonBaseUrl = "https://seasonal.example",
            catalogName = "Romantic Autumn Stories"
        )

        assertEquals(
            "Romantic Autumn Stories",
            updated.catalogName
        )
        assertEquals(
            current.catalogId,
            updated.catalogId
        )
        assertEquals(
            current.items,
            updated.items
        )
    }
}
