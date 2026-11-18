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

        val target = selectionContext.withDebridEpisodeTarget(season ?: resolve.season, episode ?: resolve.episode)
        playable.firstDebridTargetEpisodeTitleMatch(target) { it.displayName() }
            ?.let { return it }
        if (target.requireTitleMatch) return null

        val eligible = playable.withoutDebridEpisodeTitleConflicts(target) { it.displayName() }
        if (eligible.isEmpty()) return null

        val episodePatterns = buildDebridEpisodePatterns(
            season = target.season,
            episode = target.episode
        )
        val names = resolve.specificDebridFileNames(episodePatterns)
        if (names.isNotEmpty()) {
            eligible.firstDebridNameMatch(names) { it.displayName() }?.let { return it }
        }

        if (episodePatterns.isNotEmpty()) {
            eligible.firstOrNull { file ->
                file.displayName().matchesDebridEpisodeNumber(target.season, target.episode)
            }?.let { return it }
        }

        resolve.fileIdx?.let { fileIdx ->
            eligible.firstOrNull { it.id == fileIdx }?.let { return it }
            if (fileIdx > 0) eligible.firstOrNull { it.id == fileIdx - 1 }?.let { return it }
        }

        if (target.season != null && target.episode != null) return null
        return eligible.maxByOrNull { it.bytes ?: 0L }
    }

    private fun RealDebridTorrentFileDto.isPlayableVideo(): Boolean {
        val name = displayName().lowercase()
        return name.hasDebridVideoExtension()
    }
}
