package com.nuvio.tv.ui.screens.home

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** The focused landscape row can already expose its successor. */
internal fun <Row> homeExposureRows(
    rows: List<Row>,
    focusedRowIndex: Int,
    isLandscape: (Row) -> Boolean,
    canPrewarm: (Row) -> Boolean
): List<Row> {
    if (focusedRowIndex !in rows.indices) return emptyList()

    return buildList {
        for (bridgeIndex in focusedRowIndex..minOf(focusedRowIndex + 3, rows.lastIndex - 1)) {
            if (isLandscape(rows[bridgeIndex])) {
                val exposedRow = rows[bridgeIndex + 1]
                if (canPrewarm(exposedRow)) add(exposedRow)
            }
        }
    }
}

/**
 * Own each exposed window independently of focus and unrelated metadata updates.
 * An unchanged in-flight window keeps its job; a later idle update checks
 * completed windows' residency before asking Coil to recover missing images.
 * Only the current, bounded lookahead is retained. The owner's scope cancels
 * every job when the platform or Home composition changes.
 */
internal class HomeExposurePrewarmer<Window : Any>(
    private val scope: CoroutineScope,
    private val isWarm: (Window) -> Boolean = { false },
    private val prewarm: suspend (Window) -> Unit
) {
    private val permits = Semaphore(2)
    private val jobs = mutableMapOf<Window, Job>()

    fun update(windows: List<Window>) {
        val wanted = windows.toSet()
        val iterator = jobs.iterator()
        while (iterator.hasNext()) {
            val (window, job) = iterator.next()
            if (window !in wanted) {
                job.cancel()
                iterator.remove()
            }
        }

        for (window in wanted) {
            if (jobs[window]?.isActive == true) continue
            if (isWarm(window)) continue
            jobs[window] = scope.launch {
                permits.withPermit { prewarm(window) }
            }
        }
    }
}

internal data class HomeExposureWindow(
    val rowKey: String,
    val imageUrls: List<String>,
    val requestWidthPx: Int,
    val requestHeightPx: Int
)
