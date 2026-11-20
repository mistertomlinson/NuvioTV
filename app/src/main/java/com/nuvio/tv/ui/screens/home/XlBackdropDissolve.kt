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

private class XlDissolveLayer(val frame: ModernHeroBackdropFrame, opacity: Float) {
    val alpha = Animatable(opacity)
}

/** Fade the new image over the exact retained composite, including interrupted fades. */
@Composable
internal fun XlBackdropDissolve(transition: Transition<ModernHeroBackdropFrame>, duration: Int,
    content: @Composable (ModernHeroBackdropFrame) -> Unit) {
    val layers = remember(transition) {
        mutableStateListOf(XlDissolveLayer(transition.currentState, 1f))
    }
    val latestContent by rememberUpdatedState(content)
    val target = transition.targetState
    LaunchedEffect(target, duration) {
        if (layers.lastOrNull()?.let { it.frame == target && it.alpha.value == 1f } == true) return@LaunchedEffect
        // Cancelling a previous effect freezes its partial opacity. Keep that
        // composite intact underneath this new fade, even when reversing.
        val incoming = XlDissolveLayer(target, 0f)
        layers.add(incoming)
        incoming.alpha.animateTo(1f, tween(duration, easing = LinearEasing))
        layers.removeAll { it !== incoming }
    }
    val renderers = layers.map { layer ->
        key(layer) {
            remember(layer) {
                movableContentOf {
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = layer.alpha.value }) {
                        latestContent(layer.frame)
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
