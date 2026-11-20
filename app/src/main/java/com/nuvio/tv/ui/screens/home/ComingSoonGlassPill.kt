package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioColors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect

internal val COMING_SOON_PILL_HEIGHT = 26.dp

/** Shared glass/text/width animation; placement belongs to the Home or XL caller. */
@Composable
internal fun ComingSoonGlassPill(
    text: String,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    blurEnabled: Boolean = false,
    glassVisibilityProgress: Float = 1f
) {
    val pillHeight = COMING_SOON_PILL_HEIGHT
    val pillShape = remember {
        RoundedCornerShape(7.dp)
    }

    val density =
        androidx.compose.ui.platform.LocalDensity.current

    val baseTextStyle =
        MaterialTheme.typography.labelMedium

    val textStyle =
        remember(baseTextStyle) {
            baseTextStyle.copy(
                fontWeight =
                    androidx.compose.ui.text.font
                        .FontWeight.SemiBold
            )
        }

    val textMeasurer =
        androidx.compose.ui.text
            .rememberTextMeasurer()

    val targetWidthPx =
        remember(
            text,
            textStyle,
            density
        ) {
            val measured =
                textMeasurer.measure(
                    text = text,
                    style = textStyle,
                    maxLines = 1
                )

            measured.size.width.toFloat() +
                with(density) {
                    20.dp.toPx()
                }
        }

    val widthAnim =
        remember {
            androidx.compose.animation.core
                .Animatable(0f)
        }

    var initialized by
        remember {
            mutableStateOf(false)
        }

    var displayText by
        remember {
            mutableStateOf(text)
        }

    LaunchedEffect(
        text,
        targetWidthPx
    ) {
        displayText = text

        if (!initialized) {
            widthAnim.snapTo(
                targetWidthPx
            )
            initialized = true
        } else {
            widthAnim.animateTo(
                targetValue =
                    targetWidthPx,
                animationSpec =
                    androidx.compose.animation.core
                        .tween(
                            durationMillis = 250,
                            easing =
                                androidx.compose.animation.core
                                    .CubicBezierEasing(
                                        0.2f,
                                        0f,
                                        0f,
                                        1f
                                    )
                        )
            )
        }
    }

    val animatedWidth =
        with(density) {
            /*
             * widthAnim is already expressed in physical pixels.
             *
             * Snap only the rendered layout width to the nearest whole
             * physical pixel. The underlying 250ms Animatable remains
             * continuous, so its easing curve is unchanged.
             */
            (
                widthAnim.value
                    .coerceAtLeast(0f) +
                    0.5f
            )
                .toInt()
                .toDp()
        }

    /*
     * Exact Modern sidebar visual family.
     */
    val glassBrush =
        remember {
            Brush.verticalGradient(
                listOf(
                    /*
                     * Same popup glass hue, but intentionally clearer because
                     * this surface is only 26dp tall.
                     *
                     * Alpha:
                     * 0x8F ~= 56%
                     * 0x82 ~= 51%
                     * 0x89 ~= 54%
                     */
                    Color(0x8F2A3038),
                    Color(0x8220252C),
                    Color(0x8924292F)
                )
            )
        }

    val glassBorder =
        Color.White.copy(
            alpha = 0.09f
        )

    val blurModifier =
        if (blurEnabled && hazeState != null) {
            Modifier
                .clip(pillShape)
                .hazeEffect(
                    state = hazeState
                ) {
                    this.blurEnabled = true
                    /*
                                     * Android RenderEffect cannot create a blur at 0 radius.
                                     *
                                     * Follow the same proven pattern used by Nuvio's popup glass:
                                     * keep the native blur strictly positive while the hazeChild
                                     * exists, from 1dp at zero visibility to 30dp at full glass.
                                     *
                                     * This node exists only while the label is active. Exit
                                     * detaches it before the glass/text fade can leave a ghost.
                                     */
                    this.blurRadius =
                        (
                                                1f +
                                                    (
                                                        29f *
                                                            glassVisibilityProgress
                                                                .coerceIn(0f, 1f)
                                                    )
                                            ).dp
                    this.noiseFactor = homeGlassNoiseFactor(glassVisibilityProgress)
                }
        } else {
            Modifier
        }

    if (animatedWidth > 0.dp) {
        Box(
            modifier = modifier
                .width(
                    animatedWidth
                )
                .height(
                    pillHeight
                )

                /*
                 * Real Haze child now sits OUTSIDE its source.
                 */
                .then(
                    blurModifier
                )
                .graphicsLayer {
                    shape = pillShape
                    clip = true
                }
                .clip(
                    pillShape
                )
                .background(
                    brush =
                        glassBrush,
                    shape =
                        pillShape
                )
                .border(
                    width = 1.dp,
                    color =
                        glassBorder,
                    shape =
                        pillShape
                ),
            contentAlignment =
                Alignment.Center
        ) {
            androidx.compose.animation.AnimatedContent(
            targetState = text,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                (
                    fadeIn(
                        animationSpec =
                            tween(
                                durationMillis = 220,
                                easing =
                                    androidx.compose.animation.core
                                        .LinearEasing
                            )
                    ) togetherWith
                        fadeOut(
                            animationSpec =
                                tween(
                                    durationMillis = 220,
                                    easing =
                                        androidx.compose.animation.core
                                            .LinearEasing
                                )
                        )
                ).using(
                    sizeTransform = null
                )
            },
            contentAlignment = Alignment.Center,
            label = "comingSoonHomeGlassText"
        ) { animatedText ->
            /*
             * Every transition state occupies the SAME geometry.
             *
             * This deliberately prevents the old and new strings
             * from participating in pill measurement while its width
             * is independently animating.
             */
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = animatedText,
                    style =
                        MaterialTheme.typography.labelMedium,
                    color = NuvioColors.TextPrimary,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
        }
    }
}
