package com.nuvio.tv.ui.screens.home

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.LocalHomeHeroTrailerPlaying
import com.nuvio.tv.domain.model.SeasonalEffectsConfig
import com.nuvio.tv.domain.model.SnowSizeProfile
import com.nuvio.tv.domain.model.SnowStartupMode
import com.nuvio.tv.ui.screens.settings.SeasonalEffectsViewModel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive

internal object SeasonalSnowRuntime {
    private val _homeScrolling = MutableStateFlow(false)
    val homeScrolling: StateFlow<Boolean> = _homeScrolling.asStateFlow()

    fun setHomeScrolling(scrolling: Boolean) {
        if (_homeScrolling.value != scrolling) {
            _homeScrolling.value = scrolling
        }
    }
}

private const val MAX_SNOW_PARTICLES = 120
private const val NANOS_PER_SECOND = 1_000_000_000.0

/*
 * A platform-parallax transition can occasionally miss a Compose frame. The
 * old 100ms clamp made the snow timeline itself lose time after that miss,
 * which made the hesitation more obvious. A larger catch-up window cannot
 * create frames the GPU did not draw, but the first surviving frame now lands
 * at the correct later position instead of continuing in slow motion.
 */
private const val MAX_CATCH_UP_DELTA_NANOS = 500_000_000L

private const val SOFT_FADE_DURATION_SECONDS = 1.5
private const val FALL_IN_SPAWN_WINDOW_SECONDS = 3.0
private const val TRAVEL_HEIGHT_AT_1080P = 1104.0

private const val SNOW_SPRITE_SIZE = 24
private const val SNOW_SPRITE_COLUMNS = 4
private const val SNOW_SPRITE_ROWS = 3
private const val SNOW_SPRITE_COUNT =
    SNOW_SPRITE_COLUMNS * SNOW_SPRITE_ROWS

/*
 * TEST accumulation tuning.
 *
 * 96 samples + one fill path is intentionally tiny. The relatively generous
 * deposit amounts make the bank visibly grow within tens of seconds so the
 * shape/cap can be judged quickly. If approved, only these deposit constants
 * need to be reduced for production-speed accumulation.
 */
private const val SNOW_BANK_COLUMNS = 96
private const val SNOW_BANK_MAX_HEIGHT_DP = 22f
private const val SNOW_BANK_CENTER_DEPOSIT_DP = 2.35f
private const val SNOW_BANK_NEAR_DEPOSIT_DP = 1.15f
private const val SNOW_BANK_FAR_DEPOSIT_DP = 0.45f

private data class SnowParticle(
    val xSeed: Float,
    val ySeed: Float,
    val sizeSeed: Float,
    val speedSeed: Float,
    val alphaSeed: Float,
    val phase: Float,
    val swayFrequency: Float,
    val depthSeed: Float,
    val spriteIndex: Int
)

/*
 * One tiny texture atlas generated once.
 *
 * The irregularity and fuzziness are baked into alpha pixels. Runtime snow
 * therefore performs one image draw per particle: no blur, no flake geometry,
 * no extra line/oval passes and no per-flake shape trig.
 */
private val snowSpriteAtlas: ImageBitmap by lazy(LazyThreadSafetyMode.NONE) {
    buildSnowSpriteAtlas()
}

