package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimklScrobbleConflictTest {
    @Test
    fun `completed stop conflict requires explicit history recovery`() {
        val result = conflictResult(progress = 95.0)

        assertEquals(SimklScrobbleOutcome.SCROBBLE, result.outcome)
        assertTrue(result.requiresHistoryRecovery)
    }

    @Test
    fun `unfinished stop conflict remains paused without history recovery`() {
        val result = conflictResult(progress = 50.0)

        assertEquals(SimklScrobbleOutcome.PAUSE, result.outcome)
        assertFalse(result.requiresHistoryRecovery)
    }

    @Test
    fun `successful completed stop does not require history recovery`() {
        val result = SimklApiResponse(
            status = 201,
            body = """{"action":"scrobble","progress":95}""",
            headers = emptyMap()
        ).toSimklScrobbleResult(
            requestedAction = TrackingScrobbleAction.STOP,
            event = event(progress = 95.0),
            json = Json { ignoreUnknownKeys = true }
        )

        assertEquals(SimklScrobbleOutcome.SCROBBLE, result.outcome)
        assertFalse(result.requiresHistoryRecovery)
    }

    @Test
    fun `completion conflict writes history instead of trusting scrobble result`() = runTest {
        val media = movie()
        val result = conflictResult(progress = 95.0)
        val mutationService = mockk<SimklMutationService>()
        val syncRepository = mockk<SimklSyncRepository>(relaxed = true)
        coEvery { mutationService.addToHistory(any()) } returns TrackingMutationResult(1)

        val recovered = commitSimklScrobbleResult(
            result = result,
            media = media,
            mutationService = mutationService,
            syncRepository = syncRepository,
            watchedAtEpochMs = 123_456L
        )

        assertTrue(recovered)
        coVerify(exactly = 1) {
            mutationService.addToHistory(
                match { items ->
                    items.single().media == media &&
                        items.single().watchedAtEpochMs == 123_456L
                }
            )
        }
        coVerify(exactly = 1) { syncRepository.commitScrobble(result) }
    }

    private fun conflictResult(progress: Double): SimklScrobbleResult = SimklApiResponse(
        status = 409,
        body = "",
        headers = emptyMap(),
        isSoftSuccess = true
    ).toSimklScrobbleResult(
        requestedAction = TrackingScrobbleAction.STOP,
        event = event(progress),
        json = Json { ignoreUnknownKeys = true }
    )

    private fun event(progress: Double) = TrackingScrobbleEvent(
        media = movie(),
        progressPercent = progress
    )

    private fun movie() = TrackingMediaReference(
        kind = TrackingMediaKind.MOVIE,
        title = "Conflict Movie",
        ids = TrackingExternalIds(imdb = "tt32386654")
    )
}
