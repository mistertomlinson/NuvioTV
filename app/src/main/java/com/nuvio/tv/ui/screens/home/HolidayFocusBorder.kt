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
 * Static holiday focus ring that reads like tiny Christmas lights rather than
 * broad stripes. The ring stays draw-only: no animation clock, no state loop,
 * no layout change and no real blur/RenderEffect.
 */
internal fun Modifier.holidaySegmentedFocusBorder(
    cornerRadius: Dp,
    segmentLength: Dp = 4.dp,
    gapLength: Dp = 2.dp,
    strokeWidth: Dp = 2.25.dp,
    glowWidth: Dp = 7.dp,
    outerGlowWidth: Dp = 11.dp
): Modifier = drawWithCache {
    val segmentPx = segmentLength.toPx().coerceAtLeast(1f)
    val gapPx = gapLength.toPx().coerceAtLeast(0.5f)
    val stepPx = segmentPx + gapPx
    val cyclePx = stepPx * HOLIDAY_FOCUS_COLORS.size
    val offPx = (cyclePx - segmentPx).coerceAtLeast(1f)
    val dashIntervals = floatArrayOf(segmentPx, offPx)

    val mainWidthPx = strokeWidth.toPx().coerceAtLeast(1f)
    val glowWidthPx = glowWidth.toPx().coerceAtLeast(mainWidthPx)
    val outerGlowWidthPx = outerGlowWidth.toPx().coerceAtLeast(glowWidthPx)

    // Center the main stroke half a stroke-width inside the bounds. Its outer
    // edge therefore lands directly on the poster edge instead of looking inset.
    // Glow uses the same path; the portion beyond the draw bounds clips naturally.
    val inset = mainWidthPx * 0.5f
    val borderSize = Size(
        width = (size.width - inset * 2f).coerceAtLeast(0f),
        height = (size.height - inset * 2f).coerceAtLeast(0f)
    )
    val radiusPx = (cornerRadius.toPx() - inset).coerceAtLeast(0f)
    val roundedCorner = CornerRadius(radiusPx, radiusPx)
    val topLeft = Offset(inset, inset)

    fun phaseFor(index: Int): Float = when (index) {
        0 -> 0f
        1 -> stepPx * 3f
        2 -> stepPx * 2f
        else -> stepPx
    }

    val mainStyles = Array(HOLIDAY_FOCUS_COLORS.size) { index ->
        Stroke(
            width = mainWidthPx,
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(
                dashIntervals,
                phaseFor(index)
            )
        )
    }

    val glowStyles = Array(HOLIDAY_FOCUS_COLORS.size) { index ->
        Stroke(
            width = glowWidthPx,
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(
                dashIntervals,
                phaseFor(index)
            )
        )
    }

    val outerGlowStyles = Array(HOLIDAY_FOCUS_COLORS.size) { index ->
        Stroke(
            width = outerGlowWidthPx,
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(
                dashIntervals,
                phaseFor(index)
            )
        )
    }

    onDrawWithContent {
        drawContent()

        // Two cheap low-alpha strokes make adjacent bulbs bleed softly into
        // one another, hiding hard color boundaries without a real blur pass.
        HOLIDAY_FOCUS_COLORS.forEachIndexed { index, color ->
            drawRoundRect(
                color = color.copy(alpha = 0.07f),
                topLeft = topLeft,
                size = borderSize,
                cornerRadius = roundedCorner,
                style = outerGlowStyles[index]
            )
        }

        HOLIDAY_FOCUS_COLORS.forEachIndexed { index, color ->
            drawRoundRect(
                color = color.copy(alpha = 0.22f),
                topLeft = topLeft,
                size = borderSize,
                cornerRadius = roundedCorner,
                style = glowStyles[index]
            )
        }

        HOLIDAY_FOCUS_COLORS.forEachIndexed { index, color ->
            drawRoundRect(
                color = color.copy(alpha = 0.96f),
                topLeft = topLeft,
                size = borderSize,
                cornerRadius = roundedCorner,
                style = mainStyles[index]
            )
        }
    }
}
