package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.sqrt

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
 * The bulbs are tiny rounded line segments rather than circles or wide dashed
 * stripes. Geometry is cached into four paths (one per color), so rendering
 * costs only 12 path draws total: broad haze + inner glow + soft core for each
 * color. The wide low-alpha passes deliberately overlap so adjacent colors
 * blend instead of exposing hard red/green/blue/yellow boundaries.
 *
 * Seasonal state is observed only while a visible focus outline owns this
 * modifier. Holiday Effects OFF immediately restores the exact normal Nuvio
 * focus ring instead of leaving seasonal colors behind.
 */
@Composable
internal fun Modifier.holidaySegmentedFocusBorder(
    cornerRadius: Dp,
    bulbSpacing: Dp = 7.dp,
    bulbLength: Dp = 3.2.dp,
    coreWidth: Dp = 1.8.dp,
    middleGlowWidth: Dp = 5.6.dp,
    outerGlowWidth: Dp = 10.dp
): Modifier {
    val seasonalEffectsViewModel: SeasonalEffectsViewModel =
        hiltViewModel()
    val config by
        seasonalEffectsViewModel.config.collectAsStateWithLifecycle()

    if (!config.enabled) {
        return border(
            width = 2.dp,
            color = NuvioColors.FocusRing,
            shape = RoundedCornerShape(cornerRadius)
        )
    }

    return drawWithCache {
        val coreStroke = coreWidth.toPx().coerceAtLeast(0.75f)
        val middleStroke = middleGlowWidth.toPx().coerceAtLeast(coreStroke)
        val outerStroke = outerGlowWidth.toPx().coerceAtLeast(middleStroke)
        val halfBulbLength = bulbLength.toPx().coerceAtLeast(1f) * 0.5f
        val spacing = bulbSpacing.toPx().coerceAtLeast(halfBulbLength * 2f + 1f)

        /*
         * Keep the colored core on the visible card edge. Glow is allowed to
         * spread inward/outward naturally rather than recessing the ring.
         */
        val edgeInset = coreStroke * 0.5f
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

            var d =
                ((rawDistance % perimeter) + perimeter) % perimeter

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
        val tangentProbe =
            minOf(actualSpacing * 0.18f, 1.5f).coerceAtLeast(0.5f)

        val colorPaths =
            Array(HOLIDAY_FOCUS_COLORS.size) { Path() }

        repeat(bulbCount) { index ->
            val distance = index * actualSpacing
            val center = roundedPoint(distance)
            val ahead = roundedPoint(distance + tangentProbe)
            val dx = ahead.x - center.x
            val dy = ahead.y - center.y
            val length = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
            val tx = dx / length
            val ty = dy / length
            val start = Offset(
                center.x - tx * halfBulbLength,
                center.y - ty * halfBulbLength
            )
            val end = Offset(
                center.x + tx * halfBulbLength,
                center.y + ty * halfBulbLength
            )

            colorPaths[index % colorPaths.size].apply {
                moveTo(start.x, start.y)
                lineTo(end.x, end.y)
            }
        }

        val outerStyle = Stroke(
            width = outerStroke,
            cap = StrokeCap.Round
        )
        val middleStyle = Stroke(
            width = middleStroke,
            cap = StrokeCap.Round
        )
        val coreStyle = Stroke(
            width = coreStroke,
            cap = StrokeCap.Round
        )

        onDrawWithContent {
            drawContent()

            HOLIDAY_FOCUS_COLORS.forEachIndexed { index, color ->
                drawPath(
                    path = colorPaths[index],
                    color = color.copy(alpha = 0.045f),
                    style = outerStyle
                )
            }

            HOLIDAY_FOCUS_COLORS.forEachIndexed { index, color ->
                drawPath(
                    path = colorPaths[index],
                    color = color.copy(alpha = 0.14f),
                    style = middleStyle
                )
            }

            HOLIDAY_FOCUS_COLORS.forEachIndexed { index, color ->
                drawPath(
                    path = colorPaths[index],
                    color = color.copy(alpha = 0.38f),
                    style = coreStyle
                )
            }
        }
    }
}
