package com.nuvio.tv.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseReminderIdentityTest {

    @Test
    fun imdbAliasMatchesSearchResult() {
        val reminder = ReleaseReminderRecord(
            itemId = "tmdb:123",
            itemType = "movie",
            title = "Test",
            imdbId = "tt1234567"
        )

        assertTrue(
            reminder.matchesContentIdentity(
                candidateId = "tt1234567",
                candidateType = "movie"
            )
        )
    }

    @Test
    fun tmdbAliasMatchesSearchResult() {
        val reminder = ReleaseReminderRecord(
            itemId = "tt1234567",
            itemType = "movie",
            title = "Test",
            tmdbId = 123
        )

        assertTrue(
            reminder.matchesContentIdentity(
                candidateId = "tmdb:123",
                candidateType = "movie"
            )
        )
    }

    @Test
    fun tvAndSeriesNormalizeToSameType() {
        val reminder = ReleaseReminderRecord(
            itemId = "tt7654321",
            itemType = "series",
            title = "Test"
        )

        assertTrue(
            reminder.matchesContentIdentity(
                candidateId = "tt7654321",
                candidateType = "tv"
            )
        )
    }

    @Test
    fun differentContentTypesDoNotMatch() {
        val reminder = ReleaseReminderRecord(
            itemId = "tt7654321",
            itemType = "series",
            title = "Test"
        )

        assertFalse(
            reminder.matchesContentIdentity(
                candidateId = "tt7654321",
                candidateType = "movie"
            )
        )
    }
}
