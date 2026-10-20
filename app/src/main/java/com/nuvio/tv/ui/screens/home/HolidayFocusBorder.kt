package com.nuvio.tv.ui.screens.home

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val HOLIDAY_FOCUS_COLORS = arrayOf(
    Color(0xFFE53935), // red
    Color(0xFF43A047), // green
    Color(0xFF1E88E5), // blue
    Color(0xFFFDD835)  // yellow
)

/**
 * Static holiday focus ring for Home catalog posters.
 *
 * This is deliberately draw-only: no animation clock, no state loop and no
 * layout change. Only the focused poster receives the modifier. Four cached
 * dashed strokes form repeating red/green/blue/yellow segments; a wider,
 * low-alpha copy underneath fakes a soft glow without RenderEffect/blur.
 */
internal fun Modifier.holidaySegmentedFocusBorder(
    cornerRadius: Dp,
    segmentLength: Dp = 9.dp,
    strokeWidth: Dp = 2.dp,
    glowWidth: Dp = 5.dp
): Modifier = drawWithCache {
    val segmentPx = segmentLength.toPx().coerceAtLeast(1f)
    val mainWidthPx = strokeWidth.toPx().coerceAtLeast(1f)
    val glowWidthPx = glowWidth.toPx().coerceAtLeast(mainWidthPx)
    val dashIntervals = floatArrayOf(segmentPx, segmentPx * 3f)

    // Keep the wider glow inside the card's draw bounds so no offscreen layer
    // or clipping workaround is needed.
    val inset = glowWidthPx * 0.5f
    val borderSize = Size(
        width = (size.width - inset * 2f).coerceAtLeast(0f),
        height = (size.height - inset * 2f).coerceAtLeast(0f)
    )
    val radiusPx = (cornerRadius.toPx() - inset).coerceAtLeast(0f)
    val roundedCorner = CornerRadius(radiusPx, radiusPx)
    val topLeft = Offset(inset, inset)

    val mainStyles = Array(HOLIDAY_FOCUS_COLORS.size) { index ->
        // Android's dash phase advances into the pattern. These phases make
        // consecutive path segments read red -> green -> blue -> yellow.
        val phase = when (index) {
            0 -> 0f
            1 -> segmentPx * 3f
            2 -> segmentPx * 2f
            else -> segmentPx
        }
        Stroke(
            width = mainWidthPx,
            cap = StrokeCap.Butt,
            pathEffect = PathEffect.dashPathEffect(dashIntervals, phase)
        )
    }
    val glowStyles = Array(HOLIDAY_FOCUS_COLORS.size) { index ->
        val phase = when (index) {
            0 -> 0f
            1 -> segmentPx * 3f
            2 -> segmentPx * 2f
            else -> segmentPx
        }
        Stroke(
            width = glowWidthPx,
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(dashIntervals, phase)
        )
    }

    onDrawWithContent {
        drawContent()

        HOLIDAY_FOCUS_COLORS.forEachIndexed { index, color ->
            drawRoundRect(
                color = color.copy(alpha = 0.18f),
                topLeft = topLeft,
                size = borderSize,
                cornerRadius = roundedCorner,
                style = glowStyles[index]
            )
        }
        HOLIDAY_FOCUS_COLORS.forEachIndexed { index, color ->
            drawRoundRect(
                color = color,
                topLeft = topLeft,
                size = borderSize,
                cornerRadius = roundedCorner,
                style = mainStyles[index]
            )
        }
    }
}
