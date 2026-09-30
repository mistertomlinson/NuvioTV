package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeNextUpResolutionTest {

    @Test
    fun `resolved metadata containing seed permits authoritative cleanup`() {
        val meta = metaWith(video(season = 1, episode = 2))

        assertTrue(hasResolvedNextUpSeed(meta, seedSeason = 1, seedEpisode = 2))
    }

    @Test
    fun `missing metadata remains inconclusive`() {
        assertFalse(hasResolvedNextUpSeed(null, seedSeason = 1, seedEpisode = 2))
    }

    @Test
    fun `empty episode metadata remains inconclusive`() {
        assertFalse(hasResolvedNextUpSeed(metaWith(), seedSeason = 1, seedEpisode = 2))
    }

    @Test
    fun `metadata missing completed seed remains inconclusive`() {
        val meta = metaWith(video(season = 1, episode = 3))

        assertFalse(hasResolvedNextUpSeed(meta, seedSeason = 1, seedEpisode = 2))
    }

    @Test
    fun `missing seed coordinates remain inconclusive`() {
        val meta = metaWith(video(season = 1, episode = 2))

        assertFalse(hasResolvedNextUpSeed(meta, seedSeason = null, seedEpisode = 2))
        assertFalse(hasResolvedNextUpSeed(meta, seedSeason = 1, seedEpisode = null))
        assertFalse(hasResolvedNextUpSeed(meta, seedSeason = 0, seedEpisode = 2))
    }


    @Test
    fun `completed next up seed overrides older in progress snapshot`() {
        val staleInProgress = progress(
            lastWatched = 100L,
            position = 25L,
            duration = 100L,
            progressPercent = 25f
        )
        val completedSeed = progress(
            lastWatched = 200L,
            position = 100L,
            duration = 100L,
            progressPercent = 100f
        )

        val completedAt = latestCompletedAtByContentForSuppression(
            allProgress = listOf(staleInProgress),
            nextUpSeeds = listOf(completedSeed),
            isCompletedSeed = { it.progressPercent == 100f }
        )

        assertFalse(
            shouldTreatAsActiveInProgressForNextUpSuppression(
                progress = staleInProgress,
                latestCompletedAt = completedAt["series-1"]
            )
        )
    }

    @Test
    fun `completed episode eight rejects stale episode five progress`() {
        val staleEpisodeFive = progress(
            lastWatched = 100L,
            position = 25L,
            duration = 100L,
            progressPercent = 25f,
            episode = 5
        )
        val completedEpisodeEight = progress(
            lastWatched = 200L,
            position = 100L,
            duration = 100L,
            progressPercent = 100f,
            episode = 8
        )

        val completedAt = latestCompletedAtByContentForSuppression(
            allProgress = listOf(staleEpisodeFive),
            nextUpSeeds = listOf(completedEpisodeEight),
            isCompletedSeed = { it.progressPercent == 100f }
        )

        assertFalse(
            shouldTreatAsActiveInProgressForNextUpSuppression(
                progress = staleEpisodeFive,
                latestCompletedAt = completedAt["series-1"]
            )
        )
    }

    @Test
    fun `newer in progress playback still suppresses older completed seed`() {
        val completedSeed = progress(
            lastWatched = 100L,
            position = 100L,
            duration = 100L,
            progressPercent = 100f
        )
        val newerInProgress = progress(
            lastWatched = 200L,
            position = 25L,
            duration = 100L,
            progressPercent = 25f
        )

        val completedAt = latestCompletedAtByContentForSuppression(
            allProgress = listOf(newerInProgress),
            nextUpSeeds = listOf(completedSeed),
            isCompletedSeed = { it.progressPercent == 100f }
        )

        assertTrue(
            shouldTreatAsActiveInProgressForNextUpSuppression(
                progress = newerInProgress,
                latestCompletedAt = completedAt["series-1"]
            )
        )
    }

    @Test
    fun `cached episode fields are rejected when next up advances`() {
        assertFalse(
            canReuseCachedNextUpEpisodeFields(
                cachedSeason = 4,
                cachedEpisode = 7,
                freshSeason = 4,
                freshEpisode = 8
            )
        )
    }

    @Test
    fun `cached episode fields remain reusable for same episode`() {
        assertTrue(
            canReuseCachedNextUpEpisodeFields(
                cachedSeason = 4,
                cachedEpisode = 8,
                freshSeason = 4,
                freshEpisode = 8
            )
        )
    }

    private fun progress(
        lastWatched: Long,
        position: Long,
        duration: Long,
        progressPercent: Float,
        episode: Int = 1
    ) = WatchProgress(
        contentId = "series-1",
        contentType = "series",
        name = "Series",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "series-1:1:1",
        season = 1,
        episode = episode,
        episodeTitle = "Episode $episode",
        position = position,
        duration = duration,
        lastWatched = lastWatched,
        progressPercent = progressPercent
    )

    private fun video(season: Int, episode: Int) = CwVideoSummary(
        id = "episode-$season-$episode",
        title = "Episode $episode",
        released = "2026-09-01",
        thumbnail = null,
        season = season,
        episode = episode,
        overview = null
    )

    private fun metaWith(vararg videos: CwVideoSummary) = CwMetaSummary(
        id = "series-1",
        name = "Series",
        poster = null,
        backdropUrl = null,
        logo = null,
        description = null,
        genres = emptyList(),
        releaseInfo = null,
        imdbRating = null,
        language = null,
        country = null,
        videos = videos.toList()
    )
}
