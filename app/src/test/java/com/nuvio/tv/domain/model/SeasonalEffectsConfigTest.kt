package com.nuvio.tv.domain.model

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeasonalEffectsConfigTest {
    private val automatic = SeasonalEffectsConfig(holidayMode = HolidayMode.AUTOMATIC)

    @Test
    fun `automatic Christmas effects begin on Thanksgiving 2026`() {
        assertFalse(automatic.isChristmasActive(LocalDate.of(2026, 11, 25)))
        assertTrue(automatic.isChristmasActive(LocalDate.of(2026, 11, 26)))
        assertTrue(automatic.isChristmasActive(LocalDate.of(2026, 11, 27)))
    }

    @Test
    fun `automatic start follows fourth Thursday of November across years`() {
        assertFalse(automatic.isChristmasActive(LocalDate.of(2025, 11, 26)))
        assertTrue(automatic.isChristmasActive(LocalDate.of(2025, 11, 27)))

        assertFalse(automatic.isChristmasActive(LocalDate.of(2027, 11, 24)))
        assertTrue(automatic.isChristmasActive(LocalDate.of(2027, 11, 25)))

        assertFalse(automatic.isChristmasActive(LocalDate.of(2028, 11, 22)))
        assertTrue(automatic.isChristmasActive(LocalDate.of(2028, 11, 23)))
    }

    @Test
    fun `automatic effects end after New Years Eve`() {
        assertTrue(automatic.isChristmasActive(LocalDate.of(2026, 12, 31)))
        assertFalse(automatic.isChristmasActive(LocalDate.of(2027, 1, 1)))
        assertFalse(automatic.isChristmasActive(LocalDate.of(2026, 11, 1)))
    }

    @Test
    fun `manual on and off override the holiday window`() {
        val outsideWindow = LocalDate.of(2026, 7, 4)
        val insideWindow = LocalDate.of(2026, 11, 26)

        assertTrue(SeasonalEffectsConfig(holidayMode = HolidayMode.ON).isChristmasActive(outsideWindow))
        assertFalse(SeasonalEffectsConfig(holidayMode = HolidayMode.OFF).isChristmasActive(insideWindow))
    }
}
