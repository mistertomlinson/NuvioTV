package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.LocalHomeHeroTrailerPlaying
import com.nuvio.tv.domain.model.SeasonalEffectsConfig
import com.nuvio.tv.domain.model.SnowSizeProfile
import com.nuvio.tv.domain.model.SnowStartupMode
import com.nuvio.tv.ui.screens.settings.SeasonalEffectsViewModel
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive

/**
 * Tiny bridge from ModernHomeContent's real LazyColumn scroll state to the
 * route-level overlay. Keeping it out of Home's UI state prevents snow tuning
 * from invalidating/rebuilding catalog rows.
 */
internal object SeasonalSnowRuntime {
    private val _homeScrolling = MutableStateFlow(false)
    val homeScrolling: StateFlow<Boolean> = _homeScrolling.asStateFlow()

    fun setHomeScrolling(scrolling: Boolean) {
        if (_homeScrolling.value != scrolling) {
            _homeScrolling.value = scrolling
        }
    }
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
    val shapeSeed: Float,
    val aspectSeed: Float,
    val softnessSeed: Float
)

private const val MAX_SNOW_PARTICLES = 120
private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val MAX_FRAME_DELTA_NANOS = 100_000_000L
private const val SOFT_FADE_DURATION_SECONDS = 1.5
private const val FALL_IN_SPAWN_WINDOW_SECONDS = 3.0
private const val TWO_PI = (2.0 * PI).toFloat()

private val snowParticles: List<SnowParticle> = buildList(MAX_SNOW_PARTICLES) {
    // Deterministic LCG: no Random object and no particle allocation at runtime.
    // Extra shape values are derived from the original motion seeds so this keeps
    // the same overall motion distribution while improving appearance.
    var state = 0x51A7C0DEu
    fun nextFloat(): Float {
        state = state * 1664525u + 1013904223u
        return ((state shr 8).toUInt().toLong() and 0x00FFFFFFL).toFloat() / 0x01000000L
    }

    repeat(MAX_SNOW_PARTICLES) {
        val xSeed = nextFloat()
        val ySeed = nextFloat()
        val sizeSeed = nextFloat()
        val speedSeed = nextFloat()
        val alphaSeed = nextFloat()
        val phase = nextFloat() * TWO_PI
        val swayFrequency = 0.45f + nextFloat() * 0.85f
        val depthSeed = nextFloat()

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
                shapeSeed =
                    (xSeed * 0.73f + ySeed * 0.41f + sizeSeed * 0.87f + depthSeed * 0.29f) % 1f,
                aspectSeed =
                    (ySeed * 0.67f + alphaSeed * 0.53f + speedSeed * 0.31f + depthSeed * 0.47f) % 1f,
                softnessSeed =
                    (xSeed * 0.37f + alphaSeed * 0.89f + sizeSeed * 0.43f + depthSeed * 0.61f) % 1f
            )
        )
    }
}

