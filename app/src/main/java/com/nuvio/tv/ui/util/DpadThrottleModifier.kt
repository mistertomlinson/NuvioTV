package com.nuvio.tv.ui.util

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val LocalFastHorizontalNavigationEnabled = compositionLocalOf { false }

fun Modifier.dpadRepeatThrottle(
    horizontalGateMs: Long = 80L,
    verticalGateMs: Long = 112L,
    onThrottling: ((Boolean) -> Unit)? = null
): Modifier = composed {
    val focusManager = LocalFocusManager.current
    val fastHorizontalNavigationEnabled = LocalFastHorizontalNavigationEnabled.current
    val lastRepeatTime = remember { longArrayOf(0L) }
    val scope = rememberCoroutineScope()
    val resetJob = remember { arrayOfNulls<Job>(1) }

    onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (native.action == KeyEvent.ACTION_DOWN &&
            native.repeatCount > 0 &&
            (native.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                native.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                native.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                native.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            val isVertical = native.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                native.keyCode == KeyEvent.KEYCODE_DPAD_UP
            val gateMs = if (isVertical) {
                verticalGateMs
            } else if (fastHorizontalNavigationEnabled) {
                48L
            } else {
                horizontalGateMs
            }
            val now = SystemClock.uptimeMillis()
            // Signal throttling active and schedule reset after idle
            onThrottling?.invoke(true)
            resetJob[0]?.cancel()
            resetJob[0] = scope.launch {
                delay(300L)
                onThrottling?.invoke(false)
            }
            if (now - lastRepeatTime[0] < gateMs) {
                return@onPreviewKeyEvent true
            }
            lastRepeatTime[0] = now
            val direction = when (native.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
                KeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
                KeyEvent.KEYCODE_DPAD_LEFT -> FocusDirection.Left
                KeyEvent.KEYCODE_DPAD_RIGHT -> FocusDirection.Right
                else -> null
            }
            if (direction != null) focusManager.moveFocus(direction)
            return@onPreviewKeyEvent true
        }
        false
    }
}
