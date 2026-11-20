package com.nuvio.tv.ui.screens.home

import coil.ImageLoader
import coil.request.ImageRequest
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class XlArtworkPresentationTest {
    @Test fun aStaleOrDifferentRowCommitCannotReleaseTheIncomingArtwork() {
        val presentation = XlArtworkPresentation()
        presentation.renderers["row"] = true
        presentation.frame = XlArtworkFrame("other", "title", "url")
        assertFalse(presentation.ready("row", "title", "url"))
        presentation.frame = XlArtworkFrame("row", "previous", "url")
        assertFalse(presentation.ready("row", "title", "url"))
        presentation.frame = XlArtworkFrame("row", "title", "old-url")
        assertFalse(presentation.ready("row", "title", "url"))
        presentation.frame = XlArtworkFrame("row", "title", "url")
        assertTrue(presentation.ready("row", "title", "url"))
    }

    @Test fun preparingADestinationRunsBothLoadsConcurrentlyAndCancelsBothWhenObsolete() = runBlocking {
        val loader = mockk<ImageLoader>()
        val started = AtomicInteger()
        val cancelled = AtomicInteger()
        val bothStarted = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Nothing>()
        coEvery { loader.execute(any()) } coAnswers {
            if (started.incrementAndGet() == 2) bothStarted.complete(Unit)
            try { never.await() } finally { cancelled.incrementAndGet() }
        }
        val job = launch { prepareXlArtworkPair(loader, listOf(mockk<ImageRequest>(), mockk<ImageRequest>())) }
        withTimeout(5_000) { bothStarted.await() }
        assertTrue(job.isActive)
        job.cancelAndJoin()
        assertEquals(2, started.get())
        assertEquals("Neither stale image load should continue after focus changes", 2, cancelled.get())
    }
}
