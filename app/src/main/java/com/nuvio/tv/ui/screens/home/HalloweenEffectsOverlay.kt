package com.nuvio.tv.ui.screens.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal object HalloweenEffectsRuntime {
    private val lastActivity =
        AtomicLong(System.nanoTime())

    @Volatile
    private var focusedPosterOwner: Any? = null

    @Volatile
    private var focusedPosterBounds: RectF? = null

    @Volatile
    private var focusedPosterPerchEligible = false

    fun noteUserActivity() {
        lastActivity.set(System.nanoTime())
    }

    fun lastUserActivityNanos(): Long =
        lastActivity.get()

    fun updateFocusedPosterBounds(
        owner: Any,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        perchEligible: Boolean
    ) {
        synchronized(this) {
            focusedPosterOwner = owner
            focusedPosterBounds =
                RectF(
                    left,
                    top,
                    right,
                    bottom
                )
            focusedPosterPerchEligible =
                perchEligible
        }
    }

    fun setFocusedPosterPerchEligible(
        owner: Any,
        eligible: Boolean
    ) {
        synchronized(this) {
            if (focusedPosterOwner !== owner) {
                focusedPosterOwner = owner
                focusedPosterBounds = null
            }

            focusedPosterPerchEligible =
                eligible
        }
    }

    fun clearFocusedPosterBounds(
        owner: Any
    ) {
        synchronized(this) {
            if (focusedPosterOwner === owner) {
                focusedPosterOwner = null
                focusedPosterBounds = null
                focusedPosterPerchEligible = false
            }
        }
    }

    fun focusedPosterPerchTarget(): RectF? =
        synchronized(this) {
            if (!focusedPosterPerchEligible) {
                null
            } else {
                focusedPosterBounds?.let {
                    RectF(it)
                }
            }
        }
}

private const val HALLOWEEN_FPS = 30
private const val NANOS_PER_SECOND =
    1_000_000_000.0
private const val MAX_DELTA_NANOS =
    500_000_000L
private const val FADE_NANOS =
    350_000_000L

private const val BAT_COUNT = 6
private const val IDLE_PERCH_NANOS =
    20_000_000_000L
private const val PERCH_COOLDOWN_NANOS =
    12_000_000_000L
private const val APPROACH_NANOS =
    1_050_000_000L
private const val TAKEOFF_NANOS =
    850_000_000L

private const val LIGHTNING_CYCLE_SECONDS =
    180.0

private val lightningStarts =
    doubleArrayOf(
        8.0,
        47.0,
        101.0,
        151.0
    )

private enum class PerchPhase {
    NONE,
    APPROACH,
    PERCHED,
    TAKEOFF
}

private class HalloweenState {
    var simulationNanos = 0L

    var perchPhase =
        PerchPhase.NONE

    var perchStartNanos = 0L

    var perchBounds: RectF? = null

    var perchExitDirection = 1f

    var lastPerchEndNanos =
        Long.MIN_VALUE

    var lastObservedActivity =
        HalloweenEffectsRuntime
            .lastUserActivityNanos()
}

private data class BatSeed(
    val phaseSeconds: Float,
    val cycleSeconds: Float,
    val activeSeconds: Float,
    val laneSeed: Float,
    val depthSeed: Float,
    val pathSeed: Float,
    val wingSeed: Float,
    val directionSeed: Float,
    val scaleSeed: Float
)

private val bats =
    buildList(BAT_COUNT) {
        var state =
            0x5A17B47DL

        fun nextFloat(): Float {
            state =
                (
                    state *
                        1664525L +
                        1013904223L
                    ) and
                    0xFFFFFFFFL

            return (
                (
                    state ushr 8
                    ) and
                    0x00FFFFFFL
                ).toFloat() /
                0x01000000L
        }

        val phaseOffsets =
            floatArrayOf(
                0.0f,
                0.48f,
                0.96f,
                5.8f,
                10.7f,
                15.4f
            )

        repeat(BAT_COUNT) { index ->
            add(
                BatSeed(
                    phaseSeconds =
                        phaseOffsets[index] +
                            nextFloat() *
                                1.6f,
                    cycleSeconds =
                        17f +
                            nextFloat() *
                                11f,
                    activeSeconds =
                        2.45f +
                            nextFloat() *
                                1.25f,
                    laneSeed =
                        nextFloat(),
                    depthSeed =
                        nextFloat(),
                    pathSeed =
                        nextFloat(),
                    wingSeed =
                        nextFloat(),
                    directionSeed =
                        nextFloat(),
                    scaleSeed =
                        nextFloat()
                )
            )
        }
    }

private data class LightningFrame(
    val ambientAlpha: Float,
    val graveyardAlpha: Float,
    val boltAlpha: Float,
    val eventIndex: Int
)

@Composable
internal fun HalloweenEffectsOverlayHost(
    visible: Boolean,
    keepAlive: Boolean,
    appInForeground: Boolean,
    generation: Long
) {
    val state =
        remember(generation) {
            HalloweenState()
        }

    if (!keepAlive) {
        return
    }

    Popup(
        alignment = Alignment.TopStart,
        onDismissRequest = {},
        properties =
            PopupProperties(
                focusable = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                clippingEnabled = false
            )
    ) {
        DedicatedHalloweenSurface(
            state = state,
            visible = visible,
            appInForeground =
                appInForeground
        )
    }
}

@Composable
private fun DedicatedHalloweenSurface(
    state: HalloweenState,
    visible: Boolean,
    appInForeground: Boolean
) {
    val context =
        androidx.compose.ui.platform
            .LocalContext.current

    val view =
        remember(
            state,
            context
        ) {
            HalloweenSurfaceView(
                context
            ).apply {
                bind(
                    state = state,
                    visible = visible,
                    appInForeground =
                        appInForeground
                )
            }
        }

    DisposableEffect(view) {
        onDispose {
            view.shutdown()
        }
    }

    AndroidView(
        factory = { view },
        modifier =
            Modifier.fillMaxSize(),
        update = {
            it.bind(
                state = state,
                visible = visible,
                appInForeground =
                    appInForeground
            )
        }
    )
}

