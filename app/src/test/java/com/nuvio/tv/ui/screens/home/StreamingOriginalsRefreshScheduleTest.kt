package com.nuvio.tv.ui.screens.home

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingOriginalsRefreshScheduleTest {
    private fun at(iso: String): ZonedDateTime =
        ZonedDateTime.parse(iso)

    private fun due(
        iso: String,
        last: Map<StreamingOriginalsTimedCheck, String?> = emptyMap()
    ) = dueStreamingOriginalsTimedChecks(at(iso), last)

    @Test
    fun `there are exactly three Eastern checkpoints and no overnight retries`() {
        assertEquals(ZoneId.of("America/New_York"), STREAMING_ORIGINALS_TIME_ZONE)
        assertEquals(
            listOf(
                StreamingOriginalsTimedCheck.MIDNIGHT,
                StreamingOriginalsTimedCheck.DAYTIME_FIRST,
                StreamingOriginalsTimedCheck.DAYTIME_SECOND
            ),
            StreamingOriginalsTimedCheck.entries
        )
        assertEquals(
            listOf("00:00", "11:45", "12:45"),
            StreamingOriginalsTimedCheck.entries.map { it.at.toString() }
        )
    }

    @Test
    fun `midnight is the only overnight refresh checkpoint`() {
        val prior = StreamingOriginalsTimedCheck.entries.associateWith {
            "2026-11-05"
        }
        assertEquals(
            listOf(StreamingOriginalsTimedCheck.MIDNIGHT),
            due("2026-11-06T00:00:00-05:00[America/New_York]", prior)
        )

        val todayMidnightRecorded =
            prior + (StreamingOriginalsTimedCheck.MIDNIGHT to "2026-11-06")
        assertTrue(
            due("2026-11-06T00:45:00-05:00[America/New_York]", todayMidnightRecorded)
                .isEmpty()
        )
    }

    @Test
    fun `eleven forty five and twelve forty five PM are independent`() {
        val midnightDone = mapOf(StreamingOriginalsTimedCheck.MIDNIGHT to "2026-11-06")
        assertTrue(
            due("2026-11-06T11:44:59-05:00[America/New_York]", midnightDone)
                .isEmpty()
        )
        assertEquals(
            listOf(StreamingOriginalsTimedCheck.DAYTIME_FIRST),
            due("2026-11-06T11:45:00-05:00[America/New_York]", midnightDone)
        )
        assertEquals(
            listOf(StreamingOriginalsTimedCheck.DAYTIME_SECOND),
            due(
                "2026-11-06T12:45:00-05:00[America/New_York]",
                midnightDone + (StreamingOriginalsTimedCheck.DAYTIME_FIRST to "2026-11-06")
            )
        )
    }

    @Test
    fun `resume catchup coalesces all missed checkpoints into one staged refresh`() {
        assertEquals(
            StreamingOriginalsTimedCheck.entries,
            due("2026-11-06T13:30:00-05:00[America/New_York]")
        )
        val recorded = StreamingOriginalsTimedCheck.entries.associateWith {
            "2026-11-06"
        }
        assertTrue(due("2026-11-06T18:00:00-05:00[America/New_York]", recorded).isEmpty())
    }

    @Test
    fun `spring forward keeps midnight and daytime checks at Eastern wall clock time`() {
        // On 2026-03-08, New York skips 02:00-02:59 and is EDT by noon.
        val midnightDone = mapOf(StreamingOriginalsTimedCheck.MIDNIGHT to "2026-03-08")
        assertEquals(
            listOf(StreamingOriginalsTimedCheck.MIDNIGHT),
            due("2026-03-08T05:00:00Z")
        )
        assertTrue(due("2026-03-08T07:00:00Z", midnightDone).isEmpty())
        assertEquals(
            listOf(StreamingOriginalsTimedCheck.DAYTIME_FIRST),
            due("2026-03-08T15:45:00Z", midnightDone)
        )
        assertEquals(
            listOf(StreamingOriginalsTimedCheck.DAYTIME_SECOND),
            due(
                "2026-03-08T16:45:00Z",
                midnightDone + (StreamingOriginalsTimedCheck.DAYTIME_FIRST to "2026-03-08")
            )
        )
    }

    @Test
    fun `fall back repeated one AM does not re-run midnight`() {
        // On 2026-11-01, 01:30 happens twice; noon is EST.
        val midnightDone = mapOf(StreamingOriginalsTimedCheck.MIDNIGHT to "2026-11-01")
        assertTrue(due("2026-11-01T05:30:00Z", midnightDone).isEmpty())
        assertTrue(due("2026-11-01T06:30:00Z", midnightDone).isEmpty())
        assertEquals(
            listOf(StreamingOriginalsTimedCheck.DAYTIME_FIRST),
            due("2026-11-01T16:45:00Z", midnightDone)
        )
        assertEquals(
            listOf(StreamingOriginalsTimedCheck.DAYTIME_SECOND),
            due(
                "2026-11-01T17:45:00Z",
                midnightDone + (StreamingOriginalsTimedCheck.DAYTIME_FIRST to "2026-11-01")
            )
        )
    }

    @Test
    fun `timed HTTP probe starts while Home is visible without touching UI`() {
        assertTrue(shouldStartStreamingOriginalsProbe(
            refreshDue = true, stageReady = false,
            stageRunning = false, seasonalBusy = false
        ))
        assertFalse(shouldEnrichStreamingOriginalsStage(homePresentationVisible = true))
        assertFalse(shouldApplyStreamingOriginalsStage(
            homePresentationVisible = true, stageReady = true
        ))
    }

    @Test
    fun `only offscreen window can enrich and atomically swap prepared rows`() {
        assertTrue(shouldEnrichStreamingOriginalsStage(homePresentationVisible = false))
        assertTrue(shouldApplyStreamingOriginalsStage(
            homePresentationVisible = false, stageReady = true
        ))
        assertFalse(shouldApplyStreamingOriginalsStage(
            homePresentationVisible = false, stageReady = false
        ))
    }

    @Test
    fun `staging is skipped when work is active or Seasonal owns the gate`() {
        for (flags in listOf(
            listOf(false, false, false, false),
            listOf(true, true, false, false),
            listOf(true, false, true, false),
            listOf(true, false, false, true)
        )) {
            assertFalse(shouldStartStreamingOriginalsProbe(
                refreshDue = flags[0],
                stageReady = flags[1],
                stageRunning = flags[2],
                seasonalBusy = flags[3]
            ))
        }
    }
    @Test
    fun `dream window focus loss counts as offscreen even if Home remains composed`() {
        assertTrue(homePresentationActive(windowFocused = true, lifecycleResumed = true))
        assertFalse(homePresentationActive(windowFocused = false, lifecycleResumed = true))
        assertFalse(homePresentationActive(windowFocused = true, lifecycleResumed = false))
        assertFalse(homePresentationActive(windowFocused = false, lifecycleResumed = false))
    }
    @Test
    fun `different device timezone cannot shift Eastern checkpoints`() {
        val midnightDone = mapOf(StreamingOriginalsTimedCheck.MIDNIGHT to "2026-11-06")
        assertEquals(
            listOf(StreamingOriginalsTimedCheck.DAYTIME_FIRST),
            due("2026-11-06T08:45:00-08:00[America/Los_Angeles]", midnightDone)
        )
        assertFalse(
            due("2026-11-06T07:44:00-08:00[America/Los_Angeles]", midnightDone)
                .isNotEmpty()
        )
    }
}
