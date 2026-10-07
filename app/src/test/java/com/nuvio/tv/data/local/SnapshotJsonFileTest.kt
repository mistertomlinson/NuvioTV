package com.nuvio.tv.data.local

import com.google.gson.reflect.TypeToken
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class SnapshotJsonFileTest {
    @get:Rule val temp = TemporaryFolder()
    private data class Entry(val value: String, val cachedAtMs: Long)
    private val type = object : TypeToken<Map<String, Entry>>() {}.type
    private fun store(file: File) = SnapshotJsonFile<Entry>({ file }, type)

    @Test
    fun `existing JSON loads once and unchanged saves do not rewrite`() {
        val file = temp.newFile("cache.json")
        file.writeText("""{"a":{"value":"title","cachedAtMs":123}}""")
        val cache = store(file)
        val first = cache.read()
        assertEquals(Entry("title", 123), first["a"])
        assertSame(first, cache.read())
        assertFalse(cache.write(first.toMap()))
        assertEquals(first, store(file).read())
    }

    @Test
    fun `changed snapshots replace disk and survive a new process`() {
        val file = File(temp.root, "cache.json")
        val cache = store(file)
        cache.read()
        val values = mapOf("a" to Entry("title", 123), "b" to Entry("other", 456))
        assertTrue(cache.write(values))
        assertEquals(values, store(file).read())
        assertFalse(File(temp.root, "cache.json.tmp").exists())
    }

    @Test
    fun `failed write leaves previous file and snapshot available for retry`() {
        val file = File(temp.root, "cache.json")
        val cache = store(file)
        val old = mapOf("a" to Entry("old", 123))
        val updated = mapOf("a" to Entry("new", 456))
        cache.write(old)
        val blockedTemporary = File(temp.root, "cache.json.tmp")
        blockedTemporary.mkdir()
        try {
            cache.write(updated)
            fail("Expected the write to fail")
        } catch (_: IOException) { }
        assertEquals(old, cache.read())
        assertEquals(old, store(file).read())
        assertTrue(cache.write(updated))
        assertEquals(updated, store(file).read())
    }

    @Test
    fun `external deletion invalidates retained entries`() {
        val file = File(temp.root, "cache.json")
        val cache = store(file)
        cache.write(mapOf("a" to Entry("old", 123)))
        assertTrue(file.delete())
        assertTrue(cache.read().isEmpty())
    }
}
