package com.nuvio.tv.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object NuvioGradients {
    private val HomePurple = Color(0xFF9B5FE0)
    private val HomePurpleMid = Color(0xFF6A3FD4)
    private val HomeCyan = Color(0xFF00C8C8)

    val HomeFocus: Brush = Brush.radialGradient(
        colors = listOf(
            HomePurple,
            HomePurpleMid,
            HomeCyan
        ),
        center = Offset.Zero,
        radius = 120f
    )

    val ReleaseStatusBadge: Brush = Brush.radialGradient(
        colors = listOf(
            HomePurple.copy(alpha = 0.8f),
            HomePurpleMid.copy(alpha = 0.8f),
            HomeCyan.copy(alpha = 0.8f)
        ),
        center = Offset.Zero,
        radius = 120f
    )
}
