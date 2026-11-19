package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

/** Dims the backdrop, below row artwork, metadata, and platform controls. */
@Composable
internal fun ModernXlBackdropDimmer(alpha: State<Float>, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }.background(Color.Black))
}
