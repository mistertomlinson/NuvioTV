package com.nuvio.tv.ui.screens.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.LocalAppInForeground
import com.nuvio.tv.LocalBackgroundedAtMs
import com.nuvio.tv.domain.model.SeasonalEffectsConfig
import com.nuvio.tv.domain.model.SnowSizeProfile
import com.nuvio.tv.domain.model.SnowStartupMode
import com.nuvio.tv.ui.screens.settings.SeasonalEffectsViewModel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object SeasonalSnowRuntime {
    private val _homeScrolling = MutableStateFlow(false)
    val homeScrolling: StateFlow<Boolean> = _homeScrolling.asStateFlow()

    private val _homeReady = MutableStateFlow(false)
    val homeReady: StateFlow<Boolean> = _homeReady.asStateFlow()

    private val _restartGeneration = MutableStateFlow(0L)
    val restartGeneration: StateFlow<Long> = _restartGeneration.asStateFlow()

    private val _surfaceRecoveryGeneration = MutableStateFlow(0L)
    val surfaceRecoveryGeneration: StateFlow<Long> =
        _surfaceRecoveryGeneration.asStateFlow()

    private var lastHomeLoadSessionId: Long? = null

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

    fun noteHomeLoadSession(sessionId: Long) {
        if (lastHomeLoadSessionId != sessionId) {
            lastHomeLoadSessionId = sessionId
            restartSnowfall()
        }
    }

    fun restartSnowfall() {
        _restartGeneration.value = _restartGeneration.value + 1L
    }

    fun recoverSurfaceAfterForeground() {
        _surfaceRecoveryGeneration.value =
            _surfaceRecoveryGeneration.value + 1L
    }
}

private const val MAX_SNOW_PARTICLES = 120
private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val MAX_CATCH_UP_DELTA_NANOS = 500_000_000L
private const val SOFT_FADE_DURATION_SECONDS = 1.5
private const val FALL_IN_SPAWN_WINDOW_SECONDS = 3.0
private const val TRAVEL_HEIGHT_AT_1080P = 1104.0

private const val SNOW_SPRITE_SIZE = 24
private const val SNOW_SPRITE_COLUMNS = 4
private const val SNOW_SPRITE_ROWS = 3
private const val SNOW_SPRITE_COUNT = SNOW_SPRITE_COLUMNS * SNOW_SPRITE_ROWS

/*
 * Rendered size is the depth cue. These limits span the existing profiles after
 * their depth-layer multiplier, so size can drive speed without changing the
 * approved overall min/max motion envelope.
 */
private const val SNOW_MIN_VISUAL_RADIUS_AT_1080P = 0.58f
private const val SNOW_MAX_VISUAL_RADIUS_AT_1080P = 6.44f
private const val SNOW_MIN_FALL_PX_PER_SECOND_AT_1080P = 48f
private const val SNOW_MAX_FALL_PX_PER_SECOND_AT_1080P = 130f
private const val SNOW_MIN_MOTION_ASPECT_RATIO = 1.04f
private const val SNOW_MAX_MOTION_ASPECT_RATIO = 1.22f

/* Approved accumulated-bank geometry. */
private const val SNOW_BANK_COLUMNS = 768
private const val SNOW_BANK_MAX_HEIGHT_DP = 7.5f
private const val SNOW_BANK_DEPOSIT_RADIUS = 16
private const val SNOW_BANK_RENDER_SMOOTH_RADIUS = 8
private const val SNOW_BANK_DEPOSIT_TOTAL_DP = 4.0f
private const val SNOW_BANK_SEARCH_DISTANCE_PENALTY = 0.003125f

/*
 * Simulation state deliberately lives above the SurfaceView. Removing the
 * surface for Details/Player therefore pauses rendering without resetting the
 * approved fall/bank state. A genuine Home generation replaces this object.
 */
private class SeasonalSnowState {
    @Volatile
    var simulationNanos: Long = 0L

    val bankHeightsDp = FloatArray(SNOW_BANK_COLUMNS)

    @Volatile
    var bankGeneration: Int = 0
}

private val snowBankDepositKernelDp = run {
    val count = SNOW_BANK_DEPOSIT_RADIUS * 2 + 1
    val sigma = 6.4f
    val weights = FloatArray(count)
    var totalWeight = 0f

    for (index in weights.indices) {
        val offset = (index - SNOW_BANK_DEPOSIT_RADIUS).toFloat()
        val normalized = offset / sigma
        val weight = exp((-0.5f * normalized * normalized).toDouble()).toFloat()
        weights[index] = weight
        totalWeight += weight
    }

    val scale = SNOW_BANK_DEPOSIT_TOTAL_DP / totalWeight.coerceAtLeast(0.0001f)
    for (index in weights.indices) {
        weights[index] *= scale
    }
    weights
}

