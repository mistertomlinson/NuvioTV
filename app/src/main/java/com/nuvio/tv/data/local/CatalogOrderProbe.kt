package com.nuvio.tv.data.local

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CATALOG_ORDER_PROBE — DIAGNOSTIC ONLY. REMOVE BEFORE SHIPPING.
 * Grep "CATALOG_ORDER_PROBE" to find every reference to strip.
 * See CATALOG_ORDER_BUG_INVESTIGATION.md at repo root.
 *
 * Pull the log when the bug occurs:
 *   adb -s <device> shell run-as com.nuviodebug.com cat files/catalog_order_probe.log > order_log.txt
 */
object CatalogOrderProbe {
    private const val TAG = "CATALOG_ORDER_PROBE"
    private const val FILE_NAME = "catalog_order_probe.log"
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun log(context: Context, event: String, detail: String) {
        val line = "${fmt.format(Date())} | $event | $detail"
        // Full line to the persistent file (untruncated).
        runCatching {
            File(context.filesDir, FILE_NAME).appendText(line + "\n")
        }
        // logcat has a ~4000-char per-line cap that clips long order lists mid-field,
        // hiding the output=/droppedFromSaved= tail. Emit in <=3000-char chunks with a
        // stamp so the full line can be reassembled from logcat without file access.
        val stamp = fmt.format(Date())
        val chunkSize = 3000
        if (line.length <= chunkSize) {
            android.util.Log.d(TAG, line)
        } else {
            val total = (line.length + chunkSize - 1) / chunkSize
            var idx = 0
            var part = 1
            while (idx < line.length) {
                val end = minOf(idx + chunkSize, line.length)
                android.util.Log.d(TAG, "[$stamp CHUNK $part/$total] " + line.substring(idx, end))
                idx = end
                part++
            }
        }
    }

    fun stack(): String {
        return Throwable().stackTrace
            .asSequence()
            .map { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber }
            .filter { it.contains("nuvio", ignoreCase = true) || it.contains("Catalog") || it.contains("Home") || it.contains("Addon") }
            .take(12)
            .joinToString(" <- ")
    }

    fun dropped(before: List<String>, after: List<String>): List<String> {
        val a = after.toSet()
        return before.filterNot { it in a }
    }
}
