package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.data.local.ReleaseReminderBadge
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaBehaviorHints
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingOriginalsNewSeasonBadgeScopeTest {

    @Test
    fun streamingOriginalsLatestSeries_allowsReturningSeasonBadge() {
        val card = buildCatalogItem(
            item = returningSeason(),
            row = row(
                addonId = STREAMING_ORIGINALS,
                catalogId = "latest_series_hbo"
            ),
            useLandscapePosters = false,
            occurrence = 0
        )

        assertTrue(card.isNewSeason)
    }

    @Test
    fun streamingOriginalsPopularSeries_suppressesStaleReturningSeasonBadge() {
        val card = buildCatalogItem(
            item = returningSeason(),
            row = row(
                addonId = STREAMING_ORIGINALS,
                catalogId = "popular_series_hbo"
            ),
            useLandscapePosters = false,
            occurrence = 0,
            releaseReminderBadge = ReleaseReminderBadge.NEW_SEASON
        )

        assertFalse(card.isNewSeason)
        assertNull(card.releaseReminderBadge)
    }

    @Test
    fun streamingOriginalsComingSoonSeries_allowsFutureReturningSeasonBadge() {
        val card = buildCatalogItem(
            item = returningSeason(
                comingSoon = true,
                upcomingSeason = 5
            ),
            row = row(
                addonId = STREAMING_ORIGINALS,
                catalogId = "coming_soon_hbo"
            ),
            useLandscapePosters = false,
            occurrence = 0,
            releaseReminderBadge = ReleaseReminderBadge.NEW_SEASON
        )

        assertTrue(card.isNewSeason)
        assertEquals(
            ReleaseReminderBadge.NEW_SEASON,
            card.releaseReminderBadge
        )
    }

    @Test
    fun nonStreamingOriginals_keepsExistingReturningSeasonBehavior() {
        val card = buildCatalogItem(
            item = returningSeason(),
            row = row(
                addonId = "example.other.addon",
                catalogId = "popular_series"
            ),
            useLandscapePosters = false,
            occurrence = 0,
            releaseReminderBadge = ReleaseReminderBadge.NEW_SEASON
        )

        assertTrue(card.isNewSeason)
        assertEquals(
            ReleaseReminderBadge.NEW_SEASON,
            card.releaseReminderBadge
        )
    }

    private fun row(
        addonId: String,
        catalogId: String
    ): CatalogRow =
        CatalogRow(
            addonId = addonId,
            addonName = "Test",
            addonBaseUrl = "https://example.test",
            catalogId = catalogId,
            catalogName = "Test Series",
            type = ContentType.SERIES,
            items = emptyList(),
            supportsSkip = true
        )

    private fun returningSeason(
        comingSoon: Boolean = false,
        upcomingSeason: Int? = null
    ): MetaPreview =
        MetaPreview(
            id = "tmdb:63247",
            type = ContentType.SERIES,
            name = "Westworld Canary",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "2016",
            imdbRating = null,
            genres = emptyList(),
            behaviorHints = MetaBehaviorHints(
                comingSoon = comingSoon,
                upcomingSeason = upcomingSeason,
                newSeason = true,
                newSeasonNumber = upcomingSeason ?: 4,
                newSeasonReleaseDate =
                    if (comingSoon) "2027-01-15" else "2022-06-26"
            )
        )

    private companion object {
        const val STREAMING_ORIGINALS =
            "community.streaming.originals.v6"
    }
}
