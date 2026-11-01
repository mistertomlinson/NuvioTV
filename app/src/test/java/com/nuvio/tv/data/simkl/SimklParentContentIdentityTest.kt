package com.nuvio.tv.data.simkl

import com.nuvio.tv.data.repository.mergeProviderNextUpSeedsWithPlayerCompletions
import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class SimklParentContentIdentityTest {

    @Test
    fun `tmdb route id normalizes to simkl canonical imdb id`() {
        val snapshot = snapshotWithAliases()

        assertEquals(
            CONTENT_ID,
            normalizeSimklParentContentId(
                snapshot = snapshot,
                parentContentId = ROUTE_ID
            )
        )
    }

    @Test
    fun `unknown route id is preserved`() {
        assertEquals(
            "tmdb:111111",
            normalizeSimklParentContentId(
                snapshot = snapshotWithAliases(),
                parentContentId = "tmdb:111111"
            )
        )
    }

    @Test
    fun `canonicalized terminal player completion replaces stale provider seed`() {
        val snapshot = snapshotWithAliases()
        val normalizedContentId =
            normalizeSimklParentContentId(
                snapshot = snapshot,
                parentContentId = ROUTE_ID
            )

        val staleProviderSeed =
            progress(
                contentId = CONTENT_ID,
                episode = 1,
                lastWatched = 1_000L
            )
        val terminalPlayerCompletion =
            progress(
                contentId = normalizedContentId,
                episode = 10,
                lastWatched = 10_000L
            )

        val result =
            mergeProviderNextUpSeedsWithPlayerCompletions(
                providerSeeds = listOf(staleProviderSeed),
                playerCompletionSeeds = listOf(terminalPlayerCompletion),
                nowEpochMs = 10_100L,
                maxAgeMs = 180_000L
            )

        assertEquals(1, result.size)
        assertEquals(CONTENT_ID, result.single().contentId)
        assertEquals(10, result.single().episode)
    }

    private fun snapshotWithAliases() = SimklSyncSnapshot(
        entries = listOf(
            SimklLibraryEntry(
                mediaType = SimklMediaType.SHOWS,
                show = SimklMedia(
                    title = "Terminal Identity Test",
                    ids = mapOf(
                        "imdb" to JsonPrimitive(CONTENT_ID),
                        "tmdb" to JsonPrimitive(987654),
                        "simkl" to JsonPrimitive(123456)
                    )
                )
            )
        )
    )

    private fun progress(
        contentId: String,
        episode: Int,
        lastWatched: Long
    ) = WatchProgress(
        contentId = contentId,
        contentType = "series",
        name = "Terminal Identity Test",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "$contentId:6:$episode",
        season = 6,
        episode = episode,
        episodeTitle = "Episode $episode",
        position = 1L,
        duration = 1L,
        lastWatched = lastWatched,
        progressPercent = 100f,
        source = WatchProgress.SOURCE_LOCAL
    )

    private companion object {
        const val CONTENT_ID = "tt21267394"
        const val ROUTE_ID = "tmdb:987654"
    }
}
