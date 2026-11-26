package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

private class XlDissolveLayer(val frame: ModernHeroBackdropFrame, opacity: Float) {
    val alpha = Animatable(opacity)
    var ready by mutableStateOf(opacity == 1f || frame.url.isNullOrBlank())
}

private val compositeSequence = AtomicInteger()

/** Blend prepared color fields, never a screen capture or a second blur. */
private suspend fun flattenBlurredLayers(layers: List<XlDissolveLayer>): ModernHeroBackdropFrame? {
    if (layers.size < 2 || layers.any { !it.frame.blurred || it.frame.blurredBitmap == null }) return null
    val images = layers.map { it.frame to it.alpha.value }
    return withContext(Dispatchers.Default) {
        val pixels = IntArray(XL_BLUR_WIDTH * XL_BLUR_HEIGHT)
        val colors = FloatArray(pixels.size * 3)
        for ((frame, alpha) in images) {
            val image = checkNotNull(frame.blurredBitmap)
            if (image.isRecycled || image.width != XL_BLUR_WIDTH || image.height != XL_BLUR_HEIGHT) return@withContext null
            image.getPixels(pixels, 0, XL_BLUR_WIDTH, 0, 0, XL_BLUR_WIDTH, XL_BLUR_HEIGHT)
            if (pixels.any { it ushr 24 != 255 }) return@withContext null
            val original = frame.compositedColors
            val remainder = 1f - alpha
            for (index in pixels.indices) {
                if (index % 2048 == 0) currentCoroutineContext().ensureActive()
                val offset = index * 3
                for (channel in 0..2) {
                    val source = original?.get(offset + channel) ?: ((pixels[index] ushr (16 - 8 * channel)) and 255).toFloat()
                    colors[offset + channel] = colors[offset + channel] * remainder + source * alpha
                }
            }
        }
        currentCoroutineContext().ensureActive()
        val result = Bitmap.createBitmap(XL_BLUR_WIDTH, XL_BLUR_HEIGHT, Bitmap.Config.ARGB_8888)
        for (index in pixels.indices) {
            val offset = index * 3
            pixels[index] = (255 shl 24) or (colors[offset].roundToInt().coerceIn(0, 255) shl 16) or
                (colors[offset + 1].roundToInt().coerceIn(0, 255) shl 8) or colors[offset + 2].roundToInt().coerceIn(0, 255)
        }
        result.setPixels(pixels, 0, XL_BLUR_WIDTH, 0, 0, XL_BLUR_WIDTH, XL_BLUR_HEIGHT)
        ModernHeroBackdropFrame("nuvio-xl-composite:${compositeSequence.incrementAndGet()}", 1f, false, true).apply {
            blurredBitmap = result
            compositedBitmap = result
            compositedColors = colors
        }
    }
}

/** Fade the new image over the exact retained composite, including interrupted fades. */
@Composable
internal fun XlBackdropDissolve(transition: Transition<ModernHeroBackdropFrame>, duration: Int,
    onFinished: (ModernHeroBackdropFrame) -> Unit = {},
    frozen: Boolean = false,
    content: @Composable (ModernHeroBackdropFrame, () -> Unit) -> Unit) {
    val layers = remember(transition) {
        mutableStateListOf(XlDissolveLayer(transition.currentState, 1f))
    }
    val latestContent by rememberUpdatedState(content)
    val latestFinished by rememberUpdatedState(onFinished)
    val target = transition.targetState
    LaunchedEffect(target, duration, frozen) {
        if (frozen) return@LaunchedEffect
        if (layers.lastOrNull()?.let { it.frame == target && it.alpha.value == 1f } == true) return@LaunchedEffect
        // Cancelling a previous effect freezes its partial opacity. Keep that
        // composite intact underneath this new fade, even when reversing.
        // Unseen cancelled requests contribute no pixels. Ready partial blurs
        // share a crop/gradient, so retain them as one tiny opaque field rather
        // than accumulating full-screen alpha surfaces on every rapid D-pad.
        layers.removeAll { it.alpha.value <= 0f }
        val retained = layers.lastOrNull()?.takeIf { it.frame == target }
        val incoming = if (retained != null) retained else {
            flattenBlurredLayers(layers.toList())?.let { composite ->
                layers.clear()
                layers.add(XlDissolveLayer(composite, 1f))
            }
            XlDissolveLayer(target, 0f).also { layers.add(it) }
        }
        withTimeoutOrNull(2_000) { snapshotFlow { incoming.ready }.first { it } }
        incoming.alpha.animateTo(1f, tween(duration, easing = LinearEasing))
        layers.removeAll { it !== incoming }
        latestFinished(target)
    }
    val renderers = layers.map { layer ->
        key(layer) {
            remember(layer) {
                movableContentOf {
                    Box(Modifier.fillMaxSize().graphicsLayer {
                        // Keep the complete image + its lighter gradient rasterized
                        // at alpha 1 too, while the platform/rows move around it.
                        // Tint remains inside this cache, preserving per-draw alpha.
                        compositingStrategy = CompositingStrategy.Offscreen
                        alpha = layer.alpha.value
                        XlNavigationTrace.event("BACKDROP_LAYER") { "image=${layer.frame.url?.hashCode()} blurred=${layer.frame.blurred} alpha=$alpha ready=${layer.ready}" }
                    }) {
                        latestContent(layer.frame) { layer.ready = true }
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        // Only interrupted fades can have multiple frozen partial images.
        // Cache their static composite once; the active alpha stays outside.
        Box(Modifier.fillMaxSize().graphicsLayer {
            compositingStrategy = if (renderers.size > 2) CompositingStrategy.Offscreen else CompositingStrategy.Auto
        }) {
            renderers.dropLast(1).forEach { it() }
        }
        renderers.lastOrNull()?.invoke()
    }
}
