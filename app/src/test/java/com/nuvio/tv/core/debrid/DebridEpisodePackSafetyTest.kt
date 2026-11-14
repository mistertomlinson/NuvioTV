package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.remote.dto.PremiumizeDirectDownloadFileDto
import com.nuvio.tv.data.remote.dto.RealDebridTorrentFileDto
import com.nuvio.tv.data.remote.dto.TorboxTorrentFileDto
import com.nuvio.tv.domain.model.StreamClientResolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebridEpisodePackSafetyTest {
    @Test
    fun `short x episode number never matches episodes ten through nineteen`() {
        val names = listOf(
            "The.Office.2x10.mkv",
            "The.Office.2x11.mkv",
            "The.Office.2x01.mkv"
        )
        // The wrong files are deliberately larger and earlier in the list.
        assertSelectedForAllProviders("The.Office.2x01.mkv", names, 2, 1)
        assertEquals(
            DebridEpisodeTitleMatch.CONFLICT,
            classifyDebridEpisodeNumber("The.Office.2x10.mkv", 2, 1)
        )
        assertEquals(
            DebridEpisodeTitleMatch.TARGET,
            classifyDebridEpisodeNumber("The.Office.2x01.mkv", 2, 1)
        )
    }

    @Test
    fun `padded S episode numbers do not confuse E01 and E010`() {
        val names = listOf("Office.S02E010.mkv", "Office.S02E01.mkv")
        assertSelectedForAllProviders("Office.S02E01.mkv", names, 2, 1)
    }

    @Test
    fun `pack directory episode numbers do not override the actual file number`() {
        val names = listOf("Office.S02E01.Pack/Office.S02E10.mkv")
        assertSelectedForAllProviders(null, names, 2, 1)
    }

    @Test
    fun `missing requested episode is never replaced by largest pack file`() {
        val names = listOf("Office.S02E10.mkv", "Office.S02E11.mkv")
        assertSelectedForAllProviders(null, names, 2, 1)
    }

    @Test
    fun `unlabeled multi video pack without index refuses arbitrary selection`() {
        val names = listOf("Disk.One.mkv", "Disk.Two.mkv", "Disk.Three.mkv")
        assertSelectedForAllProviders(null, names, 2, 1)
    }

    @Test
    fun `single unlabeled video remains playable`() {
        assertSelectedForAllProviders("Single.Movie.or.Episode.mkv",
            listOf("Single.Movie.or.Episode.mkv"), 2, 1)
    }

    @Test
    fun `movie fallback still selects largest video`() {
        assertSelectedForAllProviders(
            "Movie.Larger.mkv",
            listOf("Movie.Smaller.mkv", "Movie.Larger.mkv"),
            season = null,
            episode = null
        )
    }

    @Test
    fun `episode title evidence is retained without season numbers in filenames`() {
        val context = DebridEpisodeSelectionContext(
            season = 2, episode = 12, title = "The Injury",
            seasonEpisodeTitles = listOf("The Fight", "The Injury")
        )
        assertSelectedForAllProviders(
            "The.Office.The.Injury.mkv",
            listOf("The.Office.The.Fight.mkv", "The.Office.The.Injury.mkv"),
            2, 12, context = context
        )
    }

    @Test
    fun `known wrong number defeats apparent title match`() {
        val context = DebridEpisodeSelectionContext(
            season = 2, episode = 1, title = "The Injury",
            seasonEpisodeTitles = listOf("The Injury")
        )
        assertEquals(
            DebridEpisodeTitleMatch.CONFLICT,
            classifyDebridEpisodeFileName("The.Office.S02E10.The.Injury.mkv", context)
        )
        assertSelectedForAllProviders(
            null, listOf("The.Office.S02E10.The.Injury.mkv", "The.Office.S02E11.mkv"),
            2, 1, context = context
        )
    }

    @Test
    fun `explicit wrong episode vetoes an otherwise valid file index`() {
        val names = listOf("README.txt", "Office.S02E10.mkv", "Office.S02E11.mkv")
        assertSelectedForAllProviders(null, names, 2, 1, fileIdx = 1)
    }

    @Test
    fun `source file index refers to full torrent listing not debrid service file ID`() {
        val names = listOf("README.txt", "unlabeled.A.mkv", "unlabeled.B.mkv")
        val resolve = resolve(fileIdx = 1)
        val realFiles = real(names)
        val torboxFiles = torbox(names)
        assertEquals("unlabeled.A.mkv",
            RealDebridFileSelector().selectFile(realFiles, resolve, 2, 1)?.displayName())
        assertEquals("unlabeled.A.mkv",
            TorboxFileSelector().selectFile(torboxFiles, resolve, 2, 1)?.displayName())
    }

    @Test
    fun `source index for an attachment is never treated as a video file`() {
        val names = listOf("README.txt", "unlabeled.A.mkv", "unlabeled.B.mkv")
        val resolve = resolve(fileIdx = 0)
        assertNull(RealDebridFileSelector().selectFile(real(names), resolve, 2, 1))
        assertNull(TorboxFileSelector().selectFile(torbox(names), resolve, 2, 1))
    }

    private fun assertSelectedForAllProviders(
        expected: String?,
        names: List<String>,
        season: Int?,
        episode: Int?,
        fileIdx: Int? = null,
        context: DebridEpisodeSelectionContext? = null
    ) {
        val metadata = resolve(fileIdx = fileIdx)
        assertEquals("Real-Debrid: $names", expected,
            RealDebridFileSelector().selectFile(real(names), metadata, season, episode, context)?.displayName())
        assertEquals("TorBox: $names", expected,
            TorboxFileSelector().selectFile(torbox(names), metadata, season, episode, context)?.displayName())
        assertEquals("Premiumize: $names", expected,
            PremiumizeDirectDownloadFileSelector().selectFile(premiumize(names), metadata, season, episode, context)?.displayName())
    }

    private fun real(names: List<String>) = names.mapIndexed { index, name ->
        RealDebridTorrentFileDto(
            id = 100 + index * 7,
            path = "Release/$name",
            bytes = 100000L - index * 1000L
        )
    }

    private fun torbox(names: List<String>) = names.mapIndexed { index, name ->
        TorboxTorrentFileDto(
            id = 100 + index * 7,
            name = "Release/$name",
            size = 100000L - index * 1000L
        )
    }

    private fun premiumize(names: List<String>) = names.mapIndexed { index, name ->
        PremiumizeDirectDownloadFileDto(
            path = "Release/$name",
            link = "https://example.invalid/file/$index",
            size = 100000L - index * 1000L
        )
    }

    private fun resolve(fileIdx: Int? = null) = StreamClientResolve(
        type = "debrid",
        infoHash = null,
        fileIdx = fileIdx,
        magnetUri = null,
        sources = null,
        torrentName = null,
        filename = null,
        mediaType = null,
        mediaId = null,
        mediaOnlyId = null,
        title = null,
        season = null,
        episode = null,
        service = null,
        serviceIndex = null,
        serviceExtension = null,
        isCached = null
    )
}
