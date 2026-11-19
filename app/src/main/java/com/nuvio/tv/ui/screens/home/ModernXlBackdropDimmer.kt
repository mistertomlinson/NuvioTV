package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

/** Dims the backdrop, below row artwork, metadata, and platform controls. */
@Composable
internal fun ModernXlBackdropDimmer(active: Boolean, modifier: Modifier = Modifier) {
    val alpha = animateFloatAsState(
        targetValue = if (active) XL_BACKDROP_DIM_ALPHA else 0f,
        animationSpec = tween(280),
        label = "xlBackdropDim"
    )
    Box(modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }.background(Color.Black))
}
