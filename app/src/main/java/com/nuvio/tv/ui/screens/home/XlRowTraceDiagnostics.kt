package com.nuvio.tv.ui.screens.home

import android.os.SystemClock
import android.os.Trace
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Temporary diagnostics, intentionally without snapshot writes or changes to
 * layout geometry, prefetch, spring, scrolling, or poster fade.
 *
 * XL = outgoing compact XL content; R1 = first regular row underneath it;
 * R2 = second regular row underneath it (the potentially late third visible row).
 */
internal fun compactXlDiagnosticRole(
    enabled: Boolean, isXl: Boolean, hasSummary: Boolean,
    compactOrdinarySuffix: Boolean, distanceAfterXl: Int
): String? = when {
    !enabled -> null
    isXl && !hasSummary -> "XL"
    compactOrdinarySuffix && distanceAfterXl == 1 -> "R1"
    compactOrdinarySuffix && distanceAfterXl == 2 -> "R2"
    else -> null
}

internal object XlRowTraceDiagnostics {
    private const val TAG = "NuvioXLTrace"
    private val nextInstance = AtomicInteger(0)

    fun newInstance(): Int = nextInstance.incrementAndGet()

    fun event(marker: String, details: String) {
        // Android trace uses BOOTTIME; elapsedRealtimeNanos provides the
        // matching clock for correlating logcat and the Perfetto timeline.
        val elapsedNs = SystemClock.elapsedRealtimeNanos()
        Trace.beginSection("NXL:$marker")
        Trace.endSection()
        Log.i(TAG, "elapsedNs=$elapsedNs $marker $details")
    }

    fun navigation(from: String, to: String) =
        event("NAV_DOWN", "from=$from to=$to")

    fun scrollStarted(id: Int, key: String, totalPx: Float) =
        event("SCROLL_START", "motion=$id key=$key travelPx=$totalPx")

    fun scrollFinished(id: Int, key: String, completed: Boolean) =
        event("SCROLL_END", "motion=$id key=$key completed=$completed")
}

private class XlRowTraceTracker(private val role: String, private val rowKey: String) {
    private val instance = XlRowTraceDiagnostics.newInstance()
    private var committed = false
    private var measured = false
    private var placed = false
    private var becameVisible = false
    private var firstDraw = false
    private var draws = 0
    private var sizeChanges = 0

    private fun mark(stage: String, extra: String = "") {
        XlRowTraceDiagnostics.event("${role}_$stage",
            "instance=$instance row=$rowKey $extra")
    }

    fun mounted() = mark("MOUNT")

    fun compositionCommitted() {
        if (!committed) {
            committed = true
            mark("COMMIT")
        }
    }

    fun sizeChanged(width: Int, height: Int) {
        sizeChanges++
        if (!measured || sizeChanges <= 3) {
            measured = true
            mark("SIZE", "w=$width h=$height change=$sizeChanges")
        }
    }

    fun placed(bounds: Rect, rootHeight: Int) {
        if (!placed) {
            placed = true
            mark("PLACE", "y=${bounds.top.roundToInt()} bottom=${bounds.bottom.roundToInt()} viewportHeight=$rootHeight")
        }
        if (!becameVisible && rootHeight > 0 && bounds.bottom > 0f &&
            bounds.top < rootHeight.toFloat()) {
            becameVisible = true
            mark("VISIBLE", "y=${bounds.top.roundToInt()} bottom=${bounds.bottom.roundToInt()} viewportHeight=$rootHeight")
        }
    }

    fun drawn() {
        draws++
        if (!firstDraw) {
            firstDraw = true
            mark("DRAW_FIRST", "drawCount=$draws")
        }
    }

    fun disposed() = mark("DISPOSE",
        "draws=$draws sizeChanges=$sizeChanges wasVisible=$becameVisible")
}

/** Adds trace slices and first-occurrence logs only for compact XL + two followers. */
@Composable
internal fun Modifier.xlTraceRowLifecycle(role: String?, rowKey: String): Modifier {
    if (role == null) return this
    val tracker = remember(rowKey, role) { XlRowTraceTracker(role, rowKey) }
    val rootView = LocalView.current.rootView

    SideEffect { tracker.compositionCommitted() }
    DisposableEffect(tracker) {
        tracker.mounted()
        onDispose { tracker.disposed() }
    }

    return this
        .onSizeChanged { tracker.sizeChanged(it.width, it.height) }
        .onGloballyPositioned {
            tracker.placed(it.boundsInRoot(), rootView.height)
        }
        .drawWithContent {
            Trace.beginSection("NXL:${role}_DRAW")
            try {
                tracker.drawn()
                drawContent()
            } finally {
                Trace.endSection()
            }
        }
}
