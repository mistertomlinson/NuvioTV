package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

internal const val XL_BURST_SPEED_DP_PER_SEC = 1200f
internal const val XL_BURST_ACCEL_DP_PER_SEC2 = 6000f

/** A bounded-acceleration controller, shared by repeated taps and held navigation. */
internal fun xlApproachVelocity(current: Float, desired: Float, acceleration: Float, dt: Float): Float =
    current + (desired - current).coerceIn(-acceleration * dt, acceleration * dt)

internal fun xlTargetVelocity(distance: Float, speed: Float, acceleration: Float): Float =
    sign(distance) * minOf(speed, sqrt(2f * acceleration * abs(distance)))

/**
 * No animateScrollToItem on a retarget: that API can teleport across uncomposed
 * rows and restarts the animation velocity. This owner moves only by bounded
 * scroll deltas, retaining velocity when a new key changes its destination.
 * All commands are issued on Home's UI thread.
 */
internal class XlContinuousScroll(
    private val scope: CoroutineScope,
    private val list: LazyListState,
    private val density: () -> Float,
    private val distanceTo: (XlSceneSelection) -> Float?,
    private val forwardRemaining: () -> Float,
    private val onVelocity: (Float) -> Unit,
    // The final paint-only separation shares this frame clock. Return true
    // to retain the owner after its list offset reaches the target.
    private val onTargetFrame: (XlSceneSelection, Float, Float) -> Boolean = { _, _, _ -> false },
    private val onHeldFrame: (Float) -> Unit = {}
) {
    private var job: Job? = null
    private var target: XlSceneSelection? = null
    private var heldSign = 0
    private var velocity = 0f
    private var generation = 0

    fun retarget(selection: XlSceneSelection, inheritedVelocity: Float = 0f) {
        target = selection
        heldSign = 0
        start(inheritedVelocity)
    }

    fun hold(sign: Int, inheritedVelocity: Float = 0f) {
        heldSign = sign
        target = null
        start(inheritedVelocity)
    }

    fun stop() {
        generation++
        job?.cancel()
        job = null
        target = null
        heldSign = 0
        velocity = 0f
        onVelocity(0f)
    }

    private fun start(inheritedVelocity: Float) {
        if (job?.isActive == true) return
        val speed = XL_BURST_SPEED_DP_PER_SEC * density()
        velocity = inheritedVelocity.coerceIn(-speed, speed)
        val owner = ++generation
        job = scope.launch {
            // A compact anchor request may have been issued in the same
            // key event. Acquire the scroll mutex after its measure commits.
            var lastFrame = withFrameNanos { it }
            try {
                list.scroll(MutatePriority.PreventUserInput) {
                    while (true) {
                        val now = withFrameNanos { it }
                        val dt = ((now - lastFrame) / 1_000_000_000f).coerceIn(0f, 0.024f)
                        lastFrame = now
                        if (dt <= 0f) continue
                        val speedNow = XL_BURST_SPEED_DP_PER_SEC * density()
                        val acceleration = XL_BURST_ACCEL_DP_PER_SEC2 * density()
                        val selected = target
                        val distance = if (heldSign == 0) selected?.let(distanceTo)
                            else if (heldSign > 0) forwardRemaining() else Float.NEGATIVE_INFINITY
                        if (distance == null) break
                        val remaining = if (heldSign > 0) distance.coerceAtLeast(0f) else distance
                        val desired = xlTargetVelocity(remaining, speedNow, acceleration)
                        if (heldSign != 0) onHeldFrame(dt)
                        if (abs(remaining) <= 0.5f && abs(velocity) <= 1f) {
                            if (heldSign == 0 && (selected == null || !onTargetFrame(selected, remaining, dt))) break
                            continue
                        }
                        val next = xlApproachVelocity(velocity, desired, acceleration, dt)
                        var delta = (velocity + next) * 0.5f * dt
                        // Prevent overshoot without teleporting to a target
                        // that isn't yet composed. Direction changes first
                        // brake the inherited velocity, then reverse.
                        if (remaining.isFinite() && sign(delta) == sign(remaining) && abs(delta) > abs(remaining))
                            delta = remaining
                        if (heldSign > 0 && delta > 0f) delta = minOf(delta, remaining)
                        if (heldSign == 0 && abs(remaining) <= 0.5f) {
                            velocity = 0f
                            onVelocity(0f)
                            if (selected == null || !onTargetFrame(selected, remaining, dt)) break
                            continue
                        }
                        if (heldSign != 0 && abs(remaining) <= 0.5f && sign(velocity) == heldSign.toFloat()) {
                            velocity = 0f
                            onVelocity(0f)
                            continue
                        }
                        val consumed = scrollBy(delta)
                        val hitLimit = abs(delta) > 1f && abs(consumed) < 0.5f
                        velocity = if (hitLimit || abs(remaining - delta) <= 0.5f) 0f else next
                        onVelocity(velocity)
                        XlNavigationTrace.event("CONTINUOUS_STEP") { "target=${selected?.rowKey} held=$heldSign remaining=$remaining delta=$delta consumed=$consumed velocity=$velocity" }
                        if (heldSign == 0 && selected != null) {
                            val after = distanceTo(selected) ?: (remaining - consumed)
                            val keep = onTargetFrame(selected, after, dt)
                            if (hitLimit && !keep) break
                        } else if (heldSign == 0 && hitLimit) break
                    }
                }
            } finally {
                if (generation == owner) {
                    velocity = 0f
                    onVelocity(0f)
                }
            }
        }
    }
}
