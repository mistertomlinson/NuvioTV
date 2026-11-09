package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.rewatch.RewatchEpisode
import com.nuvio.tv.domain.rewatch.RewatchPhase
import com.nuvio.tv.domain.rewatch.RewatchSession
import com.nuvio.tv.domain.rewatch.completeRewatchEpisode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RewatchCompletionCatalogTest {
    private fun video(
        season: Int,
        episode: Int,
        released: String? = "2020-01-01"
    ) = Video(
        id = "episode:$season:$episode",
        title = "Episode $episode",
        released = released,
        thumbnail = null,
        season = season,
        episode = episode,
        overview = null
    )

    private fun session(season: Int, episode: Int) = RewatchSession(
        ownerKey = "wife",
        canonicalSeriesId = "imdb:show",
        sessionId = "replay-1",
        episode = RewatchEpisode(season, episode),
        positionMs = 120_000L,
        durationMs = 300_000L,
        lastCompletedEpisode = null,
        phase = RewatchPhase.PLAYING,
        updatedAtEpochMs = 1_000L
    )

    private val all = listOf(
        video(1, 1),
        video(1, 2),
        video(2, 1),
        video(2, 2)
    )

    @Test
    fun `known aired next episode works across season boundaries`() {
        val completed = RewatchEpisode(1, 2)
        val catalog = verifiedRewatchCompletionCatalog(
            all, completed, true, "Returning Series"
        )
        assertTrue(catalog.metadataComplete)
        assertEquals(
            listOf(completed, RewatchEpisode(2, 1)),
            catalog.orderedEpisodes
        )
        val progressed = completeRewatchEpisode(
            session(1, 2), completed, catalog.orderedEpisodes,
            catalog.metadataComplete, 2_000L
        )
        assertEquals(RewatchPhase.NEXT_UP, progressed.phase)
        assertEquals(RewatchEpisode(2, 1), progressed.episode)
    }

    @Test
    fun `verified ended series finale removes replay only at actual last episode`() {
        val completed = RewatchEpisode(2, 2)
        val catalog = verifiedRewatchCompletionCatalog(all, completed, true, "Ended")
        assertTrue(catalog.metadataComplete)
        assertEquals(all.size, catalog.orderedEpisodes?.size)
        val progressed = completeRewatchEpisode(
            session(2, 2), completed, catalog.orderedEpisodes,
            catalog.metadataComplete, 2_000L
        )
        assertEquals(RewatchPhase.FINISHED, progressed.phase)
    }

    @Test
    fun `unknown or active status cannot falsely finish the last known episode`() {
        for (status in listOf(null, "Returning Series", "In Production", "")) {
            val catalog = verifiedRewatchCompletionCatalog(
                all, RewatchEpisode(2, 2), true, status
            )
            assertFalse(catalog.metadataComplete)
            assertNull(catalog.orderedEpisodes)
        }
    }

    @Test
    fun `missing current episode or incomplete metadata remains pending`() {
        val missing = verifiedRewatchCompletionCatalog(
            all, RewatchEpisode(3, 1), true, "Ended"
        )
        assertFalse(missing.metadataComplete)
        val unresolved = verifiedRewatchCompletionCatalog(
            all, RewatchEpisode(2, 2), false, "Ended"
        )
        assertFalse(unresolved.metadataComplete)
    }

    @Test
    fun `duplicated episodes and interior gaps do not certify a finale`() {
        val duplicate = verifiedRewatchCompletionCatalog(
            all + video(2, 2), RewatchEpisode(2, 2), true, "Ended"
        )
        assertFalse(duplicate.metadataComplete)

        val missing = verifiedRewatchCompletionCatalog(
            listOf(video(1, 1), video(1, 3)),
            RewatchEpisode(1, 3), true, "Ended"
        )
        assertFalse(missing.metadataComplete)
    }

    @Test
    fun `future episode does not become rewatch next up`() {
        val catalog = verifiedRewatchCompletionCatalog(
            listOf(video(1, 1), video(1, 2, "2999-01-01")),
            RewatchEpisode(1, 1), true, "Returning Series"
        )
        assertFalse(catalog.metadataComplete)
        assertNull(catalog.orderedEpisodes)
    }
}
