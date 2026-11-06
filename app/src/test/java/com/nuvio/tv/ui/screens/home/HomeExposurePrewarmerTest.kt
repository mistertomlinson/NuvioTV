package com.nuvio.tv.ui.screens.home

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeExposurePrewarmerTest {
    private data class Row(val key: String, val landscape: Boolean = false)

    private fun targets(rows: List<Row>, focused: Int) = homeExposureRows(
        rows = rows,
        focusedRowIndex = focused,
        isLandscape = { it.landscape },
        canPrewarm = { it.key != "continue_watching" }
    ).map { it.key }

    @Test
    fun `the exposed target survives moving from above onto its landscape bridge`() {
        val rows = listOf(Row("above"), Row("bridge", true), Row("exposed"))
        assertEquals(listOf("exposed"), targets(rows, 0))
        assertEquals(listOf("exposed"), targets(rows, 1))
    }

    @Test
    fun `consecutive landscape rows prepare every exposed orientation`() {
        val rows = listOf(Row("above"), Row("wide1", true), Row("wide2", true), Row("portrait"))
        assertEquals(listOf("wide2", "portrait"), targets(rows, 0))
        assertEquals(listOf("wide2", "portrait"), targets(rows, 1))
        assertEquals(listOf("portrait"), targets(rows, 2))
    }

    @Test
    fun `lookahead stays bounded and excludes Continue Watching`() {
        val rows = listOf(
            Row("above"), Row("bridge", true), Row("continue_watching"),
            Row("near", true), Row("exposed"), Row("far", true), Row("too-far")
        )
        assertEquals(listOf("exposed"), targets(rows, 0))
        assertTrue(targets(rows, -1).isEmpty())
        assertTrue(targets(rows, rows.lastIndex).isEmpty())
    }

    @Test
    fun `ordinary portrait rows do not schedule exposure work`() {
        assertTrue(targets(listOf(Row("a"), Row("b")), 0).isEmpty())
    }

    @Test
    fun `focus landing and unrelated metadata updates do not restart a slow window`() = runTest {
        val release = CompletableDeferred<Unit>()
        var started = 0
        var finished = 0
        var cancelled = 0
        val warmer = HomeExposurePrewarmer<String>(backgroundScope) {
            started++
            try {
                release.await()
                finished++
            } finally {
                if (!release.isCompleted) cancelled++
            }
        }
        warmer.update(listOf("same-artwork-and-size"))
        runCurrent()
        repeat(50) { warmer.update(listOf("same-artwork-and-size")) }
        runCurrent()
        assertEquals(1, started)
        assertEquals(0, cancelled)
        release.complete(Unit)
        runCurrent()
        assertEquals(1, finished)
    }

    @Test
    fun `adding a nearby exposure preserves the existing in-flight batch`() = runTest {
        val started = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val warmer = HomeExposurePrewarmer<String>(backgroundScope) { window ->
            started += window
            try {
                awaitCancellation()
            } finally {
                cancelled += window
            }
        }
        warmer.update(listOf("still-exposed"))
        runCurrent()
        warmer.update(listOf("still-exposed", "next-exposure"))
        runCurrent()
        assertEquals(listOf("still-exposed", "next-exposure"), started)
        assertTrue(cancelled.isEmpty())
        warmer.update(listOf("next-exposure"))
        runCurrent()
        assertEquals(listOf("still-exposed"), cancelled)
    }

    @Test
    fun `completed windows are checked again after cache eviction`() = runTest {
        val cache = mutableSetOf<String>()
        var decodes = 0
        var requests = 0
        val warmer = HomeExposurePrewarmer<String>(backgroundScope, isWarm = cache::contains) {
            requests++
            if (cache.add(it)) decodes++
        }
        warmer.update(listOf("poster"))
        runCurrent()
        warmer.update(listOf("poster"))
        runCurrent()
        assertEquals(1, requests)
        assertEquals(1, decodes)
        cache.clear()
        warmer.update(listOf("poster"))
        runCurrent()
        assertEquals(2, requests)
        assertEquals(2, decodes)
    }

    @Test
    fun `failed image attempts can retry without a changed window signature`() = runTest {
        var attempts = 0
        var succeeded = false
        val warmer = HomeExposurePrewarmer<String>(backgroundScope) {
            attempts++
            succeeded = attempts > 1
        }
        warmer.update(listOf("poster"))
        runCurrent()
        assertFalse(succeeded)
        warmer.update(listOf("poster"))
        runCurrent()
        assertTrue(succeeded)
    }

    @Test
    fun `changed artwork cancels only the outdated window`() = runTest {
        val cancelled = mutableListOf<HomeExposureWindow>()
        val old = HomeExposureWindow("row", listOf("old.jpg"), 200, 300)
        val new = old.copy(imageUrls = listOf("new.jpg"))
        val other = old.copy(rowKey = "other")
        val warmer = HomeExposurePrewarmer<HomeExposureWindow>(backgroundScope) {
            try {
                awaitCancellation()
            } finally {
                cancelled += it
            }
        }
        warmer.update(listOf(old, other))
        runCurrent()
        warmer.update(listOf(new, other))
        runCurrent()
        assertEquals(listOf(old), cancelled)
    }

    @Test
    fun `size changes invalidate a window and duplicate URLs do not duplicate jobs`() = runTest {
        val started = mutableListOf<HomeExposureWindow>()
        val small = HomeExposureWindow("row", listOf("poster"), 200, 300)
        val large = small.copy(requestWidthPx = 500)
        val warmer = HomeExposurePrewarmer<HomeExposureWindow>(backgroundScope) {
            started += it
            awaitCancellation()
        }
        warmer.update(listOf(small, small))
        runCurrent()
        warmer.update(listOf(large))
        runCurrent()
        assertEquals(listOf(small, large), started)
    }

    @Test
    fun `four nearby windows use at most two concurrent requests`() = runTest {
        val releases = (1..4).associateWith { CompletableDeferred<Unit>() }
        var active = 0
        var maximum = 0
        val finished = mutableListOf<Int>()
        val warmer = HomeExposurePrewarmer<Int>(backgroundScope) {
            active++
            maximum = maxOf(maximum, active)
            releases.getValue(it).await()
            active--
            finished += it
        }
        warmer.update(listOf(1, 2, 3, 4))
        runCurrent()
        assertEquals(2, active)
        releases.getValue(1).complete(Unit)
        releases.getValue(2).complete(Unit)
        runCurrent()
        assertEquals(2, active)
        releases.getValue(3).complete(Unit)
        releases.getValue(4).complete(Unit)
        runCurrent()
        assertEquals(2, maximum)
        assertEquals(listOf(1, 2, 3, 4), finished)
    }

    @Test
    fun `leaving the page drops active and queued windows`() = runTest {
        val started = mutableListOf<Int>()
        val cancelled = mutableListOf<Int>()
        val warmer = HomeExposurePrewarmer<Int>(backgroundScope) {
            started += it
            try {
                awaitCancellation()
            } finally {
                cancelled += it
            }
        }
        warmer.update(listOf(1, 2, 3, 4))
        runCurrent()
        warmer.update(emptyList())
        runCurrent()
        assertEquals(listOf(1, 2), started)
        assertEquals(listOf(1, 2), cancelled)
    }
}
