package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalRatingPostPlayRaceTest {
    @Test
    fun `terminal rating reservation blocks post play reveal before provider check completes`() {
        val state = PlayerUiState(
            playbackEnded = true,
            manualEndActionRequested = true,
            naturalEndRatingCheckPending = true
        )

        assertTrue(state.blocksNaturalCompletion)
        assertTrue(state.blocksEndActionForRating)
    }

    @Test
    fun `post play remains blocked after rating destination takes ownership`() {
        val state = PlayerUiState(
            playbackEnded = true,
            manualEndActionRequested = true,
            naturalEndRatingCheckPending = false,
            showRatingOverlay = true,
            ratingOverlayDestination = RatingOverlayDestination.POST_PLAY
        )

        assertTrue(state.blocksEndActionForRating)
    }

    @Test
    fun `post play is unblocked after unavailable provider releases rating reservation`() {
        val state = PlayerUiState(
            playbackEnded = true,
            manualEndActionRequested = true,
            naturalEndRatingCheckPending = false,
            creditRatingPromptHandled = true,
            ratingOverlayDestination = null
        )

        assertFalse(state.blocksEndActionForRating)
    }
}