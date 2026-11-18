package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.*
import org.junit.Test

class DebridEpisodeNumberingConflictTest {
    private val murder = DebridEpisodeSelectionContext(6, 9, "Murder", listOf("Koi Pond", "Double Date", "Murder"))
    private val french = source("The Office_S06E09_Hélène et Michael.mkv")
    private val alternateOrder = source("S06E09 Double Date (Extended Cut).mkv")

    @Test
    fun `trace detects a known other title at the requested episode number`() {
        val evidence = murder.withDebridEpisodeNumberingEvidence(listOf(french, alternateOrder))
        assertTrue(evidence.requireTitleMatch)
        assertFalse(french.acceptsResolvedDebridEpisodeFile(french.behaviorHints?.filename, evidence))
    }

    @Test
    fun `translated file with matching number is rejected when numbering is ambiguous`() {
        val evidence = murder.withDebridEpisodeNumberingEvidence(listOf(alternateOrder))
        assertTrue(french.isDebridEpisodePlaybackCandidate(evidence))
        assertFalse(french.copy(url = "https://cdn.example/foreign-file").isDebridEpisodePlaybackCandidate(evidence))
        assertFalse(french.acceptsResolvedDebridEpisodeFile("The Office_S06E09_Hélène et Michael.mkv", evidence))
    }

    @Test
    fun `a file explicitly titled Murder can resolve from an alternate episode number`() {
        val evidence = murder.withDebridEpisodeNumberingEvidence(listOf(french, alternateOrder))
        assertTrue(alternateOrder.acceptsResolvedDebridEpisodeFile("The.Office.S06E10.Murder.mkv", evidence))
    }

    @Test
    fun `number only files cannot settle an observed numbering conflict`() {
        val evidence = murder.withDebridEpisodeNumberingEvidence(listOf(alternateOrder))
        assertFalse(source("The.Office.S06E09.mkv").acceptsResolvedDebridEpisodeFile("The.Office.S06E09.mkv", evidence))
    }

    @Test
    fun `ordinary numeric matching remains available without contradictory title evidence`() {
        val evidence = murder.withDebridEpisodeNumberingEvidence(listOf(source("The.Office.S06E09.mkv"), french))
        assertFalse(evidence.requireTitleMatch)
        assertTrue(french.acceptsResolvedDebridEpisodeFile(french.behaviorHints?.filename, evidence))
    }

    @Test
    fun `wrong season alone does not establish an alternate numbering scheme`() {
        assertFalse(murder.withDebridEpisodeNumberingEvidence(listOf(source("The.Office.S09E15.Double.Date.mkv"))).requireTitleMatch)
    }

    @Test
    fun `ambiguity stays latched when a later partial emission omits the conflicting row`() {
        val evidence = murder.withDebridEpisodeNumberingEvidence(listOf(alternateOrder))
        assertTrue(evidence.withDebridEpisodeNumberingEvidence(listOf(french)).requireTitleMatch)
    }

    @Test
    fun `strong and numeric only resolver caches have different identities`() {
        assertNotEquals(murder.cacheKeyPart(), murder.copy(requireTitleMatch = true).cacheKeyPart())
    }

    @Test
    fun `requiring a title is preserved only for the same target episode`() {
        val evidence = murder.copy(requireTitleMatch = true)
        assertTrue(evidence.withDebridEpisodeTarget(6, 9).requireTitleMatch)
        assertFalse(evidence.withDebridEpisodeTarget(6, 10).requireTitleMatch)
    }

    @Test
    fun `repairable named packs rank ahead of unverified files after conflicting numbering is observed`() {
        val evidence = murder.withDebridEpisodeNumberingEvidence(listOf(alternateOrder))
        val confirmed = source("The.Office.S06E10.Murder.mkv")
        assertEquals(listOf(confirmed, alternateOrder, french), listOf(french, alternateOrder, confirmed).orderByDebridEpisodeTitleEvidence(evidence))
    }

    @Test
    fun `weak generic target titles do not enable a confirmation rule they cannot satisfy`() {
        val pilot = DebridEpisodeSelectionContext(1, 1, "Pilot", listOf("Pilot", "Second Story"))
        assertFalse(pilot.withDebridEpisodeNumberingEvidence(listOf(source("Show.S01E01.Second.Story.mkv"))).requireTitleMatch)
    }

    private fun source(filename: String) = Stream(
        name = "FHD TB Instant", title = null, description = null, url = null, ytId = null,
        infoHash = filename, fileIdx = 108, externalUrl = null,
        behaviorHints = StreamBehaviorHints(null, null, null, null, filename = filename),
        addonName = "Comet", addonLogo = null
    )
}
