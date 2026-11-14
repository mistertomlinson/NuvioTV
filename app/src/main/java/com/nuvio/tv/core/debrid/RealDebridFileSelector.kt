package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.remote.dto.RealDebridTorrentFileDto
import com.nuvio.tv.domain.model.StreamClientResolve
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealDebridFileSelector @Inject constructor() {
    fun selectFile(
        files: List<RealDebridTorrentFileDto>,
        resolve: StreamClientResolve,
        season: Int?,
        episode: Int?,
        selectionContext: DebridEpisodeSelectionContext? = null
    ): RealDebridTorrentFileDto? {
        val playable = files.filter { it.isPlayableVideo() }
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
            files.getOrNull(fileIdx)?.takeIf { it in eligible }?.let { return it }
        }

        // If a multi-video episode pack has no trustworthy match, do not
        // silently play its largest (often wrong) episode. Try another source.
        if (isEpisode) return if (playable.size == 1) eligible.singleOrNull() else null

        // Retain movie/single-video behavior when no episode was requested.
        return eligible.maxByOrNull { it.bytes ?: 0L }
    }

    private fun RealDebridTorrentFileDto.isPlayableVideo(): Boolean {
        val name = displayName().lowercase()
        return name.hasDebridVideoExtension()
    }
}