private val snowBankRenderKernel = run {
    val count = SNOW_BANK_RENDER_SMOOTH_RADIUS * 2 + 1
    val sigma = 4.0f
    val weights = FloatArray(count)
    var totalWeight = 0f

    for (index in weights.indices) {
        val offset = (index - SNOW_BANK_RENDER_SMOOTH_RADIUS).toFloat()
        val normalized = offset / sigma
        val weight = exp((-0.5f * normalized * normalized).toDouble()).toFloat()
        weights[index] = weight
        totalWeight += weight
    }

    val inverseTotal = 1f / totalWeight.coerceAtLeast(0.0001f)
    for (index in weights.indices) {
        weights[index] *= inverseTotal
    }
    weights
}

private val snowBankColumnCapDp = FloatArray(SNOW_BANK_COLUMNS) { index ->
    val denominator = (SNOW_BANK_COLUMNS - 1).coerceAtLeast(1).toDouble()
    val phase = index.toDouble() / denominator
    val broadWave = sin(phase * PI * 4.0 + 0.35)
    val secondaryWave = sin(phase * PI * 10.0 + 1.20)
    val combined = broadWave * 0.68 + secondaryWave * 0.32
    val normalized = (((combined + 1.0) * 0.5).coerceIn(0.0, 1.0)).toFloat()

    SNOW_BANK_MAX_HEIGHT_DP * (0.78f + normalized * 0.22f)
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

private val snowSpriteAtlas: Bitmap by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    buildSnowSpriteAtlas()
}

private fun buildSnowSpriteAtlas(): Bitmap {
    val atlasWidth = SNOW_SPRITE_COLUMNS * SNOW_SPRITE_SIZE
    val atlasHeight = SNOW_SPRITE_ROWS * SNOW_SPRITE_SIZE
    val pixels = IntArray(atlasWidth * atlasHeight)
    val center = (SNOW_SPRITE_SIZE - 1) * 0.5f

    repeat(SNOW_SPRITE_COUNT) { variant ->
        val cellX = (variant % SNOW_SPRITE_COLUMNS) * SNOW_SPRITE_SIZE
        val cellY = (variant / SNOW_SPRITE_COLUMNS) * SNOW_SPRITE_SIZE
        val rotation = variant * 0.47f
        val cosRotation = cos(rotation)
        val sinRotation = sin(rotation)
        val mainSigmaX = 2.55f + (variant % 4) * 0.34f
        val mainSigmaY = 2.65f + ((variant * 3) % 5) * 0.25f
        val lobeCount = 2 + variant % 4

        for (y in 0 until SNOW_SPRITE_SIZE) {
            for (x in 0 until SNOW_SPRITE_SIZE) {
                val dx = x + 0.5f - center
                val dy = y + 0.5f - center
                val rotatedX = dx * cosRotation + dy * sinRotation
                val rotatedY = -dx * sinRotation + dy * cosRotation

                var energy = 0.105f * exp(
                    (-(dx * dx + dy * dy) / (2f * 5.8f * 5.8f)).toDouble()
                ).toFloat()

                energy += 2.05f * exp(
                    (-0.5f * (
                        rotatedX * rotatedX / (mainSigmaX * mainSigmaX) +
                            rotatedY * rotatedY / (mainSigmaY * mainSigmaY)
                        )).toDouble()
                ).toFloat()

                repeat(lobeCount) { lobe ->
                    val angle = rotation +
                        lobe * (2f * PI.toFloat() / lobeCount) +
                        ((variant % 3) - 1) * 0.13f
                    val reach = 2.25f + ((variant + lobe) % 3) * 0.48f
                    val lobeX = cos(angle) * reach
                    val lobeY = sin(angle) * reach
                    val lx = dx - lobeX
                    val ly = dy - lobeY
                    val sigma = 1.20f + ((variant + lobe * 2) % 4) * 0.18f
                    val strength = 0.24f + ((variant + lobe) % 3) * 0.045f

                    energy += strength * exp(
                        (-(lx * lx + ly * ly) / (2f * sigma * sigma)).toDouble()
                    ).toFloat()
                }

                val coverage = (1f - exp(-energy.toDouble()).toFloat()).coerceIn(0f, 1f)
                val alpha = (coverage * 245f).roundToInt().coerceIn(0, 245)
                pixels[(cellY + y) * atlasWidth + cellX + x] =
                    (alpha shl 24) or 0x00FFFFFF
            }
        }
    }

    return Bitmap.createBitmap(
        pixels,
        atlasWidth,
        atlasHeight,
        Bitmap.Config.ARGB_8888
    )
}

private val snowParticles: List<SnowParticle> = buildList(MAX_SNOW_PARTICLES) {
    var state = 0x51A7C0DEu

    fun nextFloat(): Float {
        state = state * 1664525u + 1013904223u
        return (((state shr 8).toUInt().toLong() and 0x00FFFFFFL).toFloat() /
            0x01000000L)
    }

    repeat(MAX_SNOW_PARTICLES) {
        val xSeed = nextFloat()
        val ySeed = nextFloat()
        val sizeSeed = nextFloat()
        val speedSeed = nextFloat()
        val alphaSeed = nextFloat()
        val phase = nextFloat() * (2f * PI.toFloat())
        val swayFrequency = 0.45f + nextFloat() * 0.85f
        val depthSeed = nextFloat()
        val spriteSeed = (
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
                spriteIndex = (spriteSeed * SNOW_SPRITE_COUNT)
                    .toInt()
                    .coerceIn(0, SNOW_SPRITE_COUNT - 1)
            )
        )
    }
}

