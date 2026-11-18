package com.nuvio.tv.core.diagnostics

import android.content.Context
import android.util.Log
import com.nuvio.tv.domain.model.Stream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * Event-only playback breadcrumbs, not a playback-position sampler.
 *
 * Each process gets an ID; every decision/transition is written as one JSON line.
 * Storage is bounded to two 512 KiB files in the app-specific external directory,
 * accessible with adb pull. The writer never runs on the player/UI thread.
 * No playback URLs, API keys, request headers or magnet links are stored.
 */
object PlaybackTrace {
    private const val TAG = "NuvioPlaybackTrace"
    private const val MAX_FILE_BYTES = 512L * 1024L
    private const val RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
    private const val CURRENT = "playback-current.jsonl"
    private const val PREVIOUS = "playback-previous.jsonl"

    private val processId = UUID.randomUUID().toString().take(12)
    private val counter = AtomicLong(0)
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "nuvio-playback-diagnostics").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    @Volatile private var directory: File? = null

    fun install(context: Context) {
        val base = context.applicationContext.getExternalFilesDir(null)
            ?: context.applicationContext.filesDir
        directory = File(base, "diagnostics")
        record("process_start", "appPackage" to context.packageName)
    }

    /** A deterministic, non-reversible identifier for matching the same link or torrent. */
    fun fingerprint(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .take(10)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    /** Always use this for Stream metadata; never serialize a Stream or a raw URL. */
    fun stream(event: String, source: Stream, vararg details: Pair<String, Any?>) {
        record(
            event,
            "addon" to source.addonName,
            "name" to source.name,
            "title" to source.title,
            "filename" to (
                source.behaviorHints?.filename
                    ?: source.clientResolve?.filename
                    ?: source.clientResolve?.stream?.raw?.filename
            ),
            "torrentName" to source.clientResolve?.torrentName,
            "torrentIdHash" to fingerprint(source.infoHash ?: source.clientResolve?.infoHash),
            "streamUrlHash" to fingerprint(source.getStreamUrl()),
            "fileIdx" to source.fileIdx,
            "resolveFileIdx" to source.clientResolve?.fileIdx,
            "bingeGroup" to source.behaviorHints?.bingeGroup,
            *details
        )
    }

    fun record(event: String, vararg details: Pair<String, Any?>) {
        if (directory == null) return
        val now = System.currentTimeMillis()
        val sequence = counter.incrementAndGet()
        val safeDetails = details.take(32).map { (key, value) ->
            key.take(48) to scrub(key, value)
        }
        worker.execute {
            try {
                val root = directory ?: return@execute
                if (!root.exists() && !root.mkdirs()) return@execute
                val current = File(root, CURRENT)
                val previous = File(root, PREVIOUS)
                if (current.length() > MAX_FILE_BYTES) {
                    if (previous.exists()) previous.delete()
                    if (!current.renameTo(previous)) current.delete()
                }
                if (previous.exists() && now - previous.lastModified() > RETENTION_MS) {
                    previous.delete()
                }
                if (current.exists() && now - current.lastModified() > RETENTION_MS) {
                    current.delete()
                }
                val data = JSONObject()
                    .put("timestampMs", now)
                    .put("process", processId)
                    .put("seq", sequence)
                    .put("event", event.take(80))
                val fields = JSONObject()
                safeDetails.forEach { (key, value) -> fields.put(key, value ?: JSONObject.NULL) }
                data.put("fields", fields)
                val line = data.toString()
                FileOutputStream(current, true).bufferedWriter(Charsets.UTF_8).use {
                    it.append(line).append('\n')
                }
                Log.d(TAG, line)
            } catch (error: Exception) {
                Log.w(TAG, "Could not write playback breadcrumb", error)
            }
        }
    }

    internal fun scrub(key: String, value: Any?): Any? {
        if (value == null) return null
        val field = key.lowercase()
        if ((field.contains("url") && !field.endsWith("hash")) || field.contains("token") ||
            field.contains("secret") || field.contains("password") ||
            field.contains("authorization") || field.contains("magnet") ||
            field.contains("header") || field.contains("apikey")) {
            return "<redacted>"
        }
        return when (value) {
            is Number, is Boolean -> value
            else -> value.toString().take(220)
                .replace(Regex("""(?i)(?:https?://|magnet:\?)[^\s]+"""), "<redacted-link>")
        }
    }
}
