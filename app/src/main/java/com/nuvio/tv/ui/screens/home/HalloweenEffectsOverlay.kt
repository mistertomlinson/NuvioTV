package com.nuvio.tv.ui.screens.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

internal object HalloweenEffectsRuntime {
    private val lastActivity =
        AtomicLong(
            System.nanoTime()
        )

    @Volatile
    private var focusedPosterOwner:
        Any? = null

    @Volatile
    private var focusedPosterBounds:
        RectF? = null

    fun noteUserActivity() {
        lastActivity.set(
            System.nanoTime()
        )
    }

    fun lastUserActivityNanos():
        Long =
        lastActivity.get()

    fun updateFocusedPosterBounds(
        owner: Any,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) {
        synchronized(this) {
            focusedPosterOwner =
                owner

            focusedPosterBounds =
                RectF(
                    left,
                    top,
                    right,
                    bottom
                )
        }
    }

    fun clearFocusedPosterBounds(
        owner: Any
    ) {
        synchronized(this) {
            if (
                focusedPosterOwner ===
                owner
            ) {
                focusedPosterOwner =
                    null
                focusedPosterBounds =
                    null
            }
        }
    }

    fun focusedPosterBounds():
        RectF? =
        synchronized(this) {
            focusedPosterBounds
                ?.let {
                    RectF(it)
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

private const val BAT_FRAME_COUNT = 8
private const val BAT_FRAME_SIZE = 96
private const val BAT_COUNT = 7

private const val IDLE_PERCH_NANOS =
    20_000_000_000L

private const val APPROACH_NANOS =
    1_800_000_000L

private const val TAKEOFF_NANOS =
    1_300_000_000L

private const val LIGHTNING_CYCLE_SECONDS =
    240.0

private val lightningStarts =
    doubleArrayOf(
        10.0,
        52.0,
        119.0,
        183.0
    )

private enum class PerchPhase {
    NONE,
    APPROACH,
    PERCHED,
    TAKEOFF
}

private class HalloweenState {
    var simulationNanos:
        Long = 0L

    var perchPhase:
        PerchPhase =
        PerchPhase.NONE

    var perchStartNanos:
        Long = 0L

    var perchBounds:
        RectF? = null

    var lastObservedActivity:
        Long =
        HalloweenEffectsRuntime
            .lastUserActivityNanos()
}

private data class BatSeed(
    val xSeed: Float,
    val ySeed: Float,
    val speedSeed: Float,
    val depthSeed: Float,
    val phaseSeed: Float,
    val cycleSeed: Float,
    val directionSeed: Float
)

private val bats:
    List<BatSeed> =
    buildList(BAT_COUNT) {
        var state =
            0x5A17B47Du

        fun nextFloat():
            Float {
            state =
                state *
                    1664525u +
                    1013904223u

            return (
                (
                    (
                        state shr 8
                    ).toLong() and
                        0x00FFFFFFL
                    ).toFloat() /
                    0x01000000L
                )
        }

        repeat(BAT_COUNT) {
            add(
                BatSeed(
                    xSeed =
                        nextFloat(),
                    ySeed =
                        nextFloat(),
                    speedSeed =
                        nextFloat(),
                    depthSeed =
                        nextFloat(),
                    phaseSeed =
                        nextFloat(),
                    cycleSeed =
                        nextFloat(),
                    directionSeed =
                        nextFloat()
                )
            )
        }
    }

private val batAtlas:
    Bitmap by
    lazy(
        LazyThreadSafetyMode.SYNCHRONIZED
    ) {
        buildBatAtlas()
    }

private fun buildBatAtlas():
    Bitmap {
    val width =
        BAT_FRAME_SIZE *
            BAT_FRAME_COUNT

    val bitmap =
        Bitmap.createBitmap(
            width,
            BAT_FRAME_SIZE,
            Bitmap.Config.ARGB_8888
        )

    val canvas =
        Canvas(bitmap)

    val paint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color =
                Color.rgb(
                    15,
                    15,
                    19
                )
            style =
                Paint.Style.FILL
        }

    repeat(
        BAT_FRAME_COUNT
    ) { frame ->
        val left =
            frame *
                BAT_FRAME_SIZE

        val cx =
            left +
                BAT_FRAME_SIZE *
                0.5f

        val cy =
            BAT_FRAME_SIZE *
                0.50f

        val angle =
            frame.toDouble() /
                BAT_FRAME_COUNT *
                PI *
                2.0

        val flap =
            sin(angle)
                .toFloat()

        val wingTipY =
            cy -
                flap *
                21f

        fun drawWing(
            direction: Float
        ) {
            val path =
                Path()

            path.moveTo(
                cx +
                    direction *
                    4f,
                cy - 5f
            )

            path.cubicTo(
                cx +
                    direction *
                    13f,
                cy - 13f,
                cx +
                    direction *
                    27f,
                wingTipY - 6f,
                cx +
                    direction *
                    42f,
                wingTipY
            )

            path.cubicTo(
                cx +
                    direction *
                    36f,
                wingTipY + 9f,
                cx +
                    direction *
                    31f,
                cy + 5f,
                cx +
                    direction *
                    26f,
                cy + 13f
            )

            path.cubicTo(
                cx +
                    direction *
                    21f,
                cy + 6f,
                cx +
                    direction *
                    17f,
                cy + 15f,
                cx +
                    direction *
                    12f,
                cy + 16f
            )

            path.cubicTo(
                cx +
                    direction *
                    10f,
                cy + 7f,
                cx +
                    direction *
                    6f,
                cy + 6f,
                cx +
                    direction *
                    3f,
                cy + 5f
            )

            path.close()

            canvas.drawPath(
                path,
                paint
            )
        }

        drawWing(-1f)
        drawWing(1f)

        canvas.drawOval(
            RectF(
                cx - 5.2f,
                cy - 11f,
                cx + 5.2f,
                cy + 15f
            ),
            paint
        )

        canvas.drawCircle(
            cx,
            cy - 13f,
            5.4f,
            paint
        )

        val ears =
            Path().apply {
                moveTo(
                    cx - 4.5f,
                    cy - 16f
                )
                lineTo(
                    cx - 7.5f,
                    cy - 25f
                )
                lineTo(
                    cx - 1.2f,
                    cy - 18f
                )
                close()

                moveTo(
                    cx + 4.5f,
                    cy - 16f
                )
                lineTo(
                    cx + 7.5f,
                    cy - 25f
                )
                lineTo(
                    cx + 1.2f,
                    cy - 18f
                )
                close()
            }

        canvas.drawPath(
            ears,
            paint
        )
    }

    return bitmap
}

private data class LightningFrame(
    val flashAlpha: Float,
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
        alignment =
            Alignment.TopStart,
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
    private var appInForeground:
        Boolean = true

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

    private val srcRect =
        Rect()

    private val dstRect =
        RectF()

    private val batPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG or
                Paint.FILTER_BITMAP_FLAG
        )

    private val flashPaint =
        Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

    private val graveyardPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }

