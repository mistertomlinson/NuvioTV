package com.nuvio.tv.core.streams

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCodecFilterTest {
    private fun stream(name: String, filename: String? = null): Stream =
        Stream(
            name = name,
            title = null,
            description = null,
            url = "https://example.invalid/video.mkv",
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = StreamBehaviorHints(
                notWebReady = null,
                bingeGroup = null,
                countryWhitelist = null,
                proxyHeaders = null,
                filename = filename
            ),
            addonName = "Test",
            addonLogo = null
        )

    @Test fun defaultHidesVc1Only() {
        val hidden = VideoCodecFilter.DEFAULT_HIDDEN_CODEC_IDS
        assertEquals(setOf(VideoCodecOption.VC1.id), hidden)
        for (name in listOf("VC-1", "VC1", "VC_1", "VC 1", "WMV3", "WVC1", "WMV9")) {
            assertTrue(name, VideoCodecFilter.excludedText(name, hidden))
        }
        for (name in listOf("1080p H.264", "HEVC", "AV1", "VP9", "MPEG-2", "VC10")) {
            assertFalse(name, VideoCodecFilter.excludedText(name, hidden))
        }
    }

    @Test fun otherCodecsCanBeFilteredIndividually() {
        val hevc = setOf(VideoCodecOption.HEVC.id)
        assertTrue(VideoCodecFilter.excludedText("movie.1080p.x265.mkv", hevc))
        assertTrue(VideoCodecFilter.excludedText("movie.H.265.mkv", hevc))
        assertFalse(VideoCodecFilter.excludedText("movie.x264.mkv", hevc))
        assertTrue(VideoCodecFilter.excludedText("TV.WEB.DOVI.mkv", setOf(VideoCodecOption.DOLBY_VISION.id)))
        assertTrue(VideoCodecFilter.excludedText("movie.DiVX.avi", setOf(VideoCodecOption.MPEG4_PART2.id)))
    }

    @Test fun explicitEmptySelectionKeepsAllStreams() {
        val samples = listOf(stream("REMUX VC-1"), stream("WEB-DL H264"))
        assertEquals(samples, VideoCodecFilter.visibleStreams(samples, emptySet()))
    }

    @Test fun nameAndResolvedFilenameAreBothChecked() {
        val hidden = VideoCodecFilter.DEFAULT_HIDDEN_CODEC_IDS
        val opaquePack = stream("FHD TB Instant")
        val identifiedEpisode = stream(
            "FHD TB Instant",
            "The.Office.US.S06E04E05.Niagara.1080p.BluRay.REMUX.VC-1.DTS-HD.MA.5.1.mkv"
        )
        val safe = stream("WEB-DL AVC")
        assertFalse(VideoCodecFilter.excludedStream(opaquePack, hidden))
        assertTrue(VideoCodecFilter.excludedStream(identifiedEpisode, hidden))
        assertEquals(listOf(opaquePack, safe),
            VideoCodecFilter.visibleStreams(listOf(opaquePack, identifiedEpisode, safe), hidden))
    }
}
