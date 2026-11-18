package com.nuvio.tv.ui.screens.player

import android.app.Activity
import com.nuvio.tv.core.diagnostics.PlaybackTrace
import java.lang.ref.WeakReference

internal fun PlayerRuntimeController.attachHostActivity(activity: Activity?) {
    hostActivityRef = activity?.let { WeakReference(it) }
}

internal fun PlayerRuntimeController.startInitialPlaybackIfNeeded() {
    if (initialPlaybackStarted) return

    initialPlaybackStarted = true
    PlaybackTrace.record(
        "initial_playback_request",
        "contentId" to contentId,
        "videoId" to currentVideoId,
        "season" to currentSeason,
        "episode" to currentEpisode,
        "episodeTitle" to currentEpisodeTitle,
        "filename" to currentFilename,
        "linkHash" to PlaybackTrace.fingerprint(currentStreamUrl),
        "torrentHash" to PlaybackTrace.fingerprint(currentInfoHash),
        "fileIdx" to currentFileIdx,
        "addon" to currentAddonName
    )
    preparePlaybackBeforeStart(
        url = currentStreamUrl,
        headers = currentHeaders,
        loadSavedProgress = false,
        preloadSavedProgressBeforeInitialize =
            !navigationArgs.startFromBeginning
    )
}

internal fun PlayerRuntimeController.currentHostActivity(): Activity? {
    return hostActivityRef?.get()
}
