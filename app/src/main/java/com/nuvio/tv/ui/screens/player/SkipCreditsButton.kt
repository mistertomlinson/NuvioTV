@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioColors

private const val SKIP_CREDITS_TIMEOUT_MS = 10_000

/**
 * Post-credit scene shortcut.
 *
 * Uses the same presentation as SkipIntroButton, but the progress bar has
 * end-action semantics: if the user does not select Skip Credits before the
 * ten-second countdown completes, normal end-of-title handling begins.
 */
@Composable
fun SkipCreditsButton(
    target: PostCreditSceneTiming?,
    controlsVisible: Boolean,
    onSkip: () -> Unit,
    onTimeout: () -> Unit,
    onVisibilityChanged: (Boolean) -> Unit = {},
    onFocused: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    val targetStartMs = target?.startMs

    var timeoutHandled by remember(targetStartMs) { mutableStateOf(false) }
    val progress = remember(targetStartMs) { Animatable(0f) }

    val internalFocusRequester = remember { FocusRequester() }
    val activeFocusRequester = focusRequester ?: internalFocusRequester
    var isFocused by remember { mutableStateOf(false) }

    val shouldShow = target != null && !timeoutHandled

    // Same behavior as Skip Intro: controls pause the countdown.
    // Unlike Skip Intro, completion means "I'm done watching this title."
    LaunchedEffect(targetStartMs, controlsVisible, timeoutHandled) {
        if (targetStartMs != null && !timeoutHandled && !controlsVisible) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = (
                        (1f - progress.value) * SKIP_CREDITS_TIMEOUT_MS
                    ).toInt().coerceAtLeast(1),
                    easing = LinearEasing
                )
            )

            // This coroutine is automatically cancelled if the target changes
            // because playback reached the scene naturally or another target
            // replaces it.
            timeoutHandled = true
            onTimeout()
        }
    }

    LaunchedEffect(shouldShow) {
        onVisibilityChanged(shouldShow)
    }

    LaunchedEffect(shouldShow, controlsVisible) {
        if (shouldShow && !controlsVisible) {
            try {
                activeFocusRequester.requestFocus()
            } catch (_: Exception) {
            }
        }
    }

    AnimatedVisibility(
        visible = shouldShow,
        enter = fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.8f),
        exit = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.8f),
        modifier = modifier
    ) {
        Card(
            onClick = onSkip,
            modifier = Modifier
                .focusRequester(activeFocusRequester)
                .then(
                    if (downFocusRequester != null) {
                        Modifier.focusProperties { down = downFocusRequester }
                    } else {
                        Modifier
                    }
                )
                .onPreviewKeyEvent { keyEvent ->
                    if (
                        downFocusRequester != null &&
                        keyEvent.nativeKeyEvent.action ==
                            android.view.KeyEvent.ACTION_DOWN &&
                        keyEvent.nativeKeyEvent.keyCode ==
                            android.view.KeyEvent.KEYCODE_DPAD_DOWN
                    ) {
                        try {
                            downFocusRequester.requestFocus()
                        } catch (_: Exception) {
                        }
                        true
                    } else {
                        false
                    }
                }
                .onFocusChanged {
                    isFocused = it.isFocused
                    if (it.isFocused) {
                        onFocused?.invoke()
                    }
                },
            colors = CardDefaults.colors(
                containerColor = Color(0xFF1E1E1E).copy(alpha = 0.85f),
                focusedContainerColor = NuvioColors.Secondary
            ),
            shape = CardDefaults.shape(
                shape = RoundedCornerShape(12.dp)
            )
        ) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.width(IntrinsicSize.Max)
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = 18.dp,
                        vertical = 12.dp
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = null,
                        tint = if (isFocused) {
                            NuvioColors.OnSecondary
                        } else {
                            Color.White
                        },
                        modifier = Modifier.size(20.dp)
                    )

                    Text(
                        text = "Skip Credits",
                        color = if (isFocused) {
                            NuvioColors.OnSecondary
                        } else {
                            Color.White
                        },
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(
                            RoundedCornerShape(
                                bottomStart = 12.dp,
                                bottomEnd = 12.dp
                            )
                        )
                        .background(
                            Color.White.copy(
                                alpha = if (controlsVisible) 0f else 0.15f
                            )
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.value)
                            .height(4.dp)
                            .background(
                                Color(0xFF1E1E1E).copy(
                                    alpha = if (controlsVisible) 0f else 0.85f
                                )
                            )
                    )
                }
            }
        }
    }
}
