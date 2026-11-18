package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.remote.dto.TorboxTorrentFileDto
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.StreamClientResolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridEpisodePlaybackRepairTest {
    private val target = DebridEpisodeSelectionContext(6, 8, "Double Date", listOf("Koi Pond", "Double Date", "Murder"))
    private val advertised = "The Office (US) (2005) - S06E08 - Koi Pond (1080p BluRay x265 Silence).mkv"
    private val actual = "The Office (US) (2005) - S06E09 - Double Date (1080p BluRay x265 Silence).mkv"

    @Test
    fun `trace candidate can be inspected before its advertised title is rejected`() {
        val source = stream(advertised)
        assertEquals(DebridEpisodeTitleMatch.CONFLICT, source.debridEpisodeTitleMatch(target))
        assertTrue(source.canAttemptDebridEpisodePlayback(target, willResolveFile = true))
        assertTrue(source.isDebridEpisodePlaybackCandidate(target))
        assertFalse(source.canAttemptDebridEpisodePlayback(target, willResolveFile = false))
    }

    @Test
    fun `trace pack finds and accepts the title confirmed file with alternate numbering`() {
        val source = stream(advertised)
        val resolve = resolve(advertised)
        val selected = TorboxFileSelector().selectFile(listOf(
            TorboxTorrentFileDto(id = 128, name = advertised, size = 2000),
            TorboxTorrentFileDto(id = 129, name = actual, size = 1000)
        ), resolve, 6, 8, target)!!
        assertEquals(129, selected.id)
        assertTrue(source.acceptsResolvedDebridEpisodeFile(selected.displayName(), target))
        val playable = source.copy(url = "https://cdn.example/selected-file", resolvedFilename = selected.displayName())
        assertEquals(DebridEpisodeTitleMatch.TARGET, playable.debridEpisodeTitleMatch(target))
        assertEquals(source.stableKey(), playable.stableKey())
    }

    @Test
    fun `an actual selected filename supersedes stale client resolve filenames`() {
        val source = stream(advertised).copy(clientResolve = resolve(advertised))
        val playable = source.copy(url = "https://cdn.example/selected-file", resolvedFilename = actual)
        assertEquals(DebridEpisodeTitleMatch.TARGET, playable.debridEpisodeTitleMatch(target))
        assertTrue(playable.isDebridEpisodePlaybackCandidate(target))
    }

    @Test
    fun `a conflicting original title cannot be repaired just by stripping the title`() {
        assertFalse(stream(advertised).acceptsResolvedDebridEpisodeFile("The.Office.S06E08.mkv", target))
        assertFalse(stream(advertised).acceptsResolvedDebridEpisodeFile(null, target))
    }

    @Test
    fun `direct URL to a known other episode is still rejected`() {
        val source = stream(advertised).copy(url = "https://cdn.example/koi-pond")
        assertFalse(source.isDebridEpisodePlaybackCandidate(target))
    }

    @Test
    fun `old wrong season nine failure stays rejected after repair is allowed`() {
        assertFalse(stream("The.Office.S06E08.mkv").acceptsResolvedDebridEpisodeFile("The.Office.S09E15.Extended.mkv", target))
    }

    @Test
    fun `a known other title in the actual file is still rejected`() {
        assertFalse(stream("The.Office.S06E08.mkv").acceptsResolvedDebridEpisodeFile(advertised, target))
    }

    @Test
    fun `a matching number without title keeps the existing ordinary playback policy`() {
        assertTrue(stream("The.Office.S06E08.mkv").acceptsResolvedDebridEpisodeFile("The.Office.S06E08.mkv", target))
    }

    private fun stream(filename: String) = Stream(
        name = "FHD TB Instant", title = "Office complete pack", description = null,
        url = null, ytId = null, infoHash = "torrent", fileIdx = 247, externalUrl = null,
        behaviorHints = StreamBehaviorHints(null, null, null, null, filename = filename),
        addonName = "Torrentio", addonLogo = null
    )

    private fun resolve(filename: String) = StreamClientResolve(
        type = "debrid", infoHash = "torrent", fileIdx = 247, magnetUri = null, sources = null,
        torrentName = "Office pack", filename = filename, mediaType = "series", mediaId = null,
        mediaOnlyId = null, title = null, season = 6, episode = 8, service = "torbox",
        serviceIndex = null, serviceExtension = null, isCached = true
    )
}
