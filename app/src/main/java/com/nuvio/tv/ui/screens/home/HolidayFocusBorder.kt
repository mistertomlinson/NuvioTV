package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.ui.screens.settings.SeasonalEffectsViewModel
import com.nuvio.tv.ui.theme.NuvioColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val HOLIDAY_FOCUS_COLORS =
    arrayOf(
        Color(0xFFFF2B2B),
        Color(0xFF22C55E),
        Color(0xFF2F7DFF),
        Color(0xFFFFE12B)
    )

/*
 * Static Christmas-light focus treatment.
 *
 * Return to the round bulbs that read better from couch distance, but retain
 * the broader/lower-alpha glow from the later treatment. Bulbs are still
 * batched into four cached paths, so the whole focus treatment remains only
 * 12 path draws: broad haze + inner glow + soft core for each color.
 *
 * Holiday Effects OFF immediately restores the normal Nuvio focus ring.
 */
@Composable
internal fun Modifier.holidaySegmentedFocusBorder(
    cornerRadius: Dp,
    bulbSpacing: Dp = 7.dp,
    coreRadius: Dp = 1.65.dp,
    middleGlowRadius: Dp = 3.1.dp,
    outerGlowRadius: Dp = 5.4.dp
): Modifier {
    val seasonalEffectsViewModel: SeasonalEffectsViewModel = hiltViewModel()
    val config by seasonalEffectsViewModel.config.collectAsStateWithLifecycle()

    if (!config.enabled) {
        return border(
            width = 2.dp,
            color = NuvioColors.FocusRing,
            shape = RoundedCornerShape(cornerRadius)
        )
    }

    return drawWithCache {
        val core = coreRadius.toPx().coerceAtLeast(0.75f)
        val middle = middleGlowRadius.toPx().coerceAtLeast(core)
        val outer = outerGlowRadius.toPx().coerceAtLeast(middle)
        val spacing = bulbSpacing.toPx().coerceAtLeast(core * 2f + 1f)

        /* Keep the bulb centers on the visible card edge. */
        val edgeInset = core
        val left = edgeInset
        val top = edgeInset
        val right = (size.width - edgeInset).coerceAtLeast(left)
        val bottom = (size.height - edgeInset).coerceAtLeast(top)
        val width = right - left
        val height = bottom - top

        val requestedRadius =
            (cornerRadius.toPx() - edgeInset).coerceAtLeast(0f)
        val radius =
            requestedRadius.coerceAtMost(
                minOf(width * 0.5f, height * 0.5f)
            )

        val straightHorizontal =
            (width - radius * 2f).coerceAtLeast(0f)
        val straightVertical =
            (height - radius * 2f).coerceAtLeast(0f)
        val quarterArc = radius * PI.toFloat() * 0.5f
        val perimeter =
            straightHorizontal * 2f +
                straightVertical * 2f +
                quarterArc * 4f

        fun rectangularPoint(rawDistance: Float): Offset {
            val rectanglePerimeter = width * 2f + height * 2f
            if (rectanglePerimeter <= 0f) return Offset(left, top)

            var d =
                ((rawDistance % rectanglePerimeter) + rectanglePerimeter) %
                    rectanglePerimeter

            if (d <= width) return Offset(left + d, top)
            d -= width
            if (d <= height) return Offset(right, top + d)
            d -= height
            if (d <= width) return Offset(right - d, bottom)
            d -= width
            return Offset(left, bottom - d)
        }

        fun roundedPoint(rawDistance: Float): Offset {
            if (radius <= 0.01f || perimeter <= 0f) {
                return rectangularPoint(rawDistance)
            }

            var d = ((rawDistance % perimeter) + perimeter) % perimeter

            if (d <= straightHorizontal) {
                return Offset(left + radius + d, top)
            }
            d -= straightHorizontal

            if (d <= quarterArc) {
                val angle = -PI.toFloat() * 0.5f + d / radius
                return Offset(
                    right - radius + cos(angle) * radius,
                    top + radius + sin(angle) * radius
                )
            }
            d -= quarterArc

            if (d <= straightVertical) {
                return Offset(right, top + radius + d)
            }
            d -= straightVertical

            if (d <= quarterArc) {
                val angle = d / radius
                return Offset(
                    right - radius + cos(angle) * radius,
                    bottom - radius + sin(angle) * radius
                )
            }
            d -= quarterArc

            if (d <= straightHorizontal) {
                return Offset(right - radius - d, bottom)
            }
            d -= straightHorizontal

            if (d <= quarterArc) {
                val angle = PI.toFloat() * 0.5f + d / radius
                return Offset(
                    left + radius + cos(angle) * radius,
                    bottom - radius + sin(angle) * radius
                )
            }
            d -= quarterArc

            if (d <= straightVertical) {
                return Offset(left, bottom - radius - d)
            }
            d -= straightVertical

            val angle = PI.toFloat() + d / radius
            return Offset(
                left + radius + cos(angle) * radius,
                top + radius + sin(angle) * radius
            )
        }

        val bulbCount =
            (perimeter / spacing).roundToInt().coerceAtLeast(4)
        val actualSpacing =
            if (bulbCount > 0) perimeter / bulbCount else spacing

        val outerPaths = Array(HOLIDAY_FOCUS_COLORS.size) { Path() }
        val middlePaths = Array(HOLIDAY_FOCUS_COLORS.size) { Path() }
        val corePaths = Array(HOLIDAY_FOCUS_COLORS.size) { Path() }

        repeat(bulbCount) { index ->
            val center = roundedPoint(index * actualSpacing)
            val colorIndex = index % HOLIDAY_FOCUS_COLORS.size

            outerPaths[colorIndex].addOval(
                Rect(
                    left = center.x - outer,
                    top = center.y - outer,
                    right = center.x + outer,
                    bottom = center.y + outer
                )
            )
            middlePaths[colorIndex].addOval(
                Rect(
                    left = center.x - middle,
                    top = center.y - middle,
                    right = center.x + middle,
                    bottom = center.y + middle
                )
            )
            corePaths[colorIndex].addOval(
                Rect(
                    left = center.x - core,
                    top = center.y - core,
                    right = center.x + core,
                    bottom = center.y + core
                )
            )
        }

        onDrawWithContent {
            drawContent()

            HOLIDAY_FOCUS_COLORS.forEachIndexed { index, color ->
                drawPath(
                    path = outerPaths[index],
                    color = color.copy(alpha = 0.045f)
                )
            }

            HOLIDAY_FOCUS_COLORS.forEachIndexed { index, color ->
                drawPath(
                    path = middlePaths[index],
                    color = color.copy(alpha = 0.14f)
                )
            }

            HOLIDAY_FOCUS_COLORS.forEachIndexed { index, color ->
                drawPath(
                    path = corePaths[index],
                    color = color.copy(alpha = 0.38f)
                )
            }
        }
    }
}
