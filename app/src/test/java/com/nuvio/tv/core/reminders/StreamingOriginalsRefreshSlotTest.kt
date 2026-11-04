package com.nuvio.tv.core.reminders

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingOriginalsRefreshSlotTest {
    private val eastern = ZoneId.of("America/New_York")

    @Test
    fun top10ChecksAreAt1145And1245Eastern() {
        val before = ZonedDateTime.of(2026, 11, 3, 11, 40, 0, 0, eastern)
        assertEquals(
            LocalTime.of(11, 45),
            StreamingOriginalsRefreshSlot.TOP10_PRIMARY.nextRun(before).toLocalTime()
        )
        assertEquals(
            LocalTime.of(12, 45),
            StreamingOriginalsRefreshSlot.TOP10_RECOVERY.nextRun(before).toLocalTime()
        )
        assertEquals(
            LocalDate.of(2026, 11, 3),
            StreamingOriginalsRefreshSlot.TOP10_PRIMARY.nextRun(before).toLocalDate()
        )
    }

    @Test
    fun missedPrimaryCheckReschedulesToNextLocalDay() {
        val after = ZonedDateTime.of(2026, 11, 3, 11, 46, 0, 0, eastern)
        val next = StreamingOriginalsRefreshSlot.TOP10_PRIMARY.nextRun(after)
        assertEquals(LocalDate.of(2026, 11, 4), next.toLocalDate())
        assertEquals(LocalTime.of(11, 45), next.toLocalTime())
    }

    @Test
    fun overnightSlotsHaveIndependentWallClockTimes() {
        val afternoon = ZonedDateTime.of(2026, 11, 3, 20, 0, 0, 0, eastern)
        assertEquals(
            LocalTime.of(23, 45),
            StreamingOriginalsRefreshSlot.NIGHT_PRECHECK.nextRun(afternoon).toLocalTime()
        )
        assertEquals(
            LocalDate.of(2026, 11, 4),
            StreamingOriginalsRefreshSlot.MIDNIGHT.nextRun(afternoon).toLocalDate()
        )
        assertEquals(
            LocalTime.of(0, 45),
            StreamingOriginalsRefreshSlot.NIGHT_RECOVERY.nextRun(afternoon).toLocalTime()
        )
    }

    @Test
    fun dayLightSavingTransitionDoesNotShiftLocalNoonChecks() {
        val beforeDst = ZonedDateTime.of(2027, 3, 13, 13, 0, 0, 0, eastern)
        val beforeFall = ZonedDateTime.of(2027, 11, 6, 13, 0, 0, 0, eastern)
        val afterSpring = StreamingOriginalsRefreshSlot.TOP10_PRIMARY.nextRun(beforeDst)
        val afterFall = StreamingOriginalsRefreshSlot.TOP10_PRIMARY.nextRun(beforeFall)
        assertEquals(LocalTime.of(11, 45), afterSpring.toLocalTime())
        assertEquals(LocalTime.of(11, 45), afterFall.toLocalTime())
        assertTrue(afterSpring.offset.totalSeconds != beforeDst.offset.totalSeconds)
        assertTrue(afterFall.offset.totalSeconds != beforeFall.offset.totalSeconds)
    }
}
