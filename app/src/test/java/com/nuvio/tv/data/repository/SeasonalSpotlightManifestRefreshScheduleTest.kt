package com.nuvio.tv.data.repository

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeasonalSpotlightManifestRefreshScheduleTest {
    private val hourly = 60L * 60L * 1_000L

    private fun at(iso: String): ZonedDateTime = ZonedDateTime.parse(iso)

    private fun delay(iso: String) =
        seasonalSpotlightNextManifestPollDelayMs(at(iso), hourly)

    @Test
    fun `calendar is explicitly New York and regular checks remain hourly`() {
        assertEquals(
            ZoneId.of("America/New_York"),
            SEASONAL_SPOTLIGHT_REFRESH_TIME_ZONE
        )
        assertEquals(hourly, delay("2026-11-25T13:00:00-05:00[America/New_York]"))
        assertEquals(hourly, delay("2026-11-26T00:00:00-05:00[America/New_York]"))
    }

    @Test
    fun `Thanksgiving activation triggers a midnight manifest probe`() {
        assertEquals(
            120_000L,
            delay("2026-11-25T23:58:00-05:00[America/New_York]")
        )
        assertEquals(
            1_000L,
            delay("2026-11-25T23:59:59-05:00[America/New_York]")
        )
        assertEquals(
            1_000L,
            delay("2026-11-25T23:59:59.750-05:00[America/New_York]")
        )
    }

    @Test
    fun `spring and fall daylight savings changes preserve wall-clock midnight`() {
        assertEquals(
            30L * 60_000L,
            delay("2026-03-08T23:30:00-04:00[America/New_York]")
        )
        assertEquals(
            30L * 60_000L,
            delay("2026-11-01T23:30:00-05:00[America/New_York]")
        )
    }

    @Test
    fun `device in Pacific time still refreshes at Eastern midnight`() {
        assertEquals(
            120_000L,
            delay("2026-11-25T20:58:00-08:00[America/Los_Angeles]")
        )
    }

    @Test
    fun `inflight request crossing the date boundary requires revalidation`() {
        assertTrue(
            seasonalSpotlightManifestPollCrossedEasternMidnight(
                at("2026-11-25T23:59:59-05:00[America/New_York]"),
                at("2026-11-26T00:00:01-05:00[America/New_York]")
            )
        )
        assertFalse(
            seasonalSpotlightManifestPollCrossedEasternMidnight(
                at("2026-11-26T00:00:01-05:00[America/New_York]"),
                at("2026-11-26T00:00:02-05:00[America/New_York]")
            )
        )
    }

    @Test
    fun `only Eastern midnight matters for a cross-zone request`() {
        assertFalse(
            seasonalSpotlightManifestPollCrossedEasternMidnight(
                at("2026-11-25T23:59:58Z"),
                at("2026-11-26T00:00:01Z")
            )
        )
        assertTrue(
            seasonalSpotlightManifestPollCrossedEasternMidnight(
                at("2026-11-26T04:59:58Z"),
                at("2026-11-26T05:00:01Z")
            )
        )
    }
}
