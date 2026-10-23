package com.nuvio.tv.ui.screens.home

import android.graphics.Bitmap
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.LocalAppInForeground
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
 * Real-string wire palette. The broad almost-black pass provides the soft
 * self-shadow of bundled conductors, the narrower evergreen pass supplies the
 * plastic insulation, and the broken highlight is intentionally faint so it
 * reads as a twist/specular ridge rather than decorative striping.
 */
private val HOLIDAY_WIRE_SHADOW = Color(0xD9000905)
private val HOLIDAY_WIRE_BASE = Color(0xFF0D3A1D)
private val HOLIDAY_WIRE_HIGHLIGHT = Color(0x98547F50)

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

    val christmasActive =
        remember(
            config,
            appInForeground
        ) {
            config.isChristmasActive()
        }

    if (!christmasActive) {
        return border(
            width = 2.dp,
            color = NuvioColors.FocusRing,
            shape = RoundedCornerShape(cornerRadius)
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
                .coerceAtLeast(
                    HOLIDAY_BULB_COLOR_COUNT
                )

        val actualSpacing =
            if (bulbCount > 0) {
                perimeter /
                    bulbCount
            } else {
                spacing
            }

        fun cornerVariationScale(
            rawDistance: Float
        ): Float {
            if (
                radius <= 0.01f ||
                perimeter <= 0f
            ) {
                return 1f
            }

            var d =
                (
                    (
                        rawDistance %
                            perimeter
                        ) +
                        perimeter
                    ) %
                    perimeter

            if (
                d <=
                straightHorizontal
            ) {
                return 1f
            }

            d -=
                straightHorizontal

            if (d <= quarterArc) {
                return 0.58f
            }

            d -= quarterArc

            if (
                d <=
                straightVertical
            ) {
                return 1f
            }

            d -=
                straightVertical

            if (d <= quarterArc) {
                return 0.58f
            }

            d -= quarterArc

            if (
                d <=
                straightHorizontal
            ) {
                return 1f
            }

            d -=
                straightHorizontal

            if (d <= quarterArc) {
                return 0.58f
            }

            d -= quarterArc

            if (
                d <=
                straightVertical
            ) {
                return 1f
            }

            return 0.58f
        }

        /*
         * Deliberately imperfect, but not chaotic.
         *
         * Straight sections vary by about +/-14%. Rounded corners get
         * substantially less variation so a large accidental-looking gap
         * does not appear at a corner. The generated weights are normalized,
         * so the complete strand still closes perfectly around the card.
         *
         * The seed is deterministic for a given card geometry: changing
         * focus never causes the lights to rearrange themselves.
         */
        var spacingSeed =
            0x5EED1234L xor
                (
                    bulbCount.toLong()
                        .shl(8)
                    ) xor
                (
                    size.width
                        .roundToInt()
                        .toLong()
                        .shl(20)
                    ) xor
                size.height
                    .roundToInt()
                    .toLong()

        fun nextSpacingNoise():
            Float {
            spacingSeed =
                (
                    spacingSeed *
                        1664525L +
                        1013904223L
                    ) and
                    0xFFFFFFFFL

            return (
                (
                    spacingSeed ushr 8
                    ) and
                    0x00FFFFFFL
                ).toFloat() /
                0x01000000L
        }

        val rawDeviations =
            FloatArray(
                bulbCount
            ) { index ->
                val uniformDistance =
                    index *
                        actualSpacing

                val centeredNoise =
                    nextSpacingNoise() -
                        0.5f

                centeredNoise *
                    cornerVariationScale(
                        uniformDistance
                    )
            }

        val averageDeviation =
            rawDeviations
                .average()
                .toFloat()

        val gapWeights =
            FloatArray(
                bulbCount
            ) { index ->
                1f +
                    (
                        rawDeviations[index] -
                            averageDeviation
                        ) *
                    0.28f
            }

        val totalGapWeight =
            gapWeights
                .sum()
                .coerceAtLeast(
                    0.001f
                )

        val normalizeGapWeight =
            bulbCount.toFloat() /
                totalGapWeight

        var currentDistance =
            0f

        val bulbCenters =
            Array(
                bulbCount
            ) { index ->
                val center =
                    roundedPoint(
                        currentDistance
                    )

                currentDistance +=
                    actualSpacing *
                        gapWeights[index] *
                        normalizeGapWeight

                center
            }

        /*
         * Build one believable miniature light cord and cache all of its
         * geometry with the card. Nothing in this section depends on the bulb
         * pulse phase, so the 20fps brightness animation never regenerates the
         * wire, its random bends, or its socket stems.
         *
         * The cable rides just inside the bulb centers instead of tracing a
         * mathematically perfect rounded rectangle. Each gap gets a tiny,
         * deterministic quadratic bend. That gives the strand the irregular
         * tension/sag of real insulated wire without allowing obvious waves or
         * corner gaps. Focus changes therefore never reshuffle the strand.
         */
        /*
         * Keep one canonical thin strand width for every card geometry. The
         * first pass looked best once the trailer card expanded, so use that
         * lighter visual weight everywhere rather than letting compact posters
         * read as a heavy green frame.
         */
        val wireShadowWidthPx = 1.82.dp.toPx()
        val wireWidthPx = 1.10.dp.toPx()
        val wireHighlightWidthPx = 0.30.dp.toPx()
        val bulbMountOffsetPx = 2.05.dp.toPx()
        val wireRadialJitterPx = 0.42.dp.toPx()
        val wireMaxBendPx = 1.15.dp.toPx()
        val twistDashOnPx = 1.70.dp.toPx()
        val twistDashOffPx = 2.35.dp.toPx()
        val cardCenter =
            Offset(
                (left + right) * 0.5f,
                (top + bottom) * 0.5f
            )

        var wireSeed =
            spacingSeed xor
                0x6A09E667L xor
                (bulbCount.toLong() shl 14)

        fun nextWireNoise(): Float {
            wireSeed =
                (
                    wireSeed *
                        1103515245L +
                        12345L
                    ) and
                    0x7FFFFFFFL

            return (
                wireSeed and
                    0x00FFFFFFL
                ).toFloat() /
                0x01000000L
        }

        fun inwardUnitNormal(index: Int): Offset {
            val center = bulbCenters[index]
            val previous =
                bulbCenters[
                    (index - 1 + bulbCount) %
                        bulbCount
                ]
            val next =
                bulbCenters[
                    (index + 1) %
                        bulbCount
                ]

            val tangentX = next.x - previous.x
            val tangentY = next.y - previous.y
            val tangentLength =
                sqrt(
                    tangentX * tangentX +
                        tangentY * tangentY
                )

            var normalX: Float
            var normalY: Float

            if (tangentLength > 0.001f) {
                normalX = -tangentY / tangentLength
                normalY = tangentX / tangentLength
            } else {
                val toCenterX = cardCenter.x - center.x
                val toCenterY = cardCenter.y - center.y
                val toCenterLength =
                    sqrt(
                        toCenterX * toCenterX +
                            toCenterY * toCenterY
                    ).coerceAtLeast(0.001f)

                normalX = toCenterX / toCenterLength
                normalY = toCenterY / toCenterLength
            }

            val toCenterX = cardCenter.x - center.x
            val toCenterY = cardCenter.y - center.y

            if (
                normalX * toCenterX +
                    normalY * toCenterY < 0f
            ) {
                normalX = -normalX
                normalY = -normalY
            }

            return Offset(normalX, normalY)
        }

        val wireNormals =
            Array(bulbCount) { index ->
                inwardUnitNormal(index)
            }

        /*
         * The cable now straddles the original perimeter instead of living
         * permanently inside every bulb. This lets real-looking sockets mount
         * bulbs on either side of the strand.
         *
         * Side selection is deterministic and deliberately constrained to no
         * more than two bulbs in a row on the same side. That avoids both a
         * mechanical alternating pattern and ugly random clusters. Geometry is
         * cached, so focus/pulse updates never reshuffle the bulbs.
         */
        val bulbSideSigns = IntArray(bulbCount)
        var previousSide = 0
        var sameSideRun = 0

        for (index in 0 until bulbCount) {
            var side =
                if (nextWireNoise() < 0.5f) {
                    -1
                } else {
                    1
                }

            if (
                side == previousSide &&
                sameSideRun >= 2
            ) {
                side = -side
            }

            bulbSideSigns[index] = side

            if (side == previousSide) {
                sameSideRun += 1
            } else {
                previousSide = side
                sameSideRun = 1
            }
        }

        val wireAnchors =
            Array(bulbCount) { index ->
                val center = bulbCenters[index]
                val inwardNormal = wireNormals[index]
                val radialJitter =
                    (nextWireNoise() - 0.5f) *
                        2f *
                        wireRadialJitterPx

                Offset(
                    center.x +
                        inwardNormal.x *
                        radialJitter,
                    center.y +
                        inwardNormal.y *
                        radialJitter
                )
            }

        val mountedBulbCenters =
            Array(bulbCount) { index ->
                val anchor = wireAnchors[index]
                val inwardNormal = wireNormals[index]
                val localMountOffset =
                    bulbMountOffsetPx *
                        (
                            0.84f +
                                nextWireNoise() *
                                0.32f
                            )
                val signedOffset =
                    localMountOffset *
                        bulbSideSigns[index]

                Offset(
                    anchor.x +
                        inwardNormal.x *
                        signedOffset,
                    anchor.y +
                        inwardNormal.y *
                        signedOffset
                )
            }

        val cablePath = Path()
        val wireHighlightPath = Path()

        if (bulbCount > 0) {
            cablePath.moveTo(
                wireAnchors[0].x,
                wireAnchors[0].y
            )
            wireHighlightPath.moveTo(
                wireAnchors[0].x,
                wireAnchors[0].y
            )

            for (index in 0 until bulbCount) {
                val start = wireAnchors[index]
                val end =
                    wireAnchors[
                        (index + 1) %
                            bulbCount
                    ]
                val dx = end.x - start.x
                val dy = end.y - start.y
                val segmentLength =
                    sqrt(dx * dx + dy * dy)

                if (segmentLength <= 0.001f) {
                    cablePath.lineTo(end.x, end.y)
                    wireHighlightPath.lineTo(
                        end.x,
                        end.y
                    )
                    continue
                }

                val normalX = -dy / segmentLength
                val normalY = dx / segmentLength

                /*
                 * At normal 11dp bulb spacing this tops out around one dp of
                 * lateral deviation. Enough to feel hand-strung, not enough to
                 * read as a decorative squiggle from couch distance.
                 */
                val bendLimit =
                    minOf(
                        wireMaxBendPx,
                        segmentLength * 0.105f
                    )
                val bend =
                    (
                        nextWireNoise() -
                            0.5f
                        ) *
                        2f *
                        bendLimit

                val controlX =
                    (start.x + end.x) *
                        0.5f +
                        normalX * bend
                val controlY =
                    (start.y + end.y) *
                        0.5f +
                        normalY * bend

                cablePath.quadraticBezierTo(
                    controlX,
                    controlY,
                    end.x,
                    end.y
                )
                wireHighlightPath
                    .quadraticBezierTo(
                        controlX,
                        controlY,
                        end.x,
                        end.y
                    )
            }

            /*
             * Real miniature-light sockets stand a little proud of the cord.
             * Add all stems to the same cached cable path so they cost no extra
             * per-bulb draw calls. The glowing sprites are rendered afterward
             * and naturally hide each stem/cord junction.
             */
            mountedBulbCenters.forEachIndexed { index, center ->
                val anchor = wireAnchors[index]
                cablePath.moveTo(
                    anchor.x,
                    anchor.y
                )
                cablePath.lineTo(
                    center.x,
                    center.y
                )
            }
        }

        val wireShadowStyle =
            Stroke(
                width = wireShadowWidthPx,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        val wireStyle =
            Stroke(
                width = wireWidthPx,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        val wireHighlightStyle =
            Stroke(
                width = wireHighlightWidthPx,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect =
                    PathEffect.dashPathEffect(
                        floatArrayOf(
                            twistDashOnPx,
                            twistDashOffPx
                        )
                    )
            )

        onDrawWithContent {
            drawContent()

            /*
             * Three path draws total regardless of bulb count:
             * shadow/bundle depth -> green insulation -> broken highlight.
             * The highlight's short repeating glints suggest the alternating
             * exposed ridge of twisted conductors without animating or drawing
             * a costly literal helix.
             */
            drawPath(
                path = cablePath,
                color = HOLIDAY_WIRE_SHADOW,
                style = wireShadowStyle
            )
            drawPath(
                path = cablePath,
                color = HOLIDAY_WIRE_BASE,
                style = wireStyle
            )
            drawPath(
                path = wireHighlightPath,
                color = HOLIDAY_WIRE_HIGHLIGHT,
                style = wireHighlightStyle
            )

            mountedBulbCenters.forEachIndexed { index, center ->
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
                 * Approved TV tuning:
                 *
                 * Resting bulbs stay at 85% brightness. At full influence the
                 * base sprite reaches 100%, then receives the same 38% cached
                 * glow boost used by the approved bright state.
                 */
                val baseAlpha =
                    0.85f +
                        0.15f *
                            influence

                val boostAlpha =
                    HOLIDAY_BULB_BOOST_ALPHA *
                        influence

                val srcOffset =
                    IntOffset(
                        colorIndex *
                            HOLIDAY_BULB_SPRITE_SIZE,
                        0
                    )

                val srcSize =
                    IntSize(
                        HOLIDAY_BULB_SPRITE_SIZE,
                        HOLIDAY_BULB_SPRITE_SIZE
                    )

                val dstOffset =
                    IntOffset(
                        (
                            center.x -
                                halfDrawSizePx
                            ).roundToInt(),
                        (
                            center.y -
                                halfDrawSizePx
                            ).roundToInt()
                    )

                val dstSize =
                    IntSize(
                        drawSizePx,
                        drawSizePx
                    )

                drawImage(
                    image = atlas,
                    srcOffset = srcOffset,
                    srcSize = srcSize,
                    dstOffset = dstOffset,
                    dstSize = dstSize,
                    alpha = baseAlpha,
                    filterQuality =
                        FilterQuality.Low
                )

                if (
                    boostAlpha >
                    0.002f
                ) {
                    drawImage(
                        image = atlas,
                        srcOffset = srcOffset,
                        srcSize = srcSize,
                        dstOffset = dstOffset,
                        dstSize = dstSize,
                        alpha = boostAlpha,
                        filterQuality =
                            FilterQuality.Low
                    )
                }
            }
        }
    }
}
