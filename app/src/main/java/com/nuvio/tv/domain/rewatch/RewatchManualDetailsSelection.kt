package com.nuvio.tv.domain.rewatch

/**
 * In-process one-shot proof of an explicit details-page episode click.
 * It does not persist, change account identity, or authorize an autoplay
 * transition. The player must still verify watched-history before moving.
 */
internal object RewatchManualDetailsSelection {
    private data class Target(
        val profileId: Int,
        val seriesKey: String,
        val episode: RewatchEpisode,
        val createdAtMs: Long
    )
    private var pending: Target? = null
    private const val MAX_AGE_MS = 120_000L

    @Synchronized
    fun arm(profileId: Int, series: String, episode: RewatchEpisode, nowMs: Long) {
        if (profileId <= 0 || series.isBlank() || nowMs < 0L) return
        pending = Target(profileId, series.trim().lowercase(), episode, nowMs)
    }

    @Synchronized
    fun consume(profileId: Int, series: String, episode: RewatchEpisode, nowMs: Long): Boolean {
        val target = pending ?: return false
        val age = nowMs - target.createdAtMs
        if (age !in 0L..MAX_AGE_MS) {
            pending = null
            return false
        }
        if (target.profileId != profileId ||
            target.seriesKey != series.trim().lowercase() ||
            target.episode != episode) return false
        pending = null
        return true
    }
}