private class HalloweenSurfaceView(
    context: Context
) :
    SurfaceView(context),
    SurfaceHolder.Callback {

    @Volatile
    private var currentState:
        HalloweenState? = null

    @Volatile
    private var appInForeground = true

    private val running =
        AtomicBoolean(false)

    private var renderThread:
        Thread? = null

    private val presentationLock =
        Any()

    private var presentationInitialized =
        false

    private var presentationStartAlpha =
        1f

    private var presentationTargetAlpha =
        1f

    private var presentationStartNanos =
        0L

    @Volatile
    private var hiddenSurfaceCleared =
        false

    private val silhouettePaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color =
                Color.rgb(
                    8,
                    8,
                    11
                )
            style =
                Paint.Style.FILL
        }

    private val leftWingPath =
        Path()

    private val rightWingPath =
        Path()

    private val bodyPath =
        Path()

    private val flashPaint =
        Paint().apply {
            color =
                Color.rgb(
                    248,
                    249,
                    255
                )
            style =
                Paint.Style.FILL
        }

    private val graveyardPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color = Color.BLACK
            style =
                Paint.Style.FILL
        }

    private val graveyardStrokePaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color = Color.BLACK
            style =
                Paint.Style.STROKE
            strokeCap =
                Paint.Cap.ROUND
            strokeJoin =
                Paint.Join.ROUND
        }

    private val graveyardFillPath =
        Path()

    private val graveyardDetailPath =
        Path()

    private var graveyardWidth =
        -1

    private var graveyardHeight =
        -1

    private val boltTrunkPath =
        Path()

    private val boltBranchPath =
        Path()

    private var cachedBoltEventIndex =
        Int.MIN_VALUE

    private var cachedBoltWidth =
        -1

    private var cachedBoltHeight =
        -1

    private val boltGlowPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color =
                Color.rgb(
                    190,
                    214,
                    255
                )
            style =
                Paint.Style.STROKE
            strokeCap =
                Paint.Cap.ROUND
            strokeJoin =
                Paint.Join.ROUND
        }

    private val boltMidPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color =
                Color.rgb(
                    226,
                    236,
                    255
                )
            style =
                Paint.Style.STROKE
            strokeCap =
                Paint.Cap.ROUND
            strokeJoin =
                Paint.Join.ROUND
        }

    private val boltCorePaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color = Color.WHITE
            style =
                Paint.Style.STROKE
            strokeCap =
                Paint.Cap.ROUND
            strokeJoin =
                Paint.Join.ROUND
        }

    init {
        setZOrderOnTop(true)

        holder.setFormat(
            PixelFormat.TRANSLUCENT
        )

        holder.addCallback(this)

        isFocusable = false
        isFocusableInTouchMode =
            false
        isClickable = false
        isLongClickable = false

        setWillNotDraw(true)
    }

    fun bind(
        state: HalloweenState,
        visible: Boolean,
        appInForeground: Boolean
    ) {
        currentState = state

        this.appInForeground =
            appInForeground

        updatePresentationTarget(
            visible
        )

        if (appInForeground) {
            ensureRenderThread()
        }
    }

    private fun updatePresentationTarget(
        visible: Boolean
    ) {
        val target =
            if (visible) {
                1f
            } else {
                0f
            }

        val now =
            System.nanoTime()

        synchronized(
            presentationLock
        ) {
            if (!presentationInitialized) {
                presentationInitialized =
                    true

                presentationStartAlpha =
                    target

                presentationTargetAlpha =
                    target

                presentationStartNanos =
                    now

                hiddenSurfaceCleared =
                    target <= 0f

                return
            }

            if (
                presentationTargetAlpha ==
                target
            ) {
                return
            }

            val current =
                resolvePresentationAlphaLocked(
                    now
                )

            presentationStartAlpha =
                current

            presentationTargetAlpha =
                target

            presentationStartNanos =
                now

            hiddenSurfaceCleared =
                false
        }
    }

    private fun resolvePresentationAlphaLocked(
        nowNanos: Long
    ): Float {
        if (!presentationInitialized) {
            return presentationTargetAlpha
        }

        val elapsed =
            (
                nowNanos -
                    presentationStartNanos
                ).coerceAtLeast(
                0L
            )

        if (
            elapsed >=
            FADE_NANOS
        ) {
            return presentationTargetAlpha
        }

        val t =
            (
                elapsed.toDouble() /
                    FADE_NANOS.toDouble()
                ).toFloat()
                .coerceIn(
                    0f,
                    1f
                )

        val eased =
            FastOutSlowInEasing
                .transform(t)

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
        synchronized(
            presentationLock
        ) {
            resolvePresentationAlphaLocked(
                nowNanos
            )
        }

    private fun targetVisible():
        Boolean =
        synchronized(
            presentationLock
        ) {
            presentationTargetAlpha >
                0.5f
        }

    override fun surfaceCreated(
        holder: SurfaceHolder
    ) {
        ensureRenderThread()
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int
    ) {
        graveyardWidth = -1
        graveyardHeight = -1
        cachedBoltWidth = -1
        cachedBoltHeight = -1
    }

    override fun surfaceDestroyed(
        holder: SurfaceHolder
    ) {
        stopRenderThread()
    }

    override fun onDetachedFromWindow() {
        stopRenderThread()

        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(
        hasWindowFocus: Boolean
    ) {
        super.onWindowFocusChanged(
            hasWindowFocus
        )

        if (hasWindowFocus) {
            ensureRenderThread()
        }
    }

    override fun onVisibilityChanged(
        changedView:
            android.view.View,
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

        running.set(false)

        startRenderThread()
    }

    private fun startRenderThread() {
        if (
            !running.compareAndSet(
                false,
                true
            )
        ) {
            return
        }

        renderThread =
            Thread(
                {
                    renderLoop()
                },
                "NuvioHalloweenV2"
            ).apply {
                priority =
                    Thread.NORM_PRIORITY +
                        1
                start()
            }
    }

    private fun stopRenderThread() {
        running.set(false)

        val thread =
            renderThread

        renderThread = null

        if (
            thread != null &&
            thread !==
            Thread.currentThread()
        ) {
            thread.interrupt()

            runCatching {
                thread.join(120L)
            }
        }
    }

    private fun renderLoop() {
        var previousFrameNanos =
            0L

        var nextFrameNanos =
            System.nanoTime()

        val frameIntervalNanos =
            (
                NANOS_PER_SECOND /
                    HALLOWEEN_FPS
                ).toLong()

        while (running.get()) {
            try {
                if (!appInForeground) {
                    previousFrameNanos =
                        0L

                    nextFrameNanos =
                        System.nanoTime()

                    Thread.sleep(50L)

                    continue
                }

                val state =
                    currentState

                if (state == null) {
                    Thread.sleep(16L)

                    continue
                }

                val now =
                    System.nanoTime()

                if (
                    now <
                    nextFrameNanos
                ) {
                    val remaining =
                        nextFrameNanos -
                            now

                    Thread.sleep(
                        remaining /
                            1_000_000L,
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

                if (
                    alpha <= 0.001f &&
                    !targetVisible()
                ) {
                    previousFrameNanos =
                        0L

                    if (!hiddenSurfaceCleared) {
                        clearSurfaceFrame()

                        hiddenSurfaceCleared =
                            true
                    }

                    Thread.sleep(50L)

                    continue
                }

                hiddenSurfaceCleared =
                    false

                if (
                    previousFrameNanos !=
                    0L
                ) {
                    val delta =
                        (
                            frameNow -
                                previousFrameNanos
                            ).coerceIn(
                            0L,
                            MAX_DELTA_NANOS
                        )

                    state.simulationNanos +=
                        delta
                }

                previousFrameNanos =
                    frameNow

                updatePerchState(
                    state
                )

                drawFrame(
                    state = state,
                    presentationAlpha =
                        alpha
                )
            } catch (
                _: InterruptedException
            ) {
                if (!running.get()) {
                    return
                }

                previousFrameNanos =
                    0L

                nextFrameNanos =
                    System.nanoTime()
            } catch (
                _: Throwable
            ) {
                previousFrameNanos =
                    0L

                nextFrameNanos =
                    System.nanoTime()

                try {
                    Thread.sleep(16L)
                } catch (
                    _: InterruptedException
                ) {
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
            } catch (
                _: Throwable
            ) {
                null
            } ?: return

        try {
            canvas.drawColor(
                Color.TRANSPARENT,
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

    private fun drawFrame(
        state: HalloweenState,
        presentationAlpha: Float
    ) {
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
            } catch (
                _: Throwable
            ) {
                null
            } ?: return

        try {
            canvas.drawColor(
                Color.TRANSPARENT,
                PorterDuff.Mode.CLEAR
            )

            val seconds =
                state.simulationNanos /
                    NANOS_PER_SECOND

            drawFlyingBats(
                canvas = canvas,
                seconds = seconds,
                presentationAlpha =
                    presentationAlpha
            )

            drawPerchingBat(
                canvas = canvas,
                state = state,
                seconds = seconds,
                presentationAlpha =
                    presentationAlpha
            )

            val lightning =
                lightningFrame(
                    seconds
                )

            if (
                lightning.ambientAlpha >
                0f
            ) {
                flashPaint.alpha =
                    (
                        lightning.ambientAlpha *
                            presentationAlpha *
                            255f
                        ).roundToInt()
                        .coerceIn(
                            0,
                            255
                        )

                canvas.drawRect(
                    0f,
                    0f,
                    width.toFloat(),
                    height.toFloat(),
                    flashPaint
                )
            }

            if (
                lightning.graveyardAlpha >
                0f
            ) {
                drawGraveyard(
                    canvas = canvas,
                    alpha =
                        lightning.graveyardAlpha *
                            presentationAlpha
                )
            }

            if (
                lightning.boltAlpha >
                0f
            ) {
                drawLightningBolt(
                    canvas = canvas,
                    alpha =
                        lightning.boltAlpha *
                            presentationAlpha,
                    eventIndex =
                        lightning.eventIndex
                )
            }
        } finally {
            runCatching {
                holder.unlockCanvasAndPost(
                    canvas
                )
            }
        }
    }

    private fun drawFlyingBats(
        canvas: Canvas,
        seconds: Double,
        presentationAlpha: Float
    ) {
        val screenScale =
            height /
                1080f

        bats.forEachIndexed {
                index,
                bat ->

            val cycle =
                bat.cycleSeconds
                    .toDouble()

            val active =
                bat.activeSeconds
                    .toDouble()

            var local =
                (
                    seconds +
                        bat.phaseSeconds
                            .toDouble()
                    ) %
                    cycle

            if (local < 0.0) {
                local +=
                    cycle
            }

            if (local > active) {
                return@forEachIndexed
            }

            val t =
                (
                    local /
                        active
                    ).toFloat()
                    .coerceIn(
                    0f,
                    1f
                )

            val fromLeft =
                bat.directionSeed <
                    0.5f

            val direction =
                if (fromLeft) {
                    1f
                } else {
                    -1f
                }

            val nx =
                if (fromLeft) {
                    -0.14f +
                        1.28f *
                            t
                } else {
                    1.14f -
                        1.28f *
                            t
                }

            val dnx =
                direction *
                    1.28f

            val pi =
                PI.toFloat()

            val lane =
                0.10f +
                    bat.laneSeed *
                        0.42f

            val waveA =
                pi *
                    2f *
                    t +
                    bat.pathSeed *
                        pi *
                        2f

            val waveB =
                pi *
                    5f *
                    t +
                    bat.wingSeed *
                        pi *
                        2f

            val ny =
                lane -
                    0.055f *
                        sin(
                            pi *
                                t
                        ) +
                    0.034f *
                        sin(waveA) +
                    0.014f *
                        sin(waveB)

            val dny =
                -0.055f *
                    pi *
                    cos(
                        pi *
                            t
                    ) +
                    0.034f *
                    pi *
                    2f *
                    cos(waveA) +
                    0.014f *
                    pi *
                    5f *
                    cos(waveB)

            val baseDepth =
                1.62f +
                    bat.depthSeed *
                        0.92f

            val approachDepth =
                0.52f +
                    (
                        1f -
                            bat.depthSeed
                        ) *
                        0.48f

            val depthWave =
                pi *
                    2f *
                    t +
                    bat.pathSeed *
                        4.7f

            val depth =
                (
                    baseDepth -
                        approachDepth *
                            sin(
                                pi *
                                    t
                            ) +
                        0.12f *
                            sin(
                                depthWave
                            )
                    ).coerceAtLeast(
                    0.68f
                )

            val dDepth =
                -approachDepth *
                    pi *
                    cos(
                        pi *
                            t
                    ) +
                    0.12f *
                    pi *
                    2f *
                    cos(
                        depthWave
                    )

            val dx =
                dnx *
                    2f *
                    depth +
                    (
                        nx -
                            0.5f
                        ) *
                    2f *
                    dDepth

            val dy =
                -dny *
                    1.20f *
                    depth +
                    (
                        0.5f -
                            ny
                        ) *
                    1.20f *
                    dDepth

            val dz =
                dDepth

            val yaw =
                atan2(
                    dx,
                    dz
                )

            val horizontalSpeed =
                sqrt(
                    dx *
                        dx +
                        dz *
                            dz
                ).coerceAtLeast(
                    0.001f
                )

            val pitch =
                atan2(
                    dy,
                    horizontalSpeed
                ) *
                    0.72f

            val roll =
                (
                    -dny *
                        1.9f +
                        sin(
                            waveA +
                                index *
                                    0.63f
                        ) *
                        0.11f
                    ).coerceIn(
                    -0.48f,
                    0.48f
                )

            val wingHz =
                7.8f +
                    bat.wingSeed *
                        3.6f

            val glideSignal =
                sin(
                    seconds *
                        0.62 +
                        bat.pathSeed
                            .toDouble() *
                            7.4
                ).toFloat()

            val flapAmplitude =
                if (
                    glideSignal >
                    0.90f
                ) {
                    0.16f
                } else {
                    1f
                }

            val wingPhase =
                (
                    seconds *
                        wingHz.toDouble() *
                        PI *
                        2.0 +
                        bat.wingSeed
                            .toDouble() *
                            PI *
                            2.0
                    ).toFloat()

            val flapLeft =
                sin(
                    wingPhase
                ) *
                    flapAmplitude

            val flapRight =
                sin(
                    wingPhase +
                        0.09f +
                        bat.pathSeed *
                            0.05f
                ) *
                    flapAmplitude

            val baseSize =
                (
                    34f +
                        bat.scaleSeed *
                            12f
                    ) *
                    screenScale

            val alpha =
                (
                    (
                        0.70f +
                            bat.depthSeed *
                                0.20f
                        ) *
                        presentationAlpha *
                        255f
                    ).roundToInt()
                    .coerceIn(
                    0,
                    235
                )

            drawBat3D(
                canvas = canvas,
                centerX =
                    nx *
                        width,
                centerY =
                    ny *
                        height,
                depth = depth,
                baseSizePx =
                    baseSize,
                yaw = yaw,
                pitch = pitch,
                roll = roll,
                flapLeft =
                    flapLeft,
                flapRight =
                    flapRight,
                fold = 0f,
                alpha = alpha
            )
        }
    }

    private fun updatePerchState(
        state: HalloweenState
    ) {
        val activity =
            HalloweenEffectsRuntime
                .lastUserActivityNanos()

        val eligibleTarget =
            HalloweenEffectsRuntime
                .focusedPosterPerchTarget()

        if (
            activity !=
            state.lastObservedActivity
        ) {
            state.lastObservedActivity =
                activity

            if (
                state.perchPhase ==
                    PerchPhase.APPROACH ||
                state.perchPhase ==
                    PerchPhase.PERCHED
            ) {
                beginPerchTakeoff(
                    state
                )
            }
        }

        if (
            eligibleTarget == null &&
            (
                state.perchPhase ==
                    PerchPhase.APPROACH ||
                    state.perchPhase ==
                    PerchPhase.PERCHED
                )
        ) {
            beginPerchTakeoff(
                state
            )
        }

        when (state.perchPhase) {
            PerchPhase.NONE -> {
                val idleFor =
                    System.nanoTime() -
                        activity

                val cooldownDone =
                    state.lastPerchEndNanos ==
                        Long.MIN_VALUE ||
                        state.simulationNanos -
                            state.lastPerchEndNanos >=
                        PERCH_COOLDOWN_NANOS

                if (
                    idleFor >=
                        IDLE_PERCH_NANOS &&
                    cooldownDone &&
                    eligibleTarget != null
                ) {
                    state.perchBounds =
                        RectF(
                            eligibleTarget
                        )

                    state.perchExitDirection =
                        if (
                            eligibleTarget
                                .centerX() <
                            width *
                                0.5f
                        ) {
                            -1f
                        } else {
                            1f
                        }

                    state.perchPhase =
                        PerchPhase.APPROACH

                    state.perchStartNanos =
                        state.simulationNanos
                }
            }

            PerchPhase.APPROACH -> {
                if (
                    state.simulationNanos -
                        state.perchStartNanos >=
                    APPROACH_NANOS
                ) {
                    state.perchPhase =
                        PerchPhase.PERCHED

                    state.perchStartNanos =
                        state.simulationNanos
                }
            }

            PerchPhase.PERCHED ->
                Unit

            PerchPhase.TAKEOFF -> {
                if (
                    state.simulationNanos -
                        state.perchStartNanos >=
                    TAKEOFF_NANOS
                ) {
                    state.perchPhase =
                        PerchPhase.NONE

                    state.perchBounds =
                        null

                    state.lastPerchEndNanos =
                        state.simulationNanos
                }
            }
        }
    }

    private fun beginPerchTakeoff(
        state: HalloweenState
    ) {
        if (
            state.perchPhase ==
                PerchPhase.TAKEOFF ||
            state.perchPhase ==
                PerchPhase.NONE
        ) {
            return
        }

        state.perchPhase =
            PerchPhase.TAKEOFF

        state.perchStartNanos =
            state.simulationNanos
    }

    private fun drawPerchingBat(
        canvas: Canvas,
        state: HalloweenState,
        seconds: Double,
        presentationAlpha: Float
    ) {
        val bounds =
            state.perchBounds
                ?: return

        if (
            state.perchPhase ==
            PerchPhase.NONE
        ) {
            return
        }

        val screenScale =
            height /
                1080f

        val targetX =
            (
                bounds.left +
                    bounds.width() *
                        0.72f
                ).coerceIn(
                34f,
                width -
                    34f
            )

        val targetY =
            (
                bounds.top -
                    8f *
                        screenScale
                ).coerceIn(
                22f,
                height -
                    22f
            )

        val alpha =
            (
                presentationAlpha *
                    238f
                ).roundToInt()
                .coerceIn(
                0,
                238
            )

        val elapsed =
            state.simulationNanos -
                state.perchStartNanos

        when (state.perchPhase) {
            PerchPhase.APPROACH -> {
                val p =
                    (
                        elapsed.toDouble() /
                            APPROACH_NANOS
                                .toDouble()
                        ).toFloat()
                        .coerceIn(
                        0f,
                        1f
                    )

                val eased =
                    smoothStep(p)

                val startX =
                    targetX +
                        if (
                            targetX <
                            width *
                                0.5f
                        ) {
                            -width *
                                0.34f
                        } else {
                            width *
                                0.34f
                        }

                val startY =
                    (
                        targetY -
                            height *
                                0.23f
                        ).coerceAtLeast(
                        28f
                    )

                val centerX =
                    lerpFloat(
                        startX,
                        targetX,
                        eased
                    )

                val centerY =
                    lerpFloat(
                        startY,
                        targetY,
                        eased
                    ) -
                        sin(
                            p *
                                PI.toFloat()
                        ) *
                        28f *
                        screenScale

                val depth =
                    lerpFloat(
                        1.80f,
                        0.88f,
                        eased
                    )

                val yaw =
                    lerpFloat(
                        if (
                            startX <
                            targetX
                        ) {
                            1.05f
                        } else {
                            -1.05f
                        },
                        0.16f,
                        eased
                    )

                val pitch =
                    lerpFloat(
                        -0.18f,
                        0.08f,
                        eased
                    )

                val roll =
                    lerpFloat(
                        if (
                            startX <
                            targetX
                        ) {
                            -0.26f
                        } else {
                            0.26f
                        },
                        0f,
                        eased
                    )

                val wingPhase =
                    (
                        seconds *
                            10.8 *
                            PI *
                            2.0
                        ).toFloat()

                val flap =
                    sin(
                        wingPhase
                    ) *
                        (
                            1f -
                                0.70f *
                                    eased
                            )

                drawBat3D(
                    canvas = canvas,
                    centerX = centerX,
                    centerY = centerY,
                    depth = depth,
                    baseSizePx =
                        38f *
                            screenScale,
                    yaw = yaw,
                    pitch = pitch,
                    roll = roll,
                    flapLeft = flap,
                    flapRight =
                        sin(
                            wingPhase +
                                0.08f
                        ) *
                            (
                                1f -
                                    0.70f *
                                        eased
                                ),
                    fold =
                        (
                            eased *
                                0.92f
                            ).coerceIn(
                            0f,
                            0.92f
                        ),
                    alpha = alpha
                )
            }

            PerchPhase.PERCHED -> {
                val tinyLift =
                    sin(
                        seconds *
                            2.2
                    ).toFloat() *
                        0.8f *
                        screenScale

                drawBat3D(
                    canvas = canvas,
                    centerX = targetX,
                    centerY =
                        targetY +
                            tinyLift,
                    depth = 0.88f,
                    baseSizePx =
                        38f *
                            screenScale,
                    yaw = 0.12f,
                    pitch = 0.10f,
                    roll = 0f,
                    flapLeft =
                        -0.18f,
                    flapRight =
                        -0.16f,
                    fold = 0.94f,
                    alpha = alpha
                )
            }

            PerchPhase.TAKEOFF -> {
                val p =
                    (
                        elapsed.toDouble() /
                            TAKEOFF_NANOS
                                .toDouble()
                        ).toFloat()
                        .coerceIn(
                        0f,
                        1f
                    )

                val eased =
                    smoothStep(p)

                val endX =
                    targetX +
                        state.perchExitDirection *
                            width *
                            0.40f

                val endY =
                    (
                        targetY -
                            height *
                                0.30f
                        ).coerceAtLeast(
                        20f
                    )

                val centerX =
                    lerpFloat(
                        targetX,
                        endX,
                        eased
                    )

                val centerY =
                    lerpFloat(
                        targetY,
                        endY,
                        eased
                    ) -
                        sin(
                            p *
                                PI.toFloat()
                        ) *
                        34f *
                        screenScale

                val depth =
                    lerpFloat(
                        0.88f,
                        1.55f,
                        eased
                    )

                val yaw =
                    state.perchExitDirection *
                        lerpFloat(
                            0.12f,
                            1.16f,
                            eased
                        )

                val wingPhase =
                    (
                        seconds *
                            12.2 *
                            PI *
                            2.0
                        ).toFloat()

                val flap =
                    sin(
                        wingPhase
                    )

                drawBat3D(
                    canvas = canvas,
                    centerX = centerX,
                    centerY = centerY,
                    depth = depth,
                    baseSizePx =
                        39f *
                            screenScale,
                    yaw = yaw,
                    pitch = -0.20f,
                    roll =
                        -state
                            .perchExitDirection *
                            0.24f *
                            eased,
                    flapLeft = flap,
                    flapRight =
                        sin(
                            wingPhase +
                                0.10f
                        ),
                    fold =
                        (
                            0.94f *
                                (
                                    1f -
                                        min(
                                            1f,
                                            p *
                                                2.8f
                                        )
                                    )
                            ).coerceIn(
                            0f,
                            0.94f
                        ),
                    alpha = alpha
                )
            }

            PerchPhase.NONE ->
                Unit
        }
    }

    private fun drawBat3D(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        depth: Float,
        baseSizePx: Float,
        yaw: Float,
        pitch: Float,
        roll: Float,
        flapLeft: Float,
        flapRight: Float,
        fold: Float,
        alpha: Int
    ) {
        silhouettePaint.alpha =
            alpha.coerceIn(
                0,
                255
            )

        drawWing(
            canvas = canvas,
            path = leftWingPath,
            side = -1f,
            centerX = centerX,
            centerY = centerY,
            depth = depth,
            baseSizePx =
                baseSizePx,
            yaw = yaw,
            pitch = pitch,
            roll = roll,
            flap = flapLeft,
            fold = fold
        )

        drawWing(
            canvas = canvas,
            path = rightWingPath,
            side = 1f,
            centerX = centerX,
            centerY = centerY,
            depth = depth,
            baseSizePx =
                baseSizePx,
            yaw = yaw,
            pitch = pitch,
            roll = roll,
            flap = flapRight,
            fold = fold
        )

        drawBody(
            canvas = canvas,
            centerX = centerX,
            centerY = centerY,
            depth = depth,
            baseSizePx =
                baseSizePx,
            yaw = yaw,
            pitch = pitch,
            roll = roll
        )
    }

    private fun drawWing(
        canvas: Canvas,
        path: Path,
        side: Float,
        centerX: Float,
        centerY: Float,
        depth: Float,
        baseSizePx: Float,
        yaw: Float,
        pitch: Float,
        roll: Float,
        flap: Float,
        fold: Float
    ) {
        path.reset()

        val span =
            1f -
                fold *
                    0.70f

        val lift =
            flap *
                (
                    0.34f -
                        fold *
                            0.22f
                    )

        fun point(
            move: Boolean,
            x: Float,
            y: Float,
            z: Float
        ) {
            appendProjected(
                path = path,
                move = move,
                centerX = centerX,
                centerY = centerY,
                depth = depth,
                baseSizePx =
                    baseSizePx,
                yaw = yaw,
                pitch = pitch,
                roll = roll,
                localX = x,
                localY = y,
                localZ = z
            )
        }

        point(
            true,
            side * 0.12f,
            0.04f,
            0.08f
        )

        point(
            false,
            side *
                0.50f *
                span,
            0.04f +
                lift *
                    0.55f,
            0.02f
        )

        point(
            false,
            side *
                0.91f *
                span,
            0.02f +
                lift *
                    0.90f,
            -0.02f
        )

        point(
            false,
            side *
                1.26f *
                span,
            -0.02f +
                lift,
            0.10f +
                side *
                    0.025f
        )

        point(
            false,
            side *
                1.00f *
                span,
            -0.25f +
                lift *
                    0.48f,
            0.13f
        )

        point(
            false,
            side *
                0.78f *
                span,
            -0.15f +
                lift *
                    0.34f,
            0.07f
        )

        point(
            false,
            side *
                0.58f *
                span,
            -0.27f +
                lift *
                    0.24f,
            0.02f
        )

        point(
            false,
            side *
                0.36f *
                span,
            -0.15f +
                lift *
                    0.15f,
            -0.02f
        )

        point(
            false,
            side * 0.10f,
            -0.20f,
            -0.10f
        )

        path.close()

        canvas.drawPath(
            path,
            silhouettePaint
        )
    }

    private fun drawBody(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        depth: Float,
        baseSizePx: Float,
        yaw: Float,
        pitch: Float,
        roll: Float
    ) {
        bodyPath.reset()

        fun point(
            move: Boolean,
            x: Float,
            y: Float,
            z: Float
        ) {
            appendProjected(
                path = bodyPath,
                move = move,
                centerX = centerX,
                centerY = centerY,
                depth = depth,
                baseSizePx =
                    baseSizePx,
                yaw = yaw,
                pitch = pitch,
                roll = roll,
                localX = x,
                localY = y,
                localZ = z
            )
        }

        point(
            true,
            -0.11f,
            0.08f,
            0.38f
        )

        point(
            false,
            -0.17f,
            0.20f,
            0.43f
        )

        point(
            false,
            -0.04f,
            0.15f,
            0.47f
        )

        point(
            false,
            0.04f,
            0.15f,
            0.47f
        )

        point(
            false,
            0.17f,
            0.20f,
            0.43f
        )

        point(
            false,
            0.11f,
            0.08f,
            0.38f
        )

        point(
            false,
            0.16f,
            0.03f,
            0.14f
        )

        point(
            false,
            0.13f,
            -0.08f,
            -0.18f
        )

        point(
            false,
            0.05f,
            -0.11f,
            -0.46f
        )

        point(
            false,
            0f,
            -0.04f,
            -0.58f
        )

        point(
            false,
            -0.05f,
            -0.11f,
            -0.46f
        )

        point(
            false,
            -0.13f,
            -0.08f,
            -0.18f
        )

        point(
            false,
            -0.16f,
            0.03f,
            0.14f
        )

        bodyPath.close()

        canvas.drawPath(
            bodyPath,
            silhouettePaint
        )
    }

    private fun appendProjected(
        path: Path,
        move: Boolean,
        centerX: Float,
        centerY: Float,
        depth: Float,
        baseSizePx: Float,
        yaw: Float,
        pitch: Float,
        roll: Float,
        localX: Float,
        localY: Float,
        localZ: Float
    ) {
        val cosRoll =
            cos(roll)

        val sinRoll =
            sin(roll)

        val rollX =
            localX *
                cosRoll -
                localY *
                    sinRoll

        val rollY =
            localX *
                sinRoll +
                localY *
                    cosRoll

        val rollZ =
            localZ

        val cosPitch =
            cos(pitch)

        val sinPitch =
            sin(pitch)

        val pitchY =
            rollY *
                cosPitch -
                rollZ *
                    sinPitch

        val pitchZ =
            rollY *
                sinPitch +
                rollZ *
                    cosPitch

        val pitchX =
            rollX

        val cosYaw =
            cos(yaw)

        val sinYaw =
            sin(yaw)

        val rotatedX =
            pitchX *
                cosYaw +
                pitchZ *
                    sinYaw

        val rotatedZ =
            -pitchX *
                sinYaw +
                pitchZ *
                    cosYaw

        val rotatedY =
            pitchY

        val effectiveDepth =
            (
                depth +
                    rotatedZ *
                        0.24f
                ).coerceAtLeast(
                0.38f
            )

        val perspectiveScale =
            baseSizePx /
                effectiveDepth

        val screenX =
            centerX +
                rotatedX *
                    perspectiveScale

        val screenY =
            centerY -
                rotatedY *
                    perspectiveScale

        if (move) {
            path.moveTo(
                screenX,
                screenY
            )
        } else {
            path.lineTo(
                screenX,
                screenY
            )
        }
    }

    private fun lightningFrame(
        seconds: Double
    ): LightningFrame {
        val cycleNumber =
            floor(
                seconds /
                    LIGHTNING_CYCLE_SECONDS
            ).toInt()

        var local =
            seconds %
                LIGHTNING_CYCLE_SECONDS

        if (local < 0.0) {
            local +=
                LIGHTNING_CYCLE_SECONDS
        }

        var age =
            -1.0

        var eventIndex =
            -1

        lightningStarts
            .forEachIndexed {
                    index,
                    start ->

                val candidate =
                    local -
                        start

                if (
                    candidate >= 0.0 &&
                    candidate <= 1.10
                ) {
                    age =
                        candidate

                    eventIndex =
                        cycleNumber *
                            lightningStarts.size +
                            index
                }
            }

        if (age < 0.0) {
            return LightningFrame(
                ambientAlpha = 0f,
                graveyardAlpha = 0f,
                boltAlpha = 0f,
                eventIndex = -1
            )
        }

        val firstFlash =
            when {
                age < 0.045 ->
                    (
                        age /
                            0.045 *
                            0.70
                        ).toFloat()

                age < 0.115 ->
                    (
                        (
                            1.0 -
                                (
                                    age -
                                        0.045
                                    ) /
                                0.070
                            ) *
                            0.70
                        ).toFloat()

                else ->
                    0f
            }

        val secondFlash =
            when {
                age < 0.180 ->
                    0f

                age < 0.225 ->
                    (
                        (
                            age -
                                0.180
                            ) /
                            0.045 *
                            0.82
                        ).toFloat()

                age < 0.335 ->
                    (
                        0.82 -
                            (
                                age -
                                    0.225
                                ) /
                                0.110 *
                                0.20
                        ).toFloat()

                age < 0.700 ->
                    (
                        (
                            1.0 -
                                (
                                    age -
                                        0.335
                                    ) /
                                0.365
                            ) *
                            0.62
                        ).toFloat()

                else ->
                    0f
            }

        val ambient =
            max(
                firstFlash,
                secondFlash
            ).coerceIn(
                0f,
                0.82f
            )

        val graveyard =
            if (
                ambient <=
                0.015f
            ) {
                0f
            } else {
                (
                    ambient /
                        0.32f
                    ).coerceIn(
                    0f,
                    1f
                )
            }

        val bolt =
            when {
                age < 0.080 ->
                    0f

                age < 0.130 ->
                    (
                        (
                            age -
                                0.080
                            ) /
                            0.050
                        ).toFloat()

                age < 0.330 ->
                    1f

                age < 1.050 ->
                    (
                        1.0 -
                            (
                                age -
                                    0.330
                                ) /
                            0.720
                        ).toFloat()

                else ->
                    0f
            }.coerceIn(
                0f,
                1f
            )

        return LightningFrame(
            ambientAlpha =
                ambient,
            graveyardAlpha =
                graveyard,
            boltAlpha =
                bolt,
            eventIndex =
                eventIndex
        )
    }

    private fun drawGraveyard(
        canvas: Canvas,
        alpha: Float
    ) {
        if (
            graveyardWidth !=
                width ||
            graveyardHeight !=
                height
        ) {
            rebuildGraveyard()
        }

        val a =
            (
                alpha *
                    255f
                ).roundToInt()
                .coerceIn(
                0,
                255
            )

        graveyardPaint.alpha =
            a

        graveyardStrokePaint.alpha =
            a

        canvas.drawPath(
            graveyardFillPath,
            graveyardPaint
        )

        canvas.drawPath(
            graveyardDetailPath,
            graveyardStrokePaint
        )
    }

    private fun rebuildGraveyard() {
        graveyardWidth =
            width

        graveyardHeight =
            height

        graveyardFillPath.reset()

        graveyardDetailPath.reset()

        val w =
            width.toFloat()

        val h =
            height.toFloat()

        val ground =
            h *
                0.865f

        graveyardFillPath.moveTo(
            0f,
            ground +
                h *
                    0.018f
        )

        graveyardFillPath.cubicTo(
            w * 0.14f,
            ground -
                h *
                    0.038f,
            w * 0.29f,
            ground +
                h *
                    0.010f,
            w * 0.46f,
            ground -
                h *
                    0.030f
        )

        graveyardFillPath.cubicTo(
            w * 0.62f,
            ground -
                h *
                    0.060f,
            w * 0.78f,
            ground +
                h *
                    0.012f,
            w,
            ground -
                h *
                    0.028f
        )

        graveyardFillPath.lineTo(
            w,
            h
        )

        graveyardFillPath.lineTo(
            0f,
            h
        )

        graveyardFillPath.close()

        fun headstone(
            centerX: Float,
            widthFraction: Float,
            heightFraction: Float,
            lean: Float = 0f
        ) {
            val stoneW =
                w *
                    widthFraction

            val stoneH =
                h *
                    heightFraction

            val baseY =
                ground +
                    h *
                        0.006f

            val left =
                centerX -
                    stoneW *
                        0.5f

            val right =
                centerX +
                    stoneW *
                        0.5f

            val top =
                baseY -
                    stoneH

            val shoulderY =
                top +
                    stoneW *
                        0.45f

            graveyardFillPath.moveTo(
                left +
                    lean,
                baseY
            )

            graveyardFillPath.lineTo(
                left +
                    lean,
                shoulderY
            )

            graveyardFillPath.cubicTo(
                left +
                    lean,
                top,
                right +
                    lean,
                top,
                right +
                    lean,
                shoulderY
            )

            graveyardFillPath.lineTo(
                right,
                baseY
            )

            graveyardFillPath.close()
        }

        headstone(
            w * 0.10f,
            0.036f,
            0.125f,
            -w *
                0.004f
        )

        headstone(
            w * 0.24f,
            0.045f,
            0.165f,
            w *
                0.003f
        )

        headstone(
            w * 0.39f,
            0.032f,
            0.100f
        )

        headstone(
            w * 0.56f,
            0.050f,
            0.180f,
            -w *
                0.003f
        )

        headstone(
            w * 0.72f,
            0.035f,
            0.130f
        )

        headstone(
            w * 0.88f,
            0.043f,
            0.160f,
            w *
                0.004f
        )

        fun cross(
            cx: Float,
            heightFraction: Float
        ) {
            val crossH =
                h *
                    heightFraction

            val stem =
                w *
                    0.008f

            val arm =
                w *
                    0.034f

            val baseY =
                ground +
                    h *
                        0.006f

            val top =
                baseY -
                    crossH

            graveyardFillPath.addRect(
                cx -
                    stem *
                        0.5f,
                top,
                cx +
                    stem *
                        0.5f,
                baseY,
                Path.Direction.CW
            )

            graveyardFillPath.addRect(
                cx -
                    arm *
                        0.5f,
                top +
                    crossH *
                        0.27f,
                cx +
                    arm *
                        0.5f,
                top +
                    crossH *
                        0.27f +
                    stem,
                Path.Direction.CW
            )
        }

        cross(
            w * 0.32f,
            0.165f
        )

        cross(
            w * 0.80f,
            0.145f
        )

        graveyardStrokePaint.strokeWidth =
            max(
                2.2f,
                h *
                    0.0042f
            )

        val treeX =
            w *
                0.92f

        val treeBaseY =
            ground -
                h *
                    0.010f

        graveyardDetailPath.moveTo(
            treeX,
            treeBaseY
        )

        graveyardDetailPath.lineTo(
            treeX -
                w *
                    0.012f,
            ground -
                h *
                    0.180f
        )

        graveyardDetailPath.lineTo(
            treeX +
                w *
                    0.010f,
            ground -
                h *
                    0.255f
        )

        graveyardDetailPath.moveTo(
            treeX -
                w *
                    0.009f,
            ground -
                h *
                    0.160f
        )

        graveyardDetailPath.lineTo(
            treeX -
                w *
                    0.055f,
            ground -
                h *
                    0.225f
        )

        graveyardDetailPath.lineTo(
            treeX -
                w *
                    0.075f,
            ground -
                h *
                    0.275f
        )

        graveyardDetailPath.moveTo(
            treeX -
                w *
                    0.010f,
            ground -
                h *
                    0.205f
        )

        graveyardDetailPath.lineTo(
            treeX +
                w *
                    0.045f,
            ground -
                h *
                    0.270f
        )

        graveyardDetailPath.lineTo(
            treeX +
                w *
                    0.058f,
            ground -
                h *
                    0.315f
        )

        val fenceTop =
            ground -
                h *
                    0.070f

        val fenceBottom =
            ground +
                h *
                    0.006f

        for (i in 0..9) {
            val x =
                w *
                    (
                        0.02f +
                            i *
                                0.055f
                        )

            graveyardDetailPath.moveTo(
                x,
                fenceBottom
            )

            graveyardDetailPath.lineTo(
                x,
                fenceTop -
                    h *
                        (
                            0.006f *
                                (i % 3)
                            )
            )
        }

        graveyardDetailPath.moveTo(
            w * 0.015f,
            fenceTop +
                h *
                    0.018f
        )

        graveyardDetailPath.lineTo(
            w * 0.53f,
            fenceTop
        )
    }

    private fun drawLightningBolt(
        canvas: Canvas,
        alpha: Float,
        eventIndex: Int
    ) {
        if (
            eventIndex !=
                cachedBoltEventIndex ||
            width !=
                cachedBoltWidth ||
            height !=
                cachedBoltHeight
        ) {
            rebuildLightningBolt(
                eventIndex
            )
        }

        val screenScale =
            height /
                1080f

        boltGlowPaint.alpha =
            (
                alpha *
                    68f
                ).roundToInt()
                .coerceIn(
                0,
                68
            )

        boltGlowPaint.strokeWidth =
            15f *
                screenScale

        canvas.drawPath(
            boltTrunkPath,
            boltGlowPaint
        )

        boltGlowPaint.strokeWidth =
            8.5f *
                screenScale

        canvas.drawPath(
            boltBranchPath,
            boltGlowPaint
        )

        boltMidPaint.alpha =
            (
                alpha *
                    190f
                ).roundToInt()
                .coerceIn(
                0,
                190
            )

        boltMidPaint.strokeWidth =
            6.2f *
                screenScale

        canvas.drawPath(
            boltTrunkPath,
            boltMidPaint
        )

        boltMidPaint.strokeWidth =
            3.6f *
                screenScale

        canvas.drawPath(
            boltBranchPath,
            boltMidPaint
        )

        boltCorePaint.alpha =
            (
                alpha *
                    255f
                ).roundToInt()
                .coerceIn(
                0,
                255
            )

        boltCorePaint.strokeWidth =
            2.2f *
                screenScale

        canvas.drawPath(
            boltTrunkPath,
            boltCorePaint
        )

        boltCorePaint.strokeWidth =
            1.25f *
                screenScale

        canvas.drawPath(
            boltBranchPath,
            boltCorePaint
        )
    }

    private fun rebuildLightningBolt(
        eventIndex: Int
    ) {
        cachedBoltEventIndex =
            eventIndex

        cachedBoltWidth =
            width

        cachedBoltHeight =
            height

        boltTrunkPath.reset()

        boltBranchPath.reset()

        var rngState =
            (
                eventIndex.toLong() *
                    1103515245L +
                    0x5F3759DFL
                ) and
                0x7FFFFFFFL

        fun nextFloat():
            Float {
            rngState =
                (
                    rngState *
                        1103515245L +
                        12345L
                    ) and
                    0x7FFFFFFFL

            return rngState.toFloat() /
                0x7FFFFFFF
                    .toFloat()
        }

        val w =
            width.toFloat()

        val h =
            height.toFloat()

        val pointsX =
            FloatArray(17)

        val pointsY =
            FloatArray(17)

        pointsX[0] =
            w *
                (
                    0.18f +
                        nextFloat() *
                            0.64f
                    )

        pointsY[0] =
            -h *
                0.025f

        var drift =
            (
                nextFloat() -
                    0.5f
                ) *
                w *
                0.020f

        boltTrunkPath.moveTo(
            pointsX[0],
            pointsY[0]
        )

        for (i in 1..16) {
            val nextY =
                h *
                    (
                        0.015f +
                            i *
                                0.049f
                        )

            drift =
                drift *
                    0.48f +
                    (
                        nextFloat() -
                            0.5f
                        ) *
                    w *
                    0.052f

            val nextX =
                (
                    pointsX[
                        i -
                            1
                    ] +
                        drift
                    ).coerceIn(
                    w *
                        0.05f,
                    w *
                        0.95f
                )

            pointsX[i] =
                nextX

            pointsY[i] =
                nextY

            boltTrunkPath.lineTo(
                nextX,
                nextY
            )
        }

        val branchOrigins =
            intArrayOf(
                4,
                7,
                10,
                13
            )

        branchOrigins
            .forEachIndexed {
                    branchIndex,
                    origin ->

                var x =
                    pointsX[origin]

                var y =
                    pointsY[origin]

                val direction =
                    if (
                        nextFloat() <
                        0.5f
                    ) {
                        -1f
                    } else {
                        1f
                    }

                val segments =
                    3 +
                        (
                            nextFloat() *
                                3f
                            ).toInt()

                boltBranchPath.moveTo(
                    x,
                    y
                )

                var branchDrift =
                    direction *
                        w *
                        (
                            0.020f +
                                nextFloat() *
                                    0.020f
                            )

                for (
                    segment in
                    1..segments
                ) {
                    y +=
                        h *
                            (
                                0.030f +
                                    nextFloat() *
                                        0.022f
                                )

                    branchDrift =
                        branchDrift *
                            0.70f +
                            direction *
                                w *
                                (
                                    0.006f +
                                        nextFloat() *
                                            0.020f
                                    )

                    x +=
                        branchDrift

                    boltBranchPath.lineTo(
                        x,
                        y
                    )

                    if (
                        branchIndex ==
                            1 &&
                        segment ==
                            2
                    ) {
                        val forkX =
                            x -
                                direction *
                                    w *
                                    (
                                        0.024f +
                                            nextFloat() *
                                                0.020f
                                        )

                        val forkY =
                            y +
                                h *
                                    (
                                        0.045f +
                                            nextFloat() *
                                                0.025f
                                        )

                        boltBranchPath
                            .moveTo(
                                x,
                                y
                            )

                        boltBranchPath
                            .lineTo(
                                forkX,
                                forkY
                            )

                        boltBranchPath
                            .moveTo(
                                x,
                                y
                            )
                    }
                }
            }
    }

    private fun smoothStep(
        value: Float
    ): Float {
        val t =
            value.coerceIn(
                0f,
                1f
            )

        return t *
            t *
            (
                3f -
                    2f *
                        t
                )
    }

    private fun lerpFloat(
        start: Float,
        end: Float,
        amount: Float
    ): Float =
        start +
            (
                end -
                    start
                ) *
            amount
}