private fun snowDepth(particle: SnowParticle, layerCount: Int): Float =
    when (layerCount.coerceIn(1, 3)) {
        1 -> 0.88f
        2 -> if (particle.depthSeed < 0.58f) 0.68f else 1.12f
        else -> when {
            particle.depthSeed < 0.44f -> 0.68f
            particle.depthSeed < 0.80f -> 0.88f
            else -> 1.12f
        }
    }

private fun snowVisualRadiusAt1080(
    particle: SnowParticle,
    config: SeasonalEffectsConfig,
    depth: Float
): Float {
    val profileRadius = when (config.sizeProfile) {
        SnowSizeProfile.TINY -> 0.85f + particle.sizeSeed * 1.25f
        SnowSizeProfile.BALANCED -> 1.10f + particle.sizeSeed * 2.30f
        SnowSizeProfile.LARGE -> 2.00f + particle.sizeSeed * 3.75f
        SnowSizeProfile.MIXED ->
            0.85f + particle.sizeSeed * particle.sizeSeed * 4.75f
    }

    return profileRadius * depth
}

private fun snowVisualDepthFraction(radiusAt1080: Float): Float =
    (
        (radiusAt1080 - SNOW_MIN_VISUAL_RADIUS_AT_1080P) /
            (SNOW_MAX_VISUAL_RADIUS_AT_1080P - SNOW_MIN_VISUAL_RADIUS_AT_1080P)
        ).coerceIn(0f, 1f)

private fun snowFallPxPerSecondAt1080(radiusAt1080: Float): Float {
    val depthFraction = snowVisualDepthFraction(radiusAt1080)
    return SNOW_MIN_FALL_PX_PER_SECOND_AT_1080P +
        (SNOW_MAX_FALL_PX_PER_SECOND_AT_1080P -
            SNOW_MIN_FALL_PX_PER_SECOND_AT_1080P) *
        depthFraction
}

private fun completedLandingsAt(
    particle: SnowParticle,
    seconds: Double,
    config: SeasonalEffectsConfig
): Long {
    val spawnDelay = if (config.startupMode == SnowStartupMode.FALL_IN_FROM_TOP) {
        particle.ySeed.toDouble() * FALL_IN_SPAWN_WINDOW_SECONDS
    } else {
        0.0
    }

    if (seconds <= spawnDelay) return 0L

    val depth = snowDepth(particle, config.depthLayers)
    val radiusAt1080 = snowVisualRadiusAt1080(particle, config, depth)
    val fallPxPerSecondAt1080 = snowFallPxPerSecondAt1080(radiusAt1080)
    val cycleSeconds = TRAVEL_HEIGHT_AT_1080P /
        (fallPxPerSecondAt1080 * config.snowfallSpeed.multiplier)

    if (cycleSeconds <= 0.0) return 0L

    val activeSeconds = seconds - spawnDelay
    val initialProgress = if (config.startupMode == SnowStartupMode.FALL_IN_FROM_TOP) {
        0.0
    } else {
        particle.ySeed.toDouble()
    }

    return floor(initialProgress + activeSeconds / cycleSeconds)
        .toLong()
        .coerceAtLeast(0L)
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
        val after = (before + amountDp)
            .coerceAtMost(snowBankColumnCapDp[wrapped])
        if (after > before) {
            bankHeightsDp[wrapped] = after
            changed = true
        }
    }

    for (index in 0 until particleCount.coerceIn(0, MAX_SNOW_PARTICLES)) {
        val particle = snowParticles[index]
        val beforeLanding = completedLandingsAt(particle, beforeSeconds, config)
        val afterLanding = completedLandingsAt(particle, afterSeconds, config)
        val newLandings = (afterLanding - beforeLanding).coerceIn(0L, 3L).toInt()

        repeat(newLandings) { landingOffset ->
            val landingNumber = beforeLanding + landingOffset + 1L
            val cycleJitter = ((landingNumber % 97L).toFloat() * 0.173f) % 1f
            val normalizedX = (
                particle.xSeed + cycleJitter + particle.depthSeed * 0.11f
                ) % 1f
            val baseColumn = (normalizedX * columns)
                .toInt()
                .coerceIn(0, columns - 1)

            var targetColumn = baseColumn
            var bestScore = Float.MAX_VALUE

            for (offset in -SNOW_BANK_DEPOSIT_RADIUS..SNOW_BANK_DEPOSIT_RADIUS) {
                val candidate = wrapColumn(baseColumn + offset)
                val cap = snowBankColumnCapDp[candidate]
                val current = bankHeightsDp[candidate]
                val room = cap - current
                if (room <= 0.01f) continue

                val normalizedHeight = current / cap.coerceAtLeast(0.01f)
                val score = normalizedHeight +
                    abs(offset) * SNOW_BANK_SEARCH_DISTANCE_PENALTY

                if (score < bestScore) {
                    bestScore = score
                    targetColumn = candidate
                }
            }

            for (offset in -SNOW_BANK_DEPOSIT_RADIUS..SNOW_BANK_DEPOSIT_RADIUS) {
                addDeposit(
                    targetColumn + offset,
                    snowBankDepositKernelDp[offset + SNOW_BANK_DEPOSIT_RADIUS]
                )
            }
        }
    }

    return changed
}

