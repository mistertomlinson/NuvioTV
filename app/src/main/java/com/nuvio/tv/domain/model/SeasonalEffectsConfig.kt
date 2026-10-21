package com.nuvio.tv.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month

/**
 * Christmas holiday effects.
 *
 * The visual configuration remains fixed at the approved values.
 * Only [holidayMode] is user configurable.
 */
data class SeasonalEffectsConfig(
    val holidayMode: HolidayMode = HolidayMode.AUTOMATIC,
    val startupMode: SnowStartupMode = SnowStartupMode.FALL_IN_FROM_TOP,
    val particleCount: Int = 120,
    val targetFps: Int = 30,
    val snowfallSpeed: SnowfallSpeed = SnowfallSpeed.FAST,
    val sizeProfile: SnowSizeProfile = SnowSizeProfile.MIXED,
    val wind: SnowWind = SnowWind.MEDIUM,
    val sway: SnowSway = SnowSway.OFF,
    val opacity: SnowOpacity = SnowOpacity.BRIGHT,
    val depthLayers: Int = 3,
    val reduceDensityWhileScrolling: Boolean = false,
    val reduceFpsWhileScrolling: Boolean = false,
    val freezeWhileScrolling: Boolean = false,
    val pauseDuringHeroTrailer: Boolean = false,
    val blur: SnowBlur = SnowBlur.OFF
) {
    fun isChristmasActive(
        date: LocalDate = LocalDate.now()
    ): Boolean =
        when (holidayMode) {
            HolidayMode.ON ->
                true

            HolidayMode.OFF ->
                false

            HolidayMode.AUTOMATIC ->
                isChristmasAutomaticDate(date)
        }

    private fun isChristmasAutomaticDate(
        date: LocalDate
    ): Boolean {
        if (
            date.month != Month.NOVEMBER &&
            date.month != Month.DECEMBER
        ) {
            return false
        }

        val start =
            christmasAutomaticStart(
                date.year
            )

        val end =
            LocalDate.of(
                date.year,
                Month.DECEMBER,
                31
            )

        return !date.isBefore(start) &&
            !date.isAfter(end)
    }

    companion object {
        val Recommended =
            SeasonalEffectsConfig()

        private fun christmasAutomaticStart(
            year: Int
        ): LocalDate {
            var firstThursday =
                LocalDate.of(
                    year,
                    Month.NOVEMBER,
                    1
                )

            while (
                firstThursday.dayOfWeek !=
                DayOfWeek.THURSDAY
            ) {
                firstThursday =
                    firstThursday.plusDays(1)
            }

            val thanksgiving =
                firstThursday.plusWeeks(3)

            return thanksgiving.plusDays(1)
        }
    }
}

enum class HolidayMode {
    ON,
    OFF,
    AUTOMATIC
}

enum class SnowStartupMode {
    PRE_FILLED,
    FALL_IN_FROM_TOP,
    SOFT_FADE_IN
}

enum class SnowfallSpeed(
    val multiplier: Float
) {
    SLOW(0.65f),
    NORMAL(1.0f),
    FAST(1.45f),
    BLIZZARD(2.0f)
}

enum class SnowSizeProfile {
    TINY,
    BALANCED,
    LARGE,
    MIXED
}

enum class SnowWind(
    val pixelsPerSecondAt1080p: Float
) {
    OFF(0f),
    LIGHT(18f),
    MEDIUM(42f),
    STRONG(82f)
}

enum class SnowSway(
    val amplitudeAt1080p: Float
) {
    OFF(0f),
    LIGHT(8f),
    MEDIUM(18f),
    STRONG(34f)
}

enum class SnowOpacity(
    val multiplier: Float
) {
    SUBTLE(0.68f),
    NORMAL(1.0f),
    BRIGHT(1.22f)
}

enum class SnowBlur(
    val radiusDp: Float
) {
    OFF(0f),
    LIGHT(0.8f),
    MEDIUM(1.6f),
    HEAVY(3.0f)
}
