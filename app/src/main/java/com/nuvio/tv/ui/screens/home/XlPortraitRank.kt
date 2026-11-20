package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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

/** Only the rank crossing the featured edge fades; every other rank follows its card. */
@Composable
internal fun XlPortraitRank(index: Int, rowKey: String, track: XlRankTrack, origin: State<Float>,
    geometry: XlRowGeometry, numberStyle: NumberStyle, useThemeColor: Boolean) {
    val density = LocalDensity.current
    val featuredRight = track.featuredLeft + geometry.featuredWidth
    val clear by remember(index, track, origin, featuredRight) { derivedStateOf {
        track.numberLeft(index, track.portraitLeftAtOrigin(index, origin.value, geometry.featuredWidth)) >= featuredRight + 10f
    } }
    val opacity = remember { Animatable(if (clear) 1f else 0f) }
    val parkedX = remember { floatArrayOf(0f) }
    var parking by remember { mutableStateOf(false) }
    LaunchedEffect(clear) {
        if (clear) {
            parking = false
            opacity.animateTo(1f, tween(70, easing = LinearEasing))
        } else if (opacity.value > 0f) {
            // Finish the short fade outside the artwork before relocating the
            // invisible glyph. Its lower overhang never travels below XL.
            val outside = (track.textWidths[index] - XL_RANK_OVERLAP_DP).coerceAtLeast(0f)
            parkedX[0] = maxOf(featuredRight + 10f + outside,
                track.portraitLeftAtOrigin(index, origin.value, geometry.featuredWidth))
            parking = true
            opacity.animateTo(0f, tween(60, easing = LinearEasing))
            parking = false
        }
    }
    Box(Modifier.absoluteOffset {
        val x = if (parking) parkedX[0] else track.portraitLeftAtOrigin(index, origin.value, geometry.featuredWidth)
        IntOffset(with(density) { x.dp.roundToPx() }, 0)
    }.testTag("xl-rank-$rowKey-$index")) {
        NumberedCatalogCardWrapper(index + 1, geometry.portraitWidth.dp, geometry.cardHeight.dp,
            preMeasuredTextWidth = track.textWidths[index].dp, numberStyle = numberStyle,
            useThemeColorForNumbers = useThemeColor, compactXl = true,
            compactXlFontSize = track.fontSize, numberOpacity = opacity.asState()) {
            Spacer(Modifier.width(geometry.portraitWidth.dp).height(geometry.cardHeight.dp))
        }
    }
}
