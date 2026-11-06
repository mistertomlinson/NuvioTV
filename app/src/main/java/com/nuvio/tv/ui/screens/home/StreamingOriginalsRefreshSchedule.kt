package com.nuvio.tv.ui.screens.home

import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Streaming Originals serves exact-date releases against the New York calendar.
 * Use the same IANA zone for all Nuvio refresh boundaries so noon and midnight
 * retain their wall-clock meanings through spring-forward and fall-back.
 */
internal val STREAMING_ORIGINALS_TIME_ZONE: ZoneId =
    ZoneId.of("America/New_York")

internal enum class StreamingOriginalsTimedCheck(
    val at: LocalTime,
    val preferenceKey: String,
    val reason: String
) {
    MIDNIGHT_RECOVERY(
        LocalTime.of(0, 45),
        "last_0045_recovery_local_date",
        "00:45_eastern_midnight_recovery"
    ),
    DAYTIME_FIRST(
        LocalTime.of(11, 45),
        "last_1145_daytime_refresh_date",
        "11:45_eastern_daytime_refresh"
    ),
    DAYTIME_SECOND(
        LocalTime.of(12, 45),
        "last_1245_daytime_recovery_date",
        "12:45_eastern_daytime_recovery"
    ),
    PRE_MIDNIGHT(
        LocalTime.of(23, 45),
        "last_2345_precheck_local_date",
        "23:45_eastern_midnight_precheck"
    )
}

/**
 * Return all as-yet-unrecorded wall-clock checkpoints for today, in order.
 * The 12:45 PM check is independent of an 11:45 AM success; the 00:45 AM
 * recovery is only needed when the new calendar day has not been serviced.
 */
internal fun dueStreamingOriginalsTimedChecks(
    instant: ZonedDateTime,
    lastTriggeredDates: Map<StreamingOriginalsTimedCheck, String?>,
    midnightRefreshAlreadySucceeded: Boolean
): List<StreamingOriginalsTimedCheck> {
    val now = instant.withZoneSameInstant(STREAMING_ORIGINALS_TIME_ZONE)
    val today = now.toLocalDate().toString()
    return StreamingOriginalsTimedCheck.entries.filter { checkpoint ->
        now.toLocalTime() >= checkpoint.at &&
            lastTriggeredDates[checkpoint] != today &&
            (
                checkpoint != StreamingOriginalsTimedCheck.MIDNIGHT_RECOVERY ||
                    !midnightRefreshAlreadySucceeded
            )
    }
}
