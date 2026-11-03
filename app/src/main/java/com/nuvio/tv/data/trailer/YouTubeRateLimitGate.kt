package com.nuvio.tv.data.trailer

/**
 * Stops YouTube extraction temporarily after HTTP 429 or a bot challenge. Each
 * consecutive strike doubles the pause up to 15 minutes; a successful extraction resets it.
 */
internal class YouTubeRateLimitGate(
    private val now: () -> Long = System::currentTimeMillis
) {
    private var strikes = 0
    private var blockedUntil = 0L

    @Synchronized
    fun isBlocked(): Boolean = now() < blockedUntil

    @Synchronized
    fun remainingMs(): Long = (blockedUntil - now()).coerceAtLeast(0L)

    @Synchronized
    fun onRateLimited(): Long {
        strikes++
        val cooldown = (BASE_COOLDOWN_MS shl (strikes - 1).coerceAtMost(MAX_DOUBLINGS))
            .coerceAtMost(MAX_COOLDOWN_MS)
        blockedUntil = now() + cooldown
        return cooldown
    }

    @Synchronized
    fun onSuccess() {
        strikes = 0
        blockedUntil = 0L
    }

    companion object {
        const val BASE_COOLDOWN_MS = 60_000L
        const val MAX_COOLDOWN_MS = 15 * 60_000L
        private const val MAX_DOUBLINGS = 4
    }
}
