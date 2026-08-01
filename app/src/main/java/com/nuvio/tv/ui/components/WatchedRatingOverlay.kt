@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
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
import android.view.KeyEvent as AndroidKeyEvent

@Composable
fun WatchedRatingOverlay(
    visible: Boolean,
    onRate: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val dismissFocusRequester = remember { FocusRequester() }
    val dislikeFocusRequester = remember { FocusRequester() }
    val likeFocusRequester = remember { FocusRequester() }
    val loveFocusRequester = remember { FocusRequester() }
    var consumed by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (visible) {
            consumed = false
            kotlinx.coroutines.delay(80)
            runCatching { dismissFocusRequester.requestFocus() }
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + scaleIn(tween(220), initialScale = 0.95f),
        exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.95f)
    ) {
        Dialog(onDismissRequest = { if (!consumed) { consumed = true; onDismiss() } }) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(NuvioColors.BackgroundElevated, RoundedCornerShape(20.dp))
                    .border(1.dp, NuvioColors.Border, RoundedCornerShape(20.dp))
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
                        nextFocusDown = dismissFocusRequester,
                        nextFocusUp = dismissFocusRequester,
                        nextFocusRight = likeFocusRequester,
                        onClick = { if (!consumed) { consumed = true; onRate(2) } }
                    )
                    WatchedRatingButton(
                        iconRes = R.raw.ic_player_rating_like,
                        contentDescription = "Thumbs up",
                        focusRequester = likeFocusRequester,
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
                        containerColor = Color.Transparent,
                        focusedContainerColor = NuvioColors.BackgroundCard,
                        contentColor = NuvioColors.TextSecondary,
                        focusedContentColor = NuvioColors.TextPrimary
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
            .focusRequester(focusRequester)
            .focusProperties {
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
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = Color.White,
            contentColor = NuvioColors.TextPrimary,
            focusedContentColor = Color.Black
        ),
        shape = IconButtonDefaults.shape(shape = CircleShape)
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            modifier = Modifier.size(26.dp)
        )
    }
}
