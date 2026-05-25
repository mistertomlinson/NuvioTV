package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.nuvio.tv.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MAX_STARTUP_AUTO_RETRIES = 2
private const val MAX_AUTO_RETRIES = 1
private const val MAX_AUTO_ADVANCE_STREAMS = 3
private const val RETRY_DELAY_MS = 1_500L
private const val STABLE_PROGRESS_RESET_DELAY_MS = 5_000L

internal fun PlayerRuntimeController.showRecoveryOverlay() {
    _uiState.update { state ->
        state.copy(
            error = null,
            isBuffering = true,
            showLoadingOverlay = true,
            loadingMessage = context.getString(R.string.player_loading_buffering),
            showPauseOverlay = false
        )
    }
}

internal fun PlayerRuntimeController.attemptStartupRecovery(
    error: PlaybackException,
    detailedError: String
): Boolean {
    if (hasRenderedFirstFrame) return false
    if (!isRetryablePlaybackError(error)) return false
    if (startupRetryCount >= MAX_STARTUP_AUTO_RETRIES) return false

    val paused = userPausedManually
    val attempt = startupRetryCount
    startupRetryCount++

    Log.w(
        PlayerRuntimeController.TAG,
        "Startup recovery ${attempt + 1}/$MAX_STARTUP_AUTO_RETRIES after ${RETRY_DELAY_MS}ms for: $detailedError"
    )

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        _uiState.update {
            it.copy(
                error = null,
                isBuffering = true,
                showLoadingOverlay = it.loadingOverlayEnabled,
                loadingMessage = context.getString(R.string.player_loading_buffering),
                showPauseOverlay = false
            )
        }
        delay(RETRY_DELAY_MS)
        releasePlayer(flushPlaybackState = false)
        initializePlayer(currentStreamUrl, currentHeaders)
    }
    return true
}

internal fun isRetryablePlaybackError(error: PlaybackException): Boolean {
    return when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true

        PlaybackException.ERROR_CODE_UNSPECIFIED -> {
            val cause = error.cause
            cause is IllegalStateException || cause is NullPointerException
        }

        else -> false
    }
}

internal fun PlaybackException.findInvalidResponseCodeException(): HttpDataSource.InvalidResponseCodeException? {
    var current: Throwable? = cause
    while (current != null) {
        if (current is HttpDataSource.InvalidResponseCodeException) return current
        current = current.cause
    }
    return null
}

internal fun PlaybackException.toDisplayMessage(context: android.content.Context): String {
    val responseException = findInvalidResponseCodeException()
    if (responseException != null) {
        val code = responseException.responseCode
        val statusText = responseException.responseMessage?.takeIf { it.isNotBlank() }
        val providerHint = when (code) {
            403 -> context.getString(R.string.player_error_stream_blocked)
            404 -> context.getString(R.string.player_error_stream_removed)
            410 -> context.getString(R.string.player_error_stream_expired)
            429 -> context.getString(R.string.player_error_stream_rate_limited)
            500, 502, 503 -> context.getString(R.string.player_error_stream_unavailable)
            else -> ""
        }
        return buildString {
            append("HTTP $code")
            statusText?.let { append(" $it") }
            append(" [$errorCodeName]")
            append(providerHint)
        }
    }

    val isRendererError = errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
    if (isRendererError) {
        val meaningfulMessage = findMostRelevantCauseMessage()
        val decoderHeader = meaningfulMessage ?: context.getString(R.string.player_error_decoder)
        val unsupported = context.getString(R.string.player_error_unsupported_format, errorCodeName)
        return "$decoderHeader\n\n$unsupported"
    }

    val meaningfulMessage = findMostRelevantCauseMessage()
    return if (meaningfulMessage != null) {
        "$meaningfulMessage [$errorCodeName]"
    } else {
        errorCodeName
    }
}

internal fun Throwable.toDisplayMessage(context: android.content.Context, fallback: String? = null): String {
    val meaningfulMessage = findMostRelevantCauseMessage()
    return meaningfulMessage
        ?: message?.takeIf { it.isNotBlank() }
        ?: fallback
        ?: context.getString(R.string.player_error_playback_fallback)
}

