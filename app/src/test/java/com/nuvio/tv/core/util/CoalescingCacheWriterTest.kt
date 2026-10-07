package com.nuvio.tv.core.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoalescingCacheWriterTest {
    @Test
    fun `a burst writes one latest snapshot after a quiet period`() = runTest {
        var value = 0
        val saved = mutableListOf<Int>()
        val writer = CoalescingCacheWriter(backgroundScope, 200, 1000) { saved += value }
        repeat(100) { value++; writer.requestSave() }
        runCurrent()
        advanceTimeBy(199)
        runCurrent()
        assertEquals(emptyList<Int>(), saved)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(100), saved)
    }

    @Test
    fun `new requests restart quiet time without starving continuous progress`() = runTest {
        var saves = 0
        val writer = CoalescingCacheWriter(backgroundScope, 200, 1000) { saves++ }
        writer.requestSave()
        runCurrent()
        repeat(9) {
            advanceTimeBy(100)
            writer.requestSave()
            runCurrent()
        }
        assertEquals(0, saves)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, saves)
    }

    @Test
    fun `requests during a slow write are retained and never overlap or cancel it`() = runTest {
        val releaseFirst = CompletableDeferred<Unit>()
        val saved = mutableListOf<Int>()
        var value = 1
        var active = 0
        var maximumActive = 0
        val writer = CoalescingCacheWriter(backgroundScope, 200, 1000) {
            active++
            maximumActive = maxOf(maximumActive, active)
            val snapshot = value
            if (snapshot == 1) releaseFirst.await()
            saved += snapshot
            active--
        }
        writer.requestSave()
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        value = 2
        repeat(100) { writer.requestSave() }
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(emptyList<Int>(), saved)
        releaseFirst.complete(Unit)
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf(1, 2), saved)
        assertEquals(1, maximumActive)
    }
}
