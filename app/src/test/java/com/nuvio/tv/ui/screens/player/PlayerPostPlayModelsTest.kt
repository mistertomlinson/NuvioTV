package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPostPlayModelsTest {
    @Test
    fun `running analyzer suppresses fallback timing`() {
        val timing = CreditTimingUiState(status = CreditTimingStatus.RUNNING)

        assertEquals(false, authoritativeEndActionDecision(timing, positionMs = 9_999_999L))
    }

    @Test
    fun `running analyzer uses cross release estimate when available`() {
        val timing = CreditTimingUiState(
            status = CreditTimingStatus.RUNNING,
            creditsStartMs = 6_300_000L,
            finalCreditsStartMs = 6_700_000L,
            hasPostCreditScenes = true
        )

        assertFalse(authoritativeEndActionDecision(timing, 6_699_999L)!!)
        assertTrue(authoritativeEndActionDecision(timing, 6_700_000L)!!)
    }

    @Test
    fun `final credit boundary is used instead of initial credits`() {
        val timing = CreditTimingUiState(
            status = CreditTimingStatus.COMPLETE,
            creditsStartMs = 6_297_575L,
            finalCreditsStartMs = 6_695_575L,
            hasPostCreditScenes = true
        )

        assertFalse(authoritativeEndActionDecision(timing, 6_297_575L)!!)
        assertFalse(authoritativeEndActionDecision(timing, 6_695_574L)!!)
        assertTrue(authoritativeEndActionDecision(timing, 6_695_575L)!!)
    }

    @Test
    fun `fallback status delegates to legacy timing`() {
        val timing = CreditTimingUiState(status = CreditTimingStatus.FALLBACK)

        assertNull(authoritativeEndActionDecision(timing, positionMs = 1_000L))
    }

    @Test
    fun `failed exact analysis retains cross release estimate`() {
        val timing = CreditTimingUiState(
            status = CreditTimingStatus.FALLBACK,
            finalCreditsStartMs = 6_700_000L
        )

        assertFalse(authoritativeEndActionDecision(timing, 6_699_999L)!!)
        assertTrue(authoritativeEndActionDecision(timing, 6_700_000L)!!)
    }

    @Test
    fun `content key is stable across episode releases`() {
        val first = buildCreditAnalyzerContentKey(
            contentType = "series",
            contentId = "tmdb:123",
            videoId = "release-one",
            season = 1,
            episode = 4
        )
        val second = buildCreditAnalyzerContentKey(
            contentType = "tv",
            contentId = "TMDB:123",
            videoId = "release-two",
            season = 1,
            episode = 4
        )

        assertEquals(first, second)
        assertEquals(64, first?.length)
    }

    @Test
    fun `content key separates episodes`() {
        val first = buildCreditAnalyzerContentKey(
            contentType = "series",
            contentId = "tmdb:123",
            videoId = null,
            season = 1,
            episode = 4
        )
        val next = buildCreditAnalyzerContentKey(
            contentType = "series",
            contentId = "tmdb:123",
            videoId = null,
            season = 1,
            episode = 5
        )

        assertTrue(first != next)
    }

    @Test
    fun `credit rating prompt starts two seconds into final credits`() {
        val state = PlayerUiState(
            contentType = "movie",
            isRatingProviderConnected = true,
            creditTiming = CreditTimingUiState(
                status = CreditTimingStatus.COMPLETE,
                finalCreditsStartMs = 6_700_000L
            )
        )

        assertFalse(shouldShowCreditRatingPrompt(state, 6_699_999L))
        assertTrue(shouldShowCreditRatingPrompt(state, 6_700_000L))
    }

    @Test
    fun `credit rating prompt starts only on final episode of latest aired season`() {
        val episodes = listOf(episode(season = 1, number = 1), episode(season = 1, number = 2))
        val base = PlayerUiState(
            contentType = "series",
            currentSeason = 1,
            currentEpisode = 1,
            episodesAll = episodes,
            isRatingProviderConnected = true,
            creditTiming = CreditTimingUiState(
                status = CreditTimingStatus.INTRO_DB_AVAILABLE,
                finalCreditsStartMs = 2_400_000L
            )
        )

        assertFalse(shouldShowCreditRatingPrompt(base, 2_400_000L))
        assertTrue(
            shouldShowCreditRatingPrompt(
                base.copy(currentEpisode = 2),
                2_402_000L
            )
        )
    }

    @Test
    fun `older season finale is not rating eligible when a later season has aired`() {
        val episodes = listOf(
            episode(season = 1, number = 1),
            episode(season = 1, number = 2),
            episode(season = 2, number = 1)
        )

        assertFalse(
            isRatingPromptEligibleContent(
                contentType = "series",
                currentSeason = 1,
                currentEpisode = 2,
                episodes = episodes
            )
        )
    }

    @Test
    fun `future announced season does not block rating current completed season`() {
        val episodes = listOf(
            episode(season = 2, number = 1, released = "2026-01-01"),
            episode(season = 2, number = 2, released = "2026-01-08"),
            episode(season = 3, number = 1, released = "2099-01-01")
        )

        assertTrue(
            isRatingPromptEligibleContent(
                contentType = "series",
                currentSeason = 2,
                currentEpisode = 2,
                episodes = episodes
            )
        )
    }

    @Test
    fun `unfinished current season is not rating eligible`() {
        val episodes = listOf(
            episode(season = 2, number = 1, released = "2026-01-01"),
            episode(season = 2, number = 2, released = "2026-01-08"),
            episode(season = 2, number = 3, released = "2099-01-01")
        )

        assertFalse(
            isRatingPromptEligibleContent(
                contentType = "series",
                currentSeason = 2,
                currentEpisode = 2,
                episodes = episodes
            )
        )
    }

    @Test
    fun `handled credit rating prompt does not reopen`() {
        val state = PlayerUiState(
            contentType = "movie",
            isRatingProviderConnected = true,
            creditRatingPromptHandled = true,
            creditTiming = CreditTimingUiState(
                status = CreditTimingStatus.COMPLETE,
                finalCreditsStartMs = 6_700_000L
            )
        )

        assertFalse(shouldShowCreditRatingPrompt(state, 6_700_000L))
    }

    @Test
    fun `credit rating transition blocks natural completion and end actions`() {
        val state = PlayerUiState(
            ratingOverlayDestination = RatingOverlayDestination.POST_PLAY
        )

        assertTrue(state.blocksNaturalCompletion)
        assertTrue(state.blocksEndActionForRating)
    }

    @Test
    fun `manual near end exit starts post play for a movie`() {
        val state = PlayerUiState(contentType = "movie")

        assertFalse(shouldStartManualEndAction(state, 84_999L, 100_000L))
        assertTrue(shouldStartManualEndAction(state, 85_000L, 100_000L))
    }

    @Test
    fun `movie with known credits waits for the real credit boundary`() {
        val state = PlayerUiState(
            contentType = "movie",
            creditTiming = CreditTimingUiState(
                status = CreditTimingStatus.COMPLETE,
                creditsStartMs = 98_000L,
                finalCreditsStartMs = 99_000L
            )
        )

        assertFalse(shouldStartManualEndAction(state, 95_000L, 100_000L))
        assertTrue(shouldStartManualEndAction(state, 98_000L, 100_000L))
    }

    @Test
    fun `movie back during cached post credit timing starts post play`() {
        val state = PlayerUiState(
            contentType = "movie",
            creditTiming = CreditTimingUiState(
                status = CreditTimingStatus.RUNNING,
                creditsStartMs = 90_000L,
                finalCreditsStartMs = 99_000L,
                hasPostCreditScenes = true,
                postCreditScenes = listOf(
                    PostCreditSceneTiming(
                        startMs = 94_000L,
                        endMs = 96_000L
                    )
                )
            )
        )

        assertTrue(shouldStartManualEndAction(state, 95_000L, 100_000L))
    }

    @Test
    fun `handled rating still allows movie post play until recommendations are dismissed`() {
        val state = PlayerUiState(
            contentType = "movie",
            creditRatingPromptHandled = true
        )

        assertTrue(shouldStartManualEndAction(state, 95_000L, 100_000L))
        assertFalse(
            shouldStartManualEndAction(
                state.copy(postPlayRecommendationDismissed = true),
                95_000L,
                100_000L
            )
        )
    }

    @Test
    fun `manual near end exit does not reopen after return to video`() {
        val state = PlayerUiState(
            contentType = "movie",
            creditRatingPromptHandled = true,
            postPlayRecommendationDismissed = true
        )

        assertFalse(shouldStartManualEndAction(state, 95_000L, 100_000L))
    }

    @Test
    fun `ordinary episode back never enters manual end flow even when analyzer looks late`() {
        val state = PlayerUiState(
            contentType = "series",
            currentSeason = 1,
            currentEpisode = 1,
            nextEpisode = NextEpisodeInfo(
                videoId = "episode-1-2",
                season = 1,
                episode = 2,
                title = "Episode 2",
                thumbnail = null,
                overview = null,
                released = "2026-01-01",
                hasAired = true,
                unairedMessage = null
            ),
            creditTiming = CreditTimingUiState(
                status = CreditTimingStatus.COMPLETE,
                finalCreditsStartMs = 96_000L
            )
        )

        assertFalse(shouldStartManualEndAction(state, 84_999L, 100_000L))
        assertFalse(shouldStartManualEndAction(state, 95_000L, 100_000L))
        assertFalse(shouldStartManualEndAction(state, 96_000L, 100_000L))
    }

    @Test
    fun `ordinary episode near end does not override without a late analyzer timestamp`() {
        val base = PlayerUiState(
            contentType = "series",
            currentSeason = 1,
            currentEpisode = 1,
            nextEpisode = NextEpisodeInfo(
                videoId = "episode-1-2",
                season = 1,
                episode = 2,
                title = "Episode 2",
                thumbnail = null,
                overview = null,
                released = "2026-01-01",
                hasAired = true,
                unairedMessage = null
            )
        )

        assertFalse(shouldStartManualEndAction(base, 95_000L, 100_000L))

        assertFalse(
            shouldStartManualEndAction(
                base.copy(
                    creditTiming = CreditTimingUiState(
                        status = CreditTimingStatus.COMPLETE,
                        finalCreditsStartMs = 94_000L
                    )
                ),
                95_000L,
                100_000L
            )
        )
    }

    @Test
    fun `manual near end exit is limited to final season episode`() {
        val episodes = listOf(episode(season = 1, number = 1), episode(season = 1, number = 2))
        val state = PlayerUiState(
            contentType = "series",
            currentSeason = 1,
            currentEpisode = 1,
            episodesAll = episodes
        )

        assertFalse(shouldStartManualEndAction(state, 95_000L, 100_000L))
        assertTrue(
            shouldStartManualEndAction(
                state.copy(currentEpisode = 2),
                95_000L,
                100_000L
            )
        )
    }

    @Test
    fun `series finale introdb timestamp overrides 85 percent fallback`() {
        val episodes = listOf(
            episode(season = 1, number = 1),
            episode(season = 1, number = 2)
        )
        val state = PlayerUiState(
            contentType = "series",
            currentSeason = 1,
            currentEpisode = 2,
            episodesAll = episodes,
            creditTiming = CreditTimingUiState(
                status = CreditTimingStatus.INTRO_DB_AVAILABLE,
                creditsStartMs = 96_000L,
                finalCreditsStartMs = 96_000L
            )
        )

        assertFalse(shouldStartManualEndAction(state, 95_000L, 100_000L))
        assertTrue(shouldStartManualEndAction(state, 96_000L, 100_000L))
    }

    @Test
    fun `series finale completed analyzer timestamp overrides 85 percent fallback`() {
        val episodes = listOf(
            episode(season = 1, number = 1),
            episode(season = 1, number = 2)
        )
        val state = PlayerUiState(
            contentType = "series",
            currentSeason = 1,
            currentEpisode = 2,
            episodesAll = episodes,
            creditTiming = CreditTimingUiState(
                status = CreditTimingStatus.COMPLETE,
                creditsStartMs = 90_000L,
                finalCreditsStartMs = 96_000L
            )
        )

        // Initial credits are not enough for a finale rating flow when
        // final-credit timing protects later post-credit material.
        assertFalse(shouldStartManualEndAction(state, 95_000L, 100_000L))
        assertTrue(shouldStartManualEndAction(state, 96_000L, 100_000L))
    }

    @Test
    fun `series finale running analyzer uses cached timing instead of 85 percent`() {
        val episodes = listOf(
            episode(season = 1, number = 1),
            episode(season = 1, number = 2)
        )
        val state = PlayerUiState(
            contentType = "series",
            currentSeason = 1,
            currentEpisode = 2,
            episodesAll = episodes,
            creditTiming = CreditTimingUiState(
                status = CreditTimingStatus.RUNNING,
                creditsStartMs = 90_000L,
                finalCreditsStartMs = 96_000L
            )
        )

        assertFalse(shouldStartManualEndAction(state, 95_000L, 100_000L))
        assertTrue(shouldStartManualEndAction(state, 96_000L, 100_000L))
    }

    @Test
    fun `series finale running analyzer without timing falls back to 85 percent`() {
        val episodes = listOf(
            episode(season = 1, number = 1),
            episode(season = 1, number = 2)
        )
        val state = PlayerUiState(
            contentType = "series",
            currentSeason = 1,
            currentEpisode = 2,
            episodesAll = episodes,
            creditTiming = CreditTimingUiState(
                status = CreditTimingStatus.RUNNING
            )
        )

        assertFalse(shouldStartManualEndAction(state, 84_999L, 100_000L))
        assertTrue(shouldStartManualEndAction(state, 85_000L, 100_000L))
    }

    @Test
    fun `series finale analyzer failure without timing falls back to 85 percent`() {
        val episodes = listOf(
            episode(season = 1, number = 1),
            episode(season = 1, number = 2)
        )
        val state = PlayerUiState(
            contentType = "series",
            currentSeason = 1,
            currentEpisode = 2,
            episodesAll = episodes,
            creditTiming = CreditTimingUiState(
                status = CreditTimingStatus.FALLBACK
            )
        )

        assertFalse(shouldStartManualEndAction(state, 84_999L, 100_000L))
        assertTrue(shouldStartManualEndAction(state, 85_000L, 100_000L))
    }

    @Test
    fun `completed timing without a timestamp does not silently use 85 percent`() {
        val episodes = listOf(
            episode(season = 1, number = 1),
            episode(season = 1, number = 2)
        )
        val state = PlayerUiState(
            contentType = "series",
            currentSeason = 1,
            currentEpisode = 2,
            episodesAll = episodes,
            creditTiming = CreditTimingUiState(
                status = CreditTimingStatus.COMPLETE
            )
        )

        assertFalse(shouldStartManualEndAction(state, 95_000L, 100_000L))
    }

    @Test
    fun `explicit next up play skips automatic countdown`() {
        assertTrue(shouldRunNextEpisodeAutoPlayCountdown(userInitiated = false))
        assertFalse(shouldRunNextEpisodeAutoPlayCountdown(userInitiated = true))
    }

    private fun episode(
        season: Int,
        number: Int,
        released: String? = null
    ): Video = Video(
        id = "episode-$season-$number",
        title = "Episode $number",
        released = released,
        thumbnail = null,
        season = season,
        episode = number,
        overview = null
    )
}
