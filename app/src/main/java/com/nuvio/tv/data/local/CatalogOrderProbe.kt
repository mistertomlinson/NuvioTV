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
        android.util.Log.d(TAG, line)
        runCatching {
            File(context.filesDir, FILE_NAME).appendText(line + "\n")
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