@Composable
internal fun SeasonalEffectsOverlayHost(
    visible: Boolean,
    keepAlive: Boolean = visible,
    requireHomeReady: Boolean = true,
    viewModel: SeasonalEffectsViewModel = hiltViewModel()
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val appInForeground = LocalAppInForeground.current

    val christmasActive =
        remember(
            config,
            appInForeground
        ) {
            config.isChristmasActive()
        }

    /*
     * OFF tears the snow Popup/SurfaceView down completely. When the effect is
     * enabled again, wait to remount until a brand-new runtime generation has
     * been published. This avoids re-entering the snow tree with stale producer
     * identity while still keeping Holiday Effects genuinely absent when OFF.
     */
    val snowActivationReady = remember {
        androidx.compose.runtime.mutableStateOf(christmasActive)
    }

    LaunchedEffect(christmasActive) {
        if (!christmasActive) {
            snowActivationReady.value = false
            return@LaunchedEffect
        }

        if (!snowActivationReady.value) {
            SeasonalSnowRuntime.restartSnowfall()
            androidx.compose.runtime.withFrameNanos { }
            snowActivationReady.value = true
        }
    }

    if (!christmasActive || !snowActivationReady.value) return

    val homeReady by SeasonalSnowRuntime.homeReady.collectAsStateWithLifecycle()
    val restartGeneration by
        SeasonalSnowRuntime.restartGeneration.collectAsStateWithLifecycle()
    val surfaceRecoveryGeneration by
        SeasonalSnowRuntime.surfaceRecoveryGeneration.collectAsStateWithLifecycle()

    val snowState = remember(restartGeneration) {
        SeasonalSnowState()
    }

    val backgroundedAtMs = LocalBackgroundedAtMs.current

    LaunchedEffect(appInForeground) {
        if (appInForeground && backgroundedAtMs > 0L) {
            /*
             * A real Android-TV sleep can invalidate the translucent producer
             * while Compose state survives. Recreate only the producer/view on
             * every foreground return; keep particle/bank simulation intact.
             */
            SeasonalSnowRuntime.recoverSurfaceAfterForeground()
        }
    }

    /*
     * Details keeps the transparent surface alive so Home <-> Details can
     * crossfade the SAME snow frame without rebuilding anything. Stream and
     * Player do not keep the seasonal window at all.
     */
    if (
        !keepAlive ||
        (requireHomeReady && !homeReady)
    ) {
        return
    }

    /*
     * Keep the separate Popup window so Activity PixelCopy/backdrop capture
     * still excludes snow. The pixels inside that window now come from an
     * independent SurfaceView producer thread instead of Compose's UI frame
     * clock, so Home recomposition/platform transitions can miss a frame
     * without stopping snowfall.
     */
    Popup(
        alignment = Alignment.TopStart,
        onDismissRequest = {},
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            clippingEnabled = false
        )
    ) {
        DedicatedSnowSurface(
            config = config,
            snowState = snowState,
            surfaceRecoveryGeneration = surfaceRecoveryGeneration,
            visible = visible,
            appInForeground = appInForeground
        )
    }
}

@Composable
private fun DedicatedSnowSurface(
    config: SeasonalEffectsConfig,
    snowState: SeasonalSnowState,
    surfaceRecoveryGeneration: Long,
    visible: Boolean,
    appInForeground: Boolean
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = remember(
        snowState,
        surfaceRecoveryGeneration,
        context
    ) {
        SeasonalSnowSurfaceView(context).apply {
            bind(
                config = config,
                state = snowState,
                visible = visible,
                appInForeground = appInForeground
            )
        }
    }

    DisposableEffect(view) {
        onDispose {
            view.shutdown()
        }
    }

    androidx.compose.runtime.key(surfaceRecoveryGeneration) {
        AndroidView(
            factory = { view },
            modifier = Modifier.fillMaxSize(),
            update = {
                it.bind(
                    config = config,
                    state = snowState,
                    visible = visible,
                    appInForeground = appInForeground
                )
            }
        )
    }
}