    private val graveyardPath =
        Path()

    private var graveyardWidth =
        -1

    private var graveyardHeight =
        -1

    private val boltPath =
        Path()

    private val boltShadowPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeCap =
                Paint.Cap.ROUND
            strokeJoin =
                Paint.Join.ROUND
        }

    private val boltPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
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
            if (
                !presentationInitialized
            ) {
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
        if (
            !presentationInitialized
        ) {
            return presentationTargetAlpha
        }

        val elapsed =
            (
                nowNanos -
                    presentationStartNanos
                ).coerceAtLeast(0L)

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

        running.set(false)

        startRenderThread()
    }

    private fun startRenderThread() {
        if (
            !running
                .compareAndSet(
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
                "NuvioHalloween"
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
                thread.join(250L)
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

                    if (
                        !hiddenSurfaceCleared
                    ) {
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
                    state,
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
                    if (
                        !running.get()
                    ) {
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
                canvas =
                    canvas,
                seconds =
                    seconds,
                presentationAlpha =
                    presentationAlpha
            )

            drawPerchingBat(
                canvas =
                    canvas,
                state =
                    state,
                seconds =
                    seconds,
                presentationAlpha =
                    presentationAlpha
            )

            val lightning =
                lightningFrame(
                    seconds
                )

            if (
                lightning.flashAlpha >
                0f
            ) {
                val alpha =
                    (
                        lightning.flashAlpha *
                            presentationAlpha *
                            255f
                        ).roundToInt()
                        .coerceIn(
                            0,
                            255
                        )

                flashPaint.alpha =
                    alpha

                canvas.drawRect(
                    0f,
                    0f,
                    width.toFloat(),
                    height.toFloat(),
                    flashPaint
                )

                drawGraveyard(
                    canvas =
                        canvas,
                    flashAlpha =
                        lightning.flashAlpha *
                            presentationAlpha
                )
            }

            if (
                lightning.boltAlpha >
                0f
            ) {
                drawLightningBolt(
                    canvas =
                        canvas,
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

        val margin =
            90f *
                screenScale

        bats.forEachIndexed {
                index,
                bat ->

            val cycle =
                18.0 +
                    bat.cycleSeed *
                        24.0

            val activeDuration =
                5.7 +
                    bat.speedSeed *
                        3.8

            var local =
                (
                    seconds +
                        bat.xSeed *
                            cycle *
                            2.15
                    ) %
                    cycle

            if (local < 0.0) {
                local += cycle
            }

            if (
                local >
                activeDuration
            ) {
                return@forEachIndexed
            }

            val progress =
                (
                    local /
                        activeDuration
                    ).toFloat()
                    .coerceIn(
                        0f,
                        1f
                    )

            val depth =
                0.48f +
                    bat.depthSeed *
                        0.78f

            val fromLeft =
                bat.directionSeed <
                    0.5f

            val travel =
                width +
                    margin *
                        2f

            val x =
                if (fromLeft) {
                    -margin +
                        travel *
                            progress
                } else {
                    width +
                        margin -
                        travel *
                            progress
                }

            val baseY =
                height *
                    (
                        0.10f +
                            bat.ySeed *
                                0.43f
                        )

            val flutterY =
                sin(
                    progress *
                        PI.toFloat() *
                        (
                            3.4f +
                                bat.speedSeed *
                                    2.1f
                            ) +
                        bat.phaseSeed *
                            PI.toFloat() *
                            2f
                ) *
                    (
                        16f +
                            bat.depthSeed *
                                36f
                        ) *
                    screenScale

            val driftY =
                sin(
                    progress *
                        PI.toFloat() +
                        bat.phaseSeed *
                            5f
                ) *
                    28f *
                    screenScale

            val y =
                baseY +
                    flutterY +
                    driftY

            val size =
                (
                    42f +
                        depth *
                            46f
                    ) *
                    screenScale

            val wingHz =
                6.4 +
                    bat.speedSeed *
                        4.2

            val frame =
                (
                    floor(
                        seconds *
                            wingHz +
                            index *
                                1.7
                    ).toInt() %
                        BAT_FRAME_COUNT +
                        BAT_FRAME_COUNT
                    ) %
                    BAT_FRAME_COUNT

            val rotation =
                sin(
                    progress *
                        PI.toFloat() *
                        4.0f +
                        bat.phaseSeed *
                            6f
                ) *
                    (
                        5f +
                            bat.depthSeed *
                                8f
                        )

            val alpha =
                (
                    (
                        0.58f +
                            depth *
                                0.28f
                        ) *
                        presentationAlpha *
                        255f
                    ).roundToInt()
                    .coerceIn(
                        0,
                        235
                    )

            drawBatSprite(
                canvas =
                    canvas,
                x =
                    x,
                y =
                    y,
                size =
                    size,
                frame =
                    frame,
                alpha =
                    alpha,
                rotation =
                    rotation
            )
        }
    }

    private fun updatePerchState(
        state: HalloweenState
    ) {
        val activity =
            HalloweenEffectsRuntime
                .lastUserActivityNanos()

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
                state.perchPhase =
                    PerchPhase.TAKEOFF

                state.perchStartNanos =
                    state.simulationNanos
            }
        }

        when (
            state.perchPhase
        ) {
            PerchPhase.NONE -> {
                val idleFor =
                    System.nanoTime() -
                        activity

                if (
                    idleFor >=
                    IDLE_PERCH_NANOS
                ) {
                    val bounds =
                        HalloweenEffectsRuntime
                            .focusedPosterBounds()

                    if (bounds != null) {
                        state.perchBounds =
                            bounds

                        state.perchPhase =
                            PerchPhase.APPROACH

                        state.perchStartNanos =
                            state.simulationNanos
                    }
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
                }
            }
        }
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
                bounds.right -
                    bounds.width() *
                        0.20f
                ).coerceIn(
                36f,
                width - 36f
            )

        val targetY =
            (
                bounds.top +
                    2f *
                        screenScale
                ).coerceIn(
                24f,
                height - 24f
            )

        val elapsed =
            state.simulationNanos -
                state.perchStartNanos

        val alpha =
            (
                presentationAlpha *
                    235f
                ).roundToInt()
                .coerceIn(
                    0,
                    235
                )

        when (
            state.perchPhase
        ) {
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
                    p *
                        p *
                        (
                            3f -
                                2f *
                                    p
                            )

                val startX =
                    width +
                        85f *
                            screenScale

                val startY =
                    (
                        targetY -
                            170f *
                                screenScale
                        ).coerceAtLeast(
                        30f
                    )

                val x =
                    startX +
                        (
                            targetX -
                                startX
                            ) *
                        eased

                val y =
                    startY +
                        (
                            targetY -
                                startY
                            ) *
                        eased +
                        sin(
                            p *
                                PI.toFloat() *
                                5f
                        ) *
                        12f *
                        screenScale *
                        (
                            1f -
                                p
                            )

                val frame =
                    (
                        seconds *
                            9.0
                        ).toInt() %
                        BAT_FRAME_COUNT

                drawBatSprite(
                    canvas,
                    x,
                    y,
                    62f *
                        screenScale,
                    frame,
                    alpha,
                    -10f *
                        (
                            1f -
                                eased
                            )
                )
            }

            PerchPhase.PERCHED -> {
                val tinyMotion =
                    sin(
                        seconds *
                            2.1
                    ).toFloat() *
                        1.5f *
                        screenScale

                drawBatSprite(
                    canvas,
                    targetX,
                    targetY +
                        tinyMotion,
                    55f *
                        screenScale,
                    6,
                    alpha,
                    0f
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
                    p *
                        p *
                        (
                            3f -
                                2f *
                                    p
                            )

                val endX =
                    width +
                        100f *
                            screenScale

                val endY =
                    (
                        targetY -
                            height *
                                0.28f
                        ).coerceAtLeast(
                        28f
                    )

                val x =
                    targetX +
                        (
                            endX -
                                targetX
                            ) *
                        eased

                val y =
                    targetY +
                        (
                            endY -
                                targetY
                            ) *
                        eased -
                        sin(
                            p *
                                PI.toFloat()
                        ) *
                        45f *
                        screenScale

                val frame =
                    (
                        seconds *
                            11.0
                        ).toInt() %
                        BAT_FRAME_COUNT

                drawBatSprite(
                    canvas,
                    x,
                    y,
                    (
                        55f +
                            p *
                                14f
                        ) *
                        screenScale,
                    frame,
                    alpha,
                    18f *
                        eased
                )
            }

            PerchPhase.NONE ->
                Unit
        }
    }

    private fun drawBatSprite(
        canvas: Canvas,
        x: Float,
        y: Float,
        size: Float,
        frame: Int,
        alpha: Int,
        rotation: Float
    ) {
        val safeFrame =
            (
                frame %
                    BAT_FRAME_COUNT +
                    BAT_FRAME_COUNT
                ) %
                BAT_FRAME_COUNT

        srcRect.set(
            safeFrame *
                BAT_FRAME_SIZE,
            0,
            (
                safeFrame +
                    1
                ) *
                BAT_FRAME_SIZE,
            BAT_FRAME_SIZE
        )

        val half =
            size *
                0.5f

        dstRect.set(
            x - half,
            y - half,
            x + half,
            y + half
        )

        batPaint.alpha =
            alpha

        canvas.save()

        canvas.rotate(
            rotation,
            x,
            y
        )

        canvas.drawBitmap(
            batAtlas,
            srcRect,
            dstRect,
            batPaint
        )

        canvas.restore()
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

                    return@forEachIndexed
                }
            }

        if (age < 0.0) {
            return LightningFrame(
                flashAlpha = 0f,
                boltAlpha = 0f,
                eventIndex = -1
            )
        }

        val flash =
            when {
                age < 0.045 ->
                    (
                        age /
                            0.045 *
                            0.66
                        ).toFloat()

                age < 0.105 ->
                    (
                        (
                            1.0 -
                                (
                                    age -
                                        0.045
                                    ) /
                                0.060
                            ) *
                            0.66
                        ).toFloat()

                age < 0.145 ->
                    0f

                age < 0.185 ->
                    (
                        (
                            age -
                                0.145
                            ) /
                            0.040 *
                            0.74
                        ).toFloat()

                age < 0.370 ->
                    (
                        (
                            1.0 -
                                (
                                    age -
                                        0.185
                                    ) /
                                0.185
                            ) *
                            0.74
                        ).toFloat()

                else ->
                    0f
            }.coerceIn(
                0f,
                0.74f
            )

        val bolt =
            when {
                age < 0.075 ->
                    0f

                age < 0.115 ->
                    (
                        (
                            age -
                                0.075
                            ) /
                            0.040
                        ).toFloat()

                age < 0.340 ->
                    1f

                age < 1.050 ->
                    (
                        1.0 -
                            (
                                age -
                                    0.340
                                ) /
                            0.710
                        ).toFloat()

                else ->
                    0f
            }.coerceIn(
                0f,
                1f
            )

        return LightningFrame(
            flashAlpha = flash,
            boltAlpha = bolt,
            eventIndex = eventIndex
        )
    }

    private fun drawGraveyard(
        canvas: Canvas,
        flashAlpha: Float
    ) {
        if (
            graveyardWidth != width ||
            graveyardHeight != height
        ) {
            rebuildGraveyard()
        }

        val normalized =
            (
                flashAlpha /
                    0.74f
                ).coerceIn(
                0f,
                1f
            )

        graveyardPaint.alpha =
            (
                normalized *
                    245f
                ).roundToInt()
                .coerceIn(
                    0,
                    245
                )

        canvas.drawPath(
            graveyardPath,
            graveyardPaint
        )
    }

    private fun rebuildGraveyard() {
        graveyardWidth =
            width

        graveyardHeight =
            height

        graveyardPath.reset()

        val w =
            width.toFloat()

        val h =
            height.toFloat()

        val ground =
            h *
                0.875f

        graveyardPath.moveTo(
            0f,
            ground +
                h *
                    0.012f
        )

        graveyardPath.cubicTo(
            w *
                0.18f,
            ground -
                h *
                    0.025f,
            w *
                0.31f,
            ground +
                h *
                    0.008f,
            w *
                0.47f,
            ground -
                h *
                    0.020f
        )

        graveyardPath.cubicTo(
            w *
                0.64f,
            ground -
                h *
                    0.038f,
            w *
                0.79f,
            ground +
                h *
                    0.012f,
            w,
            ground -
                h *
                    0.018f
        )

        graveyardPath.lineTo(
            w,
            h
        )

        graveyardPath.lineTo(
            0f,
            h
        )

        graveyardPath.close()

        fun tombstone(
            centerX: Float,
            widthFraction: Float,
            heightFraction: Float
        ) {
            val tw =
                w *
                    widthFraction

            val th =
                h *
                    heightFraction

            val left =
                centerX -
                    tw *
                        0.5f

            val top =
                ground -
                    th

            graveyardPath.addRoundRect(
                RectF(
                    left,
                    top,
                    left + tw,
                    ground +
                        h *
                            0.01f
                ),
                tw *
                    0.42f,
                tw *
                    0.42f,
                Path.Direction.CW
            )
        }

        tombstone(
            w * 0.11f,
            0.032f,
            0.105f
        )

        tombstone(
            w * 0.24f,
            0.040f,
            0.135f
        )

        tombstone(
            w * 0.39f,
            0.030f,
            0.090f
        )

        tombstone(
            w * 0.57f,
            0.045f,
            0.150f
        )

        tombstone(
            w * 0.72f,
            0.033f,
            0.110f
        )

        tombstone(
            w * 0.88f,
            0.038f,
            0.130f
        )

        fun cross(
            cx: Float,
            heightFraction: Float
        ) {
            val ch =
                h *
                    heightFraction

            val stem =
                w *
                    0.008f

            val arm =
                w *
                    0.030f

            val top =
                ground -
                    ch

            graveyardPath.addRect(
                cx - stem * 0.5f,
                top,
                cx + stem * 0.5f,
                ground +
                    h *
                        0.008f,
                Path.Direction.CW
            )

            graveyardPath.addRect(
                cx - arm * 0.5f,
                top +
                    ch *
                        0.26f,
                cx + arm * 0.5f,
                top +
                    ch *
                        0.26f +
                    stem,
                Path.Direction.CW
            )
        }

        cross(
            w * 0.32f,
            0.145f
        )

        cross(
            w * 0.81f,
            0.125f
        )
    }

    private fun drawLightningBolt(
        canvas: Canvas,
        alpha: Float,
        eventIndex: Int
    ) {
        val variant =
            abs(
                eventIndex
            ) %
                3

        val direction =
            if (
                variant %
                    2 ==
                0
            ) {
                1f
            } else {
                -1f
            }

        val startX =
            width *
                (
                    0.28f +
                        variant *
                            0.19f
                    )

        val h =
            height.toFloat()

        val w =
            width.toFloat()

        boltPath.reset()

        boltPath.moveTo(
            startX,
            0f
        )

        boltPath.lineTo(
            startX +
                direction *
                    w *
                    0.034f,
            h *
                0.17f
        )

        boltPath.lineTo(
            startX -
                direction *
                    w *
                    0.008f,
            h *
                0.31f
        )

        boltPath.lineTo(
            startX +
                direction *
                    w *
                    0.052f,
            h *
                0.43f
        )

        boltPath.lineTo(
            startX +
                direction *
                    w *
                    0.015f,
            h *
                0.58f
        )

        boltPath.lineTo(
            startX +
                direction *
                    w *
                    0.088f,
            h *
                0.76f
        )

        boltPath.moveTo(
            startX +
                direction *
                    w *
                    0.052f,
            h *
                0.43f
        )

        boltPath.lineTo(
            startX -
                direction *
                    w *
                    0.012f,
            h *
                0.51f
        )

        boltPath.lineTo(
            startX -
                direction *
                    w *
                    0.036f,
            h *
                0.63f
        )

        val scale =
            height /
                1080f

        boltShadowPaint.strokeWidth =
            8f *
                scale

        boltShadowPaint.alpha =
            (
                alpha *
                    105f
                ).roundToInt()
                .coerceIn(
                    0,
                    105
                )

        canvas.drawPath(
            boltPath,
            boltShadowPaint
        )

        boltPaint.strokeWidth =
            3.4f *
                scale

        boltPaint.alpha =
            (
                alpha *
                    245f
                ).roundToInt()
                .coerceIn(
                    0,
                    245
                )

        canvas.drawPath(
            boltPath,
            boltPaint
        )
    }
}
