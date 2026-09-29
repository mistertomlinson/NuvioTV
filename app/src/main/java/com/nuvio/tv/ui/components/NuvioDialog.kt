package com.nuvio.tv.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import android.util.Log
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioColors
import dev.chrisbanes.haze.hazeChild

private val NuvioDialogGlassBrush = Brush.verticalGradient(
    colors = listOf(
        Color(0x842A3038),
        Color(0x7720252C),
        Color(0x7F24292F)
    )
)

private val NuvioDialogGlassBorderColor =
    Color.White.copy(alpha = 0.09f)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NuvioDialog(
    onDismiss: () -> Unit,
    title: String,
    subtitle: String? = null,
    width: Dp = 520.dp,
    suppressFirstKeyUp: Boolean = true,
    glass: Boolean = false,
    enhancedGlass: Boolean = false,
    compact: Boolean = false,
    usePlatformDefaultWidth: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    var suppressNextKeyUp by remember { mutableStateOf(suppressFirstKeyUp) }
    val appearanceProgress = remember { Animatable(0f) }

    /*
     * Read the host environment in the parent composition, before Dialog
     * creates its separate window/composition. This mirrors the working
     * Home popup implementation and preserves the Settings Haze state.
     */
    val glassEnvironment = LocalHomePopupGlassEnvironment.current
    val useEnhancedGlass = glass && enhancedGlass

    LaunchedEffect(useEnhancedGlass) {
        if (useEnhancedGlass) {
            appearanceProgress.snapTo(0f)
            appearanceProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 220,
                    easing = FastOutSlowInEasing
                )
            )
        } else {
            appearanceProgress.snapTo(1f)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = usePlatformDefaultWidth
        )
    ) {
        val dialogView = LocalView.current

        /*
         * Enhanced glass is an explicit caller opt-in. Settings uses it;
         * existing glass dialogs elsewhere keep their original behavior.
         */
        DisposableEffect(dialogView, useEnhancedGlass) {
            if (useEnhancedGlass) {
                (dialogView.parent as? DialogWindowProvider)
                    ?.window
                    ?.let { window ->
                        window.clearFlags(
                            WindowManager.LayoutParams.FLAG_DIM_BEHIND
                        )
                    }
            }

            onDispose { }
        }

        val shape =
            RoundedCornerShape(if (useEnhancedGlass) 24.dp else 16.dp)

        val borderColor = when {
            useEnhancedGlass -> NuvioDialogGlassBorderColor
            glass -> Color.White.copy(alpha = 0.12f)
            else -> NuvioColors.Border
        }

        val hazeState = glassEnvironment.hazeState

        if (useEnhancedGlass) {
            Log.d(
                "NuvioDialogGlass",
                "enhanced=true blurEnabled=${glassEnvironment.blurEnabled} " +
                    "hazeState=${if (hazeState != null) "present" else "NULL"}"
            )
        }

        val blurModifier =
            if (
                useEnhancedGlass &&
                glassEnvironment.blurEnabled &&
                hazeState != null
            ) {
                Modifier.hazeChild(
                    state = hazeState,
                    shape = shape,
                    tint = Color.Unspecified,
                    blurRadius =
                        (1f + (29f * appearanceProgress.value)).dp,
                    noiseFactor =
                        0.025f * appearanceProgress.value
                )
            } else {
                Modifier
            }

        Box(
            modifier =
                if (useEnhancedGlass) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                },
            contentAlignment = Alignment.Center
        ) {
        Box(
            modifier = Modifier
                .width(width)
                .graphicsLayer {
                    alpha =
                        if (useEnhancedGlass) {
                            appearanceProgress.value
                        } else {
                            1f
                        }
                    val animatedScale =
                        if (useEnhancedGlass) {
                            0.96f +
                                (0.04f * appearanceProgress.value)
                        } else {
                            1f
                        }
                    scaleX = animatedScale
                    scaleY = animatedScale
                }
                .then(blurModifier)
                .clip(shape)
                .then(
                    when {
                        useEnhancedGlass ->
                            Modifier.background(NuvioDialogGlassBrush, shape)
                        glass ->
                            Modifier.background(Color(0xD923292F), shape)
                        else ->
                            Modifier.background(NuvioColors.BackgroundElevated, shape)
                    }
                )
                .border(1.dp, borderColor, shape)
                .padding(
                    if (useEnhancedGlass) {
                        24.dp
                    } else if (compact) {
                        18.dp
                    } else {
                        24.dp
                    }
                )
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (suppressNextKeyUp && native.action == AndroidKeyEvent.ACTION_UP) {
                        if (isSelectKey(native.keyCode) || native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                            suppressNextKeyUp = false
                            return@onPreviewKeyEvent true
                        }
                    }
                    false
                }
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(
                    if (useEnhancedGlass) {
                        16.dp
                    } else if (compact) {
                        11.dp
                    } else {
                        16.dp
                    }
                )
            ) {
                Text(
                    text = title,
                    style =
                        if (useEnhancedGlass) {
                            MaterialTheme.typography.titleLarge
                        } else if (compact) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.titleLarge
                        },
                    color = NuvioColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style =
                            if (useEnhancedGlass) {
                                MaterialTheme.typography.bodyMedium
                            } else if (compact) {
                                MaterialTheme.typography.bodySmall
                            } else {
                                MaterialTheme.typography.bodyMedium
                            },
                        color = NuvioColors.TextSecondary
                    )
                }

                content()
            }
        }
        }
    }
}

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}
