package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.remote.dto.PremiumizeDirectDownloadFileDto
import com.nuvio.tv.data.remote.dto.RealDebridTorrentFileDto
import com.nuvio.tv.data.remote.dto.TorboxTorrentFileDto
import com.nuvio.tv.domain.model.StreamClientResolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class DebridEpisodeFileSelectionTest(private val provider: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun providers() = listOf(arrayOf("torbox"), arrayOf("realdebrid"), arrayOf("premiumize"))
    }

    private val office = DebridEpisodeSelectionContext(
        season = 6,
        episode = 8,
        title = "Double Date",
        seasonEpisodeTitles = listOf("Koi Pond", "Double Date", "Murder")
    )

    @Test
    fun `Office trace rejects lone S09E15 returned for requested S06E08`() {
        assertNull(select(
            files = listOf(File(2, "The Office US S09E15 Extended 1080p PCOK WEB-DL EAC3 H 264-NTb.mkv")),
            filename = "S06E08-Sleeping with the Enemy.avi",
            fileIdx = 91
        ))
    }

    @Test
    fun `wrong episode is rejected even when its file id matches the addon index`() {
        assertNull(select(listOf(File(91, "The.Office.S09E15.mkv")), fileIdx = 91))
    }

    @Test
    fun `exact addon filename cannot override the requested episode`() {
        val correct = "The.Office.S06E08.mkv"
        assertEquals(correct, select(
            listOf(File(1, "The.Office.S09E15.mkv"), File(2, correct)),
            filename = "The.Office.S09E15.mkv"
        ))
    }

    @Test
    fun `episode eight does not match episode eighty or eight hundred`() {
        val correct = "The.Office.S06E08.mkv"
        assertEquals(correct, select(listOf(
            File(1, "The.Office.S06E080.mkv", 3000),
            File(2, "The.Office.6x800.mkv", 2000),
            File(3, correct, 1000)
        )))
    }

    @Test
    fun `missing requested episode does not fall back to the largest unnumbered video`() {
        assertNull(select(listOf(File(1, "bonus-feature.mkv", 3000), File(2, "sample.mp4", 1000))))
    }

    @Test
    fun `episode arguments protect selection when title context is unavailable`() {
        assertNull(select(listOf(File(2, "The.Office.S09E15.mkv")), context = null))
    }

    @Test
    fun `episode number from resolve metadata also protects selection`() {
        assertNull(select(
            listOf(File(2, "The.Office.S09E15.mkv")), context = null,
            season = null, episode = null
        ))
    }

    @Test
    fun `matching title still supports an alternate episode numbering order`() {
        val correct = "The.Office.S06E09.Double.Date.mkv"
        assertEquals(correct, select(listOf(File(1, "The.Office.S06E08.Koi.Pond.mkv"), File(2, correct))))
    }

    @Test
    fun `known conflicting title is rejected despite matching episode number`() {
        assertNull(select(listOf(File(1, "The.Office.S06E08.Koi.Pond.mkv"))))
    }

    @Test
    fun `combined episode range includes the requested episode`() {
        val correct = "The.Office.S06E07-E08.mkv"
        assertEquals(correct, select(listOf(File(1, correct))))
    }

    @Test
    fun `combined episode suffix includes the requested episode`() {
        val correct = "The.Office.S06E07E08.mkv"
        assertEquals(correct, select(listOf(File(1, correct))))
    }

    @Test
    fun `x notation includes requested episode and respects numeric boundaries`() {
        val correct = "The.Office.6x8.mkv"
        assertEquals(correct, select(listOf(File(1, "The.Office.6x80.mkv"), File(2, correct))))
    }

    @Test
    fun `exact unnumbered filename remains sufficient evidence`() {
        val correct = "episode-file.mkv"
        assertEquals(correct, select(listOf(File(1, "bonus.mkv", 3000), File(2, correct, 1000)), filename = correct))
    }

    @Test
    fun `movie selection retains largest video fallback`() {
        assertEquals("movie.mkv", select(
            listOf(File(1, "sample.mp4", 1000), File(2, "movie.mkv", 3000)),
            context = null, season = null, episode = null, resolveSeason = null, resolveEpisode = null
        ))
    }

    @Test
    fun `Murder request rejects the translated Double Date file under conflicting numbering`() {
        assertNull(select(
            listOf(File(35, "The Office_S06E09_Hélène et Michael.mkv")),
            filename = "The Office_S06E09_Hélène et Michael.mkv", fileIdx = 35,
            context = DebridEpisodeSelectionContext(6, 9, "Murder", listOf("Double Date", "Murder"), requireTitleMatch = true),
            season = 6, episode = 9
        ))
    }

    @Test
    fun `known numbering conflict requires a title even on number only files`() {
        assertNull(select(
            listOf(File(35, "The.Office.S06E09.mkv")), filename = "The.Office.S06E09.mkv", fileIdx = 35,
            context = DebridEpisodeSelectionContext(6, 9, "Murder", listOf("Double Date", "Murder"), requireTitleMatch = true),
            season = 6, episode = 9
        ))
    }

    @Test
    fun `known numbering conflict selects the title confirmed Murder file inside a pack`() {
        val correct = "The.Office.S06E10.Murder.mkv"
        assertEquals(correct, select(
            listOf(File(35, "The.Office.S06E09.Double.Date.mkv"), File(36, correct)),
            context = DebridEpisodeSelectionContext(6, 9, "Murder", listOf("Double Date", "Murder"), requireTitleMatch = true),
            season = 6, episode = 9
        ))
    }

    private data class File(val id: Int, val name: String, val size: Long = 1000)

    private fun select(
        files: List<File>,
        filename: String? = null,
        fileIdx: Int? = null,
        context: DebridEpisodeSelectionContext? = office,
        season: Int? = 6,
        episode: Int? = 8,
        resolveSeason: Int? = 6,
        resolveEpisode: Int? = 8
    ): String? {
        val resolve = StreamClientResolve(
            type = "debrid", infoHash = "torrent", fileIdx = fileIdx, magnetUri = null,
            sources = null, torrentName = null, filename = filename, mediaType = "series",
            mediaId = null, mediaOnlyId = null, title = null,
            season = resolveSeason, episode = resolveEpisode, service = provider,
            serviceIndex = null, serviceExtension = null, isCached = true
        )
        return when (provider) {
            "torbox" -> TorboxFileSelector().selectFile(
                files.map { TorboxTorrentFileDto(id = it.id, name = it.name, size = it.size) },
                resolve, season, episode, context
            )?.displayName()
            "realdebrid" -> RealDebridFileSelector().selectFile(
                files.map { RealDebridTorrentFileDto(id = it.id, path = it.name, bytes = it.size) },
                resolve, season, episode, context
            )?.displayName()
            "premiumize" -> PremiumizeDirectDownloadFileSelector().selectFile(
                files.map { PremiumizeDirectDownloadFileDto(path = it.name, size = it.size) },
                resolve, season, episode, context
            )?.displayName()
            else -> error("Unexpected test provider")
        }
    }
}
