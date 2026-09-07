package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.MDBListRatings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostPlayRecommendationPresentationTest {
    @Test
    fun `resolved recommendation with logo and scores is presentation ready`() {
        val recommendation = recommendation(
            logo = "https://image.test/logo.png",
            ratings = MDBListRatings(imdb = 7.4),
            resolved = true
        )

        assertTrue(recommendation.isPresentationReady())
    }

    @Test
    fun `recommendation without logo is not presentation ready`() {
        assertFalse(
            recommendation(
                logo = null,
                ratings = MDBListRatings(imdb = 7.4),
                resolved = true
            ).isPresentationReady()
        )
    }

    @Test
    fun `recommendation without scores remains presentation ready`() {
        assertTrue(
            recommendation(
                logo = "https://image.test/logo.png",
                ratings = null,
                resolved = true
            ).isPresentationReady()
        )
    }

    private fun recommendation(
        logo: String?,
        ratings: MDBListRatings?,
        resolved: Boolean
    ) = PostPlayRecommendation(
        id = "tmdb:123",
        contentType = "movie",
        title = "Recommendation",
        backdrop = "https://image.test/backdrop.jpg",
        poster = null,
        logo = logo,
        description = "Description",
        releaseInfo = "2026",
        genres = listOf("Drama"),
        mdbListRatings = ratings,
        metadataResolved = resolved
    )
}
