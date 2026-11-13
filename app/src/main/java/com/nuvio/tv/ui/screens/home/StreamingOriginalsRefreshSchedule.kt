package com.nuvio.tv.ui.screens.home

import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Streaming Originals uses the New York release-day calendar. All refresh
 * checkpoints use that same IANA timezone (EST/EDT), not a fixed UTC offset
 * or the device's possibly different local timezone.
 */
internal val STREAMING_ORIGINALS_TIME_ZONE: ZoneId =
    ZoneId.of("America/New_York")

/**
 * Exactly three checkpoints:
 * 00:00 — release-day Coming Soon rollover
 * 11:45 — normal daily Streaming Originals catalog changes
 * 12:45 — follow-up after the addon had time to finish/recover
 *
 * Each checkpoint is tracked separately. A successful first daytime check
 * cannot suppress the second one, and no 23:45/00:45 timers are scheduled.
 */
internal enum class StreamingOriginalsTimedCheck(
    val at: LocalTime,
    val preferenceKey: String,
    val reason: String
) {
    MIDNIGHT(
        LocalTime.MIDNIGHT,
        "last_midnight_eastern_refresh_date",
        "00:00_eastern_release_day_rollover"
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
    )
}

/**
 * Checks are local-date keyed, so repeated 1 AM on fall-back day cannot run
 * the midnight task twice. A resumed app coalesces missed checkpoints into
 * one fresh stage rather than fetching the whole addon three times.
 */
internal fun dueStreamingOriginalsTimedChecks(
    instant: ZonedDateTime,
    lastTriggeredDates: Map<StreamingOriginalsTimedCheck, String?>
): List<StreamingOriginalsTimedCheck> {
    val now = instant.withZoneSameInstant(STREAMING_ORIGINALS_TIME_ZONE)
    val today = now.toLocalDate().toString()
    return StreamingOriginalsTimedCheck.entries.filter { check ->
        now.toLocalTime() >= check.at &&
            lastTriggeredDates[check] != today
    }
}

/**
 * Two independent phases: a low-cost HTTP probe may run while Home owns focus,
 * but TMDB/image enrichment and the atomic row swap require an offscreen
 * window. Android TV dreams do not reliably dispose HomeScreen composition.
 */
internal fun shouldStartStreamingOriginalsProbe(
    refreshDue: Boolean,
    stageReady: Boolean,
    stageRunning: Boolean,
    seasonalBusy: Boolean
): Boolean = refreshDue && !stageReady && !stageRunning && !seasonalBusy

internal fun shouldEnrichStreamingOriginalsStage(
    homePresentationVisible: Boolean
): Boolean = !homePresentationVisible

internal fun shouldApplyStreamingOriginalsStage(
    homePresentationVisible: Boolean,
    stageReady: Boolean
): Boolean = !homePresentationVisible && stageReady
