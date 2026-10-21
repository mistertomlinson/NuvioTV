package com.nuvio.tv.ui.screens.home

import android.graphics.Bitmap
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.LocalAppInForeground
import com.nuvio.tv.domain.model.HolidayTheme
import com.nuvio.tv.ui.screens.settings.SeasonalEffectsViewModel
import com.nuvio.tv.ui.theme.NuvioColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val HOLIDAY_BULB_SPRITE_SIZE = 48
private const val HOLIDAY_BULB_COLOR_COUNT = 4
private const val HOLIDAY_BULB_CORE_HUE_TINT = 0.12f

/*
 * One shared-looking, deliberately low-frequency brightness animation.
 *
 * Only the focused card owns this clock, and it publishes at 20fps. The
 * expensive-looking glow itself remains the exact cached sprite atlas.
 */
private const val HOLIDAY_BULB_PULSE_STEP_MS = 50L
private const val HOLIDAY_BULB_COLOR_PHASE_MS = 900L
private const val HOLIDAY_BULB_BOOST_ALPHA = 0.38f

/*
 * RGB values stay saturated outside the hot center so the bloom still reads
 * red / green / blue / amber from couch distance.
 */
private val HOLIDAY_BULB_RGB = arrayOf(
    intArrayOf(255, 42, 32),
    intArrayOf(48, 220, 88),
    intArrayOf(62, 126, 255),
    intArrayOf(255, 176, 24)
)

/*
 * One tiny 192x48 texture atlas generated once for the lifetime of the process.
 *
 * Each tile contains the complete light treatment: a hot near-white center,
 * saturated colored bloom, and a broad feathered halo. Runtime rendering uses
 * only cached image draws -- there is no blur / RenderEffect on the focused
 * card and no per-frame glow geometry construction.
 */
private val holidayBulbAtlas: ImageBitmap by lazy(LazyThreadSafetyMode.NONE) {
    buildHolidayBulbAtlas()
}

private fun buildHolidayBulbAtlas(): ImageBitmap {
    val atlasWidth =
        HOLIDAY_BULB_SPRITE_SIZE * HOLIDAY_BULB_COLOR_COUNT
    val atlasHeight = HOLIDAY_BULB_SPRITE_SIZE
    val pixels = IntArray(atlasWidth * atlasHeight)
    val center = (HOLIDAY_BULB_SPRITE_SIZE - 1) * 0.5f
    val half = HOLIDAY_BULB_SPRITE_SIZE * 0.5f

    HOLIDAY_BULB_RGB.forEachIndexed { colorIndex, rgb ->
        val tileX = colorIndex * HOLIDAY_BULB_SPRITE_SIZE
        val baseR = rgb[0] / 255f
        val baseG = rgb[1] / 255f
        val baseB = rgb[2] / 255f

        for (y in 0 until HOLIDAY_BULB_SPRITE_SIZE) {
            for (x in 0 until HOLIDAY_BULB_SPRITE_SIZE) {
                val dx = (x + 0.5f - center) / half
                val dy = (y + 0.5f - center) / half
                val radiusSquared = dx * dx + dy * dy

                fun gaussian(sigma: Float): Float =
                    exp(
                        (
                            -0.5f *
                                radiusSquared /
                                (sigma * sigma)
                            ).toDouble()
                    ).toFloat()

                /*
                 * The outer layer is intentionally broad and faint. The inner
                 * layer supplies the saturated bloom; the compact core then
                 * drives both opacity and the near-white center.
                 */
                val outerGlow =
                    0.30f * gaussian(0.43f)
                val innerGlow =
                    0.78f * gaussian(0.235f)
                val hotCore =
                    gaussian(0.135f)

                val coloredAlpha =
                    (
                        1f -
                            (1f - outerGlow) *
                            (1f - innerGlow)
                        ).coerceIn(0f, 1f)

                val alpha =
                    (
                        1f -
                            (1f - coloredAlpha) *
                            (1f - hotCore)
                        ).coerceIn(0f, 1f)

                if (alpha < 0.0025f) {
                    continue
                }

                /*
                 * sqrt() keeps a visibly larger white-hot center without
                 * introducing a hard circular boundary.
                 */
                val whiteMix =
                    sqrt(hotCore.coerceIn(0f, 1f))

                /*
                 * Keep the incandescent / white-hot look, but let a small
                 * amount of each bulb's own hue remain in the hottest center.
                 * 12% is intentionally subtle.
                 */
                val hotR =
                    1f *
                        (1f - HOLIDAY_BULB_CORE_HUE_TINT) +
                        baseR *
                        HOLIDAY_BULB_CORE_HUE_TINT

                val hotG =
                    0.985f *
                        (1f - HOLIDAY_BULB_CORE_HUE_TINT) +
                        baseG *
                        HOLIDAY_BULB_CORE_HUE_TINT

                val hotB =
                    0.93f *
                        (1f - HOLIDAY_BULB_CORE_HUE_TINT) +
                        baseB *
                        HOLIDAY_BULB_CORE_HUE_TINT

                val red =
                    (
                        (baseR * (1f - whiteMix) + hotR * whiteMix) *
                            255f
                        ).roundToInt().coerceIn(0, 255)
                val green =
                    (
                        (baseG * (1f - whiteMix) + hotG * whiteMix) *
                            255f
                        ).roundToInt().coerceIn(0, 255)
                val blue =
                    (
                        (baseB * (1f - whiteMix) + hotB * whiteMix) *
                            255f
                        ).roundToInt().coerceIn(0, 255)
                val a =
                    (alpha * 255f)
                        .roundToInt()
                        .coerceIn(0, 255)

                pixels[
                    y * atlasWidth +
                        tileX + x
                ] =
                    (a shl 24) or
                        (red shl 16) or
                        (green shl 8) or
                        blue
            }
        }
    }

    val bitmap = Bitmap.createBitmap(
        atlasWidth,
        atlasHeight,
        Bitmap.Config.ARGB_8888
    )

    bitmap.setPixels(
        pixels,
        0,
        atlasWidth,
        0,
        0,
        atlasWidth,
        atlasHeight
    )

    return bitmap.asImageBitmap()
}

