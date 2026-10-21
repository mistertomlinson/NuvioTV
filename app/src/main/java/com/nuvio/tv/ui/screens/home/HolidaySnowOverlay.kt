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

    /*
     * HomeScreen owns the loading curtain and therefore owns the authoritative
     * definition of "Home is actually visible". Seasonal effects do not invent
     * their own timer/readiness heuristic.
     */
    private val _homeReady = MutableStateFlow(false)
    val homeReady: StateFlow<Boolean> = _homeReady.asStateFlow()

    fun setHomeScrolling(scrolling: Boolean) {
        if (_homeScrolling.value != scrolling) {
            _homeScrolling.value = scrolling
        }
    }

    fun setHomeReady(ready: Boolean) {
        if (_homeReady.value != ready) {
            _homeReady.value = ready
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
 * 192 samples are still just a tiny primitive array, but halve the horizontal
 * spacing of the old height map. Deposits are spread over a wider kernel so
 * the deliberately fast test build keeps roughly the same visual fill rate
 * without growing blocky local towers.
 */
private const val SNOW_BANK_COLUMNS = 768
private const val SNOW_BANK_MAX_HEIGHT_DP = 7.5f

/*
 * Four times the previous bank resolution. The height map is still only a
 * small primitive array, but samples now sit close enough together that the
 * generated surface should no longer advertise individual columns on a
 * 1080p/4K television.
 */
private const val SNOW_BANK_DEPOSIT_RADIUS = 16
private const val SNOW_BANK_RENDER_SMOOTH_RADIUS = 8

/*
 * Preserve approximately the same deliberately-fast test accumulation volume
 * as the previous nine-column kernel, but distribute it smoothly across a
 * much denser 33-column footprint.
 */
private const val SNOW_BANK_DEPOSIT_TOTAL_DP = 10.5f
private const val SNOW_BANK_SEARCH_DISTANCE_PENALTY = 0.003125f

/*
 * Generated once. No Gaussian math occurs during rendering.
 */
private val snowBankDepositKernelDp =
    run {
        val count =
            SNOW_BANK_DEPOSIT_RADIUS * 2 + 1
        val sigma = 6.4f
        val weights = FloatArray(count)
        var totalWeight = 0f

        for (index in weights.indices) {
            val offset =
                (
                    index -
                        SNOW_BANK_DEPOSIT_RADIUS
                    ).toFloat()

            val normalized =
                offset / sigma

            val weight =
                exp(
                    (
                        -0.5f *
                            normalized *
                            normalized
                        ).toDouble()
                ).toFloat()

            weights[index] = weight
            totalWeight += weight
        }

        val scale =
            SNOW_BANK_DEPOSIT_TOTAL_DP /
                totalWeight.coerceAtLeast(0.0001f)

        for (index in weights.indices) {
            weights[index] *= scale
        }

        weights
    }

/*
 * The visible bank uses a separate normalized kernel. Because this blends
 * FILL FRACTION rather than final height, a completely full bank still
 * reproduces the existing approved 7.5dp wavy cap exactly.
 */
private val snowBankRenderKernel =
    run {
        val count =
            SNOW_BANK_RENDER_SMOOTH_RADIUS * 2 + 1
        val sigma = 4.0f
        val weights = FloatArray(count)
        var totalWeight = 0f

        for (index in weights.indices) {
            val offset =
                (
                    index -
                        SNOW_BANK_RENDER_SMOOTH_RADIUS
                    ).toFloat()

            val normalized =
                offset / sigma

            val weight =
                exp(
                    (
                        -0.5f *
                            normalized *
                            normalized
                        ).toDouble()
                ).toFloat()

            weights[index] = weight
            totalWeight += weight
        }

        val inverseTotal =
            1f /
                totalWeight.coerceAtLeast(0.0001f)

        for (index in weights.indices) {
            weights[index] *= inverseTotal
        }

        weights
    }

/*
 * Even after the bank is completely full, do not let all columns converge to
 * one identical flat ceiling. This is the same low-frequency wave formula as
 * the approved 96-column build; the higher sampling resolution only traces it
 * more finely. Maximum height and final silhouette stay unchanged.
 */
private val snowBankColumnCapDp =
    FloatArray(SNOW_BANK_COLUMNS) { index ->
        val denominator =
            (SNOW_BANK_COLUMNS - 1)
                .coerceAtLeast(1)
                .toDouble()

        val phase =
            index.toDouble() /
                denominator

        val broadWave =
            sin(
                phase * PI * 4.0 +
                    0.35
            )

        val secondaryWave =
            sin(
                phase * PI * 10.0 +
                    1.20
            )

        val combined =
            broadWave * 0.68 +
                secondaryWave * 0.32

        val normalized =
            (
                (combined + 1.0) *
                    0.5
                ).coerceIn(
                0.0,
                1.0
            ).toFloat()

        SNOW_BANK_MAX_HEIGHT_DP *
            (
                0.78f +
                    normalized * 0.22f
                )
    }

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
                .coerceAtMost(
                    snowBankColumnCapDp[wrapped]
                )
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

            /*
             * Search a slightly wider local neighborhood now that the source
             * map has twice the horizontal resolution. This keeps deposits
             * seeking low pockets instead of forming narrow spikes.
             */
            var targetColumn = baseColumn
            var bestScore = Float.MAX_VALUE

            for (offset in -SNOW_BANK_DEPOSIT_RADIUS..SNOW_BANK_DEPOSIT_RADIUS) {
                val candidate =
                    wrapColumn(
                        baseColumn + offset
                    )

                val cap =
                    snowBankColumnCapDp[
                        candidate
                    ]

                val current =
                    bankHeightsDp[
                        candidate
                    ]

                val room =
                    cap - current

                if (room <= 0.01f) {
                    continue
                }

                val normalizedHeight =
                    current /
                        cap.coerceAtLeast(
                            0.01f
                        )

                val score =
                    normalizedHeight +
                        abs(offset) *
                            SNOW_BANK_SEARCH_DISTANCE_PENALTY

                if (score < bestScore) {
                    bestScore = score
                    targetColumn =
                        candidate
                }
            }

            /*
             * Smooth 33-column Gaussian deposit. The total material per
             * landing remains approximately the same as the previous fast-test
             * build, but no individual ring of the deposit has a hard edge.
             */
            for (
                offset in
                -SNOW_BANK_DEPOSIT_RADIUS..
                    SNOW_BANK_DEPOSIT_RADIUS
            ) {
                addDeposit(
                    targetColumn + offset,
                    snowBankDepositKernelDp[
                        offset +
                            SNOW_BANK_DEPOSIT_RADIUS
                    ]
                )
            }
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

    /*
     * Do not even compose/start the snowfall simulation underneath Home's
     * loading curtain. The first snow frame begins only after Home's existing
     * reveal transaction has completed.
     */
    val homeReady by
        SeasonalSnowRuntime
            .homeReady
            .collectAsStateWithLifecycle()

    if (!homeReady) return

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
     * Mutable primitive arrays avoid hundreds of independent Compose states. A
     * single generation integer invalidates the Canvas only when a landing
     * actually changes the bank.
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
         * Shallow accumulated bank.
         *
         * The physical bank now has 768 samples. The visible height is derived
         * from a 17-sample Gaussian blend of neighboring FILL FRACTIONS, not a blur of
         * the final cap heights. That distinction is important: while building,
         * isolated landings blend into soft drifts; when every column is full,
         * every fraction is exactly 1 and the approved cap/wave is reproduced
         * exactly.
         *
         * Opaque fill plus a tiny feather on only the exposed top edge.
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

        fun wrappedBankColumn(index: Int): Int {
            val wrapped = index % SNOW_BANK_COLUMNS
            return if (wrapped < 0) {
                wrapped + SNOW_BANK_COLUMNS
            } else {
                wrapped
            }
        }

        fun bankFillProgress(column: Int): Float {
            val wrapped =
                wrappedBankColumn(column)
            val cap =
                snowBankColumnCapDp[wrapped]
                    .coerceAtLeast(0.01f)

            return (
                bankHeightsDp[wrapped] /
                    cap
                ).coerceIn(0f, 1f)
        }

        fun bankSurfaceY(column: Int): Float {
            var progress = 0f

            for (
                offset in
                -SNOW_BANK_RENDER_SMOOTH_RADIUS..
                    SNOW_BANK_RENDER_SMOOTH_RADIUS
            ) {
                progress +=
                    bankFillProgress(
                        column + offset
                    ) *
                        snowBankRenderKernel[
                            offset +
                                SNOW_BANK_RENDER_SMOOTH_RADIUS
                        ]
            }

            val heightDp =
                snowBankColumnCapDp[column] *
                    progress.coerceIn(
                        0f,
                        1f
                    )

            return size.height -
                heightDp * density
        }

        val firstY =
            bankSurfaceY(0)

        bankFillPath.moveTo(
            0f,
            size.height
        )

        bankFillPath.lineTo(
            0f,
            firstY
        )

        /*
         * Separate open path for only the exposed snow surface. This lets us
         * feather the visible boundary without blurring the opaque bank body
         * or accidentally outlining the screen edges/bottom.
         */
        bankTopPath.moveTo(
            0f,
            firstY
        )

        var previousX = 0f
        var previousY = firstY

        for (
            column in
            1 until SNOW_BANK_COLUMNS
        ) {
            val x =
                column *
                    columnWidth

            val y =
                bankSurfaceY(
                    column
                )

            val midpointX =
                (
                    previousX +
                        x
                    ) * 0.5f

            val midpointY =
                (
                    previousY +
                        y
                    ) * 0.5f

            bankFillPath
                .quadraticBezierTo(
                    previousX,
                    previousY,
                    midpointX,
                    midpointY
                )

            bankTopPath
                .quadraticBezierTo(
                    previousX,
                    previousY,
                    midpointX,
                    midpointY
                )

            previousX = x
            previousY = y
        }

        bankFillPath
            .quadraticBezierTo(
                previousX,
                previousY,
                previousX,
                previousY
            )

        bankTopPath
            .quadraticBezierTo(
                previousX,
                previousY,
                previousX,
                previousY
            )

        bankFillPath.lineTo(
            size.width,
            size.height
        )

        bankFillPath.close()

        /*
         * The bank itself remains completely opaque.
         */
        drawPath(
            path = bankFillPath,
            color = Color.White
        )

        /*
         * Anti-staircase feather.
         *
         * At only ~7.5dp tall, the approved shallow wave spans very few
         * physical vertical pixels on Android TV. More horizontal samples
         * cannot fix that final rasterization step. Two extremely cheap,
         * translucent strokes create a short coverage ramp outside the hard
         * white boundary and visually hide those pixel steps.
         *
         * This is NOT a blur pass and does not allocate an offscreen layer.
         */
        drawPath(
            path = bankTopPath,
            color = Color.White.copy(
                alpha = 0.08f
            ),
            style = Stroke(
                width = 2.4f * density,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        drawPath(
            path = bankTopPath,
            color = Color.White.copy(
                alpha = 0.20f
            ),
            style = Stroke(
                width = 1.2f * density,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}