private class SeasonalSnowSurfaceView(
    context: Context
) : SurfaceView(context), SurfaceHolder.Callback {

    @Volatile
    private var currentConfig: SeasonalEffectsConfig =
        SeasonalEffectsConfig.Recommended

    @Volatile
    private var currentState: SeasonalSnowState? = null

    private val running = AtomicBoolean(false)
    private var renderThread: Thread? = null

    @Volatile
    private var appInForeground: Boolean = true

    /*
     * Presentation alpha belongs to the producer thread, not Compose.
     *
     * That preserves the main reason the SurfaceView fixed platform/root
     * hesitation: no per-frame seasonal animation is scheduled on Compose's
     * frame clock.
     */
    private val presentationLock = Any()
    private var presentationInitialized = false
    private var presentationStartAlpha = 1f
    private var presentationTargetAlpha = 1f
    private var presentationStartNanos = 0L
    private val presentationDurationNanos = 350_000_000L

    @Volatile
    private var hiddenSurfaceCleared = false

    private val spritePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bankFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
    }
    private val bankOuterFeatherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        alpha = (0.08f * 255f).roundToInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bankInnerFeatherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        alpha = (0.20f * 255f).roundToInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val bankFillPath = Path()
    private val bankTopPath = Path()
    private var cachedBankGeneration = Int.MIN_VALUE
    private var cachedBankWidth = -1
    private var cachedBankHeight = -1

    private val srcRect = Rect()
    private val dstRect = RectF()

    init {
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
        isLongClickable = false
        setWillNotDraw(true)
    }

    fun bind(
        config: SeasonalEffectsConfig,
        state: SeasonalSnowState,
        visible: Boolean,
        appInForeground: Boolean
    ) {
        currentConfig = config
        this.appInForeground = appInForeground

        if (currentState !== state) {
            currentState = state
            cachedBankGeneration = Int.MIN_VALUE
        }

        updatePresentationTarget(visible)

        /*
         * SurfaceView can survive an Android-TV screensaver transition while
         * its old producer thread does not. Explicitly verify the producer on
         * every foreground bind instead of relying only on surfaceCreated().
         */
        if (appInForeground) {
            ensureRenderThread()
        }
    }

    private fun updatePresentationTarget(
        visible: Boolean
    ) {
        val target =
            if (visible) 1f else 0f
        val now =
            System.nanoTime()

        synchronized(presentationLock) {
            if (!presentationInitialized) {
                presentationInitialized = true
                presentationStartAlpha = target
                presentationTargetAlpha = target
                presentationStartNanos = now
                hiddenSurfaceCleared = target <= 0f
                return
            }

            if (presentationTargetAlpha == target) {
                return
            }

            val current =
                resolvePresentationAlphaLocked(now)

            presentationStartAlpha = current
            presentationTargetAlpha = target
            presentationStartNanos = now
            hiddenSurfaceCleared = false
        }
    }

    private fun resolvePresentationAlphaLocked(
        nowNanos: Long
    ): Float {
        if (!presentationInitialized) {
            return presentationTargetAlpha
        }

        val elapsed =
            (nowNanos - presentationStartNanos)
                .coerceAtLeast(0L)

        if (elapsed >= presentationDurationNanos) {
            return presentationTargetAlpha
        }

        val t =
            (
                elapsed.toDouble() /
                    presentationDurationNanos.toDouble()
                ).toFloat()
                .coerceIn(0f, 1f)

        val eased =
            FastOutSlowInEasing.transform(t)

        return presentationStartAlpha +
            (
                presentationTargetAlpha -
                    presentationStartAlpha
                ) *
            eased
    }

    private fun presentationAlpha(
        nowNanos: Long
    ): Float =
        synchronized(presentationLock) {
            resolvePresentationAlphaLocked(nowNanos)
        }

    private fun presentationTargetVisible(): Boolean =
        synchronized(presentationLock) {
            presentationTargetAlpha > 0.5f
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        /*
         * A freshly recreated translucent Popup SurfaceView can attach before
         * its Surface is useful on some Android-TV builds. surfaceCreated() is
         * still the normal path, but these idempotent retries ensure OFF -> ON
         * cannot leave a valid surface with no producer thread.
         */
        post { ensureRenderThread() }
        postDelayed({ ensureRenderThread() }, 96L)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        ensureRenderThread()
        post { ensureRenderThread() }
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int
    ) {
        cachedBankWidth = -1
        cachedBankHeight = -1
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopRenderThread()
    }

    override fun onDetachedFromWindow() {
        stopRenderThread()
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(
        hasWindowFocus: Boolean
    ) {
        super.onWindowFocusChanged(hasWindowFocus)

        if (hasWindowFocus) {
            /*
             * Important for Android-TV screensavers: some devices restore the
             * same SurfaceView/window without delivering a useful new
             * surfaceCreated callback.
             */
            ensureRenderThread()
        }
    }

    override fun onVisibilityChanged(
        changedView: android.view.View,
        visibility: Int
    ) {
        super.onVisibilityChanged(
            changedView,
            visibility
        )

        if (
            visibility ==
                android.view.View.VISIBLE
        ) {
            ensureRenderThread()
        }
    }

    fun shutdown() {
        stopRenderThread()
    }

    private fun ensureRenderThread() {
        if (
            !isAttachedToWindow ||
            !holder.surface.isValid
        ) {
            return
        }

        val existing =
            renderThread

        if (
            running.get() &&
            existing?.isAlive == true
        ) {
            return
        }

        /*
         * Recover from a producer that terminated while Android retained the
         * SurfaceView object across screensaver/window transitions.
         */
        running.set(false)
        startRenderThread()
    }

    private fun startRenderThread() {
        if (!running.compareAndSet(false, true)) return

        renderThread = Thread(
            { renderLoop() },
            "NuvioSeasonalSnow"
        ).apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    private fun stopRenderThread() {
        running.set(false)
        val thread = renderThread
        renderThread = null
        if (thread != null && thread !== Thread.currentThread()) {
            thread.interrupt()
            runCatching { thread.join(250L) }
        }
    }

    private fun renderLoop() {
        var previousFrameNanos = 0L
        var nextFrameNanos = System.nanoTime()

        while (running.get()) {
            try {
                /*
                 * Do not accumulate invisible time while Android has the app
                 * backgrounded/screensaver-covered. A >30s real resume is
                 * already handled by SeasonalSnowRuntime.restartSnowfall().
                 */
                if (!appInForeground) {
                    previousFrameNanos = 0L
                    nextFrameNanos = System.nanoTime()
                    Thread.sleep(50L)
                    continue
                }

                val config = currentConfig
                val state = currentState
                val targetFps =
                    config.targetFps.coerceIn(1, 60)
                val frameIntervalNanos =
                    NANOS_PER_SECOND.toLong() /
                        targetFps

                val now = System.nanoTime()

                if (now < nextFrameNanos) {
                    val remaining =
                        nextFrameNanos - now

                    Thread.sleep(
                        remaining / 1_000_000L,
                        (
                            remaining %
                                1_000_000L
                            ).toInt()
                    )
                    continue
                }

                val frameNow =
                    System.nanoTime()

                nextFrameNanos =
                    frameNow +
                        frameIntervalNanos

                val alpha =
                    presentationAlpha(
                        frameNow
                    )

                val targetVisible =
                    presentationTargetVisible()

                /*
                 * Once Home's 350ms fade-out is complete, freeze BOTH falling
                 * snow and bank accumulation while Details is open.
                 */
                if (
                    alpha <= 0.001f &&
                    !targetVisible
                ) {
                    previousFrameNanos = 0L

                    if (!hiddenSurfaceCleared) {
                        clearSurfaceFrame()
                        hiddenSurfaceCleared = true
                    }

                    Thread.sleep(50L)
                    continue
                }

                hiddenSurfaceCleared = false

                if (state != null) {
                    if (previousFrameNanos != 0L) {
                        val delta =
                            (
                                frameNow -
                                    previousFrameNanos
                                ).coerceIn(
                                0L,
                                MAX_CATCH_UP_DELTA_NANOS
                            )

                        advanceSimulation(
                            state,
                            config,
                            delta
                        )
                    }

                    previousFrameNanos =
                        frameNow

                    drawFrame(
                        state = state,
                        config = config,
                        presentationAlpha = alpha
                    )
                } else {
                    previousFrameNanos =
                        frameNow
                }
            } catch (_: InterruptedException) {
                if (!running.get()) {
                    return
                }

                previousFrameNanos = 0L
                nextFrameNanos =
                    System.nanoTime()
            } catch (_: Throwable) {
                /*
                 * Surface replacement can race one producer frame on some TV
                 * builds. Keep the producer alive and retry instead of leaving
                 * a permanently frozen final buffer.
                 */
                previousFrameNanos = 0L
                nextFrameNanos =
                    System.nanoTime()

                try {
                    Thread.sleep(16L)
                } catch (_: InterruptedException) {
                    if (!running.get()) {
                        return
                    }
                }
            }
        }
    }

    private fun clearSurfaceFrame() {
        if (
            !holder.surface.isValid ||
            width <= 0 ||
            height <= 0
        ) {
            return
        }

        val canvas =
            try {
                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.M
                ) {
                    holder.lockHardwareCanvas()
                } else {
                    holder.lockCanvas()
                }
            } catch (_: Throwable) {
                null
            } ?: return

        try {
            canvas.drawColor(
                android.graphics.Color.TRANSPARENT,
                PorterDuff.Mode.CLEAR
            )
        } finally {
            runCatching {
                holder.unlockCanvasAndPost(
                    canvas
                )
            }
        }
    }

    private fun advanceSimulation(
        state: SeasonalSnowState,
        config: SeasonalEffectsConfig,
        deltaNanos: Long
    ) {
        if (deltaNanos <= 0L) return

        val beforeNanos = state.simulationNanos
        val afterNanos = beforeNanos + deltaNanos
        val bankChanged = depositSnowBankForInterval(
            bankHeightsDp = state.bankHeightsDp,
            particleCount = config.particleCount.coerceIn(1, MAX_SNOW_PARTICLES),
            beforeSeconds = beforeNanos / NANOS_PER_SECOND,
            afterSeconds = afterNanos / NANOS_PER_SECOND,
            config = config
        )

        state.simulationNanos = afterNanos
        if (bankChanged) {
            state.bankGeneration = state.bankGeneration + 1
        }
    }

    private fun drawFrame(
        state: SeasonalSnowState,
        config: SeasonalEffectsConfig,
        presentationAlpha: Float
    ) {
        if (!holder.surface.isValid || width <= 0 || height <= 0) return

        val canvas = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                holder.lockHardwareCanvas()
            } else {
                holder.lockCanvas()
            }
        } catch (_: Throwable) {
            null
        } ?: return

        try {
            canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            drawSnowParticles(
                canvas = canvas,
                state = state,
                config = config,
                presentationAlpha = presentationAlpha
            )
            drawSnowBank(
                canvas = canvas,
                state = state,
                presentationAlpha = presentationAlpha
            )
        } finally {
            runCatching { holder.unlockCanvasAndPost(canvas) }
        }
    }

    private fun drawSnowParticles(
        canvas: Canvas,
        state: SeasonalSnowState,
        config: SeasonalEffectsConfig,
        presentationAlpha: Float
    ) {
        val seconds = state.simulationNanos / NANOS_PER_SECOND
        val scale1080 = height / 1080f
        val windPxPerSecond = config.wind.pixelsPerSecondAt1080p * scale1080
        val swayAmplitude = config.sway.amplitudeAt1080p * scale1080
        val fallMultiplier = config.snowfallSpeed.multiplier
        val opacityMultiplier = config.opacity.multiplier
        val margin = 12f * scale1080
        val travelHeight = height + margin * 2f
        val layerCount = config.depthLayers.coerceIn(1, 3)
        val effectiveCount = config.particleCount.coerceIn(1, MAX_SNOW_PARTICLES)

        val startupOpacity = when (config.startupMode) {
            SnowStartupMode.SOFT_FADE_IN -> {
                val t = (seconds / SOFT_FADE_DURATION_SECONDS)
                    .coerceIn(0.0, 1.0)
                    .toFloat()
                t * t * (3f - 2f * t)
            }
            else -> 1f
        }

        if (startupOpacity <= 0f && config.startupMode == SnowStartupMode.SOFT_FADE_IN) {
            return
        }

        val atlas = snowSpriteAtlas

        for (index in 0 until effectiveCount) {
            val particle = snowParticles[index]
            val depth = snowDepth(particle, layerCount)
            val radiusAt1080 = snowVisualRadiusAt1080(particle, config, depth)
            val depthFraction = snowVisualDepthFraction(radiusAt1080)
            val fallPxPerSecondAt1080 = snowFallPxPerSecondAt1080(radiusAt1080)

            val motionSeconds = if (
                config.startupMode == SnowStartupMode.FALL_IN_FROM_TOP
            ) {
                val spawnDelaySeconds =
                    particle.ySeed.toDouble() * FALL_IN_SPAWN_WINDOW_SECONDS
                if (seconds < spawnDelaySeconds) continue
                seconds - spawnDelaySeconds
            } else {
                seconds
            }

            val velocityY =
                fallPxPerSecondAt1080 *
                    fallMultiplier *
                    scale1080
            val yTravel =
                motionSeconds *
                    velocityY

            val y = if (config.startupMode == SnowStartupMode.FALL_IN_FROM_TOP) {
                (yTravel.toFloat() % travelHeight) - margin
            } else {
                (particle.ySeed * travelHeight + yTravel).toFloat() %
                    travelHeight - margin
            }

            val windVelocityX = windPxPerSecond * depth
            val directionalWind = motionSeconds * windVelocityX
            val swayScale = 0.72f + particle.depthSeed * 0.56f
            val swayAngularVelocity =
                particle.swayFrequency * 2f * PI.toFloat()
            val swayPhase =
                motionSeconds * swayAngularVelocity +
                    particle.phase
            val sway = if (swayAmplitude == 0f) {
                0f
            } else {
                sin(swayPhase).toFloat() *
                    swayAmplitude *
                    swayScale
            }
            val swayVelocityX = if (swayAmplitude == 0f) {
                0f
            } else {
                cos(swayPhase).toFloat() *
                    swayAngularVelocity *
                    swayAmplitude *
                    swayScale
            }

            var x = particle.xSeed * width + directionalWind.toFloat() + sway
            x %= width.toFloat()
            if (x < 0f) x += width.toFloat()

            val radius = radiusAt1080 * scale1080
            val baseAlpha = 0.34f + particle.alphaSeed * 0.44f
            val steadyAlpha = (
                baseAlpha * opacityMultiplier * (0.78f + depth * 0.20f)
                ).coerceIn(0.10f, 0.96f)
            val alpha =
                steadyAlpha *
                    startupOpacity *
                    presentationAlpha
            val spriteDrawSize = (radius * 5.6f).coerceAtLeast(5f)

            /*
             * Preserve approximately the same sprite area while stretching its
             * long axis with apparent depth. Rotating that axis into the actual
             * velocity vector makes wind and sway read as motion rather than a
             * vertical oval pasted onto a diagonal trajectory.
             */
            val motionAspectRatio =
                SNOW_MIN_MOTION_ASPECT_RATIO +
                    (SNOW_MAX_MOTION_ASPECT_RATIO -
                        SNOW_MIN_MOTION_ASPECT_RATIO) *
                    depthFraction
            val areaPreservingScale = sqrt(motionAspectRatio)
            val spriteWidth = spriteDrawSize / areaPreservingScale
            val spriteHeight = spriteDrawSize * areaPreservingScale
            val velocityX = windVelocityX + swayVelocityX
            val travelRotationDegrees =
                (
                    atan2(
                        velocityY.toDouble(),
                        velocityX.toDouble()
                    ) *
                        180.0 /
                        PI -
                        90.0
                    ).toFloat()

            val sprite = particle.spriteIndex
            val sourceX = (sprite % SNOW_SPRITE_COLUMNS) * SNOW_SPRITE_SIZE
            val sourceY = (sprite / SNOW_SPRITE_COLUMNS) * SNOW_SPRITE_SIZE

            srcRect.set(
                sourceX,
                sourceY,
                sourceX + SNOW_SPRITE_SIZE,
                sourceY + SNOW_SPRITE_SIZE
            )
            dstRect.set(
                x - spriteWidth * 0.5f,
                y - spriteHeight * 0.5f,
                x + spriteWidth * 0.5f,
                y + spriteHeight * 0.5f
            )
            spritePaint.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)

            val saveCount = canvas.save()
            canvas.rotate(
                travelRotationDegrees,
                x,
                y
            )
            canvas.drawBitmap(atlas, srcRect, dstRect, spritePaint)
            canvas.restoreToCount(saveCount)
        }
    }

    private fun drawSnowBank(
        canvas: Canvas,
        state: SeasonalSnowState,
        presentationAlpha: Float
    ) {
        if (
            cachedBankGeneration != state.bankGeneration ||
            cachedBankWidth != width ||
            cachedBankHeight != height
        ) {
            rebuildBankPaths(state)
            cachedBankGeneration = state.bankGeneration
            cachedBankWidth = width
            cachedBankHeight = height
        }

        bankFillPaint.alpha =
            (
                255f *
                    presentationAlpha
                ).roundToInt()
                .coerceIn(0, 255)

        bankOuterFeatherPaint.alpha =
            (
                0.08f *
                    255f *
                    presentationAlpha
                ).roundToInt()
                .coerceIn(0, 255)

        bankInnerFeatherPaint.alpha =
            (
                0.20f *
                    255f *
                    presentationAlpha
                ).roundToInt()
                .coerceIn(0, 255)

        canvas.drawPath(
            bankFillPath,
            bankFillPaint
        )
        canvas.drawPath(
            bankTopPath,
            bankOuterFeatherPaint
        )
        canvas.drawPath(
            bankTopPath,
            bankInnerFeatherPaint
        )
    }

    private fun rebuildBankPaths(state: SeasonalSnowState) {
        bankFillPath.reset()
        bankTopPath.reset()

        val density = resources.displayMetrics.density
        val columnWidth = if (SNOW_BANK_COLUMNS > 1) {
            width.toFloat() / (SNOW_BANK_COLUMNS - 1)
        } else {
            width.toFloat()
        }

        fun wrappedBankColumn(index: Int): Int {
            val wrapped = index % SNOW_BANK_COLUMNS
            return if (wrapped < 0) wrapped + SNOW_BANK_COLUMNS else wrapped
        }

        fun bankFillProgress(column: Int): Float {
            val wrapped = wrappedBankColumn(column)
            val cap = snowBankColumnCapDp[wrapped].coerceAtLeast(0.01f)
            return (state.bankHeightsDp[wrapped] / cap).coerceIn(0f, 1f)
        }

        fun bankSurfaceY(column: Int): Float {
            var progress = 0f
            for (
                offset in -SNOW_BANK_RENDER_SMOOTH_RADIUS..
                    SNOW_BANK_RENDER_SMOOTH_RADIUS
            ) {
                progress += bankFillProgress(column + offset) *
                    snowBankRenderKernel[offset + SNOW_BANK_RENDER_SMOOTH_RADIUS]
            }

            val heightDp = snowBankColumnCapDp[column] *
                progress.coerceIn(0f, 1f)
            return height - heightDp * density
        }

        val firstY = bankSurfaceY(0)
        bankFillPath.moveTo(0f, height.toFloat())
        bankFillPath.lineTo(0f, firstY)
        bankTopPath.moveTo(0f, firstY)

        var previousX = 0f
        var previousY = firstY

        for (column in 1 until SNOW_BANK_COLUMNS) {
            val x = column * columnWidth
            val y = bankSurfaceY(column)
            val midpointX = (previousX + x) * 0.5f
            val midpointY = (previousY + y) * 0.5f

            bankFillPath.quadTo(previousX, previousY, midpointX, midpointY)
            bankTopPath.quadTo(previousX, previousY, midpointX, midpointY)
            previousX = x
            previousY = y
        }

        bankFillPath.quadTo(previousX, previousY, previousX, previousY)
        bankTopPath.quadTo(previousX, previousY, previousX, previousY)
        bankFillPath.lineTo(width.toFloat(), height.toFloat())
        bankFillPath.close()

        bankOuterFeatherPaint.strokeWidth = 2.4f * density
        bankInnerFeatherPaint.strokeWidth = 1.2f * density
    }
}
