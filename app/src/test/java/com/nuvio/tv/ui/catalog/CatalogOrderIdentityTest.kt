package com.nuvio.tv.ui.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogOrderIdentityTest {

    private val myList =
        "__nuvio_internal_my_list__"

    private val normalA =
        "addon.normal_movie_alpha"

    private val normalB =
        "addon.normal_movie_beta"

    private val seasonalMovie1 =
        "community.seasonalspotlight_movie_seasonalspotlight.slot01"

    private val seasonalMovie2 =
        "community.seasonalspotlight_movie_seasonalspotlight.slot02"

    private val watchlyLoved1 =
        "com.bimal.watchly_movie_watchly.loved.tt001"

    private val watchlyLoved2 =
        "com.bimal.watchly_movie_watchly.loved.tt002"

    @Test
    fun `watchly only reconciliation keeps existing group behavior`() {
        val defaultOrder =
            listOf(
                myList,
                normalA,
                watchlyLoved1,
                watchlyLoved2,
                normalB
            )

        val savedOrder =
            listOf(
                myList,
                watchlyOrderAnchor(
                    "watchly.loved.movie"
                ),
                normalA,
                normalB
            )

        assertEquals(
            listOf(
                myList,
                watchlyLoved1,
                watchlyLoved2,
                normalA,
                normalB
            ),
            reconcileDynamicCatalogOrder(
                defaultOrder =
                    defaultOrder,
                savedOrderKeys =
                    savedOrder,
                myListKey =
                    myList
            )
        )
    }

    @Test
    fun `seasonal anchor expands all current slots at saved position`() {
        val defaultOrder =
            listOf(
                myList,
                normalA,
                seasonalMovie1,
                seasonalMovie2,
                normalB
            )

        val savedOrder =
            listOf(
                myList,
                normalA,
                SEASONAL_SPOTLIGHT_ORDER_ANCHOR,
                normalB
            )

        assertEquals(
            listOf(
                myList,
                normalA,
                seasonalMovie1,
                seasonalMovie2,
                normalB
            ),
            reconcileDynamicCatalogOrder(
                defaultOrder =
                    defaultOrder,
                savedOrderKeys =
                    savedOrder,
                myListKey =
                    myList
            )
        )
    }

    @Test
    fun `legacy concrete seasonal key claims logical seasonal position`() {
        val defaultOrder =
            listOf(
                myList,
                seasonalMovie1,
                seasonalMovie2,
                normalA,
                normalB
            )

        val savedOrder =
            listOf(
                myList,
                normalA,
                seasonalMovie1,
                normalB
            )

        assertEquals(
            listOf(
                myList,
                normalA,
                seasonalMovie1,
                seasonalMovie2,
                normalB
            ),
            reconcileDynamicCatalogOrder(
                defaultOrder =
                    defaultOrder,
                savedOrderKeys =
                    savedOrder,
                myListKey =
                    myList
            )
        )
    }

    @Test
    fun `watchly and seasonal groups retain independent saved positions`() {
        val defaultOrder =
            listOf(
                myList,
                normalA,
                seasonalMovie1,
                seasonalMovie2,
                watchlyLoved1,
                watchlyLoved2,
                normalB
            )

        val savedOrder =
            listOf(
                myList,
                watchlyOrderAnchor(
                    "watchly.loved.movie"
                ),
                normalA,
                SEASONAL_SPOTLIGHT_ORDER_ANCHOR,
                normalB
            )

        assertEquals(
            listOf(
                myList,
                watchlyLoved1,
                watchlyLoved2,
                normalA,
                seasonalMovie1,
                seasonalMovie2,
                normalB
            ),
            reconcileDynamicCatalogOrder(
                defaultOrder =
                    defaultOrder,
                savedOrderKeys =
                    savedOrder,
                myListKey =
                    myList
            )
        )
    }

    @Test
    fun `seasonal anchor is harmless while no seasonal rows are active`() {
        val defaultOrder =
            listOf(
                myList,
                normalA,
                normalB
            )

        val savedOrder =
            listOf(
                myList,
                normalA,
                SEASONAL_SPOTLIGHT_ORDER_ANCHOR,
                normalB
            )

        assertEquals(
            defaultOrder,
            reconcileDynamicCatalogOrder(
                defaultOrder =
                    defaultOrder,
                savedOrderKeys =
                    savedOrder,
                myListKey =
                    myList
            )
        )
    }

    @Test
    fun `seasonal rows reclaim anchor position when they return`() {
        val savedOrder =
            listOf(
                myList,
                normalA,
                SEASONAL_SPOTLIGHT_ORDER_ANCHOR,
                normalB
            )

        val returnedDefaultOrder =
            listOf(
                myList,
                normalA,
                normalB,
                seasonalMovie1,
                seasonalMovie2
            )

        assertEquals(
            listOf(
                myList,
                normalA,
                seasonalMovie1,
                seasonalMovie2,
                normalB
            ),
            reconcileDynamicCatalogOrder(
                defaultOrder =
                    returnedDefaultOrder,
                savedOrderKeys =
                    savedOrder,
                myListKey =
                    myList
            )
        )
    }

    @Test
    fun `seasonal identity cannot claim watchly keys`() {
        assertEquals(
            "seasonalspotlight",
            seasonalSpotlightGroup(
                seasonalMovie1
            )
        )

        assertEquals(
            "seasonalspotlight",
            seasonalSpotlightGroup(
                SEASONAL_SPOTLIGHT_ORDER_ANCHOR
            )
        )

        assertNull(
            seasonalSpotlightGroup(
                watchlyLoved1
            )
        )
    }

    @Test
    fun `seasonal collapse does not change watchly identity`() {
        assertEquals(
            listOf(
                watchlyLoved1,
                SEASONAL_SPOTLIGHT_ORDER_ANCHOR,
                normalA
            ),
            collapseSeasonalSpotlightOrderKeys(
                listOf(
                    watchlyLoved1,
                    seasonalMovie1,
                    seasonalMovie2,
                    normalA
                )
            )
        )
    }
}
