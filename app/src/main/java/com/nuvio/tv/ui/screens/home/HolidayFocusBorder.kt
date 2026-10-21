package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
 * Tiny static Christmas bulbs around the poster edge.
 *
 * No blur filter and no animation. Each bulb is three cached circle draws:
 * broad faint haze, tighter glow, soft colored core.
 *
 * This avoids the clearly-defined boundaries of colored dash segments.
 *
 * The seasonal config is observed only while a poster owns the visible focus
 * outline. That keeps the normal Home rows free of per-card settings
 * collectors while allowing the original focus ring to return immediately
 * when Holiday Effects is disabled.
 */
@Composable
internal fun Modifier.holidaySegmentedFocusBorder(
    cornerRadius: Dp,
    bulbSpacing: Dp = 6.dp,
    coreRadius: Dp = 1.15.dp,
    middleGlowRadius: Dp = 2.55.dp,
    outerGlowRadius: Dp = 4.6.dp
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
        val core =
            coreRadius.toPx()
                .coerceAtLeast(0.5f)

        val middle =
            middleGlowRadius.toPx()
                .coerceAtLeast(core)

        val outer =
            outerGlowRadius.toPx()
                .coerceAtLeast(middle)

        val spacing =
            bulbSpacing.toPx()
                .coerceAtLeast(
                    core * 2f +
                        1f
                )

        /*
         * Core touches the actual component edge rather than being inset like
         * the previous wide stroke. Glow extends around that edge naturally.
         */
        val left = core
        val top = core
        val right =
            (size.width - core)
                .coerceAtLeast(left)

        val bottom =
            (size.height - core)
                .coerceAtLeast(top)

        val width =
            right - left

        val height =
            bottom - top

        val requestedRadius =
            (
                cornerRadius.toPx() -
                    core
                ).coerceAtLeast(0f)

        val radius =
            requestedRadius
                .coerceAtMost(
                    minOf(
                        width / 2f,
                        height / 2f
                    )
                )

        val straightHorizontal =
            (
                width -
                    radius * 2f
                ).coerceAtLeast(0f)

        val straightVertical =
            (
                height -
                    radius * 2f
                ).coerceAtLeast(0f)

        val quarterArc =
            radius *
                PI.toFloat() /
                2f

        val perimeter =
            straightHorizontal * 2f +
                straightVertical * 2f +
                quarterArc * 4f

        fun rectangularPoint(
            rawDistance: Float
        ): Offset {
            val rectanglePerimeter =
                width * 2f +
                    height * 2f

            if (
                rectanglePerimeter <=
                0f
            ) {
                return Offset(
                    left,
                    top
                )
            }

            var d =
                (
                    rawDistance %
                        rectanglePerimeter +
                        rectanglePerimeter
                    ) %
                    rectanglePerimeter

            if (d <= width) {
                return Offset(
                    left + d,
                    top
                )
            }

            d -= width

            if (d <= height) {
                return Offset(
                    right,
                    top + d
                )
            }

            d -= height

            if (d <= width) {
                return Offset(
                    right - d,
                    bottom
                )
            }

            d -= width

            return Offset(
                left,
                bottom - d
            )
        }

        fun roundedPoint(
            rawDistance: Float
        ): Offset {
            if (
                radius <= 0.01f ||
                perimeter <= 0f
            ) {
                return rectangularPoint(
                    rawDistance
                )
            }

            var d =
                (
                    rawDistance %
                        perimeter +
                        perimeter
                    ) %
                    perimeter

            // Top
            if (
                d <=
                straightHorizontal
            ) {
                return Offset(
                    left +
                        radius +
                        d,
                    top
                )
            }

            d -=
                straightHorizontal

            // Top-right
            if (d <= quarterArc) {
                val angle =
                    -PI.toFloat() /
                        2f +
                        d /
                        radius

                return Offset(
                    right -
                        radius +
                        cos(angle) *
                        radius,
                    top +
                        radius +
                        sin(angle) *
                        radius
                )
            }

            d -= quarterArc

            // Right
            if (
                d <=
                straightVertical
            ) {
                return Offset(
                    right,
                    top +
                        radius +
                        d
                )
            }

            d -=
                straightVertical

            // Bottom-right
            if (d <= quarterArc) {
                val angle =
                    d /
                        radius

                return Offset(
                    right -
                        radius +
                        cos(angle) *
                        radius,
                    bottom -
                        radius +
                        sin(angle) *
                        radius
                )
            }

            d -= quarterArc

            // Bottom
            if (
                d <=
                straightHorizontal
            ) {
                return Offset(
                    right -
                        radius -
                        d,
                    bottom
                )
            }

            d -=
                straightHorizontal

            // Bottom-left
            if (d <= quarterArc) {
                val angle =
                    PI.toFloat() /
                        2f +
                        d /
                        radius

                return Offset(
                    left +
                        radius +
                        cos(angle) *
                        radius,
                    bottom -
                        radius +
                        sin(angle) *
                        radius
                )
            }

            d -= quarterArc

            // Left
            if (
                d <=
                straightVertical
            ) {
                return Offset(
                    left,
                    bottom -
                        radius -
                        d
                )
            }

            d -=
                straightVertical

            // Top-left
            val angle =
                PI.toFloat() +
                    d /
                    radius

            return Offset(
                left +
                    radius +
                    cos(angle) *
                    radius,
                top +
                    radius +
                    sin(angle) *
                    radius
            )
        }

        val bulbCount =
            (
                perimeter /
                    spacing
                ).roundToInt()
                .coerceAtLeast(4)

        val actualSpacing =
            if (bulbCount > 0) {
                perimeter /
                    bulbCount
            } else {
                spacing
            }

        val bulbPositions =
            Array(bulbCount) { index ->
                roundedPoint(
                    index *
                        actualSpacing
                )
            }

        onDrawWithContent {
            drawContent()

            /*
             * Broad haze. Adjacent colors blend here, so the eye doesn't see
             * hard red/green/blue/yellow boundaries.
             */
            bulbPositions
                .forEachIndexed {
                    index,
                    position ->

                    drawCircle(
                        color =
                            HOLIDAY_FOCUS_COLORS[
                                index %
                                    HOLIDAY_FOCUS_COLORS.size
                            ].copy(
                                alpha = 0.075f
                            ),
                        radius = outer,
                        center = position
                    )
                }

            /*
             * Stronger inner glow.
             */
            bulbPositions
                .forEachIndexed {
                    index,
                    position ->

                    drawCircle(
                        color =
                            HOLIDAY_FOCUS_COLORS[
                                index %
                                    HOLIDAY_FOCUS_COLORS.size
                            ].copy(
                                alpha = 0.18f
                            ),
                        radius = middle,
                        center = position
                    )
                }

            /*
             * Tiny soft bulb core.
             */
            bulbPositions
                .forEachIndexed {
                    index,
                    position ->

                    drawCircle(
                        color =
                            HOLIDAY_FOCUS_COLORS[
                                index %
                                    HOLIDAY_FOCUS_COLORS.size
                            ].copy(
                                alpha = 0.72f
                            ),
                        radius = core,
                        center = position
                    )
                }
        }
    }
}
