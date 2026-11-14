package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.remote.dto.TorboxTorrentFileDto
import com.nuvio.tv.domain.model.StreamClientResolve
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TorboxFileSelector @Inject constructor() {
    fun selectFile(
        files: List<TorboxTorrentFileDto>,
        resolve: StreamClientResolve,
        season: Int?,
        episode: Int?,
        selectionContext: DebridEpisodeSelectionContext? = null
    ): TorboxTorrentFileDto? {
        val fileNames = files.map { it.displayName() }
        val isBluray = fileNames.isBlurayDiscStructure()
        val playable = files.filter { it.isPlayableVideo(isBluray) }.sortedByDescending { it.size ?: 0L }
        if (playable.isEmpty()) return null

        val requestedSeason = season ?: resolve.season
        val requestedEpisode = episode ?: resolve.episode
        val isEpisode = requestedSeason != null && requestedEpisode != null
        val targetContext = DebridEpisodeSelectionContext(
            season = requestedSeason,
            episode = requestedEpisode,
            title = selectionContext?.title,
            seasonEpisodeTitles = selectionContext?.seasonEpisodeTitles.orEmpty()
        )
        // Numeric mismatches and known-other episode titles are ineligible,
        // including when a pack's fileIndex or filename metadata points at them.
        val eligible = playable.withoutDebridEpisodeTitleConflicts(targetContext) { it.displayName() }
        if (eligible.isEmpty()) return null

        eligible.firstDebridTargetEpisodeTitleMatch(targetContext) { it.displayName() }
            ?.let { return it }

        val episodePatterns = buildDebridEpisodePatterns(requestedSeason, requestedEpisode)
        val names = resolve.specificDebridFileNames(episodePatterns)
        if (names.isNotEmpty()) {
            eligible.firstDebridNameMatch(
                names, { it.displayName() }, requireExact = isEpisode
            )?.let { return it }
        }

        // Stremio fileIdx is zero-based within the original torrent file list.
        // It is not a provider-specific file ID and includes non-video entries.
        resolve.fileIdx?.let { fileIdx ->
            val indexedFile = files.getOrNull(fileIdx)?.takeIf { it in eligible }
            if (indexedFile != null) return indexedFile
            // A bad/mismatching episode index is not permission to guess a file.
            if (isEpisode) return null
        }

        // If a multi-video episode pack has no trustworthy match, do not
        // silently play its largest (often wrong) episode. Try another source.
        if (isEpisode) return if (playable.size == 1) eligible.singleOrNull() else null

        // Retain movie/single-video behavior when no episode was requested.
        return eligible.maxByOrNull { it.size ?: 0L }
    }

    private fun TorboxTorrentFileDto.isPlayableVideo(isBluray: Boolean = false): Boolean {
        val mime = mimeType.orEmpty().lowercase()
        if (mime.startsWith("video/")) {
            if (isBluray && displayName().lowercase().endsWith(".m2ts")) return false
            return true
        }
        val name = displayName().lowercase()
        if (isBluray && name.endsWith(".m2ts")) return false
        return name.hasDebridVideoExtension()
    }
}