/*
 * Static Christmas-light focus treatment.
 *
 * The light itself is a cached sprite modeled after a real illuminated bulb:
 * hot white center -> colored bloom -> diffuse halo. Only the focused card
 * draws these images, and the atlas is generated once, so the visual softness
 * does not require a live blur pass.
 *
 * Holiday Effects OFF immediately restores the normal Nuvio focus ring.
 */
@Composable
internal fun Modifier.holidaySegmentedFocusBorder(
    cornerRadius: Dp,
    bulbSpacing: Dp = 11.dp,
    bulbDrawSize: Dp = 22.dp,
    edgeInset: Dp = 2.2.dp
): Modifier {
    val seasonalEffectsViewModel: SeasonalEffectsViewModel = hiltViewModel()
    val config by seasonalEffectsViewModel.config.collectAsStateWithLifecycle()
    val appInForeground = LocalAppInForeground.current

    val activeHoliday =
        remember(
            config,
            appInForeground
        ) {
            config.activeHoliday()
        }

    if (activeHoliday == null) {
        return border(
            width = 2.dp,
            color = NuvioColors.FocusRing,
            shape = RoundedCornerShape(cornerRadius)
        )
    }

    if (
        activeHoliday ==
        HolidayTheme.HALLOWEEN
    ) {
        val focusOwner =
            remember {
                Any()
            }

        DisposableEffect(
            focusOwner
        ) {
            onDispose {
                HalloweenEffectsRuntime
                    .clearFocusedPosterBounds(
                        focusOwner
                    )
            }
        }

        return onGloballyPositioned {
            val bounds =
                it.boundsInWindow()

            HalloweenEffectsRuntime
                .updateFocusedPosterBounds(
                    owner = focusOwner,
                    left = bounds.left,
                    top = bounds.top,
                    right = bounds.right,
                    bottom = bounds.bottom
                )
        }.border(
            width = 2.dp,
            color = Color(0xFFFF7A00),
            shape =
                RoundedCornerShape(
                    cornerRadius
                )
        )
    }

    /*
     * Absolute monotonic time means changing focused cards does not restart the
     * sequence at red. All focused-poster implementations therefore appear to
     * share one continuous red -> green -> blue -> amber pulse.
     */
    var pulsePhase by remember {
        mutableFloatStateOf(0f)
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            val cycleMs =
                HOLIDAY_BULB_COLOR_PHASE_MS *
                    HOLIDAY_BULB_COLOR_COUNT

            val phaseMs =
                (System.nanoTime() / 1_000_000L) %
                    cycleMs

            pulsePhase =
                phaseMs.toFloat() /
                    HOLIDAY_BULB_COLOR_PHASE_MS.toFloat()

            delay(HOLIDAY_BULB_PULSE_STEP_MS)
        }
    }

    return drawWithCache {
        val atlas = holidayBulbAtlas
        val drawSizePx =
            bulbDrawSize.toPx()
                .roundToInt()
                .coerceAtLeast(4)
        val halfDrawSizePx = drawSizePx * 0.5f
        val spacing =
            bulbSpacing.toPx()
                .coerceAtLeast(1f)
        val inset =
            edgeInset.toPx()
                .coerceAtLeast(0f)

        /*
         * The luminous center follows the card edge; most of the soft halo is
         * allowed to bloom inward while any available outer area receives the
         * other half naturally.
         */
        val left = inset
        val top = inset
        val right = (size.width - inset).coerceAtLeast(left)
        val bottom = (size.height - inset).coerceAtLeast(top)
        val width = right - left
        val height = bottom - top

        val requestedRadius =
            (cornerRadius.toPx() - inset).coerceAtLeast(0f)
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
            (perimeter / spacing)
                .roundToInt()
                .coerceAtLeast(HOLIDAY_BULB_COLOR_COUNT)
        val actualSpacing =
            if (bulbCount > 0) {
                perimeter / bulbCount
            } else {
                spacing
            }
        val bulbCenters =
            Array(bulbCount) { index ->
                roundedPoint(index * actualSpacing)
            }

        onDrawWithContent {
            drawContent()

            bulbCenters.forEachIndexed { index, center ->
                val colorIndex =
                    index % HOLIDAY_BULB_COLOR_COUNT

                /*
                 * Only brightness changes. The approved bulb/core/halo sprite
                 * remains completely unchanged.
                 *
                 * Adjacent color families overlap through the handoff, so the
                 * sequence breathes rather than blinks.
                 */
                val rawDistance =
                    abs(
                        pulsePhase -
                            colorIndex.toFloat()
                    )

                val wrappedDistance =
                    minOf(
                        rawDistance,
                        HOLIDAY_BULB_COLOR_COUNT.toFloat() -
                            rawDistance
                    )

                val influence =
                    if (wrappedDistance >= 1f) {
                        0f
                    } else {
                        val x =
                            (1f - wrappedDistance)
                                .coerceIn(0f, 1f)

                        x * x * (3f - 2f * x)
                    }

                /*
                 * Every bulb now keeps the exact previously-approved
                 * FULL-BRIGHTNESS sprite as its baseline. The active color
                 * family gets a second cached draw on top, which strengthens
                 * mostly the translucent colored bloom instead of dimming
                 * every other bulb.
                 */
                val boostAlpha =
                    HOLIDAY_BULB_BOOST_ALPHA *
                        influence

                val srcOffset = IntOffset(
                    colorIndex * HOLIDAY_BULB_SPRITE_SIZE,
                    0
                )

                val srcSize = IntSize(
                    HOLIDAY_BULB_SPRITE_SIZE,
                    HOLIDAY_BULB_SPRITE_SIZE
                )

                val dstOffset = IntOffset(
                    (center.x - halfDrawSizePx).roundToInt(),
                    (center.y - halfDrawSizePx).roundToInt()
                )

                val dstSize = IntSize(
                    drawSizePx,
                    drawSizePx
                )

                drawImage(
                    image = atlas,
                    srcOffset = srcOffset,
                    srcSize = srcSize,
                    dstOffset = dstOffset,
                    dstSize = dstSize,
                    alpha = 1f,
                    filterQuality = FilterQuality.Low
                )

                if (boostAlpha > 0.002f) {
                    drawImage(
                        image = atlas,
                        srcOffset = srcOffset,
                        srcSize = srcSize,
                        dstOffset = dstOffset,
                        dstSize = dstSize,
                        alpha = boostAlpha,
                        filterQuality = FilterQuality.Low
                    )
                }
            }
        }
    }
}
