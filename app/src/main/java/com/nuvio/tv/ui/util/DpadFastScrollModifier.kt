package com.nuvio.tv.ui.util

import android.view.KeyEvent
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DEFAULT_VERTICAL_VELOCITY_DP_PER_SEC = 3200f
private const val DEFAULT_END_TIMEOUT_MS = 160L
private const val DEFAULT_MAX_FRAME_DT_SEC = 0.048f
private enum class FastScrollMode { None, Vertical }

fun Modifier.dpadVerticalFastScroll(
    scrollableState: ScrollableState,
    resolveVerticalLanding: (sign: Int) -> String?,
    onFastScrollingChanged: (Boolean) -> Unit = {},
    shouldHaltForward: () -> Boolean = { false },
    horizontalGateMs: Long = 80L,
    verticalVelocityDpPerSec: Float = DEFAULT_VERTICAL_VELOCITY_DP_PER_SEC,
    endTimeoutMs: Long = DEFAULT_END_TIMEOUT_MS,
    maxFrameDtSec: Float = DEFAULT_MAX_FRAME_DT_SEC,
): Modifier = composed {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val jobRef = remember { AtomicReference<Job?>(null) }
    val endTimerRef = remember { AtomicReference<Job?>(null) }
    val modeRef = remember { AtomicReference(FastScrollMode.None) }
    val directionRef = remember { AtomicInteger(0) }
    val isActiveRef = remember { AtomicReference(false) }

    DisposableEffect(Unit) {
        onDispose {
            jobRef.getAndSet(null)?.cancel()
            endTimerRef.getAndSet(null)?.cancel()
        }
    }

    onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        val kc = native.keyCode
        val isHoriz = kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT
        val isVert = kc == KeyEvent.KEYCODE_DPAD_UP || kc == KeyEvent.KEYCODE_DPAD_DOWN

        fun setActive(value: Boolean) {
            if (isActiveRef.getAndSet(value) != value) onFastScrollingChanged(value)
        }

        fun endFastScroll() {
            val mode = modeRef.getAndSet(FastScrollMode.None)
            val direction = directionRef.getAndSet(0)
            jobRef.getAndSet(null)?.cancel()
            endTimerRef.getAndSet(null)?.cancel()
            setActive(false)
            if (mode == FastScrollMode.Vertical) {
                resolveVerticalLanding(if (direction == 0) 1 else direction)
            }
        }

        if (!isHoriz && !isVert) return@onPreviewKeyEvent false

        if (native.action == KeyEvent.ACTION_UP) {
            if (modeRef.get() != FastScrollMode.None) endFastScroll()
            return@onPreviewKeyEvent false
        }

        if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
        if (native.repeatCount == 0) return@onPreviewKeyEvent false

        if (isHoriz) {
            if (modeRef.get() != FastScrollMode.None) endFastScroll()
            return@onPreviewKeyEvent false
        }

        val sign = if (kc == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
        val needsStart = modeRef.get() != FastScrollMode.Vertical ||
            directionRef.get() != sign || jobRef.get()?.isActive != true

        if (needsStart) {
            jobRef.getAndSet(null)?.cancel()
            val atScrollEdge = (sign > 0 && !scrollableState.canScrollForward) ||
                (sign < 0 && !scrollableState.canScrollBackward)
            val halted = sign > 0 && shouldHaltForward()
            if (atScrollEdge || halted) {
                if (modeRef.get() != FastScrollMode.None) endFastScroll()
                return@onPreviewKeyEvent true
            }
            modeRef.set(FastScrollMode.Vertical)
            directionRef.set(sign)
            setActive(true)
            val velocityPxPerSec = with(density) { verticalVelocityDpPerSec.dp.toPx() }
            jobRef.set(scope.launch {
                try {
                    scrollableState.scroll {
                        var lastFrame = withFrameNanos { it }
                        while (true) {
                            val now = withFrameNanos { it }
                            val dtSec = ((now - lastFrame) / 1_000_000_000f).coerceAtMost(maxFrameDtSec)
                            lastFrame = now
                            if (sign > 0 && shouldHaltForward()) break
                            val delta = sign * velocityPxPerSec * dtSec
                            val consumed = scrollBy(delta)
                            if (consumed == 0f && delta != 0f) break
                        }
                    }
                    endFastScroll()
                } catch (_: CancellationException) { }
            })
        }

        endTimerRef.getAndSet(null)?.cancel()
        endTimerRef.set(scope.launch {
            delay(endTimeoutMs)
            endFastScroll()
        })
        true
    }
}
