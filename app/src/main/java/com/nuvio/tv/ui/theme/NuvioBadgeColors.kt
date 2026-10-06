package com.nuvio.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import com.nuvio.tv.domain.model.BadgeColorStyle

internal enum class NuvioBadgeSemantic {
    STANDARD,
    NEW_EPISODE,
    NEW_SEASON,
    AVAILABLE_NOW
}

private val LegacyNewEpisodeBadge = Color(0xFF1D4ED8)
private val LegacyNewSeasonBadge = Color(0xFFB45309)
private val LegacyAvailableNowBadge = Color(0xFFB91C1C)

@Composable
@ReadOnlyComposable
internal fun nuvioBadgeBrush(semantic: NuvioBadgeSemantic): Brush {
    val background = NuvioTheme.colors.Background

    return when (NuvioTheme.badgeColorStyle) {
        BadgeColorStyle.NUVIO -> when (semantic) {
            NuvioBadgeSemantic.STANDARD ->
                SolidColor(background.copy(alpha = 0.8f))

            NuvioBadgeSemantic.NEW_EPISODE,
            NuvioBadgeSemantic.NEW_SEASON,
            NuvioBadgeSemantic.AVAILABLE_NOW ->
                NuvioGradients.ReleaseStatusBadge
        }

        BadgeColorStyle.LEGACY -> when (semantic) {
            NuvioBadgeSemantic.STANDARD ->
                SolidColor(background.copy(alpha = 0.8f))

            NuvioBadgeSemantic.NEW_EPISODE ->
                SolidColor(LegacyNewEpisodeBadge)

            NuvioBadgeSemantic.NEW_SEASON ->
                SolidColor(LegacyNewSeasonBadge)

            NuvioBadgeSemantic.AVAILABLE_NOW ->
                SolidColor(LegacyAvailableNowBadge)
        }
    }
}
