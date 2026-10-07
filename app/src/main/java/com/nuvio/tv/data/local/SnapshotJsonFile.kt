package com.nuvio.tv.data.local

import com.google.gson.Gson
import java.io.File
import java.io.IOException
import java.lang.reflect.Type

/** Caller serializes access and runs on IO. Values must be immutable snapshots. */
internal class SnapshotJsonFile<T>(
    private val file: () -> File,
    private val type: Type,
    private val gson: Gson = Gson()
) {
    private var snapshot: Map<String, T>? = null
    private var modified = -1L
    private var length = -1L

    fun read(): Map<String, T> {
        val target = file()
        snapshot?.let {
            if (target.lastModified() == modified && target.length() == length) return it
        }
        val entries: Map<String, T> = if (target.exists()) {
            target.bufferedReader().use { gson.fromJson(it, type) } ?: emptyMap()
        } else {
            emptyMap()
        }
        remember(target, entries)
        return entries
    }

    /** Returns false without serialization or IO writes when nothing changed. */
    fun write(entries: Map<String, T>): Boolean {
        val target = file()
        if (target.exists() && target.lastModified() == modified &&
            target.length() == length && entries == snapshot
        ) return false

        val temporary = File(target.parentFile, "${target.name}.tmp")
        try {
            // Stream JSON instead of allocating a second full-cache String.
            temporary.bufferedWriter().use { gson.toJson(entries, type, it) }
            if (!temporary.renameTo(target)) {
                // Keep the previous complete file if replacement fails.
                throw IOException("Cannot replace cache file ${target.name}")
            }
            remember(target, entries)
            return true
        } finally {
            temporary.delete()
        }
    }

    private fun remember(target: File, entries: Map<String, T>) {
        snapshot = entries
        modified = target.lastModified()
        length = target.length()
    }
}
