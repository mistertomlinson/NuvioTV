package com.nuvio.tv.ui.shared

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
fun SharedBackdropLayer(
    viewModel: SharedBackdropViewModel
) {
    val backdropUrl by viewModel.backdropUrl.collectAsState()
    val parallaxOffsetX by viewModel.parallaxOffsetX.collectAsState()
    val overscale by viewModel.overscale.collectAsState()
    val context = LocalContext.current

    val scale = if (overscale) 1.1f else 1.0f

    val imageRequest = remember(context, backdropUrl) {
        ImageRequest.Builder(context)
            .data(backdropUrl)
            .crossfade(false)
            .build()
    }

    AsyncImage(
        model = imageRequest,
        contentDescription = null,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = parallaxOffsetX
            },
        contentScale = ContentScale.Crop,
        alignment = Alignment.Center
    )
}