@Composable
internal fun SeasonalEffectsOverlayHost(
    viewModel: SeasonalEffectsViewModel = hiltViewModel()
) {
    val config by viewModel.config.collectAsStateWithLifecycle()

    // Critical fast path: when disabled there is no Canvas and no frame clock.
    if (!config.enabled) return

    val isScrolling by SeasonalSnowRuntime.homeScrolling.collectAsStateWithLifecycle()
    val heroTrailerPlaying = LocalHomeHeroTrailerPlaying.current.value

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
    val pauseForTrailer = config.pauseDuringHeroTrailer && heroTrailerPlaying
    val freezeForScroll = config.freezeWhileScrolling && isScrolling
    val paused = pauseForTrailer || freezeForScroll
    val effectiveFps = if (isScrolling && config.reduceFpsWhileScrolling) {
        minOf(config.targetFps, 15)
    } else {
        config.targetFps
    }.coerceIn(1, 60)

    val requestedCount = config.particleCount.coerceIn(1, MAX_SNOW_PARTICLES)
    val effectiveCount = if (isScrolling && config.reduceDensityWhileScrolling) {
        (requestedCount * 0.55f).toInt().coerceAtLeast(1)
    } else {
        requestedCount
    }

    // Changing startup style while this composable survives restarts only the
    // snow simulation; other tuning changes continue from the current motion.
    var simulationNanos by remember(config.startupMode) { mutableLongStateOf(0L) }

    LaunchedEffect(effectiveFps, paused, config.startupMode) {
        val publishIntervalNanos = NANOS_PER_SECOND.toLong() / effectiveFps
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

                val delta = (now - previousVsync)
                    .coerceIn(0L, MAX_FRAME_DELTA_NANOS)
                previousVsync = now

                if (!paused) {
                    accumulatedSimulationNanos += delta
                    if (now - lastPublishedVsync >= publishIntervalNanos) {
                        simulationNanos += accumulatedSimulationNanos
                        accumulatedSimulationNanos = 0L
                        lastPublishedVsync = now
                    }
                } else {
                    // Don't let a pause build a catch-up jump.
                    accumulatedSimulationNanos = 0L
                    lastPublishedVsync = now
                }
            }
        }
    }

    val blurRadius = config.blur.radiusDp
    val drawModifier = Modifier
        .fillMaxSize()
        .then(
            if (blurRadius > 0f) {
                // Experimental by design: whole-layer blur can force an
                // offscreen render target on some Android TV GPUs.
                Modifier.blur(blurRadius.dp)
            } else {
                Modifier
            }
        )

    Canvas(modifier = drawModifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas

        val seconds = simulationNanos / NANOS_PER_SECOND
        val scale1080 = size.height / 1080f
        val windPxPerSecond = config.wind.pixelsPerSecondAt1080p * scale1080
        val swayAmplitude = config.sway.amplitudeAt1080p * scale1080
        val fallMultiplier = config.snowfallSpeed.multiplier
        val opacityMultiplier = config.opacity.multiplier
        val margin = 12f * scale1080
        val travelHeight = size.height + margin * 2f
        val layerCount = config.depthLayers.coerceIn(1, 3)

        val startupOpacity = when (config.startupMode) {
            SnowStartupMode.SOFT_FADE_IN -> {
                val t = (seconds / SOFT_FADE_DURATION_SECONDS)
                    .coerceIn(0.0, 1.0)
                    .toFloat()
                // Smoothstep avoids visible linear endpoints without another
                // animator or transition object.
                t * t * (3f - 2f * t)
            }
            else -> 1f
        }

        if (startupOpacity <= 0f && config.startupMode == SnowStartupMode.SOFT_FADE_IN) {
            return@Canvas
        }

        for (index in 0 until effectiveCount) {
            val particle = snowParticles[index]
            val depthBand = when (layerCount) {
                1 -> 1
                2 -> if (particle.depthSeed < 0.58f) 0 else 2
                else -> when {
                    particle.depthSeed < 0.44f -> 0
                    particle.depthSeed < 0.80f -> 1
                    else -> 2
                }
            }
            val depth = when (depthBand) {
                0 -> 0.68f
                1 -> 0.88f
                else -> 1.12f
            }

            val baseFallPxPerSecond = 52f + particle.speedSeed * 62f
            val motionSeconds = if (config.startupMode == SnowStartupMode.FALL_IN_FROM_TOP) {
                val spawnDelaySeconds =
                    particle.ySeed.toDouble() * FALL_IN_SPAWN_WINDOW_SECONDS
                if (seconds < spawnDelaySeconds) {
                    continue
                }
                seconds - spawnDelaySeconds
            } else {
                seconds
            }

            val yTravel =
                motionSeconds * baseFallPxPerSecond * fallMultiplier * depth * scale1080
            val y = if (config.startupMode == SnowStartupMode.FALL_IN_FROM_TOP) {
                (yTravel.toFloat() % travelHeight) - margin
            } else {
                (particle.ySeed * travelHeight + yTravel).toFloat() % travelHeight - margin
            }

            val directionalWind = motionSeconds * windPxPerSecond * depth
            val sway = if (swayAmplitude == 0f) {
                0f
            } else {
                sin(
                    motionSeconds * particle.swayFrequency * 2.0 * PI +
                        particle.phase
                ).toFloat() * swayAmplitude *
                    (0.72f + particle.depthSeed * 0.56f)
            }
            var x = particle.xSeed * size.width + directionalWind.toFloat() + sway
            x %= size.width
            if (x < 0f) x += size.width

            val radius1080 = when (config.sizeProfile) {
                SnowSizeProfile.TINY -> 0.85f + particle.sizeSeed * 1.25f
                SnowSizeProfile.BALANCED -> 1.10f + particle.sizeSeed * 2.30f
                SnowSizeProfile.LARGE -> 2.00f + particle.sizeSeed * 3.75f
                SnowSizeProfile.MIXED -> 0.85f + particle.sizeSeed * particle.sizeSeed * 4.75f
            }
            val radius = radius1080 * scale1080 * depth
            val baseAlpha = 0.34f + particle.alphaSeed * 0.44f
            val steadyAlpha =
                (baseAlpha * opacityMultiplier * (0.78f + depth * 0.20f))
                    .coerceIn(0.10f, 0.96f)
            val alpha = steadyAlpha * startupOpacity
            val center = Offset(x, y)

            // Prefer the soft irregular look you liked. Most flakes can get the
            // cheap fake-softness treatment without needing a real blur pass.
            val softHalo = when (depthBand) {
                0 -> particle.softnessSeed > 0.40f
                1 -> particle.softnessSeed > 0.22f
                else -> true
            }

            drawSoftIrregularSnowFlake(
                center = center,
                radius = radius,
                alpha = alpha,
                aspectSeed = particle.aspectSeed,
                shapeSeed = particle.shapeSeed,
                softHalo = softHalo,
                emphasizeSecondaryLobe = depthBand >= 1 || particle.shapeSeed > 0.52f
            )
        }
    }
}

