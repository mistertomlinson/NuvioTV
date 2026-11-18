package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Stream

internal data class StreamAddonFilterResult(
    val selectedAddon: String?,
    val streams: List<Stream>
)

internal fun resolveStreamAddonFilter(
    allStreams: List<Stream>,
    availableAddons: List<String>,
    selectedAddon: String?
): StreamAddonFilterResult {
    val availableSelection = selectedAddon?.takeIf { it in availableAddons }
    return StreamAddonFilterResult(
        selectedAddon = availableSelection,
        streams = if (availableSelection == null) {
            allStreams
        } else {
            allStreams.filter { it.addonName == availableSelection }
        }
    )
}

internal fun findCurrentStreamIndex(
    streams: List<Stream>,
    currentSourceStreamKey: String?,
    currentStreamInfoHash: String?,
    currentStreamFileIdx: Int?,
    currentStreamUrl: String?,
    currentStreamName: String?,
    currentStreamAddonName: String?,
    currentStreamDescription: String?
): Int {
    if (streams.isEmpty()) return -1

    if (!currentSourceStreamKey.isNullOrBlank()) {
        streams.indexOfFirst { it.stableKey() == currentSourceStreamKey }
            .takeIf { it >= 0 }?.let { return it }
    }

    val expectedHash = currentStreamInfoHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    if (expectedHash != null) {
        val matches = streams.indices.filter { index ->
            val stream = streams[index]
            val hash = (stream.infoHash ?: stream.clientResolve?.infoHash)?.trim()?.lowercase()
            val fileIdx = stream.fileIdx ?: stream.clientResolve?.fileIdx
            hash == expectedHash && (currentStreamFileIdx == null || fileIdx == currentStreamFileIdx)
        }
        // Hashes identify a torrent, not an episode. An unknown file index
        // cannot select the first of several different files in the pack.
        val distinctFiles = matches.map { streams[it].fileIdx ?: streams[it].clientResolve?.fileIdx }.distinct()
        if (matches.isNotEmpty() && (currentStreamFileIdx != null || matches.size == 1 ||
                (distinctFiles.size == 1 && distinctFiles.single() != null))
        ) return matches.first()
    }

    if (!currentStreamUrl.isNullOrBlank()) {
        val matches = streams.indices.filter { streams[it].getStreamUrl() == currentStreamUrl }
        if (matches.size == 1) return matches.first()
        val presentationMatches = matches.filter { index ->
            val stream = streams[index]
            val addonMatches = !currentStreamAddonName.isNullOrBlank() &&
                stream.addonName.trim().equals(currentStreamAddonName.trim(), ignoreCase = true)
            val nameMatches = !currentStreamName.isNullOrBlank() &&
                listOfNotNull(stream.name, stream.getDisplayName()).any {
                    it.trim().equals(currentStreamName.trim(), ignoreCase = true)
                }
            val descriptionMatches = !currentStreamDescription.isNullOrBlank() &&
                stream.getDisplayDescription()?.trim()?.equals(
                    currentStreamDescription.trim(), ignoreCase = true
                ) == true
            addonMatches && (nameMatches || descriptionMatches)
        }
        if (presentationMatches.size == 1) return presentationMatches.first()
    }

    // Generic labels, release descriptions and file indices can all be shared
    // by different sources. None can establish playback identity by itself.
    return -1
}

internal fun playingSourceDescription(stream: Stream, playingFilename: String?): String? {
    val filename = playingFilename?.takeIf { it.isNotBlank() } ?: return stream.getDisplayDescription()
    val description = stream.getDisplayDescription() ?: return filename
    val advertisedFilename = stream.behaviorHints?.filename
        ?: stream.clientResolve?.filename
        ?: stream.clientResolve?.stream?.raw?.filename
    return if (!advertisedFilename.isNullOrBlank() && description.contains(advertisedFilename)) {
        description.replace(advertisedFilename, filename)
    } else filename
}
