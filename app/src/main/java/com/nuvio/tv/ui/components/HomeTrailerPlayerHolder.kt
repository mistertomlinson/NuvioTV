package com.nuvio.tv.ui.components

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Singleton
class HomeTrailerPlayerHolder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val player: ExoPlayer by lazy { createPlayer() }

    // Expanded cards have a separate lifetime from the hero and popup players.
    // Keep one lazy instance so leaving a card doesn't synchronously release
    // ExoPlayer on the UI thread. TrailerPlayer still stops/clears each media item.
    val expandedCardPlayer: ExoPlayer by lazy { createPlayer() }

    private fun createPlayer(): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 120_000,
                /* bufferForPlaybackMs = */ 5_000,
                /* bufferForPlaybackAfterRebufferMs = */ 10_000
            )
            .build()
        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_OFF
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            }
    }
}
