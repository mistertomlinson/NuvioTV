package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerStreamMatchingTest {

    @Test
    fun `playing torrent remains matched when provider variants arrive`() {
        val other = stream(hash = "other", fileIdx = 0, name = "Other")
        val playingFirstProvider = stream(hash = "playing", fileIdx = 3, name = "Provider A")
        val playingSecondProvider = stream(hash = "playing", fileIdx = 3, name = "Provider B")

        assertEquals(
            1,
            findCurrentStreamIndex(
                streams = listOf(other, playingFirstProvider, playingSecondProvider),
                currentSourceStreamKey = null,
                currentStreamInfoHash = "PLAYING",
                currentStreamFileIdx = 3,
                currentStreamUrl = "https://temporary.example/video",
                currentStreamName = "Original display name",
                currentStreamAddonName = "Original addon",
                currentStreamDescription = null
            )
        )
    }

    @Test
    fun `file index distinguishes episodes in the same torrent`() {
        val episodeOne = stream(hash = "series-pack", fileIdx = 1, name = "Episode 1")
        val episodeTwo = stream(hash = "series-pack", fileIdx = 2, name = "Episode 2")

        assertEquals(
            1,
            findCurrentStreamIndex(
                streams = listOf(episodeOne, episodeTwo),
                currentSourceStreamKey = null,
                currentStreamInfoHash = "series-pack",
                currentStreamFileIdx = 2,
                currentStreamUrl = null,
                currentStreamName = null,
                currentStreamAddonName = null,
                currentStreamDescription = null
            )
        )
    }

    @Test
    fun `progressive result preserves addon selected after loading began`() {
        val aioStream = stream(hash = "aio", fileIdx = 0, name = "AIO result")
            .copy(addonName = "AIOStreams")
        val cometStream = stream(hash = "comet", fileIdx = 0, name = "Comet result")
            .copy(addonName = "Comet")

        val result = resolveStreamAddonFilter(
            allStreams = listOf(aioStream, cometStream),
            availableAddons = listOf("AIOStreams", "Comet"),
            selectedAddon = "AIOStreams"
        )

        assertEquals("AIOStreams", result.selectedAddon)
        assertEquals(listOf(aioStream), result.streams)
    }

    @Test
    fun `selected loading addon stays active while another addon finishes`() {
        val cometStream = stream(hash = "comet", fileIdx = 0, name = "Comet result")
            .copy(addonName = "Comet")

        val result = resolveStreamAddonFilter(
            allStreams = listOf(cometStream),
            availableAddons = listOf("AIOStreams", "Comet"),
            selectedAddon = "AIOStreams"
        )

        assertEquals("AIOStreams", result.selectedAddon)
        assertEquals(emptyList<Stream>(), result.streams)
    }

    @Test
    fun `selected source identity survives resolved HTTP handoff with no torrent hash`() {
        val source = stream("selected", 104, "FHD TB Instant")
        val other = stream("other", 104, "FHD TB Instant")
        assertEquals(1, match(listOf(other, source), key = source.stableKey(), fileIdx = 104))
    }

    @Test
    fun `exact selected provider takes priority over another provider with the same torrent file`() {
        val first = stream("same", 104, "Provider A")
        val selected = stream("same", 104, "Provider B")
        assertEquals(1, match(listOf(first, selected), key = selected.stableKey(), hash = "same", fileIdx = 104))
    }

    @Test
    fun `a generic label and the same file index cannot mark an unrelated stream Playing`() {
        assertEquals(-1, match(listOf(stream("wrong", 91, "FHD TB Instant")), fileIdx = 91))
    }

    @Test
    fun `matching release description cannot override a different torrent identity`() {
        val other = stream("wrong", 91, "FHD TB Instant").copy(description = "same release description")
        assertEquals(-1, match(listOf(other), hash = "actual", fileIdx = 91, description = "same release description"))
    }

    @Test
    fun `torrent hash alone cannot choose among several episodes in a pack`() {
        assertEquals(-1, match(listOf(stream("pack", 1, "One"), stream("pack", 2, "Two")), hash = "pack"))
    }

    @Test
    fun `missing indices on several torrent rows remain ambiguous`() {
        val one = stream("pack", 1, "One").copy(fileIdx = null)
        val two = stream("pack", 2, "Two").copy(fileIdx = null)
        assertEquals(-1, match(listOf(one, two), hash = "pack"))
    }

    @Test
    fun `exact direct URL still identifies the playing stream`() {
        val selected = stream("unused", 1, "FHD TB Instant").copy(infoHash = null, url = "https://cdn.example/current")
        assertEquals(1, match(listOf(stream("other", 1, "FHD TB Instant"), selected)))
    }

    @Test
    fun `Playing description replaces stale advertised episode with actual selected filename`() {
        val advertised = "The.Office.S06E08.Koi.Pond.mkv"
        val actual = "The.Office.S06E09.Double.Date.mkv"
        val source = stream("pack", 247, "FHD TB Instant").copy(
            title = "Office complete pack\n$advertised\n1080p",
            behaviorHints = StreamBehaviorHints(null, null, null, null, filename = advertised)
        )
        assertEquals("Office complete pack\n$actual\n1080p", playingSourceDescription(source, actual))
        assertEquals(source.title, playingSourceDescription(source, null))
    }

    private fun match(
        streams: List<Stream>, key: String? = null, hash: String? = null,
        fileIdx: Int? = null, description: String? = null
    ) = findCurrentStreamIndex(
        streams, key, hash, fileIdx, "https://cdn.example/current", "FHD TB Instant", "Addon", description
    )

    private fun stream(hash: String, fileIdx: Int, name: String) = Stream(
        name = name,
        title = null,
        description = null,
        url = null,
        ytId = null,
        infoHash = hash,
        fileIdx = fileIdx,
        externalUrl = null,
        behaviorHints = null,
        addonName = "Addon",
        addonLogo = null
    )
}
