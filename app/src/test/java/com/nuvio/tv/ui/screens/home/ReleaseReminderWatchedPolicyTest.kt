package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.data.local.ReleaseReminderBadge
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseReminderWatchedPolicyTest {

    @Test
    fun seasonTwoReminder_isProtectedFromFullyWatchedSeriesState() {
        assertTrue(
            shouldProtectReleaseReminderFromWatchedInvalidation(
                itemType = "series",
                seasonNumber = 2,
                badge = ReleaseReminderBadge.NEW_SEASON
            )
        )
    }

    @Test
    fun laterSeasonReminder_isProtectedEvenWithGenericBadge() {
        assertTrue(
            shouldProtectReleaseReminderFromWatchedInvalidation(
                itemType = "tv",
                seasonNumber = 5,
                badge = ReleaseReminderBadge.AVAILABLE_NOW
            )
        )
    }

    @Test
    fun newSeasonBadgeProtectsReminderWhenSeasonNumberIsMissing() {
        assertTrue(
            shouldProtectReleaseReminderFromWatchedInvalidation(
                itemType = "series",
                seasonNumber = null,
                badge = ReleaseReminderBadge.NEW_SEASON
            )
        )
    }

    @Test
    fun ordinarySeriesReminder_isNotFutureSeasonProtected() {
        assertFalse(
            shouldProtectReleaseReminderFromWatchedInvalidation(
                itemType = "series",
                seasonNumber = 1,
                badge = ReleaseReminderBadge.AVAILABLE_NOW
            )
        )
    }

    @Test
    fun movieCannotBecomeFutureSeasonProtected() {
        assertFalse(
            shouldProtectReleaseReminderFromWatchedInvalidation(
                itemType = "movie",
                seasonNumber = 4,
                badge = ReleaseReminderBadge.NEW_SEASON
            )
        )
    }
}
