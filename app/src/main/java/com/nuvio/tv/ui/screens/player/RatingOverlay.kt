@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nuvio.tv.R
import kotlinx.coroutines.launch
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type

private const val RATING_NONE = -1

@Composable
fun RatingOverlay(
    visible: Boolean,
    logo: String?,
    title: String,
    onRate: (Int) -> Unit,
    onDismiss: () -> Unit,
    onReturnToVideo: () -> Unit,
    onExitAnimationComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dismissFocusRequester = remember { FocusRequester() }
    val returnFocusRequester = remember { FocusRequester() }
    val dislikeFocusRequester = remember { FocusRequester() }
    val likeFocusRequester = remember { FocusRequester() }
    val loveFocusRequester = remember { FocusRequester() }

    // Entrance animation
    val overlayAlpha = remember { Animatable(0f) }
    val logoAlpha = remember { Animatable(0f) }
    val logoOffsetY = remember { Animatable(16f) }
    val textAlpha = remember { Animatable(0f) }
    val textOffsetY = remember { Animatable(12f) }
    val buttonsAlpha = remember { Animatable(0f) }
    val buttonsOffsetY = remember { Animatable(12f) }
    val actionsAlpha = remember { Animatable(0f) }

    // Exit
    val exitAlpha = remember { Animatable(1f) }
    var selectedRating by remember { mutableIntStateOf(RATING_NONE) }
    var isExiting by remember { mutableStateOf(false) }
    var isDismissing by remember { mutableStateOf(false) }

    // Per-button scale
    val dislikeScale = remember { Animatable(1f) }
    val likeScale = remember { Animatable(1f) }
    val loveScale = remember { Animatable(1f) }

    // Entrance sequence
    LaunchedEffect(visible) {
        if (visible) {
            isExiting = false
            selectedRating = RATING_NONE
            exitAlpha.snapTo(1f)
            overlayAlpha.snapTo(0f)
            logoAlpha.snapTo(0f)
            logoOffsetY.snapTo(16f)
            textAlpha.snapTo(0f)
            textOffsetY.snapTo(12f)
            buttonsAlpha.snapTo(0f)
            buttonsOffsetY.snapTo(12f)
            actionsAlpha.snapTo(0f)

            overlayAlpha.animateTo(1f, tween(250))

            launch { logoAlpha.animateTo(1f, tween(220, easing = FastOutSlowInEasing)) }
            launch { logoOffsetY.animateTo(0f, tween(260, easing = FastOutSlowInEasing)) }

            kotlinx.coroutines.delay(80)
            launch { textAlpha.animateTo(1f, tween(200, easing = FastOutSlowInEasing)) }
            launch { textOffsetY.animateTo(0f, tween(240, easing = FastOutSlowInEasing)) }

            kotlinx.coroutines.delay(80)
            launch { buttonsAlpha.animateTo(1f, tween(200, easing = FastOutSlowInEasing)) }
            launch { buttonsOffsetY.animateTo(0f, tween(240, easing = FastOutSlowInEasing)) }

            kotlinx.coroutines.delay(60)
            actionsAlpha.animateTo(1f, tween(180))

            runCatching { dismissFocusRequester.requestFocus() }
        } else {
            overlayAlpha.snapTo(0f)
        }
    }

    // Exit sequence — icon pulse → reverse stagger → notify complete
    LaunchedEffect(selectedRating) {
        if (selectedRating == RATING_NONE || isExiting) return@LaunchedEffect
        isExiting = true

        // 1. Icon pulse
        val scaleAnim = when (selectedRating) {
            2 -> dislikeScale
            7 -> likeScale
            else -> loveScale
        }
        launch {
            scaleAnim.animateTo(
                targetValue = 1f,
                animationSpec = keyframes {
                    durationMillis = 300
                    1f at 0
                    1.35f at 120
                    1f at 300
                }
            )
        }

        // 2. Notify rate (triggers blackout behind us) then wait for blackout
        onRate(selectedRating)
        kotlinx.coroutines.delay(300) // blackout fade-in is 350ms

        // 3. Reverse stagger out: actions → buttons → text → logo
        launch { actionsAlpha.animateTo(0f, tween(150)) }
        kotlinx.coroutines.delay(80)
        launch { buttonsAlpha.animateTo(0f, tween(150)) }
        launch { buttonsOffsetY.animateTo(12f, tween(180, easing = FastOutSlowInEasing)) }
        kotlinx.coroutines.delay(80)
        launch { textAlpha.animateTo(0f, tween(130)) }
        launch { textOffsetY.animateTo(12f, tween(160, easing = FastOutSlowInEasing)) }
        kotlinx.coroutines.delay(80)
        logoAlpha.animateTo(0f, tween(150))
        logoOffsetY.animateTo(16f, tween(180, easing = FastOutSlowInEasing))

        // 4. Signal complete — PlayerScreen triggers stopAndRelease + navigation
        onExitAnimationComplete()
    }

    // Dismiss exit sequence
    LaunchedEffect(isDismissing) {
        if (!isDismissing) return@LaunchedEffect
        kotlinx.coroutines.delay(300) // wait for blackout
        launch { actionsAlpha.animateTo(0f, tween(150)) }
        kotlinx.coroutines.delay(80)
        launch { buttonsAlpha.animateTo(0f, tween(150)) }
        launch { buttonsOffsetY.animateTo(12f, tween(180, easing = FastOutSlowInEasing)) }
        kotlinx.coroutines.delay(80)
        launch { textAlpha.animateTo(0f, tween(130)) }
        launch { textOffsetY.animateTo(12f, tween(160, easing = FastOutSlowInEasing)) }
        kotlinx.coroutines.delay(80)
        logoAlpha.animateTo(0f, tween(150))
        logoOffsetY.animateTo(16f, tween(180, easing = FastOutSlowInEasing))
        onExitAnimationComplete()
    }

    if (!visible && overlayAlpha.value == 0f) return

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = overlayAlpha.value * exitAlpha.value }
            // Intercept Back so player underneath doesn't respond;
            // pass d-pad through so focus system can navigate between buttons
            .onPreviewKeyEvent { keyEvent ->
                when (keyEvent.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_BACK,
                    AndroidKeyEvent.KEYCODE_ESCAPE -> {
                        if (keyEvent.type == KeyEventType.KeyUp && !isExiting) onDismiss()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY,
                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> true // swallow media keys only
                    else -> false // let d-pad and select through to focus system
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Dim layer
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.88f), Color.Transparent)
                )
            )
            drawRect(color = Color.Black.copy(alpha = 0.55f))
            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Black.copy(alpha = 0.6f),
                        0.3f to Color.Black.copy(alpha = 0.4f),
                        0.6f to Color.Black.copy(alpha = 0.2f),
                        1f to Color.Transparent
                    )
                )
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo / title
            Box(
                modifier = Modifier.graphicsLayer {
                    alpha = logoAlpha.value
                    translationY = logoOffsetY.value.dp.toPx()
                },
                contentAlignment = Alignment.Center
            ) {
                if (!logo.isNullOrBlank()) {
                    var logoFailed by remember(logo) { mutableStateOf(false) }
                    if (!logoFailed) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(logo)
                                .memoryCacheKey(logo)
                                .crossfade(true)
                                .build(),
                            contentDescription = title,
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.Center,
                            modifier = Modifier.height(80.dp),
                            onError = { logoFailed = true }
                        )
                    } else {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineLarge,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineLarge,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // "What did you think?"
            Box(
                modifier = Modifier.graphicsLayer {
                    alpha = textAlpha.value
                    translationY = textOffsetY.value.dp.toPx()
                }
            ) {
                Text(
                    text = "What did you think?",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal),
                    color = Color.White.copy(alpha = 0.9f)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Rating buttons
            Box(
                modifier = Modifier.graphicsLayer {
                    alpha = buttonsAlpha.value
                    translationY = buttonsOffsetY.value.dp.toPx()
                }
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RatingButton(
                        iconRes = R.raw.ic_player_rating_dislike,
                        contentDescription = "Thumbs down",
                        scale = dislikeScale.value,
                        focusRequester = dislikeFocusRequester,
                        nextFocusDown = dismissFocusRequester,
                        nextFocusUp = dismissFocusRequester,
                        nextFocusRight = likeFocusRequester,
                        onClick = { if (!isExiting) selectedRating = 2 }
                    )
                    RatingButton(
                        iconRes = R.raw.ic_player_rating_like,
                        contentDescription = "Thumbs up",
                        scale = likeScale.value,
                        focusRequester = likeFocusRequester,
                        nextFocusDown = dismissFocusRequester,
                        nextFocusUp = dismissFocusRequester,
                        nextFocusLeft = dislikeFocusRequester,
                        nextFocusRight = loveFocusRequester,
                        onClick = { if (!isExiting) selectedRating = 7 }
                    )
                    RatingButton(
                        iconRes = R.raw.ic_player_rating_love,
                        contentDescription = "Love it",
                        scale = loveScale.value,
                        focusRequester = loveFocusRequester,
                        nextFocusDown = dismissFocusRequester,
                        nextFocusUp = dismissFocusRequester,
                        nextFocusLeft = likeFocusRequester,
                        onClick = { if (!isExiting) selectedRating = 10 }
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Action buttons
            Box(
                modifier = Modifier.graphicsLayer { alpha = actionsAlpha.value }
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Dismiss → exits to detail screen
                    Button(
                        onClick = {
                        if (!isExiting && !isDismissing) {
                            isDismissing = true
                            onDismiss()
                        }
                    },
                        modifier = Modifier
                            .focusRequester(dismissFocusRequester)
                            .focusProperties {
                                up = likeFocusRequester
                                down = returnFocusRequester
                            }
                            .onPreviewKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyDown) {
                                    when (keyEvent.nativeKeyEvent.keyCode) {
                                        AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                                            runCatching { likeFocusRequester.requestFocus() }
                                            true
                                        }
                                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                            runCatching { returnFocusRequester.requestFocus() }
                                            true
                                        }
                                        AndroidKeyEvent.KEYCODE_DPAD_LEFT,
                                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> true // block
                                        else -> false
                                    }
                                } else false
                            },
                        colors = ButtonDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.15f),
                            focusedContainerColor = Color.White.copy(alpha = 0.28f),
                            contentColor = Color.White,
                            focusedContentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Dismiss",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }

                    // Return to video → resumes playback
                    Button(
                        onClick = { if (!isExiting) onReturnToVideo() },
                        modifier = Modifier
                            .focusRequester(returnFocusRequester)
                            .focusProperties {
                                up = dismissFocusRequester
                            }
                            .onPreviewKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyDown) {
                                    when (keyEvent.nativeKeyEvent.keyCode) {
                                        AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                                            runCatching { dismissFocusRequester.requestFocus() }
                                            true
                                        }
                                        AndroidKeyEvent.KEYCODE_DPAD_DOWN,
                                        AndroidKeyEvent.KEYCODE_DPAD_LEFT,
                                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> true // block
                                        else -> false
                                    }
                                } else false
                            },
                        colors = ButtonDefaults.colors(
                            containerColor = Color.Transparent,
                            focusedContainerColor = Color.White.copy(alpha = 0.15f),
                            contentColor = Color.White.copy(alpha = 0.65f),
                            focusedContentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Return to video",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RatingButton(
    iconRes: Int,
    contentDescription: String,
    scale: Float,
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
            .size(64.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .focusRequester(focusRequester)
            .focusProperties {
                down = nextFocusDown
                up = nextFocusUp
                // Only set left/right if a target exists; otherwise focus stays put
                if (nextFocusLeft != null) left = nextFocusLeft
                if (nextFocusRight != null) right = nextFocusRight
            }
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                            runCatching { nextFocusUp.requestFocus() }
                            true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                            runCatching { nextFocusDown.requestFocus() }
                            true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (nextFocusLeft != null) runCatching { nextFocusLeft.requestFocus() }
                            true // always consume — prevents wrap-around
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (nextFocusRight != null) runCatching { nextFocusRight.requestFocus() }
                            true // always consume — prevents wrap-around
                        }
                        else -> false
                    }
                } else false
            },
        colors = IconButtonDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.12f),
            focusedContainerColor = Color.White,
            contentColor = Color.White,
            focusedContentColor = Color.Black
        ),
        shape = IconButtonDefaults.shape(shape = CircleShape)
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            modifier = Modifier.size(28.dp)
        )
    }
}
