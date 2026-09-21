package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Stream
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
