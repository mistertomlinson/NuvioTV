package com.nuvio.tv.ui.screens.home

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Local, explicitly enabled diagnostics. No disk or logcat writes on the UI thread. */
internal object XlNavigationTrace {
    @Volatile private var directory: File? = null
    @Volatile private var configured = false
    @Volatile private var deadline = 0L
    @Volatile var frameNumber = 0L
        private set
    private val sequence = AtomicLong()
    private val queue = Channel<String>(8192, BufferOverflow.DROP_OLDEST)
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val enabled: Boolean get() = directory != null
    val active: Boolean get() = enabled && SystemClock.elapsedRealtime() <= deadline

    fun configure(context: Context) {
        if (configured) return
        configured = true
        val parent = context.getExternalFilesDir(null) ?: return
        val diagnostics = File(parent, "diagnostics")
        if (!File(diagnostics, "xl-trace.enabled").isFile) return
        directory = diagnostics
        deadline = SystemClock.elapsedRealtime() + 2500
        writer.launch {
            val out = File(diagnostics, "xl-navigation-trace.tsv")
            try {
                out.writeText("seq\tmonotonic_ns\tframe\tevent\tfields\n")
                while (true) {
                    val first = queue.receive()
                    delay(80)
                    val batch = StringBuilder(first).append('\n')
                    repeat(512) {
                        val next = queue.tryReceive().getOrNull() ?: return@repeat
                        batch.append(next).append('\n')
                    }
                    out.appendText(batch.toString())
                }
            } catch (_: java.io.IOException) {
                directory = null
            }
        }
        event("SESSION") { "schema=1 density=${context.resources.displayMetrics.density}" }
    }

    fun key(action: Int, code: Int, repeat: Int, eventTime: Long, downTime: Long, rowKey: String?) {
        if (!enabled) return
        deadline = SystemClock.elapsedRealtime() + 5000
        event("KEY") { "action=$action code=$code repeat=$repeat eventTime=$eventTime downTime=$downTime focused=$rowKey" }
    }

    fun nextFrame() { frameNumber++ }
    fun event(name: String, fields: () -> String) {
        if (!active) return
        val seq = sequence.incrementAndGet()
        queue.trySend("$seq\t${SystemClock.elapsedRealtimeNanos()}\t$frameNumber\t$name\t${fields().replace('\n', ' ').replace('\t', ' ')}")
    }
}

@Composable
internal fun ObserveXlNavigationTrace(scene: XlSceneState, list: LazyListState) {
    val context = LocalContext.current
    // Configure once on a real Home mount. Ordinary builds never start a writer
    // or a frame observer when the marker is absent.
    DisposableEffect(context) {
        XlNavigationTrace.configure(context.applicationContext)
        onDispose { }
    }
    LaunchedEffect(scene, list) {
        if (!XlNavigationTrace.enabled) return@LaunchedEffect
        while (true) {
            if (!XlNavigationTrace.active) {
                delay(100)
                continue
            }
            withFrameNanos {
                XlNavigationTrace.nextFrame()
                XlNavigationTrace.event("FRAME") {
                    val info = list.layoutInfo
                    val move = scene.transition
                    val motion = scene.verticalMotion
                    val selected = scene.selection
                    val rows = info.visibleItemsInfo.joinToString(";") { row ->
                        val key = row.key.toString()
                        val fixed = scene.fixedFollowerOffsetForIndex(row.index)
                        val preview = scene.previewReturnOffsetForIndex(row.index)
                        "${row.index},$key,${row.offset + info.beforeContentPadding},${row.size},${scene.gapPx(key)},${scene.headerPx(key)},$fixed,$preview,${scene.summaryTrailingGapReductions[key]?.value ?: 0f}"
                    }
                    "selected=${selected?.rowKey}/${selected?.itemKey} xl=${selected?.isXl} summary=${selected?.summary} " +
                        "deferred=${scene.deferredDownwardExit} continuous=${scene.continuousVerticalNavigation} collapsed=${scene.continuousGapsCollapsed} held=${scene.verticalKeyHeld}/${scene.continuousHeldScroll} epoch=${scene.continuousInputEpoch} " +
                        "move=${move?.id}:${move?.from?.rowKey}>${move?.to?.rowKey} settled=${move?.rowsSettled} motion=${motion?.id}:${motion?.scrollPx} " +
                        "metadataEpoch=${scene.metadataEpoch} metadataAlpha=${scene.exitMetadataAlpha} stationary=${scene.stationaryMetadata?.rowKey} previewReturn=${scene.previewReturn?.rowKey} " +
                        "scrolling=${list.isScrollInProgress} canUp=${list.canScrollBackward} canDown=${list.canScrollForward} first=${list.firstVisibleItemIndex}/${list.firstVisibleItemScrollOffset} " +
                        "viewport=${info.viewportStartOffset},${info.viewportEndOffset},${info.viewportSize.height} padding=${info.beforeContentPadding} total=${info.totalItemsCount} rows(index,key,top,size,gap,header,fixed,preview,trimDp)=$rows"
                }
            }
        }
    }
}
