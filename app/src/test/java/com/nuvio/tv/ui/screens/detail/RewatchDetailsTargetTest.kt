package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.data.local.ProfileRewatchSessions
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.rewatch.RewatchEpisode
import com.nuvio.tv.domain.rewatch.RewatchPhase
import com.nuvio.tv.domain.rewatch.RewatchSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RewatchDetailsTargetTest {
    private val office = "tt0386676"
    private val profile = 2
    private val replay = RewatchSession(
        ownerKey = "local-profile:2",
        canonicalSeriesId = office,
        sessionId = "ios-replay-test",
        episode = RewatchEpisode(5, 16),
        positionMs = 120000L,
        durationMs = 1300000L,
        lastCompletedEpisode = null,
        phase = RewatchPhase.PLAYING,
        updatedAtEpochMs = 1000L
    )
    private fun video(number: Int, available: Boolean? = true) = Video(
        id = "office:5:$number",
        title = "Episode $number",
        released = "2026-01-01",
        thumbnail = null,
        season = 5,
        episode = number,
        overview = null,
        available = available
    )
    private val videos = listOf(video(16), video(22), video(26))
    private fun snapshot(vararg sessions: RewatchSession, profileId: Int = profile) =
        ProfileRewatchSessions(profileId, sessions.toList())

    private fun choose(
        local: ProfileRewatchSessions? = snapshot(replay),
        ids: Set<String> = setOf(office),
        list: List<Video> = videos,
        activeProfileId: Int = profile
    ) = resolveRewatchDetailsTarget(activeProfileId, local, ids, list)

    @Test
    fun manuallySelectedS5E16WinsOverOldS5E22ProviderProgress() {
        val target = choose()
        assertEquals(16, target?.video?.episode)
        assertEquals("office:5:16", target?.video?.id)
        assertEquals(RewatchPhase.PLAYING, target?.session?.phase)
        assertEquals(120000L, target?.session?.positionMs)
    }

    @Test
    fun nextUpAfterS5E25TargetsS5E26NotOriginalProviderResume() {
        val next = replay.copy(
            episode = RewatchEpisode(5, 26),
            phase = RewatchPhase.NEXT_UP,
            positionMs = 0L,
            durationMs = 0L,
            lastCompletedEpisode = RewatchEpisode(5, 25)
        )
        val target = choose(local = snapshot(next))
        assertEquals("office:5:26", target?.video?.id)
        assertEquals(RewatchPhase.NEXT_UP, target?.session?.phase)
    }

    @Test
    fun imdbPrefixAliasesAreMatchedOnlyWhenTheyIdentifySameShow() {
        assertEquals(16, choose(ids = setOf("imdb:tt0386676"))?.video?.episode)
        val prefixed = replay.copy(canonicalSeriesId = "imdb:tt0386676")
        assertEquals(16, choose(local = snapshot(prefixed))?.video?.episode)
        assertNull(choose(ids = setOf("tt0000000")))
        assertNull(choose(ids = setOf("imdb:tt0000000")))
    }

    @Test
    fun profileSwitchAndForeignOwnerFailClosed() {
        assertNull(choose(activeProfileId = 3))
        assertNull(choose(local = snapshot(replay, profileId = 3)))
        assertNull(choose(local = snapshot(replay.copy(ownerKey = "local-profile:3"))))
        assertNull(choose(local = null))
    }

    @Test
    fun duplicateOrMissingEpisodeMetadataFallsBackToNormalDetails() {
        assertNull(choose(list = listOf(video(22), video(26))))
        assertNull(choose(list = listOf(video(16), video(16))))
        assertNull(choose(list = listOf(video(16, available = false), video(22))))
        assertNull(choose(list = listOf(video(16).copy(id = ""), video(22))))
    }

    @Test
    fun finishedAndUnresolvedReplayNeverHijackDetailsHero() {
        assertNull(choose(local = snapshot(replay.copy(phase = RewatchPhase.FINISHED))))
        assertNull(choose(local = snapshot(replay.copy(phase = RewatchPhase.AWAITING_METADATA))))
        assertNull(choose(local = snapshot(replay.copy(
            phase = RewatchPhase.NEXT_UP, positionMs = 500L
        ))))
        assertNull(choose(local = snapshot(replay, replay.copy(
            sessionId = "duplicate-local-cursor"
        ))))
    }

    @Test
    fun unrelatedActiveReplayDoesNotChangeThisShowsDetails() {
        val other = replay.copy(canonicalSeriesId = "tt9999999")
        assertNull(choose(local = snapshot(other)))
        assertEquals(16, choose(local = snapshot(other, replay))?.video?.episode)
    }
}