private fun buildSnowSpriteAtlas(): ImageBitmap {
    val atlasWidth =
        SNOW_SPRITE_COLUMNS * SNOW_SPRITE_SIZE
    val atlasHeight =
        SNOW_SPRITE_ROWS * SNOW_SPRITE_SIZE

    val pixels = IntArray(atlasWidth * atlasHeight)
    val center = (SNOW_SPRITE_SIZE - 1) * 0.5f

    repeat(SNOW_SPRITE_COUNT) { variant ->
        val cellX =
            (variant % SNOW_SPRITE_COLUMNS) * SNOW_SPRITE_SIZE
        val cellY =
            (variant / SNOW_SPRITE_COLUMNS) * SNOW_SPRITE_SIZE

        val rotation = variant * 0.47f
        val cosRotation = cos(rotation)
        val sinRotation = sin(rotation)

        val mainSigmaX =
            2.55f + (variant % 4) * 0.34f
        val mainSigmaY =
            2.65f + ((variant * 3) % 5) * 0.25f
        val lobeCount = 2 + variant % 4

        for (y in 0 until SNOW_SPRITE_SIZE) {
            for (x in 0 until SNOW_SPRITE_SIZE) {
                val dx = x + 0.5f - center
                val dy = y + 0.5f - center

                val rotatedX =
                    dx * cosRotation + dy * sinRotation
                val rotatedY =
                    -dx * sinRotation + dy * cosRotation

                /* Baked wide haze: no runtime blur pass. */
                var energy =
                    0.105f *
                        exp(
                            (
                                -(dx * dx + dy * dy) /
                                    (2f * 5.8f * 5.8f)
                            ).toDouble()
                        ).toFloat()

                /* Irregular elliptical center. */
                energy +=
                    2.05f *
                        exp(
                            (
                                -0.5f *
                                    (
                                        rotatedX * rotatedX /
                                            (mainSigmaX * mainSigmaX) +
                                            rotatedY * rotatedY /
                                            (mainSigmaY * mainSigmaY)
                                    )
                            ).toDouble()
                        ).toFloat()

                /*
                 * Tiny soft protrusions are rasterized once into the atlas.
                 * Overlaps are normalized before alpha is written, so there
                 * are no bright runtime overlap seams.
                 */
                repeat(lobeCount) { lobe ->
                    val angle =
                        rotation +
                            lobe *
                                (2f * PI.toFloat() / lobeCount) +
                            ((variant % 3) - 1) * 0.13f

                    val reach =
                        2.25f +
                            ((variant + lobe) % 3) * 0.48f

                    val lobeX = cos(angle) * reach
                    val lobeY = sin(angle) * reach
                    val lx = dx - lobeX
                    val ly = dy - lobeY

                    val sigma =
                        1.20f +
                            ((variant + lobe * 2) % 4) * 0.18f
                    val strength =
                        0.24f +
                            ((variant + lobe) % 3) * 0.045f

                    energy +=
                        strength *
                            exp(
                                (
                                    -(lx * lx + ly * ly) /
                                        (2f * sigma * sigma)
                                ).toDouble()
                            ).toFloat()
                }

                val coverage =
                    (
                        1f -
                            exp(-energy.toDouble()).toFloat()
                    ).coerceIn(0f, 1f)

                val alpha =
                    (coverage * 245f)
                        .roundToInt()
                        .coerceIn(0, 245)

                pixels[
                    (cellY + y) * atlasWidth +
                        cellX + x
                ] =
                    (alpha shl 24) or 0x00FFFFFF
            }
        }
    }

    val bitmap =
        Bitmap.createBitmap(
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

private val snowParticles: List<SnowParticle> =
    buildList(MAX_SNOW_PARTICLES) {
        var state = 0x51A7C0DEu

        fun nextFloat(): Float {
            state =
                state * 1664525u +
                    1013904223u

            return (
                (
                    state shr 8
                ).toUInt().toLong() and
                    0x00FFFFFFL
                ).toFloat() /
                0x01000000L
        }

        repeat(MAX_SNOW_PARTICLES) {
            val xSeed = nextFloat()
            val ySeed = nextFloat()
            val sizeSeed = nextFloat()
            val speedSeed = nextFloat()
            val alphaSeed = nextFloat()
            val phase =
                nextFloat() *
                    (2f * PI.toFloat())
            val swayFrequency =
                0.45f + nextFloat() * 0.85f
            val depthSeed = nextFloat()

            val spriteSeed =
                (
                    xSeed * 0.41f +
                        ySeed * 0.29f +
                        sizeSeed * 0.83f +
                        depthSeed * 0.57f
                    ) % 1f

            add(
                SnowParticle(
                    xSeed = xSeed,
                    ySeed = ySeed,
                    sizeSeed = sizeSeed,
                    speedSeed = speedSeed,
                    alphaSeed = alphaSeed,
                    phase = phase,
                    swayFrequency = swayFrequency,
                    depthSeed = depthSeed,
                    spriteIndex =
                        (
                            spriteSeed *
                                SNOW_SPRITE_COUNT
                            ).toInt()
                            .coerceIn(
                                0,
                                SNOW_SPRITE_COUNT - 1
                            )
                )
            )
        }
    }

private fun snowDepth(
    particle: SnowParticle,
    layerCount: Int
): Float =
    when (layerCount.coerceIn(1, 3)) {
        1 -> 0.88f
        2 ->
            if (particle.depthSeed < 0.58f) {
                0.68f
            } else {
                1.12f
            }

        else ->
            when {
                particle.depthSeed < 0.44f -> 0.68f
                particle.depthSeed < 0.80f -> 0.88f
                else -> 1.12f
            }
    }

private fun completedLandingsAt(
    particle: SnowParticle,
    seconds: Double,
    config: SeasonalEffectsConfig
): Long {
    val spawnDelay =
        if (config.startupMode == SnowStartupMode.FALL_IN_FROM_TOP) {
            particle.ySeed.toDouble() * FALL_IN_SPAWN_WINDOW_SECONDS
        } else {
            0.0
        }

    if (seconds <= spawnDelay) return 0L

    val depth = snowDepth(particle, config.depthLayers)
    val baseFallPxPerSecond =
        52.0 + particle.speedSeed * 62.0
    val cycleSeconds =
        TRAVEL_HEIGHT_AT_1080P /
            (
                baseFallPxPerSecond *
                    config.snowfallSpeed.multiplier *
                    depth
                )

    if (cycleSeconds <= 0.0) return 0L

    val activeSeconds = seconds - spawnDelay
    val initialProgress =
        if (config.startupMode == SnowStartupMode.FALL_IN_FROM_TOP) {
            0.0
        } else {
            particle.ySeed.toDouble()
        }

    return floor(
        initialProgress +
            activeSeconds / cycleSeconds
    ).toLong().coerceAtLeast(0L)
}

private fun depositSnowBankForInterval(
    bankHeightsDp: FloatArray,
    particleCount: Int,
    beforeSeconds: Double,
    afterSeconds: Double,
    config: SeasonalEffectsConfig
): Boolean {
    if (afterSeconds <= beforeSeconds) return false

    var changed = false
    val columns = bankHeightsDp.size

    fun wrapColumn(index: Int): Int {
        val wrapped = index % columns
        return if (wrapped < 0) wrapped + columns else wrapped
    }

    fun addDeposit(index: Int, amountDp: Float) {
        val wrapped = wrapColumn(index)
        val before = bankHeightsDp[wrapped]
        val after =
            (before + amountDp)
                .coerceAtMost(SNOW_BANK_MAX_HEIGHT_DP)
        if (after > before) {
            bankHeightsDp[wrapped] = after
            changed = true
        }
    }

    for (index in 0 until particleCount.coerceIn(0, MAX_SNOW_PARTICLES)) {
        val particle = snowParticles[index]
        val beforeLanding =
            completedLandingsAt(
                particle = particle,
                seconds = beforeSeconds,
                config = config
            )
        val afterLanding =
            completedLandingsAt(
                particle = particle,
                seconds = afterSeconds,
                config = config
            )

        val newLandings =
            (afterLanding - beforeLanding)
                .coerceIn(0L, 3L)
                .toInt()

        repeat(newLandings) { landingOffset ->
            val landingNumber =
                beforeLanding + landingOffset + 1L

            /*
             * Deterministic drift keeps repeated landings from the same flake
             * from building one vertical spike, without storing landed flakes.
             */
            val cycleJitter =
                ((landingNumber % 97L).toFloat() * 0.173f) % 1f
            val normalizedX =
                (
                    particle.xSeed +
                        cycleJitter +
                        particle.depthSeed * 0.11f
                    ) % 1f
            val baseColumn =
                (normalizedX * columns)
                    .toInt()
                    .coerceIn(0, columns - 1)

            /* Prefer the lowest local pocket so the bank spreads naturally. */
            var targetColumn = baseColumn
            var bestScore = Float.MAX_VALUE
            for (offset in -2..2) {
                val candidate = wrapColumn(baseColumn + offset)
                val score =
                    bankHeightsDp[candidate] +
                        abs(offset) * 0.08f
                if (score < bestScore) {
                    bestScore = score
                    targetColumn = candidate
                }
            }

            addDeposit(targetColumn, SNOW_BANK_CENTER_DEPOSIT_DP)
            addDeposit(targetColumn - 1, SNOW_BANK_NEAR_DEPOSIT_DP)
            addDeposit(targetColumn + 1, SNOW_BANK_NEAR_DEPOSIT_DP)
            addDeposit(targetColumn - 2, SNOW_BANK_FAR_DEPOSIT_DP)
            addDeposit(targetColumn + 2, SNOW_BANK_FAR_DEPOSIT_DP)
        }
    }

    return changed
}

@Composable
internal fun SeasonalEffectsOverlayHost(
    viewModel: SeasonalEffectsViewModel = hiltViewModel()
) {
    val config by
        viewModel.config
            .collectAsStateWithLifecycle()

    if (!config.enabled) return

    val isScrolling by
        SeasonalSnowRuntime
            .homeScrolling
            .collectAsStateWithLifecycle()

    val heroTrailerPlaying =
        LocalHomeHeroTrailerPlaying.current.value

    HolidaySnowOverlay(
        config = config,
        isScrolling = isScrolling,
        heroTrailerPlaying = heroTrailerPlaying
    )
}

@Composable
private fun HolidaySnowOverlay(
    config: SeasonalEffectsConfig,
    isScrolling: Boolean,
    heroTrailerPlaying: Boolean
) {
    val pauseForTrailer =
        config.pauseDuringHeroTrailer &&
            heroTrailerPlaying

    val freezeForScroll =
        config.freezeWhileScrolling &&
            isScrolling

    val paused =
        pauseForTrailer || freezeForScroll

    val effectiveFps =
        if (
            isScrolling &&
            config.reduceFpsWhileScrolling
        ) {
            minOf(config.targetFps, 15)
        } else {
            config.targetFps
        }.coerceIn(1, 60)

    val requestedCount =
        config.particleCount
            .coerceIn(1, MAX_SNOW_PARTICLES)

    val effectiveCount =
        if (
            isScrolling &&
            config.reduceDensityWhileScrolling
        ) {
            (requestedCount * 0.55f)
                .toInt()
                .coerceAtLeast(1)
        } else {
            requestedCount
        }

    var simulationNanos by
        remember(config.startupMode) {
            mutableLongStateOf(0L)
        }

    /*
     * Mutable primitive arrays avoid 96 independent Compose states. A single
     * generation integer invalidates the Canvas only when an actual landing
     * changes the bank.
     */
    val bankHeightsDp =
        remember(config.startupMode) {
            FloatArray(SNOW_BANK_COLUMNS)
        }
    var bankGeneration by
        remember(config.startupMode) {
            mutableIntStateOf(0)
        }

    LaunchedEffect(
        effectiveFps,
        paused,
        config.startupMode,
        config.snowfallSpeed,
        config.depthLayers,
        requestedCount
    ) {
        val publishIntervalNanos =
            NANOS_PER_SECOND.toLong() /
                effectiveFps

        var previousVsync = 0L
        var lastPublishedVsync = 0L
        var accumulatedSimulationNanos = 0L

        while (isActive) {
            withFrameNanos { now ->
                if (previousVsync == 0L) {
                    previousVsync = now
                    lastPublishedVsync = now
                    return@withFrameNanos
                }

                val delta =
                    (now - previousVsync)
                        .coerceIn(
                            0L,
                            MAX_CATCH_UP_DELTA_NANOS
                        )

                previousVsync = now

                if (!paused) {
                    accumulatedSimulationNanos += delta

                    if (
                        now - lastPublishedVsync >=
                        publishIntervalNanos
                    ) {
                        val beforeNanos = simulationNanos
                        val afterNanos =
                            beforeNanos +
                                accumulatedSimulationNanos

                        val bankChanged =
                            depositSnowBankForInterval(
                                bankHeightsDp = bankHeightsDp,
                                particleCount = requestedCount,
                                beforeSeconds =
                                    beforeNanos /
                                        NANOS_PER_SECOND,
                                afterSeconds =
                                    afterNanos /
                                        NANOS_PER_SECOND,
                                config = config
                            )

                        simulationNanos = afterNanos
                        if (bankChanged) {
                            bankGeneration++
                        }

                        accumulatedSimulationNanos = 0L
                        lastPublishedVsync = now
                    }
                } else {
                    accumulatedSimulationNanos = 0L
                    lastPublishedVsync = now
                }
            }
        }
    }

    val blurRadius = config.blur.radiusDp

    val drawModifier =
        Modifier
            .fillMaxSize()
            .then(
                if (blurRadius > 0f) {
                    Modifier.blur(blurRadius.dp)
                } else {
                    Modifier
                }
            )

    val bankFillPath = remember { Path() }
    val bankTopPath = remember { Path() }

    /* Reading this state is enough to invalidate the Canvas after landings. */
    val bankGenerationSnapshot = bankGeneration

    Canvas(modifier = drawModifier) {
        if (size.width <= 0f || size.height <= 0f) {
            return@Canvas
        }

        /* Keep the primitive-array dependency explicit for the draw snapshot. */
        if (bankGenerationSnapshot < 0) return@Canvas

        val atlas = snowSpriteAtlas
        val seconds =
            simulationNanos /
                NANOS_PER_SECOND
        val scale1080 = size.height / 1080f

        val windPxPerSecond =
            config.wind
                .pixelsPerSecondAt1080p *
                scale1080
        val swayAmplitude =
            config.sway
                .amplitudeAt1080p *
                scale1080
        val fallMultiplier =
            config.snowfallSpeed.multiplier
        val opacityMultiplier =
            config.opacity.multiplier
        val margin = 12f * scale1080
        val travelHeight =
            size.height + margin * 2f
        val layerCount =
            config.depthLayers.coerceIn(1, 3)

        val startupOpacity =
            when (config.startupMode) {
                SnowStartupMode.SOFT_FADE_IN -> {
                    val t =
                        (
                            seconds /
                                SOFT_FADE_DURATION_SECONDS
                            ).coerceIn(0.0, 1.0)
                            .toFloat()
                    t * t * (3f - 2f * t)
                }

                else -> 1f
            }

        if (
            startupOpacity > 0f ||
            config.startupMode !=
                SnowStartupMode.SOFT_FADE_IN
        ) {
            for (index in 0 until effectiveCount) {
                val particle = snowParticles[index]
                val depth = snowDepth(particle, layerCount)

                val baseFallPxPerSecond =
                    52f +
                        particle.speedSeed * 62f

                val motionSeconds =
                    if (
                        config.startupMode ==
                        SnowStartupMode.FALL_IN_FROM_TOP
                    ) {
                        val spawnDelaySeconds =
                            particle.ySeed.toDouble() *
                                FALL_IN_SPAWN_WINDOW_SECONDS

                        if (seconds < spawnDelaySeconds) {
                            continue
                        }

                        seconds - spawnDelaySeconds
                    } else {
                        seconds
                    }

                val yTravel =
                    motionSeconds *
                        baseFallPxPerSecond *
                        fallMultiplier *
                        depth *
                        scale1080

                val y =
                    if (
                        config.startupMode ==
                        SnowStartupMode.FALL_IN_FROM_TOP
                    ) {
                        (
                            yTravel.toFloat() %
                                travelHeight
                            ) - margin
                    } else {
                        (
                            particle.ySeed *
                                travelHeight +
                                yTravel
                            ).toFloat() %
                            travelHeight -
                            margin
                    }

                val directionalWind =
                    motionSeconds *
                        windPxPerSecond *
                        depth

                val sway =
                    if (swayAmplitude == 0f) {
                        0f
                    } else {
                        sin(
                            motionSeconds *
                                particle.swayFrequency *
                                2.0 *
                                PI +
                                particle.phase
                        ).toFloat() *
                            swayAmplitude *
                            (
                                0.72f +
                                    particle.depthSeed *
                                    0.56f
                                )
                    }

                var x =
                    particle.xSeed *
                        size.width +
                        directionalWind.toFloat() +
                        sway

                x %= size.width
                if (x < 0f) x += size.width

                val radius1080 =
                    when (config.sizeProfile) {
                        SnowSizeProfile.TINY ->
                            0.85f +
                                particle.sizeSeed * 1.25f

                        SnowSizeProfile.BALANCED ->
                            1.10f +
                                particle.sizeSeed * 2.30f

                        SnowSizeProfile.LARGE ->
                            2.00f +
                                particle.sizeSeed * 3.75f

                        SnowSizeProfile.MIXED ->
                            0.85f +
                                particle.sizeSeed *
                                particle.sizeSeed *
                                4.75f
                    }

                val radius =
                    radius1080 *
                        scale1080 *
                        depth

                val baseAlpha =
                    0.34f +
                        particle.alphaSeed * 0.44f
                val steadyAlpha =
                    (
                        baseAlpha *
                            opacityMultiplier *
                            (0.78f + depth * 0.20f)
                        ).coerceIn(
                        0.10f,
                        0.96f
                    )
                val alpha =
                    steadyAlpha *
                        startupOpacity

                val spriteDrawSize =
                    (radius * 5.6f)
                        .roundToInt()
                        .coerceAtLeast(5)

                val sprite = particle.spriteIndex
                val sourceX =
                    (sprite % SNOW_SPRITE_COLUMNS) *
                        SNOW_SPRITE_SIZE
                val sourceY =
                    (sprite / SNOW_SPRITE_COLUMNS) *
                        SNOW_SPRITE_SIZE

                drawImage(
                    image = atlas,
                    srcOffset =
                        IntOffset(sourceX, sourceY),
                    srcSize =
                        IntSize(
                            SNOW_SPRITE_SIZE,
                            SNOW_SPRITE_SIZE
                        ),
                    dstOffset =
                        IntOffset(
                            (
                                x -
                                    spriteDrawSize * 0.5f
                                ).roundToInt(),
                            (
                                y -
                                    spriteDrawSize * 0.5f
                                ).roundToInt()
                        ),
                    dstSize =
                        IntSize(
                            spriteDrawSize,
                            spriteDrawSize
                        ),
                    alpha = alpha,
                    filterQuality =
                        FilterQuality.Low
                )
            }
        }

        /*
         * Shallow accumulated bank: one filled path + one soft top-edge stroke.
         * No settled-particle objects, collisions, or retained sprite pile.
         */
        bankFillPath.reset()
        bankTopPath.reset()

        val columnWidth =
            if (SNOW_BANK_COLUMNS > 1) {
                size.width /
                    (SNOW_BANK_COLUMNS - 1)
            } else {
                size.width
            }

        bankFillPath.moveTo(0f, size.height)

        for (column in 0 until SNOW_BANK_COLUMNS) {
            val x = column * columnWidth
            val heightPx =
                bankHeightsDp[column]
                    .coerceIn(
                        0f,
                        SNOW_BANK_MAX_HEIGHT_DP
                    ) * density
            val y = size.height - heightPx

            if (column == 0) {
                bankFillPath.lineTo(x, y)
                bankTopPath.moveTo(x, y)
            } else {
                bankFillPath.lineTo(x, y)
                bankTopPath.lineTo(x, y)
            }
        }

        bankFillPath.lineTo(size.width, size.height)
        bankFillPath.close()

        drawPath(
            path = bankFillPath,
            color = Color.White.copy(alpha = 0.82f)
        )
        drawPath(
            path = bankTopPath,
            color = Color.White.copy(alpha = 0.24f),
            style = Stroke(
                width = 2.4.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}
