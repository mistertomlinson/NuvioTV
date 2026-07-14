package com.nuvio.tv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R

@Composable
fun PulsingLogoIndicator(
    modifier: Modifier = Modifier,
    active: Boolean = true,
    // Set true when the caller WANTS to dismiss. The loader keeps playing whole
    // cycles and only stops at a cycle boundary, then invokes onDismissReady.
    dismissRequested: Boolean = false,
    onDismissReady: (() -> Unit)? = null
) {
    // One-time entrance fade so the logo doesn't pop in abruptly after profile selection.
    val entranceAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        entranceAlpha.animateTo(1f, animationSpec = tween(durationMillis = 450))
    }

    // One "cycle" = a single complete L->R pass where all three dots bounce once.
    // Implemented as a MANUAL loop with a suspending Animatable so each cycle is
    // ATOMIC: animateTo(1f) runs to completion before we decide anything. We only
    // check dismissRequested at a boundary (progress just reached 1, dots at rest),
    // guaranteeing we never fade in or out mid-bounce and always play >= 1 full
    // cycle even if dismissal is requested immediately.
    val progressAnim = remember { Animatable(0f) }
    val progress = progressAnim.value
    // Mirror through updated-state so the long-lived loop reads the CURRENT values
    // (a plain LaunchedEffect(Unit) coroutine would capture the initial ones).
    val dismissNow = androidx.compose.runtime.rememberUpdatedState(dismissRequested)
    val onDismissReadyNow = androidx.compose.runtime.rememberUpdatedState(onDismissReady)
    LaunchedEffect(Unit) {
        while (true) {
            progressAnim.snapTo(0f)
            progressAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 2600, easing = androidx.compose.animation.core.LinearEasing)
            )
            // Boundary: a whole cycle just finished, all dots are at rest.
            if (dismissNow.value) {
                onDismissReadyNow.value?.invoke()
                break
            }
        }
    }

    // Group opacity: fade in ONCE on appearance and then hold solid forever.
    // The fade-OUT at dismissal is handled by the overlay's AnimatedVisibility
    // (whole-cycle gated), so the dots themselves never self-fade mid-cycle.
    val groupFade = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        groupFade.animateTo(1f, animationSpec = tween(durationMillis = 260))
    }
    val groupAlpha = groupFade.value

    // Floaty parabolic bounce. A "floaty" arc = low gravity: the dot spends most
    // of its time near the top (long hang) and only a little near the floor. We
    // get the hang by using a FLATTENED-top parabola (raise the parabola to a
    // power < 1 so it plateaus near the peak). One tall floaty arc + one gentle
    // secondary bounce. Slots packed (0.30) so the third dot finishes close to
    // the cycle end — minimal dead air, so "fade after complete cycle" looks
    // immediate rather than fading during a long motionless gap.
    fun dotLift(index: Int): Float {
        val slot = 0.30f
        val local = (progress - index * slot) / slot
        if (local < 0f || local > 1f) return 0f

        fun floatArc(t0: Float, t1: Float, peak: Float): Float {
            val t = ((local - t0) / (t1 - t0)).coerceIn(0f, 1f)
            val u = 2f * t - 1f
            val parab = 1f - u * u                 // 0..1 parabola
            // Flatten the top for a hang: pow < 1 pushes values toward 1 near peak.
            val hang = Math.pow(parab.toDouble(), 0.55).toFloat()
            return peak * hang
        }
        return when {
            local < 0.72f -> floatArc(0.00f, 0.72f, 1.0f)   // tall floaty arc, long hang
            local < 1.00f -> floatArc(0.72f, 1.00f, 0.22f)  // gentle secondary bounce
            else -> 0f
        }
    }

    val dotColors = listOf(
        androidx.compose.ui.graphics.Color(0xFF4DE4F1),
        androidx.compose.ui.graphics.Color(0xFF4A65DB),
        androidx.compose.ui.graphics.Color(0xFFC758E4)
    )

    // Dot sizing/spacing.
    val dotSize = 14.dp
    val dotGap = 12.dp
    val maxLift = 12.dp

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .width(dotSize * 3 + dotGap * 2)
                .height(dotSize + maxLift * 2)
                .alpha(entranceAlpha.value * groupAlpha)
        ) {
            val d = dotSize.toPx()
            val gap = dotGap.toPx()
            val lift = maxLift.toPx()
            val r = d / 2f
            val centerY = size.height / 2f
            val totalW = d * 3 + gap * 2
            val startX = (size.width - totalW) / 2f + r
            for (i in 0..2) {
                val cx = startX + i * (d + gap)
                val cy = centerY - dotLift(i) * lift
                drawCircle(
                    color = dotColors[i],
                    radius = r,
                    center = androidx.compose.ui.geometry.Offset(cx, cy)
                )
            }
        }
    }
}
