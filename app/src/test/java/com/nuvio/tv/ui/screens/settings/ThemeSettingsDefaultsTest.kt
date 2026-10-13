package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.domain.model.BadgeColorStyle
import com.nuvio.tv.domain.model.PosterStatusIconStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeSettingsDefaultsTest {

    @Test
    fun freshSettingsUseNuvioBadgesAndModernStatusIcons() {
        val state = ThemeSettingsUiState()

        assertEquals(
            BadgeColorStyle.NUVIO,
            state.selectedBadgeColorStyle
        )

        assertEquals(
            PosterStatusIconStyle.MODERN,
            state.selectedPosterStatusIconStyle
        )
    }
}
