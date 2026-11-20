package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/** Rank position and fade follow the same moving portrait, without a separate clock. */
@Composable
internal fun XlPortraitRank(index: Int, rowKey: String, track: XlRankTrack, origin: State<Float>,
    geometry: XlRowGeometry, numberStyle: NumberStyle, useThemeColor: Boolean) {
    val density = LocalDensity.current
    val featuredRightPx = with(density) { track.featuredLeft.dp.roundToPx() + geometry.featuredWidth.dp.roundToPx() }
    val featuredRight = featuredRightPx / density.density
    val opacity = remember(index, track, origin, featuredRight) { derivedStateOf {
        val right = track.portraitLeftAtOrigin(index, origin.value, geometry.featuredWidth) + geometry.portraitWidth
        ((right - featuredRight) / geometry.portraitWidth).coerceIn(0f, 1f)
    } }
    val edge = remember(index, track, origin, featuredRightPx, density) { derivedStateOf {
        val poster = track.portraitLeftAtOrigin(index, origin.value, geometry.featuredWidth)
        val posterPx = with(density) { poster.dp.roundToPx() }
        val outsidePx = with(density) { (track.textWidths[index] - XL_RANK_OVERLAP_DP).dp.roundToPx() }
        (featuredRightPx - posterPx + outsidePx) / density.density
    } }
    Box(Modifier.absoluteOffset {
        val x = track.portraitLeftAtOrigin(index, origin.value, geometry.featuredWidth)
        IntOffset(with(density) { x.dp.roundToPx() }, 0)
    }.testTag("xl-rank-$rowKey-$index")) {
        NumberedCatalogCardWrapper(index + 1, geometry.portraitWidth.dp, geometry.cardHeight.dp,
            preMeasuredTextWidth = track.textWidths[index].dp, numberStyle = numberStyle,
            useThemeColorForNumbers = useThemeColor, compactXl = true,
            compactXlFontSize = track.fontSize, numberOpacity = opacity, numberFadeEdge = edge) {
            Spacer(Modifier.width(geometry.portraitWidth.dp).height(geometry.cardHeight.dp))
        }
    }
}