private fun Throwable.findMostRelevantCauseMessage(): String? {
    val candidates = buildList {
        var current: Throwable? = this@findMostRelevantCauseMessage
        while (current != null) {
            current.message
                ?.trim()
                ?.takeIf {
                    it.isNotBlank() &&
                        !it.equals("Playback error", ignoreCase = true) &&
                        !it.equals("Source error", ignoreCase = true) &&
                        !it.equals("Unexpected runtime error", ignoreCase = true)
                }
                ?.let(::add)
            current = current.cause
        }
    }
    return candidates.firstOrNull()
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.attemptAutoRetry(
    error: PlaybackException,
    detailedError: String
): Boolean {
    if (!isRetryablePlaybackError(error)) return false
    if (errorRetryCount >= MAX_AUTO_RETRIES) return false
    // Don't retry debrid/download streams — they need special handling
    if (isCurrentStreamDebridOrDownload()) return false

    val paused = userPausedManually
    val attempt = errorRetryCount
    errorRetryCount++

    Log.w(
        PlayerRuntimeController.TAG,
        "Auto-retry ${attempt + 1}/$MAX_AUTO_RETRIES after ${RETRY_DELAY_MS}ms for: $detailedError"
    )
    android.util.Log.d("PlayerRecovery", "Auto-retry attempt ${attempt + 1}/$MAX_AUTO_RETRIES for stream: $currentStreamUrl")

    val savedPosition = _exoPlayer?.currentPosition?.takeIf { it > 0L } ?: 0L
    val isFirstAttempt = attempt == 0

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        _uiState.update {
            it.copy(
                error = null,
                showLoadingOverlay = it.loadingOverlayEnabled,
                loadingMessage = context.getString(R.string.player_loading_buffering),
                showPauseOverlay = false
            )
        }
        delay(RETRY_DELAY_MS)

        if (isFirstAttempt) {
            val player = _exoPlayer
            if (player != null) {
                if (savedPosition > 0L) {
                    player.seekTo((savedPosition - 1).coerceAtLeast(0L))
                }
                player.prepare()
                player.playWhenReady = !paused
            } else {
                releasePlayer(flushPlaybackState = false)
                if (savedPosition > 0L) {
                    _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
                }
                initializePlayer(currentStreamUrl, currentHeaders)
            }
        } else {
            releasePlayer(flushPlaybackState = false)
            if (savedPosition > 0L) {
                _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
            }
            initializePlayer(currentStreamUrl, currentHeaders)
        }
    }
    return true
}