private fun DrawScope.drawSoftIrregularSnowFlake(
    center: Offset,
    radius: Float,
    alpha: Float,
    aspectSeed: Float,
    shapeSeed: Float,
    softHalo: Boolean,
    emphasizeSecondaryLobe: Boolean
) {
    val widthFactor = (0.58f + aspectSeed * 0.96f).coerceIn(0.58f, 1.54f)
    val heightFactor = (1.28f - (widthFactor - 1f) * 0.46f).coerceIn(0.58f, 1.46f)
    val halfWidth = radius * widthFactor
    val halfHeight = radius * heightFactor

    val lobeSeed = (shapeSeed * 1.73f) % 1f
    val lobeOffsetX = (shapeSeed - 0.5f) * radius * 0.78f
    val lobeOffsetY = (lobeSeed - 0.5f) * radius * 0.52f
    val lobeScale = if (emphasizeSecondaryLobe) {
        0.42f + lobeSeed * 0.28f
    } else {
        0.28f + lobeSeed * 0.18f
    }

    if (softHalo) {
        val haloScale = 1.46f
        drawOval(
            color = Color.White.copy(alpha = alpha * 0.085f),
            topLeft = Offset(
                center.x - halfWidth * haloScale,
                center.y - halfHeight * haloScale
            ),
            size = Size(
                halfWidth * 2f * haloScale,
                halfHeight * 2f * haloScale
            )
        )
        if (emphasizeSecondaryLobe) {
            val haloLobeRadius = radius * lobeScale * 1.65f
            drawCircle(
                color = Color.White.copy(alpha = alpha * 0.065f),
                radius = haloLobeRadius,
                center = Offset(center.x + lobeOffsetX * 1.10f, center.y + lobeOffsetY * 1.10f)
            )
        }
    }

    drawOval(
        color = Color.White.copy(alpha = alpha * 0.96f),
        topLeft = Offset(center.x - halfWidth, center.y - halfHeight),
        size = Size(halfWidth * 2f, halfHeight * 2f)
    )

    val lobeRadius = radius * lobeScale
    drawCircle(
        color = Color.White.copy(alpha = (alpha * 0.88f).coerceAtMost(1f)),
        radius = lobeRadius,
        center = Offset(center.x + lobeOffsetX, center.y + lobeOffsetY)
    )
}
