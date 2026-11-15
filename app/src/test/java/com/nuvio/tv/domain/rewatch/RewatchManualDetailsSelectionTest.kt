package com.nuvio.tv.domain.rewatch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewatchManualDetailsSelectionTest {
    private val selected = RewatchEpisode(5, 19)
    private val series = "tt0386676"

    @Test fun detailsClickIsSingleUseAndProfileScoped() {
        selectEpisode()
        assertFalse(RewatchManualDetailsSelection.consume(
            1, series, selected, 11_000L
        ))
        assertFalse(RewatchManualDetailsSelection.consume(
            2, series, RewatchEpisode(5, 18), 11_000L
        ))
        assertTrue(RewatchManualDetailsSelection.consume(
            2, series, selected, 11_000L
        ))
        assertFalse(RewatchManualDetailsSelection.consume(
            2, series, selected, 11_000L
        ))
    }

    @Test fun expiredProofNeverAuthorizesAutoplay() {
        selectEpisode(nowMs = 20_000L)
        assertFalse(RewatchManualDetailsSelection.consume(
            2, series, selected, 141_000L
        ))
    }

    @Test fun heroResumeDoesNotAuthorizeReposition() {
        requestHeroResume()
        assertFalse(consume())
    }

    @Test fun heroResumeClearsAbandonedEpisodeClickForTheSameTarget() {
        selectEpisode()
        requestHeroResume()
        assertFalse(consume())
    }

    @Test fun heroResumeClearsAbandonedClickForADifferentTarget() {
        selectEpisode()
        requestHeroResume(RewatchEpisode(5, 22))
        assertFalse(consume())
    }

    @Test fun movieOrMissingEpisodeCannotLeaveAnOldSelectionArmed() {
        selectEpisode()
        requestHeroResume(null)
        assertFalse(consume())
        selectEpisode()
        selectEpisode(episode = null)
        assertFalse(consume())
    }

    @Test fun explicitEpisodeClickAfterHeroResumeStillAuthorizesOneMove() {
        requestHeroResume()
        selectEpisode()
        assertTrue(consume())
        assertFalse(consume())
    }

    private fun selectEpisode(
        episode: RewatchEpisode? = selected,
        nowMs: Long = 10_000L
    ) = RewatchManualDetailsSelection.onPlaybackRequested(
        2, series, episode, RewatchDetailsPlaybackOrigin.EPISODE_LIST, nowMs
    )

    private fun requestHeroResume(episode: RewatchEpisode? = selected) =
        RewatchManualDetailsSelection.onPlaybackRequested(
            2, series, episode, RewatchDetailsPlaybackOrigin.HERO_BUTTON, 10_500L
        )

    private fun consume() = RewatchManualDetailsSelection.consume(
        2, series, selected, 11_000L
    )
}
