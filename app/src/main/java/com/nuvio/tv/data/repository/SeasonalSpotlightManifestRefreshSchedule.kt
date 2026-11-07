package com.nuvio.tv.data.repository

import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Holiday/event visibility is decided by the Seasonal Spotlight backend using
 * the Eastern calendar. The manifest needs a targeted revalidation when that
 * calendar changes dates, regardless of when the app's normal hourly poll began.
 */
internal val SEASONAL_SPOTLIGHT_REFRESH_TIME_ZONE: ZoneId =
    ZoneId.of("America/New_York")

/**
 * Keep existing hourly manifest checks, but never sleep across Eastern midnight.
 * This schedules one additional targeted manifest check at the day boundary,
 * without introducing a new timer or touching unrelated addons.
 */
internal fun seasonalSpotlightNextManifestPollDelayMs(
    now: ZonedDateTime,
    regularIntervalMs: Long
): Long {
    require(regularIntervalMs >= 1_000L)

    val easternNow = now.withZoneSameInstant(
        SEASONAL_SPOTLIGHT_REFRESH_TIME_ZONE
    )
    val nextMidnight = easternNow.toLocalDate()
        .plusDays(1)
        .atStartOfDay(SEASONAL_SPOTLIGHT_REFRESH_TIME_ZONE)

    val toMidnightMs = Duration.between(
        easternNow, nextMidnight
    ).toMillis()

    // If the calculation lands within the last sub-second of the day,
    // wake just after the boundary rather than spinning before midnight.
    return minOf(regularIntervalMs, toMidnightMs)
        .coerceAtLeast(1_000L)
}

/**
 * A manifest request may start before midnight and finish after it. In that
 * case, schedule another targeted probe immediately: the first request may
 * have observed yesterday's backend manifest.
 */
internal fun seasonalSpotlightManifestPollCrossedEasternMidnight(
    startedAt: ZonedDateTime,
    finishedAt: ZonedDateTime
): Boolean =
    startedAt.withZoneSameInstant(SEASONAL_SPOTLIGHT_REFRESH_TIME_ZONE)
        .toLocalDate() !=
        finishedAt.withZoneSameInstant(SEASONAL_SPOTLIGHT_REFRESH_TIME_ZONE)
            .toLocalDate()
