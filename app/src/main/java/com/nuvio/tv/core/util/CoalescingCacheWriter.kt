package com.nuvio.tv.core.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One writer per cache: coalesce bursts without cancelling an in-flight write.
 * A continuous stream still reaches disk at least once per maxWaitMillis
 * (plus the duration of the preceding write). Requests arriving during a write
 * stay pending and take a fresh snapshot on the next pass.
 */
internal class CoalescingCacheWriter(
    scope: CoroutineScope,
    quietMillis: Long = 2_000L,
    maxWaitMillis: Long = 30_000L,
    write: suspend () -> Unit
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        require(quietMillis > 0 && maxWaitMillis >= quietMillis)
        scope.launch {
            for (request in requests) {
                withTimeoutOrNull(maxWaitMillis) {
                    while (withTimeoutOrNull(quietMillis) {
                        requests.receive()
                        true
                    } == true) {
                        // Restart the quiet period, bounded by the outer timeout.
                    }
                }
                write()
            }
        }
    }

    fun requestSave() {
        requests.trySend(Unit)
    }
}
