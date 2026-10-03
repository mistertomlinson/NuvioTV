package com.nuvio.tv.ui.screens.player

import android.content.Intent
import android.media.audiofx.AudioEffect

internal fun PlayerRuntimeController.releasePlayer() {
    releasePlayer(
        flushPlaybackState = true,
        creditBoundaryGraceMs = 0L,
        allowPercentageFallback = false
    )
}

internal fun PlayerRuntimeController.releasePlayer(
    flushPlaybackState: Boolean,
    creditBoundaryGraceMs: Long = 0L,
    allowPercentageFallback: Boolean = false
) {
    if (flushPlaybackState) {
        flushPlaybackSnapshotForSwitchOrExit(
            creditBoundaryGraceMs = creditBoundaryGraceMs,
            allowPercentageFallback = allowPercentageFallback
        )
    }

    notifyAudioSessionUpdate(false)

    try {
        currentMediaSession?.release()
        currentMediaSession = null
    } catch (e: Exception) {
        e.printStackTrace()
    }
    progressJob?.cancel()
    hideControlsJob?.cancel()
    watchProgressSaveJob?.cancel()
    seekProgressSyncJob?.cancel()
    frameRateProbeJob?.cancel()
    hideStreamSourceIndicatorJob?.cancel()
    hideSubtitleDelayOverlayJob?.cancel()
    playbackPreparationJob?.cancel()
    playbackPreparationJob = null
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    postPlayTrailerCountdownJob?.cancel()
    postPlayTrailerCountdownJob = null
    ratingTransitionJob?.cancel()
    ratingTransitionJob = null
    _exoPlayer?.release()
    _exoPlayer = null
}

internal fun PlayerRuntimeController.notifyAudioSessionUpdate(active: Boolean) {
    _exoPlayer?.let { player ->
        try {
            val intent = Intent(
                if (active) AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
                else AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION
            )
            intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
            intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            if (active) {
                intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MOVIE)
            }
            context.sendBroadcast(intent)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}
