package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModernHomeTrailerPopupPolicyTest {

    @Test
    fun `rendered expanded trailer is preserved when options open`() {
        assertTrue(
            shouldPreserveExpandedTrailerForPopup(
                playTrailerInExpandedCard = true,
                trailerFirstFrameRendered = true
            )
        )
    }

    @Test
    fun `loading trailer is not treated as already playing`() {
        assertFalse(
            shouldPreserveExpandedTrailerForPopup(
                playTrailerInExpandedCard = true,
                trailerFirstFrameRendered = false
            )
        )
    }

    @Test
    fun `open popup suppresses autoplay when trailer was not playing`() {
        assertTrue(
            shouldSuppressFocusedPosterAutoplayForPopup(
                popupVisible = true,
                preservePlayingTrailer = false
            )
        )
    }

    @Test
    fun `open popup does not suppress an already playing trailer`() {
        assertFalse(
            shouldSuppressFocusedPosterAutoplayForPopup(
                popupVisible = true,
                preservePlayingTrailer = true
            )
        )
    }

    @Test
    fun `closed popup releases autoplay suppression`() {
        assertFalse(
            shouldSuppressFocusedPosterAutoplayForPopup(
                popupVisible = false,
                preservePlayingTrailer = false
            )
        )
    }
}
