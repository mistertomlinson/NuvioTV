@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.player.rememberRawSvgPainter
import com.nuvio.tv.ui.theme.NuvioColors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import android.view.KeyEvent as AndroidKeyEvent

private val WatchedRatingGlassBrush = androidx.compose.ui.graphics.Brush.verticalGradient(
    colors = listOf(
        Color(0xAD2A3038),
        Color(0x9E20252C),
        Color(0xA824292F)
    )
)
private val WatchedRatingGlassRowColor = Color.Transparent
private val WatchedRatingGlassRowFocusedColor = Color.White.copy(alpha = 0.16f)
private val WatchedRatingGlassBorderColor = Color.White.copy(alpha = 0.09f)
private val WatchedRatingGlassFocusBorderColor = Color.White.copy(alpha = 0.28f)

/**
 * Compatibility path for screens that still present the rating UI in a
 * platform dialog. Home supplies a real Haze source through the overload
 * below, while existing callers keep their previous API and window behavior.
 */
@Composable
fun WatchedRatingOverlay(
    visible: Boolean,
    onRate: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    if (visible) {
        Dialog(onDismissRequest = onDismiss) {
            WatchedRatingOverlay(
                visible = true,
                hazeState = remember { HazeState() },
                blurEnabled = false,
                onRate = onRate,
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
fun WatchedRatingOverlay(
    visible: Boolean,
    hazeState: HazeState,
    blurEnabled: Boolean,
    onRate: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val dismissFocusRequester = remember { FocusRequester() }
    val dislikeFocusRequester = remember { FocusRequester() }
    val likeFocusRequester = remember { FocusRequester() }
    val loveFocusRequester = remember { FocusRequester() }
    var consumed by remember { mutableStateOf(false) }
    var ratingButtonsCanFocus by remember(visible) {
        mutableStateOf(false)
    }
    val appearanceProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (visible) 220 else 180,
            easing = androidx.compose.animation.core.FastOutSlowInEasing
        ),
        label = "watchedRatingGlassAppearance"
    )

    LaunchedEffect(visible) {
        if (visible) {
            consumed = false
            androidx.compose.runtime.withFrameNanos { }
            runCatching { dismissFocusRequester.requestFocus() }
            kotlinx.coroutines.delay(64)
            ratingButtonsCanFocus = true
        }
    }

    BackHandler(enabled = visible) {
        if (!consumed) {
            consumed = true
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.EnterTransition.None,
        exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.95f)
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val panelShape = RoundedCornerShape(24.dp)
            val blurModifier = if (blurEnabled) {
                Modifier.hazeChild(
                    state = hazeState,
                    shape = panelShape,
                    tint = Color.Unspecified,
                    blurRadius = (1f + (29f * appearanceProgress)).dp,
                    noiseFactor = 0.025f * appearanceProgress
                )
            } else {
                Modifier
            }
            Column(
                modifier = Modifier
                    .then(blurModifier)
                    .graphicsLayer {
                        shape = panelShape
                        clip = true
                        alpha = appearanceProgress
                        val animatedScale = 0.96f + (0.04f * appearanceProgress)
                        scaleX = animatedScale
                        scaleY = animatedScale
                    }
                    .clip(panelShape)
                    .background(WatchedRatingGlassBrush, panelShape)
                    .border(1.dp, WatchedRatingGlassBorderColor, panelShape)
                    .padding(horizontal = 40.dp, vertical = 32.dp)
                    .onPreviewKeyEvent { keyEvent ->
                        when (keyEvent.nativeKeyEvent.keyCode) {
                            AndroidKeyEvent.KEYCODE_BACK,
                            AndroidKeyEvent.KEYCODE_ESCAPE -> {
                                if (keyEvent.type == KeyEventType.KeyUp && !consumed) {
                                    consumed = true
                                    onDismiss()
                                }
                                true
                            }
                            else -> false
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                Text(
                    text = "What Did You Think?",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = NuvioColors.TextPrimary
                )

                Spacer(modifier = Modifier.height(28.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WatchedRatingButton(
                        iconRes = R.raw.ic_player_rating_dislike,
                        contentDescription = "Thumbs down",
                        focusRequester = dislikeFocusRequester,
                        canFocus = ratingButtonsCanFocus,
                        nextFocusDown = dismissFocusRequester,
                        nextFocusUp = dismissFocusRequester,
                        nextFocusRight = likeFocusRequester,
                        onClick = { if (!consumed) { consumed = true; onRate(2) } }
                    )
                    WatchedRatingButton(
                        iconRes = R.raw.ic_player_rating_like,
                        contentDescription = "Thumbs up",
                        focusRequester = likeFocusRequester,
                        canFocus = ratingButtonsCanFocus,
                        nextFocusDown = dismissFocusRequester,
                        nextFocusUp = dismissFocusRequester,
                        nextFocusLeft = dislikeFocusRequester,
                        nextFocusRight = loveFocusRequester,
                        onClick = { if (!consumed) { consumed = true; onRate(7) } }
                    )
                    WatchedRatingButton(
                        iconRes = R.raw.ic_player_rating_love,
                        contentDescription = "Love it",
                        focusRequester = loveFocusRequester,
                        canFocus = ratingButtonsCanFocus,
                        nextFocusDown = dismissFocusRequester,
                        nextFocusUp = dismissFocusRequester,
                        nextFocusLeft = likeFocusRequester,
                        onClick = { if (!consumed) { consumed = true; onRate(10) } }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { if (!consumed) { consumed = true; onDismiss() } },
                    modifier = Modifier
                        .glassDialogFocusTransform()
                        .focusRequester(dismissFocusRequester)
                        .focusProperties {
                            up = likeFocusRequester
                            down = likeFocusRequester
                        }
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                when (keyEvent.nativeKeyEvent.keyCode) {
                                    AndroidKeyEvent.KEYCODE_DPAD_UP,
                                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                        runCatching { likeFocusRequester.requestFocus() }
                                        true
                                    }
                                    AndroidKeyEvent.KEYCODE_DPAD_LEFT,
                                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> true
                                    else -> false
                                }
                            } else false
                        },
                    colors = ButtonDefaults.colors(
                        containerColor = WatchedRatingGlassRowColor,
                        focusedContainerColor = WatchedRatingGlassRowFocusedColor,
                        contentColor = NuvioColors.TextSecondary,
                        focusedContentColor = NuvioColors.TextPrimary
                    ),
                    border = ButtonDefaults.border(
                        border = androidx.tv.material3.Border.None,
                        focusedBorder = androidx.tv.material3.Border.None
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(32.dp)),
                    scale = ButtonDefaults.scale(
                        focusedScale = 1f,
                        pressedScale = 1f
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Dismiss",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun WatchedRatingButton(
    iconRes: Int,
    contentDescription: String,
    focusRequester: FocusRequester,
    canFocus: Boolean,
    nextFocusDown: FocusRequester,
    nextFocusUp: FocusRequester,
    nextFocusLeft: FocusRequester? = null,
    nextFocusRight: FocusRequester? = null,
    onClick: () -> Unit
) {
    val painter = rememberRawSvgPainter(iconRes)
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(60.dp)
            .glassDialogFocusTransform(enabled = canFocus)
            .focusRequester(focusRequester)
            .focusProperties {
                this.canFocus = canFocus
                down = nextFocusDown
                up = nextFocusUp
                if (nextFocusLeft != null) left = nextFocusLeft
                if (nextFocusRight != null) right = nextFocusRight
            }
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                            runCatching { nextFocusUp.requestFocus() }; true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                            runCatching { nextFocusDown.requestFocus() }; true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (nextFocusLeft != null) runCatching { nextFocusLeft.requestFocus() }; true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (nextFocusRight != null) runCatching { nextFocusRight.requestFocus() }; true
                        }
                        else -> false
                    }
                } else false
            },
        colors = IconButtonDefaults.colors(
            containerColor = WatchedRatingGlassRowColor,
            focusedContainerColor = WatchedRatingGlassRowFocusedColor,
            contentColor = NuvioColors.TextSecondary,
            focusedContentColor = NuvioColors.TextPrimary
        ),
        border = IconButtonDefaults.border(
            border = androidx.tv.material3.Border.None,
            focusedBorder = androidx.tv.material3.Border.None
        ),
        shape = IconButtonDefaults.shape(shape = CircleShape),
        scale = IconButtonDefaults.scale(
            focusedScale = 1f,
            pressedScale = 1f
        )
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            modifier = Modifier.size(26.dp)
        )
    }
}
