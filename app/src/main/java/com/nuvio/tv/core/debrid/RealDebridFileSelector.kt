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

        playable.firstDebridTargetEpisodeTitleMatch(selectionContext) { it.displayName() }
            ?.let { return it }

        val eligible = playable.withoutDebridEpisodeTitleConflicts(selectionContext) { it.displayName() }
        if (eligible.isEmpty()) return null

        val episodePatterns = buildDebridEpisodePatterns(
            season = season ?: resolve.season,
            episode = episode ?: resolve.episode
        )
        val names = resolve.specificDebridFileNames(episodePatterns)
        if (names.isNotEmpty()) {
            eligible.firstDebridNameMatch(names) { it.displayName() }?.let { return it }
        }

        if (episodePatterns.isNotEmpty()) {
            eligible.firstOrNull { file ->
                val fileName = file.displayName().lowercase()
                episodePatterns.any { pattern -> fileName.contains(pattern) }
            }?.let { return it }
        }

        resolve.fileIdx?.let { fileIdx ->
            eligible.firstOrNull { it.id == fileIdx }?.let { return it }
            if (fileIdx > 0) eligible.firstOrNull { it.id == fileIdx - 1 }?.let { return it }
        }

        return eligible.maxByOrNull { it.bytes ?: 0L }
    }

    private fun RealDebridTorrentFileDto.isPlayableVideo(): Boolean {
        val name = displayName().lowercase()
        return name.hasDebridVideoExtension()
    }
}
