package com.nuvio.tv.core.debrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridEpisodeTitleSelectionTest {

    private val electricDreams = DebridEpisodeSelectionContext(
        season = 1,
        episode = 8,
        title = "Autofac",
        seasonEpisodeTitles = listOf(
            "The Hood Maker",
            "Impossible Planet",
            "The Commuter",
            "Crazy Diamond",
            "Real Life",
            "Human Is",
            "The Father Thing",
            "Autofac",
            "Safe and Sound",
            "Kill All Others"
        )
    )

    @Test
    fun `alternate order target title is recognized even with different episode number`() {
        assertEquals(
            DebridEpisodeTitleMatch.TARGET,
            classifyDebridEpisodeFileName(
                "Philip.K.Dicks.Electric.Dreams.S01E02.Autofac.1080p.WEB-DL.mkv",
                electricDreams
            )
        )
    }

    @Test
    fun `same episode number with another known title is a conflict`() {
        assertEquals(
            DebridEpisodeTitleMatch.CONFLICT,
            classifyDebridEpisodeFileName(
                "Philip.K.Dicks.Electric.Dreams.S01E08.Impossible.Planet.1080p.WEB-DL.mkv",
                electricDreams
            )
        )
    }

    @Test
    fun `number only filename remains unknown and is not rejected`() {
        assertEquals(
            DebridEpisodeTitleMatch.UNKNOWN,
            classifyDebridEpisodeFileName(
                "Philip.K.Dicks.Electric.Dreams.S01E08.2160p.WEB-DL.mkv",
                electricDreams
            )
        )
    }

    @Test
    fun `target title is preferred and conflicting title is excluded`() {
        val files = listOf(
            "S01E08.Impossible.Planet.mkv",
            "S01E08.2160p.WEB-DL.mkv",
            "S01E02.Autofac.mkv"
        )

        val target = files.firstDebridTargetEpisodeTitleMatch(electricDreams) { it }
        assertEquals("S01E02.Autofac.mkv", target)

        val safe = files.withoutDebridEpisodeTitleConflicts(electricDreams) { it }
        assertTrue("S01E02.Autofac.mkv" in safe)
        assertTrue("S01E08.2160p.WEB-DL.mkv" in safe)
        assertFalse("S01E08.Impossible.Planet.mkv" in safe)
    }

    @Test
    fun `ampersand metadata matches and release naming`() {
        val context = DebridEpisodeSelectionContext(
            season = 1,
            episode = 6,
            title = "Safe & Sound",
            seasonEpisodeTitles = listOf("Safe & Sound", "Autofac")
        )

        assertEquals(
            DebridEpisodeTitleMatch.TARGET,
            classifyDebridEpisodeFileName(
                "Electric.Dreams.S01E06.Safe.and.Sound.1080p.mkv",
                context
            )
        )
    }

    @Test
    fun `different known title wins over incidental target occurrence`() {
        val context = DebridEpisodeSelectionContext(
            season = 1,
            episode = 8,
            title = "Autofac",
            seasonEpisodeTitles = listOf("Autofac", "Impossible Planet")
        )

        assertEquals(
            DebridEpisodeTitleMatch.CONFLICT,
            classifyDebridEpisodeFileName(
                "Autofac.S01E08.Impossible.Planet.1080p.mkv",
                context
            )
        )
    }

}
