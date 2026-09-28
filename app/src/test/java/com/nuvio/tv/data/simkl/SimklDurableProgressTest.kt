package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.WatchProgress
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SimklDurableProgressTest {
    @Test
    fun `completed movie dismisses stale playback and removes durable resume`() = runTest {
        val durableProgressStore = mockk<SimklDurableProgressStore>(relaxed = true)
        val progressDismissalStore = mockk<SimklProgressDismissalStore>(relaxed = true)
        val profileManager = mockk<ProfileManager>(relaxed = true)
        every { profileManager.activeProfileId } returns MutableStateFlow(1)

        val provider = SimklTrackingProgressProvider(
            profileManager = profileManager,
            syncRepository = mockk(relaxed = true),
            apiClient = mockk(relaxed = true),
            authStorage = mockk(relaxed = true),
            layoutPreferences = mockk(relaxed = true),
            durableProgressStore = durableProgressStore,
            progressDismissalStore = progressDismissalStore
        )
        val completed = progress(
            contentId = "tt0000099",
            percent = 100f,
            lastWatched = 500L
        )

        provider.persistDurableProgress(completed)

        coVerify(exactly = 1) {
            progressDismissalStore.dismiss(
                contentId = "tt0000099",
                season = null,
                episode = null,
                dismissedAtEpochMs = 500L
            )
        }
        coVerify(exactly = 1) {
            durableProgressStore.removeProgress(
                contentId = "tt0000099",
                season = null,
                episode = null
            )
        }
        coVerify(exactly = 0) {
            progressDismissalStore.clearForNewerProgress(any())
        }
        coVerify(exactly = 0) {
            durableProgressStore.persist(any())
        }
    }

    @Test
    fun `completed episode removes durable resume without dismissing next up`() = runTest {
        val durableProgressStore = mockk<SimklDurableProgressStore>(relaxed = true)
        val progressDismissalStore = mockk<SimklProgressDismissalStore>(relaxed = true)
        val profileManager = mockk<ProfileManager>(relaxed = true)
        every { profileManager.activeProfileId } returns MutableStateFlow(1)

        val provider = SimklTrackingProgressProvider(
            profileManager = profileManager,
            syncRepository = mockk(relaxed = true),
            apiClient = mockk(relaxed = true),
            authStorage = mockk(relaxed = true),
            layoutPreferences = mockk(relaxed = true),
            durableProgressStore = durableProgressStore,
            progressDismissalStore = progressDismissalStore
        )
        val completed = progress(
            contentId = "tt0000100",
            season = 1,
            episode = 4,
            percent = 100f,
            lastWatched = 600L
        )

        provider.persistDurableProgress(completed)

        coVerify(exactly = 0) {
            progressDismissalStore.dismiss(any(), any(), any(), any())
        }
        coVerify(exactly = 1) {
            durableProgressStore.removeProgress(
                contentId = "tt0000100",
                season = 1,
                episode = 4
            )
        }
        coVerify(exactly = 0) {
            progressDismissalStore.clearForNewerProgress(any())
        }
        coVerify(exactly = 0) {
            durableProgressStore.persist(any())
        }
    }

    @Test
    fun `unfinished progress clears older dismissal and remains durable`() = runTest {
        val durableProgressStore = mockk<SimklDurableProgressStore>(relaxed = true)
        val progressDismissalStore = mockk<SimklProgressDismissalStore>(relaxed = true)
        val profileManager = mockk<ProfileManager>(relaxed = true)
        every { profileManager.activeProfileId } returns MutableStateFlow(1)

        val provider = SimklTrackingProgressProvider(
            profileManager = profileManager,
            syncRepository = mockk(relaxed = true),
            apiClient = mockk(relaxed = true),
            authStorage = mockk(relaxed = true),
            layoutPreferences = mockk(relaxed = true),
            durableProgressStore = durableProgressStore,
            progressDismissalStore = progressDismissalStore
        )
        val unfinished = progress(
            contentId = "tt0000099",
            percent = 50f,
            lastWatched = 600L
        )

        provider.persistDurableProgress(unfinished)

        coVerify(exactly = 1) { progressDismissalStore.clearForNewerProgress(unfinished) }
        coVerify(exactly = 1) { durableProgressStore.persist(unfinished) }
        coVerify(exactly = 0) {
            progressDismissalStore.dismiss(any(), any(), any(), any())
        }
        coVerify(exactly = 0) {
            durableProgressStore.removeProgress(any(), any(), any())
        }
    }

    @Test
    fun `only unfinished progress past the start threshold is durable`() {
        assertFalse(shouldPersistSimklDurableProgress(progress(percent = 1f)))
        assertTrue(shouldPersistSimklDurableProgress(progress(percent = 50f)))
        assertFalse(shouldPersistSimklDurableProgress(progress(percent = 85f)))
        assertFalse(shouldPersistSimklDurableProgress(progress(percent = 100f)))
    }

    @Test
    fun `remote playback wins while missing remote playback uses durable copy`() {
        val remote = progress(
            contentId = "tt0000001",
            percent = 30f,
            lastWatched = 300L,
            source = WatchProgress.SOURCE_SIMKL_PLAYBACK
        )
        val duplicateDurable = progress(
            contentId = "tt0000001",
            percent = 60f,
            lastWatched = 600L,
            source = WatchProgress.SOURCE_SIMKL_DURABLE
        )
        val durableOnly = progress(
            contentId = "tt0000002",
            percent = 40f,
            lastWatched = 400L,
            source = WatchProgress.SOURCE_SIMKL_DURABLE
        )

        val result = mergeSimklProgressWithDurable(
            remoteEntries = listOf(remote),
            durableEntries = listOf(duplicateDurable, durableOnly),
            isWatched = { false }
        )

        assertEquals(2, result.size)
        assertSame(durableOnly, result[0])
        assertSame(remote, result[1])
    }

    @Test
    fun `remote show playback suppresses durable progress for another episode`() {
        val remote = progress(
            contentId = "tt0000005",
            season = 1,
            episode = 3,
            percent = 30f,
            source = WatchProgress.SOURCE_SIMKL_PLAYBACK
        )
        val olderDurableEpisode = progress(
            contentId = "tt0000005",
            season = 1,
            episode = 2,
            percent = 60f,
            lastWatched = 600L,
            source = WatchProgress.SOURCE_SIMKL_DURABLE
        )

        val result = mergeSimklProgressWithDurable(
            remoteEntries = listOf(remote),
            durableEntries = listOf(olderDurableEpisode),
            isWatched = { false }
        )

        assertEquals(1, result.size)
        assertSame(remote, result.single())
    }

    @Test
    fun `watched durable progress is suppressed`() {
        val durable = progress(
            contentId = "tt0000003",
            percent = 50f,
            source = WatchProgress.SOURCE_SIMKL_DURABLE
        )

        val result = mergeSimklProgressWithDurable(
            remoteEntries = emptyList(),
            durableEntries = listOf(durable),
            isWatched = { true }
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `remote episode progress replaces matching durable episode`() {
        val remote = progress(
            contentId = "tt0000004",
            season = 1,
            episode = 2,
            percent = 20f,
            source = WatchProgress.SOURCE_SIMKL_PLAYBACK
        )
        val durable = progress(
            contentId = "tt0000004",
            season = 1,
            episode = 2,
            percent = 70f,
            source = WatchProgress.SOURCE_SIMKL_DURABLE
        )

        val result = mergeSimklEpisodeProgressWithDurable(
            remoteEntries = mapOf((1 to 2) to remote),
            durableEntries = mapOf((1 to 2) to durable),
            isWatched = { false }
        )

        assertEquals(1, result.size)
        assertSame(remote, result.getValue(1 to 2))
    }

    private fun progress(
        contentId: String = "tt0000000",
        season: Int? = null,
        episode: Int? = null,
        percent: Float,
        lastWatched: Long = 100L,
        source: String = WatchProgress.SOURCE_LOCAL
    ): WatchProgress = WatchProgress(
        contentId = contentId,
        contentType = if (season == null) "movie" else "series",
        name = "Test",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = if (season == null) contentId else "$contentId:$season:$episode",
        season = season,
        episode = episode,
        episodeTitle = null,
        position = percent.toLong(),
        duration = 100L,
        lastWatched = lastWatched,
        progressPercent = percent,
        source = source
    )
}
