package com.nuvio.tv.data.simkl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimklReminderMembershipTest {
    private val planToWatch = simklLibraryStatusDefinitions.single {
        it.status == SimklListStatus.PLAN_TO_WATCH
    }.key
    private val watching = simklLibraryStatusDefinitions.single {
        it.status == SimklListStatus.WATCHING
    }.key

    @Test
    fun repeatedFulfillmentNeverTogglesPlanToWatchOff() {
        val original = mapOf(
            planToWatch to true,
            watching to false
        )

        val first = ensuredSimklPlanToWatchMembership(original)
        val second = ensuredSimklPlanToWatchMembership(first)

        assertEquals(original, first)
        assertEquals(first, second)
        assertTrue(second[planToWatch] == true)
    }

    @Test
    fun existingWatchingStatusIsReplacedByPlanToWatch() {
        val before = mapOf(
            watching to true,
            planToWatch to false
        )

        val desired = ensuredSimklPlanToWatchMembership(before)

        assertFalse(desired[watching] == true)
        assertTrue(desired[planToWatch] == true)
        assertEquals(desired, ensuredSimklPlanToWatchMembership(desired))
    }

    @Test
    fun missingMembershipAlwaysSelectsPlanToWatch() {
        val desired = ensuredSimklPlanToWatchMembership(emptyMap())
        assertTrue(desired[planToWatch] == true)
    }
}