internal fun PlayerRuntimeController.attemptNextSourceStream(): Boolean {
    // Don't advance streams if current stream is a debrid/download stream
    if (isCurrentStreamDebridOrDownload()) {
        android.util.Log.d("PlayerRecovery", "Current stream is debrid/download — skipping auto-advance")
        return false
    }
    val streams = _uiState.value.sourceAllStreams
    android.util.Log.d("PlayerRecovery", "attemptNextSourceStream: ${streams.size} streams available, currentIndex=$autoAdvanceStreamIndex")

    val nextIndex = autoAdvanceStreamIndex + 1

    // If streams not loaded yet, fetch them then retry
    if (streams.isEmpty()) {
        android.util.Log.d("PlayerRecovery", "Streams not loaded — fetching on demand before trying next source")
        errorRetryJob?.cancel()
        errorRetryJob = scope.launch {
            _uiState.update {
                it.copy(
                    error = null,
                    showLoadingOverlay = it.loadingOverlayEnabled,
                    loadingMessage = context.getString(R.string.player_loading_trying_next_source),
                    showPauseOverlay = false
                )
            }
            loadSourceStreams(forceRefresh = false)
            // Wait for streams to populate (max 10s)
            var waited = 0
            while (_uiState.value.sourceAllStreams.isEmpty() && waited < 10_000) {
                delay(500L)
                waited += 500
            }
            val loadedStreams = _uiState.value.sourceAllStreams
            android.util.Log.d("PlayerRecovery", "On-demand stream load complete: ${loadedStreams.size} streams")
            if (loadedStreams.isEmpty() || nextIndex >= MAX_AUTO_ADVANCE_STREAMS || nextIndex >= loadedStreams.size) {
                android.util.Log.w("PlayerRecovery", "No streams available after load — giving up")
                _uiState.update { it.copy(showLoadingOverlay = false, loadingMessage = null) }
                return@launch
            }
            autoAdvanceStreamIndex = nextIndex
            val nextStream = loadedStreams[nextIndex]
            android.util.Log.d("PlayerRecovery", "Switching to source $nextIndex: ${nextStream.name ?: nextStream.getStreamUrl()?.take(60)}")
            errorRetryCount = 0
            startupRetryCount = 0
            hasRenderedFirstFrame = false
            switchToSourceStream(nextStream)
        }
        return true
    }

    if (nextIndex >= MAX_AUTO_ADVANCE_STREAMS || nextIndex >= streams.size) {
        android.util.Log.w("PlayerRecovery", "All $MAX_AUTO_ADVANCE_STREAMS sources exhausted — showing error")
        return false
    }

    autoAdvanceStreamIndex = nextIndex
    val nextStream = streams[nextIndex]
    android.util.Log.d("PlayerRecovery", "Switching to source $nextIndex: ${nextStream.name ?: nextStream.getStreamUrl()?.take(60)}")

    errorRetryCount = 0
    startupRetryCount = 0
    hasRenderedFirstFrame = false

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        _uiState.update {
            it.copy(
                error = null,
                showLoadingOverlay = it.loadingOverlayEnabled,
                loadingMessage = context.getString(R.string.player_loading_trying_next_source),
                showPauseOverlay = false
            )
        }
        delay(RETRY_DELAY_MS)
        switchToSourceStream(nextStream)
    }
    return true
}

internal fun PlayerRuntimeController.isCurrentStreamDebridOrDownload(): Boolean {
    val streams = _uiState.value.sourceAllStreams
    val currentStream = streams.getOrNull(autoAdvanceStreamIndex)
    if (currentStream != null) {
        if (currentStream.isTorrent()) return true
        if (currentStream.isExternal()) return true
        if (currentStream.behaviorHints?.notWebReady == true) return true
    }
    // Also check current URL against known debrid patterns
    val url = currentStreamUrl.lowercase()
    return url.contains("torbox") || url.contains("debrid") || url.contains("alldebrid") ||
        url.contains("realdebrid") || url.contains("premiumize") || url.contains("offcloud")
}

internal fun PlayerRuntimeController.resetErrorRetryState() {
    startupRetryCount = 0
    errorRetryCount = 0
    autoAdvanceStreamIndex = 0
    pendingAudioPcmFallbackRebuild = false
    errorRetryJob?.cancel()
    errorRetryJob = null
}

internal fun PlayerRuntimeController.scheduleStableProgressReset() {
    stableProgressResetJob?.cancel()
    stableProgressResetJob = scope.launch {
        delay(STABLE_PROGRESS_RESET_DELAY_MS)
        val player = _exoPlayer ?: return@launch
        if (player.playbackState == Player.STATE_READY && player.isPlaying) {
            resetErrorRetryState()
        }
    }
}

internal fun PlayerRuntimeController.cancelStableProgressReset() {
    stableProgressResetJob?.cancel()
    stableProgressResetJob = null
}

internal fun PlayerRuntimeController.refreshStableProgressResetGate() {
    if (!hasRenderedFirstFrame) return
    val player = _exoPlayer ?: return
    val healthy = player.playbackState == Player.STATE_READY && player.isPlaying
    if (healthy) {
        if (stableProgressResetJob?.isActive != true) {
            scheduleStableProgressReset()
        }
    } else {
        cancelStableProgressReset()
    }
}
