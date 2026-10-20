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
    Color(0xFFFF2B2B), // red
    Color(0xFF22C55E), // green
    Color(0xFF2F7DFF), // blue
    Color(0xFFFFE12B)  // yellow
)

/**
 * Static holiday focus ring for Home catalog posters.
 *
 * Deliberately draw-only: no animation clock, no state loop and no layout
 * change. The segments are intentionally large enough to stay visibly
 * red/green/blue/yellow at normal TV viewing distance instead of blending into
 * one pale line. A wider low-alpha copy underneath fakes a soft glow without
 * RenderEffect/blur.
 */
internal fun Modifier.holidaySegmentedFocusBorder(
    cornerRadius: Dp,
    segmentLength: Dp = 22.dp,
    strokeWidth: Dp = 3.dp,
    glowWidth: Dp = 7.dp
): Modifier = drawWithCache {
    val segmentPx = segmentLength.toPx().coerceAtLeast(1f)
    val mainWidthPx = strokeWidth.toPx().coerceAtLeast(1f)
    val glowWidthPx = glowWidth.toPx().coerceAtLeast(mainWidthPx)
    val dashIntervals = floatArrayOf(segmentPx, segmentPx * 3f)

    val inset = glowWidthPx * 0.5f
    val borderSize = Size(
        width = (size.width - inset * 2f).coerceAtLeast(0f),
        height = (size.height - inset * 2f).coerceAtLeast(0f)
    )
    val radiusPx = (cornerRadius.toPx() - inset).coerceAtLeast(0f)
    val roundedCorner = CornerRadius(radiusPx, radiusPx)
    val topLeft = Offset(inset, inset)

    val mainStyles = Array(HOLIDAY_FOCUS_COLORS.size) { index ->
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
                color = color.copy(alpha = 0.16f),
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
